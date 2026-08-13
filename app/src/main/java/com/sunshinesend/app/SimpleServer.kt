package com.sunshinesend.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

class SimpleServer(
    private val context: Context,
    private val listener: ServerListener
) : NanoHTTPD("0.0.0.0", PORT) {

    interface ServerListener {
        fun onUploadStart(fileName: String, fileSize: Long)
        fun onUploadProgress(fileName: String, progress: Int, bytesReceived: Long, totalBytes: Long)
        fun onUploadComplete(fileName: String, file: File)
        fun onUploadError(fileName: String, error: String)
        fun onUploadCancelled(fileName: String)
    }

    companion object {
        const val PORT = 9527
        const val TAG = "SimpleServer"
        private val uploadProgress = ConcurrentHashMap<String, Int>()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val uploadDir: File by lazy {
        File(context.getExternalFilesDir(null), "Uploads").apply {
            if (!exists()) mkdirs()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.d(TAG, "serve: $method $uri")

        return when {
            uri == "/api/upload" && method == Method.POST -> {
                val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
                val fileName = session.parms["filename"]
                    ?: "unknown_${System.currentTimeMillis()}"
                val safeName = fileName.replace(Regex("[^a-zA-Z0-9._\\-一-鿿 ()]"), "_")

                handler.post { listener.onUploadStart(safeName, contentLength) }

                injectProgressTracker(session, safeName, contentLength)
                handleUpload(session, safeName, contentLength)
            }

            uri == "/api/progress" -> handleProgress(getQueryParam(session, "file"))
            uri == "/api/files" -> handleFileList()
            else -> handleStaticContent()
        }
    }

    private fun handleUpload(
        session: IHTTPSession,
        safeName: String,
        contentLength: Long
    ): Response {
        return try {
            val tmpFiles = HashMap<String, String>()
            session.parseBody(tmpFiles)

            Log.d(TAG, "upload: $safeName size=$contentLength tmpFiles=$tmpFiles")

            val tmpFile = File(tmpFiles["file"] ?: "")
            if (tmpFile.exists() && tmpFile.length() > 0) {
                val finalFile = File(uploadDir, safeName)
                if (finalFile.exists()) finalFile.delete()
                tmpFile.copyTo(finalFile, overwrite = true)
                tmpFile.delete()
                Log.d(TAG, "upload complete: $safeName size=${finalFile.length()}")
                handler.post { listener.onUploadComplete(safeName, finalFile) }

                newFixedLengthResponse(
                    Response.Status.OK,
                    "application/json",
                    """{"success":true,"fileName":"${safeName.replace("\"", "\\\"")}"}"""
                )
            } else {
                Log.e(TAG, "upload error: no temp file or empty")
                val progress = uploadProgress[safeName] ?: 0
                if (progress > 0 && progress < 100) {
                    handler.post { listener.onUploadCancelled(safeName) }
                } else {
                    handler.post { listener.onUploadError(safeName, "No file received") }
                }
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"success":false,"error":"No file received"}"""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "upload error", e)
            val errorMsg = e.message ?: "Unknown error"
            
            val progress = uploadProgress[safeName] ?: 0
            if (e is java.io.IOException || (progress > 0 && progress < 100)) {
                handler.post { listener.onUploadCancelled(safeName) }
            } else {
                handler.post { listener.onUploadError(safeName, errorMsg) }
            }
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"success":false,"error":"${errorMsg.replace("\"", "\\\"")}"}"""
            )
        } finally {
            uploadProgress.remove(safeName)
        }
    }

    private fun handleProgress(fileName: String): Response {
        val progress = uploadProgress[fileName] ?: 0
        return newFixedLengthResponse(
            Response.Status.OK,
            "application/json",
            """{"progress":$progress}"""
        )
    }

    private fun injectProgressTracker(session: IHTTPSession, fileName: String, totalBytes: Long) {
        try {
            val sessionClass = session.javaClass
            val inputStreamField = sessionClass.getDeclaredField("inputStream")
            inputStreamField.isAccessible = true

            // Handle final modifier if necessary
            try {
                val modifiersField = Field::class.java.getDeclaredField("modifiers")
                modifiersField.isAccessible = true
                modifiersField.setInt(inputStreamField, inputStreamField.modifiers and Modifier.FINAL.inv())
            } catch (e: Exception) {
                // On some Android versions, this might fail, but let's try anyway
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

    private fun handleFileList(): Response {
        val files = uploadDir.listFiles()?.filter { !it.name.contains(".tmp_") }?.map { file ->
            """{"name":"${
                file.name.replace(
                    "\"",
                    "\\\""
                )
            }","size":${file.length()},"time":${file.lastModified()}}"""
        } ?: emptyList()

        val json = "[${files.joinToString(",")}]"
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

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

    private fun handleStaticContent(): Response {
        return try {
            val html = context.resources.openRawResource(R.raw.index).bufferedReader().readText()
            Log.d(TAG, "serving index.html size=${html.length}")
            newFixedLengthResponse(Response.Status.OK, "text/html", html)
        } catch (e: Exception) {
            Log.e(TAG, "error serving index", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Server Error"
            )
        }
    }
}
