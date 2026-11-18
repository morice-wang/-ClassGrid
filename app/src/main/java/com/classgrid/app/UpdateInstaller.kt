package com.classgrid.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * 拉起系统安装器（第三阶段）。
 *
 * Android 不允许应用自己静默装包，唯一合规路径：
 * 1. 校验「安装未知应用」权限（8.0+ 是按 App 授权的）；
 * 2. 用 FileProvider 把 APK 以 content:// 暴露出去（Android 7.0+ 禁止 file://）；
 * 3. ACTION_VIEW + application/vnd.android.package-archive 交给系统安装器。
 *
 * 真正的包名/签名校验由系统安装器完成：签名不同会直接拒绝覆盖安装。
 */
object UpdateInstaller {

    /** 8.0+ 需要用户为「课格」单独打开「安装未知应用」。 */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** 跳到「安装未知应用」授权页；用户回来后由调用方重试。 */
    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun apkUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * 打开系统安装器。
     *
     * @return true 表示已拉起安装界面；false 表示缺权限或没有安装器。
     */
    fun install(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        if (!canInstall(context)) {
            openInstallPermissionSettings(context)
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri(context, file), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
