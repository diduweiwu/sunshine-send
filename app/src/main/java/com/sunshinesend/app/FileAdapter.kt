package com.sunshinesend.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

data class FileItem(
    val name: String,
    val size: Long,
    var file: File?,
    var progress: Int = 100,
    var status: FileStatus = FileStatus.COMPLETED
)

enum class FileStatus {
    UPLOADING,
    COMPLETED,
    ERROR,
    INSTALLING
}

interface FileItemCallback {
    fun onOpen(item: FileItem)
    fun onDelete(item: FileItem)
}

class FileAdapter(private val callback: FileItemCallback) : RecyclerView.Adapter<FileAdapter.ViewHolder>() {

    private val items = mutableListOf<FileItem>()

    fun addItem(item: FileItem) {
        items.add(0, item)
        notifyItemInserted(0)
    }

    fun removeItem(name: String) {
        val index = items.indexOfFirst { it.name == name }
        if (index >= 0) {
            items.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    fun updateProgress(name: String, progress: Int) {
        val index = items.indexOfFirst { it.name == name }
        if (index >= 0) {
            items[index].progress = progress
            items[index].status = FileStatus.UPLOADING
            notifyItemChanged(index)
        }
    }

    fun completeUpload(name: String, file: File) {
        val index = items.indexOfFirst { it.name == name }
        if (index >= 0) {
            items[index].file = file
            items[index].progress = 100
            items[index].status = FileStatus.COMPLETED
            notifyItemChanged(index)
        }
    }

    fun setInstalling(name: String) {
        val index = items.indexOfFirst { it.name == name }
        if (index >= 0) {
            items[index].status = FileStatus.INSTALLING
            notifyItemChanged(index)
        }
    }

    fun setError(name: String) {
        val index = items.indexOfFirst { it.name == name }
        if (index >= 0) {
            items[index].status = FileStatus.ERROR
            notifyItemChanged(index)
        }
    }

    fun getItems(): List<FileItem> = items.toList()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_file, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], callback)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val fileName: TextView = itemView.findViewById(R.id.fileName)
        private val fileSize: TextView = itemView.findViewById(R.id.fileSize)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progressBar)
        private val progressText: TextView = itemView.findViewById(R.id.progressText)
        private val fileStatus: TextView = itemView.findViewById(R.id.fileStatus)
        private val btnOpen: TextView = itemView.findViewById(R.id.btnOpen)
        private val btnDelete: TextView = itemView.findViewById(R.id.btnDelete)

        fun bind(item: FileItem, callback: FileItemCallback) {
            fileName.text = item.name
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
            }

            btnOpen.setOnClickListener { callback.onOpen(item) }
            btnDelete.setOnClickListener { callback.onDelete(item) }

            itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    itemView.elevation = 12f
                } else {
                    itemView.elevation = 0f
                }
            }
            setupFocusAnimation(btnOpen)
            setupFocusAnimation(btnDelete)
        }

        private fun setupFocusAnimation(view: TextView) {
            view.setOnFocusChangeListener { _, hasFocus ->
                (view.getTag() as? android.animation.ObjectAnimator)?.cancel()
                if (hasFocus) {
                    view.alpha = 1f
                    val animator = android.animation.ObjectAnimator.ofFloat(view, "alpha", 1f, 0.4f, 1f)
                    animator.duration = 1000
                    animator.repeatCount = android.animation.ObjectAnimator.INFINITE
                    animator.start()
                    view.setTag(animator)
                } else {
                    view.alpha = 1f
                }
            }
        }

        private fun formatSize(size: Long): String {
            return when {
                size < 1024 -> "$size B"
                size < 1024 * 1024 -> String.format("%.1f KB", size / 1024.0)
                size < 1024 * 1024 * 1024 -> String.format("%.1f MB", size / (1024.0 * 1024))
                else -> String.format("%.1f GB", size / (1024.0 * 1024 * 1024))
            }
        }
    }
}
