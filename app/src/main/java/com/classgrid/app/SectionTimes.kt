package com.classgrid.app

import android.content.Context

/**
 * 节次时间（兼容门面，v1.48.0 起真正的数据在 [ScheduleProfiles]）
 *
 * 为什么要留这一层：全 App 有 20 多个调用点（课表绘制、今日小组件、通知、导入导出、
 * 解析器）都按「取一份 Section 列表」来用，签名不动就能全线跟随新的作息库。
 *
 * 取的是「当前课表绑定的那份作息」；没绑定就跟随全局当前作息。
 * 因为 [ScheduleView] 的 onDraw 也会调 [currentSection]，这里做了进程内缓存，
 * 任何写操作 / 切换课表都会 [invalidate]。
 */
object SectionTimes {

    data class Section(val start: String, val end: String) // "HH:MM"

    @Volatile
    private var cache: List<Section>? = null

    /** 作息数据变了、或者当前课表换了，都要调一次 */
    fun invalidate() {
        cache = null
    }

    /** 广外 15 节（数据在 ScheduleProfiles，避免两处硬编码不一致） */
    fun default(): List<Section> = ScheduleProfiles.gdufsSections()

    /** 当前课表生效的作息（UI 用来显示名字、判断是否是预设） */
    fun activeProfile(context: Context): ScheduleProfiles.Profile =
        ScheduleProfiles.profileFor(context, TimetableStore.activeId(context))

    private fun resolve(context: Context): List<Section> {
        cache?.let { return it }
        val profile = activeProfile(context)
        val list = profile.sections.ifEmpty { default() }
        cache = list
        return list
    }

    fun load(context: Context): MutableList<Section> = resolve(context).toMutableList()

    /**
     * 覆盖当前课表生效作息的时间表。老入口保留，新代码请直接用 ScheduleProfiles.update。
     */
    fun save(context: Context, list: List<Section>) {
        if (list.isEmpty()) return
        val profile = activeProfile(context)
        ScheduleProfiles.update(context, profile.copy(sections = list.toList()))
        invalidate()
    }

    /** 把当前作息恢复成它来源的预设（没有预设来源就恢复广外默认） */
    fun reset(context: Context) {
        val profile = activeProfile(context)
        val preset = ScheduleProfiles.preset(profile.presetKey)
        ScheduleProfiles.update(
            context,
            profile.copy(sections = preset?.sections ?: default())
        )
        invalidate()
    }

    /** 第 section 节（1-based）的时间 */
    fun sectionAt(context: Context, section: Int): Section? = load(context).getOrNull(section - 1)

    /** 当前正在上的节次（1-based），非上课时间返回 -1 */
    fun currentSection(context: Context): Int {
        val cal = java.util.Calendar.getInstance()
        val minutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val list = resolve(context)
        for ((i, s) in list.withIndex()) {
            val sm = toMinutes(s.start)
            val em = toMinutes(s.end)
            if (sm != null && em != null && minutes >= sm && minutes <= em) return i + 1
        }
        return -1
    }

    fun toMinutes(hhmm: String): Int? {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(hhmm.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }

    fun isValid(hhmm: String): Boolean = toMinutes(hhmm) != null
}
