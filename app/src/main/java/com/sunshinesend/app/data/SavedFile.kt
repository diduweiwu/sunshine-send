package com.sunshinesend.app.data

/**
 * 传输记录实体。
 *
 * 表示一次已完成的文件传输，既用于内存中的列表维护，也用于
 * SharedPreferences 中的 JSON 序列化/反序列化（由 Gson 完成）。
 *
 * @property name 文件名（展示用，同时是上传目录内的唯一标识）
 * @property size 文件大小（字节）
 * @property path 文件的绝对路径，用于重启后校验文件是否存在并重新打开
 * @property time 传输完成时间戳（毫秒），用于列表排序（新的在前）
 *
 * 使用示例：
 * ```kotlin
 * val record = SavedFile(
 *     name = "movie.mp4",
 *     size = file.length(),
 *     path = file.absolutePath,
 *     time = System.currentTimeMillis()
 * )
 * ```
 */
data class SavedFile(
    val name: String,
    val size: Long,
    val path: String,
    val time: Long
)
