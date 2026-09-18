package com.sunshinesend.app.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.sunshinesend.app.R
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * 内置 HTTP 服务：接收网页端上传并提供静态上传页。
 *
 * 路由一览（端口 [PORT]）：
 * | 方法 | 路径           | 说明                                         |
 * |------|----------------|----------------------------------------------|
 * | POST | /api/upload    | 接收文件（multipart，字段名 file），写盘后回调 |
 * | GET  | /api/progress?file=xxx | 查询单个文件的上传进度（0-100）        |
 * | GET  | /api/files     | 返回上传目录文件清单（JSON 数组）             |
 * | GET  | 其他           | 返回打包在 res/raw 里的上传页 index.html      |
 *
 * 进度统计通过反射向 NanoHTTPD 会话注入包装输入流实现，
 * UI 回调统一切换到主线程后再分发。
 *
 * 使用示例：
 * ```kotlin
 * val server = SimpleServer(context, listener).also { it.start() }
 * // 网页端访问 http://{设备IP}:9527 扫码上传
 * server.stop()   // 页面销毁时
 * ```
 *
 * @param context 用于读取上传页资源与定位上传目录
 * @param listener 上传生命周期回调（回调已在主线程）
 */
class SimpleServer(
    private val context: Context,
    private val listener: ServerListener
) : NanoHTTPD("0.0.0.0", PORT) {

    /**
     * 上传生命周期回调。所有方法均保证在主线程被调用。
     */
    interface ServerListener {
        /**
         * 上传开始（已收到请求头，尚未写入数据）。
         * @param fileName 安全化处理后的文件名
         * @param fileSize 声明的文件大小（来自 Content-Length，可能为 0）
         */
        fun onUploadStart(fileName: String, fileSize: Long)

        /**
         * 上传进度更新（约 100ms 节流一次）。
         * @param fileName 文件名
         * @param progress 进度百分比 0-100
         * @param bytesReceived 已接收字节数
         * @param totalBytes 总字节数
         */
        fun onUploadProgress(fileName: String, progress: Int, bytesReceived: Long, totalBytes: Long)

        /**
         * 上传完成，文件已写入上传目录。
         * @param fileName 文件名
         * @param file 已落盘的文件
         */
        fun onUploadComplete(fileName: String, file: File)

        /**
         * 上传失败（未收到有效数据或服务端异常）。
         * @param fileName 文件名
         * @param error 失败原因描述
         */
        fun onUploadError(fileName: String, error: String)

        /**
         * 上传被取消（网页端取消或连接中断）。
         * @param fileName 文件名
         */
        fun onUploadCancelled(fileName: String)
    }

    companion object {
        /** 服务固定监听端口 */
        const val PORT = 9527

        private const val TAG = "SimpleServer"

        /** 全部进行中上传的进度表：文件名 -> 进度（0-100），供 /api/progress 查询 */
        private val uploadProgress = ConcurrentHashMap<String, Int>()
    }

    private val handler = Handler(Looper.getMainLooper())

    /** 上传文件目录：app 外部私有目录/Uploads，与 FileRepository 保持一致 */
    private val uploadDir: File by lazy {
        File(context.getExternalFilesDir(null), "Uploads").apply {
            if (!exists()) mkdirs()
        }
    }

    /** 请求分发：按「路径 + 方法」路由到对应处理器 */
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.d(TAG, "serve: $method $uri")

        return when {
            uri == "/api/upload" && method == Method.POST -> handleUpload(session)
            uri == "/api/progress" -> handleProgress(getQueryParam(session, "file"))
            uri == "/api/files" -> handleFileList()
            else -> handleStaticContent()
        }
    }

    /**
     * 处理文件上传：解析文件名 -> 注入进度追踪 -> 落盘 -> 回调结果。
     *
     * 任何异常都会被转换为「取消」或「失败」回调：
     * - IO 异常或进度未完成即中断，视为用户取消；
     * - 其余情况视为上传失败。
     *
     * @param session NanoHTTPD 会话（multipart body，文件字段为 "file"）
     * @return JSON 响应：`{"success":true/false, ...}`
     */
    private fun handleUpload(session: IHTTPSession): Response {
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        val safeName = resolveFileName(session)
        notify { listener.onUploadStart(safeName, contentLength) }

        injectProgressTracker(session, safeName, contentLength)

        return try {
            val tmpFiles = HashMap<String, String>()
            session.parseBody(tmpFiles)

            Log.d(TAG, "upload: $safeName size=$contentLength tmpFiles=$tmpFiles")
            val tmpFile = File(tmpFiles["file"] ?: "")
            if (tmpFile.exists() && tmpFile.length() > 0) {
                saveUploadedFile(tmpFile, safeName)
                jsonOk("""{"success":true,"fileName":"${safeName.replace("\"", "\\\"")}"}""")
            } else {
                Log.e(TAG, "upload error: no temp file or empty")
                notifyCancelledIfInProgress(safeName, "No file received")
                jsonError("No file received")
            }
        } catch (e: Exception) {
            Log.e(TAG, "upload error", e)
            notifyCancelledIfInProgress(safeName, e.message ?: "Unknown error")
            jsonError(e.message ?: "Unknown error")
        } finally {
            uploadProgress.remove(safeName)
        }
    }

    /**
     * 从请求中解析并安全化文件名。
     *
     * 文件名优先取 query 参数 `filename`（网页端约定），
     * 缺失时用时间戳兜底；随后过滤掉白名单之外的字符，
     * 防止路径穿越与非法字符破坏文件系统。
     */
    private fun resolveFileName(session: IHTTPSession): String {
        val fileName = session.parms["filename"]
            ?: "unknown_${System.currentTimeMillis()}"
        return fileName.replace(Regex("[^a-zA-Z0-9._\\-一-鿿 ()]"), "_")
    }

    /**
     * 将 NanoHTTPD 落好的临时文件转移到上传目录的最终位置（同名覆盖），
     * 成功后回调 [ServerListener.onUploadComplete]。
     */
    private fun saveUploadedFile(tmpFile: File, safeName: String) {
        val finalFile = File(uploadDir, safeName)
        if (finalFile.exists()) finalFile.delete()
        tmpFile.copyTo(finalFile, overwrite = true)
        tmpFile.delete()
        Log.d(TAG, "upload complete: $safeName size=${finalFile.length()}")
        notify { listener.onUploadComplete(safeName, finalFile) }
    }

    /**
     * 按进度决定上报「取消」还是「失败」：
     * 已产生部分进度且未到 100% 的视为取消，否则视为失败。
     *
     * @param safeName 文件名
     * @param error 失败原因（失败回调使用）
     */
    private fun notifyCancelledIfInProgress(safeName: String, error: String) {
        val progress = uploadProgress[safeName] ?: 0
        if (progress > 0 && progress < 100) {
            notify { listener.onUploadCancelled(safeName) }
        } else {
            notify { listener.onUploadError(safeName, error) }
        }
    }

    /**
     * 处理进度查询：`GET /api/progress?file=xxx`。
     * @return `{"progress":0-100}`；未知文件返回 0
     */
    private fun handleProgress(fileName: String): Response {
        return jsonOk("""{"progress":${uploadProgress[fileName] ?: 0}}""")
    }

    /**
     * 处理文件清单查询：`GET /api/files`。
     * @return JSON 数组 `[{"name":...,"size":...,"time":...}]`，过滤临时文件
     */
    private fun handleFileList(): Response {
        val files = uploadDir.listFiles()?.filter { !it.name.contains(".tmp_") }?.map { file ->
            """{"name":"${
                file.name.replace(
                    "\"",
                    "\\\""
                )
            }","size":${file.length()},"time":${file.lastModified()}}"""
        } ?: emptyList()

        return jsonOk("[${files.joinToString(",")}]")
    }

    /**
     * 返回打包在 res/raw/index.html 的上传页。
     * @return HTML 响应；资源读取失败时返回 500
     */
    private fun handleStaticContent(): Response {
        return try {
            val html = context.resources.openRawResource(R.raw.index).bufferedReader().readText()
            Log.d(TAG, "serving index.html size=${html.length}")
            newFixedLengthResponse(Response.Status.OK, "text/html", html)
        } catch (e: Exception) {
            Log.e(TAG, "error serving index", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Server Error")
        }
    }

    /**
     * 通过反射向 NanoHTTPD 会话注入进度追踪输入流。
     *
     * NanoHTTPD 内部直接持有原始 inputStream 字段，这里将其替换为
     * 计数包装流：每次 read 累加字节数，按约 100ms 节流更新
     * [uploadProgress] 并回调 [ServerListener.onUploadProgress]。
     * 注入失败（部分 Android 版本限制反射）仅影响进度展示，不影响上传。
     *
     * @param session 目标会话
     * @param fileName 用于进度表 key 与回调的文件名
     * @param totalBytes 声明的总字节数（用于计算百分比）
     */
    private fun injectProgressTracker(session: IHTTPSession, fileName: String, totalBytes: Long) {
        try {
            val sessionClass = session.javaClass
            val inputStreamField = sessionClass.getDeclaredField("inputStream")
            inputStreamField.isAccessible = true

            // 移除 final 修饰，便于替换字段值；个别系统版本可能不允许，允许失败
            try {
                val modifiersField = Field::class.java.getDeclaredField("modifiers")
                modifiersField.isAccessible = true
                modifiersField.setInt(inputStreamField, inputStreamField.modifiers and Modifier.FINAL.inv())
            } catch (e: Exception) {
                Log.w(TAG, "Remove final modifier failed (may be fine): $e")
            }

            val originalStream = inputStreamField.get(session) as InputStream

            var bytesRead: Long = 0
            var lastUpdateTime: Long = 0

            val trackedStream = object : BufferedInputStream(originalStream) {
                override fun read(): Int {
                    val b = super.read()
                    if (b != -1) updateProgress(1)
                    return b
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val read = super.read(b, off, len)
                    if (read != -1) updateProgress(read.toLong())
                    return read
                }

                /** 累加读取字节数，节流刷新进度表并回调 UI */
                private fun updateProgress(delta: Long) {
                    bytesRead += delta
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUpdateTime > 100 || bytesRead >= totalBytes) {
                        lastUpdateTime = currentTime
                        val progress = if (totalBytes > 0) {
                            (bytesRead * 100 / totalBytes).toInt().coerceIn(0, 100)
                        } else 0
                        uploadProgress[fileName] = progress
                        handler.post {
                            listener.onUploadProgress(fileName, progress, bytesRead, totalBytes)
                        }
                    }
                }
            }
            inputStreamField.set(session, trackedStream)
            Log.d(TAG, "Successfully injected progress tracker for $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject progress tracker", e)
        }
    }

    /** 从 URI 中解析指定 query 参数（手工解析，避免对 NanoHTTPD 内部实现的依赖） */
    private fun getQueryParam(session: IHTTPSession, key: String): String {
        val uri = session.uri
        val queryIdx = uri.indexOf("?")
        if (queryIdx < 0) return ""
        val queryStr = uri.substring(queryIdx + 1)
        for (pair in queryStr.split("&")) {
            val idx = pair.indexOf("=")
            if (idx > 0 && pair.substring(0, idx) == key) {
                return try {
                    java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                } catch (e: Exception) {
                    pair.substring(idx + 1)
                }
            }
        }
        return ""
    }

    /** 在主线程执行回调（NanoHTTPD 工作线程 -> UI 线程） */
    private fun notify(block: () -> Unit) = handler.post(block)

    /** 构造 200 JSON 响应 */
    private fun jsonOk(body: String) =
        newFixedLengthResponse(Response.Status.OK, "application/json", body)

    /** 构造 500 JSON 响应，error 字段做 JSON 转义 */
    private fun jsonError(error: String) = newFixedLengthResponse(
        Response.Status.INTERNAL_ERROR,
        "application/json",
        """{"success":false,"error":"${error.replace("\"", "\\\"")}"}"""
    )
}
