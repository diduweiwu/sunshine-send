package com.sunshinesend.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 远端最新版本信息（解析自 GitHub Release）。
 *
 * @property tagName Release 标签名，如 "v1.0.3"
 * @property versionName 版本名（当前即 tagName）
 * @property versionCode 由版本名换算出的可比较版本号
 * @property downloadUrl APK 下载地址（已拼接代理前缀）
 * @property releaseNote 更新说明
 */
data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val releaseNote: String
)

/**
 * 应用在线更新管理器（单例）。
 *
 * 职责：检查新版本 -> 多线程下载 APK（3 线程分片 + 断点不保留，整体重下）
 * -> 校验完整性 -> 拉起安装；支持取消下载与权限引导。
 *
 * 典型用法（MainActivity）：
 * ```kotlin
 * if (!UpdateManager.hasUpdatePermission(context)) { /* 引导去设置 */ }
 * UpdateManager.checkForUpdate(context, progressListener, checkCallback)
 * // 有新版本时：
 * UpdateManager.startUpdate(context, updateInfo, progressListener)
 * // 用户取消：
 * UpdateManager.cancelDownload(context)
 * ```
 *
 * 所有回调均保证在主线程执行。
 */
object UpdateManager {

    private const val TAG = "UpdateManager"

    /** GitHub 最新 Release API 地址 */
    private const val API_URL =
        "https://api.github.com/repos/diduweiwu/sunshine-send/releases/latest"

    /** GitHub 资源下载代理前缀（国内网络加速） */
    private const val GH_PROXY = "https://gh-proxy.org/"

    /** 元信息请求超时（毫秒） */
    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 15000

    /** APK 最小合法体积，低于此值视为无效文件 */
    private const val MIN_APK_SIZE = 1024 * 1024L

    /** 多线程下载的分片数 */
    private const val THREAD_COUNT = 3

    /** 进度回调节流间隔（毫秒） */
    private const val PROGRESS_INTERVAL_MS = 300L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 当前进度回调（下载过程中异步线程引用，注意线程安全） */
    private var progressListener: ProgressListener? = null
    private var currentApkFile: File? = null
    private var downloadScope: CoroutineScope? = null
    private var isDownloading = false

    /** 下载过程进度回调，所有方法均在主线程被调用 */
    interface ProgressListener {
        /** 进度文本更新（含百分比与已下载/总大小） */
        fun onProgress(text: String)

        /** 开始下载（进入下载流程） */
        fun onDownloadStart()

        /** 下载完成（随后会自动拉起安装） */
        fun onDownloadComplete()

        /** 下载失败 */
        fun onDownloadFailed()

        /** 下载被用户取消 */
        fun onDownloadCanceled()
    }

    /** 版本检查结果回调，所有方法均在主线程被调用 */
    interface CheckCallback {
        /** 发现新版本 */
        fun onUpdateAvailable(updateInfo: UpdateInfo)

        /** 已是最新版本 */
        fun onNoUpdate()

        /** 检查失败（网络错误等） */
        fun onError(error: String)
    }

    /**
     * 检查是否有新版本。
     *
     * 在 IO 线程请求 GitHub Release API，与本地 versionCode 比较：
     * 远端更新则回调 [CheckCallback.onUpdateAvailable]，否则 [CheckCallback.onNoUpdate]，
     * 请求失败回调 [CheckCallback.onError]。结果均切回主线程。
     *
     * @param context 任意 Context
     * @param listener 过程进度回调（检查阶段基本不产生事件，保留给下载复用）
     * @param callback 检查结果回调
     */
    fun checkForUpdate(context: Context, listener: ProgressListener, callback: CheckCallback) {
        progressListener = listener
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val info = fetchLatestRelease()
                if (info == null) {
                    withContext(Dispatchers.Main) { callback.onError("无法获取版本信息") }
                    return@launch
                }

                val currentVersionCode = getCurrentVersionCode(context)
                val currentVersionName = getCurrentVersionName(context)

                Log.d(
                    TAG,
                    "Remote: ${info.versionName} (${info.versionCode}), Current: $currentVersionName ($currentVersionCode)"
                )

                withContext(Dispatchers.Main) {
                    if (info.versionCode > currentVersionCode) {
                        callback.onUpdateAvailable(info)
                    } else {
                        callback.onNoUpdate()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Check update failed", e)
                withContext(Dispatchers.Main) {
                    callback.onError(e.message ?: "网络错误，请检查网络连接")
                }
            }
        }
    }

    /**
     * 请求并解析 GitHub 最新 Release 信息。
     *
     * 挂起函数，内部已切换到 IO 线程；任何失败均返回 null 而不抛异常。
     * 下载地址会拼接 [GH_PROXY] 代理前缀，并按设备 ABI 匹配对应 APK
     * （匹配不到时依次回退 universal 包、第一个 apk 资源）。
     *
     * @return 最新版本信息；请求失败或响应不含有效 APK 资源时为 null
     */
    suspend fun fetchLatestRelease(): UpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(API_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
                connection.setRequestProperty("User-Agent", "SunshineSend")

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e(TAG, "HTTP error: ${connection.responseCode}")
                    return@withContext null
                }

                val reader = connection.inputStream.bufferedReader()
                val response = reader.readText()
                reader.close()
                connection.disconnect()

                val jsonObject = Gson().fromJson(response, JsonObject::class.java)
                val tagName = jsonObject.get("tag_name")?.asString ?: return@withContext null
                val releaseNote = jsonObject.get("body")?.asString ?: ""

                UpdateInfo(
                    tagName = tagName,
                    versionName = tagName,
                    versionCode = parseVersionCode(tagName),
                    downloadUrl = "$GH_PROXY${findApkDownloadUrl(jsonObject) ?: return@withContext null}",
                    releaseNote = releaseNote
                )
            } catch (e: Exception) {
                Log.e(TAG, "Fetch release failed", e)
                null
            }
        }
    }

    /**
     * 开始下载并安装新版本。
     *
     * 流程：
     * 1. 定位本地 APK 文件（下载目录 /SunshineSend-{version}.apk）；
     * 2. 本地已有完整 APK 则直接安装（秒装路径）；
     * 3. 先 HEAD 请求获取文件总大小，成功则走 [downloadMultiThread]，
     *    否则回退 [downloadSingleThread]；
     * 4. 下载完成校验大小与 APK 魔数后自动拉起安装。
     *
     * 重复调用时若已有下载在进行则忽略。完成/失败均通过
     * [ProgressListener] 在主线程回调。
     *
     * @param context 任意 Context
     * @param updateInfo 由 [checkForUpdate] 返回的新版本信息
     * @param listener 下载进度回调
     */
    fun startUpdate(context: Context, updateInfo: UpdateInfo, listener: ProgressListener) {
        progressListener = listener
        if (isDownloading) return

        val apkFile = prepareApkFile(context, updateInfo)
        currentApkFile = apkFile

        // 本地已有完整安装包：跳过下载直接安装
        if (apkFile.exists() && isFileComplete(apkFile)) {
            Log.d(TAG, "APK already exists, skip download")
            listener.onDownloadComplete()
            installApk(context, apkFile)
            return
        }
        if (apkFile.exists()) apkFile.delete()

        downloadScope = CoroutineScope(Dispatchers.IO)
        downloadScope?.launch {
            try {
                isDownloading = true
                val totalBytes = requestContentLength(updateInfo.downloadUrl)

                if (totalBytes <= 0) {
                    Log.w(TAG, "Failed to get content length, falling back to single thread")
                    downloadSingleThread(updateInfo, apkFile, context)
                } else {
                    Log.i(TAG, "Starting multi-threaded download: size=$totalBytes")
                    downloadMultiThread(updateInfo, apkFile, context, totalBytes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                isDownloading = false
                withContext(Dispatchers.Main) { progressListener?.onDownloadFailed() }
            }
        }
    }

    /**
     * 准备本地 APK 文件路径并确保下载目录存在。
     * @return 形如 {下载目录}/SunshineSend-{version}.apk 的目标文件
     */
    private fun prepareApkFile(context: Context, updateInfo: UpdateInfo): File {
        val fileName = "SunshineSend-${updateInfo.versionName}.apk"
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        downloadDir?.mkdirs()
        return File(downloadDir, fileName)
    }

    /**
     * HEAD 请求获取下载文件总大小。
     * @return 文件字节数；请求失败或服务端未返回时为 -1
     */
    private fun requestContentLength(url: String): Long {
        val headRequest = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", "SunshineSend")
            .build()
        return client.newCall(headRequest).execute().use { response ->
            if (response.isSuccessful) response.body?.contentLength() ?: -1L else -1L
        }
    }

    /**
     * 多线程分片下载：将文件按 [THREAD_COUNT] 等分为若干 Range 区间，
     * 各线程并发写入同一文件的对应偏移，进度按全局已下载字节聚合上报。
     * 任何分片失败即整体抛异常；全部成功后校验完整性并安装。
     *
     * @param apkFile 目标文件（各分片通过 RandomAccessFile 按偏移写入）
     * @param totalBytes 文件总大小（> 0）
     */
    private suspend fun downloadMultiThread(
        updateInfo: UpdateInfo,
        apkFile: File,
        context: Context,
        totalBytes: Long
    ) {
        val chunkSize = totalBytes / THREAD_COUNT
        val totalRead = AtomicLong(0)
        var lastUpdateTime = 0L

        val deferreds = coroutineScope {
            (0 until THREAD_COUNT).map { i ->
                val start = i * chunkSize
                val end = if (i == THREAD_COUNT - 1) totalBytes - 1 else (i + 1) * chunkSize - 1

                async { downloadChunk(updateInfo.downloadUrl, apkFile, start, end) { read ->
                    val currentTotal = totalRead.addAndGet(read.toLong())
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime > PROGRESS_INTERVAL_MS) {
                        lastUpdateTime = now
                        reportProgress(currentTotal, totalBytes, suffix = " [多线程]")
                    }
                } }
            }
        }
        deferreds.awaitAll()

        if (isFileComplete(apkFile, totalBytes)) {
            isDownloading = false
            withContext(Dispatchers.Main) {
                progressListener?.onDownloadComplete()
                installApk(context, apkFile)
            }
        } else {
            throw Exception("File size mismatch after multi-threaded download")
        }
    }

    /**
     * 下载单个字节区间（[start, end]）并写入 [apkFile] 的对应偏移。
     *
     * @param onBytesRead 每读到一个缓冲区数据后回调（用于聚合上报进度），
     *        参数为本区段新增读取的字节数
     */
    private inline fun downloadChunk(
        url: String,
        apkFile: File,
        start: Long,
        end: Long,
        onBytesRead: (Int) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .header("User-Agent", "SunshineSend")
            .header("Accept", "*/*")
            .header("Connection", "keep-alive")
            .header("Referer", "https://github.com/")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("Range request failed: $response")
            val body = response.body ?: throw Exception("Body is null")

            RandomAccessFile(apkFile, "rw").use { raf ->
                raf.seek(start)
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        raf.write(buffer, 0, bytesRead)
                        onBytesRead(bytesRead)
                    }
                }
            }
        }
    }

    /**
     * 单线程整体下载（多线程获取不到 Content-Length 时的回退路径）。
     * 完成后同样校验完整性并安装；失败回调 [ProgressListener.onDownloadFailed]。
     */
    private suspend fun downloadSingleThread(updateInfo: UpdateInfo, apkFile: File, context: Context) {
        try {
            val request = Request.Builder()
                .url(updateInfo.downloadUrl)
                .header("User-Agent", "SunshineSend")
                .header("Accept", "*/*")
                .header("Referer", "https://github.com/")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Unexpected code $response")

                val body = response.body ?: throw Exception("Response body is null")
                val totalBytes = body.contentLength()

                body.byteStream().use { input ->
                    apkFile.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L
                        var lastUpdate = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > PROGRESS_INTERVAL_MS) {
                                lastUpdate = now
                                reportProgress(totalRead, totalBytes)
                            }
                        }
                    }
                }

                if (isFileComplete(apkFile, totalBytes)) {
                    isDownloading = false
                    withContext(Dispatchers.Main) {
                        progressListener?.onDownloadComplete()
                        installApk(context, apkFile)
                    }
                } else {
                    throw Exception("File size mismatch after download")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Single thread download error", e)
            isDownloading = false
            withContext(Dispatchers.Main) {
                progressListener?.onDownloadFailed()
            }
        }
    }

    /**
     * 节流上报下载进度（进度文本形如「下载中 45% (12MB/27MB)」）。
     * @param downloaded 已下载字节数
     * @param total 总字节数（<= 0 时百分比显示 0）
     * @param suffix 附加到文本末尾的标注（如 " [多线程]"）
     */
    private suspend fun reportProgress(downloaded: Long, total: Long, suffix: String = "") {
        val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
        val downloadedMB = downloaded / (1024 * 1024)
        val totalMB = total / (1024 * 1024)
        withContext(Dispatchers.Main) {
            progressListener?.onProgress("下载中 ${percent}% (${downloadedMB}MB/${totalMB}MB)$suffix")
        }
    }

    /**
     * 校验 APK 文件完整性：文件存在、非空、大小匹配（可选）且为合法 ZIP 魔数。
     *
     * @param apkFile 待校验文件
     * @param expectedSize 期望大小；<= 0 时跳过大小比对
     * @return true 表示文件可用
     */
    private fun isFileComplete(apkFile: File, expectedSize: Long = -1L): Boolean {
        if (!apkFile.exists()) return false
        if (apkFile.length() <= 0) return false
        if (expectedSize > 0 && apkFile.length() != expectedSize) {
            Log.w(TAG, "File size mismatch: expected=$expectedSize, actual=${apkFile.length()}")
            return false
        }
        return isValidApk(apkFile)
    }

    /**
     * 检查文件头是否为 ZIP 魔数（PK\x03\x04），APK 本质是 ZIP 包。
     * @return true 表示魔数匹配
     */
    private fun isValidApk(apkFile: File): Boolean {
        return try {
            apkFile.inputStream().use { input ->
                val header = ByteArray(4)
                if (input.read(header) != 4) return false
                header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                        header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
            }
        } catch (e: Exception) {
            Log.e(TAG, "isValidApk error", e)
            false
        }
    }

    /**
     * 通过 FileProvider 拉起系统安装器安装 APK。
     * 拉起失败时回调 [ProgressListener.onDownloadFailed]。
     */
    private fun installApk(context: Context, apkFile: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Install failed", e)
            progressListener?.onDownloadFailed()
        }
    }

    /**
     * 取消当前下载：终止协程、删除未完成的 APK 文件并回调取消事件。
     * 无下载进行时调用是安全的（幂等）。
     */
    fun cancelDownload(context: Context) {
        downloadScope?.cancel()
        downloadScope = null
        isDownloading = false
        currentApkFile?.let { if (it.exists()) it.delete() }
        currentApkFile = null
        progressListener?.onDownloadCanceled()
    }

    /**
     * 是否拥有「安装未知来源应用」权限。
     * Android 8.0 以下无此权限概念，直接返回 true。
     *
     * @return true 表示可以直接安装 APK
     */
    fun hasUpdatePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /**
     * 构造跳转「安装未知来源应用」授权页的 Intent。
     * @return 可直接 startActivity 的设置页 Intent
     */
    fun getUpdatePermissionIntent(context: Context): Intent {
        return Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * 将 "v1.2.3" 形式的版本名换算为可比较的整数版本号：
     * major * 1000000 + minor * 10000 + patch * 100 + build。
     * 解析失败返回 0（视为最旧版本，从而触发更新提示）。
     */
    private fun parseVersionCode(tagName: String): Int {
        val version = tagName.removePrefix("v").removePrefix("V")
        val cleanVersion = version.split("-").firstOrNull() ?: version
        val parts = cleanVersion.split(".")
        return try {
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val build = parts.getOrNull(3)?.toIntOrNull() ?: 0
            major * 1000000 + minor * 10000 + patch * 100 + build
        } catch (e: Exception) {
            0
        }
    }

    /** 读取本机已安装包的 versionCode，异常时返回 0 */
    private fun getCurrentVersionCode(context: Context): Int {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: Exception) {
            0
        }
    }

    /** 读取本机已安装包的 versionName，异常时返回 "1.0.0" */
    private fun getCurrentVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * 从 Release JSON 的 assets 中挑选适配当前设备 ABI 的 APK 下载地址。
     *
     * 选择优先级：当前 ABI 精确匹配 → universal 包 → 第一个 .apk 资源。
     *
     * @param releaseJson GitHub Release 响应 JSON
     * @return 下载地址；无任何可用 APK 资源时为 null
     */
    private fun findApkDownloadUrl(releaseJson: JsonObject): String? {
        val assets = releaseJson.getAsJsonArray("assets") ?: return null
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        val abiMapping = mapOf(
            "arm64-v8a" to "arm64-v8a",
            "armeabi-v7a" to "armeabi-v7a",
            "x86" to "x86",
            "x86_64" to "x86_64"
        )
        val targetAbi = abiMapping[abi] ?: "arm64-v8a"

        for (asset in assets) {
            val obj = asset.asJsonObject
            val name = obj.get("name")?.asString ?: continue
            val downloadUrl = obj.get("browser_download_url")?.asString ?: continue
            if (name.contains(targetAbi) && name.endsWith(".apk")) {
                return downloadUrl
            }
        }

        for (asset in assets) {
            val obj = asset.asJsonObject
            val name = obj.get("name")?.asString ?: continue
            if (name.contains("universal") && name.endsWith(".apk")) {
                return obj.get("browser_download_url")?.asString
            }
        }

        return assets.firstOrNull()?.asJsonObject?.get("browser_download_url")?.asString
    }
}
