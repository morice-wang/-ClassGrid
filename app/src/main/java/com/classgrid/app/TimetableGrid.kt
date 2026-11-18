package com.classgrid.app

/**
 * 通用「二维表格」课表解析（v1.42.0）。
 *
 * 为什么不复用 GridParser：GridParser 吃的是 OCR 的**文字坐标**（LineBox），靠几何聚类猜测列/行；
 * 而 Excel/CSV 与「单元格 OCR」拿到的本来就是**规整的二维网格**（第 r 行第 c 列），
 * 再退化成坐标去聚类只会把确定信息弄丢。所以这里直接按网格语义解析：
 *
 *  ① 找表头行：单元格是「周一/一/一 11/2/11-02/星期一/Mon」→ 列 ↔ 星期；
 *     表头竖着放（星期在某一列）时先转置再走同一套逻辑。
 *  ② 找左侧节次列：单元格是「第1-2节 / 1-2 / 第3大节 / 08:30」→ 行 ↔ 小节区间；
 *     大节按用户当前作息（SectionTimes）动态换算，不再写死广外 7 大节。
 *  ③ 每个内容格按空行/分隔线拆成多门课，逐段识别 名称/教师/周次/教室/课程代码。
 *  ④ 周次支持「1-16周 / 1-8,10-16周 / 2-16周(双) / 3-15单周 / 第5周」→ 区间+单双周+排除周。
 *
 * 纯 Kotlin（不依赖 Android），Excel 导入、单元格 OCR、以及后续任何"表格型课表"都走这里，单测可覆盖。
 */
object TimetableGrid {

    private val WEEK_RANGE = Regex("""(?:第\s*)?(\d{1,2})\s*[-–—~～至到]\s*(\d{1,2})\s*周""")
    private val WEEK_SINGLE = Regex("""(?:第\s*)?(\d{1,2})\s*周""")
    private val WEEK_ODD_EVEN = Regex("""(?:周)?\s*[\(（]?\s*(单|双)\s*周?\s*[\)）]?""")
    private val SECTION_RANGE = Regex("""第?\s*(\d{1,2})\s*[-–—~～至到]\s*(\d{1,2})\s*节""")
    private val SECTION_SINGLE = Regex("""第\s*(\d{1,2})\s*节""")
    private val SECTION_BARE_PAIR = Regex("""^(\d{1,2})\s*[-–—~～]\s*(\d{1,2})$""")
    private val SECTION_BARE_SINGLE = Regex("""^(\d{1,2})$""")
    /** 「01,02节」「03,04,05节」——教务系统导出 Excel 常见的节次写法（逗号列表） */
    private val SECTION_LIST = Regex("""^第?\s*((?:\d{1,2}\s*[,，、]?\s*)+)\s*节$""")
    /** 「1-18(01,02节)」：周次区间 + 括号里的节次列表，拆成两行后分别解析 */
    private val WEEK_SECTION_PAIR = Regex(
        """((?:\d{1,2}\s*(?:[-–—~～]\s*\d{1,2})?(?:\s*[,，、]\s*\d{1,2}\s*(?:[-–—~～]\s*\d{1,2})?)*))""" +
            """\s*[\(（]\s*((?:\d{1,2}\s*[,，、]?\s*)+)节\s*[\)）]"""
    )
    private val BIG_SECTION = Regex("""第?\s*([一二三四五六七八九十\d]{1,3})\s*大\s*节""")
    private val TIME_HHMM = Regex("""(\d{1,2})\s*[:：]\s*(\d{2})""")
    private val CODE_RE = Regex("""(?<![A-Za-z0-9])[A-Za-z]{2,5}\d{3,6}(?![A-Za-z0-9])""")
    private val ROOM_HINT = Regex("""[教楼室馆场厅堂]|机房|实验|报告厅|中心|基地""")
    private val ROOM_CODE = Regex("""^[A-Za-z]?\d{1,2}[-－]?\d{3,4}[A-Za-z]?$""")
    private val NAME_NOISE = Regex("""^(上课地点|地点|教室|教师|老师|周次|节次|时间|课程名称|课程表)$""")
    /** 班级/学号行，不能当课程名或教师（如「2026级软件工程3班(创新班)」「《高等数学Ⅰ1》6班」） */
    private val CLASS_HINT = Regex("""\d{4}\s*级|创新班|[)）]?\s*\d{0,2}\s*班\s*$""")

    /** 一个二维表格（行优先，宽度可不等长） */
    data class Sheet(val rows: List<List<String>>) {
        fun toText(limitRows: Int = 80): String =
            rows.take(limitRows).joinToString("\n") { row -> row.joinToString("\t") }
    }

    // ------------------------------------------------------------------ 表头/节次识别

    /** 「周一 / 一 / 星期一 / 一 11/2 / 11-02 / Mon」→ 1..7，认不出返回 0 */
    fun dayOf(text: String): Int {
        val t = text.replace(Regex("""[\s\u00a0]+"""), "")
            .replace('（', '(').replace('）', ')')
        if (t.isEmpty() || t.length > 24) return 0
        Regex("""(?:星期|周|週|礼拜|禮拜)\s*([一二三四五六日天1-7])""").find(t)?.let { m ->
            dayCharToInt(m.groupValues[1])?.let { return it }
        }
        Regex("""(?i)^(mon|tue|tues|wed|thur|thurs|fri|sat|sun)(day)?$""").find(t)?.let { m ->
            return when (m.groupValues[1].lowercase()) {
                "mon" -> 1; "tue", "tues" -> 2; "wed" -> 3; "thur", "thurs" -> 4
                "fri" -> 5; "sat" -> 6; else -> 7
            }
        }
        if (t.length == 1) dayCharToInt(t)?.let { return it }
        // 「一11/2」「一(11/2)」「一11月2日」：裸日字 + 日期，是教务系统最常见的表头写法
        // （只认汉字日字——带数字的 "11/2" 本身是日期，不能把首字符 "1" 当成周一）
        Regex("""^([一二三四五六日天])\(?\d{1,2}[/月.\-]\d{1,2}日?\)?$""").find(t)?.let { m ->
            dayCharToInt(m.groupValues[1])?.let { return it }
        }
        return 0
    }

    /** 表头宽松识别：OCR 常把「二 11/3」认成「二118」「二 11月3日」，首字是星期字就认 */
    private fun headerDayLoose(text: String): Int {
        val t = text.trim()
        if (t.isEmpty() || t.length > 6) return 0
        return when (t[0]) {
            '一' -> 1; '二' -> 2; '三' -> 3; '四' -> 4; '五' -> 5; '六' -> 6; '日', '天' -> 7
            else -> 0
        }
    }

    /** 纯日期表头（如「11/2」「11月2日」）——本身不含星期，只能按列顺序加权推断 */
    fun isDateHeader(text: String): Boolean {
        val t = text.replace(Regex("""[\s\u00a0]+"""), "")
        if (t.isEmpty() || t.length > 12) return false
        return Regex("""^\d{1,2}[/月.\-]\d{1,2}日?$""").matches(t) ||
            Regex("""^\d{1,2}[/月.\-]\d{1,2}日?周一?$""").matches(t)
    }

    /** 节次标签 → 小节区间（1-based）。时间标签用 [sections] 反推 */
    fun sectionOf(text: String, sections: List<SectionTimes.Section>): IntRange? {
        val t = text.replace(Regex("""[\s\u00a0]+"""), "")
        if (t.isEmpty() || t.length > 40) return null
        SECTION_RANGE.find(t)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@let
            val b = m.groupValues[2].toIntOrNull() ?: return@let
            return clampSection(a, b, sections.size)
        }
        BIG_SECTION.find(t)?.let { m ->
            val big = cnOrDigit(m.groupValues[1]) ?: return@let
            return bigSection(big, sections.size)
        }
        SECTION_SINGLE.find(t)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@let
            return clampSection(a, a, sections.size)
        }
        SECTION_BARE_PAIR.find(t)?.let { m ->
            val a = m.groupValues[1].toIntOrNull() ?: return@let
            val b = m.groupValues[2].toIntOrNull() ?: return@let
            if (a in 1..sections.size && b in 1..sections.size) return clampSection(a, b, sections.size)
        }
        // 「01,02节」「03,04,05节」：逗号列表 → 取最小..最大
        SECTION_LIST.find(t)?.let { m ->
            val nums = Regex("""\d{1,2}""").findAll(m.groupValues[1]).mapNotNull { it.value.toIntOrNull() }.toList()
            if (nums.isNotEmpty()) return clampSection(nums.min(), nums.max(), sections.size)
        }
        // 「08:30」「08:30-09:10」：按当前作息反推节次
        TIME_HHMM.find(t)?.let { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@let
            val min = m.groupValues[2].toIntOrNull() ?: return@let
            val target = h * 60 + min
            var best = -1
            var bestDiff = Int.MAX_VALUE
            for ((i, s) in sections.withIndex()) {
                val sm = toMinutes(s.start) ?: continue
                val em = toMinutes(s.end) ?: continue
                if (target in sm..em) return IntRange(i + 1, i + 1)
                val d = minOf(Math.abs(target - sm), Math.abs(target - em))
                if (d < bestDiff) {
                    bestDiff = d
                    best = i + 1
                }
            }
            if (best > 0 && bestDiff <= 40) return IntRange(best, best)
        }
        return null
    }

    private fun clampSection(a: Int, b: Int, count: Int): IntRange {
        val lo = a.coerceIn(1, count.coerceAtLeast(1))
        val hi = b.coerceIn(lo, count.coerceAtLeast(1))
        return IntRange(lo, hi)
    }

    /** 大节 → 小节：按当前作息节数动态分组（15 节=7 大节、12 节=6 大节…），不再写死广外 */
    fun bigSection(big: Int, sectionCount: Int): IntRange {
        val idx = (big - 1).coerceAtLeast(0)
        if (sectionCount <= 0) return IntRange(1, 2)
        // 广外（以及很多沿用 15 小节作息的高校）是「7 大节、第一大节占 3 小节」的不均匀划分，
        // 按偶数分组的通用算法会把第七大节算成 13-14，所以 15 节时保留这张表。
        if (sectionCount == 15) {
            val legacy = listOf(
                IntRange(1, 2), IntRange(3, 5), IntRange(6, 7), IntRange(8, 9),
                IntRange(10, 11), IntRange(12, 13), IntRange(14, 15)
            )
            return legacy.getOrNull(idx) ?: legacy.last()
        }
        val groups = (sectionCount + 1) / 2
        val group = idx.coerceAtMost(groups - 1)
        val start = group * 2 + 1
        val end = (start + 1).coerceAtMost(sectionCount)
        return IntRange(start.coerceAtLeast(1), end.coerceAtLeast(1))
    }

    // ------------------------------------------------------------------ 周次解析

    /** 返回 (startWeek, endWeek, weekType, excluded) */
    fun weeksOf(text: String, defaultEnd: Int = 20): WeekSpec? {
        val t = text.replace(Regex("""[\s\u00a0]+"""), "")
        // 「1-8,10-16周」「第5周」「2-16周(双)」：把「周」前面的整段数字表达式一起展开，
        // 不能逐个 "N-M周" 匹配——「1-8,10-16周」里只有最后一个数字带「周」，
        // 逐段匹配会把 1-8 整个丢掉（真实教务表里这种写法很常见）。
        val values = sortedSetOf<Int>()
        val exprRegex = Regex("""(\d{1,2}(?:\s*[-–—~～至到]\s*\d{1,2})?(?:\s*[,，、]\s*\d{1,2}(?:\s*[-–—~～至到]\s*\d{1,2})?)*)\s*周""")
        var anyExpr = false
        for (m in exprRegex.findAll(t)) {
            anyExpr = true
            expandWeekExpr(m.groupValues[1], values)
        }
        var oddOnly = false
        var evenOnly = false
        if (!anyExpr) {
            // 「1-16(单)」这种「周」字被省略的写法
            val range = Regex("""(\d{1,2})\s*[-–—~～至到]\s*(\d{1,2})""").find(t) ?: return null
            val a = range.groupValues[1].toIntOrNull() ?: return null
            val b = range.groupValues[2].toIntOrNull() ?: return null
            for (w in a..b) values.add(w)
        }
        if (values.isEmpty()) return null
        if (WEEK_ODD_EVEN.containsMatchIn(t)) {
            val m = WEEK_ODD_EVEN.find(t)!!
            if (m.groupValues[1] == "单") oddOnly = true else evenOnly = true
        }
        var start = values.min().coerceIn(1, 40)
        var end = values.max().coerceIn(start, 40)
        val weekType = when {
            oddOnly && !evenOnly -> Course.WEEK_ODD
            evenOnly && !oddOnly -> Course.WEEK_EVEN
            else -> Course.WEEK_EVERY
        }
        val excluded = mutableSetOf<Int>()
        for (w in start..end) {
            val active = when (weekType) {
                Course.WEEK_ODD -> w % 2 == 1
                Course.WEEK_EVEN -> w % 2 == 0
                else -> true
            }
            if (!active) excluded.add(w)
        }
        // 非连续周次（1-8,10-16）里被跳过的周
        if (weekType == Course.WEEK_EVERY) {
            val hasRange = values.size > 1 && (values.max() - values.min() + 1) > values.size
            if (hasRange) for (w in start..end) if (w !in values) excluded.add(w)
        }
        return WeekSpec(start, end, weekType, excluded)
    }

    /** 展开「1-8,10-16」「3-15」「5」这类周次表达式 */
    private fun expandWeekExpr(expr: String, out: MutableSet<Int>) {
        for (part in expr.split(",", "，", "、")) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val m = Regex("""(\d{1,2})\s*[-–—~～至到]\s*(\d{1,2})""").find(p)
            if (m != null) {
                val a = m.groupValues[1].toIntOrNull() ?: continue
                val b = m.groupValues[2].toIntOrNull() ?: continue
                for (w in minOf(a, b)..maxOf(a, b)) out.add(w)
            } else {
                p.toIntOrNull()?.let { out.add(it) }
            }
        }
    }

    data class WeekSpec(val startWeek: Int, val endWeek: Int, val weekType: Int, val excluded: Set<Int>)

    // ------------------------------------------------------------------ 主解析

    /**
     * 解析二维表格为课程。
     * @param sections 当前用户作息（决定节次上限与大节换算）
     * @param defaultEndWeek 单元格完全没写周次时的默认结束周（高校常见 16-18 周）
     */
    fun parse(
        rows: List<List<String>>,
        sections: List<SectionTimes.Section> = SectionTimes.default(),
        defaultEndWeek: Int = 20
    ): List<Course> {
        val clean = rows.map { row -> row.map { it.replace("\u00a0", " ").trim() } }
            .filter { row -> row.any { it.isNotEmpty() } }
        if (clean.size < 3) return emptyList()
        val parsed = parseHorizontal(clean, sections, defaultEndWeek)
        if (parsed.isNotEmpty()) return finish(parsed)
        // 竖表：星期在左列，节次在首行 → 转置后按同一套逻辑
        val maxCols = clean.maxOfOrNull { it.size } ?: 0
        if (maxCols < 3) return emptyList()
        val transposed = (0 until maxCols).map { c ->
            clean.map { row -> row.getOrElse(c) { "" } }
        }
        return finish(parseHorizontal(transposed, sections, defaultEndWeek))
    }

    fun parse(sheet: Sheet, sections: List<SectionTimes.Section> = SectionTimes.default(), defaultEndWeek: Int = 20): List<Course> =
        parse(sheet.rows, sections, defaultEndWeek)

    private fun parseHorizontal(
        rows: List<List<String>>,
        sections: List<SectionTimes.Section>,
        defaultEndWeek: Int
    ): List<Course> {
        // ① 表头行 → 列 ↔ 星期
        var headerRow = -1
        var dayCols = mapOf<Int, Int>()
        val headerLimit = minOf(rows.size, 8)
        for (r in 0 until headerLimit) {
            val map = linkedMapOf<Int, Int>()
            for ((c, cell) in rows[r].withIndex()) {
                val d = dayOf(cell)
                if (d > 0) map[c] = d
            }
            if (map.values.toSet().size >= 4) {
                headerRow = r
                dayCols = map
                break
            }
            // OCR 把「二 11/3」认成「二118」这类：表头行里首字是「一…日」且内容很短时仍按星期算
            if (map.size >= 3) {
                val relaxed = linkedMapOf<Int, Int>()
                var loose = 0
                for ((c, cell) in rows[r].withIndex()) {
                    val d = map[c] ?: headerDayLoose(cell)
                    if (d > 0) {
                        relaxed[c] = d
                        if (!map.containsKey(c)) loose++
                    }
                }
                if (relaxed.values.toSet().size >= 4 && loose >= 1) {
                    headerRow = r
                    dayCols = relaxed
                    break
                }
            }
        }
        // 表头部分列只有日期（「一 11/2」的「一」没认出来）：用相邻已识别列推星期，
        // 课表列一定是按周一…周日连续排列的，推出来的 1..7 才可信。
        if (headerRow >= 0) {
            val known = dayCols.keys.sorted()
            val filled = LinkedHashMap(dayCols)
            for (c in rows[headerRow].indices) {
                if (c in filled) continue
                if (!isDateHeader(rows[headerRow][c])) continue
                val near = known.minByOrNull { kotlin.math.abs(it - c) } ?: continue
                val guess = dayCols[near]!! + (c - near)
                if (guess in 1..7) filled[c] = guess
            }
            dayCols = filled
        }
        // 表头只有日期（一 11/2 的日期被拆到另一行）：按列顺序补星期，7 列=周一到周日
        if (headerRow < 0) {
            for (r in 0 until headerLimit) {
                val dateCols = rows[r].withIndex().filter { isDateHeader(it.value) }.map { it.index }
                if (dateCols.size >= 5) {
                    headerRow = r
                    dayCols = dateCols.withIndex().associate { (i, c) -> c to (i + 1).coerceAtMost(7) }
                    break
                }
            }
        }
        if (headerRow < 0 || dayCols.size < 4) return emptyList()

        val minDayCol = dayCols.keys.min()
        val dayColSet = dayCols.keys
        // ② 左侧节次标签 → 行区间
        val rowSections = mutableMapOf<Int, IntRange>()
        for (r in (headerRow + 1) until rows.size) {
            for (c in 0 until minDayCol.coerceAtLeast(1)) {
                val text = rows[r].getOrElse(c) { "" }
                val sec = sectionOf(text, sections) ?: continue
                rowSections[r] = sec
                break
            }
        }
        val bodyRows = (headerRow + 1 until rows.size).toList()
        // 没有节次标签行（部分系统只给时间或干脆没有）：按行序两节一行兜底
        if (rowSections.isEmpty()) {
            bodyRows.forEachIndexed { i, r ->
                rowSections[r] = clampSection(i * 2 + 1, i * 2 + 2, sections.size)
            }
        } else {
            var last = rowSections.values.maxOfOrNull { it.last } ?: 1
            for (r in bodyRows) {
                if (rowSections.containsKey(r)) continue
                val next = (last + 1).coerceAtMost(sections.size)
                rowSections[r] = IntRange(next, next.coerceAtLeast(next))
                last = next
            }
        }

        val out = mutableListOf<Course>()
        for (r in bodyRows) {
            val row = rows[r]
            for ((c, day) in dayCols) {
                if (c !in dayColSet) continue
                val text = row.getOrElse(c) { "" }
                if (text.isBlank()) continue
                val span = rowSections[r] ?: IntRange(1, 2)
                for (segment in splitCell(text)) {
                    val course = parseSegment(segment, day, span, sections, defaultEndWeek) ?: continue
                    out.add(course)
                }
            }
        }
        return out
    }

    /** 一个格子里可能塞了多门课：空行 / 分隔线 / 多个「周次」段落 */
    fun splitCell(text: String): List<String> {
        val rebased = text.replace("\r\n", "\n").replace('\r', '\n')
            // 教务系统导出的 Excel 常用 ★ / 制表符 / 竖线 做字段分隔（如「课程★周次(节次)★教室★班级」）
            .replace('★', '\n').replace('\t', '\n').replace('｜', '\n')
        // 「1-18(01,02节)」拆成「1-18周」+「01,02节」两行，分别交给周次/节次解析
        val norm = rebased.replace(WEEK_SECTION_PAIR) { m ->
            val weeks = m.groupValues[1].trim()
            val sections = m.groupValues[2].trim()
            "${weeks}周\n${sections}节"
        }
        val byBlank = norm.split(Regex("""\n\s*\n"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (byBlank.size > 1) return byBlank.flatMap { splitBySeparator(it) }
        // 一格多课但只用换行分隔（教务系统导出 xls 常见）：
        // 已经读完一门课的周次后，再遇到「课程名行 + 下一行是周次」就当作新的一门课开始。
        val lines = norm.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size >= 3) {
            val parts = mutableListOf<String>()
            var cur = mutableListOf<String>()
            var curHasWeek = false
            for ((i, line) in lines.withIndex()) {
                val next = lines.getOrNull(i + 1)
                val nextIsWeek = next != null && (
                    WEEK_RANGE.containsMatchIn(next) || WEEK_SINGLE.containsMatchIn(next) ||
                        Regex("""^\d{1,2}(?:\s*[-–—~～,，]\s*\d{1,2})+\s*周""").containsMatchIn(next)
                    )
                val isMeta = weeksOf(line) != null ||
                    sectionOf(line, SectionTimes.default()) != null ||
                    looksLikeRoom(line) || CLASS_HINT.containsMatchIn(line)
                if (cur.isNotEmpty() && curHasWeek && nextIsWeek && !isMeta) {
                    parts.add(cur.joinToString("\n"))
                    cur = mutableListOf()
                    curHasWeek = false
                }
                cur.add(line)
                if (weeksOf(line) != null) curHasWeek = true
            }
            if (cur.isNotEmpty()) parts.add(cur.joinToString("\n"))
            if (parts.size > 1) return parts
        }
        return splitBySeparator(norm)
    }

    private fun splitBySeparator(text: String): List<String> {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        // 出现「—— / ==== / ***」分隔线时切开
        val parts = mutableListOf<MutableList<String>>()
        var cur = mutableListOf<String>()
        for (line in lines) {
            if (Regex("""^[-=_*—]{3,}$""").matches(line)) {
                if (cur.isNotEmpty()) parts.add(cur)
                cur = mutableListOf()
            } else {
                cur.add(line)
            }
        }
        if (cur.isNotEmpty()) parts.add(cur)
        if (parts.size <= 1) return listOf(lines.joinToString("\n"))
        return parts.map { it.joinToString("\n") }
    }

    /** 单门课的文本段 → Course */
    fun parseSegment(
        segment: String,
        day: Int,
        defaultSpan: IntRange,
        sections: List<SectionTimes.Section>,
        defaultEndWeek: Int
    ): Course? {
        var lines = segment.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        // 有些教务系统导出的 Excel 把「课程名 周次 教师 教室」连成一行：
        // 单行且含周次/教室特征时，按空白再切一层（名字里有空格的多为英文课名，可接受被切）。
        if (lines.size == 1) {
            val single = lines[0]
            val hasWeek = weeksOf(single, defaultEndWeek) != null
            val hasRoom = ROOM_HINT.containsMatchIn(single)
            if (hasWeek || hasRoom) {
                val toks = single.split(Regex("""\s+""")).map { it.trim() }.filter { it.isNotEmpty() }
                if (toks.size > 1) lines = toks
            }
        }
        var weeks: WeekSpec? = null
        var span: IntRange? = null
        var code = ""
        var teacher = ""
        var room = ""
        var name = ""
        val extras = mutableListOf<String>()
        for (line in lines) {
            if (weeks == null) weeksOf(line, defaultEndWeek)?.let { weeks = it }
            // 「1-8周 / 1-16周(单)」是周次行，里面的 1-8 不能当节次（否则课程会占满第 1-8 节）；
            // 格内只有「第7节 / 7-8小节 / 10:10」这种带「节」或时间的写法才采信，
            // 裸数字区间多半是 OCR 掉了「周」的周次，交给行标签（第07节）决定节次。
            if (span == null &&
                !line.contains("周") && !line.contains("週") &&
                (line.contains("节") || line.contains("節") || line.contains(":"))
            ) {
                sectionOf(line, sections)?.let { s -> if (s.first <= sections.size) span = s }
            }
            if (code.isEmpty()) CODE_RE.find(line)?.let { code = it.value }
            val isWeekLine = weeksOf(line, defaultEndWeek) != null
            val isSectionLine = sectionOf(line, sections) != null
            when {
                isWeekLine || isSectionLine || NAME_NOISE.matches(line) -> Unit
                CLASS_HINT.containsMatchIn(line) -> extras.add(line)
                name.isEmpty() && line.length <= 40 && !looksLikeRoom(line) && !isTeacherName(line) -> name = cleanName(line)
                name.isEmpty() && looksLikeRoom(line) -> room = line
                name.isEmpty() -> name = cleanName(line)
                room.isEmpty() && looksLikeRoom(line) -> room = line
                teacher.isEmpty() && isTeacherName(line) && !CLASS_HINT.containsMatchIn(line) -> teacher = cleanName(line)
                else -> extras.add(line)
            }
        }
        if (name.isEmpty()) {
            // 全部行都被判成教师/教室时，兜底取第一行当课程名
            name = cleanName(lines.firstOrNull().orEmpty())
        }
        name = cleanName(name)
        if (name.isEmpty() || name.length < 2) return null
        if (NAME_NOISE.matches(name)) return null
        if (room.isEmpty()) {
            room = extras.firstOrNull { looksLikeRoom(it) }.orEmpty()
        }
        if (teacher.isEmpty()) {
            teacher = extras.firstOrNull { isTeacherName(it) }.orEmpty()
        }
        val sec = span ?: defaultSpan
        val w = weeks ?: WeekSpec(1, defaultEndWeek.coerceAtLeast(16), Course.WEEK_EVERY, emptySet())
        return Course(
            id = UniqueId.next(),
            name = name,
            teacher = cleanName(teacher),
            classroom = cleanRoom(room),
            dayOfWeek = day.coerceIn(1, 7),
            startSection = sec.first.coerceIn(1, 15),
            endSection = sec.last.coerceIn(sec.first.coerceIn(1, 15), 15),
            startWeek = w.startWeek.coerceIn(1, 40),
            endWeek = w.endWeek.coerceIn(w.startWeek, 40),
            weekType = w.weekType,
            colorIndex = 0,
            excludedWeeks = w.excluded,
            code = code
        )
    }

    private fun finish(courses: List<Course>): List<Course> {
        if (courses.isEmpty()) return courses
        val merged = linkedMapOf<String, Course>()
        for (c in courses) {
            val key = "${c.name}|${c.dayOfWeek}|${c.startSection}|${c.endSection}|${c.startWeek}|${c.endWeek}|${c.weekType}"
            val old = merged[key]
            merged[key] = if (old == null) c else old.copy(
                teacher = old.teacher.ifEmpty { c.teacher },
                classroom = old.classroom.ifEmpty { c.classroom },
                code = old.code.ifEmpty { c.code },
                excludedWeeks = old.excludedWeeks.ifEmpty { c.excludedWeeks }
            )
        }
        return mergeWeeksAcrossRows(mergeAdjacent(merged.values.toList()))
            .sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }, { it.name }))
    }

    /**
     * 同一门课在同一格被拆成「17周(08,09节)」和「7-16周(08,09,10节)」两条时（教务系统按周次分段写），
     * 节次区间有交集就把周次取并集、节次取并集，避免课表里出现两条几乎一样的课。
     */
    private fun mergeWeeksAcrossRows(courses: List<Course>): List<Course> {
        if (courses.size < 2) return courses
        val out = mutableListOf<Course>()
        for (c in courses.sortedWith(compareBy({ it.dayOfWeek }, { it.name }, { it.startSection }))) {
            val idx = out.indexOfFirst { p ->
                p.name == c.name && p.dayOfWeek == c.dayOfWeek &&
                    p.startSection <= c.endSection && c.startSection <= p.endSection &&
                    (p.teacher == c.teacher || p.teacher.isEmpty() || c.teacher.isEmpty()) &&
                    (p.classroom == c.classroom || p.classroom.isEmpty() || c.classroom.isEmpty())
            }
            if (idx < 0) {
                out.add(c)
            } else {
                val p = out[idx]
                val weeks = (p.startWeek..p.endWeek).toSet() + (c.startWeek..c.endWeek).toSet()
                val lo = weeks.min()
                val hi = weeks.max()
                val excluded = (lo..hi).toSet() - weeks
                out[idx] = p.copy(
                    startSection = minOf(p.startSection, c.startSection),
                    endSection = maxOf(p.endSection, c.endSection),
                    startWeek = lo,
                    endWeek = hi,
                    weekType = Course.WEEK_EVERY,
                    excludedWeeks = excluded,
                    teacher = p.teacher.ifEmpty { c.teacher },
                    classroom = p.classroom.ifEmpty { c.classroom }
                )
            }
        }
        return out
    }

    /**
     * 合并单元格跨两节时，同一门课会同时出现在两个节次行里：把节次相邻或重叠的
     * 同名课程并成一门（节次取并集），避免课表里出现两门一模一样的课。
     */
    private fun mergeAdjacent(courses: List<Course>): List<Course> {
        if (courses.size < 2) return courses
        val sorted = courses.sortedWith(compareBy({ it.dayOfWeek }, { it.startSection }, { it.name }))
        val out = mutableListOf<Course>()
        for (c in sorted) {
            val prev = out.lastOrNull()
            val sameLesson = prev != null &&
                prev.name == c.name &&
                prev.dayOfWeek == c.dayOfWeek &&
                prev.teacher == c.teacher &&
                prev.classroom == c.classroom &&
                prev.startWeek == c.startWeek &&
                prev.endWeek == c.endWeek &&
                prev.weekType == c.weekType
            if (sameLesson && c.startSection <= prev!!.endSection + 1) {
                if (c.endSection > prev.endSection) {
                    out[out.size - 1] = prev.copy(endSection = c.endSection)
                }
            } else {
                out.add(c)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 文本工具

    fun cleanName(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return t
        CODE_RE.find(t)?.let { if (it.range.first <= 2 || it.range.last >= t.length - 2) t = t.replace(it.value, "") }
        t = t.replace(Regex("""^[《<\[\(]+|[》>\]\)]+$"""), "")
        t = t.replace(Regex("""\s*[（(]\s*(?:第?\d[\d\-~—－至,，、]*\s*(?:周|节)[^）)]*)[）)]\s*$"""), "")
        t = t.replace(Regex("""[★☆*]+$"""), "")
        t = t.replace(Regex("""[\s\u3000]+"""), " ").trim()
        t = t.trim(' ', '　', '-', '_', '/', '\\', ',', '，', '、', ';', '；')
        return t
    }

    fun cleanRoom(raw: String): String {
        var t = raw.trim().trimEnd(';', '；')
        t = t.replace(Regex("""^(?:上课地点|地点|教室)\s*[:：]\s*"""), "")
        return t.trim()
    }

    fun looksLikeRoom(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || t.length > 28) return false
        if (weeksOf(t) != null) return false
        if (ROOM_HINT.containsMatchIn(t)) return true
        if (ROOM_CODE.matches(t)) return true
        return Regex("""^[A-Za-z]{1,3}\d{2,4}$""").matches(t)
    }

    fun isTeacherName(text: String): Boolean {
        val t = text.trim()
        if (t.length !in 2..12) return false
        if (t.any { it.isDigit() }) return false
        if (weeksOf(t) != null || t.contains("节")) return false
        if (ROOM_HINT.containsMatchIn(t)) return false
        if (t.endsWith("课") || t.endsWith("论") || t.endsWith("学")) return false
        return t.all { it in '\u4e00'..'\u9fa5' || it == '·' || it == ' ' }
    }

    private fun dayCharToInt(c: String): Int? = when (c) {
        "一", "1" -> 1; "二", "2" -> 2; "三", "3" -> 3; "四", "4" -> 4
        "五", "5" -> 5; "六", "6" -> 6; "日", "天", "7" -> 7
        else -> null
    }

    private fun cnOrDigit(c: String): Int? {
        c.toIntOrNull()?.let { return it }
        return when (c) {
            "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5
            "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
            "十一" -> 11; "十二" -> 12
            else -> null
        }
    }

    private fun toMinutes(hhmm: String): Int? {
        val m = Regex("""^(\d{1,2}):(\d{2})$""").find(hhmm.trim()) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }
}
