package com.classgrid.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真实教务系统导出的 .xls（肇庆学院「蓝翔 上课课程表.xls」）回归：
 * 文件是 OLE2 + BIFF8，表头「星期一…星期日」、左列「1-2节…」，格内是
 * 「课程★周次(节次)★教室★班级」这种用 ★ 分隔的写法，一格多课用换行分隔。
 */
class XlsImporterTest {

    private fun bytes(): ByteArray = File("src/test/resources/zqu_export.xls").readBytes()

    @Test
    fun recognizesOle2Header() {
        assertTrue(XlsImporter.isXls(bytes()))
        assertFalse(XlsImporter.isXls("PK\u0003\u0004".toByteArray()))
    }

    @Test
    fun readsWeekdayHeadersAndSectionLabels() {
        val sheet = XlsImporter.read(bytes())
        assertTrue("至少读到 8 行", sheet.rows.size >= 8)
        val flat = sheet.rows.joinToString("\n") { it.joinToString("\t") }
        assertTrue(flat.contains("节次"))
        for (day in listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")) {
            assertTrue("缺少 $day", flat.contains(day))
        }
        assertTrue("缺少 1-2节 行标签", flat.contains("1-2节"))
    }

    @Test
    fun parsesTimetableIntoCourses() {
        val sheet = XlsImporter.read(bytes())
        val courses = TimetableGrid.parse(sheet)
        assertTrue("应解析出至少 10 门课，实际 ${courses.size}", courses.size >= 10)

        val pe = courses.firstOrNull { it.name.contains("大学体育") }
        assertNotNull("应识别出大学体育1", pe)
        assertEquals(1, pe!!.dayOfWeek)           // 周一
        assertEquals(1, pe.startSection)          // (01,02节)
        assertEquals(2, pe.endSection)
        assertEquals(1, pe.startWeek)             // 1-18 周
        assertEquals(18, pe.endWeek)
        assertTrue(pe.classroom.contains("艺术大楼"))

        val mondayMath = courses.firstOrNull { it.name.contains("高等数学") && it.dayOfWeek == 1 }
        assertNotNull("周一应有高等数学Ⅰ1", mondayMath)
        assertEquals(6, mondayMath!!.startSection) // (06,07节)
        assertEquals(7, mondayMath.endSection)
        assertEquals(7, mondayMath.startWeek)
        assertEquals(17, mondayMath.endWeek)

        // 周五的两条大学英语（17周 8-9节 + 7-16周 8-10节）应合并成一条
        val fridayEnglish = courses.filter { it.name.contains("大学英语") && it.dayOfWeek == 5 }
        assertEquals("周五大学英语应合并为 1 条，实际 ${fridayEnglish.size}", 1, fridayEnglish.size)
        assertEquals(7, fridayEnglish[0].startWeek)
        assertEquals(17, fridayEnglish[0].endWeek)
        assertEquals(8, fridayEnglish[0].startSection)
        assertEquals(10, fridayEnglish[0].endSection)
    }

    @Test
    fun multipleCoursesInOneCellAreSplit() {
        val sheet = XlsImporter.read(bytes())
        val courses = TimetableGrid.parse(sheet)
        val wed = courses.filter { it.dayOfWeek == 3 }
        assertTrue("周三应有 形势与政策1", wed.any { it.name.contains("形势与政策") })
        assertTrue("周三应有 高级语言程序设计", wed.any { it.name.contains("高级语言程序设计") })
        // 同一格里的两门课不能被拼成「课程名=班级」或「教师=下一门课」
        assertTrue("不能把班级名当课程名", courses.none { it.name.contains("级软件工程") })
        assertTrue("不能把课程名当教师", courses.none { it.teacher.contains("高级语言") })
    }
}
