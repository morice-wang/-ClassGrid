package com.classgrid.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * 仅 debug 包使用的「一键把小组件钉到桌面」入口（v1.44.0 验证用）。
 *
 * Android 8+ 的 launcher 支持 requestPinAppWidget：调起来系统会弹「添加小组件」确认框，
 * 比在桌面长按拖拽稳定得多，也不需要人工找坐标。release 包不包含这个类（见 app/src/debug/AndroidManifest.xml）。
 *
 * 用法：adb shell am start -n com.classgrid.app/.WidgetPinDebugActivity
 */
class WidgetPinDebugActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = AppWidgetManager.getInstance(this)
        if (manager == null || !manager.isRequestPinAppWidgetSupported) {
            Toast.makeText(this, "当前桌面不支持 requestPinAppWidget", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val provider = ComponentName(this, TodayWidgetProvider::class.java)
        val requested = runCatching { manager.requestPinAppWidget(provider, null, null) }.getOrDefault(false)
        Toast.makeText(
            this,
            if (requested) "已请求添加小组件，请在系统弹窗里确认" else "请求失败",
            Toast.LENGTH_SHORT
        ).show()
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 1200)
    }
}
