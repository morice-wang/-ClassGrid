package com.classgrid.app

import android.content.Context
import java.util.Calendar
import java.util.Locale

/**
 * v1.44.0 桌面小组件的「今天的课」数据层。
 *
 * 只做计算，不碰 RemoteViews：输入课程、当前周、今天星期几、当前分钟数，
 * 输出小组件要显示的 4 行 + 多余门数，方便单测（见 TodayWidgetDataTest）。
 *
 * @see TodayWidgetProvider 负责把这里的结果画到桌面上
 */
object TodayWidgetData {

    /** 一行课程在组件里的状态 */
    const val STATE_DONE = 0      // 已上完
    const val STATE_ONGOING = 1   // 正在上
    const val STATE_NEXT = 2      // 下一节（还没开始）
    const val STATE_LATER = 3     // 后面还有

    /** 组件能显示的最大行数（4x2 布局） */
    const val MAX_ROWS = 4

    data class Row(
        val time: String,
        val name: String,
        val room: String,
        val state: Int,
        val startMinutes: Int = 0,
        val endMinutes: Int = 0
    )

    data class Snapshot(
        val week: Int,
        val weekday: Int,          // 1=周一 … 7=周日
        val rows: List<Row>,
        val extra: Int,            // 4 行之外还剩几门
        val todayCount: Int,       // 今天一共有几门
        val allDone: Boolean,      // 今天的课都上完了
        val nowMinutes: Int = 0,
        val allRows: List<Row> = rows
    ) {
        val hasCourses: Boolean get() = todayCount > 0

        /** v1.46.0 三栏布局：已上完（左上，小） */
        val doneRows: List<Row> get() = allRows.filter { it.state == STATE_DONE }

        /** 正在上 / 下一节（中间，大），没有则 null */
        val focusRow: Row? get() = allRows.firstOrNull { it.state == STATE_ONGOING }
            ?: allRows.firstOrNull { it.state == STATE_NEXT }

        /** 关注项之后还要上的（右上，小） */
        val laterRows: List<Row> get() {
            val focus = focusRow ?: return emptyList()
            val start = allRows.indexOf(focus)
            if (start < 0) return emptyList()
            return allRows.drop(start + 1).filter { it.state == STATE_NEXT || it.state == STATE_LATER }
        }
    }

    /**
     * 从 Context 读取真实数据；组件 onUpdate / 刷新广播都走这个。
     */
    fun snapshot(context: Context, now: Calendar = Calendar.getInstance()): Snapshot {
        val courses = CourseStore.load(context)
        val week = Prefs.currentWeek(context)
        val sections = SectionTimes.load(context)
        val weekday = weekdayOf(now)
        val minutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return build(courses, week, weekday, minutes, sections)
    }

    /**
     * 纯函数：不读磁盘、不读系统时间，单测直接用。
     *
     * @param sections 节次作息（长度至少覆盖课程节次）
     */
    fun build(
        courses: List<Course>,
        week: Int,
        weekday: Int,
        nowMinutes: Int,
        sections: List<SectionTimes.Section>,
        maxRows: Int = MAX_ROWS
    ): Snapshot {
        val today = courses
            .filter { it.dayOfWeek == weekday && it.activeInWeek(week) }
            .sortedWith(compareBy({ it.startSection }, { it.endSection }, { it.name }))

        if (today.isEmpty()) {
            return Snapshot(week, weekday, emptyList(), 0, 0, false, nowMinutes)
        }

        // 先按时间给每门课定位状态，再决定「下一节」标记
        val states = today.map { c ->
            val (start, end) = timesOf(c, sections)
            when {
                nowMinutes >= end -> STATE_DONE
                nowMinutes >= start -> STATE_ONGOING
                else -> STATE_LATER
            }
        }
        val nextIndex = states.indexOfFirst { it != STATE_DONE && it != STATE_ONGOING }
        val rows = today.mapIndexed { i, c ->
            val (start, end) = timesOf(c, sections)
            val state = when {
                states[i] == STATE_LATER && i == nextIndex -> STATE_NEXT
                else -> states[i]
            }
            Row(
                time = timeLabel(c, sections),
                name = c.name,
                room = c.roomLabel(),
                state = state,
                startMinutes = start,
                endMinutes = end
            )
        }
        val visible = rows.take(maxRows)
        return Snapshot(
            week = week,
            weekday = weekday,
            rows = visible,
            extra = (rows.size - visible.size).coerceAtLeast(0),
            todayCount = rows.size,
            allDone = states.all { it == STATE_DONE },
            nowMinutes = nowMinutes,
            allRows = rows
        )
    }

    /** 1=周一 … 7=周日（Calendar 的 1 是周日，这里换成 ISO 习惯） */
    fun weekdayOf(now: Calendar): Int {
        val cal = now.get(Calendar.DAY_OF_WEEK) // 1=周日 … 7=周六
        return ((cal - Calendar.MONDAY + 7) % 7) + 1
    }

    /** 课程实际起止分钟；课程自带 startTime/endTime 时优先用，否则查作息表 */
    private fun timesOf(course: Course, sections: List<SectionTimes.Section>): Pair<Int, Int> {
        val start = parseMinutes(course.startTime)
            ?: sectionMinutes(sections, course.startSection, start = true)
        val end = parseMinutes(course.endTime)
            ?: sectionMinutes(sections, course.endSection, start = false)
        return start to end
    }

    private fun sectionMinutes(sections: List<SectionTimes.Section>, section: Int, start: Boolean): Int {
        val s = sections.getOrNull(section - 1) ?: return if (start) 0 else 24 * 60
        return parseMinutes(if (start) s.start else s.end) ?: if (start) 0 else 24 * 60
    }

    /** "08:30-09:10"；缺时间时退化成「第1-2节」 */
    fun timeLabel(course: Course, sections: List<SectionTimes.Section>): String {
        val (s, e) = timesOf(course, sections)
        if (s <= 0 || e >= 24 * 60 || s >= e) {
            return if (course.startSection == course.endSection) {
                "第${course.startSection}节"
            } else {
                "第${course.startSection}-${course.endSection}节"
            }
        }
        return String.format(Locale.US, "%02d:%02d-%02d:%02d", s / 60, s % 60, e / 60, e % 60)
    }

    // ===== v1.45.0 常驻通知文案（同样纯函数，便于单测）=====

    /** 通知标题：「今天：周四 · 第10周」 */
    fun headerText(snapshot: Snapshot): String =
        "今天：${weekdayName(snapshot.weekday)} · 第${snapshot.week}周"

    /** 1=周一 … 7=周日 → 「一」…「日」 */
    fun weekdayName(day: Int): String {
        val names = arrayOf("一", "二", "三", "四", "五", "六", "日")
        return names.getOrElse(day - 1) { "一" }
    }

    /** 通知正文：正在上 / 下一节倒计时 / 已上完 / 没课 */
    fun statusText(snapshot: Snapshot): String {
        if (!snapshot.hasCourses) return "今天没有课，好好休息"

        val ongoing = snapshot.rows.firstOrNull { it.state == STATE_ONGOING }
        if (ongoing != null) {
            return buildString {
                append("正在上 ").append(ongoing.name)
                if (ongoing.room.isNotBlank()) append(" · ").append(ongoing.room)
            }
        }

        val next = snapshot.rows.firstOrNull { it.state == STATE_NEXT }
        if (next != null) {
            val minutes = next.startMinutes - snapshot.nowMinutes
            val eta = if (minutes > 0) "还有 $minutes 分钟" else "马上开始"
            return "下一节 ${clock(next.startMinutes)} ${next.name} · $eta"
        }

        if (snapshot.allDone) return "今天的课已上完（共 ${snapshot.todayCount} 门）"
        return "今天共 ${snapshot.todayCount} 门课"
    }

    /** 展开通知时逐行显示：「08:30-09:10 高等数学1 · 1号教学楼101」 */
    fun courseLines(snapshot: Snapshot): List<String> = snapshot.rows.map { row ->
        buildString {
            append(row.time).append(' ').append(row.name)
            if (row.room.isNotBlank()) append(" · ").append(row.room)
        }
    }

    /** 课程开始分钟（毫秒换算给闹钟用） */
    fun startMinutesOf(course: Course, sections: List<SectionTimes.Section>): Int =
        timesOf(course, sections).first

    /** 课程结束分钟 */
    fun endMinutesOf(course: Course, sections: List<SectionTimes.Section>): Int =
        timesOf(course, sections).second

    private fun clock(minutes: Int): String = String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

    private fun Course.roomLabel(): String = when {
        classroom.isBlank() -> if (teacher.isBlank()) "" else teacher
        teacher.isBlank() -> classroom
        else -> classroom
    }

    private fun parseMinutes(raw: String): Int? {
        if (raw.isBlank()) return null
        val m = Regex("""^\s*(\d{1,2})\s*[:：]\s*(\d{2})\s*$""").find(raw) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }
}
