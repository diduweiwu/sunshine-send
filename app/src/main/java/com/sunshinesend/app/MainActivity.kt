package com.sunshinesend.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sunshinesend.app.ApkInstaller.isApk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity(), SimpleServer.ServerListener, FileItemCallback {

    private lateinit var qrCodeImage: ImageView
    private lateinit var urlText: TextView
    private lateinit var fileListTitle: TextView
    private lateinit var tvVersionName: TextView
    private lateinit var btnCheckUpdate: TextView
    private lateinit var btnDonate: TextView
    private lateinit var fileRecyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var fileAdapter: FileAdapter

    private var server: SimpleServer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingApkFile: String? = null

    private val prefs by lazy { getSharedPreferences("sunshinesend", Context.MODE_PRIVATE) }
    private val gson = Gson()
    private val savedFiles = mutableListOf<SavedFile>()

    data class SavedFile(
        val name: String,
        val size: Long,
        val path: String,
        val time: Long
    )

    private val installLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val fileName = pendingApkFile ?: return@registerForActivityResult
        pendingApkFile = null
        val file = File(uploadDir, fileName)
        handler.post {
            fileAdapter.completeUpload(fileName, file)
            saveFiles()
            updateTitle()
            if (result.resultCode != RESULT_OK) {
                Toast.makeText(this, "$fileName 安装已取消", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val uploadDir: File by lazy {
        File(getExternalFilesDir(null), "Uploads").apply { if (!exists()) mkdirs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        qrCodeImage = findViewById(R.id.qrCodeImage)
        urlText = findViewById(R.id.urlText)
        fileListTitle = findViewById(R.id.fileListTitle)
        tvVersionName = findViewById(R.id.tvVersionName)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnDonate = findViewById(R.id.btnDonate)
        fileRecyclerView = findViewById(R.id.fileRecyclerView)
        emptyView = findViewById(R.id.emptyView)

        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            tvVersionName.text = "v$versionName"
        } catch (e: Exception) {
            tvVersionName.text = ""
        }

        fileAdapter = FileAdapter(this)
        fileRecyclerView.layoutManager = LinearLayoutManager(this)
        fileRecyclerView.adapter = fileAdapter

        btnCheckUpdate.setOnClickListener { checkForUpdate() }
        btnCheckUpdate.setOnFocusChangeListener { _, hasFocus ->
            (btnCheckUpdate.getTag() as? android.animation.ValueAnimator)?.cancel()
            btnCheckUpdate.animate().cancel()
            if (hasFocus) {
                val animator =
                    android.animation.ValueAnimator.ofArgb(0xFF00BCD4.toInt(), 0xFFFFFFFF.toInt())
                animator.duration = 800
                animator.repeatCount = android.animation.ValueAnimator.INFINITE
                animator.repeatMode = android.animation.ValueAnimator.REVERSE
                animator.addUpdateListener { btnCheckUpdate.setTextColor(it.animatedValue as Int) }
                animator.start()
                btnCheckUpdate.setTag(animator)
            } else {
                btnCheckUpdate.setTextColor(0xFF00BCD4.toInt())
            }
        }

        btnDonate.setOnClickListener { showDonateDialog() }
        btnDonate.setOnFocusChangeListener { _, hasFocus ->
            (btnDonate.getTag() as? android.animation.ValueAnimator)?.cancel()
            btnDonate.animate().cancel()
            if (hasFocus) {
                val animator =
                    android.animation.ValueAnimator.ofArgb(0xFFFFD600.toInt(), 0xFFFFFFFF.toInt())
                animator.duration = 800
                animator.repeatCount = android.animation.ValueAnimator.INFINITE
                animator.repeatMode = android.animation.ValueAnimator.REVERSE
                animator.addUpdateListener { btnDonate.setTextColor(it.animatedValue as Int) }
                animator.start()
                btnDonate.setTag(animator)
            } else {
                btnDonate.setTextColor(0xFFFFD600.toInt())
            }
        }

        loadSavedFiles()
        updateTitle()
        if (savedFiles.isEmpty()) {
            btnDonate.requestFocus()
        }
        startServer()
    }

    private fun showDonateDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_donate, null)
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.85).toInt(),
            android.view.WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialogView.requestFocus()
    }

    private var pendingUpdateInfo: UpdateInfo? = null
    private var updateProgressDialog: android.app.AlertDialog? = null

    private fun checkForUpdate() {
        if (!UpdateManager.hasUpdatePermission(this)) {
            showPermissionDialog()
            return
        }

        Toast.makeText(this, "正在检查更新...", Toast.LENGTH_SHORT).show()

        UpdateManager.checkForUpdate(
            this,
            object : UpdateManager.ProgressListener {
                override fun onProgress(text: String) {}
                override fun onDownloadStart() {}
                override fun onDownloadComplete() {
                    handler.post {
                        Toast.makeText(
                            this@MainActivity,
                            "下载完成，正在安装...",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onDownloadFailed() {
                    handler.post {
                        Toast.makeText(
                            this@MainActivity,
                            "操作失败，请检查网络后重试",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                override fun onDownloadCanceled() {}
            },
            object : UpdateManager.CheckCallback {
                override fun onUpdateAvailable(updateInfo: UpdateInfo) {
                    handler.post { showUpdateDialog(updateInfo) }
                }

                override fun onNoUpdate() {
                    handler.post { showNoUpdateDialog() }
                }

                override fun onError(error: String) {
                    handler.post {
                        Toast.makeText(this@MainActivity, "检查更新失败: $error", Toast.LENGTH_LONG)
                            .show()
                    }
                }
            }
        )
    }

    private fun showPermissionDialog() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("需要权限")
            .setMessage("安装应用需要开启\"允许安装未知来源应用\"权限，请在设置中开启。")
            .setPositiveButton("去设置") { _, _ ->
                startActivity(UpdateManager.getUpdatePermissionIntent(this))
            }
            .setNegativeButton("取消", null)
            .setCancelable(true)
            .create()
        dialog.show()
    }

    private fun showUpdateDialog(updateInfo: UpdateInfo) {
        pendingUpdateInfo = updateInfo

        val dialogView = layoutInflater.inflate(R.layout.dialog_update_confirm, null)
        val versionInfo = dialogView.findViewById<TextView>(R.id.updateVersionInfo)
        val releaseNote = dialogView.findViewById<TextView>(R.id.updateReleaseNote)

        val currentVersionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0.0"
        }

        versionInfo.text = "当前版本: $currentVersionName  →  最新版本: ${updateInfo.versionName}"
        releaseNote.text = updateInfo.releaseNote.ifBlank { "无更新说明" }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setView(dialogView)
            .setPositiveButton("下载") { _, _ ->
                pendingUpdateInfo?.let { startUpdateWithProgress(it) }
            }
            .setNegativeButton("稍后更新", null)
            .setCancelable(true)
            .create()
        dialog.show()
    }

    private fun startUpdateWithProgress(updateInfo: UpdateInfo) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_update_progress, null)
        val progressText = dialogView.findViewById<TextView>(R.id.updateProgressText)
        val progressBar =
            dialogView.findViewById<android.widget.ProgressBar>(R.id.updateProgressBar)

        updateProgressDialog = android.app.AlertDialog.Builder(this)
            .setTitle("下载更新 ${updateInfo.versionName}")
            .setView(dialogView)
            .setNegativeButton("取消") { _, _ ->
                UpdateManager.cancelDownload(this)
            }
            .setCancelable(false)
            .create()
        updateProgressDialog?.show()

        UpdateManager.startUpdate(this, updateInfo, object : UpdateManager.ProgressListener {
            override fun onProgress(text: String) {
                handler.post {
                    progressText.text = text
                    val percent = extractPercent(text)
                    if (percent > 0) {
                        progressBar.progress = percent
                    }
                }
            }

            override fun onDownloadStart() {
                handler.post {
                    progressText.text = "开始下载..."
                    progressBar.isIndeterminate = false
                    progressBar.progress = 0
                }
            }

            override fun onDownloadComplete() {
                handler.post {
                    updateProgressDialog?.dismiss()
                    Toast.makeText(this@MainActivity, "下载完成，正在安装...", Toast.LENGTH_SHORT)
                        .show()
                }
            }

            override fun onDownloadFailed() {
                handler.post {
                    updateProgressDialog?.dismiss()
                    Toast.makeText(
                        this@MainActivity,
                        "下载失败，请检查网络后重试",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onDownloadCanceled() {
                handler.post {
                    updateProgressDialog?.dismiss()
                    Toast.makeText(this@MainActivity, "下载已取消", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun extractPercent(text: String): Int {
        val regex = Regex("(\\d+)%")
        return regex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun showNoUpdateDialog() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("已是最新版本")
            .setMessage("当前已是最新版本，无需更新。")
            .setPositiveButton("确定", null)
            .setCancelable(true)
            .create()
        dialog.show()
    }

    private fun loadSavedFiles() {
        val json = prefs.getString("files", null) ?: return
        try {
            val type = object : TypeToken<List<SavedFile>>() {}.type
            val files: List<SavedFile> = gson.fromJson(json, type)
            for (sf in files) {
                val file = File(sf.path)
                if (file.exists()) {
                    savedFiles.add(sf)
                    val item = FileItem(
                        name = sf.name,
                        size = sf.size,
                        file = file,
                        progress = 100,
                        status = FileStatus.COMPLETED
                    )
                    fileAdapter.addItem(item)
                }
            }
            if (savedFiles.isNotEmpty()) {
                emptyView.visibility = android.view.View.GONE
                fileRecyclerView.visibility = android.view.View.VISIBLE
            } else {
                emptyView.visibility = android.view.View.VISIBLE
                fileRecyclerView.visibility = android.view.View.GONE
            }
            updateFocusChain()
        } catch (e: Exception) {
            Log.e("MainActivity", "Load saved files failed", e)
        }
    }

    private fun saveFiles() {
        val json = gson.toJson(savedFiles)
        prefs.edit().putString("files", json).apply()
    }

    private fun updateTitle() {
        val count = savedFiles.size
        fileListTitle.text = if (count > 0) {
            "${getString(R.string.file_list)} ($count)"
        } else {
            getString(R.string.file_list)
        }
    }

    private fun updateFocusChain() {
        val isEmpty = fileAdapter.getItems().isEmpty()
        if (isEmpty) {
            btnCheckUpdate.setNextFocusDownId(btnDonate.id)
            btnCheckUpdate.setNextFocusUpId(btnDonate.id)
            btnDonate.setNextFocusDownId(btnCheckUpdate.id)
            btnDonate.setNextFocusUpId(btnCheckUpdate.id)
        } else {
            btnCheckUpdate.setNextFocusDownId(fileRecyclerView.id)
            btnCheckUpdate.setNextFocusUpId(btnDonate.id)
            btnDonate.setNextFocusDownId(fileRecyclerView.id)
            btnDonate.setNextFocusUpId(btnCheckUpdate.id)
            fileRecyclerView.setNextFocusUpId(btnDonate.id)
            fileRecyclerView.setNextFocusDownId(btnCheckUpdate.id)
        }
    }

    private fun startServer() {
        val ip = PortUtil.lan() ?: "127.0.0.1"
        val url = "http://$ip:${SimpleServer.PORT}"
        Log.d("MainActivity", "Starting server on $url")

        urlText.text = url

        GlobalScope.launch(Dispatchers.Default) {
            val bitmap = QrCodeUtil.createQRCodeBitmap(url)
            withContext(Dispatchers.Main) {
                bitmap?.let { qrCodeImage.setImageBitmap(it) }
            }
        }

        server = SimpleServer(this, this).also {
            try {
                it.start()
                Log.d("MainActivity", "Server started successfully")
                handler.post {
                    Toast.makeText(this, "服务已启动: $url", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Server start failed", e)
                handler.post {
                    Toast.makeText(this, "服务启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onOpen(item: FileItem) {
        handler.post {
            val file = item.file
            if (file == null || !file.exists()) {
                Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
                return@post
            }
            try {
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, getMimeType(file.name))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Open file failed", e)
                Toast.makeText(this, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDelete(item: FileItem) {
        handler.post {
            val file = item.file
            if (file != null && file.exists()) {
                val deleted = file.delete()
                if (!deleted) {
                    Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                    return@post
                }
            }
            fileAdapter.removeItem(item.name)
            savedFiles.removeAll { it.name == item.name }
            saveFiles()
            updateTitle()
            Toast.makeText(this, "已删除: ${item.name}", Toast.LENGTH_SHORT).show()
            if (fileAdapter.getItems().isEmpty()) {
                emptyView.visibility = android.view.View.VISIBLE
                fileRecyclerView.visibility = android.view.View.GONE
                btnDonate.requestFocus()
                updateFocusChain()
            }
        }
    }

    private fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "json" -> "application/json"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            else -> "*/*"
        }
    }

    override fun onUploadStart(fileName: String, fileSize: Long) {
        handler.post {
            val item = FileItem(
                name = fileName,
                size = fileSize,
                file = null,
                progress = 0,
                status = FileStatus.UPLOADING
            )
            fileAdapter.addItem(item)
            emptyView.visibility = android.view.View.GONE
            fileRecyclerView.visibility = android.view.View.VISIBLE
            updateFocusChain()
        }
    }

    override fun onUploadProgress(
        fileName: String,
        progress: Int,
        bytesReceived: Long,
        totalBytes: Long
    ) {
        handler.post {
            fileAdapter.updateProgress(fileName, progress)
        }
    }

    override fun onUploadComplete(fileName: String, file: File) {
        handler.post {
            Toast.makeText(this, "$fileName 上传完成", Toast.LENGTH_SHORT).show()

            if (isApk(fileName)) {
                fileAdapter.setInstalling(fileName)
                pendingApkFile = fileName
                launchInstall(file)
            } else {
                fileAdapter.completeUpload(fileName, file)
                savedFiles.removeAll { it.name == fileName }
                savedFiles.add(
                    0,
                    SavedFile(
                        fileName,
                        file.length(),
                        file.absolutePath,
                        System.currentTimeMillis()
                    )
                )
                saveFiles()
                updateTitle()
            }
        }
    }

    private fun launchInstall(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            installLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Install failed", e)
            pendingApkFile = null
            fileAdapter.completeUpload(file.name, file)
            savedFiles.removeAll { it.name == file.name }
            savedFiles.add(
                0,
                SavedFile(file.name, file.length(), file.absolutePath, System.currentTimeMillis())
            )
            saveFiles()
            updateTitle()
            Toast.makeText(this, "安装失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUploadError(fileName: String, error: String) {
        handler.post {
            fileAdapter.setError(fileName)
            Toast.makeText(this, "$fileName 上传失败: $error", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }
}
