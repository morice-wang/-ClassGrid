package com.classgrid.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.Calendar

/**
 * v1.45.0 常驻通知：通知栏一直显示「今天 · 周X · 第N周」和下一节课。
 *
 * 用普通 ongoing 通知而不是前台服务：不需要一直跑进程，只在「一节课开始/结束」这些边界
 * 用 AlarmManager 唤醒一次重画（能精确就精确，不能就降级为不精确闹钟），平时零唤醒。
 *
 * Android 16（API 36）的 Live Updates 会把这类 ongoing 通知自动提升成状态栏胶囊；
 * 本包 compileSdk 34 调不到 API 36 的 setRequestPromotedOngoing，先按标准 ongoing 通知实现。
 */
object TodayNotify {

    const val CHANNEL_ID = "classgrid_today"
    const val NOTIFICATION_ID = 4101

    const val ACTION_REFRESH = "com.classgrid.app.action.TODAY_NOTIFY_REFRESH"
    const val ACTION_OFF = "com.classgrid.app.action.TODAY_NOTIFY_OFF"

    private const val REQ_OPEN = 201
    private const val REQ_REFRESH = 202
    private const val REQ_OFF = 203
    private const val REQ_ALARM = 204

    /** 开关打开时重画通知并排下一次刷新；关闭时撤掉通知和闹钟 */
    fun refresh(context: Context) {
        val app = context.applicationContext
        if (!Prefs.todayNotify(app)) {
            cancel(app)
            return
        }
        val snapshot = runCatching { TodayWidgetData.snapshot(app) }.getOrNull() ?: return
        if (!canPost(app)) return

        ensureChannel(app)
        val courses = CourseStore.load(app)
        val week = Prefs.currentWeek(app)
        val sections = SectionTimes.load(app)
        val now = Calendar.getInstance()
        val weekday = TodayWidgetData.weekdayOf(now)
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agenda)
            .setContentTitle(TodayWidgetData.headerText(snapshot))
            .setContentText(TodayWidgetData.statusText(snapshot))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    TodayWidgetData.courseLines(snapshot).joinToString("\n").ifBlank {
                        app.getString(R.string.today_notify_empty)
                    }
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(app))
            .addAction(0, app.getString(R.string.today_notify_action_refresh), refreshIntent(app))
            .addAction(0, app.getString(R.string.today_notify_action_off), offIntent(app))
            .build()

        runCatching { NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification) }
        scheduleNext(app, courses, week, weekday, nowMinutes, sections)
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        runCatching { NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID) }
        val alarm = app.getSystemService(AlarmManager::class.java)
        runCatching { alarm?.cancel(alarmIntent(app)) }
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.today_notify_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.today_notify_channel_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 在「下一节课开始 / 当前课结束」时刻唤醒重画；今天没有边界了就排到明天 07:00。
     * Android 12+ 没有精确闹钟权限时降级为不精确闹钟（最多差几分钟，可以接受）。
     */
    private fun scheduleNext(
        context: Context,
        courses: List<Course>,
        week: Int,
        weekday: Int,
        nowMinutes: Int,
        sections: List<SectionTimes.Section>
    ) {
        val today = courses.filter { it.dayOfWeek == weekday && it.activeInWeek(week) }
        val boundaries = today.flatMap { c ->
            listOf(
                TodayWidgetData.startMinutesOf(c, sections),
                TodayWidgetData.endMinutesOf(c, sections)
            )
        }.filter { it > nowMinutes }

        // v1.47.0：正在上课时每一分钟刷新一次，桌面组件的进度环和倒计时才会「走」起来
        val ongoing = today.any { c ->
            nowMinutes >= TodayWidgetData.startMinutesOf(c, sections) &&
                nowMinutes < TodayWidgetData.endMinutesOf(c, sections)
        }
        val now = Calendar.getInstance()
        if (ongoing) {
            val target = (now.clone() as Calendar).apply { add(Calendar.MINUTE, 1) }
            schedule(context, target.timeInMillis)
            return
        }

        val target = (now.clone() as Calendar).apply {
            if (boundaries.isNotEmpty()) {
                val next = boundaries.min()
                set(Calendar.HOUR_OF_DAY, next / 60)
                set(Calendar.MINUTE, next % 60)
                set(Calendar.SECOND, 5)
            } else {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, 7)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 5)
            }
        }

        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context)
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) {
                alarm.set(AlarmManager.RTC_WAKEUP, target.timeInMillis, pending)
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target.timeInMillis, pending)
            }
        }
    }

    /** 统一排闹钟：能精确就精确，不能就降级；同一 PendingIntent 每次覆盖旧的。 */
    private fun schedule(context: Context, timeInMillis: Long) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context)
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && !alarm.canScheduleExactAlarms()) {
                alarm.set(AlarmManager.RTC_WAKEUP, timeInMillis, pending)
            } else {
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeInMillis, pending)
            }
        }
    }

    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, TodayNotifyReceiver::class.java).apply { action = ACTION_REFRESH }
        return PendingIntent.getBroadcast(
            context,
            REQ_ALARM,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQ_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun refreshIntent(context: Context): PendingIntent {
        val intent = Intent(context, TodayNotifyReceiver::class.java).apply { action = ACTION_REFRESH }
        return PendingIntent.getBroadcast(
            context,
            REQ_REFRESH,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun offIntent(context: Context): PendingIntent {
        val intent = Intent(context, TodayNotifyReceiver::class.java).apply { action = ACTION_OFF }
        return PendingIntent.getBroadcast(
            context,
            REQ_OFF,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
