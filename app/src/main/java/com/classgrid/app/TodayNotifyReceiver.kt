package com.classgrid.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * v1.45.0 常驻通知的接收器：
 *  - 通知上的「刷新」「关闭」按钮
 *  - 一节课开始/结束的闹钟
 *  - 开机、日期/时间/时区变化后重画一次
 */
class TodayNotifyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TodayNotify.ACTION_OFF -> {
                Prefs.setTodayNotify(context, false)
                TodayNotify.cancel(context)
            }

            TodayNotify.ACTION_REFRESH,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> WidgetRefresh.refresh(context)
        }
    }
}
