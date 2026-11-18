package com.classgrid.app

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 把课表导出成标准 iCalendar（.ics）文本。
 *
 * 为什么要有它：课表 App 自己用 AlarmManager 做提醒，在国产 ROM 上很容易被省电策略杀掉；
 * 而写进系统日历 / 导入到日历 App 后，提醒由系统日历进程触发，稳得多。
 * 这里先做最通用的「导出 .ics」：发到任意日历 App（手机自带日历、Outlook、Google 日历、
 * iPhone 日历）都能导入，从而获得上课提醒。
 *
 * 设计要点：
 * - 纯 Kotlin（只用 java.util / java.text），不依赖 Android，便于单测，也方便以后写系统日历。
 * - 每门课一条 VEVENT：`RRULE:FREQ=WEEKLY` 从第一次上课周铺到最后一节课周，
 *   再把不上课的周（单双周、`excludedWeeks`）用 `EXDATE` 精确剔除——
 *   这样单双周、`2-4,6-16周` 这类非连续周次都能落到真实日期上。
 * - 时间写成**带时区的本地时间**（`DTSTART;TZID=Asia/Shanghai:…`）并附 `VTIMEZONE`：
 *   早期版本写成 UTC（`…Z`），小米/华为等日历会把事件当成「UTC 时区的事件」，
 *   详情页多显示一行「协调世界时 (UTC)」，用户会以为时间错了。
 *   带 TZID 后日历只显示本地时间（最多标注 GMT+8），也更符合「上课按当地时间」的直觉。
 *   `RRULE` 的 `UNTIL` 按 RFC 5545 必须仍用 UTC。
 * - `VTIMEZONE` 按覆盖日期内的真实时区跳变生成（中国无夏令时 → 单条 STANDARD；
 *   欧美时区 → 自动写出 DAYLIGHT/STANDARD 两条），不硬编码某个国家规则。
 * - VALARM 提前 [reminderMinutes] 分钟提醒，提醒由日历 App 负责，不占 App 后台。
 * - 行折叠按 UTF-8 字节 75 折行（RFC 5545），中文课程名不会把行撑爆。
 */
object IcsExporter {

    const val PRODID = "-//ClassGrid//Timetable//CN"

    /** 默认提前 10 分钟提醒 */
    const val DEFAULT_REMINDER_MINUTES = 10

    private const val MINUTE_MILLIS = 60_000L
    private const val HOUR_MILLIS = 3_600_000L
    private const val DAY_MILLIS = 86_400_000L

    /** 生成 .ics 的推荐文件名，如 `课格课表_20261111.ics` */
    fun suggestFileName(nowMillis: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): String {
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = zone }
        return "课格课表_${fmt.format(Date(nowMillis))}.ics"
    }

    /**
     * @param courses 当前课表里的课程
     * @param semesterStartMillis 学期第一周周一 00:00 的本地时间戳（`Prefs.semesterStart`）
     * @param sections 节次作息（`SectionTimes.load`），用于把节次换算成上课/下课时间
     * @param reminderMinutes 提前多少分钟提醒；<=0 时不写 VALARM
     * @param nowMillis DTSTAMP 用（UTC）
     * @param zone 解释 [semesterStartMillis] 与节次时间的时区（默认本机时区），同时写进 TZID
     * @param calendarName 日历名称（X-WR-CALNAME）
     */
    fun build(
        courses: List<Course>,
        semesterStartMillis: Long,
        sections: List<SectionTimes.Section>,
        reminderMinutes: Int = DEFAULT_REMINDER_MINUTES,
        nowMillis: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        calendarName: String = "课格课表"
    ): String {
        val tzid = safeTzid(zone)
        val stamp = formatUtc(nowMillis)

        val events = mutableListOf<Event>()
        for (course in courses) {
            val firstWeek = firstActiveWeek(course) ?: continue
            val lastWeek = lastActiveWeek(course) ?: continue
            val startTime = timeOfSection(course.startSection, sections) ?: continue
            val endTime = endTimeOfCourse(course, sections, startTime) ?: continue

            val ruleStart = weekStart(course.dayOfWeek, firstWeek, startTime, semesterStartMillis, zone) ?: continue
            val ruleEnd = weekStart(course.dayOfWeek, lastWeek, startTime, semesterStartMillis, zone) ?: continue

            val exdates = mutableListOf<String>()
            for (week in course.startWeek..course.endWeek) {
                if (course.activeInWeek(week)) continue
                val skipped = weekStart(course.dayOfWeek, week, startTime, semesterStartMillis, zone) ?: continue
                exdates.add(formatLocal(skipped, zone))
            }
            events.add(
                Event(
                    course = course,
                    startLocal = formatLocal(ruleStart, zone),
                    endLocal = formatLocal(computeEnd(ruleStart, startTime, endTime, zone), zone),
                    untilUtc = formatUtc(ruleEnd),
                    exdates = exdates,
                    firstMillis = ruleStart,
                    lastMillis = computeEnd(ruleEnd, startTime, endTime, zone)
                )
            )
        }

        val sb = StringBuilder()
        line(sb, "BEGIN:VCALENDAR")
        line(sb, "VERSION:2.0")
        line(sb, "PRODID:$PRODID")
        line(sb, "CALSCALE:GREGORIAN")
        line(sb, "METHOD:PUBLISH")
        line(sb, "X-WR-CALNAME:${escapeText(calendarName)}")
        line(sb, "X-WR-TIMEZONE:${escapeText(tzid)}")

        // VTIMEZONE 覆盖整个课表的时间范围（前后各留一天，避免边界事件落在规则外）
        val periodStart = events.minOfOrNull { it.firstMillis } ?: (semesterStartMillis - 30 * DAY_MILLIS)
        val periodEnd = events.maxOfOrNull { it.lastMillis } ?: (semesterStartMillis + 180 * DAY_MILLIS)
        for (l in vtimezone(zone, tzid, periodStart - DAY_MILLIS, periodEnd + DAY_MILLIS)) {
            line(sb, l)
        }

        for (event in events) {
            val course = event.course
            val summary = if (course.code.isNotBlank()) "${course.name}（${course.code}）" else course.name
            val description = buildString {
                append("来自课格 ClassGrid。")
                if (course.weekType == Course.WEEK_ODD) append("单周。")
                if (course.weekType == Course.WEEK_EVEN) append("双周。")
                append("第${course.weekDesc()}。")
                if (course.teacher.isNotBlank()) append("教师：${course.teacher}。")
            }

            line(sb, "BEGIN:VEVENT")
            line(sb, "UID:${uid(course)}")
            line(sb, "DTSTAMP:$stamp")
            line(sb, "DTSTART;TZID=$tzid:${event.startLocal}")
            line(sb, "DTEND;TZID=$tzid:${event.endLocal}")
            line(sb, "RRULE:FREQ=WEEKLY;WKST=MO;UNTIL=${event.untilUtc}")
            if (event.exdates.isNotEmpty()) {
                line(sb, "EXDATE;TZID=$tzid:${event.exdates.joinToString(",")}")
            }
            line(sb, "SUMMARY:${escapeText(summary)}")
            if (course.classroom.isNotBlank()) line(sb, "LOCATION:${escapeText(course.classroom)}")
            line(sb, "DESCRIPTION:${escapeText(description)}")
            line(sb, "TRANSP:OPAQUE")
            if (reminderMinutes > 0) {
                line(sb, "BEGIN:VALARM")
                line(sb, "ACTION:DISPLAY")
                line(sb, "DESCRIPTION:${escapeText(course.name)} 上课提醒")
                line(sb, "TRIGGER:-PT${reminderMinutes}M")
                line(sb, "END:VALARM")
            }
            line(sb, "END:VEVENT")
        }

        line(sb, "END:VCALENDAR")
        return sb.toString()
    }

    /** 课程在 [startWeek, endWeek] 的第一节有效周 */
    fun firstActiveWeek(course: Course): Int? =
        (course.startWeek..course.endWeek).firstOrNull { course.activeInWeek(it) }

    /** 课程在 [startWeek, endWeek] 的最后一节有效周 */
    fun lastActiveWeek(course: Course): Int? =
        (course.endWeek downTo course.startWeek).firstOrNull { course.activeInWeek(it) }

    /** 稳定 UID：同一门课每次导出相同，日历里会更新而不是重复 */
    fun uid(course: Course): String = "course-${course.id}@classgrid.app"

    // ---------------------------------------------------------------- internals

    private class Event(
        val course: Course,
        val startLocal: String,
        val endLocal: String,
        val untilUtc: String,
        val exdates: List<String>,
        /** 第一次上课的真实时刻（算 VTIMEZONE 覆盖范围用） */
        val firstMillis: Long,
        /** 最后一次上课结束的真实时刻（不要只算第一次，否则跨夏令时的课表会缺 DAYLIGHT） */
        val lastMillis: Long
    )

    private fun timeOfSection(section: Int, sections: List<SectionTimes.Section>): Int? {
        val s = sections.getOrNull(section - 1) ?: return null
        return SectionTimes.toMinutes(s.start)
    }

    private fun endTimeOfCourse(
        course: Course,
        sections: List<SectionTimes.Section>,
        startTime: Int
    ): Int? {
        val end = sections.getOrNull(course.endSection - 1)?.let { SectionTimes.toMinutes(it.end) }
        if (end != null && end > startTime) return end
        // 节次表缺项时退化为课程自带时钟（OCR 抓到的 startTime/endTime），再不行按 45 分钟
        val fallback = SectionTimes.toMinutes(course.endTime)
        if (fallback != null && fallback > startTime) return fallback
        return startTime + 45
    }

    /** 第 [week] 周、星期 [dayOfWeek]（1=周一）、[minutes] 分钟后的本地时间戳 */
    private fun weekStart(
        dayOfWeek: Int,
        week: Int,
        minutes: Int,
        semesterStartMillis: Long,
        zone: TimeZone
    ): Long? {
        if (week < 1 || dayOfWeek !in 1..7) return null
        val cal = Calendar.getInstance(zone).apply {
            timeInMillis = semesterStartMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, (week - 1) * 7 + (dayOfWeek - 1))
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
        }
        return cal.timeInMillis
    }

    /** 同一时刻的结束时间戳：上课时间 + (下课 - 上课) 分钟，跨天按 24h 处理 */
    private fun computeEnd(startMillis: Long, startTime: Int, endTime: Int, zone: TimeZone): Long {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = startMillis }
        val duration = (endTime - startTime).coerceAtLeast(1)
        cal.add(Calendar.MINUTE, duration)
        return cal.timeInMillis
    }

    /**
     * 生成 VTIMEZONE：覆盖 [fromMillis, toMillis] 内该时区的真实跳变。
     * 中国无夏令时 → 只有一条 STANDARD；America/New_York 等会写出 DAYLIGHT + STANDARD。
     * 不硬编码任何国家的夏令时规则，跳变由 [TimeZone] 自己给出。
     */
    private fun vtimezone(zone: TimeZone, tzid: String, fromMillis: Long, toMillis: Long): List<String> {
        val out = mutableListOf<String>()
        out += "BEGIN:VTIMEZONE"
        out += "TZID:$tzid"
        out += "X-LIC-LOCATION:$tzid"

        val transitions = transitionsIn(zone, fromMillis, toMillis)
        val initialOffset = if (transitions.isEmpty()) {
            zone.getOffset(fromMillis)
        } else {
            zone.getOffset(transitions.first() - MINUTE_MILLIS)
        }
        out += component(
            zone = zone,
            instant = fromMillis,
            offset = initialOffset,
            offsetFrom = initialOffset,
            startLocal = "19700101T000000"
        )
        for (t in transitions) {
            val before = zone.getOffset(t - MINUTE_MILLIS)
            val after = zone.getOffset(t)
            out += component(
                zone = zone,
                instant = t,
                offset = after,
                offsetFrom = before,
                startLocal = formatLocal(t, zone)
            )
        }
        out += "END:VTIMEZONE"
        return out
    }

    private fun component(
        zone: TimeZone,
        instant: Long,
        offset: Int,
        offsetFrom: Int,
        startLocal: String
    ): List<String> {
        val daylight = zone.inDaylightTime(Date(instant))
        val name = zone.getDisplayName(daylight, TimeZone.SHORT, Locale.US).ifBlank {
            if (daylight) "DST" else "STD"
        }
        return listOf(
            if (daylight) "BEGIN:DAYLIGHT" else "BEGIN:STANDARD",
            "TZOFFSETFROM:${offsetString(offsetFrom)}",
            "TZOFFSETTO:${offsetString(offset)}",
            "TZNAME:${escapeText(name)}",
            "DTSTART:$startLocal",
            if (daylight) "END:DAYLIGHT" else "END:STANDARD"
        )
    }

    /** 找出 [fromMillis, toMillis] 内所有时区跳变点（先按小时找，再二分到分钟） */
    private fun transitionsIn(zone: TimeZone, fromMillis: Long, toMillis: Long): List<Long> {
        val result = mutableListOf<Long>()
        var cursor = fromMillis
        var previous = zone.getOffset(cursor)
        while (cursor + HOUR_MILLIS <= toMillis) {
            val next = cursor + HOUR_MILLIS
            val offset = zone.getOffset(next)
            if (offset != previous) {
                var lo = cursor
                var hi = next
                while (hi - lo > MINUTE_MILLIS) {
                    val mid = (lo + hi) / 2
                    if (zone.getOffset(mid) == previous) lo = mid else hi = mid
                }
                result.add(hi)
                previous = zone.getOffset(hi)
            }
            cursor = next
        }
        return result
    }

    private fun offsetString(offsetMillis: Int): String {
        val sign = if (offsetMillis < 0) "-" else "+"
        val abs = kotlin.math.abs(offsetMillis)
        val hours = abs / HOUR_MILLIS.toInt()
        val minutes = (abs % HOUR_MILLIS.toInt()) / MINUTE_MILLIS.toInt()
        return String.format(Locale.US, "%s%02d%02d", sign, hours, minutes)
    }

    /** 本地时间写法（不带 Z），如 `20260907T083000` */
    private fun formatLocal(millis: Long, zone: TimeZone): String {
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
        fmt.timeZone = zone
        return fmt.format(Date(millis))
    }

    private fun parseLocal(text: String, zone: TimeZone): Long {
        return try {
            val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
            fmt.timeZone = zone
            fmt.isLenient = false
            fmt.parse(text)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** UTC 写法（带 Z），DTSTAMP 与 RRULE UNTIL 必须用它 */
    private fun formatUtc(millis: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(millis))
    }

    /** TZID 只用稳定的时区 ID；某些设备给的是 `GMT+08:00` 这类偏移 ID，补一个别名更保险 */
    private fun safeTzid(zone: TimeZone): String {
        val id = zone.id
        return if (id.startsWith("GMT") || id.startsWith("+") || id.startsWith("-")) "Asia/Shanghai" else id
    }

    /** RFC 5545 TEXT 转义 */
    fun escapeText(raw: String): String = buildString {
        for (ch in raw) {
            when (ch) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\n' -> append("\\n")
                '\r' -> Unit
                else -> append(ch)
            }
        }
    }

    /** 追加一行（自动按 75 字节折行 + CRLF 结尾） */
    private fun line(sb: StringBuilder, content: String) {
        sb.append(fold(content)).append("\r\n")
    }

    /** 按 UTF-8 字节数折行；续行以空格开头，每行不超过 75 字节 */
    fun fold(content: String, limit: Int = 75): String {
        val out = StringBuilder()
        var lineBytes = 0
        for (ch in content) {
            val chBytes = ch.toString().toByteArray(Charsets.UTF_8).size
            if (lineBytes + chBytes > limit) {
                out.append("\r\n ")
                lineBytes = 1
            }
            out.append(ch)
            lineBytes += chBytes
        }
        return out.toString()
    }
}
