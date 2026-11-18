package com.classgrid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class TodoRepeatTest {

    /** 2026-09-07（周一）第一周开始 */
    private fun monday(): Long = Calendar.getInstance().apply {
        clear()
        set(2026, Calendar.SEPTEMBER, 7, 0, 0, 0)
    }.timeInMillis

    private fun day(offsetDays: Int): Long = monday() + offsetDays * 86_400_000L

    private fun todo(dateMillis: Long, repeat: Boolean = true) = Todo(
        id = 1L, title = "清点物资室", dateMillis = dateMillis, time = "10:00", endTime = "11:00",
        repeatWeekly = repeat
    )

    @Test
    fun weekIndexOfCountsFromSemesterStart() {
        assertEquals(1, Todo.weekIndexOf(day(0), monday()))
        assertEquals(2, Todo.weekIndexOf(day(7), monday()))
        assertEquals(3, Todo.weekIndexOf(day(14), monday()))
    }

    @Test
    fun singleTodoOnlyMatchesItsOwnDay() {
        val t = todo(day(2), repeat = false)
        assertTrue(t.occursOn(day(2), monday(), 20))
        assertFalse(t.occursOn(day(9), monday(), 20))
    }

    @Test
    fun weeklyTodoRepeatsOnSameWeekday() {
        val t = todo(day(2))
        assertTrue(t.occursOn(day(2), monday(), 20))
        assertTrue(t.occursOn(day(9), monday(), 20))
        assertTrue(t.occursOn(day(16), monday(), 20))
        assertFalse("同周其它天不命中", t.occursOn(day(3), monday(), 20))
        assertFalse("第一周之前不命中", t.occursOn(day(-5), monday(), 20))
        assertFalse("超出学期周数不命中", t.occursOn(day(2 + 7 * 25), monday(), 20))
    }

    @Test
    fun repeatHonouredEvenWithoutSemesterStart() {
        val t = todo(day(2))
        assertTrue(t.occursOn(day(9), 0L, 20))
    }

    @Test
    fun weekRangeLimitsRepeat() {
        val t = todo(day(2)).copy(startWeek = 3, endWeek = 6)
        assertFalse("第 2 周还没开始", t.occursOn(day(9), monday(), 20))
        assertTrue(t.occursOn(day(16), monday(), 20))
        assertTrue(t.occursOn(day(37), monday(), 20))
        assertFalse("第 7 周已结束", t.occursOn(day(44), monday(), 20))
    }

    @Test
    fun oddWeeksOnlyHitOddWeeks() {
        val t = todo(day(2)).copy(startWeek = 1, endWeek = 10, weekType = Course.WEEK_ODD)
        assertTrue("第 3 周是单周", t.occursOn(day(16), monday(), 20))
        assertFalse("第 4 周是双周", t.occursOn(day(23), monday(), 20))
        assertTrue("第 5 周是单周", t.occursOn(day(30), monday(), 20))
    }

    @Test
    fun evenWeeksOnlyHitEvenWeeks() {
        val t = todo(day(2)).copy(startWeek = 1, endWeek = 10, weekType = Course.WEEK_EVEN)
        assertFalse(t.occursOn(day(16), monday(), 20))
        assertTrue(t.occursOn(day(23), monday(), 20))
    }

    @Test
    fun openEndedRangeUsesTotalWeeks() {
        val t = todo(day(2)).copy(startWeek = 5, endWeek = 0)
        assertFalse(t.occursOn(day(16), monday(), 20))
        assertTrue(t.occursOn(day(37), monday(), 20))
        assertFalse("超出总周数", t.occursOn(day(2 + 7 * 21), monday(), 20))
    }

    @Test
    fun repeatDescDescribesRangeAndType() {
        val t = todo(day(2)).copy(startWeek = 2, endWeek = 16, weekType = Course.WEEK_ODD)
        assertEquals("单周 第2-16周", t.repeatDesc(20))
        assertEquals("", todo(day(2), repeat = false).repeatDesc(20))
    }

    @Test
    fun millisOfAndDayOfWeekRoundTrip() {
        val monday = monday()
        // 第 3 周的周三
        val millis = Todo.millisOf(monday, 3, 3)
        assertEquals(3, Todo.weekIndexOf(millis, monday))
        assertEquals(3, Todo.dayOfWeek(millis))
    }

    @Test
    fun singleWeekEventOccursOnlyOnItsDate() {
        val date = Todo.millisOf(monday(), 4, 5)
        val t = todo(date, repeat = false)
        assertTrue(t.occursOn(date, monday(), 20))
        assertFalse("下一周同一天不应命中", t.occursOn(date + 7L * 86_400_000L, monday(), 20))
    }
}
