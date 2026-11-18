package com.classgrid.app

import android.content.Context

/**
 * 课程色板入口。
 *
 * v1.34.0 起色值不再硬编码在本文件，而是全部转发给 [AppTheme]：
 * 这里只负责「按 colorIndex 取课程 9 色 / 取淡化色 / 取强调色」，具体色值由当前主题决定。
 * 好处：老代码调用点（ScheduleView、MainActivity、SettingsActivity）一行都不用改就能跟随主题。
 */
object ScheduleColor {

    data class Palette(val bg: Int, val fg: Int, val accent: Int)

    fun count(context: Context): Int = AppTheme.current(context).courseBgLight.size

    /** 兼容旧调用：默认主题的色族数量（10），仅用于无需 Context 的场合 */
    fun count(): Int = AppTheme.ALL[AppTheme.CLASSIC].courseBgLight.size

    fun palette(context: Context, index: Int, darkMode: Boolean): Palette {
        val spec = AppTheme.current(context)
        val bgs = if (darkMode) spec.courseBgDark else spec.courseBgLight
        val fgs = if (darkMode) spec.courseFgDark else spec.courseFgLight
        val accents = if (darkMode) spec.courseAccentDark else spec.courseAccentLight
        val n = bgs.size
        val i = ((index % n) + n) % n
        return Palette(bgs[i], fgs[i], accents[i])
    }

    fun isDarkMode(context: Context): Boolean {
        val mask = context.resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return mask == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /** 课表卡片在非本周（周次不符）时用灰阶淡化处理 */
    fun inactiveBg(context: Context, darkMode: Boolean): Int =
        AppTheme.current(context).let { if (darkMode) it.inactiveBgDark else it.inactiveBgLight }

    fun inactiveFg(context: Context, darkMode: Boolean): Int =
        AppTheme.current(context).let { if (darkMode) it.inactiveFgDark else it.inactiveFgLight }

    /** 主题强调色，用于当前周、当前节次高亮 */
    fun primary(context: Context): Int = AppTheme.primary(context, isDarkMode(context))
}
