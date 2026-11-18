package com.classgrid.app

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 更新提示对话框（v1.47.6）：
 * 把「设置页手动检查」与「启动自动检查」两处的弹窗收拢成一个，避免两套文案/行为跑偏。
 *
 * - 手动检查（fromAutoCheck = false）：下载更新 / 稍后，用户主动点的，不写「跳过」。
 * - 自动检查（fromAutoCheck = true）：下载更新 / 忽略此版本 / 稍后。
 *   选「忽略此版本」后，同一个 versionCode 不再自动打扰（[Prefs.setUpdateSkippedVersion]）。
 * - 强制更新（清单 force=true 或 minVersionCode 高于本机）：只有「下载更新」，不能关掉。
 */
object UpdatePrompt {

    /** 通知权限请求码（下载进度与「点击安装」都在通知栏）。 */
    const val REQ_NOTIFICATIONS = 4310

    fun show(activity: Activity, info: UpdateInfo, fromAutoCheck: Boolean) {
        val force = info.requiresForce(BuildConfig.VERSION_CODE)
        val size = info.sizeLabel()
        val title = when {
            force -> activity.getString(R.string.update_force_title, info.versionName)
            size.isEmpty() -> activity.getString(R.string.update_found, info.versionName)
            else -> activity.getString(R.string.update_found_size, info.versionName, size)
        }
        val message = buildString {
            append(info.changelog.ifEmpty { activity.getString(R.string.update_no_changelog) })
            append("\n\n")
            append(
                activity.getString(
                    if (force) R.string.update_force_hint else R.string.update_phase_hint
                )
            )
        }

        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(!force)
            .setPositiveButton(R.string.update_download) { _, _ -> startDownload(activity, info) }

        when {
            force -> Unit
            fromAutoCheck -> builder
                .setNeutralButton(R.string.update_skip_version) { _, _ ->
                    // 记下这个版本：下次自动检查（ignoreSkip=false）不再为它弹窗
                    Prefs.setUpdateSkippedVersion(activity, info.versionCode)
                }
                .setNegativeButton(R.string.update_later, null)
            else -> builder
                .setNeutralButton(R.string.update_copy_url) { _, _ -> copyUrl(activity, info) }
                .setNegativeButton(R.string.update_later, null)
        }
        builder.show()
    }

    /** 手动检查时的兜底：把下载地址复制出来，可以到浏览器里手动下载安装。 */
    private fun copyUrl(activity: Activity, info: UpdateInfo) {
        val url = info.primaryUrl
        if (url.isEmpty()) {
            Toast.makeText(activity, R.string.update_url_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("update", url))
        Toast.makeText(activity, R.string.update_url_copied, Toast.LENGTH_SHORT).show()
    }

    /** 交给前台服务下载，进度在通知栏；最后一步安装由用户点通知确认。 */
    fun startDownload(activity: Activity, info: UpdateInfo) {
        if (!UpdateInstaller.canInstall(activity)) {
            Toast.makeText(activity, R.string.update_need_install_permission, Toast.LENGTH_LONG).show()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(activity, R.string.update_need_notification, Toast.LENGTH_LONG).show()
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_NOTIFICATIONS
            )
        }
        UpdateService.start(activity, info)
        Toast.makeText(
            activity,
            activity.getString(R.string.update_downloading, info.versionName),
            Toast.LENGTH_SHORT
        ).show()
    }
}
