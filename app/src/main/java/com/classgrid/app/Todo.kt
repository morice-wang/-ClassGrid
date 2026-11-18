package com.classgrid.app

/**
 * 自定义日程（课表/日程 Tab 中由用户手动添加的"要做的事"，区别于按周重复的课程 [Course]）。
 *
 * v1.47.7 起支持精确时间段：`time` 是开始时间、`endTime` 是结束时间（都是 HH:MM 字符串，
 * 可空表示全天/不指定）。带完整时间段的日程会按时间比例画到课表对应位置。
 *
 * v1.47.11 起重复规则与课程一致：可指定起止周与每周/单周/双周。
 *
 * v1.47.12 起编辑器与课程编辑器同一套字段（星期、起止周、单双周、颜色），
 * 单次日程 = 起止周相同且每周。
 *
 * @param id          唯一标识
 * @param title       事项名称
 * @param dateMillis  第一次发生的日期（当天 00:00 的毫秒值），用来确定星期几
 * @param time        开始时间（HH:MM），可为空字符串表示全天/不指定
 * @param endTime     结束时间（HH:MM），可为空；为空时当作没有时间段
 * @param location    地点，可为空
 * @param note        备注，可为空
 * @param colorIndex  颜色下标（与课程共用 [ScheduleColor] 调色板）
 * @param repeatWeekly 是否跨周重复；false 时只在 dateMillis 当天出现一次
 * @param startWeek   重复起始周（0 = 从第一周开始）
 * @param endWeek     重复结束周（0 = 到学期最后一周）
 * @param weekType    周次类型：0 每周 / 1 单周 / 2 双周（与 Course.WEEK_* 一致）
 */
data class Todo(
    val id: Long,
    val title: String,
    val dateMillis: Long,
    val time: String = "",
    val endTime: String = "",
    val location: String = "",
    val note: String = "",
    val colorIndex: Int = 0,
    val repeatWeekly: Boolean = false,
    val startWeek: Int = 0,
    val endWeek: Int = 0,
    val weekType: Int = 0
) {

    /** 有效起始周（0 视作第一周） */
    fun effectiveStartWeek(): Int = if (startWeek > 0) startWeek else 1

    /** 有效结束周（0 视作学期末；totalWeeks 也给不出时退回 20） */
    fun effectiveEndWeek(totalWeeks: Int): Int = when {
        endWeek > 0 -> endWeek
        totalWeeks > 0 -> totalWeeks
        else -> 20
    }

    /** 这一天的日程是否命中（含按周重复的展开判断） */
    fun occursOn(dayStartMillis: Long, semesterStartMillis: Long, totalWeeks: Int): Boolean {
        if (!repeatWeekly) return dateMillis == dayStartMillis
        if (!sameWeekday(dayStartMillis, dateMillis)) return false
        if (semesterStartMillis <= 0L) return true
        val week = weekIndexOf(dayStartMillis, semesterStartMillis)
        if (week !in effectiveStartWeek()..effectiveEndWeek(totalWeeks)) return false
        return weekMatchesType(week)
    }

    /** 某个学期的第 week 周是否落在这条日程的重复范围内（不看星期） */
    fun occursInWeek(week: Int, totalWeeks: Int): Boolean {
        if (!repeatWeekly) return false
        if (week !in effectiveStartWeek()..effectiveEndWeek(totalWeeks)) return false
        return weekMatchesType(week)
    }

    private fun weekMatchesType(week: Int): Boolean = when (weekType) {
        Course.WEEK_ODD -> week % 2 == 1
        Course.WEEK_EVEN -> week % 2 == 0
        else -> true
    }

    /** 重复规则的简短描述（不重复返回空串） */
    fun repeatDesc(totalWeeks: Int): String {
        if (!repeatWeekly) return ""
        val type = when (weekType) {
            Course.WEEK_ODD -> "单周"
            Course.WEEK_EVEN -> "双周"
            else -> "每周"
        }
        return "$type 第${effectiveStartWeek()}-${effectiveEndWeek(totalWeeks)}周"
    }

    /** 是否有可画到课表里的时间段（开始、结束都齐全且结束晚于开始） */
    fun hasTimeRange(): Boolean {
        val s = parseMinutes(time) ?: return false
        val e = parseMinutes(endTime) ?: return false
        return e > s
    }

    companion object {
        /** 判断两个「当天 00:00」毫秒值是不是同一个星期几 */
        fun sameWeekday(a: Long, b: Long): Boolean {
            val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
            val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
            return ca.get(java.util.Calendar.DAY_OF_WEEK) == cb.get(java.util.Calendar.DAY_OF_WEEK)
        }

        /** 相对学期第一周周一算第几周（1 起） */
        fun weekIndexOf(dayStartMillis: Long, semesterStartMillis: Long): Int {
            val diffDays = java.util.concurrent.TimeUnit.MILLISECONDS.toDays(dayStartMillis - semesterStartMillis)
            return (Math.floorDiv(diffDays, 7L) + 1).toInt()
        }

        /**
         * 由「第 week 周的星期 day（1=周一）」算出当天 00:00 的毫秒值。
         * semesterStartMillis 是学期第一周周一的 00:00。
         */
        fun millisOf(semesterStartMillis: Long, week: Int, day: Int): Long {
            val weekStart = semesterStartMillis + (week - 1).coerceAtLeast(0) * 7L * 86_400_000L
            return weekStart + (day - 1).coerceIn(0, 6) * 86_400_000L
        }

        /** 从某天 00:00 的毫秒值取星期几（1=周一 … 7=周日） */
        fun dayOfWeek(dayStartMillis: Long): Int {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = dayStartMillis }
            return when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.MONDAY -> 1
                java.util.Calendar.TUESDAY -> 2
                java.util.Calendar.WEDNESDAY -> 3
                java.util.Calendar.THURSDAY -> 4
                java.util.Calendar.FRIDAY -> 5
                java.util.Calendar.SATURDAY -> 6
                else -> 7
            }
        }

        /** 把 "HH:MM" 解析成一天中的分钟数，非法返回 null */
        fun parseMinutes(hhmm: String): Int? {
            val parts = hhmm.trim().split(":")
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return h * 60 + m
        }

        /** 分钟数格式化成 "HH:MM" */
        fun formatMinutes(minutes: Int): String {
            val m = ((minutes % 1440) + 1440) % 1440
            return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60)
        }

        /** 显示用的时间段文案，没有时间段时返回空串 */
        fun rangeLabel(todo: Todo): String {
            if (todo.time.isBlank()) return ""
            return if (todo.endTime.isNotBlank()) "${todo.time}-${todo.endTime}" else todo.time
        }
    }
}
