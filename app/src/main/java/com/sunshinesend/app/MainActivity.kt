package com.sunshinesend.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sunshinesend.app.data.FileRepository
import com.sunshinesend.app.data.SavedFile
import com.sunshinesend.app.util.PortUtil
import com.sunshinesend.app.server.SimpleServer
import com.sunshinesend.app.ui.FileAdapter
import com.sunshinesend.app.ui.FileItem
import com.sunshinesend.app.ui.FileItemCallback
import com.sunshinesend.app.ui.FileStatus
import com.sunshinesend.app.update.UpdateInfo
import com.sunshinesend.app.update.UpdateManager
import com.sunshinesend.app.util.ApkInstaller.isApk
import com.sunshinesend.app.util.QrCodeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用主界面（TV 横屏）。
 *
 * 布局：左侧为服务地址二维码，右侧为已接收文件列表。
 * 职责：
 * 1. 启动/停止 [SimpleServer]，展示扫码上传地址；
 * 2. 渲染文件列表（[FileAdapter]），响应「打开 / 删除」操作；
 * 3. 通过 [FileRepository] 持久化传输记录，重启后自动恢复列表；
 * 4. 提供「检查更新」「打赏」入口（更新逻辑见 [UpdateManager]）。
 */
class MainActivity : AppCompatActivity(), SimpleServer.ServerListener, FileItemCallback {

    companion object {
        private const val TAG = "MainActivity"
    }

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

    /** 持久化仓库：SharedPreferences 记录 + 上传目录扫描兜底 */
    private val fileRepository by lazy { FileRepository(this) }

    /** 内存中的传输记录（仅含完成态，与 fileAdapter 中的完成条目对应） */
    private val savedFiles = mutableListOf<SavedFile>()

    /**
     * APK 安装结果回调。
     *
     * 拉起系统安装器后条目停留在「安装中」，无论用户安装成功还是取消，
     * 都要在这里把条目落为「已完成」并持久化记录。
     */
    private val installLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val fileName = pendingApkFile ?: return@registerForActivityResult
        pendingApkFile = null
        val file = File(fileRepository.uploadDir, fileName)
        handler.post {
            fileAdapter.completeUpload(fileName, file)
            saveRecord(fileName, file)
            updateTitle()
            if (result.resultCode != RESULT_OK) {
                Toast.makeText(this, "$fileName 安装已取消", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initFileList()
        initButtons()

        loadSavedFiles()
        updateTitle()
        if (savedFiles.isEmpty()) {
            btnDonate.requestFocus()
        }
        startServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
    }

    // ------------------------------------------------------------------
    // 初始化（拆分自 onCreate）
    // ------------------------------------------------------------------

    /** 绑定布局控件并展示版本号 */
    private fun initViews() {
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
    }

    /** 初始化文件列表 RecyclerView 与适配器 */
    private fun initFileList() {
        fileAdapter = FileAdapter(this)
        fileRecyclerView.layoutManager = LinearLayoutManager(this)
        fileRecyclerView.adapter = fileAdapter
    }

    /** 初始化「检查更新」「打赏」按钮的点击与焦点动画 */
    private fun initButtons() {
        btnCheckUpdate.setOnClickListener { checkForUpdate() }
        bindPulsingFocusAnimation(btnCheckUpdate, 0xFF00BCD4.toInt())

        btnDonate.setOnClickListener { showDonateDialog() }
        bindPulsingFocusAnimation(btnDonate, 0xFFFFD600.toInt())
    }

    /**
     * 为按钮绑定 TV 焦点呼吸动画：获得焦点时文字颜色在 brandColor
     * 与白色之间往复渐变，失去焦点时停止并恢复 brandColor。
     * 动画实例挂在 view tag 上，重复触发时先取消旧动画。
     *
     * @param view 目标按钮
     * @param brandColor 焦点态基准色（ARGB）
     */
    private fun bindPulsingFocusAnimation(view: TextView, brandColor: Int) {
        view.setOnFocusChangeListener { _, hasFocus ->
            (view.getTag() as? android.animation.ValueAnimator)?.cancel()
            view.animate().cancel()
            if (hasFocus) {
                val animator =
                    android.animation.ValueAnimator.ofArgb(brandColor, 0xFFFFFFFF.toInt())
                animator.duration = 800
                animator.repeatCount = android.animation.ValueAnimator.INFINITE
                animator.repeatMode = android.animation.ValueAnimator.REVERSE
                animator.addUpdateListener { view.setTextColor(it.animatedValue as Int) }
                animator.start()
                view.setTag(animator)
            } else {
                view.setTextColor(brandColor)
            }
        }
    }

    // ------------------------------------------------------------------
    // 传输记录：加载与持久化
    // ------------------------------------------------------------------

    /**
     * 启动时从 [FileRepository] 恢复传输记录并渲染到列表。
     *
     * 记录按时间升序返回、[FileAdapter.addItem] 头插，
     * 最终展示顺序为最新在前。恢复后按有无记录切换空态视图。
     */
    private fun loadSavedFiles() {
        val records = fileRepository.load()
        for (sf in records) {
            savedFiles.add(sf)
            fileAdapter.addItem(
                FileItem(
                    name = sf.name,
                    size = sf.size,
                    file = File(sf.path),
                    progress = 100,
                    status = FileStatus.COMPLETED
                )
            )
        }
        updateEmptyState()
    }

    /**
     * 新增或覆盖一条传输记录（内存 + 磁盘）并刷新标题。
     * 同名文件重复上传时旧记录被替换。
     *
     * @param fileName 文件名
     * @param file 已落盘文件
     */
    private fun saveRecord(fileName: String, file: File) {
        savedFiles.removeAll { it.name == fileName }
        val record = SavedFile(
            name = fileName,
            size = file.length(),
            path = file.absolutePath,
            time = System.currentTimeMillis()
        )
        savedFiles.add(0, record)
        fileRepository.upsert(record)
    }

    /** 根据内存记录数刷新标题中的计数 */
    private fun updateTitle() {
        val count = savedFiles.size
        fileListTitle.text = if (count > 0) {
            "${getString(R.string.file_list)} ($count)"
        } else {
            getString(R.string.file_list)
        }
    }

    /** 依据列表是否为空切换「空态提示 / 列表」可见性 */
    private fun updateEmptyState() {
        if (fileAdapter.getItems().isEmpty()) {
            emptyView.visibility = View.VISIBLE
            fileRecyclerView.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            fileRecyclerView.visibility = View.VISIBLE
        }
    }

    /**
     * 依据列表是否为空调整 TV 焦点链：
     * 列表为空时按钮之间互相导航；非空时按钮可下移到列表。
     */
    private fun updateFocusChain() {
        if (fileAdapter.getItems().isEmpty()) {
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

    // ------------------------------------------------------------------
    // 服务端回调（SimpleServer.ServerListener，回调已保证在主线程）
    // ------------------------------------------------------------------

    /** 上传开始：插入「上传中」条目并切换出空态 */
    override fun onUploadStart(fileName: String, fileSize: Long) {
        fileAdapter.addItem(
            FileItem(
                name = fileName,
                size = fileSize,
                file = null,
                progress = 0,
                status = FileStatus.UPLOADING
            )
        )
        updateEmptyState()
        updateFocusChain()
    }

    /** 上传进度：刷新条目进度条 */
    override fun onUploadProgress(
        fileName: String,
        progress: Int,
        bytesReceived: Long,
        totalBytes: Long
    ) {
        fileAdapter.updateProgress(fileName, progress)
    }

    /**
     * 上传完成：持久化记录；若是 APK 则先走自动安装流程，
     * 条目在安装结束后由 [installLauncher] 收尾为「已完成」。
     */
    override fun onUploadComplete(fileName: String, file: File) {
        Toast.makeText(this, "$fileName 上传完成", Toast.LENGTH_SHORT).show()

        if (isApk(fileName)) {
            fileAdapter.setInstalling(fileName)
            pendingApkFile = fileName
            launchInstall(file)
        } else {
            fileAdapter.completeUpload(fileName, file)
            saveRecord(fileName, file)
            updateTitle()
        }
    }

    /** 上传失败：条目标记为「失败」并 Toast 提示原因 */
    override fun onUploadError(fileName: String, error: String) {
        fileAdapter.setError(fileName)
        Toast.makeText(this, "$fileName 上传失败: $error", Toast.LENGTH_SHORT).show()
    }

    /** 上传被取消（网页端取消或连接中断）：条目标记为「已取消」 */
    override fun onUploadCancelled(fileName: String) {
        fileAdapter.setCancelled(fileName)
    }

    // ------------------------------------------------------------------
    // 文件操作（FileItemCallback）
    // ------------------------------------------------------------------

    /**
     * 「打开」：通过 FileProvider 拉起系统默认应用查看文件。
     * 文件不存在或无对应应用时 Toast 提示，不崩溃。
     */
    override fun onOpen(item: FileItem) {
        val file = item.file
        if (file == null || !file.exists()) {
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            return
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
            Log.e(TAG, "Open file failed", e)
            Toast.makeText(this, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 「删除」：删除磁盘文件与持久化记录，并从列表移除条目。
     * 文件删除失败时中断并提示；列表清空后回到空态并归还焦点。
     */
    override fun onDelete(item: FileItem) {
        val file = item.file
        if (file != null && file.exists()) {
            val deleted = file.delete()
            if (!deleted) {
                Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                return
            }
        }
        fileAdapter.removeItem(item.name)
        savedFiles.removeAll { it.name == item.name }
        fileRepository.remove(item.name)
        updateTitle()
        Toast.makeText(this, "已删除: ${item.name}", Toast.LENGTH_SHORT).show()
        if (fileAdapter.getItems().isEmpty()) {
            btnDonate.requestFocus()
        }
        updateEmptyState()
        updateFocusChain()
    }

    // ------------------------------------------------------------------
    // 服务启动
    // ------------------------------------------------------------------

    /**
     * 启动内置 HTTP 服务并渲染二维码。
     *
     * 地址取本机局域网 IP（取不到时回退 127.0.0.1）；二维码在
     * IO 线程生成后切回主线程设置。服务启动成功/失败均有 Toast 提示。
     */
    private fun startServer() {
        val ip = PortUtil.lan() ?: "127.0.0.1"
        val url = "http://$ip:${SimpleServer.PORT}"
        Log.d(TAG, "Starting server on $url")

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
                Log.d(TAG, "Server started successfully")
                handler.post {
                    Toast.makeText(this, "服务已启动: $url", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server start failed", e)
                handler.post {
                    Toast.makeText(this, "服务启动失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 打赏 & 更新
    // ------------------------------------------------------------------

    /** 展示打赏弹窗（支付宝/微信收款码），宽度占屏 85% */
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

    /** 待安装的新版本信息（更新确认弹窗与下载流程间传递） */
    private var pendingUpdateInfo: UpdateInfo? = null

    /** 下载进度弹窗，取消下载与关闭弹窗时引用 */
    private var updateProgressDialog: android.app.AlertDialog? = null

    /**
     * 检查更新入口。
     *
     * 先校验「安装未知来源应用」权限（缺失则弹窗引导去设置），
     * 再异步请求 [UpdateManager]，结果分别弹「发现新版本」「已是最新」
     * 或 Toast 错误。
     */
    private fun checkForUpdate() {
        if (!UpdateManager.hasUpdatePermission(this)) {
            showPermissionDialog()
            return
        }

        Toast.makeText(this, "正在检查更新...", Toast.LENGTH_SHORT).show()

        UpdateManager.checkForUpdate(
            this,
            noopProgressListener(),
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

    /** 检查阶段使用的空进度监听（检查流程不产生下载事件） */
    private fun noopProgressListener() = object : UpdateManager.ProgressListener {
        override fun onProgress(text: String) {}
        override fun onDownloadStart() {}
        override fun onDownloadComplete() {}
        override fun onDownloadFailed() {}
        override fun onDownloadCanceled() {}
    }

    /** 引导用户去系统设置开启「安装未知来源应用」权限 */
    private fun showPermissionDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("需要权限")
            .setMessage("安装应用需要开启\"允许安装未知来源应用\"权限，请在设置中开启。")
            .setPositiveButton("去设置") { _, _ ->
                startActivity(UpdateManager.getUpdatePermissionIntent(this))
            }
            .setNegativeButton("取消", null)
            .setCancelable(true)
            .show()
    }

    /**
     * 展示「发现新版本」确认弹窗：展示当前→最新版本与更新说明，
     * 用户点击「下载」后进入 [startUpdateWithProgress]。
     */
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

        android.app.AlertDialog.Builder(this)
            .setTitle("发现新版本")
            .setView(dialogView)
            .setPositiveButton("下载") { _, _ ->
                pendingUpdateInfo?.let { startUpdateWithProgress(it) }
            }
            .setNegativeButton("稍后更新", null)
            .setCancelable(true)
            .show()
    }

    /**
     * 弹出下载进度弹窗并启动 [UpdateManager] 下载。
     *
     * 进度文本实时刷新到弹窗，带百分比时同步驱动进度条；
     * 完成/失败/取消时关闭弹窗并给出对应 Toast。
     */
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

    /**
     * 从进度文本中提取百分比数字（如「下载中 45% (…)」→ 45）。
     * @return 百分比；文本中无百分比时返回 0
     */
    private fun extractPercent(text: String): Int {
        val regex = Regex("(\\d+)%")
        return regex.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** 展示「已是最新版本」提示弹窗 */
    private fun showNoUpdateDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("已是最新版本")
            .setMessage("当前已是最新版本，无需更新。")
            .setPositiveButton("确定", null)
            .setCancelable(true)
            .show()
    }

    // ------------------------------------------------------------------
    // APK 安装
    // ------------------------------------------------------------------

    /**
     * 拉起系统安装器安装上传的 APK。
     * 拉起失败时直接把条目收尾为「已完成」并持久化，避免条目卡在安装态。
     */
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
            Log.e(TAG, "Install failed", e)
            pendingApkFile = null
            fileAdapter.completeUpload(file.name, file)
            saveRecord(file.name, file)
            updateTitle()
            Toast.makeText(this, "安装失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 根据文件扩展名推断 MIME 类型（用于「打开」文件的 Intent）。
     * 未识别的扩展名返回通配类型，交由系统处理。
     *
     * @param fileName 文件名
     * @return MIME 类型字符串
     */
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
}
