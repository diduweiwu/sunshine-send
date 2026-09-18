package com.sunshinesend.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 安装工具。
 *
 * 使用示例：
 * ```kotlin
 * if (ApkInstaller.isApk(fileName)) {
 *     ApkInstaller.install(context, apkFile)   // 拉起系统安装器
 * }
 * ```
 */
object ApkInstaller {

    /**
     * 拉起系统安装器安装指定 APK。
     *
     * - Android 7.0+：通过 FileProvider 生成 content:// Uri 并授予读权限；
     * - Android 7.0 以下：直接使用 file:// Uri。
     *
     * 调用前需确保已拥有「安装未知来源应用」权限
     * （参见 [com.sunshinesend.app.update.UpdateManager.hasUpdatePermission]）。
     * 安装页拉起失败时在主线程 Toast 提示，不抛异常。
     *
     * @param context 任意 Context
     * @param apkFile 待安装的 APK 文件（必须存在且完整）
     */
    fun install(context: Context, apkFile: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
                intent.setDataAndType(uri, "application/vnd.android.package-archive")
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                intent.setDataAndType(
                    Uri.fromFile(apkFile),
                    "application/vnd.android.package-archive"
                )
            }

            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, "安装失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 判断文件名是否为 APK 文件（按 .apk 后缀，忽略大小写）。
     *
     * @param fileName 待判断的文件名
     * @return true 表示是 APK，上传完成后应走自动安装流程
     */
    fun isApk(fileName: String): Boolean {
        return fileName.lowercase().endsWith(".apk")
    }
}
