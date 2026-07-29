package com.sunshinesend.app

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

data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val versionCode: Int,
    val downloadUrl: String,
    val releaseNote: String
)

object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val API_URL =
        "https://api.github.com/repos/diduweiwu/sunshine-send/releases/latest"
    private const val GH_PROXY = "https://gh-proxy.org/"

    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 15000
    private const val MIN_APK_SIZE = 1024 * 1024L
    private const val THREAD_COUNT = 3

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var progressListener: ProgressListener? = null
    private var currentApkFile: File? = null
    private var downloadScope: CoroutineScope? = null
    private var isDownloading = false

    interface ProgressListener {
        fun onProgress(text: String)
        fun onDownloadStart()
        fun onDownloadComplete()
        fun onDownloadFailed()
        fun onDownloadCanceled()
    }

    interface CheckCallback {
        fun onUpdateAvailable(updateInfo: UpdateInfo)
        fun onNoUpdate()
        fun onError(error: String)
    }

    fun checkForUpdate(context: Context, listener: ProgressListener, callback: CheckCallback) {
        progressListener = listener
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val info = fetchLatestRelease()
                if (info == null) {
                    withContext(Dispatchers.Main) {
                        callback.onError("无法获取版本信息")
                    }
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

                val versionCode = parseVersionCode(tagName)
                val downloadUrl = findApkDownloadUrl(jsonObject)
                    ?: return@withContext null

                UpdateInfo(
                    tagName = tagName,
                    versionName = tagName,
                    versionCode = versionCode,
                    downloadUrl = "$GH_PROXY$downloadUrl",
                    releaseNote = releaseNote
                )
            } catch (e: Exception) {
                Log.e(TAG, "Fetch release failed", e)
                null
            }
        }
    }

    fun startUpdate(context: Context, updateInfo: UpdateInfo, listener: ProgressListener) {
        progressListener = listener

        val fileName = "SunshineSend-${updateInfo.versionName}.apk"
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        downloadDir?.mkdirs()
        val apkFile = File(downloadDir, fileName)
        currentApkFile = apkFile

        if (isDownloading) return

        if (apkFile.exists() && isFileComplete(apkFile)) {
            Log.d(TAG, "APK already exists, skip download")
            listener.onDownloadComplete()
            installApk(context, apkFile)
            return
        }

        if (apkFile.exists()) {
            apkFile.delete()
        }

        downloadScope = CoroutineScope(Dispatchers.IO)
        downloadScope?.launch {
            try {
                isDownloading = true

                val headRequest = Request.Builder()
                    .url(updateInfo.downloadUrl)
                    .head()
                    .header("User-Agent", "SunshineSend")
                    .build()

                val totalBytes = client.newCall(headRequest).execute().use { response ->
                    if (response.isSuccessful) response.body?.contentLength() ?: -1L else -1L
                }

                if (totalBytes <= 0) {
                    Log.w(TAG, "Failed to get content length, falling back to single thread")
                    downloadSingleThread(updateInfo, apkFile, context)
                    return@launch
                }

                Log.i(TAG, "Starting multi-threaded download: size=$totalBytes")
                val chunkSize = totalBytes / THREAD_COUNT
                val totalRead = AtomicLong(0)
                var lastUpdateTime = 0L

                val deferreds = (0 until THREAD_COUNT).map { i ->
                    val start = i * chunkSize
                    val end = if (i == THREAD_COUNT - 1) totalBytes - 1 else (i + 1) * chunkSize - 1

                    async {
                        val request = Request.Builder()
                            .url(updateInfo.downloadUrl)
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
                                        val currentTotal = totalRead.addAndGet(bytesRead.toLong())

                                        val now = System.currentTimeMillis()
                                        if (now - lastUpdateTime > 300) {
                                            lastUpdateTime = now
                                            val percent = (currentTotal * 100 / totalBytes).toInt()
                                            val downloadedMB = currentTotal / (1024 * 1024)
                                            val totalMB = totalBytes / (1024 * 1024)
                                            withContext(Dispatchers.Main) {
                                                progressListener?.onProgress(
                                                    "下载中 ${percent}% (${downloadedMB}MB/${totalMB}MB) [多线程]"
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
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
            } catch (e: Exception) {
                Log.e(TAG, "Download error", e)
                isDownloading = false
                withContext(Dispatchers.Main) {
                    progressListener?.onDownloadFailed()
                }
            }
        }
    }

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
                            if (now - lastUpdate > 300) {
                                lastUpdate = now
                                val percent = if (totalBytes > 0) (totalRead * 100 / totalBytes).toInt() else 0
                                val downloadedMB = totalRead / (1024 * 1024)
                                val totalMB = totalBytes / (1024 * 1024)
                                withContext(Dispatchers.Main) {
                                    progressListener?.onProgress("下载中 ${percent}% (${downloadedMB}MB/${totalMB}MB)")
                                }
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

    private fun isFileComplete(apkFile: File, expectedSize: Long = -1L): Boolean {
        if (!apkFile.exists()) return false
        if (apkFile.length() <= 0) return false
        if (expectedSize > 0 && apkFile.length() != expectedSize) {
            Log.w(TAG, "File size mismatch: expected=$expectedSize, actual=${apkFile.length()}")
            return false
        }
        return isValidApk(apkFile)
    }

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

    fun cancelDownload(context: Context) {
        downloadScope?.cancel()
        downloadScope = null
        isDownloading = false
        currentApkFile?.let { if (it.exists()) it.delete() }
        currentApkFile = null
        progressListener?.onDownloadCanceled()
    }

    fun hasUpdatePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun getUpdatePermissionIntent(context: Context): Intent {
        return Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

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

    private fun getCurrentVersionCode(context: Context): Int {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        } catch (e: Exception) {
            0
        }
    }

    private fun getCurrentVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

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
