package com.sunshinesend.app.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 传输记录持久化仓库。
 *
 * 采用「SharedPreferences + 目录扫描」双层存储策略：
 * 1. 主存储：SharedPreferences 中以 JSON 形式保存 [SavedFile] 列表；
 * 2. 兜底存储：启动时扫描上传目录，即使 SharedPreferences 被系统
 *    清理或记录损坏，也能根据磁盘上的文件自动恢复列表。
 *
 * 保存时机：上传完成、记录删除。读取时机：仅 [load] 一次（Activity 创建时）。
 *
 * 使用示例：
 * ```kotlin
 * val repo = FileRepository(context)
 * val records = repo.load()                 // 启动时恢复列表
 * repo.upsert(SavedFile(...))               // 上传完成后保存
 * repo.remove("a.txt")                      // 删除记录
 * ```
 *
 * @param context 任意 Context（内部只使用 application 级资源）
 */
class FileRepository(context: Context) {

    companion object {
        private const val TAG = "FileRepository"

        /** SharedPreferences 文件名 */
        private const val PREFS_NAME = "sunshinesend"

        /** SharedPreferences 中保存记录列表 JSON 的 key */
        private const val KEY_FILES = "files"

        /** 上传文件存放的子目录名（位于 app 外部私有目录下） */
        const val UPLOAD_DIR_NAME = "Uploads"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** 上传文件目录：/sdcard/Android/data/{pkg}/files/Uploads */
    val uploadDir: File = File(context.getExternalFilesDir(null), UPLOAD_DIR_NAME)
        .apply { if (!exists()) mkdirs() }

    /**
     * 加载全部有效传输记录（按时间升序返回，配合 UI 头插即为最新在前）。
     *
     * 恢复流程：
     * 1. 优先读取 SharedPreferences 中的 JSON 记录，解析失败时记录日志并继续；
     * 2. 扫描 [uploadDir]，把「记录中没有但磁盘上存在」的文件补录进来（兜底恢复）；
     * 3. 过滤掉磁盘上已不存在的记录（文件被手动删除等场景）；
     * 4. 若发生了兜底补录，立即将合并结果重新落盘，保证两层存储一致。
     *
     * @return 有效记录列表；磁盘文件已被删除的记录不会包含在内
     */
    fun load(): List<SavedFile> {
        val records = readPersistedRecords().toMutableList()
        var recovered = false

        // 目录扫描兜底：补录 SharedPreferences 丢失的记录
        for (file in uploadDir.listFiles() ?: emptyArray()) {
            if (!file.isFile || file.name.contains(".tmp_")) continue
            if (records.any { it.path == file.absolutePath }) continue
            Log.d(TAG, "Recover record from dir scan: ${file.name}")
            records.add(
                SavedFile(
                    name = file.name,
                    size = file.length(),
                    path = file.absolutePath,
                    time = file.lastModified()
                )
            )
            recovered = true
        }

        // 过滤磁盘上已不存在的文件
        val valid = records.filter { File(it.path).exists() }
            .onEach { /* keep order */ }
            .toMutableList()
        valid.sortBy { it.time }

        // 兜底补录过则回写，使持久化记录与磁盘保持一致
        if (recovered) {
            Log.d(TAG, "Recovered ${valid.size} records from dir scan, re-persisting")
            saveAll(valid)
        }
        return valid
    }

    /**
     * 新增或更新一条记录（按文件名去重），并立即同步落盘。
     *
     * 同名文件重复上传时，旧记录会被替换为新记录。
     *
     * @param record 待保存的传输记录
     */
    fun upsert(record: SavedFile) {
        val current = readPersistedRecords().filter { it.name != record.name }
        saveAll((current + record).sortedBy { it.time })
    }

    /**
     * 按文件名删除记录并同步落盘。
     *
     * 注意：本方法只删除持久化记录，不删除磁盘文件；
     * 文件的删除由调用方（UI 层）负责。
     *
     * @param name 要删除的记录对应的文件名
     */
    fun remove(name: String) {
        saveAll(readPersistedRecords().filter { it.name != name })
    }

    /**
     * 从 SharedPreferences 读取记录列表。
     *
     * 解析失败（记录损坏/被篡改）时返回空列表并记录日志，
     * 由 [load] 中的目录扫描兜底，保证列表不丢。
     */
    private fun readPersistedRecords(): List<SavedFile> {
        val json = prefs.getString(KEY_FILES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<SavedFile>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Parse persisted records failed, fallback to dir scan", e)
            emptyList()
        }
    }

    /**
     * 将记录列表整体写入 SharedPreferences。
     *
     * 使用 [android.content.SharedPreferences.Editor.commit] 同步落盘，
     * 避免进程被系统立刻杀掉时异步写入丢失。
     */
    private fun saveAll(records: List<SavedFile>) {
        prefs.edit().putString(KEY_FILES, gson.toJson(records)).commit()
    }
}
