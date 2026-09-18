package com.sunshinesend.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sunshinesend.app.R
import java.io.File
import java.util.Locale

/**
 * 文件列表条目数据模型。
 *
 * 同一条目会经历多个状态（见 [FileStatus]）：创建时处于上传中，
 * 完成后绑定真实 [file]；从持久化恢复的条目直接为完成态。
 *
 * @property name 文件名，同时作为列表内条目的唯一标识
 * @property size 文件大小（字节）
 * @property file 磁盘文件引用；上传进行中为 null，完成后或恢复加载时非空
 * @property progress 上传进度（0-100）
 * @property status 当前条目状态
 */
data class FileItem(
    val name: String,
    val size: Long,
    var file: File?,
    var progress: Int = 100,
    var status: FileStatus = FileStatus.COMPLETED
)

/** 文件条目的生命周期状态，决定条目上展示的进度条、文案与按钮可见性 */
enum class FileStatus {
    /** 上传进行中：显示进度条与百分比，隐藏“打开”按钮 */
    UPLOADING,

    /** 上传完成：隐藏进度条，显示“打开”按钮 */
    COMPLETED,

    /** 上传失败：显示“失败”，无可用操作 */
    ERROR,

    /** APK 上传完成、正在拉起安装器：显示“安装中” */
    INSTALLING,

    /** 上传被网页端或网络中断取消：显示“已取消” */
    CANCELLED
}

/**
 * 文件条目的用户操作回调，由列表宿主（MainActivity）实现。
 *
 * 使用示例：
 * ```kotlin
 * val adapter = FileAdapter(object : FileItemCallback {
 *     override fun onOpen(item: FileItem) { /* 打开文件 */ }
 *     override fun onDelete(item: FileItem) { /* 删除文件与记录 */ }
 * })
 * ```
 */
interface FileItemCallback {
    /**
     * 用户点击「打开」时触发。
     * @param item 被点击的条目
     */
    fun onOpen(item: FileItem)

    /**
     * 用户点击「删除」时触发。
     * @param item 被点击的条目
     */
    fun onDelete(item: FileItem)
}

/**
 * 文件列表适配器，负责条目渲染与状态驱动刷新。
 *
 * 所有更新方法均按 [FileItem.name] 定位条目；未找到时静默忽略，
 * 因此调用方无需关心条目是否仍然存在（如取消、失败竞态场景）。
 *
 * 使用示例：
 * ```kotlin
 * recyclerView.adapter = FileAdapter(callback)
 * adapter.addItem(item)                    // 新条目插入列表顶部
 * adapter.updateProgress("a.txt", 50)      // 上传进度刷新
 * adapter.completeUpload("a.txt", file)    // 上传完成，绑定文件
 * adapter.setError("a.txt")                // 标记失败
 * adapter.setCancelled("a.txt")            // 标记取消
 * adapter.setInstalling("a.txt")           // 标记安装中
 * adapter.removeItem("a.txt")              // 删除条目
 * ```
 *
 * @param callback 用户操作回调
 */
class FileAdapter(private val callback: FileItemCallback) :
    RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    private val items = mutableListOf<FileItem>()

    /**
     * 在列表顶部插入一个新条目（新的传输永远显示在最上面），
     * 并刷新后续条目的序号徽标。
     * @param item 待插入的条目
     */
    fun addItem(item: FileItem) {
        items.add(0, item)
        notifyItemInserted(0)
        notifyItemRangeChanged(1, items.size - 1)
    }

    /**
     * 按文件名移除条目，并刷新其后条目的序号徽标。
     * @param name 条目文件名；不存在时不做任何事
     */
    fun removeItem(name: String) {
        val index = items.indexOfFirst { it.name == name }
        if (index >= 0) {
            items.removeAt(index)
            notifyItemRemoved(index)
            notifyItemRangeChanged(index, items.size - index)
        }
    }

    /**
     * 刷新指定条目的上传进度，并强制其状态为 [FileStatus.UPLOADING]。
     * @param name 条目文件名
     * @param progress 最新进度（0-100）
     */
    fun updateProgress(name: String, progress: Int) {
        updateItem(name) {
            it.progress = progress
            it.status = FileStatus.UPLOADING
        }
    }

    /**
     * 上传完成：绑定磁盘文件、进度置 100、状态置为完成。
     * @param name 条目文件名
     * @param file 已落盘的文件
     */
    fun completeUpload(name: String, file: File) {
        updateItem(name) {
            it.file = file
            it.progress = 100
            it.status = FileStatus.COMPLETED
        }
    }

    /** 将条目标记为 [FileStatus.INSTALLING]（APK 上传完成后拉起安装器阶段） */
    fun setInstalling(name: String) = updateItem(name) { it.status = FileStatus.INSTALLING }

    /** 将条目标记为 [FileStatus.ERROR]（上传失败） */
    fun setError(name: String) = updateItem(name) { it.status = FileStatus.ERROR }

    /** 将条目标记为 [FileStatus.CANCELLED]（上传被取消） */
    fun setCancelled(name: String) = updateItem(name) { it.status = FileStatus.CANCELLED }

    /** @return 当前条目的只读快照，用于外部统计数量、判断空列表等 */
    fun getItems(): List<FileItem> = items.toList()

    /** 按文件名定位条目并应用变更，定位失败时静默忽略 */
    private fun updateItem(name: String, transform: (FileItem) -> Unit) {
        val index = items.indexOfFirst { it.name == name }
        if (index >= 0) {
            transform(items[index])
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], position + 1, callback)
    }

    override fun getItemCount(): Int = items.size

    /**
     * 条目视图持有者：负责按 [FileStatus] 切换控件可见性/文案/颜色，
     * 并绑定「打开 / 删除」点击与焦点呼吸动画（TV 遥控器交互）。
     */
    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fileName: TextView = itemView.findViewById(R.id.fileName)
        private val indexBadge: TextView = itemView.findViewById(R.id.indexBadge)
        private val fileSize: TextView = itemView.findViewById(R.id.fileSize)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val progressText: TextView = itemView.findViewById(R.id.progressText)
        private val fileStatus: TextView = itemView.findViewById(R.id.fileStatus)
        private val btnOpen: TextView = itemView.findViewById(R.id.btnOpen)
        private val btnDelete: TextView = itemView.findViewById(R.id.btnDelete)

        /**
         * 渲染条目：填充序号/名称/大小，按状态切换 UI，并绑定交互回调。
         * @param item 待渲染的条目
         * @param index 序号（从 1 开始，展示在条目最前面的圆形徽标上）
         * @param callback 用户操作回调（打开/删除）
         */
        fun bind(item: FileItem, index: Int, callback: FileItemCallback) {
            indexBadge.text = index.toString()
            fileName.text = item.name
            // 跑马灯仅在 selected 状态下滚动，文本未超宽时系统自动不滚动
            fileName.isSelected = true
            fileSize.text = formatSize(item.size)

            when (item.status) {
                FileStatus.UPLOADING -> {
                    progressBar.visibility = View.VISIBLE
                    progressText.visibility = View.VISIBLE
                    progressBar.progress = item.progress
                    progressText.text = "${item.progress}%"
                    fileStatus.text = "上传中"
                    fileStatus.setTextColor(0xFFE94560.toInt())
                    btnOpen.visibility = View.GONE
                }

                FileStatus.COMPLETED -> {
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.GONE
                    fileStatus.text = "已完成"
                    fileStatus.setTextColor(0xFF4CAF50.toInt())
                    btnOpen.visibility = View.VISIBLE
                }

                FileStatus.INSTALLING -> {
                    progressBar.visibility = View.VISIBLE
                    progressText.visibility = View.VISIBLE
                    progressBar.progress = 100
                    progressText.text = "安装中..."
                    fileStatus.text = "安装中"
                    fileStatus.setTextColor(0xFFE94560.toInt())
                    btnOpen.visibility = View.GONE
                }

                FileStatus.ERROR -> {
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.GONE
                    fileStatus.text = "失败"
                    fileStatus.setTextColor(0xFFE94560.toInt())
                    btnOpen.visibility = View.GONE
                }

                FileStatus.CANCELLED -> {
                    progressBar.visibility = View.GONE
                    progressText.visibility = View.GONE
                    fileStatus.text = "已取消"
                    fileStatus.setTextColor(0xFFB0B0B0.toInt())
                    btnOpen.visibility = View.GONE
                }
            }

            btnOpen.setOnClickListener { callback.onOpen(item) }
            btnDelete.setOnClickListener { callback.onDelete(item) }

            // 条目获得焦点时抬升阴影，TV 上提示当前选中项
            itemView.setOnFocusChangeListener { _, hasFocus ->
                itemView.elevation = if (hasFocus) 12f else 0f
            }
            setupFocusAnimation(btnOpen)
            setupFocusAnimation(btnDelete)
        }

        /**
         * 为操作按钮添加焦点呼吸动画：获得焦点时透明度 1.0→0.4→1.0 循环，
         * 失去焦点时停止动画并恢复不透明。动画实例挂在 view tag 上以便取消。
         */
        private fun setupFocusAnimation(view: TextView) {
            view.setOnFocusChangeListener { _, hasFocus ->
                (view.getTag() as? android.animation.ObjectAnimator)?.cancel()
                if (hasFocus) {
                    view.alpha = 1f
                    val animator =
                        android.animation.ObjectAnimator.ofFloat(view, "alpha", 1f, 0.4f, 1f)
                    animator.duration = 1000
                    animator.repeatCount = android.animation.ObjectAnimator.INFINITE
                    animator.start()
                    view.setTag(animator)
                } else {
                    view.alpha = 1f
                }
            }
        }

        /** 将字节数格式化为人类可读的大小文本（B / KB / MB / GB，保留 1 位小数） */
        private fun formatSize(size: Long): String {
            return when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", size / 1024.0)
                size < 1024 * 1024 * 1024 ->
                    String.format(Locale.US, "%.1f MB", size / (1024.0 * 1024))
                else -> String.format(Locale.US, "%.1f GB", size / (1024.0 * 1024 * 1024))
            }
        }
    }
}
