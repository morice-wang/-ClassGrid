package com.classgrid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真机抓包（classgrid_capture_*.json 里的 /njwhd/student/curriculum 响应）做回归。
 *
 * 这个接口把星期藏在节次编码里（weekNoteDetail = "214,215" → 周二第 14、15 节），
 * 通用字段匹配拿不到星期，曾导致「抓取失败」与更早的「全部变成周二」。
 */
class NjwhdRealPayloadTest {

    private fun body(): String = javaClass.getResourceAsStream("/njwhd_curriculum.json")!!
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }

    private fun parse() = WebJsonParser.parse(
        listOf(
            WebScheduleParser.NetDoc(
                "https://jwxt.gdufs.edu.cn/njwhd/student/curriculum?xnxq01id=&kbjcmsid=&week=",
                200,
                body()
            )
        ),
        SectionTimes.default()
    )

    @Test
    fun realPayloadYieldsAllCourses() {
        val courses = parse()
        assertEquals("真机 payload 里有 12 门课，应全部解析出来", 12, courses.size)
        assertTrue("星期不能全是 0（未知）", courses.all { it.dayOfWeek in 1..7 })
    }

    @Test
    fun weekdayComesFromSectionCode() {
        val courses = parse()

        val safety = courses.single { it.name.contains("国家安全教育") }
        assertEquals(2, safety.dayOfWeek)
        assertEquals(14, safety.startSection)
        assertEquals(15, safety.endSection)
        assertEquals(9, safety.startWeek)
        assertEquals(12, safety.endWeek)
        assertTrue("教师应来自 teacherName", safety.teacher.contains("文飞"))
        assertTrue("教室应来自 location", safety.classroom.contains("G407"))
        assertEquals("20:00", safety.startTime)

        val history = courses.single { it.name.contains("中国近现代史纲要") }
        assertEquals(2, history.dayOfWeek)
        assertEquals(3, history.startSection)
        assertEquals(5, history.endSection)

        val apec = courses.single { it.name.contains("透视") }
        assertEquals(1, apec.dayOfWeek)
        assertEquals(12, apec.startSection)
        assertEquals(13, apec.endSection)

        val english = courses.single { it.name.contains("综合英语") }
        assertEquals(3, english.dayOfWeek)
        assertEquals(1, english.startSection)
        assertEquals(2, english.endSection)
    }

    @Test
    fun sameCourseOnTwoDaysStaysTwoCourses() {
        val maths = parse().filter { it.name.contains("高等数学") }
        assertEquals("高数周一、周二各一次，不能被合并成一门", 2, maths.size)
        val monday = maths.single { it.dayOfWeek == 1 }
        assertEquals(3, monday.startSection)
        assertEquals(4, monday.endSection)
        assertEquals(2, monday.startWeek)
        assertEquals(16, monday.endWeek)

        val tuesday = maths.single { it.dayOfWeek == 2 }
        assertEquals(1, tuesday.startSection)
        assertEquals(2, tuesday.endSection)
        assertTrue("2-4,6-16 的第 5 周应被排除", tuesday.excludedWeeks.contains(5))
    }

    @Test
    fun weeksFromClassWeekDetailsOrClassWeek() {
        val courses = parse()
        val adaptive = courses.single { it.name.contains("人工智能应用基础") }
        assertEquals(1, adaptive.startWeek)
        assertEquals(16, adaptive.endWeek)
        assertTrue(courses.all { it.startWeek in 1..30 && it.endWeek >= it.startWeek })
    }
}
