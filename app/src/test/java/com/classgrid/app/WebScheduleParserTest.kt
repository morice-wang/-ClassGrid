package com.classgrid.app

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WebScheduleParser] 的 JVM 单元测试。
 *
 * 为什么写在这里：本项目没有可用的模拟器/真机（adb 无设备、SDK 无 emulator），
 * 「链接抓取能不能解析出正确课表」只能靠纯逻辑单测来验证。解析器刻意做成不依赖 Android Context，
 * 就是为了能这样测（见 docs/DEVLOG.md 的验证策略）。
 *
 * 样本形态来自 docs/JWXT_RESEARCH.md：
 *  - 广外是湖南强智 jsxsd，课表为服务端渲染的大表格 `#kbtable`；
 *  - 单元格 `.kbcontent` 文本形如 `1-16(周)[01-02节]`，多字段靠换行分隔；
 *  - 一格里可能有多门课（子 div 拆成 blocks）。
 */
class WebScheduleParserTest {

    // ---------------------------------------------------------------- 构造工具

    private fun cell(
        text: String,
        blocks: List<String>? = null,
        rs: Int = 1,
        cs: Int = 1
    ): JSONObject = JSONObject().apply {
        put("text", text)
        put("rs", rs)
        put("cs", cs)
        if (blocks != null) {
            put("blocks", JSONArray().apply { blocks.forEach { put(it) } })
        }
    }

    private fun row(vararg cells: JSONObject): JSONObject =
        JSONObject().apply { put("cells", JSONArray().apply { cells.forEach { put(it) } }) }

    private fun table(vararg rows: JSONObject): JSONObject = JSONObject().apply {
        put("id", "kbtable")
        put("cls", "Nsb_r_list Nsb_table")
        put("frame", 1)
        put("rows", JSONArray().apply { rows.forEach { put(it) } })
    }

    private fun payload(vararg tables: JSONObject): String = JSONObject().apply {
        put("url", "https://jwxt.gdufs.edu.cn/jsxsd/xskb/xskb_list.do")
        put("title", "课表查询")
        put("tables", JSONArray().apply { tables.forEach { put(it) } })
        put("bodyText", "")
        put("hasKbTable", true)
        put("manual", false)
    }.toString()

    private fun parse(json: String): WebScheduleParser.ParseResult {
        val p = WebScheduleParser.parsePayload(json)
        assertNotNull("payload 应该能解析", p)
        return WebScheduleParser.parse(p!!)
    }

    private fun dump(result: WebScheduleParser.ParseResult): String =
        result.courses.joinToString("\n") { c ->
            "name=${c.name} day=${c.dayOfWeek} sec=${c.startSection}-${c.endSection} " +
                "week=${c.startWeek}-${c.endWeek} type=${c.weekType} ex=${c.excludedWeeks} " +
                "teacher=${c.teacher} room=${c.classroom} time=${c.startTime}-${c.endTime}"
        } + "\nnotes=" + result.notes

    // ---------------------------------------------------------------- 基本能力

    /** 横表：首行是星期表头，左列是大节，正文单元格多行（最接近强智真实形态） */
    @Test
    fun parseHorizontalTable_strongZhiStyle() {
        val courseCell = cell(
            "高等数学\n张老师\n1-16(周)[01-02节]\n教学楼A101",
            blocks = listOf("高等数学\n张老师\n1-16(周)[01-02节]\n教学楼A101")
        )
        val json = payload(
            table(
                row(
                    cell("节次"), cell("星期一"), cell("星期二"), cell("星期三"),
                    cell("星期四"), cell("星期五"), cell("星期六"), cell("星期日")
                ),
                row(cell("1-2节"), courseCell, cell(""), cell(""), cell(""), cell(""), cell(""), cell(""))
            )
        )
        val r = parse(json)
        println("horizontal: " + dump(r))
        assertEquals("应该解析出 1 门课", 1, r.courses.size)
        val c = r.courses[0]
        assertEquals("高等数学", c.name)
        assertEquals(1, c.dayOfWeek)
        assertEquals(1, c.startSection)
        assertEquals(2, c.endSection)
        assertEquals(1, c.startWeek)
        assertEquals(16, c.endWeek)
        assertEquals(Course.WEEK_EVERY, c.weekType)
        assertTrue("教师应被识别: ${c.teacher}", c.teacher.contains("张"))
        assertTrue("教室应被识别: ${c.classroom}", c.classroom.contains("A101"))
    }

    /** 一格多课：blocks 里两门课 → 两门课（周次不同） */
    @Test
    fun parseCellWithMultipleBlocks() {
        val b1 = "大学英语\n李老师\n1-8(周)[01-02节]\n北校区B203"
        val b2 = "大学英语\n李老师\n9-16(周)[01-02节]\n北校区B203"
        val json = payload(
            table(
                row(
                    cell("节次"), cell("星期一"), cell("星期二"), cell("星期三"),
                    cell("星期四"), cell("星期五"), cell("星期六"), cell("星期日")
                ),
                row(cell("1-2节"), cell(b1 + "\n" + b2, listOf(b1, b2)), cell(""), cell(""), cell(""), cell(""), cell(""), cell(""))
            )
        )
        val r = parse(json)
        println("multi-block: " + dump(r))
        assertEquals("同一门课的相邻周次段应合并成 1 门", 1, r.courses.size)
        assertEquals(1, r.courses[0].startWeek)
        assertEquals(16, r.courses[0].endWeek)
    }

    /** 竖表：星期在左列，节次在首行（部分教务系统/导出文件是这种方向） */
    @Test
    fun parseTransposedTable() {
        val json = payload(
            table(
                row(cell(""), cell("1-2节"), cell("3-5节"), cell("6-7节")),
                row(cell("星期一"), cell("线性代数\n王老师\n1-16周\n教一101"), cell(""), cell("")),
                row(cell("星期二"), cell(""), cell("程序设计\n赵老师\n2-16周\n机房C302"), cell("")),
                row(cell("星期三"), cell(""), cell(""), cell("")),
                row(cell("星期四"), cell(""), cell(""), cell(""))
            )
        )
        val r = parse(json)
        println("transposed: " + dump(r))
        assertEquals("竖表应解析出 2 门课", 2, r.courses.size)
        val days = r.courses.map { it.dayOfWeek }.sorted()
        assertEquals(listOf(1, 2), days)
        val la = r.courses.first { it.dayOfWeek == 1 }
        assertEquals(1, la.startSection)
        assertEquals(2, la.endSection)
        val prog = r.courses.first { it.dayOfWeek == 2 }
        assertEquals("第二大节应映射到 3-5 节", 3, prog.startSection)
        assertEquals(5, prog.endSection)
    }

    /** 同一门课出现在两张表里（强智主表 + 备用表）→ 去重后只留一门 */
    @Test
    fun dedupeAcrossTables() {
        val text = "概率论\n陈老师\n1-16周\n教二205"
        val t1 = table(
            row(
                cell("节次"), cell("星期一"), cell("星期二"), cell("星期三"),
                cell("星期四"), cell("星期五"), cell("星期六"), cell("星期日")
            ),
            row(cell("3-5节"), cell(text, listOf(text)), cell(""), cell(""), cell(""), cell(""), cell(""), cell(""))
        )
        val t2 = table(
            row(
                cell("节次"), cell("星期一"), cell("星期二"), cell("星期三"),
                cell("星期四"), cell("星期五"), cell("星期六"), cell("星期日")
            ),
            row(cell("3-5节"), cell(text, listOf(text)), cell(""), cell(""), cell(""), cell(""), cell(""), cell(""))
        )
        val r = parse(payload(t1, t2))
        println("dedupe: " + dump(r))
        assertEquals(1, r.courses.size)
        assertEquals("概率论", r.courses[0].name)
    }

    /** 空页面/无表：不崩、给出可读 note，且识别出「有课表容器但没解析出课」 */
    @Test
    fun emptyPayloadIsGraceful() {
        val r = parse(payload())
        println("empty: " + dump(r))
        assertTrue(r.courses.isEmpty())
        assertTrue("应提示检测到课表容器", r.notes.any { it.contains("强智课表容器") })
    }

    /** 非法 JSON 不能让 App 崩：parsePayload 返回 null */
    @Test
    fun invalidJsonReturnsNull() {
        assertNull(WebScheduleParser.parsePayload("not a json"))
        assertNull(WebScheduleParser.parsePayload(""))
    }

    // ---------------------------------------------------------------- 周次/节次工具

    @Test
    fun parseWeeks_rangesAndOddEven() {
        val a = WebScheduleParser.parseWeeks("1-8,10-16周")
        assertNotNull(a)
        assertEquals(1, a!!.startWeek)
        assertEquals(16, a.endWeek)
        assertEquals(setOf(9), a.excluded)

        val b = WebScheduleParser.parseWeeks("1-16(单)")
        assertNotNull(b)
        assertEquals(Course.WEEK_ODD, b!!.weekType)

        val c = WebScheduleParser.parseWeeks("双周 3-15")
        assertNotNull(c)
        assertEquals(Course.WEEK_EVEN, c!!.weekType)

        assertNull(WebScheduleParser.parseWeeks("没有周次"))
    }

    @Test
    fun dayOfWeekOf_variants() {
        assertEquals(1, WebScheduleParser.dayOfWeekOf("星期一"))
        assertEquals(1, WebScheduleParser.dayOfWeekOf("周一"))
        assertEquals(1, WebScheduleParser.dayOfWeekOf("礼拜一"))
        assertEquals(7, WebScheduleParser.dayOfWeekOf("星期日"))
        assertEquals(7, WebScheduleParser.dayOfWeekOf("周天"))
        assertEquals(0, WebScheduleParser.dayOfWeekOf(""))
        // 长文本不是纯星期标签，避免把正文里的"周一"误当列标题
        assertEquals(0, WebScheduleParser.dayOfWeekOf("周一下午没有课请自习教室"))
    }

    @Test
    fun bigToSmall_matchesGuangwai() {
        assertEquals(IntRange(1, 2), WebScheduleParser.bigToSmall(1))
        assertEquals(IntRange(3, 5), WebScheduleParser.bigToSmall(2))
        assertEquals(IntRange(6, 7), WebScheduleParser.bigToSmall(3))
        assertEquals(IntRange(8, 9), WebScheduleParser.bigToSmall(4))
        assertEquals(IntRange(10, 11), WebScheduleParser.bigToSmall(5))
        assertEquals(IntRange(12, 13), WebScheduleParser.bigToSmall(6))
        assertEquals(IntRange(14, 15), WebScheduleParser.bigToSmall(7))
    }

    @Test
    fun cleanName_stripsNoise() {
        assertEquals("高等数学", WebScheduleParser.cleanName("课程名称：高等数学"))
        assertEquals("高等数学", WebScheduleParser.cleanName("高等数学(1-16周)"))
        assertEquals("高等数学", WebScheduleParser.cleanName(" 高等数学  "))
    }

    // ---------------------------------------------------------------- 数据层

    /** id 唯一性（AUDIT P0）：解析出来的多门课 id 必须互不相同 */
    @Test
    fun parsedCoursesHaveUniqueIds() {
        val text = "课程A\n老师\n1-16周\n教一101"
        val text2 = "课程B\n老师\n1-16周\n教一102"
        val json = payload(
            table(
                row(
                    cell("节次"), cell("星期一"), cell("星期二"), cell("星期三"),
                    cell("星期四"), cell("星期五"), cell("星期六"), cell("星期日")
                ),
                row(
                    cell("1-2节"), cell(text, listOf(text)), cell(text2, listOf(text2)),
                    cell(""), cell(""), cell(""), cell(""), cell("")
                )
            )
        )
        val r = parse(json)
        assertEquals(2, r.courses.size)
        assertEquals("id 必须唯一，否则按 id 删除/编辑会串台", 2, r.courses.map { it.id }.toSet().size)
    }

    @Test
    fun uniqueIdIsMonotonic() {
        val a = UniqueId.next()
        val b = UniqueId.next()
        val c = UniqueId.next()
        assertTrue(a < b)
        assertTrue(b < c)
    }

    /** JSON 往返：Course 序列化/反序列化不丢字段（含 excludedWeeks） */
    @Test
    fun courseJsonRoundTrip() {
        val original = listOf(
            Course(
                name = "数据结构", teacher = "刘", classroom = "教三301",
                dayOfWeek = 3, startSection = 6, endSection = 7,
                startWeek = 2, endWeek = 16, excludedWeeks = setOf(5, 9),
                code = "GU20041", startTime = "10:10", endTime = "11:45"
            ),
            Course(name = "体育", dayOfWeek = 5, startSection = 3, endSection = 5, weekType = Course.WEEK_EVEN)
        )
        val decoded = CourseJson.decode(CourseJson.encode(original))
        assertEquals(2, decoded.size)
        assertEquals(original[0].name, decoded[0].name)
        assertEquals(setOf(5, 9), decoded[0].excludedWeeks)
        assertEquals("GU20041", decoded[0].code)
        assertEquals(Course.WEEK_EVEN, decoded[1].weekType)
        // 兼容 {"courses":[...]} 包裹格式
        val wrapped = "{\"courses\":" + CourseJson.encode(original) + "}"
        assertEquals(2, CourseJson.decode(wrapped).size)
    }

    /** 损坏 JSON 必须被识别为"失败"而不是"空课表"（AUDIT P1：decode 吞异常导致留底逻辑不可达） */
    @Test
    fun corruptJsonIsDistinguishableFromEmpty() {
        assertNull("损坏的 JSON 应返回 null", CourseJson.decodeOrNull("{broken"))
        assertEquals("空字符串是合法的空课表", 0, CourseJson.decodeOrNull("")!!.size)
        assertEquals(0, CourseJson.decodeOrNull("[]")!!.size)
        assertEquals(0, CourseJson.decodeOrNull("{\"courses\":[]}")!!.size)
    }

    /**
     * 新版「智慧广外」课表是 div 卡片网格（不是 <table>），注入脚本会把它重建成合成表格
     * （cls = classgrid-synthetic）再交给解析器。这个用例锁住整条链路：
     * 周末列（周日=7）、第N大节、一格叠两门课、课程名/教师/教室三行。
     */
    @Test
    fun parseSyntheticDomGridTable() {
        val header = row(
            cell(""),
            cell("周日01"), cell("周一02"), cell("周二03"), cell("周三04"),
            cell("周四05"), cell("周五06"), cell("周六07")
        )
        val t = table(
            header,
            row(
                cell("第1大节 01,02"), cell(""), cell("高等数学(1)\n胡艳\n南G211"),
                cell(""), cell(""), cell(""), cell(""), cell("")
            ),
            row(
                cell("第2大节 03,04,05"), cell(""), cell(""),
                cell(
                    "综合英语(7)\n姚银燕\n南G409\n\n中国近现代史纲要\n陈娓\n南E205",
                    blocks = listOf(
                        "综合英语(7)\n姚银燕\n南G409",
                        "中国近现代史纲要\n陈娓\n南E205"
                    )
                ),
                cell(""), cell(""), cell(""), cell(""), cell("")
            ),
            row(
                cell("第3大节 06,07"), cell(""), cell(""), cell(""),
                cell(""), cell(""), cell(""), cell("")
            ),
            row(
                cell("第6大节 12,13"), cell(""), cell(""), cell(""),
                cell(""), cell(""), cell("体育(1)\n郑富\n南排球场1号"), cell("")
            )
        )
        val r = parse(payload(t))
        println(dump(r))
        assertEquals("应解析出 4 门课：" + dump(r), 4, r.courses.size)

        val math = r.courses.first { it.name.contains("高等数学") }
        assertEquals("课程名末尾的 (1) 要保留，不能被当噪声切掉", "高等数学(1)", math.name)
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startSection)
        assertEquals(2, math.endSection)

        val english = r.courses.first { it.name.contains("综合英语") }
        assertEquals(2, english.dayOfWeek)
        assertEquals(3, english.startSection)
        assertEquals(5, english.endSection)

        val history = r.courses.first { it.name.contains("中国近现代史") }
        assertEquals(2, history.dayOfWeek)
        assertEquals("中国近现代史纲要", history.name)

        val pe = r.courses.first { it.name.contains("体育") }
        assertEquals(5, pe.dayOfWeek)
        assertEquals(12, pe.startSection)
        assertEquals(13, pe.endSection)
    }

    // ---------------------------------------------------------------- v1.42.0：更多学校的表头形态
    // 内测案例（肇庆学院 WebVPN）：表头是「一 11/2」，左列是「第01节…第14节」。
    // 旧规则只认「周X/星期X」且左列只认「第N大节」，整张表既进不了候选、也解析不出星期和节次。

    @Test
    fun parseBareDayHeaderWithDateAndSmallSections() {
        val header = row(
            cell("节次"),
            cell("一 11/2"),
            cell("二 11/3"),
            cell("三 11/4"),
            cell("四 11/5"),
            cell("五 11/6")
        )
        val r1 = row(
            cell("第01节"),
            cell("大学体育1", blocks = listOf("大学体育1\n1-16周")),
            cell(""),
            cell(""),
            cell("高等数学1", blocks = listOf("高等数学1\n1-16周\n1号教学楼101"), rs = 2),
            cell("")
        )
        val r2 = row(
            cell("第02节"),
            cell(""),
            cell(""),
            cell(""),
            cell(""),
            cell("")
        )
        val r = parse(payload(table(header, r1, r2)))

        val math = r.courses.first { it.name.contains("高等数学") }
        assertEquals("「四 11/5」是周四", 4, math.dayOfWeek)
        assertEquals(1, math.startSection)
        assertEquals(2, math.endSection)

        val pe = r.courses.first { it.name.contains("体育") }
        assertEquals("「一 11/2」是周一", 1, pe.dayOfWeek)
        assertEquals(1, pe.startSection)
    }

    @Test
    fun parseDateOnlyHeaderFallsBackToColumnOrder() {
        val header = row(
            cell(""),
            cell("11/2"),
            cell("11/3"),
            cell("11/4"),
            cell("11/5"),
            cell("11/6")
        )
        val r1 = row(
            cell("第01节"),
            cell("高等数学1", blocks = listOf("高等数学1\n1-16周")),
            cell(""),
            cell(""),
            cell(""),
            cell("")
        )
        val r = parse(payload(table(header, r1)))
        val math = r.courses.first { it.name.contains("高等数学") }
        assertEquals("纯日期表头按列序兜底：第一列是周一", 1, math.dayOfWeek)
    }

    // ---------------------------------------------------------------- 节次标签的宽容解析
    // 真机问题（v1.32.0）：国家安全教育在页面详情里是「第七大节（14,15小节）20:00 ~ 21:20」，
    // 抓取结果却是第 1-2 节。根因是节次正则只认阿拉伯数字，中文「第七大节」直接解析失败。

    @Test
    fun sectionsFromLabel_acceptsChineseBigSection() {
        val sec = SectionTimes.default()
        assertEquals("第一大节 → 1-2 节", 1..2, WebScheduleParser.sectionsFromLabel("第一大节", sec))
        assertEquals("第七大节 → 14-15 节", 14..15, WebScheduleParser.sectionsFromLabel("第七大节", sec))
        assertEquals(
            "带括号和钟点的中文大节标签",
            14..15, WebScheduleParser.sectionsFromLabel("第七大节（14,15小节）20:00 ~ 21:20", sec)
        )
    }

    @Test
    fun sectionsFromLabel_acceptsCommaSeparatedSections() {
        val sec = SectionTimes.default()
        assertEquals("14,15小节", 14..15, WebScheduleParser.sectionsFromLabel("14,15小节", sec))
        assertEquals("第14、15节", 14..15, WebScheduleParser.sectionsFromLabel("第14、15节", sec))
        assertEquals("第7-8节", 7..8, WebScheduleParser.sectionsFromLabel("第7-8节", sec))
    }

    // ---------------------------------------------------------------- 广外电脑版（v1.42.2）

    /**
     * 真实抓包回归：广外电脑版 xsMainV 页面上有两张 9x8 表 ——
     * 一张是「课表卡片」，另一张是按周次拆分的辅助表（同一门课拆成 1-3/4-6/7-11/12-16 周多行），
     * 而且卡片单元格文本后面还跟着一份「课程详情」副本。
     * 旧版按「名称+星期+节次+周次+教师+教室」去重会保留 30 条，导入后成对重复；现在应合并成 12 门。
     */
    @Test
    fun realGdufsDesktopCaptureMergesDuplicateTables() {
        val json = java.io.File("src/test/resources/gdufs_desktop_capture.json").readText()
        val payload = WebScheduleParser.parsePayload(json)
        assertNotNull(payload)
        val result = WebScheduleParser.parse(payload!!)
        assertEquals("电脑版两张表合并后不应有重复：" + dump(result), 15, result.courses.size)
        // 同一名称 + 星期 + 节次只能出现一次
        val slots = result.courses.map { it.name + "|" + it.dayOfWeek + "|" + it.startSection + "|" + it.endSection }
        assertEquals("同一时段不应重复", slots.size, slots.toSet().size)
        // 周二 1-2 节：卡片表写 2-4,6-16 周，辅助表拆成 2-4 / 6-16，合并后第 5 周应被排除
        val mathTue = result.courses.firstOrNull {
            it.name.startsWith("高等数学") && it.dayOfWeek == 2 && it.startSection == 1
        } ?: error("missing math: " + dump(result))
        assertEquals(2, mathTue.startWeek)
        assertEquals(16, mathTue.endWeek)
        assertTrue("2-4,6-16 周的 5 周应被排除", mathTue.excludedWeeks.contains(5))
        // 周一 3-4 节的高数整学期都上，不应被合并出排除周
        val mathMon = result.courses.firstOrNull {
            it.name.startsWith("高等数学") && it.dayOfWeek == 1 && it.startSection == 3
        } ?: error("missing monday math: " + dump(result))
        assertTrue("周一 3-4 节不应有排除周", mathMon.excludedWeeks.isEmpty())
        val apec = result.courses.first { it.name.startsWith("透视 APEC") }
        assertEquals(1, apec.startWeek)
        assertEquals(11, apec.endWeek)
    }

    // ---------------------------------------------------------------- 列表展示（v1.43.0）

    /**
     * 肇庆学院这类页面：课表是 div 网格，抓不到星期表头；用户点「列表展示」后是一张普通表格
     * （一行一门课，表头含 课程名称/星期/节次/周次/教师/教室）。周表解析不出来时要走这条兜底。
     */
    @Test
    fun courseListTableIsParsedWhenWeekGridHasNoHeader() {
        val t = table(
            row(cell("课程名称"), cell("星期"), cell("节次"), cell("周次"), cell("教师"), cell("教室")),
            row(cell("高等数学1"), cell("周二"), cell("1-2节"), cell("1-16周"), cell("谭梓棋"), cell("1号教学楼101")),
            row(cell("大学体育1"), cell("周一"), cell("3-4节"), cell("1-18周(单)"), cell("梁华嘉"), cell("2号教学楼")),
            row(cell("大学英语1"), cell("周五"), cell("8-10节"), cell("2-16周"), cell("周洁"), cell("104"))
        )
        val result = parse(payload(t))
        assertEquals("列表展示应一行一门课：" + dump(result), 3, result.courses.size)
        val math = result.courses.first { it.name == "高等数学1" }
        assertEquals(2, math.dayOfWeek)
        assertEquals(1, math.startSection)
        assertEquals(2, math.endSection)
        assertEquals(1, math.startWeek)
        assertEquals(16, math.endWeek)
        assertEquals("谭梓棋", math.teacher)
        assertEquals("1号教学楼101", math.classroom)
        val pe = result.courses.first { it.name == "大学体育1" }
        assertEquals(1, pe.dayOfWeek)
        assertEquals(3, pe.startSection)
        assertEquals(4, pe.endSection)
        assertEquals(1, pe.startWeek)
        assertEquals(18, pe.endWeek)
        val english = result.courses.first { it.name == "大学英语1" }
        assertEquals(5, english.dayOfWeek)
        assertEquals(8, english.startSection)
        assertEquals(10, english.endSection)
    }
}