package com.classgrid.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WebJsonParser] 的 JVM 单元测试。
 *
 * 为什么写在这里：本项目没有真机/模拟器，「从教务接口 JSON 里能不能解析出正确课表」
 * 只能靠纯逻辑单测验证；解析器刻意不依赖 Android Context（见 WebScheduleParser 的同类说明）。
 *
 * 样本形态来自两类来源：
 *  - 现有强智 jsxsd 的公开字段命名（kcmc/jsxm/cdmc/xqj/jcs/zcd），字段名靠真实抓包回传后再补；
 *  - 用户设备会回传未知系统（智慧广外门户/金智 ehall），所以这里刻意覆盖「字段名不同、
 *    嵌套层级不同、值类型不同」的变体，验证宽容匹配而不是只验证一种 schema。
 */
class WebJsonParserTest {

    private val url = "https://info.gdufs.edu.cn/api/jw/xskb"

    // ---------------------------------------------------------------- 构造工具

    private fun doc(body: String, docUrl: String = url): WebScheduleParser.NetDoc =
        WebScheduleParser.NetDoc(url = docUrl, status = 200, body = body)

    /** 跑一次解析，返回 (课程, notes)；notes 必须非 null（App 诊断弹窗直接读它） */
    private fun runParse(net: List<WebScheduleParser.NetDoc>): Pair<List<Course>, MutableList<String>> {
        val notes = mutableListOf<String>()
        val courses = WebJsonParser.parse(net = net, notes = notes)
        return courses to notes
    }

    private fun dump(courses: List<Course>): String = courses.joinToString("\n") { c ->
        "name=${c.name} code=${c.code} day=${c.dayOfWeek} sec=${c.startSection}-${c.endSection} " +
            "week=${c.startWeek}-${c.endWeek} type=${c.weekType} ex=${c.excludedWeeks} " +
            "teacher=${c.teacher} room=${c.classroom}"
    }

    // ---------------------------------------------------------------- 1) 典型扁平结构

    @Test
    fun parseFlatStrongZhiStyle() {
        val body = """
            [
              {"kcmc":"高等数学","jsxm":"胡艳","cdmc":"南G211","xqj":"1","jcs":"01-02","zcd":"1-16周"},
              {"kcmc":"综合英语","jsxm":"姚银燕","cdmc":"南G409","xqj":"2","jcs":"03-05","zcd":"1-16周"}
            ]
        """.trimIndent()
        val (courses, notes) = runParse(listOf(doc(body)))
        println("flat: " + dump(courses))
        assertEquals("应该解析出 2 门课", 2, courses.size)

        val math = courses.first { it.name == "高等数学" }
        assertEquals("胡艳", math.teacher)
        assertEquals("南G211", math.classroom)
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startSection)
        assertEquals(2, math.endSection)
        assertEquals(1, math.startWeek)
        assertEquals(16, math.endWeek)
        assertEquals(Course.WEEK_EVERY, math.weekType)
        assertEquals("id 必须由外层重新发号", 0L, math.id)
        assertEquals(0, math.colorIndex)

        val english = courses.first { it.name == "综合英语" }
        assertEquals(2, english.dayOfWeek)
        assertEquals(3, english.startSection)
        assertEquals(5, english.endSection)

        assertTrue("成功提示应带接口地址: $notes", notes.any { it.contains("已从接口数据解析 2 门课程") })
        assertTrue("成功提示应带接口 URL: $notes", notes.any { it.contains(url) })
    }

    // ---------------------------------------------------------------- 2) 嵌套结构

    @Test
    fun parseNestedDataList() {
        val body = """
            {"code":200,"msg":"ok","data":{"total":2,"pageSize":20,"list":[
              {"kcmcstr":"数据结构","jsmc":"刘老师","cdmcstr":"教三301","xqjstr":"星期三","jcstr":"06-07","zcstr":"1-16周"},
              {"kcmcstr":"操作系统","jsmc":"陈老师","cdmcstr":"教三302","xqjstr":"星期五","jcstr":"8-9","zcstr":"2-16周"}
            ]}}
        """.trimIndent()
        val (courses, notes) = runParse(listOf(doc(body, "https://info.gdufs.edu.cn/api/jw/list")))
        println("nested: " + dump(courses))
        assertEquals("递归扫描应该能找到 data.list", 2, courses.size)
        assertEquals(setOf("数据结构", "操作系统"), courses.map { it.name }.toSet())
        val ds = courses.first { it.name == "数据结构" }
        assertEquals(3, ds.dayOfWeek)
        assertEquals(6, ds.startSection)
        assertEquals(7, ds.endSection)
        assertTrue(notes.isNotEmpty())
    }

    // ---------------------------------------------------------------- 3) 中文 key

    @Test
    fun parseChineseKeys() {
        val body = """
            [
              {"课程名称":"综合英语","教师":"姚银燕","上课地点":"南G409","星期":"周二","节次":"第3-5节","周次":"1-16周"},
              {"课程名称":"体育","教师":"王五","上课地点":"田径场","星期":"星期四","节次":"第6-7节","周次":"2-16周"}
            ]
        """.trimIndent()
        val (courses, notes) = runParse(listOf(doc(body)))
        println("chinese: " + dump(courses))
        assertEquals(2, courses.size)

        val english = courses.first { it.name == "综合英语" }
        assertEquals("姚银燕", english.teacher)
        assertEquals("南G409", english.classroom)
        assertEquals(2, english.dayOfWeek)
        assertEquals(3, english.startSection)
        assertEquals(5, english.endSection)
        assertEquals(1, english.startWeek)
        assertEquals(16, english.endWeek)

        val pe = courses.first { it.name == "体育" }
        assertEquals(4, pe.dayOfWeek)
        assertEquals(6, pe.startSection)
        assertEquals(7, pe.endSection)
        assertEquals(2, pe.startWeek)
        assertTrue(notes.any { it.contains("已从接口数据解析 2 门课程") })
    }

    // ---------------------------------------------------------------- 4) 脏数据容错

    @Test
    fun dirtyDataReturnsEmptyWithoutThrowing() {
        val net = listOf(
            doc("not json", "u1"),
            doc("", "u2"),
            doc("<html><body>请在智慧广外登录</body></html>", "u3"),
            doc("""[{"foo":"bar"}]""", "u4")
        )
        val (courses, notes) = runParse(net)
        assertTrue("脏数据不应解析出课程", courses.isEmpty())
        assertNotNull(notes)
        assertTrue("应说明 HTML 页面被跳过: $notes", notes.any { it.contains("HTML") })
        assertTrue("应给出「都不像课表数据」结论: $notes", notes.any { it.contains("都不像课表数据") })
    }

    @Test
    fun emptyNetReturnsEmptyWithNote() {
        val (courses, notes) = runParse(emptyList())
        assertTrue(courses.isEmpty())
        assertTrue("空响应列表也要给出原因: $notes", notes.any { it.contains("没有捕获到任何接口响应") })
    }

    // ---------------------------------------------------------------- 5) 缺周次兜底

    @Test
    fun missingWeeksDefaultsTo20AndNotesIt() {
        val body = """
            [
              {"kcmc":"体育","jsxm":"王五","xqj":"3","jcs":"06-07"},
              {"kcmc":"音乐鉴赏","xqj":"4","jcs":"08-09"}
            ]
        """.trimIndent()
        val (courses, notes) = runParse(listOf(doc(body)))
        println("missing-week: " + dump(courses))
        assertEquals(2, courses.size)
        courses.forEach {
            assertEquals("缺周次应按 1 周开始", 1, it.startWeek)
            assertEquals("缺周次应按 20 周兜底", 20, it.endWeek)
        }
        assertTrue(
            "应提示用户去核对页确认: $notes",
            notes.any { it.contains("部分课程缺少周次信息") && it.contains("1-20") }
        )
        // 提示只应出现一次，不要每门课刷一条
        assertEquals(1, notes.count { it.contains("部分课程缺少周次信息") })
    }

    // ---------------------------------------------------------------- 6) 倒序节次

    @Test
    fun reversedSectionsAreNormalized() {
        val body = """
            [
              {"kcmc":"线性代数","xqj":"2","jcs":"05-03","zcd":"1-16周"},
              {"kcmc":"概率论","xqj":"3","jcs":"10-02","zcd":"1-16周"}
            ]
        """.trimIndent()
        val (courses, notes) = runParse(listOf(doc(body)))
        println("reversed: " + dump(courses))
        assertEquals(2, courses.size)
        for (c in courses) {
            assertTrue("endSection 不能小于 startSection: ${c.name} ${c.startSection}-${c.endSection}",
                c.endSection >= c.startSection)
            assertTrue("节次必须夹在 1..15 内", c.startSection in 1..15 && c.endSection in 1..15)
        }
        val la = courses.first { it.name == "线性代数" }
        assertEquals(3, la.startSection)
        assertEquals(5, la.endSection)
        val prob = courses.first { it.name == "概率论" }
        assertEquals(2, prob.startSection)
        assertEquals(10, prob.endSection)
        assertTrue(notes.isNotEmpty())
    }

    // ---------------------------------------------------------------- 7) kch 不能当课程名

    @Test
    fun kchIsNotCourseName() {
        val body = """
            [
              {"kch":"12345","kcmc":"高等数学","xqj":"1","jcs":"01-02","zcd":"1-16周"},
              {"kch":"67890","kcmc":"大学物理","xqj":"2","jcs":"03-04","zcd":"1-16周"}
            ]
        """.trimIndent()
        val (courses, notes) = runParse(listOf(doc(body)))
        println("kch: " + dump(courses))
        assertEquals(2, courses.size)
        assertEquals(setOf("高等数学", "大学物理"), courses.map { it.name }.toSet())
        assertFalse("课程代码绝不能变成课程名", courses.any { it.name == "12345" || it.name == "67890" })
        assertEquals("课程代码应进 code 字段", "12345", courses.first { it.name == "高等数学" }.code)
        assertEquals("67890", courses.first { it.name == "大学物理" }.code)
        assertTrue(notes.isNotEmpty())
    }

    // ---------------------------------------------------------------- 8) 跨响应去重

    @Test
    fun dedupeAcrossNetDocs() {
        // 同一个课表接口被 fetch/XHR 两个钩子各抓一次：内容相同的两份响应
        fun body() = """
            [
              {"kcmc":"高等数学","jsxm":"胡艳","cdmc":"南G211","xqj":"1","jcs":"01-02","zcd":"1-16周"},
              {"kcmc":"综合英语","jsxm":"姚银燕","cdmc":"南G409","xqj":"2","jcs":"03-05","zcd":"1-16周"}
            ]
        """.trimIndent()
        val (courses, notes) = runParse(
            listOf(
                doc(body(), "https://info.gdufs.edu.cn/api/jw/xskb"),
                doc(body(), "https://info.gdufs.edu.cn/api/jw/xskb?t=2")
            )
        )
        println("dedupe: " + dump(courses))
        assertEquals("重复课程应被去重", 2, courses.size)
        assertEquals(setOf("高等数学", "综合英语"), courses.map { it.name }.toSet())
        assertTrue("应说明合并了多少门: $notes", notes.any { it.contains("合并重复课程") })
    }

    // ---------------------------------------------------------------- 9) 节次编码变体

    @Test
    fun fourDigitSectionCodeAndCommaSection() {
        // 强智 4 位编码 "0102" 必须按两位切；"1,2" 这种逗号写法也要能吃
        val body = """
            [
              {"kcmc":"甲课程","xqj":"1","jcs":"0102"},
              {"kcmc":"乙课程","xqj":"2","jcs":"1,2"},
              {"kcmc":"丙课程","xqj":"3","jcs":"第3节"}
            ]
        """.trimIndent()
        val (courses, notes) = runParse(listOf(doc(body)))
        println("section-codes: " + dump(courses))
        assertEquals(3, courses.size)
        val a = courses.first { it.name == "甲课程" }
        assertEquals(1, a.startSection)
        assertEquals(2, a.endSection)
        val b = courses.first { it.name == "乙课程" }
        assertEquals(1, b.startSection)
        assertEquals(2, b.endSection)
        val c = courses.first { it.name == "丙课程" }
        assertEquals(3, c.startSection)
        assertEquals(3, c.endSection)
        assertTrue(notes.isNotEmpty())
    }

    // ---------------------------------------------------------------- 10) 值类型宽容

    @Test
    fun numericValuesAreAccepted() {
        // xqj / jcs 用 JSON 数字而不是字符串（部分接口把编码直接当 int 返回）
        val body = """
            [
              {"kcmc":"体育","jsxm":"王五","xqj":5,"jcs":6,"zcd":"1-16周"},
              {"kcmc":"美术","xqj":6,"jcs":7,"zcd":"1-16周"}
            ]
        """.trimIndent()
        val (courses, notes) = runParse(listOf(doc(body)))
        println("numeric: " + dump(courses))
        assertEquals(2, courses.size)
        assertEquals(5, courses.first { it.name == "体育" }.dayOfWeek)
        assertEquals(6, courses.first { it.name == "体育" }.startSection)
        assertEquals(6, courses.first { it.name == "美术" }.dayOfWeek)
        assertTrue(notes.isNotEmpty())
    }

    // ---------------------------------------------------------------- 11) notes URL 截断

    @Test
    fun longUrlIsTruncatedInNotes() {
        val longUrl = "https://info.gdufs.edu.cn/api/" + "x".repeat(200)
        val body = """
            [
              {"kcmc":"高等数学","xqj":"1","jcs":"01-02","zcd":"1-16周"},
              {"kcmc":"大学物理","xqj":"2","jcs":"03-04","zcd":"1-16周"}
            ]
        """.trimIndent()
        val (courses, notes) = runParse(listOf(doc(body, longUrl)))
        assertEquals(2, courses.size)
        val note = notes.first { it.contains("已从接口数据解析") }
        assertTrue("notes 里的 URL 应截断到 80 字: ${note.length}", note.length < 200)
        assertFalse("notes 里不应出现完整超长 URL", note.contains(longUrl))
    }

    // ---------------------------------------------------------------- 12) 包裹字段的容错

    @Test
    fun wrappedScalarValuesAreAccepted() {
        // 金智/门户接口常把字段包成 {"value": "..."} 或 {"name": "..."}
        val body = """
            [
              {"kcmc":{"value":"高等数学"},"jsxm":{"name":"胡艳"},"xqj":{"value":"1"},"jcs":{"value":"01-02"},"zcd":{"value":"1-16周"}},
              {"kcmc":{"value":"大学物理"},"jsxm":{"name":"李四"},"xqj":{"value":"2"},"jcs":{"value":"03-04"},"zcd":{"value":"1-16周"}}
            ]
        """.trimIndent()
        val (courses, notes) = runParse(listOf(doc(body)))
        println("wrapped: " + dump(courses))
        assertEquals(2, courses.size)
        assertEquals("高等数学", courses.first { it.dayOfWeek == 1 }.name)
        assertEquals("胡艳", courses.first { it.dayOfWeek == 1 }.teacher)
        assertTrue(notes.isNotEmpty())
    }

    // ---------------------------------------------------------------- 13) 节次缺失/中文大节（真机回归）

    @Test
    fun missingSectionBecomesUnknownInsteadOfGuessed() {
        // 接口里没给节次时，解析器不能再自己猜 1-2 节（那是 v1.32.0 把 14-15 节课程排到周一早八
        // 的根因）。约定：startSection/endSection = 0 表示「未知」，由上层用页面网格补齐。
        val body = """
            [
              {"kcmc":"国家安全教育","jsxm":"文飞","cdmc":"南G407","xqj":"2","zcd":"9-12周"},
              {"kcmc":"高等数学","jsxm":"胡艳","cdmc":"南G211","xqj":"1","zcd":"2-16周"}
            ]
        """.trimIndent()
        val (courses, _) = runParse(listOf(doc(body)))
        println("unknown-section: " + dump(courses))
        assertEquals(2, courses.size)
        assertTrue(courses.all { it.startSection == 0 && it.endSection == 0 })
    }

    @Test
    fun chineseBigSectionTextIsParsed() {
        // 门户接口常把节次塞进一段人读文本（"第七大节（14,15小节）20:00 ~ 21:20"）
        val body = """
            [
              {"kcmc":"国家安全教育","jsxm":"文飞","xqj":"2",
               "上课时间":"第七大节（14,15小节）20:00 ~ 21:20","zcd":"9-12周"},
              {"kcmc":"高等数学","jsxm":"胡艳","xqj":"1","jcs":"01-02","zcd":"2-16周"}
            ]
        """.trimIndent()
        val (courses, _) = runParse(listOf(doc(body)))
        println("cn-big-section: " + dump(courses))
        assertEquals(2, courses.size)
        val safety = courses.first { it.name.contains("国家安全教育") }
        assertEquals(14, safety.startSection)
        assertEquals(15, safety.endSection)
    }

    @Test
    fun sectionIsFoundEvenWhenFieldNameIsUnknown() {
        // 真机接口可能把节次塞进自定义字段（这里用 sjms 模拟），字段名认不出来时
        // 解析器会扫一遍所有字符串值兜底，避免整门课退回第 1-2 节。
        val body = """
            [
              {"kcmc":"国家安全教育","jsxm":"文飞","xqj":"2","sjms":"第七大节（14,15小节）20:00 ~ 21:20","zcd":"9-12周"},
              {"kcmc":"高等数学","jsxm":"胡艳","xqj":"1","jcs":"01-02","zcd":"2-16周"}
            ]
        """.trimIndent()
        val (courses, _) = runParse(listOf(doc(body)))
        println("unknown-field-section: " + dump(courses))
        val safety = courses.first { it.name.contains("国家安全教育") }
        assertEquals(14, safety.startSection)
        assertEquals(15, safety.endSection)
    }

    // ---------------------------------------------------------------- 10) 学期字段不能被当成星期

    @Test
    fun semesterFieldIsNotTreatedAsWeekday() {
        // v1.36.0 真机问题：全部课程都被抓成「周二」。
        // 根因是接口里的 `xq`（学期编号，值 "2"）被当成星期字段 → parseDay("2") = 2。
        // 这里同时给 xq 与正确的 xqj，必须使用 xqj 的星期。
        val body = """
            [
              {"kcmc":"高等数学","jsxm":"胡艳","xq":"2","xqj":"1","jcs":"01-02","zcd":"1-16周"},
              {"kcmc":"综合英语","jsxm":"姚银燕","xq":"2","xqj":"3","jcs":"03-05","zcd":"1-16周"}
            ]
        """.trimIndent()
        val (courses, _) = runParse(listOf(doc(body)))
        println("semester-vs-weekday: " + dump(courses))
        assertEquals(2, courses.size)
        assertEquals(1, courses.first { it.name == "高等数学" }.dayOfWeek)
        assertEquals(3, courses.first { it.name == "综合英语" }.dayOfWeek)
    }

    @Test
    fun onlySemesterFieldMeansUnknownWeekdayNotGuessed() {
        // 只有学期字段、没有真星期：宁可丢弃这门课，也不能猜成「周二」导进课表
        val body = """
            [
              {"kcmc":"高等数学","jsxm":"胡艳","xq":"2","jcs":"01-02","zcd":"1-16周"},
              {"kcmc":"综合英语","jsxm":"姚银燕","xq":"2","jcs":"03-05","zcd":"1-16周"}
            ]
        """.trimIndent()
        val (courses, notes) = runParse(listOf(doc(body)))
        println("only-semester: " + dump(courses) + " notes=" + notes)
        assertTrue("没有真星期字段时不应猜出课程", courses.isEmpty())
    }

    // ---------------------------------------------------------------- 11) 宽松模式：星期未知也先收下

    @Test
    fun keepUnknownDayKeepsCoursesWithZeroWeekday() {
        // v1.36.1：严格模式会把「接口里根本没有星期字段」的课全丢掉 → 用户体感是「抓取失败」。
        // 宽松模式保留 day=0，交给页面网格位置补星期（WebImportActivity.repairUnknownDays）。
        val body = """
            [
              {"kcmc":"高等数学","jsxm":"胡艳","xq":"2","jcs":"01-02","zcd":"1-16周"},
              {"kcmc":"综合英语","jsxm":"姚银燕","xq":"2","jcs":"03-05","zcd":"1-16周"}
            ]
        """.trimIndent()
        val notes = mutableListOf<String>()
        val courses = WebJsonParser.parse(listOf(doc(body)), notes = notes, keepUnknownDay = true)
        println("keep-unknown: " + dump(courses))
        assertEquals(2, courses.size)
        assertTrue("宽松模式下星期应为 0（未知）", courses.all { it.dayOfWeek == 0 })
        assertEquals("高等数学", courses.first().name)
    }

    @Test
    fun strictModeIsStillDefault() {
        // 默认参数必须保持严格：不传 keepUnknownDay 时行为不能悄悄变
        val body = """
            [
              {"kcmc":"高等数学","jsxm":"胡艳","xq":"2","jcs":"01-02","zcd":"1-16周"},
              {"kcmc":"综合英语","jsxm":"姚银燕","xq":"2","jcs":"03-05","zcd":"1-16周"}
            ]
        """.trimIndent()
        val courses = WebJsonParser.parse(listOf(doc(body)), notes = mutableListOf())
        assertTrue("默认（严格）模式仍应丢弃没有星期的课", courses.isEmpty())
    }
}
