package com.classgrid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.42.0：二维表格课表解析（Excel/CSV 与单元格 OCR 共用）。
 * 重点覆盖「表头不是 周一/星期X，而是 一 / 一 11/2 / 11/2」这一批真实教务系统写法。
 */
class TimetableGridTest {

    private val sections = SectionTimes.default()

    @Test
    fun dayOfHandlesNamedBareAndDatedHeaders() {
        assertEquals(1, TimetableGrid.dayOf("周一"))
        assertEquals(1, TimetableGrid.dayOf("星期一"))
        assertEquals(3, TimetableGrid.dayOf("週三"))
        assertEquals(7, TimetableGrid.dayOf("周日"))
        assertEquals(2, TimetableGrid.dayOf("二"))
        assertEquals(1, TimetableGrid.dayOf("一 11/2"))
        assertEquals(4, TimetableGrid.dayOf("四(11/5)"))
        assertEquals(5, TimetableGrid.dayOf("Fri"))
        assertEquals(0, TimetableGrid.dayOf("11/2"))
        assertEquals(0, TimetableGrid.dayOf("备注"))
        assertTrue(TimetableGrid.isDateHeader("11/2"))
        assertTrue(TimetableGrid.isDateHeader("11-02"))
        assertTrue(TimetableGrid.isDateHeader("11月2日"))
    }

    @Test
    fun sectionLabelsMapToSmallSections() {
        assertEquals(IntRange(1, 2), TimetableGrid.sectionOf("第1-2节", sections))
        assertEquals(IntRange(3, 3), TimetableGrid.sectionOf("第3节", sections))
        assertEquals(IntRange(8, 9), TimetableGrid.sectionOf("第8-9节", sections))
        assertEquals(IntRange(1, 2), TimetableGrid.sectionOf("1-2", sections))
        // 08:30 是第 1 节、14:00 是第 8 节
        assertEquals(IntRange(1, 1), TimetableGrid.sectionOf("08:30", sections))
        assertEquals(IntRange(8, 8), TimetableGrid.sectionOf("14:00", sections))
        // 15 小节作息沿用广外的大节划分（第一大节占 3 小节），第 3 大节 → 第 6-7 小节
        assertEquals(IntRange(6, 7), TimetableGrid.sectionOf("第3大节", sections))
    }

    @Test
    fun weeksHandleRangesOddEvenAndExclusions() {
        val w = TimetableGrid.weeksOf("1-16周")!!
        assertEquals(1, w.startWeek)
        assertEquals(16, w.endWeek)
        assertEquals(Course.WEEK_EVERY, w.weekType)
        assertTrue(w.excluded.isEmpty())

        val even = TimetableGrid.weeksOf("2-16周(双)")!!
        assertEquals(Course.WEEK_EVEN, even.weekType)
        assertTrue(even.excluded.contains(3))
        assertTrue(!even.excluded.contains(4))

        val split = TimetableGrid.weeksOf("1-8,10-16周")!!
        assertEquals(1, split.startWeek)
        assertEquals(16, split.endWeek)
        assertTrue(split.excluded.contains(9))

        val single = TimetableGrid.weeksOf("第5周")!!
        assertEquals(5, single.startWeek)
        assertEquals(5, single.endWeek)
    }

    @Test
    fun parsesWeeklyTableWithSectionRows() {
        val rows = listOf(
            listOf("节次", "周一", "周二", "周三", "周四", "周五"),
            listOf("第1-2节", "高等数学1\n刘睿\n1-16周\n1号教学楼101", "", "", "", ""),
            listOf("第3-4节", "", "大学英语\n王芳\n2-16周(双)\n2号教学楼203", "", "", ""),
            listOf("第5-6节", "", "", "中国近现代史纲要\n张伟\n1-8周", "", "")
        )
        val courses = TimetableGrid.parse(rows, sections)
        assertEquals(3, courses.size)
        val math = courses.first { it.name.contains("高等数学") }
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startSection)
        assertEquals(2, math.endSection)
        assertEquals(1, math.startWeek)
        assertEquals(16, math.endWeek)
        assertEquals("刘睿", math.teacher)
        assertEquals("1号教学楼101", math.classroom)

        val english = courses.first { it.name.contains("大学英语") }
        assertEquals(2, english.dayOfWeek)
        assertEquals(Course.WEEK_EVEN, english.weekType)

        val history = courses.first { it.name.contains("近现代史") }
        assertEquals(3, history.dayOfWeek)
        assertEquals(5, history.startSection)
        assertEquals(6, history.endSection)
        assertEquals(8, history.endWeek)
    }

    @Test
    fun headerWithBareDayAndDateParses() {
        val rows = listOf(
            listOf("", "一 11/2", "二 11/3", "三 11/4", "四 11/5", "五 11/6"),
            listOf("第1-2节", "高等数学1\n潘梓祺", "", "", "高等数学1\n潘梓祺", ""),
            listOf("第3-4节", "", "高等数学1\n潘梓祺", "", "", "")
        )
        val courses = TimetableGrid.parse(rows, sections)
        assertEquals(3, courses.size)
        assertEquals(setOf(1, 2, 4), courses.map { it.dayOfWeek }.toSet())
    }

    @Test
    fun headerWithOnlyDatesAssignsColumnsInOrder() {
        val rows = listOf(
            listOf("", "11/2", "11/3", "11/4", "11/5", "11/6"),
            listOf("第1-2节", "高等数学1", "", "", "", ""),
            listOf("第3-4节", "", "大学英语", "", "", ""),
            listOf("第5-6节", "", "", "体育", "", ""),
            listOf("第7-8节", "", "", "", "形势与政策", "")
        )
        val courses = TimetableGrid.parse(rows, sections)
        assertEquals(4, courses.size)
        assertEquals(1, courses.first { it.name.contains("高等数学") }.dayOfWeek)
        assertEquals(2, courses.first { it.name.contains("大学英语") }.dayOfWeek)
        assertEquals(3, courses.first { it.name.contains("体育") }.dayOfWeek)
        assertEquals(4, courses.first { it.name.contains("形势与政策") }.dayOfWeek)
    }

    @Test
    fun transposedTableWithDaysInColumnParses() {
        val rows = listOf(
            listOf("", "第1-2节", "第3-4节"),
            listOf("周一", "高等数学1\n刘睿\n1-16周", ""),
            listOf("周二", "", "大学英语\n王芳\n2-16周"),
            listOf("周三", "体育\n李强\n1-8周", ""),
            listOf("周四", "", "形势与政策\n赵敏\n9-16周")
        )
        val courses = TimetableGrid.parse(rows, sections)
        assertTrue(courses.size >= 3)
        assertTrue(courses.any { it.name.contains("高等数学") && it.dayOfWeek == 1 })
        assertTrue(courses.any { it.name.contains("大学英语") && it.dayOfWeek == 2 })
    }

    @Test
    fun splitsMultiCourseCellByBlankLineAndSeparator() {
        val parts = TimetableGrid.splitCell("高等数学1\n刘睿\n1-16周\n\n大学英语\n王芳\n2-16周")
        assertEquals(2, parts.size)
        val separated = TimetableGrid.splitCell("高等数学1\n刘睿\n1-16周\n---\n大学英语\n王芳\n2-16周")
        assertEquals(2, separated.size)
    }

    @Test
    fun cellWithoutWeeksUsesDefaultEndWeek() {
        val rows = listOf(
            listOf("", "周一", "周二", "周三", "周四"),
            listOf("第1-2节", "高等数学1\n刘睿", "", "", ""),
            listOf("第3-4节", "", "", "", "")
        )
        val courses = TimetableGrid.parse(rows, sections, defaultEndWeek = 18)
        assertEquals(1, courses.size)
        assertEquals(1, courses[0].startWeek)
        assertEquals(18, courses[0].endWeek)
        assertEquals("刘睿", courses[0].teacher)
    }

    @Test
    fun teacherIsNotMisreadAsCourseName() {
        val course = TimetableGrid.parseSegment(
            "高等数学1\n刘睿\n1-16周\n1号教学楼101",
            1, IntRange(1, 2), sections, 20
        )
        assertNotNull(course)
        assertEquals("高等数学1", course!!.name)
        assertEquals("刘睿", course.teacher)
        assertEquals("1号教学楼101", course.classroom)
    }
}
