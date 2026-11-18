package com.classgrid.app

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

/**
 * 桌面启动图标切换（activity-alias 方案）。
 *
 * 所有候选入口都是 LauncherPurple / IcoAlt1..6 这 7 个 alias，
 * MainActivity 本身不带 LAUNCHER filter（始终可被 alias 启动），因此桌面始终只显示一个图标：
 * 切换 = 启用选中的 alias + 禁用其余 6 个。
 *
 * index 0 = 默认（最初紫色九宫格）；1..6 = 可选图标；7 = v1.34.0 新增「极光」。
 */
object IconSwitcher {

    /** 入口组件后缀；index 与 Prefs.launcherIcon 一一对应：0=默认(最初紫色)，1..6=可选图标 */
    val ENTRIES = listOf(
        "LauncherPurple",
        "IcoAlt1",
        "IcoAlt2",
        "IcoAlt3",
        "IcoAlt4",
        "IcoAlt5",
        "IcoAlt6",
        "IcoAlt7"
    )

    fun componentFor(context: Context, index: Int): ComponentName =
        ComponentName(context.packageName, context.packageName + "." + ENTRIES[index])

    /**
     * 启动时校准：把系统当前启用的入口与用户记录的选择比对，不一致时重新应用。
     * manifest 默认 enabled=true 的只有 LauncherPurple（index 0）。
     */
    fun ensureApplied(context: Context) {
        val target = Prefs.launcherIcon(context).coerceIn(0, ENTRIES.size - 1)
        val pm = context.packageManager
        var currentEnabled = -1
        for (i in ENTRIES.indices) {
            val comp = componentFor(context, i)
            when (pm.getComponentEnabledSetting(comp)) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> currentEnabled = i
                // manifest 默认 enabled=true 的只有 LauncherPurple（index 0）
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> if (i == 0) currentEnabled = i
            }
        }
        if (currentEnabled != target) apply(context, target)
    }

    /**
     * 应用指定图标。
     * 国产 ROM 桌面常对"同一瞬间禁用旧入口+启用新入口"处理失效（图标不刷新），
     * 因此分两步：先立即启用目标入口（让桌面先添加新图标），延迟 400ms 再禁用其余入口，
     * 给 Launcher 明确的"先添加、后移除"信号。
     */
    fun apply(context: Context, index: Int) {
        val idx = index.coerceIn(0, ENTRIES.size - 1)
        val pm = context.packageManager
        val appContext = context.applicationContext

        // 1. 先启用目标入口
        pm.setComponentEnabledSetting(
            componentFor(appContext, idx),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
        Prefs.setLauncherIcon(appContext, idx)

        // 2. 延迟后禁用其余入口（期间桌面可能短暂出现两个图标，属正常）
        Handler(Looper.getMainLooper()).postDelayed({
            for (i in ENTRIES.indices) {
                if (i == idx) continue
                pm.setComponentEnabledSetting(
                    componentFor(appContext, i),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }, 400)
    }
}
