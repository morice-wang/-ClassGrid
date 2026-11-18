package com.classgrid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * v1.42.0：Excel/CSV 读取。用代码现场生成 xlsx（zip+XML）与各种 CSV，不依赖外部样例文件。
 */
class SheetImporterTest {

    @Test
    fun parsesCommaCsv() {
        val rows = SheetImporter.parseCsv("节次,周一,周二\n第1-2节,\"高等数学1, 刘睿\",\n")
        assertEquals(2, rows.size)
        assertEquals(listOf("节次", "周一", "周二"), rows[0])
        assertEquals("高等数学1, 刘睿", rows[1][1])
    }

    @Test
    fun parsesTabCsvAutomatically() {
        val rows = SheetImporter.parseCsv("节次\t周一\t周二\n第1-2节\t高等数学1\t")
        assertEquals(2, rows.size)
        assertEquals("周一", rows[0][1])
        assertEquals("高等数学1", rows[1][1])
    }

    @Test
    fun quotesSurviveEmbeddedQuotesAndNewlines() {
        val rows = SheetImporter.parseCsv("\"他说\"\"你好\"\"\",b\n\"第一行\n第二行\",c")
        assertEquals(2, rows.size)
        assertEquals("他说\"你好\"", rows[0][0])
        assertEquals("第一行\n第二行", rows[1][0])
    }

    @Test
    fun decodesUtf8AndGb18030() {
        val text = "课表,周一\n高等数学,1"
        assertEquals(text, SheetImporter.decode(text.toByteArray(Charsets.UTF_8)))
        val gb = text.toByteArray(charset("GB18030"))
        assertEquals(text, SheetImporter.decode(gb))
    }

    @Test
    fun readsMinimalXlsxWithSharedStringsAndMergeCells() {
        val bytes = buildXlsx()
        val sheet = SheetImporter.readXlsx(bytes)
        assertTrue(sheet.rows.size >= 3)
        assertEquals("节次", sheet.rows[0][0])
        assertEquals("周一", sheet.rows[0][1])
        assertEquals("高等数学1", sheet.rows[1][1])
        // B2:B3 是合并单元格，B3 应该被填成「高等数学1」
        assertEquals("高等数学1", sheet.rows[2][1])
    }

    @Test
    fun dispatchReadsCsvByExtension() {
        val sheet = SheetImporter.read("课表.csv", "节次,周一\n第1-2节,高等数学".toByteArray())
        assertEquals("高等数学", sheet.rows[1][1])
    }

    @Test
    fun csvGridFeedsTimetableParser() {
        val csv = buildString {
            append("节次,周一,周二,周三,周四,周五\n")
            append("第1-2节,\"高等数学1\n刘睿\n1-16周\n1号教学楼101\",,,,\n")
            append("第3-4节,,\"大学英语\n王芳\n2-16周(双)\",,,\n")
            append("第5-6节,,,,\"形势与政策\n赵敏\n9-16周\",\n")
        }
        val sheet = SheetImporter.read("课表.csv", csv.toByteArray())
        val courses = TimetableGrid.parse(sheet.rows, SectionTimes.default())
        assertEquals(3, courses.size)
        assertTrue(courses.any { it.name.contains("高等数学") && it.dayOfWeek == 1 })
        assertTrue(courses.any { it.name.contains("大学英语") && it.dayOfWeek == 2 })
        assertTrue(courses.any { it.name.contains("形势与政策") && it.dayOfWeek == 4 })
    }

    // ------------------------------------------------------------------ 构造最小 xlsx

    private fun buildXlsx(): ByteArray {
        val shared = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2">
              <si><t>周一</t></si>
              <si><t>高等数学1</t></si>
            </sst>
        """.trimIndent()

        val sheetXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1">
                  <c r="A1" t="inlineStr"><is><t>节次</t></is></c>
                  <c r="B1" t="s"><v>0</v></c>
                </row>
                <row r="2">
                  <c r="A2" t="inlineStr"><is><t>第1-2节</t></is></c>
                  <c r="B2" t="s"><v>1</v></c>
                </row>
                <row r="3">
                  <c r="A3" t="inlineStr"><is><t>第3-4节</t></is></c>
                </row>
              </sheetData>
              <mergeCells count="1"><mergeCell ref="B2:B3"/></mergeCells>
            </worksheet>
        """.trimIndent()

        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zip.write(shared.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheetXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }
}
