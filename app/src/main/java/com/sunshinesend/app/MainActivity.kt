package com.sunshinesend.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), SimpleServer.ServerListener, FileItemCallback {

    private lateinit var qrCodeImage: ImageView
    private lateinit var urlText: TextView
    private lateinit var fileListTitle: TextView
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
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnDonate = findViewById(R.id.btnDonate)
        fileRecyclerView = findViewById(R.id.fileRecyclerView)
        emptyView = findViewById(R.id.emptyView)

        fileAdapter = FileAdapter(this)
        fileRecyclerView.layoutManager = LinearLayoutManager(this)
        fileRecyclerView.adapter = fileAdapter

        btnCheckUpdate.setOnClickListener { checkForUpdate() }
        btnCheckUpdate.setOnFocusChangeListener { _, hasFocus ->
            btnCheckUpdate.animate().cancel()
            if (hasFocus) {
                val animator = android.animation.ValueAnimator.ofArgb(0xFF00BCD4.toInt(), 0xFFFFFFFF.toInt())
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
            btnDonate.animate().cancel()
            if (hasFocus) {
                val animator = android.animation.ValueAnimator.ofArgb(0xFFFFD600.toInt(), 0xFFFFFFFF.toInt())
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

    private fun checkForUpdate() {
        Toast.makeText(this, "正在检查更新...", Toast.LENGTH_SHORT).show()
        UpdateManager.checkForUpdate(this, object : UpdateManager.UpdateCallback {
            override fun onUpdateAvailable(updateInfo: UpdateInfo) {
                showUpdateDialog(updateInfo)
            }

            override fun onNoUpdate() {
                Toast.makeText(this@MainActivity, "已是最新版本", Toast.LENGTH_SHORT).show()
            }

            override fun onError(error: String) {
                Toast.makeText(this@MainActivity, "检查更新失败: $error", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun showUpdateDialog(updateInfo: UpdateInfo) {
        val message = buildString {
            appendLine("新版本: ${updateInfo.versionName}")
            appendLine()
            if (updateInfo.releaseNote.isNotBlank()) {
                appendLine("更新内容:")
                append(updateInfo.releaseNote)
                appendLine()
            }
            append("是否下载更新?")
        }

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setMessage(message)
            .setPositiveButton("下载") { _, _ ->
                Toast.makeText(this, "开始下载...", Toast.LENGTH_SHORT).show()
                UpdateManager.downloadAndInstall(this, updateInfo)
            }
            .setNegativeButton("取消", null)
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
            }
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
                if (deleted) {
                    fileAdapter.removeItem(item.name)
                    savedFiles.removeAll { it.name == item.name }
                    saveFiles()
                    updateTitle()
                    Toast.makeText(this, "已删除: ${item.name}", Toast.LENGTH_SHORT).show()
                    if (fileAdapter.getItems().isEmpty()) {
                        emptyView.visibility = android.view.View.VISIBLE
                        fileRecyclerView.visibility = android.view.View.GONE
                        btnDonate.requestFocus()
                    }
                } else {
                    Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                }
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
            val item = FileItem(name = fileName, size = fileSize, file = null, progress = 0, status = FileStatus.UPLOADING)
            fileAdapter.addItem(item)
            emptyView.visibility = android.view.View.GONE
            fileRecyclerView.visibility = android.view.View.VISIBLE
        }
    }

    override fun onUploadProgress(fileName: String, progress: Int, bytesReceived: Long, totalBytes: Long) {
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
                savedFiles.add(0, SavedFile(fileName, file.length(), file.absolutePath, System.currentTimeMillis()))
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
            savedFiles.add(0, SavedFile(file.name, file.length(), file.absolutePath, System.currentTimeMillis()))
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
