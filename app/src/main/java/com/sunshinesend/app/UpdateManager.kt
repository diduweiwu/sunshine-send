package com.sunshinesend.app

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
    private const val PROGRESS_POLL_INTERVAL = 500L
    private const val MAX_RETRY_COUNT = 3
    private const val PENDING_TIMEOUT = 30000L
    private const val MIN_APK_SIZE = 1024 * 1024L

    private var downloadId: Long = -1
    private var progressListener: ProgressListener? = null
    private var downloadHandler: Handler? = null
    private var progressRunnable: Runnable? = null
    private var retryCount = 0
    private var currentApkFile: File? = null
    private var currentDownloadUrl: String = ""
    private var currentFileName: String = ""
    private var pendingStartTime = 0L
    private var expectedTotalBytes = 0L

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
        GlobalScope.launch(Dispatchers.IO) {
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
        retryCount = 0

        val fileName = "SunshineSend-${updateInfo.versionName}.apk"
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val apkFile = File(downloadDir, fileName)
        currentApkFile = apkFile

        if (apkFile.exists() && apkFile.length() >= MIN_APK_SIZE) {
            Log.d(TAG, "APK already exists, skip download")
            installApk(context, apkFile)
            return
        }

        if (apkFile.exists()) {
            apkFile.delete()
        }

        startDownload(context, updateInfo.downloadUrl, fileName, apkFile)
    }

    private fun startDownload(
        context: Context,
        downloadUrl: String,
        fileName: String,
        apkFile: File
    ) {
        currentDownloadUrl = downloadUrl
        currentFileName = fileName
        pendingStartTime = System.currentTimeMillis()

        try {
            if (apkFile.exists()) {
                apkFile.delete()
            }

            val request = DownloadManager.Request(Uri.parse(downloadUrl))
                .setTitle("下载 SunshineSend")
                .setDescription("正在下载新版本...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    fileName
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadManager =
                context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadId = downloadManager.enqueue(request)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    downloadCompleteReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                ContextCompat.registerReceiver(
                    context,
                    downloadCompleteReceiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
            }

            progressListener?.onDownloadStart()
            startProgressPolling(context, downloadManager)
        } catch (e: Exception) {
            Log.e(TAG, "Download start failed", e)
            progressListener?.onDownloadFailed()
        }
    }

    private fun startProgressPolling(context: Context, downloadManager: DownloadManager) {
        val handler = Handler(Looper.getMainLooper())
        downloadHandler = handler
        pendingStartTime = System.currentTimeMillis()

        val runnable = object : Runnable {
            override fun run() {
                if (downloadId == -1L) return

                try {
                    val query = DownloadManager.Query().setFilterById(downloadId)
                    val cursor = downloadManager.query(query)

                    if (cursor.moveToFirst()) {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        if (statusIndex >= 0) {
                            val status = cursor.getInt(statusIndex)

                            when (status) {
                                DownloadManager.STATUS_RUNNING -> {
                                    val bytesDownloaded = cursor.getInt(
                                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                                    ).toLong()
                                    val totalBytes = cursor.getInt(
                                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                                    ).toLong()
                                    expectedTotalBytes = totalBytes

                                    if (totalBytes > 0) {
                                        val progress = (bytesDownloaded * 100 / totalBytes).toInt()
                                        val downloadedMb = bytesDownloaded / (1024 * 1024)
                                        val totalMb = totalBytes / (1024 * 1024)
                                        progressListener?.onProgress("下载中 $progress% (${downloadedMb}MB/${totalMb}MB)")
                                    }

                                    handler.postDelayed(this, PROGRESS_POLL_INTERVAL)
                                }

                                DownloadManager.STATUS_PENDING -> {
                                    val elapsed = System.currentTimeMillis() - pendingStartTime
                                    if (elapsed > PENDING_TIMEOUT) {
                                        stopProgressPolling()
                                        progressListener?.onProgress("下载准备超时，正在重试...")
                                        handler.postDelayed({
                                            retryCount++
                                            if (retryCount <= MAX_RETRY_COUNT) {
                                                downloadManager.remove(downloadId)
                                                downloadId = -1
                                                currentApkFile?.let { startDownload(context, currentDownloadUrl, currentFileName, it) }
                                            } else {
                                                progressListener?.onDownloadFailed()
                                            }
                                        }, 1000)
                                    } else {
                                        val waitSeconds = (elapsed / 1000).toInt()
                                        progressListener?.onProgress("等待网络连接... ${waitSeconds}s")
                                        handler.postDelayed(this, PROGRESS_POLL_INTERVAL)
                                    }
                                }

                                DownloadManager.STATUS_SUCCESSFUL -> {
                                    stopProgressPolling()
                                }

                                DownloadManager.STATUS_FAILED -> {
                                    stopProgressPolling()
                                    val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                    val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else -1
                                    Log.e(TAG, "Download failed, reason: $reason")
                                    handleDownloadFailed(context, downloadManager)
                                }

                                DownloadManager.STATUS_PAUSED -> {
                                    progressListener?.onProgress("下载已暂停，等待网络...")
                                    handler.postDelayed(this, PROGRESS_POLL_INTERVAL)
                                }
                            }
                        }
                    } else {
                        val elapsed = System.currentTimeMillis() - pendingStartTime
                        if (elapsed > PENDING_TIMEOUT) {
                            progressListener?.onProgress("下载超时")
                            stopProgressPolling()
                            handleDownloadFailed(context, downloadManager)
                        } else {
                            progressListener?.onProgress("准备下载...")
                            handler.postDelayed(this, PROGRESS_POLL_INTERVAL)
                        }
                    }
                    cursor.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Progress poll error", e)
                    handler.postDelayed(this, PROGRESS_POLL_INTERVAL)
                }
            }
        }

        progressRunnable = runnable
        handler.post(runnable)
    }

    private fun stopProgressPolling() {
        progressRunnable?.let { downloadHandler?.removeCallbacks(it) }
        progressRunnable = null
        downloadHandler = null
    }

    private fun handleDownloadFailed(context: Context, downloadManager: DownloadManager) {
        retryCount++
        if (retryCount <= MAX_RETRY_COUNT) {
            Log.d(TAG, "Download retry $retryCount/$MAX_RETRY_COUNT")
            downloadManager.remove(downloadId)
            downloadId = -1
            stopProgressPolling()
            runCatching { context.unregisterReceiver(downloadCompleteReceiver) }

            currentApkFile?.let { file ->
                if (file.exists()) file.delete()

                GlobalScope.launch(Dispatchers.Main) {
                    progressListener?.onProgress("下载失败，正在重试 ($retryCount/$MAX_RETRY_COUNT)...")
                }

                GlobalScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(2000)
                    withContext(Dispatchers.Main) {
                        startDownload(context, currentDownloadUrl, currentFileName, file)
                    }
                }
            } ?: progressListener?.onDownloadFailed()
        } else {
            progressListener?.onDownloadFailed()
        }
    }

    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: return
            if (id == downloadId) {
                val downloadManager =
                    ctx?.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
                val query = DownloadManager.Query().setFilterById(id)
                val cursor = downloadManager?.query(query)

                if (cursor != null && cursor.moveToFirst()) {
                    val status =
                        cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    cursor.close()

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            stopProgressPolling()
                            verifyAndInstall(ctx)
                        }

                        DownloadManager.STATUS_FAILED -> {
                            val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = if (reasonIndex >= 0) cursor.getInt(reasonIndex) else -1
                            Log.e(TAG, "Download broadcast failed, reason: $reason")
                            handleDownloadFailed(ctx, downloadManager)
                        }
                    }
                } else {
                    cursor?.close()
                    stopProgressPolling()
                }

                runCatching { ctx?.unregisterReceiver(this) }
            }
        }
    }

    private fun verifyAndInstall(context: Context?) {
        if (context == null) return

        val file = currentApkFile
        if (file == null || !file.exists()) {
            Log.e(TAG, "APK file not found")
            progressListener?.onDownloadFailed()
            return
        }

        if (file.length() < MIN_APK_SIZE) {
            Log.e(TAG, "APK file too small: ${file.length()} bytes")
            file.delete()
            progressListener?.onDownloadFailed()
            return
        }

        Log.d(TAG, "APK downloaded: ${file.absolutePath}, size: ${file.length()} bytes")

        progressListener?.onDownloadComplete()

        installApk(context, file)
    }

    fun cancelDownload(context: Context) {
        if (downloadId != -1L) {
            val downloadManager =
                context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.remove(downloadId)
            downloadId = -1
        }
        stopProgressPolling()
        runCatching { context.unregisterReceiver(downloadCompleteReceiver) }
        currentApkFile?.let { if (it.exists()) it.delete() }
        currentApkFile = null
        progressListener?.onDownloadCanceled()
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

    fun hasUpdatePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun getUpdatePermissionIntent(): Intent {
        return Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:")
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
