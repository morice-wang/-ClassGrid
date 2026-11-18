package com.classgrid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * IcsExporter 的纯 JVM 单测：不依赖 Android，直接断言生成的 iCalendar 文本。
 *
 * 固定环境：Asia/Shanghai、学期第一周周一 = 2026-09-07。
 * 事件时间写成「带 TZID 的本地时间」，`RRULE` 的 UNTIL 按 RFC 5545 仍用 UTC。
 */
class IcsExporterTest {

    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val tzid = "Asia/Shanghai"
    private val semesterStart: Long = startOf(2026, Calendar.SEPTEMBER, 7, zone)

    private val sections = SectionTimes.default()

    private fun startOf(year: Int, month: Int, day: Int, zone: TimeZone): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, 0, 0, 0)
        }.timeInMillis

    private fun course(
        name: String = "高等数学",
        teacher: String = "",
        classroom: String = "",
        day: Int = 1,
        startSection: Int = 1,
        endSection: Int = 1,
        startWeek: Int = 1,
        endWeek: Int = 16,
        weekType: Int = Course.WEEK_EVERY,
        excluded: Set<Int> = emptySet(),
        code: String = "",
        id: Long = 1L
    ) = Course(
        id = id,
        name = name,
        teacher = teacher,
        classroom = classroom,
        dayOfWeek = day,
        startSection = startSection,
        endSection = endSection,
        startWeek = startWeek,
        endWeek = endWeek,
        weekType = weekType,
        excludedWeeks = excluded,
        code = code
    )

    private fun build(
        vararg courses: Course,
        reminderMinutes: Int = 10,
        start: Long = semesterStart,
        zone: TimeZone = this.zone
    ): String =
        IcsExporter.build(
            courses = courses.toList(),
            semesterStartMillis = start,
            sections = sections,
            reminderMinutes = reminderMinutes,
            nowMillis = start,
            zone = zone
        )

    private fun lines(ics: String): List<String> = ics.split("\r\n")

    private fun firstLine(ics: String, prefix: String): String =
        lines(ics).first { it.startsWith(prefix) }

    private fun allLines(ics: String, prefix: String): List<String> =
        lines(ics).filter { it.startsWith(prefix) }

    @Test
    fun emptyTimetableStillProducesValidCalendar() {
        val ics = build()
        assertTrue(ics.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
        assertTrue(ics.contains("VERSION:2.0"))
        assertTrue(ics.contains("PRODID:${IcsExporter.PRODID}"))
        assertTrue(ics.contains("X-WR-CALNAME:课格课表"))
        // 空课表也有时区定义，导入后不会因为缺 VTIMEZONE 被日历拒绝
        assertTrue(ics.contains("BEGIN:VTIMEZONE"))
        assertTrue(ics.contains("TZID:$tzid"))
        assertFalse(ics.contains("BEGIN:VEVENT"))
    }

    @Test
    fun allLineBreaksAreCrlf() {
        val ics = build(course(name = "线性代数, 与; 概率论"))
        val withoutCrlf = ics.replace("\r\n", "")
        assertFalse("ics 里出现了裸 \\n", withoutCrlf.contains('\n'))
        assertFalse("ics 里出现了裸 \\r", withoutCrlf.contains('\r'))
    }

    @Test
    fun vtimezoneDescribesChinaWithoutDaylightSaving() {
        val ics = build(course())
        assertTrue(ics.contains("BEGIN:VTIMEZONE"))
        assertTrue(ics.contains("TZID:$tzid"))
        assertTrue(ics.contains("TZOFFSETTO:+0800"))
        assertTrue(ics.contains("TZOFFSETFROM:+0800"))
        // 中国无夏令时：不应出现 DAYLIGHT 组件
        assertFalse(ics.contains("BEGIN:DAYLIGHT"))
        assertTrue(ics.contains("END:VTIMEZONE"))
    }

    @Test
    fun weeklyCourseUsesTzidAndUtcUntilLastWeek() {
        val ics = build(course(startWeek = 1, endWeek = 4))
        assertEquals("DTSTART;TZID=$tzid:20260907T083000", firstLine(ics, "DTSTART;TZID="))
        // 第 1 节 08:30–09:10（北京时间），事件按本地时间写，不再显示 UTC
        assertEquals("DTEND;TZID=$tzid:20260907T091000", firstLine(ics, "DTEND;TZID="))
        // RFC 5545：DTSTART 带 TZID 时 UNTIL 必须用 UTC
        assertEquals(
            "RRULE:FREQ=WEEKLY;WKST=MO;UNTIL=20260928T003000Z",
            firstLine(ics, "RRULE:")
        )
        assertTrue(allLines(ics, "EXDATE:").isEmpty())
    }

    @Test
    fun oddWeeksProduceExdatesForEvenWeeks() {
        val ics = build(course(startWeek = 1, endWeek = 6, weekType = Course.WEEK_ODD))
        val exdates = firstLine(ics, "EXDATE;TZID=")
        // 单周上课 → 第 2/4/6 周被剔除；EXDATE 跟随 DTSTART 的时区（本地时间）
        assertEquals(
            "EXDATE;TZID=$tzid:20260914T083000,20260928T083000,20261012T083000",
            exdates
        )
    }

    @Test
    fun excludedWeeksBecomeExdates() {
        val ics = build(course(startWeek = 2, endWeek = 16, excluded = setOf(5)))
        // 第 5 周周一 = 2026-10-05
        assertEquals("EXDATE;TZID=$tzid:20261005T083000", firstLine(ics, "EXDATE;TZID="))
        assertEquals("DTSTART;TZID=$tzid:20260914T083000", firstLine(ics, "DTSTART;TZID="))
    }

    @Test
    fun summaryLocationAndDescriptionAreEscaped() {
        val ics = build(
            course(
                name = "高等数学, 上册; 习题课",
                teacher = "张老师",
                classroom = "教学楼 A101",
                endSection = 2
            )
        )
        assertEquals(
            "SUMMARY:高等数学\\, 上册\\; 习题课",
            firstLine(ics, "SUMMARY:")
        )
        assertEquals("LOCATION:教学楼 A101", firstLine(ics, "LOCATION:"))
        val description = firstLine(ics, "DESCRIPTION:")
        assertTrue(description.contains("教师：张老师"))
        assertTrue(description.contains("1-16周"))
    }

    @Test
    fun reminderIsWrittenUnlessDisabled() {
        val withAlarm = build(course())
        assertEquals(1, allLines(withAlarm, "BEGIN:VALARM").size)
        assertTrue(withAlarm.contains("TRIGGER:-PT10M"))

        val withoutAlarm = build(course(), reminderMinutes = 0)
        assertFalse(withoutAlarm.contains("BEGIN:VALARM"))
    }

    @Test
    fun uidIsStableAndTiedToCourseId() {
        val a = build(course(id = 42L))
        val b = build(course(id = 42L))
        assertEquals(firstLine(a, "UID:"), firstLine(b, "UID:"))
        assertEquals("UID:course-42@classgrid.app", firstLine(a, "UID:"))
    }

    @Test
    fun longLinesAreFoldedAtSeventyFiveUtf8Bytes() {
        val longName = "数据结构和算法分析".repeat(8)
        val ics = build(course(name = longName))
        for (line in lines(ics)) {
            assertTrue(
                "行超过 75 字节：${line.take(20)}… (${line.toByteArray(Charsets.UTF_8).size})",
                line.toByteArray(Charsets.UTF_8).size <= 75
            )
        }
        val unfolded = ics.replace("\r\n ", "")
        assertTrue(unfolded.contains("SUMMARY:$longName"))
    }

    @Test
    fun courseWithNoActiveWeekIsSkipped() {
        val ics = build(course(startWeek = 1, endWeek = 2, excluded = setOf(1, 2)))
        assertFalse(ics.contains("BEGIN:VEVENT"))
    }

    @Test
    fun multiSectionCourseUsesEndSectionTime() {
        val ics = build(course(startSection = 3, endSection = 4))
        // 第 3 节 10:10 开始、第 4 节 11:30 结束
        assertEquals("DTSTART;TZID=$tzid:20260907T101000", firstLine(ics, "DTSTART;TZID="))
        assertEquals("DTEND;TZID=$tzid:20260907T113000", firstLine(ics, "DTEND;TZID="))
    }

    @Test
    fun suggestFileNameLooksLikeCalendarFile() {
        val name = IcsExporter.suggestFileName(semesterStart, zone)
        assertEquals("课格课表_20260907.ics", name)
    }

    @Test
    fun escapeTextHandlesSpecialsAndNewlines() {
        assertEquals("a\\\\b\\;c\\,d\\ne", IcsExporter.escapeText("a\\b;c,d\ne"))
    }

    @Test
    fun daylightSavingZonesGetDaylightAndStandardComponents() {
        val newYork = TimeZone.getTimeZone("America/New_York")
        // 2027-01-04（周一）起 14 周，跨 3 月 14 日的美国夏令时切换
        val start = startOf(2027, Calendar.JANUARY, 4, newYork)
        val ics = build(course(startWeek = 1, endWeek = 14), start = start, zone = newYork)
        assertTrue(ics.contains("BEGIN:DAYLIGHT"))
        assertTrue(ics.contains("TZOFFSETFROM:-0500"))
        assertTrue(ics.contains("TZOFFSETTO:-0400"))
        assertEquals(
            "DTSTART;TZID=America/New_York:20270104T083000",
            firstLine(ics, "DTSTART;TZID=")
        )
        // 最后一节有效周（第 14 周 = 2027-04-05）在夏令时内：08:30 EDT = 12:30Z
        assertTrue(firstLine(ics, "RRULE:").endsWith("UNTIL=20270405T123000Z"))
    }
}
