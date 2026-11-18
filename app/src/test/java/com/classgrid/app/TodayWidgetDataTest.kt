package com.classgrid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v1.44.0 桌面小组件数据层测试：不依赖 Android，只验纯函数。
 */
class TodayWidgetDataTest {

    private val sections = SectionTimes.default()

    private fun course(
        name: String,
        day: Int,
        startSection: Int,
        endSection: Int,
        startWeek: Int = 1,
        endWeek: Int = 16,
        excluded: Set<Int> = emptySet(),
        room: String = "",
        teacher: String = ""
    ) = Course(
        name = name,
        teacher = teacher,
        classroom = room,
        dayOfWeek = day,
        startSection = startSection,
        endSection = endSection,
        startWeek = startWeek,
        endWeek = endWeek,
        excludedWeeks = excluded
    )

    @Test
    fun emptyDayShowsNoCourses() {
        val snap = TodayWidgetData.build(emptyList(), 10, 3, 600, sections)
        assertFalse(snap.hasCourses)
        assertTrue(snap.rows.isEmpty())
        assertEquals(0, snap.extra)
    }

    @Test
    fun onlyTodayAndActiveWeekCoursesAreShown() {
        val courses = listOf(
            course("今天的课", 3, 1, 2),
            course("周五的课", 5, 1, 2),
            course("已结课", 3, 3, 4, endWeek = 8)
        )
        val snap = TodayWidgetData.build(courses, week = 10, weekday = 3, nowMinutes = 0, sections = sections)
        assertEquals(1, snap.todayCount)
        assertEquals("今天的课", snap.rows.single().name)
    }

    @Test
    fun excludedWeekHidesCourse() {
        val courses = listOf(course("调休课", 3, 1, 2, excluded = setOf(10)))
        val snap = TodayWidgetData.build(courses, week = 10, weekday = 3, nowMinutes = 0, sections = sections)
        assertFalse(snap.hasCourses)

        val later = TodayWidgetData.build(courses, week = 11, weekday = 3, nowMinutes = 0, sections = sections)
        assertTrue(later.hasCourses)
    }

    @Test
    fun rowsAreSortedByStartSection() {
        val courses = listOf(
            course("第三节", 3, 3, 4),
            course("第一节", 3, 1, 2),
            course("第七节", 3, 7, 8)
        )
        val snap = TodayWidgetData.build(courses, 10, 3, 0, sections)
        assertEquals(listOf("第一节", "第三节", "第七节"), snap.rows.map { it.name })
    }

    @Test
    fun moreThanFourCoursesCollapseIntoExtraCount() {
        val courses = (1..6).map { course("课$it", 3, it * 2 - 1, it * 2) }
        val snap = TodayWidgetData.build(courses, 10, 3, 0, sections)
        assertEquals(TodayWidgetData.MAX_ROWS, snap.rows.size)
        assertEquals(2, snap.extra)
        assertEquals(6, snap.todayCount)
    }

    @Test
    fun statesMarkDoneOngoingNextAndLater() {
        // 第 1-2 节 08:30-09:50；第 3-4 节 10:10-11:30；第 5-6 节 11:35-13:10
        val courses = listOf(
            course("已上完", 3, 1, 2),
            course("正在上", 3, 3, 4),
            course("下一节", 3, 5, 6)
        )
        // 10:40（第 3 节中段）
        val snap = TodayWidgetData.build(courses, 10, 3, 10 * 60 + 40, sections)
        assertEquals(TodayWidgetData.STATE_DONE, snap.rows[0].state)
        assertEquals(TodayWidgetData.STATE_ONGOING, snap.rows[1].state)
        assertEquals(TodayWidgetData.STATE_NEXT, snap.rows[2].state)
    }

    @Test
    fun allDoneWhenEverythingEnded() {
        val courses = listOf(course("上午课", 3, 1, 2), course("中午课", 3, 3, 4))
        val snap = TodayWidgetData.build(courses, 10, 3, 23 * 60, sections)
        assertTrue(snap.allDone)
        assertTrue(snap.rows.all { it.state == TodayWidgetData.STATE_DONE })
    }

    @Test
    fun timeLabelUsesSectionTimesOrSectionNumbers() {
        val snap = TodayWidgetData.build(listOf(course("早课", 3, 1, 2)), 10, 3, 0, sections)
        assertEquals("08:30-09:50", snap.rows.single().time)

        // 作息表不够长时退化成「第N节」
        val short = listOf(SectionTimes.Section("08:30", "09:10"))
        val fallback = TodayWidgetData.build(listOf(course("晚课", 3, 9, 10)), 10, 3, 0, short)
        assertEquals("第9-10节", fallback.rows.single().time)
    }

    @Test
    fun roomFallsBackToTeacherWhenClassroomBlank() {
        val snap = TodayWidgetData.build(
            listOf(course("无教室", 3, 1, 2, teacher = "张老师")), 10, 3, 0, sections
        )
        assertEquals("张老师", snap.rows.single().room)
    }

    @Test
    fun weekdayOfMatchesIsoWeekday() {
        val monday = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 9) } // 2026-11-09 周一
        assertEquals(1, TodayWidgetData.weekdayOf(monday))
        val sunday = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 15) } // 周日
        assertEquals(7, TodayWidgetData.weekdayOf(sunday))
    }

    // ===== v1.46.0 三栏布局：已上完 / 正在上或下一节 / 接下来 =====

    @Test
    fun focusPrefersOngoingThenNext() {
        val courses = listOf(
            course("第一节", 3, 1, 2),
            course("第二节", 3, 3, 4),
            course("第三节", 3, 5, 6)
        )
        // 10:00 在第二节（10:10-11:30 之前不行）——用第二节区间内
        val duringSecond = TodayWidgetData.build(courses, 10, 3, 10 * 60 + 20, sections)
        assertEquals("第二节", duringSecond.focusRow?.name)

        // 第一节结束、第二节开始前：关注第二节
        val beforeSecond = TodayWidgetData.build(courses, 10, 3, 9 * 60 + 55, sections)
        assertEquals("第二节", beforeSecond.focusRow?.name)
        assertEquals(TodayWidgetData.STATE_NEXT, beforeSecond.focusRow?.state)
    }

    @Test
    fun doneAndLaterRowsSplitAroundFocus() {
        val courses = listOf(
            course("第一节", 3, 1, 2),
            course("第二节", 3, 3, 4),
            course("第三节", 3, 5, 6)
        )
        // 11:30 之后：第二节已上完，关注第三节
        val snap = TodayWidgetData.build(courses, 10, 3, 11 * 60 + 40, sections)
        assertEquals(listOf("第一节", "第二节"), snap.doneRows.map { it.name })
        assertEquals("第三节", snap.focusRow?.name)
        assertTrue(snap.laterRows.isEmpty())
    }

    @Test
    fun laterRowsAreCoursesAfterFocus() {
        val courses = listOf(
            course("第一节", 3, 1, 2),
            course("第二节", 3, 3, 4),
            course("第三节", 3, 5, 6)
        )
        // 08:40 正在上第一节：后面两节都在「接下来」
        val snap = TodayWidgetData.build(courses, 10, 3, 8 * 60 + 40, sections)
        assertEquals("第一节", snap.focusRow?.name)
        assertEquals(listOf("第二节", "第三节"), snap.laterRows.map { it.name })
        assertTrue(snap.doneRows.isEmpty())
    }

    @Test
    fun allDoneHasNoFocus() {
        val snap = TodayWidgetData.build(listOf(course("唯一一节", 3, 1, 2)), 10, 3, 23 * 60, sections)
        assertTrue(snap.allDone)
        assertTrue(snap.focusRow == null)
        assertEquals("唯一一节", snap.doneRows.single().name)
    }

    // ===== v1.45.0 常驻通知文案 =====

    @Test
    fun notificationHeaderShowsWeekdayAndWeek() {
        val snap = TodayWidgetData.build(emptyList(), 10, 4, 600, sections)
        assertEquals("今天：四 · 第10周", TodayWidgetData.headerText(snap))
    }

    @Test
    fun statusTextForEmptyDay() {
        val snap = TodayWidgetData.build(emptyList(), 10, 3, 600, sections)
        assertEquals("今天没有课，好好休息", TodayWidgetData.statusText(snap))
        assertTrue(TodayWidgetData.courseLines(snap).isEmpty())
    }

    @Test
    fun statusTextForOngoingCourse() {
        val snap = TodayWidgetData.build(
            listOf(course("高等数学1", 3, 1, 2, room = "1号教学楼101")), 10, 3, 8 * 60 + 40, sections
        )
        assertEquals("正在上 高等数学1 · 1号教学楼101", TodayWidgetData.statusText(snap))
    }

    @Test
    fun statusTextForNextCourseCountsDown() {
        val snap = TodayWidgetData.build(
            listOf(course("高等数学1", 3, 1, 2, room = "1号教学楼101")), 10, 3, 8 * 60, sections
        )
        assertEquals("下一节 08:30 高等数学1 · 还有 30 分钟", TodayWidgetData.statusText(snap))
    }

    @Test
    fun statusTextAfterAllCoursesDone() {
        val snap = TodayWidgetData.build(
            listOf(course("高等数学1", 3, 1, 2), course("大学英语1", 3, 3, 4)), 10, 3, 23 * 60, sections
        )
        assertEquals("今天的课已上完（共 2 门）", TodayWidgetData.statusText(snap))
    }

    @Test
    fun courseLinesIncludeTimeNameAndRoom() {
        val snap = TodayWidgetData.build(
            listOf(
                course("高等数学1", 3, 1, 2, room = "1号教学楼101"),
                course("大学英语1", 3, 3, 4)
            ), 10, 3, 0, sections
        )
        assertEquals(
            listOf("08:30-09:50 高等数学1 · 1号教学楼101", "10:10-11:30 大学英语1"),
            TodayWidgetData.courseLines(snap)
        )
    }

    @Test
    fun courseWithExplicitStartTimeUsesIt() {
        val c = course("自带时间", 3, 1, 2).copy(startTime = "07:00", endTime = "07:45")
        val snap = TodayWidgetData.build(listOf(c), 10, 3, 0, sections)
        assertEquals("07:00-07:45", snap.rows.single().time)
    }
}
