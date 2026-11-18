package com.classgrid.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * 链接抓取：把 WebView 侧回传的「通用表格模型」解析成 [Course] 列表。
 *
 * ===== 为什么这样设计 =====
 * 各种教务系统（正方 jwglxt、强智、URP、新版 CAS 门户）课表页 DOM 差别极大，但几乎都有一个
 * `<table>`：横向是星期、纵向是大节/小节，单元格里塞着"课程名/周次/教师/地点"。
 * 所以 WebView 侧只做**无语义的结构化提取**（HTML table → 行列网格 + rowSpan/colSpan + 每格文本），
 * 语义解析全部放在这里（纯 Kotlin，可在 JVM 上跑单元测试，见 WebScheduleParserTest）。
 * 换教务系统时只需要改这个文件，不用动 JS、不用动 Activity。
 *
 * ===== 输入（payload JSON，由 WebImportScript.INSTALL_JS 生成）=====
 * ```
 * { "url": "...", "title": "...", "bodyText": "...", "manual": true,
 *   "tables": [ { "id": "...", "cls": "...",
 *     "rows": [ { "cells": [ { "rs":1, "cs":1, "text":"课程名\n1-16周\n张三\nA101",
 *                              "blocks":["课程名\n1-16周\n张三\nA101"] } ] } ] } ] }
 * ```
 * `blocks` 是"单元格内多门课"的拆分结果（多数系统用子 div 包每门课）；缺省时退回 `text`。
 *
 * ===== 输出 =====
 * [ParseResult.courses] 直接丢给 OcrResultActivity（EXTRA_COURSES）复用它的核对/编辑/导入流程。
 *
 * 解析顺序：找星期表头行 → 左侧列求每行节次 → 逐格取课程块 → 块内字段分类（周次/节次/时间/教师/教室/代码）
 * → 兜底（无表头则尝试转置表；无节次标签则按行数套广外 7 大节/15 小节）。
 */
object WebScheduleParser {

    // ------------------------------------------------------------------ 数据模型

    /** 展开后的一个单元格（row/col 为左上角坐标，0 起） */
    data class Cell(
        val text: String,
        val blocks: List<String>,
        val row: Int,
        val col: Int,
        val rowSpan: Int,
        val colSpan: Int
    ) {
        val rowEnd: Int get() = row + rowSpan - 1
        val colEnd: Int get() = col + colSpan - 1
    }

    data class Table(
        val id: String,
        val cls: String,
        val rowCount: Int,
        val colCount: Int,
        val cells: List<Cell>
    ) {
        fun at(row: Int, col: Int): Cell? = cells.firstOrNull { it.row == row && it.col == col }
        fun cellsInRow(row: Int): List<Cell> = cells.filter { row in it.row..it.rowEnd }
        fun cellsInCol(col: Int): List<Cell> = cells.filter { col in it.col..it.colEnd }
    }

    data class Payload(
        val url: String,
        val title: String,
        val tables: List<Table>,
        val bodyText: String,
        val manual: Boolean,
        /** 页面里是否存在强智课表容器（#kbtable / .kbcontent）：用于"找到了容器但解析不出课"的诊断 */
        val hasKbTable: Boolean = false,
        /** DOM 视觉网格兜底：页面上带位置与颜色的文本块（div 渲染的课表用） */
        val blocks: List<Block> = emptyList(),
        /** 页面自己请求到的接口响应（JS 注入的 fetch/XHR 钩子采集；比读 DOM 更完整） */
        val net: List<NetDoc> = emptyList(),
        /** 注入脚本 / 页面脚本在采集过程中捕获到的错误（诊断用，界面上会提示"页面脚本报错"） */
        val errors: List<String> = emptyList()
    )

    /**
     * 页面渲染出来的可定位文本块（DOM 视觉网格兜底用）。
     * 坐标单位是 CSS px、文档坐标（rect + scrollX/Y）；[frame] 是所在同源文档序号，
     * 解析前必须按 frame 分组——不同文档的坐标不能混算。
     */
    data class Block(
        val text: String,
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val color: String = "",
        val frame: Int = 0
    )

    /**
     * 页面自己请求过的接口响应（「直接读后端课表数据」路径：不受视觉截断影响、通常带完整周次）。
     */
    data class NetDoc(val url: String, val status: Int, val body: String)

    data class ParseResult(
        val courses: List<Course>,
        val notes: List<String>
    )

    /** 广外 15 小节被分成 7 个大节；教务系统课表多以"大节"为行 */
    val BIG_SECTIONS: List<IntRange> = listOf(
        IntRange(1, 2), IntRange(3, 5), IntRange(6, 7), IntRange(8, 9),
        IntRange(10, 11), IntRange(12, 13), IntRange(14, 15)
    )

    private const val MAX_WEEK = 30
    private const val MAX_SECTION = 15

    // ------------------------------------------------------------------ 入口

    /** 容错解析 payload；返回 null 表示这不是合法 payload（不是课程表页面） */
    fun parsePayload(json: String): Payload? = try {
        val o = JSONObject(json)
        val tablesArr: JSONArray = o.optJSONArray("tables") ?: JSONArray()
        val tables = mutableListOf<Table>()
        for (i in 0 until tablesArr.length()) {
            val t = tablesArr.optJSONObject(i) ?: continue
            expandTable(t)?.let { tables.add(it) }
        }
        Payload(
            url = o.optString("url", ""),
            title = o.optString("title", ""),
            tables = tables,
            bodyText = o.optString("bodyText", ""),
            manual = o.optBoolean("manual", false),
            hasKbTable = o.optBoolean("hasKbTable", false),
            blocks = parseBlocks(o),
            net = parseNet(o),
            errors = parseErrors(o)
        )
    } catch (e: Exception) {
        null
    }

    /** 解析 JS 侧捕获的错误（最多 10 条，仅用于诊断展示） */
    private fun parseErrors(o: JSONObject): List<String> {
        val arr = o.optJSONArray("errors") ?: return emptyList()
        val out = ArrayList<String>(minOf(arr.length(), 10))
        for (i in 0 until arr.length()) {
            val e = arr.optString(i, "").trim()
            if (e.isNotEmpty()) out.add(e.take(300))
        }
        return out
    }

    /** 解析 JS 回传的 DOM 文本块（缺失/损坏时返回空表，不影响表格路径） */
    private fun parseBlocks(o: JSONObject): List<Block> {
        val arr = o.optJSONArray("blocks") ?: return emptyList()
        val out = ArrayList<Block>(minOf(arr.length(), 400))
        for (i in 0 until arr.length()) {
            val b = arr.optJSONObject(i) ?: continue
            val text = b.optString("t", "").trim()
            if (text.isEmpty()) continue
            out.add(
                Block(
                    text = text,
                    x = b.optDouble("x", 0.0).toFloat(),
                    y = b.optDouble("y", 0.0).toFloat(),
                    w = b.optDouble("w", 0.0).toFloat(),
                    h = b.optDouble("h", 0.0).toFloat(),
                    color = b.optString("c", ""),
                    frame = b.optInt("f", 0)
                )
            )
        }
        return out
    }

    /** 解析页面自己请求到的接口响应 */
    private fun parseNet(o: JSONObject): List<NetDoc> {
        val arr = o.optJSONArray("net") ?: return emptyList()
        val out = ArrayList<NetDoc>(minOf(arr.length(), 30))
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            val body = n.optString("b", "")
            if (body.isEmpty()) continue
            out.add(
                NetDoc(
                    url = n.optString("u", ""),
                    status = n.optInt("s", 0),
                    body = body
                )
            )
        }
        return out
    }

    /**
     * 主解析入口。
     * @param sections 当前用户的节次作息（默认广外），用于「表格只给钟点时间时反推小节号」。
     */
    fun parse(payload: Payload, sections: List<SectionTimes.Section> = SectionTimes.default()): ParseResult {
        val notes = mutableListOf<String>()
        val out = mutableListOf<Course>()

        for ((index, table) in payload.tables.withIndex()) {
            val courses = try {
                parseTable(table, sections, notes)
            } catch (e: Exception) {
                notes.add("表格 #$index 解析异常：" + (e.message ?: e.javaClass.simpleName))
                emptyList()
            }
            // 很多教务系统的「列表展示」不是周表，而是「一行一门课」的课程列表：
            // 表头有 课程名称 / 星期 / 节次 / 周次。周表解析不出来时按列表再试一次。
            val result = if (courses.isEmpty()) {
                try {
                    parseCourseList(table, sections, notes)
                } catch (e: Exception) {
                    notes.add("表格 #$index 列表解析异常：" + (e.message ?: e.javaClass.simpleName))
                    emptyList()
                }
            } else {
                courses
            }
            if (result.isNotEmpty()) {
                notes.add("表格 #$index（id=${table.id.ifEmpty { "-" }}，${table.rowCount}x${table.colCount}）解析出 ${result.size} 门课程")
            }
            if (index == 0) {
                out.addAll(result)
            } else {
                // 电脑版教务系统（如广外强智）除了正常的课表卡片，还有一张「按周次拆分」的辅助表：
                // 同一门课被拆成 1-3 / 4-6 / 7-11 / 12-16 周，而且补录行常被塞在最后一列
                // （看起来像周日）。这些行按「课程名 + 节次」与已有课程对齐，合并周次，而不是当成新课。
                alignAndMerge(out, result, notes)
            }
        }

        val deduped = dedupe(out)
        if (deduped.size != out.size) notes.add("合并重复课程 " + (out.size - deduped.size) + " 门")
        if (payload.tables.isEmpty()) notes.add("页面中没有找到含星期表头的表格")
        if (deduped.isEmpty() && payload.hasKbTable) {
            notes.add("页面里检测到强智课表容器（#kbtable/.kbcontent），但没有解析出课程：可能表格还在加载，或结构特殊")
        }
        return ParseResult(deduped, notes)
    }

    /**
     * 把后续表格里的课程对齐到已有课程：
     * 先按「同名 + 同节次 + 同星期」找，再按「同名 + 同节次」找唯一一条（辅助表常把补录行塞错列）。
     * 找到就合并周次，找不到才作为新课加入。
     */
    private fun alignAndMerge(out: MutableList<Course>, incoming: List<Course>, notes: MutableList<String>) {
        var mergedCount = 0
        for (c in incoming) {
            val sameSlot = out.indexOfFirst {
                it.name == c.name && it.dayOfWeek == c.dayOfWeek &&
                    it.startSection == c.startSection && it.endSection == c.endSection
            }
            val target = if (sameSlot >= 0) {
                sameSlot
            } else {
                val byNameSection = out.withIndex().filter {
                    it.value.name == c.name &&
                        it.value.startSection == c.startSection && it.value.endSection == c.endSection
                }
                if (byNameSection.size == 1) byNameSection[0].index else -1
            }
            if (target >= 0) {
                out[target] = mergeSameSlot(out[target], c)
                mergedCount++
            } else {
                out.add(c)
            }
        }
        if (mergedCount > 0) notes.add("按周次合并同名课程 $mergedCount 门")
    }

    // ------------------------------------------------------------------ 表格展开

    /** 把 rows/cells 的 rowSpan/colSpan 展开成带坐标的网格 */
    fun expandTable(json: JSONObject): Table? {
        val rowsArr = json.optJSONArray("rows") ?: return null
        if (rowsArr.length() < 2) return null

        val occupied = mutableListOf<MutableList<Boolean>>()
        fun ensureRow(r: Int) {
            while (occupied.size <= r) occupied.add(mutableListOf())
        }
        fun ensureCol(r: Int, c: Int) {
            ensureRow(r)
            val row = occupied[r]
            while (row.size <= c) row.add(false)
        }
        fun isFree(r: Int, c: Int): Boolean {
            ensureCol(r, c)
            return !occupied[r][c]
        }
        fun mark(r: Int, c: Int) {
            ensureCol(r, c)
            occupied[r][c] = true
        }

        val cells = mutableListOf<Cell>()
        var maxCol = 0
        for (r in 0 until rowsArr.length()) {
            ensureRow(r)
            val rowObj = rowsArr.optJSONObject(r) ?: continue
            val cellsArr = rowObj.optJSONArray("cells") ?: continue
            var col = 0
            for (c in 0 until cellsArr.length()) {
                val cellObj = cellsArr.optJSONObject(c) ?: continue
                // 防御异常 rowSpan/colSpan（有些系统输出 0 或超大值）
                val rs = cellObj.optInt("rs", 1).let { if (it <= 0) 1 else it.coerceAtMost(50) }
                val cs = cellObj.optInt("cs", 1).let { if (it <= 0) 1 else it.coerceAtMost(20) }
                while (col < 400 && !isFree(r, col)) col++
                val text = cellObj.optString("text", "").trim()
                val blocks = mutableListOf<String>()
                val blocksArr = cellObj.optJSONArray("blocks")
                if (blocksArr != null) {
                    for (b in 0 until blocksArr.length()) {
                        val bt = blocksArr.optString(b, "").trim()
                        if (bt.isNotEmpty()) blocks.add(bt)
                    }
                }
                if (blocks.isEmpty() && text.isNotEmpty()) blocks.add(text)
                cells.add(Cell(text = text, blocks = blocks, row = r, col = col, rowSpan = rs, colSpan = cs))
                for (rr in r until r + rs) for (cc in col until col + cs) mark(rr, cc)
                maxCol = maxOf(maxCol, col + cs)
                col += cs
            }
        }
        if (cells.isEmpty()) return null
        return Table(
            id = json.optString("id", ""),
            cls = json.optString("cls", ""),
            rowCount = maxOf(occupied.size, rowsArr.length()),
            colCount = maxCol,
            cells = cells
        )
    }

    // ------------------------------------------------------------------ 单表解析

    /**
     * 「列表展示」型表格（一行一门课）：表头有 课程名称/星期/节次/周次，数据行是课程。
     * 肇庆学院、青果、部分强智的列表视图都是这种结构，周表解析不出来时用这个兜底。
     */
    private fun parseCourseList(
        table: Table,
        sections: List<SectionTimes.Section>,
        notes: MutableList<String>
    ): List<Course> {
        if (table.rowCount < 2 || table.colCount < 3) return emptyList()
        val grid = Array(table.rowCount) { arrayOfNulls<String>(table.colCount) }
        for (cell in table.cells) {
            for (r in cell.row..minOf(cell.rowEnd, table.rowCount - 1)) {
                for (c in cell.col..minOf(cell.colEnd, table.colCount - 1)) {
                    if (r >= 0 && c >= 0) grid[r][c] = cell.text
                }
            }
        }
        fun cell(r: Int, c: Int): String = grid.getOrNull(r)?.getOrNull(c)?.trim().orEmpty()

        var headerRow = -1
        var nameCol = -1
        var dayCol = -1
        var secCol = -1
        var weekCol = -1
        var teacherCol = -1
        var roomCol = -1
        for (r in 0 until minOf(table.rowCount, 6)) {
            var n = -1; var d = -1; var s = -1; var w = -1; var t = -1; var rm = -1
            for (c in 0 until table.colCount) {
                val h = cell(r, c)
                if (h.isEmpty()) continue
                when {
                    n < 0 && (h.contains("课程名称") || h == "课程" || h.contains("课程名")) -> n = c
                    d < 0 && (h.contains("星期") || h.contains("周几")) -> d = c
                    s < 0 && (h.contains("节次") || h.contains("小节") || h.contains("上课节")) -> s = c
                    w < 0 && h.contains("周次") && !h.contains("星期") -> w = c
                    t < 0 && (h.contains("教师") || h.contains("老师") || h.contains("任课")) -> t = c
                    rm < 0 && (h.contains("教室") || h.contains("地点") || h.contains("上课地点")) -> rm = c
                }
            }
            if (n >= 0 && (d >= 0 || s >= 0 || w >= 0)) {
                headerRow = r; nameCol = n; dayCol = d; secCol = s; weekCol = w; teacherCol = t; roomCol = rm
                break
            }
        }
        if (headerRow < 0) return emptyList()

        val out = mutableListOf<Course>()
        for (r in headerRow + 1 until table.rowCount) {
            val name = cleanName(cell(r, nameCol))
            if (name.length < 2) continue
            if (name.contains("课程名称")) continue
            val dayText = if (dayCol >= 0) cell(r, dayCol) else ""
            val secText = if (secCol >= 0) cell(r, secCol) else ""
            val weekText = if (weekCol >= 0) cell(r, weekCol) else ""
            val day = dayOfWeekOf(dayText).takeIf { it != 0 }
                ?: dayOfWeekOf(secText).takeIf { it != 0 }
                ?: continue
            val span = sectionsFromLabel(secText, sections)
                ?: sectionsFromLabel(dayText, sections)
                ?: continue
            val weeks = parseWeeks("$weekText $secText $dayText")
                ?: WeeksSpec(1, 20, Course.WEEK_EVERY, emptySet())
            val teacher = if (teacherCol >= 0) cleanTeacher(cell(r, teacherCol)) else ""
            val room = if (roomCol >= 0) cleanName(cell(r, roomCol)) else ""
            out.add(
                Course(
                    id = 0L,
                    name = name,
                    teacher = teacher,
                    classroom = room,
                    dayOfWeek = day,
                    startSection = span.first,
                    endSection = span.last,
                    startWeek = weeks.startWeek,
                    endWeek = weeks.endWeek,
                    weekType = weeks.weekType,
                    colorIndex = 0,
                    excludedWeeks = weeks.excluded,
                    code = ""
                )
            )
        }
        if (out.isNotEmpty()) notes.add("按「列表展示」表解析出 ${out.size} 门课程")
        return out
    }

    private fun parseTable(
        table: Table,
        sections: List<SectionTimes.Section>,
        notes: MutableList<String>
    ): List<Course> {
        // 1) 找星期表头行（横向表头）
        var headerRow = -1
        var dayCols = emptyMap<Int, Int>()
        val headerLimit = minOf(table.rowCount, 12)
        for (r in 0 until headerLimit) {
            val map = mutableMapOf<Int, Int>()
            for (cell in table.cellsInRow(r)) {
                val day = dayOfWeekOf(cell.text)
                if (day != 0 && cell.text.length <= 16) {
                    for (c in cell.col..cell.colEnd) map[c] = day
                }
            }
            if (map.values.toSet().size >= 4) {
                headerRow = r
                dayCols = map
                break
            }
        }

        if (headerRow < 0) {
            // v1.42.0：表头整行都是日期（如「11/2 11/3 …」）时，解析不出星期，
            // 但列顺序通常就是周一到周日 —— 按列序兜底，比直接放弃靠谱。
            sequentialHeader(table)?.let { hit ->
                headerRow = hit.first
                dayCols = hit.second
            }
        }

        if (headerRow < 0) {
            // 2) 没有横向表头 → 尝试转置表（星期在左侧列、节次在表头行）
            return parseTransposed(table, sections, notes)
        }

        val minDayCol = dayCols.keys.min()
        val bodyStart = headerRow + 1
        if (bodyStart >= table.rowCount) return emptyList()

        // 3) 每行的节次区间：先由左侧列的标签算，算不出的走兜底
        val rowSections = mutableMapOf<Int, IntRange>()
        for (cell in table.cells) {
            if (cell.col >= minDayCol) continue
            val range = sectionsFromLabel(cell.text, sections) ?: continue
            for (r in cell.row until minOf(cell.rowEnd, table.rowCount - 1) + 1) rowSections[r] = range
        }
        val bodyRowCount = table.rowCount - bodyStart
        for (r in bodyStart until table.rowCount) {
            if (rowSections.containsKey(r)) continue
            rowSections[r] = fallbackSection(r - bodyStart, bodyRowCount, sections.size)
        }

        // 4) 逐格取课程块
        val courses = mutableListOf<Course>()
        for (cell in table.cells) {
            if (cell.col < minDayCol) continue
            if (cell.text.isEmpty() || cell.blocks.isEmpty()) continue
            if (cell.row < bodyStart) continue
            val day = dayCols[cell.col] ?: 0
            if (day == 0) continue
            // 跨行（rowSpan）的课程块：节次取所覆盖行的并集
            val span = rowSections[cell.row] ?: fallbackSection(cell.row - bodyStart, bodyRowCount, sections.size)
            val spanRange = IntRange(
                span.first,
                maxOf(span.last, (rowSections[cell.rowEnd] ?: span).last)
            )
            for (block in cell.blocks) {
                val course = parseBlock(block, day, spanRange, sections) ?: continue
                courses.add(course)
            }
        }
        if (courses.isEmpty()) notes.add("表头在第 ${headerRow + 1} 行，但单元格里没有解析出课程")
        return courses
    }

    /** 表头是 5 个以上日期格（没有星期字样）时，按列顺序映射成周一到周日 */
    private fun sequentialHeader(table: Table): Pair<Int, Map<Int, Int>>? {
        val limit = minOf(table.rowCount, 12)
        for (r in 0 until limit) {
            val cells = table.cellsInRow(r).filter { it.text.isNotBlank() && it.text.length <= 16 }
            val hits = cells.filter { TimetableGrid.dayOf(it.text) > 0 || TimetableGrid.isDateHeader(it.text) }
            if (hits.size < 5) continue
            val map = mutableMapOf<Int, Int>()
            hits.sortedBy { it.col }.forEachIndexed { i, c ->
                val named = TimetableGrid.dayOf(c.text)
                val day = if (named > 0) named else (i + 1).coerceAtMost(7)
                if (!map.containsValue(day)) {
                    for (cc in c.col..c.colEnd) map[cc] = day
                }
            }
            if (map.values.toSet().size >= 4) return r to map
        }
        return null
    }

    /** 转置表：星期在左列，节次在表头行 */
    private fun parseTransposed(
        table: Table,
        sections: List<SectionTimes.Section>,
        notes: MutableList<String>
    ): List<Course> {
        // 找"某一列里 ≥4 个格子是星期"的列
        var dayCol = -1
        var dayRows = mapOf<Int, Int>()
        for (c in 0 until table.colCount) {
            val map = mutableMapOf<Int, Int>()
            for (cell in table.cellsInCol(c)) {
                val day = dayOfWeekOf(cell.text)
                if (day != 0 && cell.text.length <= 16) {
                    for (r in cell.row..cell.rowEnd) map[r] = day
                }
            }
            if (map.values.toSet().size >= 4) {
                dayCol = c
                dayRows = map
                break
            }
        }
        if (dayCol < 0) {
            notes.add("没有找到星期表头（既不是横表也不是竖表）")
            return emptyList()
        }

        // 表头行（第一行里非星期列的格子）→ 列↔节次
        val colSections = mutableMapOf<Int, IntRange>()
        for (cell in table.cells) {
            if (cell.row > 2) continue
            if (cell.col == dayCol) continue
            val range = sectionsFromLabel(cell.text, sections) ?: continue
            for (c in cell.col..cell.colEnd) colSections[c] = range
        }
        val minOtherCol = table.cells.filter { it.col != dayCol }.map { it.col }.minOrNull() ?: 0
        val innerCols = colSections.keys.filter { it != dayCol }.sorted()
        for (c in innerCols.indices) {
            val col = innerCols[c]
            if (!colSections.containsKey(col)) colSections[col] = fallbackSection(c, innerCols.size, sections.size)
        }

        val courses = mutableListOf<Course>()
        for (cell in table.cells) {
            if (cell.col == dayCol) continue
            if (cell.col < minOtherCol && cell.col != dayCol) continue
            if (cell.text.isEmpty()) continue
            val day = dayRows[cell.row] ?: 0
            if (day == 0) continue
            val span = colSections[cell.col] ?: continue
            for (block in cell.blocks) {
                val course = parseBlock(block, day, span, sections) ?: continue
                courses.add(course)
            }
        }
        if (courses.isNotEmpty()) notes.add("按「转置表」解析出 " + courses.size + " 门课程")
        return courses
    }

    // ------------------------------------------------------------------ 单元格 → 课程

    // 强智 jsxsd 的单元格文本形如 "1-16(周)[01-02节]"，所以数字与「周/节」之间可能隔着括号，
    // 下面所有正则都允许中间出现 (（[【 等包裹符，且节号可能带前导零（01-02）。
    private val WEEK_TOKEN = Regex("""(\d{1,2}(?:\s*[-~—－至,，、]\s*\d{1,2})*)\s*[\(（\[【]?\s*周""")
    private val WEEK_ODD_EVEN = Regex("""单周|双周|\(单\)|（单）|\(双\)|（双）|单数周|双数周|\[单]|\[双]""")
    // 中文数字：真实页面里「第七大节」是常态而不是「第7大节」。只认阿拉伯数字会让整门课
    // 退回默认节次（v1.32.0 真机问题：国家安全教育 第七大节 14-15 被导成 1-2）。
    private const val NUM_TOKEN = """[0-9一二三四五六七八九十]{1,3}"""
    private val NUM_RE = Regex(NUM_TOKEN)
    private val SEC_RANGE_LABEL = Regex("""第?\s*($NUM_TOKEN)\s*[-~—－至]\s*($NUM_TOKEN)\s*[\)）\]】]?\s*[小]?\s*节""")
    private val SEC_BIG_LABEL = Regex("""第\s*($NUM_TOKEN)\s*大\s*节""")
    private val SEC_SINGLE_LABEL = Regex("""第?\s*($NUM_TOKEN)\s*[\)）\]】]?\s*[小]?\s*节""")
    // "14,15小节" / "第14、15节"：逗号罗列的小节号（页面详情弹窗就是这种写法）
    private val SEC_LIST_LABEL = Regex("""$NUM_TOKEN\s*[,，、]\s*$NUM_TOKEN\s*(?:[,，、]\s*$NUM_TOKEN\s*)*[小]?\s*节""")
    private val SEC_PLAIN_RANGE = Regex("""(?<![\d周])($NUM_TOKEN)\s*[-~—－]\s*($NUM_TOKEN)\s*节""")
    private val TIME_RANGE = Regex("""(\d{1,2})[:：](\d{2})\s*[-~—－至]\s*(\d{1,2})[:：](\d{2})""")
    private val TIME_SINGLE = Regex("""(\d{1,2})[:：](\d{2})""")
    private val CODE_RE = Regex("""[A-Za-z]{2,}\d{2,}""")
    private val ROOM_HINT = Regex("""[教楼室馆场]|机房|实验|中心|报告厅""")

    private fun parseBlock(
        rawBlock: String,
        day: Int,
        defaultSection: IntRange,
        sections: List<SectionTimes.Section>
    ): Course? {
        val lines = rawBlock.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        var weeks: WeeksSpec? = null
        var sectionRange: IntRange? = null
        var startTime = ""
        var endTime = ""
        var teacher = ""
        var room = ""
        var code = ""
        var name = ""

        // 单行"课程名 教师 地点 1-16周"的兜底：先摘出结构化字段，剩下的按位置归类
        if (lines.size == 1) {
            val line = lines[0]
            weeks = parseWeeks(line)
            sectionRange = sectionsFromLabel(line, sections)
            TIME_RANGE.find(line)?.let {
                startTime = hm(it.groupValues[1], it.groupValues[2])
                endTime = hm(it.groupValues[3], it.groupValues[4])
            }
            if (startTime.isEmpty()) TIME_SINGLE.find(line)?.let { startTime = hm(it.groupValues[1], it.groupValues[2]) }
            var rest = line
            listOf(WEEK_TOKEN, SEC_RANGE_LABEL, SEC_BIG_LABEL, SEC_SINGLE_LABEL, SEC_PLAIN_RANGE, TIME_RANGE, TIME_SINGLE)
                .forEach { re -> rest = re.replace(rest, " ") }
            rest = rest.replace(WEEK_ODD_EVEN, " ")
            val tokens = rest.split(Regex("""[\s,，、/|·]+""")).map { it.trim() }.filter { it.isNotEmpty() }
            for (tok in tokens) {
                when {
                    code.isEmpty() && CODE_RE.matches(tok) && tok.any { it.isDigit() } -> code = tok
                    room.isEmpty() && looksLikeRoom(tok) -> room = tok
                    teacher.isEmpty() && looksLikeTeacher(tok) -> teacher = tok
                    name.isEmpty() -> name = tok
                }
            }
            if (name.isEmpty() && tokens.isNotEmpty()) name = tokens[0]
        } else {
            // 第一行 = 课程名（多数系统如此）；也兼容"课程名称：xxx"
            name = cleanName(lines[0])
            for (line in lines.drop(1)) {
                WEEK_TOKEN.find(line)?.let { if (weeks == null) weeks = parseWeeks(line) }
                if (sectionRange == null) sectionRange = sectionsFromLabel(line, sections)
                if (startTime.isEmpty()) {
                    TIME_RANGE.find(line)?.let {
                        startTime = hm(it.groupValues[1], it.groupValues[2])
                        endTime = hm(it.groupValues[3], it.groupValues[4])
                    }
                }
                if (code.isEmpty() && CODE_RE.containsMatchIn(line)) {
                    CODE_RE.find(line)?.let { code = it.value }
                }
                when {
                    line == name -> Unit
                    TEACHER_PREFIX.containsMatchIn(line) && teacher.isEmpty() ->
                        TEACHER_PREFIX.find(line)?.let { teacher = cleanTeacher(it.groupValues[1]) }
                    looksLikeRoom(line) && !TEACHER_PREFIX.containsMatchIn(line) && room.isEmpty() -> room = line
                    looksLikeTeacher(line) && teacher.isEmpty() -> teacher = line
                    else -> Unit
                }
            }
            if (name.isEmpty()) name = cleanName(lines[0])
        }

        name = cleanName(name)
        if (name.isEmpty()) return null
        // 过滤表头/说明行被误当成课程
        if (name.length > 40) name = name.substring(0, 40)
        if (Regex("""^(星期|周次|节次|课程表|时间|上午|下午|晚上|备注)$""").matches(name)) return null

        val sec = sectionRange ?: defaultSection
        val startSection = sec.first.coerceIn(1, MAX_SECTION)
        val endSection = sec.last.coerceIn(startSection, MAX_SECTION)
        // 单元格里有钟点时间 → 用节次表反推（比行号更可靠）
        var fixedStart = startSection
        var fixedEnd = endSection
        if (startTime.isNotEmpty()) {
            val fromTime = sectionRangeFromTimes(startTime, endTime, sections)
            if (fromTime != null) {
                fixedStart = fromTime.first
                fixedEnd = fromTime.last
            }
        }

        val w = weeks ?: WeeksSpec(startWeek = 1, endWeek = 20, weekType = Course.WEEK_EVERY, excluded = emptySet())
        val finalDay = day.coerceIn(1, 7)
        return Course(
            id = 0L, // 交给调用方重新分配，避免 id 冲突
            name = name,
            teacher = teacher,
            classroom = room,
            dayOfWeek = finalDay,
            startSection = fixedStart,
            endSection = fixedEnd,
            startWeek = w.startWeek.coerceIn(1, MAX_WEEK),
            endWeek = w.endWeek.coerceIn(w.startWeek, MAX_WEEK),
            weekType = w.weekType,
            colorIndex = 0,
            excludedWeeks = w.excluded,
            code = code,
            startTime = startTime,
            endTime = endTime
        )
    }

    data class WeeksSpec(
        val startWeek: Int,
        val endWeek: Int,
        val weekType: Int,
        val excluded: Set<Int>
    )

    /** 解析"1-16周 / 1-8,10-16周 / 1-16周(单)" → 区间 + 单双周 + 排除周 */
    fun parseWeeks(text: String): WeeksSpec? {
        val matches = WEEK_TOKEN.findAll(text).toList()
        if (matches.isEmpty()) {
            // "1-16(单)" 这种"周"字被省略的：用括号里的单双周 + 数字区间
            val oddEven = WEEK_ODD_EVEN.find(text) ?: return null
            val range = Regex("""(\d{1,2})\s*[-~—－至]\s*(\d{1,2})""").find(text) ?: return null
            val a = range.groupValues[1].toIntOrNull() ?: return null
            val b = range.groupValues[2].toIntOrNull() ?: return null
            val isOdd = oddEven.value.contains("单")
            return WeeksSpec(a, b, if (isOdd) Course.WEEK_ODD else Course.WEEK_EVEN, emptySet())
        }

        val set = sortedSetOf<Int>()
        for (m in matches) {
            val expr = m.groupValues[1]
            for (part in expr.split(Regex("""[,，、]"""))) {
                val p = part.trim()
                if (p.isEmpty()) continue
                val range = Regex("""^(\d{1,2})\s*[-~—－至]\s*(\d{1,2})$""").find(p)
                if (range != null) {
                    val a = range.groupValues[1].toIntOrNull() ?: continue
                    val b = range.groupValues[2].toIntOrNull() ?: continue
                    for (w in minOf(a, b)..maxOf(a, b)) if (w in 1..MAX_WEEK) set.add(w)
                    // "1-3-5" 这种多段写法
                } else {
                    p.toIntOrNull()?.let { if (it in 1..MAX_WEEK) set.add(it) }
                }
            }
        }
        if (set.isEmpty()) return null
        val start = set.first()
        val end = set.last()
        val oddEven = WEEK_ODD_EVEN.find(text)
        val weekType = when {
            oddEven == null -> Course.WEEK_EVERY
            oddEven.value.contains("单") -> Course.WEEK_ODD
            else -> Course.WEEK_EVEN
        }
        // 非连续且没有单双周 → 用 excludedWeeks 表达（如 2-4,6-16周）
        val excluded: Set<Int> = if (weekType == Course.WEEK_EVERY && set.size != (end - start + 1)) {
            (start..end).filter { it !in set }.toSet()
        } else {
            emptySet()
        }
        return WeeksSpec(start, end, weekType, excluded)
    }

    // ------------------------------------------------------------------ 节次标签

    /** "第一大节 / 第1-2节 / 第3节 / 14,15小节 / 08:30-09:10 / 3-5" → 小节区间 */
    fun sectionsFromLabel(label: String, sections: List<SectionTimes.Section>): IntRange? {
        if (label.isBlank()) return null
        val t = label.replace("\n", " ").trim()

        SEC_RANGE_LABEL.find(t)?.let { m ->
            val a = numOf(m.groupValues[1]) ?: return@let
            val b = numOf(m.groupValues[2]) ?: return@let
            return clampRange(a, b)
        }
        SEC_BIG_LABEL.find(t)?.let { m ->
            val big = numOf(m.groupValues[1]) ?: return@let
            // v1.42.0：大节→小节按用户当前作息的节数动态换算，不再写死广外 7 大节
            return TimetableGrid.bigSection(big, sections.size)
        }
        // "14,15小节"：取所有数字的最小/最大，等价于闭区间
        SEC_LIST_LABEL.find(t)?.let { m ->
            val nums = NUM_RE.findAll(m.value).mapNotNull { numOf(it.value) }.toList()
            if (nums.isNotEmpty()) return clampRange(nums.min(), nums.max())
        }
        SEC_SINGLE_LABEL.find(t)?.let { m ->
            val a = numOf(m.groupValues[1]) ?: return@let
            return clampRange(a, a)
        }
        SEC_PLAIN_RANGE.find(t)?.let { m ->
            val a = numOf(m.groupValues[1]) ?: return@let
            val b = numOf(m.groupValues[2]) ?: return@let
            if (a in 1..MAX_SECTION && b in 1..MAX_SECTION) return clampRange(a, b)
        }
        // 纯数字行（15 行小节的表，左列就是 1/2/3…）
        t.toIntOrNull()?.let { if (it in 1..MAX_SECTION) return clampRange(it, it) }
        // 钟点时间
        TIME_RANGE.find(t)?.let { m ->
            sectionRangeFromTimes(hm(m.groupValues[1], m.groupValues[2]), hm(m.groupValues[3], m.groupValues[4]), sections)
                ?.let { return it }
        }
        return null
    }

    /** 阿拉伯数字或中文数字（一~十五）→ Int；认不出返回 null */
    private fun numOf(raw: String): Int? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        t.toIntOrNull()?.let { return it }
        val cn = "一二三四五六七八九十"
        return when {
            t.length == 1 -> cn.indexOf(t).let { if (it >= 0) it + 1 else null }
            t == "十一" -> 11
            t == "十二" -> 12
            t == "十三" -> 13
            t == "十四" -> 14
            t == "十五" -> 15
            t.length == 2 && t[0] == '十' -> cn.indexOf(t[1]).let { if (it in 0..4) 10 + it + 1 else null }
            else -> null
        }
    }

    fun bigToSmall(big: Int): IntRange = BIG_SECTIONS.getOrNull(big - 1) ?: IntRange(1, 2)

    private fun clampRange(a: Int, b: Int): IntRange {
        val lo = minOf(a, b).coerceIn(1, MAX_SECTION)
        val hi = maxOf(a, b).coerceIn(lo, MAX_SECTION)
        return IntRange(lo, hi)
    }

    /** 行号兜底：8 行以内按「大节」动态换算；否则按小节课号顺排 */
    private fun fallbackSection(index: Int, rowCount: Int, sectionCount: Int = 15): IntRange {
        if (index < 0) return IntRange(1, 2)
        return if (rowCount <= 8) {
            TimetableGrid.bigSection(index + 1, sectionCount)
        } else {
            val s = (index + 1).coerceIn(1, MAX_SECTION)
            IntRange(s, s)
        }
    }

    /** 用节次表把钟点时间反推成小节号 */
    private fun sectionRangeFromTimes(
        start: String,
        end: String,
        sections: List<SectionTimes.Section>
    ): IntRange? {
        if (sections.isEmpty()) return null
        val startMin = toMinutes(start) ?: return null
        val endMin = toMinutes(end) ?: startMin
        var first = -1
        var last = -1
        for (i in sections.indices) {
            val s = toMinutes(sections[i].start) ?: continue
            val e = toMinutes(sections[i].end) ?: continue
            // 与 [start,end] 有交集即算命中
            if (s < endMin + 1 && e > startMin - 1) {
                if (first < 0) first = i + 1
                last = i + 1
            }
        }
        if (first < 0) return null
        return clampRange(first, last)
    }

    private fun toMinutes(hmText: String): Int? {
        val m = Regex("""^(\d{1,2})[:：](\d{2})$""").find(hmText.trim()) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val mm = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || mm !in 0..59) return null
        return h * 60 + mm
    }

    private fun hm(h: String, m: String): String {
        val hh = h.toIntOrNull() ?: return ""
        val mm = m.toIntOrNull() ?: return ""
        if (hh !in 0..23 || mm !in 0..59) return ""
        return (if (hh < 10) "0$hh" else "$hh") + ":" + (if (mm < 10) "0$mm" else "$mm")
    }

    // ------------------------------------------------------------------ 文本分类

    /** 表头里的星期标签：星期/周/週/礼拜 + 一二三四五六日天 或 1-7。 */
    private val DAY_LABEL = Regex("""(?:星期|周|週|礼拜|禮拜)\s*([一二三四五六日天1-7])""")

    /** 「教师：胡艳讲师」「老师:张三」这类带前缀的教师行，否则会被 looksLikeRoom 里的「教」字误判成教室 */
    private val TEACHER_PREFIX = Regex("""^(?:教师|老师|授课教师)\s*[:：]\s*(.+)$""")

    /** 去掉教师名后面的职称后缀（强智教务常写成「胡艳讲师」「陈文瑞副教授」） */
    private fun cleanTeacher(raw: String): String {
        // 强智的单行写法是「老师:胡艳讲师;时间:2-16周[3-4节];地点:南G座(南G211)」，先截到第一个分号
        var t = raw.substringBefore(';').substringBefore('；').trim()
        t = t.replace(Regex("""(高级实验师|实验师|讲师|副教授|教授|助教)$"""), "").trim()
        return t.trimEnd('无').trim()
    }

    fun dayOfWeekOf(text: String): Int {
        val t = text.replace(" ", "").replace("\n", "").replace("\u00a0", "")
        if (t.isEmpty() || t.length > 24) return 0
        val m = DAY_LABEL.find(t)
        if (m != null) {
            // 星期标签后面不该再跟正文。「周一下午没有课请自习教室」这类备注不能当表头，
            // 否则整格课程文本会被误判成星期列，导致后面按列分课全错。
            // 只放行括号与「第N小节课」这类标注残留。
            val rest = t.substring(m.range.last + 1)
            if (rest.any { it in '\u4e00'..'\u9fa5' && it != '节' && it != '小' && it != '第' }) return 0
            return when (m.groupValues[1]) {
                "一", "1" -> 1
                "二", "2" -> 2
                "三", "3" -> 3
                "四", "4" -> 4
                "五", "5" -> 5
                "六", "6" -> 6
                "日", "天", "7" -> 7
                else -> 0
            }
        }
        // v1.42.0：大量教务系统的表头是裸日字「一」或「一 11/2」「四(11/5)」。
        // 只是「11/2」这种纯日期不能当星期（没有年份/开学日期就无法确定是周几），交给表头列顺序兜底。
        if (t.length == 1) return TimetableGrid.dayOf(t)
        if (Regex("""^[一二三四五六日天]\(?\d{1,2}[/月.\-]\d{1,2}日?\)?$""").matches(t)) {
            return TimetableGrid.dayOf(t)
        }
        if (Regex("""(?i)^(mon|tue|tues|wed|thur|thurs|fri|sat|sun)(day)?$""").matches(t)) {
            return TimetableGrid.dayOf(t)
        }
        return 0
    }

    private fun looksLikeRoom(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 24) return false
        if (WEEK_TOKEN.containsMatchIn(t)) return false
        if (ROOM_HINT.containsMatchIn(t)) return true
        // A101 / B-203 / 1-101 / 实验楼A302 这类
        return Regex("""^[A-Za-z]?\d{1,3}([-－]\d{1,4})?[A-Za-z]?$""").matches(t)
    }

    private fun looksLikeTeacher(text: String): Boolean {
        val t = text.trim()
        if (t.length !in 2..12) return false
        if (t.any { it.isDigit() }) return false
        if (WEEK_TOKEN.containsMatchIn(t) || t.contains("节")) return false
        if (ROOM_HINT.containsMatchIn(t)) return false
        // 只允许中文/间隔号/括号/空格（张老师、张三(Li)、王五 李四）
        return t.all { it.isLetter() || it == '·' || it == '(' || it == ')' || it == '（' || it == '）' || it == ' ' || it == '，' || it == ',' }
    }

    /** 清理课程名：去掉代码、括号里的周次/节次等噪声 */
    fun cleanName(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return ""
        // "课程名称：高等数学" → "高等数学"
        Regex("""^(?:课程名称|课程|名称)\s*[:：]\s*""").find(t)?.let { t = t.removePrefix(it.value) }
        // 去掉首尾的课程代码 如 "GU20041 高等数学" / "高等数学 [GU20041]"
        t = Regex("""^\s*[A-Za-z]{2,}\d{2,}\s*""").replace(t, "")
        t = Regex("""\s*[\(\[]\s*[A-Za-z]{2,}\d{2,}\s*[\)\]]\s*""").replace(t, "")
        // 去掉结尾的 "(单周)" "(1-16周)" "(第1-2节)" 之类
        t = Regex("""\s*[\(\[]\s*(?:第?\d[\d\-~—－至,，、]*\s*(?:周|节)[^\)\]]*)[\)\]]\s*$""").replace(t, "")
        // 去掉残留的行尾括号噪声
        t = Regex("""\s*[\(\[]\s*[\)\]]\s*""").replace(t, "")
        t = t.replace(Regex("""[\s\u3000]+"""), " ").trim()
        t = t.trim(' ', '　', '-', '_', '/', '\\', ',', '，', '、', ';', '；')
        // 结尾括号只在「不配对」时才当噪声去掉。
        // 教训：早先这里直接把 ')' 也放进 trim 集合，导致 "高等数学(1)" 被切成 "高等数学(1"，
        // 而广外课表里的课程名大量带 "(1)(2)" 这种序号，切坏后用户在课表上看到的是残名。
        while (t.isNotEmpty()) {
            val last = t.last()
            if (last != ')' && last != '）') break
            val open = if (last == ')') '(' else '（'
            if (t.count { it == last } > t.count { it == open }) {
                t = t.dropLast(1).trimEnd(' ', '　', '-', '_', '/', '\\')
            } else {
                break
            }
        }
        return t
    }

    // ------------------------------------------------------------------ 去重

    /**
     * 同一门课在页面上可能出现在不止一处：
     *  - 强智教务的课表卡片里，卡片文字后面还跟着一份「课程详情」副本；
     *  - 电脑版页面除了上面的卡片表，还藏着一张按「周次」拆分的辅助表（同一门课被拆成 1-3 周、4-6 周…多行）。
     * 以前用「名称+星期+节次+周次+教师+教室」去重，周次不同就当成两门课，于是导入后成对重复。
     * 现在同一个「名称 + 星期 + 节次」只保留一条，周次取并集、教师/教室/代码取先出现的非空值。
     */
    private fun dedupe(list: List<Course>): List<Course> {
        val merged = LinkedHashMap<String, Course>()
        for (c in list) {
            val key = "${c.name}|${c.dayOfWeek}|${c.startSection}|${c.endSection}"
            val prev = merged[key]
            merged[key] = if (prev == null) c else mergeSameSlot(prev, c)
        }
        return merged.values.mapIndexed { i, c -> c.copy(id = i + 1L) }
    }

    private fun mergeSameSlot(a: Course, b: Course): Course {
        val aWeeks = activeWeeks(a)
        val bWeeks = activeWeeks(b)
        val richer = { base: Course, other: Course ->
            base.copy(
                teacher = base.teacher.ifEmpty { other.teacher },
                classroom = base.classroom.ifEmpty { other.classroom },
                code = base.code.ifEmpty { other.code },
                startTime = base.startTime.ifEmpty { other.startTime },
                endTime = base.endTime.ifEmpty { other.endTime }
            )
        }
        // 周次互相包含时直接保留更宽的那条（常见的「主表 2-16 周」+「补录表 3 周」）
        if (bWeeks.isEmpty()) return a
        if (aWeeks.containsAll(bWeeks)) return richer(a, b)
        if (bWeeks.containsAll(aWeeks)) return richer(b, a)
        // 真正互补的周次（如 1-3 / 4-6 / 7-11 / 12-16）取并集，用「区间 + 排除周」表达
        val union = (aWeeks + bWeeks).sorted()
        val start = union.first()
        val end = union.last()
        val excluded = (start..end).filterNot { it in union }.toSet()
        val base = richer(if (aWeeks.size >= bWeeks.size) a else b, if (aWeeks.size >= bWeeks.size) b else a)
        return base.copy(
            startWeek = start,
            endWeek = end,
            weekType = Course.WEEK_EVERY,
            excludedWeeks = excluded
        )
    }

    /** 这门课实际会上的周（把单双周与排除周都算进去） */
    private fun activeWeeks(c: Course): Set<Int> {
        val out = mutableSetOf<Int>()
        for (week in c.startWeek..c.endWeek) {
            if (c.weekType == Course.WEEK_ODD && week % 2 == 0) continue
            if (c.weekType == Course.WEEK_EVEN && week % 2 == 1) continue
            if (week in c.excludedWeeks) continue
            out.add(week)
        }
        return out
    }
}
