package com.classgrid.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * 前台下载服务（第二阶段）。
 *
 * 为什么用前台服务：25MB 的 APK 在后台下载会被系统杀进程；前台服务 + 进度通知
 * 既能保活，用户也能看到进度并取消。下载在主线程之外的 worker 线程执行。
 *
 * 完成后发一条「点击安装」通知，点击进入 [UpdateInstallActivity]：
 * 它检查「安装未知来源」权限，没权限就引导去开，有权限就直接拉起系统安装器。
 * （Android 不允许静默安装，这一步必须用户点一次。）
 */
class UpdateService : Service() {

    companion object {
        const val ACTION_START = "com.classgrid.app.action.UPDATE_DOWNLOAD"
        const val ACTION_CANCEL = "com.classgrid.app.action.UPDATE_CANCEL"
        private const val EXTRA_VERSION_CODE = "version_code"
        private const val EXTRA_VERSION_NAME = "version_name"
        private const val EXTRA_SHA256 = "sha256"
        private const val EXTRA_SIZE = "size"
        private const val EXTRA_URLS = "urls"

        private const val CHANNEL_ID = "classgrid_updates"
        private const val NOTIFICATION_PROGRESS = 4201
        internal const val NOTIFICATION_DONE = 4202

        /** 安装流程开始后清掉「点击安装」通知，避免它一直挂在通知栏。 */
        fun clearDone(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_DONE)
        }

        fun start(context: Context, info: UpdateInfo) {
            val intent = Intent(context, UpdateService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_VERSION_CODE, info.versionCode)
                putExtra(EXTRA_VERSION_NAME, info.versionName)
                putExtra(EXTRA_SHA256, info.sha256)
                putExtra(EXTRA_SIZE, info.size)
                putStringArrayListExtra(EXTRA_URLS, ArrayList(info.urls))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, UpdateService::class.java).setAction(ACTION_CANCEL)
            )
        }

        private fun infoFrom(intent: Intent): UpdateInfo? {
            val versionCode = intent.getIntExtra(EXTRA_VERSION_CODE, 0)
            val sha = intent.getStringExtra(EXTRA_SHA256).orEmpty()
            val urls = intent.getStringArrayListExtra(EXTRA_URLS)?.filter { it.isNotBlank() }.orEmpty()
            if (versionCode <= 0 || sha.length != 64 || urls.isEmpty()) return null
            return UpdateInfo(
                versionCode = versionCode,
                versionName = intent.getStringExtra(EXTRA_VERSION_NAME).orEmpty(),
                minVersionCode = 0,
                size = intent.getLongExtra(EXTRA_SIZE, 0L),
                sha256 = sha,
                urls = urls,
                changelog = "",
                force = false,
                publishedAt = "",
                manifestUrl = ""
            )
        }
    }

    @Volatile
    private var cancelled = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelled = true
            worker?.interrupt()
            stopSelf()
            return START_NOT_STICKY
        }

        val info = intent?.let { infoFrom(it) }
        if (info == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        cancelled = false
        ensureChannel()
        startForeground(NOTIFICATION_PROGRESS, progressNotification(info, 0))

        worker = Thread {
            try {
                UpdateDownloader.download(this, info) { done, total ->
                    notifyProgress(info, done, total)
                }
                onDownloaded(info)
            } catch (e: UpdateDownloader.CancelledException) {
                // 用户取消：什么都不用发
            } catch (e: Exception) {
                onFailed(info)
            } finally {
                // 先摘掉前台状态并移除进度通知，再让服务结束；DETACH 会把 4201 留在通知栏
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                NotificationManagerCompat.from(this).cancel(NOTIFICATION_PROGRESS)
                stopSelf()
            }
        }.apply { name = "update-download" }
        worker?.start()

        return START_NOT_STICKY
    }

    private fun onDownloaded(info: UpdateInfo) {
        val installIntent = UpdateInstallActivity.intent(this, info.versionCode)
        val pending = PendingIntent.getActivity(
            this, 0, installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(getString(R.string.update_download_done, info.versionName))
            .setContentText(getString(R.string.update_tap_to_install))
            .setContentIntent(pending)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notify(NOTIFICATION_DONE, notification)
    }

    private fun onFailed(info: UpdateInfo) {
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(getString(R.string.update_download_failed, info.versionName))
            .setContentText(getString(R.string.update_download_retry_hint))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        notify(NOTIFICATION_DONE, notification)
    }

    private fun notifyProgress(info: UpdateInfo, done: Long, total: Long) {
        val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
        NotificationManagerCompat.from(this)
            .notify(NOTIFICATION_PROGRESS, progressNotification(info, percent))
    }

    private fun progressNotification(info: UpdateInfo, percent: Int): android.app.Notification {
        val cancel = PendingIntent.getService(
            this, 2, Intent(this, UpdateService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_refresh)
            .setContentTitle(getString(R.string.update_downloading, info.versionName))
            .setContentText(if (info.size > 0) getString(R.string.update_downloading_size, info.sizeLabel()) else "")
            .setProgress(100, percent, info.size <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.update_cancel), cancel)
        if (info.size > 0) {
            builder.setSubText(getString(R.string.update_download_percent, percent))
        }
        return builder.build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.update_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.update_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        // Android 13 没给通知权限时 notify 会抛 SecurityException；下载本身不受影响
        runCatching { NotificationManagerCompat.from(this).notify(id, notification) }
            .onFailure {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(this, R.string.update_notify_failed, Toast.LENGTH_SHORT).show()
                }
            }
    }
}
