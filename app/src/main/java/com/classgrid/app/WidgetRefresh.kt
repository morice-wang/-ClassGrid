package com.classgrid.app

import android.content.Context

/**
 * v1.44.0 桌面小组件刷新入口。
 *
 * 所有会改变「今天的课」的地方（导入成功、课表增删改、切课表、改学期开始/节次时间/主题）
 * 调一下这里，桌面组件立刻重画；没有添加组件时几乎零开销（getAppWidgetIds 为空直接 return）。
 */
object WidgetRefresh {

    fun refresh(context: Context) {
        runCatching { TodayWidgetProvider.refreshAll(context.applicationContext) }
        // v1.45.0：通知栏常驻卡片与桌面组件共用同一份「今天的课」，一起刷新
        runCatching { TodayNotify.refresh(context.applicationContext) }
    }
}
