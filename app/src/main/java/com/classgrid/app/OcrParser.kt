package com.classgrid.app

/**
 * OCR 识别结果解析器（v1.8.0 强化版）。
 *
 * 设计目标：在真实手机字体 / 截图字体千差万别、ML Kit 输出不稳定（边界噪声、繁简混排、
 * 丢字、换行拆断、字段值污染）的情况下，尽量把"能识别出来的信息"提炼成一门可编辑课程，
 * 缺的字段留默认，交由核对页让用户补全。
 *
 * 解析流水线：
 *   1) [normalize] 归一化清洗层（解析前统一处理脏文本）
 *   2) 多级解析策略（任一级成功即采用，各级字段级最优合并）
 *      - L1 [parseFullText]：全文提取（对教务系统弹窗截图最鲁棒，保留并强化）
 *      - L2 [parseTaggedLines]：按行模糊键值对解析 + 续行合并 + 字段值截断
 *      - L3 [parseFieldLevel]：字段级兜底（不依赖标签的全局正则）
 *   3) 字段合并与合理性校验（day 1~7、节次 1~15、周次 1~25、时间 HH:MM；不合理即丢弃）
 *   4) 部分成功也算成功（有课程名，或有教师+任一时间信息 → 生成课程，缺省字段给默认）
 *
 * 典型截图文本（干净）：
 *   学术英语（1）
 *   课程代码： GW50021
 *   授课教师： 王润梅
 *   上课地点： 南C206
 *   上课周次： 3-16周
 *   上课时间： 周一，第三大节（06,07小节）12:30 ~ 13:50
 *   上课班级： 审计学2601班
 */
object OcrParser {

    // ====================================================================
    // 基础映射与正则
    // ====================================================================

    private val dayMap = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4,
        "五" to 5, "六" to 6, "日" to 7, "天" to 7
    )

    private val cnNumMap = mapOf(
        "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
        "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10
    )

    /** 星期（含"周X"/"星期X"） */
    private val dayRegex = Regex("""(?:星期|周)\s*([一二三四五六日天])""")
    /** 大节："第X大[节]?"，允许丢"节"字（第二大 / 第3大节 都匹配） */
    private val bigSectionRegex = Regex("""第\s*([一二三四五六七\d]+)\s*大[节]?""")
    /** 小节：括号内多数字列表（如「03.04,小节」「09,10,11小节」），分隔符支持 . , ， 、 与空白，允许前导 0 */
    private val smallSectionRegex = Regex(
        """[（(]\s*(\d{1,2}(?:\s*[,.，、]\s*\d{1,2})*)\s*[,，、.)\s]*\s*小?节?"""
    )
    /** 节次区间（如「3-4节」「06-07节」） */
    private val sectionRangeRegex = Regex("""第?\s*(\d{1,2})\s*[-–—~～至到]\s*(\d{1,2})\s*节""")
    /** 周次段：允许「N-M」或「N-M周」，周后缀可选（多段写法用 findAll 收集所有段） */
    private val weekRangeRegex = Regex("""(\d{1,2})\s*[-–—~～至到]\s*(\d{1,2})\s*周?""")
    /** 单周（如「第3周」「16周」） */
    private val singleWeekRegex = Regex("""第?\s*(\d{1,2})\s*周""")
    /** 时钟时间区间（如「10:10~11:30」「12:30 - 13:50」） */
    private val timeRangeRegex = Regex(
        """(\d{1,2})[:：](\d{2})\s*[~～\-—]\s*(\d{1,2})[:：](\d{2})"""
    )
    /** 单节形式（如「第8节」「第10节」），相邻多个时取 min~max（日期选择器场景） */
    private val singleSectionRegex = Regex("""第\s*(\d{1,2})\s*节""")

    /** 常见 App 界面噪声词：状态栏/页面标题/按钮，任何情况下都不应作为课程名或标题 */
    private val UI_NOISE = setOf(
        "上课中", "标准版", "课程表", "无课表课程", "知道了", "取消", "保存",
        "导入课表", "识别完成", "编辑课程", "回到本周", "课表", "日程", "设置",
        "今天没有课", "这一天没有课", "截图识别", "手动添加", "导入JSON"
    )

    /**
     * 模糊标签：允许繁简混、错字、多字/少字。
     * 用于两处：① 全文提取的字段定位与值截断（修问题 C）② 逐行键值对解析（L2）。
     * 顺序无关，调用方按"出现位置最早"去重。
     */
    /**
     * 标签引擎（系统化方案）：
     * 不再逐个枚举 OCR 错字变体，而是维护「标准标签」，对候选文本做
     * 繁简归一化 + 编辑距离(≤2)匹配——任意 1~2 个字错认/漏字/多字都能命中。
     * 发现新错字无需再改代码。
     */
    private enum class Field { CODE, TEACHER, ROOM, WEEKS, TIME, CLASS }

    private val STANDARD_LABELS: List<Pair<Field, List<String>>> = listOf(
        Field.CODE to listOf("课程代码", "课程编码", "代码"),
        Field.TEACHER to listOf("授课教师", "任课教师", "授课老师", "上课老师", "教师", "老师"),
        Field.ROOM to listOf("上课地点", "上课位置", "地点", "教室", "上课地址"),
        Field.WEEKS to listOf("上课周次", "周次"),
        Field.TIME to listOf("上课时间", "时间"),
        Field.CLASS to listOf("上课班级", "班级")
    )

    /** 常见繁体字 → 简体（标签匹配前归一化，繁简混排不再算错字） */
    private val TRAD_SIMP = mapOf(
        '課' to '课', '師' to '师', '數' to '数', '編' to '编', '碼' to '码',
        '點' to '点', '週' to '周', '間' to '间', '時' to '时', '級' to '级',
        '錯' to '错', '題' to '题', '節' to '节', '歷' to '历', '簡' to '简', '體' to '体'
    )

    private fun toSimplified(s: String): String =
        s.map { TRAD_SIMP[it] ?: it }.joinToString("")

    /** 编辑距离（Levenshtein），小字符串专用 */
    private fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        val prev = IntArray(n + 1) { it }
        val cur = IntArray(n + 1)
        for (i in 1..m) {
            cur[0] = i
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            for (j in 0..n) prev[j] = cur[j]
        }
        return prev[n]
    }

    /** 标签命中：field, start, end, dist（已按位置排序；同位置取距离最近） */
    private data class LabelHit(
        val field: Field, val start: Int, val end: Int, val dist: Int,
        /** 首尾锚定：候选与标签首尾字符一致（完整标签变体才有；"授投课教"这种半截伪命中没有） */
        val endsMatch: Boolean
    )

    /** 在已归一化文本中扫描全部标签命中（2~6 字窗口） */
    private fun findLabels(simplified: String): List<LabelHit> {
        val hits = mutableListOf<LabelHit>()
        for (i in simplified.indices) {
            val maxLen = minOf(6, simplified.length - i)
            for (len in 2..maxLen) {
                val cand = simplified.substring(i, i + len)
                for ((field, labels) in STANDARD_LABELS) {
                    for (label in labels) {
                        // 长度差允许 ±2（覆盖「授课教师」被 OCR 认成 6 字变体，如"授投课教數师"）
                        if (label.length !in len - 2..len + 2) continue
                        val d = editDistance(cand, label)
                        // 候选比标签短且差 2 字以上：多半是普通词前缀（如"授课技能"），不当作标签
                        if (cand.length < label.length && d >= 2) continue
                        val threshold = when {
                            label.length <= 2 -> 1
                            label.length <= 3 -> 2
                            else -> 3
                        }
                        // 4 字以上标签要求首尾锚定才可放宽到距离 3；近距命中（≤2）不需要
                        val endsMatch = label.length >= 4 &&
                            cand.first() == label.first() && cand.last() == label.last()
                        val anchorOk = d <= 2 || endsMatch
                        if (d <= threshold && anchorOk) {
                            hits.add(LabelHit(field, i, i + len, d, endsMatch))
                            break
                        }
                    }
                }
            }
        }
        // 同一位置只保留一个最优命中：
        // ① 首尾锚定的完整标签 > 半截伪命中（"授投课教數师"赢过内部"授投课教"）；
        // ② 同级比距离；③ 距离相同按字段优先级（CODE<TEACHER<ROOM<…）
        val best = linkedMapOf<Int, LabelHit>()
        for (h in hits) {
            val old = best[h.start]
            val better = old == null ||
                (h.endsMatch && !old.endsMatch) ||
                (h.endsMatch == old.endsMatch && h.dist < old.dist) ||
                (h.endsMatch == old.endsMatch && h.dist == old.dist &&
                    h.field.ordinal < old.field.ordinal)
            if (better) best[h.start] = h
        }
        return best.values.sortedBy { it.start }
    }

    /** 整段文本是否本身就是某个标签（用于教师值守卫：整体=标签 → 视为空） */
    private fun isLabelOnly(s: String): Boolean {
        val simp = toSimplified(s)
        if (simp.isEmpty()) return true
        return findLabels(simp).any { it.start == 0 && it.end == simp.length }
    }

    // ====================================================================
    // 归一化清洗层（解析前统一处理）
    // ====================================================================

    /** 行首/行尾边界噪声字符（仅这些会被剥除，绝不误伤正文） */
    private val EDGE_JUNK = charArrayOf('|', 'I', '!')

    /** 字段值内部的首尾边界杂字符（修问题 4：值末尾杂字残留）。含半/全角冒号：标签残留的「：」也视为噪声一并剥离 */
    private val VALUE_BOUNDARY_JUNK = charArrayOf('|', 'l', 'I', '!', ':', '：')

    /**
     * 归一化清洗：在解析前统一处理 ML Kit 脏文本。
     * ① 移除行首/行尾的 `|` `I` `!` 杂字符（仅限行首行尾）。
     *    注意：`l` 仅在"非时间前缀"时才剥除 —— 像 `l:30`（实为 11:30 的 1 被误识为 l）必须保留，
     *    交由时间修复步骤处理；否则会丢失结束时间，故不在此处整体剥除 `l`。
     * ② 全角冒号→半角、全角逗号/顿号统一为半角逗号。
     * ③ 压缩多余空格（中间多个空白合并为一个）。
     * 保持换行结构，供 L2 逐行解析使用。
     */
    fun normalize(raw: String): String {
        return raw.lines().map { line ->
            var s = line
            s = trimEdgeJunk(s)
            s = s.replace('：', ':').replace('，', ',').replace('、', ',')
            s = Regex("""[ \t\u00a0]+""").replace(s, " ")
            s
        }.joinToString("\n")
    }

    /** 仅剥除行首/行尾的边界噪声与孤立 `l`（保留形如 `l:30` / `|l:30` 的时间前缀） */
    private fun trimEdgeJunk(line: String): String {
        var s = line
        // 行首
        while (s.isNotEmpty()) {
            val c = s[0]
            if (c in EDGE_JUNK) {
                // 时间前缀的 '|' 实为误识的数字 1（如 |l:30 = 11:30），保留交由时间修复
                if (looksLikeTimePrefix(s)) break
                s = s.substring(1)
                continue
            }
            if (c == 'l' && !looksLikeTimeStart(s)) {
                s = s.substring(1)
                continue
            }
            break
        }
        // 行尾
        while (s.isNotEmpty()) {
            val c = s.last()
            if (c in EDGE_JUNK) {
                s = s.substring(0, s.length - 1)
                continue
            }
            if (c == 'l' && !looksLikeTimeEnd(s)) {
                s = s.substring(0, s.length - 1)
                continue
            }
            break
        }
        return s
    }

    private fun looksLikeTimePrefix(s: String): Boolean =
        Regex("""^\|?[l\d][:：]?\d""").containsMatchIn(s)

    private fun looksLikeTimeStart(s: String): Boolean =
        Regex("""^l[:：]?\d""").containsMatchIn(s)

    private fun looksLikeTimeEnd(s: String): Boolean =
        Regex("""\d[:：]?l$""").containsMatchIn(s)

    /**
     * 时间上下文 OCR 易错字修复：仅用于时间片段，不影响正文。
     * l/I/| → 1，O/o → 0，全角冒号→半角。例如 `l:30` → `11:30`。
     */
    private fun repairTimeChars(s: String): String =
        s.replace('l', '1').replace('I', '1').replace('|', '1')
            .replace('O', '0').replace('o', '0').replace('：', ':')

    // ====================================================================
    // 公共入口
    // ====================================================================

    /** 解析 OCR 原文，返回 0~N 门课程（部分成功也会返回可编辑课程） */
    fun parse(rawText: String): List<Course> {
        val clean = normalize(rawText)

        val l1 = parseFullText(clean)
        val l2 = parseTaggedLines(clean)
        val l3 = parseFieldLevel(clean)

        val out = mutableListOf<Course>()
        if (l2.isNotEmpty()) {
            // 多课程（或单课程）逐行结果优先；用 L3 字段级兜底补全缺失字段
            for (p in l2) {
                mergeParsed(p, l3).build()?.let { out.add(it) }
            }
        } else {
            // 否则以 L1 为主，L3 为辅做字段级最优合并；都没有再退回 L3
            val base = l1 ?: l3
            if (base != null) {
                val other = if (base === l1) l3 else l1
                mergeParsed(base, other).build()?.let { out.add(it) }
            }
        }
        return out
    }

    /**
     * 双通道互补：结构化解析（标题定位准、背景隔离）为主，
     * 纯文本解析（标签匹配不依赖坐标/聚类）补缺失字段。
     * 专治"结构化识别出课程名与代码、但教师/地点丢失"的场景：
     * 纯文本通道通常能从全文任何位置的标签行提取到教师/地点。
     */
    fun mergeCourses(structured: List<Course>, text: List<Course>): List<Course> {
        if (structured.isEmpty()) return text
        if (text.isEmpty()) return structured
        val t = text.first()
        return structured.map { c ->
            val needFill = c.teacher.isEmpty() || c.classroom.isEmpty() ||
                c.dayOfWeek !in 1..7 || c.startSection !in 1..15 ||
                (c.startWeek == 1 && c.endWeek == 16 && (t.startWeek != 1 || t.endWeek != 16))
            if (!needFill) c else c.copy(
                teacher = c.teacher.ifEmpty { t.teacher },
                classroom = c.classroom.ifEmpty { t.classroom },
                dayOfWeek = if (c.dayOfWeek in 1..7) c.dayOfWeek else t.dayOfWeek,
                startSection = if (c.startSection in 1..15) c.startSection else t.startSection,
                endSection = if (c.endSection in 1..15) c.endSection else t.endSection,
                code = c.code.ifEmpty { t.code },
                startWeek = if (c.startWeek == 1 && c.endWeek == 16 && t.startWeek in 1..40) t.startWeek else c.startWeek,
                endWeek = if (c.endWeek == 16 && c.startWeek == 1 && t.endWeek in 1..40 && t.endWeek >= t.startWeek) t.endWeek else c.endWeek,
                startTime = c.startTime.ifEmpty { t.startTime },
                endTime = c.endTime.ifEmpty { t.endTime }
            )
        }
    }

    // ====================================================================
    // S0 结构化空间解析（v1.11.0 主方案：利用行坐标定位弹窗、排除背景干扰）
    // ====================================================================

    /** 带坐标的行信息 */
    private data class LineInfo(
        val text: String,
        val box: android.graphics.Rect,
        val field: Field?,
        val labelEnd: Int
    )

    /**
     * 基于 ML Kit 元素坐标的结构化解析。
     *
     * 纯文本解析 [parse] 完全丢失位置信息，无法区分"弹窗内课程信息"与"截图背景中的其他文字"
     * （状态栏、周视图、底部通知等）。本方法利用每行 boundingBox：
     *   1) 行级模糊标签检测（课程代码/授课教师/上课地点/上课周次/上课时间/上课班级）；
     *   2) 标签行按左边缘聚类，找出"标签列"（教务详情弹窗标签左对齐、垂直等距排列）；
     *   3) 以标签列为锚点确定弹窗 ROI，标题取标签上方最大字号行，排除背景文字；
     *   4) 字段值在标签右侧同行提取，空值向下合并紧邻续行（含缩进的时间续行）；
     *   5) 教室额外做全局"教室模式"扫描兜底（中文 + 字母 + 房号）。
     * 无法形成标签列（非弹窗截图）时返回空，由调用方回退纯文本解析。
     */
    fun parseStructured(text: com.google.mlkit.vision.text.Text): List<Course> {
        val mlLines = text.textBlocks.flatMap { it.lines }
        if (mlLines.isEmpty()) return emptyList()

        val infos = mlLines.mapNotNull { mlLine ->
            val box = mlLine.boundingBox ?: return@mapNotNull null
            val t = normalize(mlLine.text).trim()
            if (t.isEmpty()) return@mapNotNull null
            val match = matchLabelWithEnd(t)
            LineInfo(t, box, match?.first, match?.second ?: -1)
        }

        val labeled = infos.filter { it.field != null }
        // 收集所有标签列（≥2 个标签）；主列 = 标签最多的一列
        val columns = clusterLabelColumns(labeled)
        if (columns.isEmpty()) return emptyList()
        val column = columns.maxByOrNull { it.size }!!

        val pc = ParsedCourse()
        val xLeft = column.minOf { it.box.left }
        val lineH = median(column.map { it.box.height().toFloat() })

        // ---- 标题：第一个标签行上方、字号最大的非标签行（排除界面噪声词与右上角小 X） ----
        val yFirst = column.first().box.top.toFloat()
        val title = infos
            .filter {
                it.field == null &&
                    !isUINoiseLine(it.text) &&
                    it.box.bottom <= yFirst + lineH * 0.4f &&
                    it.box.top >= yFirst - lineH * 3.2f
            }
            .maxByOrNull { it.box.height() }
        if (title != null) pc.name = cleanName(title.text)

        // ---- ROI 下边界：向后续接左对齐行（考核方式等），遇"知道了"按钮或大间距停止 ----
        var yBottom = column.last().box.bottom.toFloat()
        var prevBottom = column.last().box.bottom
        for (l in infos.filter { it.box.top > prevBottom }.sortedBy { it.box.top }) {
            if (l.box.top - prevBottom > lineH * 1.6f) break
            val compact = l.text.replace(" ", "")
            if (compact == "知道了" || compact.startsWith("知道了")) break
            if (Math.abs(l.box.left - xLeft) <= lineH * 0.7f) {
                yBottom = l.box.bottom.toFloat()
                prevBottom = l.box.bottom
            } else break
        }

        val yTop = title?.box?.top?.toFloat() ?: (yFirst - lineH)
        val roi = infos.filter {
            it.box.top >= yTop - lineH * 0.6f &&
                it.box.bottom <= yBottom + lineH * 0.6f
        }

        // ---- 逐字段取值（标签右侧同行；空值合并紧邻续行） ----
        val consumed = HashSet<LineInfo>()
        for (info in column) {
            val field = info.field!!
            var value = truncateAtNextLabel(info.text.substring(info.labelEnd))
            if (isValueBlank(value)) {
                val baseBottom = info.box.bottom.toFloat()
                val cont = roi.filter {
                    it.field == null && it !in consumed &&
                        it.box.top > baseBottom - lineH * 0.35f &&
                        it.box.top < baseBottom + lineH * 0.95f
                }.minByOrNull { it.box.top }
                if (cont != null) {
                    value = cont.text
                    consumed.add(cont)
                }
            }
            assignField(pc, field, value)
        }

        // ---- 多标签列互补：主列缺失的字段用其他列的有效值补全 ----
        // （场景：截图里同时存在背景预览区的完整键值列与编辑弹窗内的空值标签列）
        for (col in columns) {
            if (col === column) continue
            val alt = ParsedCourse()
            for (info in col) {
                val v = truncateAtNextLabel(info.text.substring(info.labelEnd))
                if (!isValueBlank(v)) assignField(alt, info.field!!, v)
            }
            if (pc.code.isEmpty() && alt.code.isNotEmpty()) pc.code = alt.code
            if (pc.teacher.isEmpty() && alt.teacher.isNotEmpty()) pc.teacher = alt.teacher
            if (pc.classroom.isEmpty() && alt.classroom.isNotEmpty()) pc.classroom = alt.classroom
            if (pc.startWeek == 1 && pc.endWeek == 16 && alt.startWeek != 1 || alt.endWeek != 16) {
                if (alt.startWeek in 1..40 && alt.endWeek in 1..40 && alt.startWeek <= alt.endWeek) {
                    pc.startWeek = alt.startWeek
                    pc.endWeek = alt.endWeek
                    pc.excludedWeeks = alt.excludedWeeks
                    pc.weekType = alt.weekType
                }
            }
            if (pc.day !in 1..7 && alt.day in 1..7) pc.day = alt.day
            if (pc.startSection !in 1..15 && alt.startSection in 1..15) {
                pc.startSection = alt.startSection
                pc.endSection = alt.endSection
            }
            if (pc.startTime.isEmpty() && alt.startTime.isNotEmpty()) {
                pc.startTime = alt.startTime
                pc.endTime = alt.endTime
            }
        }

        // ---- 教师全局兜底：教师标签提取空时，在 ROI 内扫描"X教师/老师：姓名"或紧邻姓名 ----
        if (pc.teacher.isEmpty()) {
            val teacherLabelY = column.firstOrNull { it.field == Field.TEACHER }?.box?.top
            scanTeacherInRoi(roi, teacherLabelY)?.let { pc.teacher = it }
        }

        // ---- 教室全局兜底扫描 ----
        if (pc.classroom.isEmpty()) {
            val roomLabelY = column.firstOrNull { it.field == Field.ROOM }?.box?.top
            scanRoomInRoi(roi, roomLabelY)?.let { pc.classroom = it }
        }

        if (!pc.partialValid()) return emptyList()

        // ROI 内文本再走字段级解析补全缺失（星期/节次/周次多重保险）
        val l3 = parseFieldLevel(roi.joinToString("\n") { it.text })
        return listOfNotNull(mergeParsed(pc, l3).build())
    }

    private fun matchLabelWithEnd(t: String): Pair<Field, Int>? {
        val f = matchLabelAtStart(t) ?: return null
        return f to labelEnd(t, f)
    }

    private fun median(values: List<Float>): Float {
        val s = values.sorted()
        return s[s.size / 2]
    }

    /** 是否为界面噪声行（不当作课程名/标题） */
    private fun isUINoiseLine(t: String): Boolean {
        val s = t.trim()
        if (s in UI_NOISE) return true
        return UI_NOISE.any { s.startsWith(it) && it.length >= 3 }
    }

    /** 标签行按左边缘聚类，返回所有簇（每簇 ≥2 个标签、按 y 排序）；不足 2 个标签的簇丢弃 */
    private fun clusterLabelColumns(labeled: List<LineInfo>): List<List<LineInfo>> {
        if (labeled.size < 2) return emptyList()
        val sorted = labeled.sortedBy { it.box.top }
        val medH = median(sorted.map { it.box.height().toFloat() })
        val tol = medH * 0.55f
        val used = HashSet<LineInfo>()
        val columns = mutableListOf<List<LineInfo>>()
        for (anchor in sorted) {
            if (anchor in used) continue
            val members = sorted.filter { Math.abs(it.box.left - anchor.box.left) <= tol }
            if (members.size < 2) continue
            members.forEach { used.add(it) }
            columns.add(members.sortedBy { it.box.top })
        }
        return columns
    }

    /** 教师姓名模式：ROI 内"教师/老师"标签附近的 2~4 个中文字符（排除常见 UI 词） */
    private fun scanTeacherInRoi(roi: List<LineInfo>, teacherLabelY: Int?): String? {
        data class Candidate(val value: String, val dist: Int)
        val cands = mutableListOf<Candidate>()
        val nameRegex = Regex("""[\u4e00-\u9fa5]{2,4}""")
        for (line in roi) {
            if (line.field != null) continue
            // 只扫描含"教师/老师"字样的行，避免把任意中文词当教师
            if (!line.text.contains("师") && !line.text.contains("師") && !line.text.contains("數")) continue
            // 先剔除行内教师标签（编辑距离引擎容错，覆盖任意 OCR 错字变体），避免标签被当成教师姓名
            val simp = toSimplified(line.text)
            val sb = StringBuilder(line.text)
            findLabels(simp)
                .filter { it.field == Field.TEACHER }
                .sortedByDescending { it.start }
                .forEach { h -> sb.delete(h.start, h.end) }
            val textNoLabel = sb.toString().trim(':', '：', ' ', '|').trim()
            if (textNoLabel.isEmpty()) continue
            for (m in nameRegex.findAll(textNoLabel)) {
                val v = m.value
                if (isUINoiseLine(v) || v == "教师" || v == "老师" || v == "授课教师" || v == "任课教师") continue
                val dist = teacherLabelY?.let { Math.abs(line.box.top - it) } ?: line.box.top
                cands.add(Candidate(v, dist))
            }
        }
        return cands.minByOrNull { it.dist }?.value
    }

    /** 教室模式：中文(1-4)+字母+2~4位房号（南G409/南实B206）；或孤立单字母+3位数字（B206） */
    private val roomGlobalRegex = Regex(
        """[\u4e00-\u9fa5]{1,4}[A-Za-z]\d{2,4}|(?<![A-Za-z])[A-Za-z]\d{3}(?![A-Za-z\d])"""
    )
    /** 课程代码模式（2 字母 + 4~5 位数字，如 GW50117 / CJ20060），扫描教室时排除 */
    private val courseCodeRegex = Regex("""^[A-Za-z]{2}\d{4,5}$""")

    /** 在 ROI 内全局扫描教室候选，优先取靠近"上课地点"标签行的命中；命中后尽力恢复括号后缀（如「(智慧)」） */
    private fun scanRoomInRoi(
        roi: List<LineInfo>,
        roomLabelY: Int?
    ): String? {
        data class Candidate(val value: String, val dist: Int)
        val cands = mutableListOf<Candidate>()
        val bracketTail = Regex("""[\(（][^()（）]{0,8}[\)）]""")
        val suffixWords = listOf("智慧", "多媒体", "语音室", "实验室", "机房", "报告厅", "微格", "实训", "教室", "阶梯")
        for (line in roi) {
            // 非地点的标签行（代码/周次/时间/班级）整行跳过，避免 "26级A16班" 等误报
            if (line.field != null && line.field != Field.ROOM) continue
            for (m in roomGlobalRegex.findAll(line.text)) {
                val v = m.value
                if (v.contains(":") || courseCodeRegex.matches(v)) continue
                // 教室命中后，同行末尾若带括号内容或已知后缀词，拼回（OCR 常丢括号，如「南G110(智慧)」→「南G110」）
                var room = v
                val tail = line.text.substring(m.range.last)
                val bracket = bracketTail.find(tail)
                if (bracket != null) {
                    room += bracket.value
                } else {
                    suffixWords.firstOrNull { tail.contains(it) }?.let { room += "($it)" }
                }
                val dist = roomLabelY?.let { Math.abs(line.box.top - it) } ?: line.box.top
                cands.add(Candidate(room, dist))
            }
        }
        return cands.minByOrNull { it.dist }?.value
    }

    // ====================================================================
    // L1 全文提取（主方案，保留并强化）
    // ====================================================================

    /**
     * 全文提取：把识别文本去空白后，按"最早出现的字段标签"切分字段值，
     * 标签用「编辑距离引擎」匹配（任意 1~2 字 OCR 错字都能命中）、值按下一标签边界截断。
     * 对教务系统课程详情弹窗截图最鲁棒。
     */
    /** 收集所有标签命中并按字段去重（与 findLabels 同一套排序）：
     * ① 首尾锚定的完整标签 > 半截伪命中/内部碎片（"授投课教數师"赢过"教數"、"上课周次"赢过"1号上课周次"）；
     * ② 同级比距离（真实标签 d0 一定赢"授课技能"这类前缀误伤）；③ 距离相同取最早出现。 */
    private fun collectByField(simp: String): LinkedHashMap<Field, LabelHit> {
        val byField = linkedMapOf<Field, LabelHit>()
        for (h in findLabels(simp)) {
            val old = byField[h.field]
            if (old == null ||
                (h.endsMatch && !old.endsMatch) ||
                (h.endsMatch == old.endsMatch && h.dist < old.dist) ||
                (h.endsMatch == old.endsMatch && h.dist == old.dist && h.start < old.start)
            ) {
                byField[h.field] = h
            }
        }
        return byField
    }

    private fun parseFullText(clean: String): ParsedCourse? {
        val full = clean.replace(Regex("""[\s\u00a0]+"""), "")
        if (full.isEmpty()) return null
        val simp = toSimplified(full)

        val byField = collectByField(simp)
        if (byField.isEmpty()) return null

        // 锚点守卫：至少存在一个课程详情标签（教师/时间/地点/周次/代码）
        val hasAnchor = byField.keys.any {
            it == Field.TEACHER || it == Field.TIME || it == Field.ROOM ||
                it == Field.WEEKS || it == Field.CODE
        }
        if (!hasAnchor) return null

        val sorted = byField.values.sortedBy { it.start }
        val pc = ParsedCourse()

        // 课程标题：第一个标签之前的部分（弹窗标题）
        val firstStart = sorted.first().start
        pc.name = sanitizeName(full.substring(0, firstStart))

        // 各字段的值 = 本标签结束位置 到 下一个标签起始位置（实现"按下一标签边界截断"，修问题 C）
        for (i in sorted.indices) {
            val end = sorted[i].end
            val nextStart = sorted.getOrElse(i + 1) {
                LabelHit(Field.CODE, full.length, full.length, Int.MAX_VALUE, false)
            }.start
            val value = full.substring(end, nextStart)
            val field = byField.keys.first { byField[it] == sorted[i] }
            assignField(pc, field, value)
        }

        // 职称锚点兜底：教师标签没识别出来时，用「姓名+职称」结构直接提取（讲师/教授/副教授/老师…）
        if (pc.teacher.isEmpty()) {
            titleAnchorRegex.find(simp)?.let { m ->
                pc.teacher = sanitizeTeacher(m.groupValues[1])
            }
        }

        return pc
    }

    // ====================================================================
    // L2 逐行模糊键值对解析（含续行合并、字段截断）
    // ====================================================================

    private fun parseTaggedLines(clean: String): List<ParsedCourse> {
        val rawLines = clean.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val lines = mergeContinuations(rawLines)

        val result = mutableListOf<ParsedCourse>()
        var draft: ParsedCourse? = null

        fun flush() {
            draft?.let { if (it.partialValid()) result.add(it) }
            draft = null
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val field = matchLabelAtStart(line)
            if (field != null) {
                if (draft == null) draft = ParsedCourse()
                val d = draft!!
                var value = truncateAtNextLabel(line.substring(labelEnd(line, field)))
                // 值跨行拆断（修问题 2-c）：标签行冒号后为空（或仅残留冒号）时，看下一行是否「非标签行」
                // （不含已知标签模式）则作为该字段的值，避免地点等字段丢失。
                if (isValueBlank(value)) {
                    val next = lines.getOrNull(i + 1)
                    if (next != null && matchLabelAtStart(next) == null) {
                        value = next
                        i++ // 消费下一行，避免被当作标题重复处理
                    }
                }
                assignField(d, field, value)
                i++
                continue
            }

            // 无标签行：可能是课程标题
            if (looksLikeTitle(line) && (draft == null || draft!!.name.isEmpty())) {
                if (draft == null) draft = ParsedCourse()
                draft!!.name = cleanName(line)
            }
            i++
        }
        flush()
        return result
    }

    /** 在字段值内遇到下一个已知标签即截断，避免值污染（修问题 C） */
    private fun truncateAtNextLabel(value: String): String {
        val simp = toSimplified(value)
        var best = value.length
        for (h in findLabels(simp)) {
            if (h.start < best) best = h.start
        }
        return value.substring(0, best)
    }

    /**
     * 判定标签行解析出的「值」是否实质为空：去除首尾空白与残留冒号（半/全角）后仍为空。
     * 用于值跨行拆断场景——标签行可能只剩一个「：」分隔符（修问题 2-c）。
     */
    private fun isValueBlank(value: String): Boolean =
        value.trim().trimStart(':', '：').trimEnd(':', '：').isBlank()

    /** 续行合并：时间行以 ~ ～ - 结尾，或下一行是孤立的 HH:MM 时间，则把下一行拼回来 */
    private fun mergeContinuations(lines: List<String>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            var cur = lines[i]
            while (cur.trimEnd().endsWith('~') || cur.trimEnd().endsWith('～') ||
                cur.trimEnd().endsWith('-') || (i + 1 < lines.size && isLoneTime(lines[i + 1]))
            ) {
                if (i + 1 >= lines.size) break
                cur = cur + lines[i + 1]
                i++
            }
            out.add(cur)
            i++
        }
        return out
    }

    /** 孤立时间行（如 `l:30` / `11:30`）：整行就是一个时间，用于续行合并判定（避免把含时间段的完整行误判） */
    private fun isLoneTime(line: String): Boolean {
        val repaired = repairTimeChars(line).trim()
        return Regex("""^\d{1,2}[:：]\d{2}$""").containsMatchIn(repaired)
    }

    /** 行首可能残留的边界杂字符（| l I ! 与空格）；清洗层已剥行首，此处兜底，让标签仍可命中 */
    private val LEADING_JUNK_RE = Regex("""^[|lI! ]+""")

    /** 剥除行首一段边界杂字符，供标签匹配兜底（不影响标签后的正文） */
    private fun stripLeadingJunk(line: String): String = LEADING_JUNK_RE.replace(line, "")

    /** 行首是否匹配某模糊标签，返回对应字段（否则 null）。允许行首有少量边界杂字（如「|上课地点」） */
    private fun matchLabelAtStart(line: String): Field? {
        val stripped = stripLeadingJunk(line)
        val simp = toSimplified(stripped)
        val hits = findLabels(simp)
        return hits.firstOrNull { it.start == 0 }?.field
    }

    /** 返回行首匹配标签的结束位置（标签长度），含被剥除的杂字偏移，供 substring 正确切片 */
    private fun labelEnd(line: String, field: Field): Int {
        val stripped = stripLeadingJunk(line)
        val offset = line.length - stripped.length
        val simp = toSimplified(stripped)
        for (h in findLabels(simp)) {
            if (h.field == field && h.start == 0) return offset + h.end
        }
        return 0
    }

    private fun looksLikeTitle(line: String): Boolean {
        if (line.length !in 2..30) return false
        if (line.contains(':') || line.contains('：')) return false
        val excluded = listOf(
            "大节", "小节", "周", "时间", "地点", "教师", "老师", "班级",
            "代码", "代碼", "教室"
        )
        if (excluded.any { line.contains(it) }) return false
        // 常见 App 界面噪声词（状态栏/页面标题/按钮），不应被当作课程名
        if (UI_NOISE.any { line.contains(it) }) return false
        if (Regex("""\d{1,2}[:：]\d{2}""").containsMatchIn(line)) return false
        if (line.all { it.isDigit() || it in ":-：. ~～" }) return false
        return true
    }

    // ====================================================================
    // L3 字段级兜底（不依赖标签的全局正则）
    // ====================================================================

    private fun parseFieldLevel(clean: String): ParsedCourse? {
        val full = clean.replace(Regex("""[\s\u00a0]+"""), "")
        if (full.isEmpty()) return null

        val pc = ParsedCourse()

        // 课程名：第一个标题状行
        val nameLine = clean.lines().map { it.trim() }.firstOrNull {
            it.isNotEmpty() && looksLikeTitle(it)
        }
        if (nameLine != null) pc.name = cleanName(nameLine)

        // 教师：TEACHER 标签截断 + 清洗（L3 兜底路径不再丢教师；
        // 教师标签 miss 时 sanitizeTeacher 会在值内最近的非教师标签处截断，保住姓名）
        val simpFull = toSimplified(full)
        val byField = collectByField(simpFull)
        val teacherHit = byField[Field.TEACHER]
        if (teacherHit != null) {
            val nextStart = byField.values
                .filter { it.start > teacherHit.start }
                .minOfOrNull { it.start } ?: full.length
            pc.teacher = sanitizeTeacher(full.substring(teacherHit.end, nextStart))
        }

        // 星期
        dayRegex.find(full)?.let { pc.day = dayMap[it.groupValues[1]] ?: pc.day }

        // 节次与时钟时间：优先按 TIME 标签取值走 parseTimeInto（含大节/小节一致性校验，
        // "(1,1小节)" 这类 OCR 丢 0 的损坏数字不再覆盖正确大节）；
        // 标签 miss 时回退旧逻辑（小节/大节/区间/单节）
        val timeHit = byField[Field.TIME]
        if (timeHit != null) {
            val nextStart = byField.values
                .filter { it.start > timeHit.start }
                .minOfOrNull { it.start } ?: full.length
            parseTimeInto(pc, full.substring(timeHit.end, nextStart))
        } else {
            val small = smallSectionRegex.find(full)
            if (small != null) {
                val nums = small.groupValues[1].split(Regex("""[,.，、\s]+"""))
                    .mapNotNull { it.toIntOrNull() }
                val mn = nums.minOrNull()
                val mx = nums.maxOrNull()
                if (mn != null && mx != null) {
                    pc.startSection = mn
                    pc.endSection = mx
                }
            }
            if (pc.startSection <= 0) {
                val big = bigSectionRegex.findAll(full).toList()
                if (big.isNotEmpty()) {
                    var minS = Int.MAX_VALUE
                    var maxE = Int.MIN_VALUE
                    for (b in big) {
                        val n = cnToInt(b.groupValues[1])
                        if (n != null) {
                            val p = guangwaiBigSection(n)
                            minS = minOf(minS, p.first)
                            maxE = maxOf(maxE, p.second)
                        }
                    }
                    if (minS != Int.MAX_VALUE) {
                        pc.startSection = minS
                        pc.endSection = maxE
                    }
                }
            }
            if (pc.startSection <= 0) {
                sectionRangeRegex.find(full)?.let {
                    pc.startSection = it.groupValues[1].toInt()
                    pc.endSection = it.groupValues[2].toInt()
                }
            }
            // 单节形式兜底（第8节 / 第10节 相邻多节 → 取 min~max）
            if (pc.startSection <= 0) {
                val singles = singleSectionRegex.findAll(full)
                    .mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                if (singles.isNotEmpty()) {
                    pc.startSection = singles.minOrNull()!!
                    pc.endSection = singles.maxOrNull()!!
                }
            }
            timeRangeRegex.find(repairTimeChars(full))?.let {
                pc.startTime = "${it.groupValues[1]}:${it.groupValues[2]}"
                pc.endTime = "${it.groupValues[3]}:${it.groupValues[4]}"
            }
        }

        // 周次
        applyWeeksTo(pc, full.replace("|", ""))

        return if (pc.partialValid()) pc else null
    }

    // ====================================================================
    // 字段赋值（统一出口，含截断与清洗）
    // ====================================================================

    private fun assignField(pc: ParsedCourse, field: Field, rawValue: String) {
        when (field) {
            Field.CODE -> pc.code = cleanFieldValue(rawValue)
            Field.TEACHER -> pc.teacher = sanitizeTeacher(rawValue)
            Field.ROOM -> pc.classroom = cleanRoom(rawValue)
            Field.WEEKS -> applyWeeksTo(pc, rawValue)
            Field.TIME -> parseTimeInto(pc, rawValue)
            Field.CLASS -> { /* 班级不参与建模，忽略 */ }
        }
    }

    /**
     * 字段值统一边界清洗（修问题 4）：字段值提取后先 trim，再循环剥离首尾连续的
     * 杂字符 `| l I ! `（仅处理值的首尾边界，正文内部不动，避免误伤如「I」开头的英文词）。
     */
    private fun cleanValue(value: String): String {
        var s = value.trim()
        s = stripBoundaryJunk(s)
        return s
    }

    /** 仅循环剥离首尾的边界杂字符（| l I !）；遇以 l/I 开头/结尾的英文词则停止，保护正文 */
    private fun stripBoundaryJunk(s: String): String {
        var t = s
        // 行首
        while (t.isNotEmpty()) {
            val c = t[0]
            if (c in VALUE_BOUNDARY_JUNK) {
                // 保护以 l/I 开头的正文英文词（如「Ipad」「laptop」）：其后紧跟拉丁字母时不剥离；
                // 后接中文/数字等则视为噪声，照常剥离（CJK 字符 isLetter 也为 true，故需单独判断拉丁字母）
                if ((c == 'l' || c == 'I') && t.length > 1 && isLatinLetter(t[1])) break
                t = t.substring(1)
                continue
            }
            break
        }
        // 行尾
        while (t.isNotEmpty()) {
            val c = t.last()
            if (c in VALUE_BOUNDARY_JUNK) {
                if ((c == 'l' || c == 'I') && t.length > 1 && isLatinLetter(t[t.length - 2])) break
                t = t.substring(0, t.length - 1)
                continue
            }
            break
        }
        return t
    }

    /** 是否为拉丁字母（a-z / A-Z）；用于区分英文词首/尾与中文正文，避免误伤 */
    private fun isLatinLetter(c: Char): Boolean = (c in 'a'..'z') || (c in 'A'..'Z')

    /** 标签字段值（CODE/TEACHER）：边界清洗 + 去除标签后残留的首尾冒号 + 去冒号后杂空格 */
    private fun cleanFieldValue(value: String): String =
        cleanValue(value).trimStart(':', '：').trimEnd(':', '：').trim()

    /**
     * 教室：保留括号内容（如「(智慧)」），仅做边界杂字清理；
     * 卡片显示时由 ScheduleView.simplifyClassroom 再精简，日程页则显示完整信息。
     */
    private fun cleanRoom(value: String): String =
        cleanValue(value).trimStart(':', '：').trimEnd(':', '：').trim()

    /** 解析时间字段：星期 + 节次（大节/小节一致性校验）+ 时钟时间区间 */
    private fun parseTimeInto(pc: ParsedCourse, rawValue: String) {
        val v = repairTimeChars(rawValue)

        dayRegex.find(v)?.let { pc.day = dayMap[it.groupValues[1]] ?: pc.day }

        // 大节映射区间（如"第五大节"→ 10..11）；多个大节取并集
        var bigRange: IntRange? = null
        val bigs = bigSectionRegex.findAll(v).toList()
        if (bigs.isNotEmpty()) {
            var minS = Int.MAX_VALUE
            var maxE = Int.MIN_VALUE
            for (b in bigs) {
                val n = cnToInt(b.groupValues[1])
                if (n != null) {
                    val p = guangwaiBigSection(n)
                    minS = minOf(minS, p.first)
                    maxE = maxOf(maxE, p.second)
                }
            }
            if (minS != Int.MAX_VALUE) {
                bigRange = minS..maxE
                pc.startSection = minS
                pc.endSection = maxE
            }
        }

        // 小节数字（括号内列表）更精确，但 OCR 可能把数字认错（如 10,11 → 1,1）；
        // 只有全部小节都在大节区间内才采纳，否则用大节（宁可用对的粗粒度，不用错的细粒度）
        val small = smallSectionRegex.find(v)
        if (small != null) {
            val nums = small.groupValues[1].split(Regex("""[,.，、\s]+"""))
                .mapNotNull { it.toIntOrNull() }
            val mn = nums.minOrNull()
            val mx = nums.maxOrNull()
            val smallConsistent = bigRange == null || nums.all { it in bigRange }
            if (mn != null && mx != null && smallConsistent) {
                pc.startSection = mn
                pc.endSection = mx
            }
        }
        // 无大节也无可用小节，再尝试区间 / 单节写法
        if (pc.startSection <= 0) {
            sectionRangeRegex.find(v)?.let {
                pc.startSection = it.groupValues[1].toInt()
                pc.endSection = it.groupValues[2].toInt()
            }
        }
        // 单节形式兜底（第8节 / 第10节 相邻多节 → 取 min~max）
        if (pc.startSection <= 0) {
            val singles = singleSectionRegex.findAll(v)
                .mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
            if (singles.isNotEmpty()) {
                pc.startSection = singles.minOrNull()!!
                pc.endSection = singles.maxOrNull()!!
            }
        }

        timeRangeRegex.find(v)?.let {
            pc.startTime = "${it.groupValues[1]}:${it.groupValues[2]}"
            pc.endTime = "${it.groupValues[3]}:${it.groupValues[4]}"
        }
    }

    /** 周次解析（多段 / 单段 / 单周），写入 ParsedCourse */
    private fun applyWeeksTo(pc: ParsedCourse, value: String) {
        val v = value.trim()
        val allRanges = weekRangeRegex.findAll(v).toList()
        if (allRanges.size >= 2) {
            val covered = mutableSetOf<Int>()
            var minStart = Int.MAX_VALUE
            var maxEnd = Int.MIN_VALUE
            for (r in allRanges) {
                val a = r.groupValues[1].toInt().coerceAtLeast(1)
                val b = r.groupValues[2].toInt().coerceAtMost(40)
                minStart = minOf(minStart, a)
                maxEnd = maxOf(maxEnd, b)
                for (w in a..b) covered.add(w)
            }
            pc.startWeek = minStart
            pc.endWeek = maxEnd
            pc.excludedWeeks = (minStart..maxEnd).filter { it !in covered }.toSet()
        } else if (allRanges.size == 1) {
            val r = allRanges[0]
            pc.startWeek = r.groupValues[1].toInt().coerceAtLeast(1)
            pc.endWeek = r.groupValues[2].toInt().coerceAtMost(40)
        } else {
            val single = singleWeekRegex.find(v)
            if (single != null) {
                val w = single.groupValues[1].toInt()
                pc.startWeek = w
                pc.endWeek = w
            }
        }
        if (v.contains("单周")) pc.weekType = Course.WEEK_ODD
        else if (v.contains("双周")) pc.weekType = Course.WEEK_EVEN
    }

    // ====================================================================
    // 合并与构建
    // ====================================================================

    /** 字段级最优合并：以 base 为主，缺失/无效字段用 other 补全（信息多者优先） */
    private fun mergeParsed(base: ParsedCourse, other: ParsedCourse?): ParsedCourse {
        if (other == null) return base
        val r = base.copy()
        if (r.name.isEmpty()) r.name = other.name
        if (r.code.isEmpty()) r.code = other.code
        if (r.teacher.isEmpty()) r.teacher = other.teacher
        if (r.classroom.isEmpty()) r.classroom = other.classroom
        if (r.day !in 1..7) r.day = other.day
        // 节次：base 不合理则用 other 的合理值
        if (r.startSection !in 1..15 || r.endSection !in 1..15) {
            if (other.startSection in 1..15) {
                r.startSection = other.startSection
                r.endSection = other.endSection
            }
        }
        // 周次：base 不合理则用 other 的合理值（含排除周与单双周）
        if (r.startWeek !in 1..40 || r.endWeek !in 1..40 || r.startWeek > r.endWeek) {
            if (other.startWeek in 1..40 && other.endWeek in 1..40) {
                r.startWeek = other.startWeek
                r.endWeek = other.endWeek
                r.excludedWeeks = other.excludedWeeks
                r.weekType = other.weekType
            }
        } else if (r.weekType == Course.WEEK_EVERY && other.weekType != Course.WEEK_EVERY) {
            r.weekType = other.weekType
        }
        if (r.startTime.isEmpty()) r.startTime = other.startTime
        if (r.endTime.isEmpty()) r.endTime = other.endTime
        return r
    }

    /** 中间结果（解析阶段使用，含未校验字段） */
    private data class ParsedCourse(
        var name: String = "",
        var code: String = "",
        var teacher: String = "",
        var classroom: String = "",
        var day: Int = -1,
        var startSection: Int = -1,
        var endSection: Int = -1,
        var startWeek: Int = 1,
        var endWeek: Int = 16,
        var weekType: Int = Course.WEEK_EVERY,
        var excludedWeeks: Set<Int> = emptySet(),
        var startTime: String = "",
        var endTime: String = ""
    ) {
        /** 部分成功判定：有课程名，或有教师+任一时间信息（星期或节次） */
        fun partialValid(): Boolean =
            name.isNotEmpty() ||
                (teacher.isNotEmpty() && (day in 1..7 || startSection in 1..15))

        /** 合理性校验后构建最终 Course（宁缺勿错：不合理字段丢弃，缺省给默认） */
        fun build(): Course? {
            if (!partialValid()) return null

            // 名称守卫：整体=标签或超长（把整段正文当名称）→ 未命名占位，核对页用户可改
            val n = if (name.isNotEmpty() && !isLabelOnly(name) && name.length <= 24) {
                name
            } else {
                "未命名课程"
            }

            val d = if (day in 1..7) day else 1

            val (ss, es) = when {
                startSection in 1..15 && endSection in 1..15 && endSection >= startSection ->
                    startSection to endSection
                startSection in 1..15 ->
                    startSection to startSection
                else -> 1 to 2
            }

            val (sw, ew, ex, wt) = normalizeWeeks(startWeek, endWeek, excludedWeeks, weekType)

            // 地点守卫：必须符合教室/场地模式（中文+字母+房号，或 场/馆/楼/堂/号 等），否则留空让用户填
            val room = if (roomValidRegex.containsMatchIn(classroom)) classroom else ""

            return Course(
                id = UniqueId.next(),
                name = n,
                teacher = sanitizeTeacher(teacher),
                classroom = room,
                dayOfWeek = d,
                startSection = ss,
                endSection = es,
                startWeek = sw,
                endWeek = ew,
                weekType = wt,
                colorIndex = 0,
                excludedWeeks = ex,
                code = code,
                startTime = startTime,
                endTime = endTime
            )
        }
    }

    /** 教室/场地合法性模式：中文+字母+房号（南G211/南实B206）、孤立字母+3位房号（B206）、场/馆/楼/堂/号（南排球场1号） */
    private val roomValidRegex = Regex(
        """[一-龥]{1,5}[A-Za-z]\d{2,4}|(?<![A-Za-z])[A-Za-z]\d{3}|[一-龥]{1,6}(?:场|馆|楼|堂|园|厅|室|中心|基地)\d*号?"""
    )

    /** 周次合理性校验：1~40 且起≤止才采纳，否则回落默认 1-16 并清空排除周 */
    private fun normalizeWeeks(
        sw: Int, ew: Int, ex: Set<Int>, wt: Int
    ): Quad {
        return if (sw in 1..40 && ew in 1..40 && sw <= ew) {
            Quad(sw, ew, ex, wt)
        } else {
            Quad(1, 16, emptySet(), wt)
        }
    }

    private data class Quad(val sw: Int, val ew: Int, val ex: Set<Int>, val wt: Int)

    // ====================================================================
    // 标题清洗与数字工具
    // ====================================================================

    private fun cleanName(s: String): String {
        var t = s.trim('×', '✕', '*', '#', '·', ' ', '　')
        if ((t.endsWith("x") || t.endsWith("X")) && t.length > 2) t = t.dropLast(1)
        t = t.trim('×', '✕', ' ')
        // 高频 OCR 单字错认修复（只修特定词组，不做泛化替换，避免误伤）
        t = t.replace("人I智能", "人工智能")
            .replace("人l智能", "人工智能")
            .replace("人1智能", "人工智能")
            .replace("基础云订", "基础会计")
        return t
    }

    /** 职称锚点：「姓名+职称」结构（讲师/教授/副教授/老师…），教师标签缺失时的直接提取 */
    private val titleAnchorRegex = Regex(
        """([一-龥·]{2,4})(?:副教授|高级实验师|高级工程师|副研究员|教授|讲师|实验师|工程师|研究员|助教|老师|教师|講師|教師|老師)"""
    )

    /** 教师职称后缀（网格截图里"胡艳讲师/姚银燕副教授/陈仕鸿教授"，识别后去掉） */
    private val teacherTitles = listOf(
        "副教授", "高级实验师", "高级工程师", "副研究员", "教授", "讲师",
        "实验师", "工程师", "研究员", "助教", "老师", "教师", "講師", "教師", "老師"
    )

    /**
     * 教师值统一守卫（系统化）：
     * ① 用编辑距离标签引擎剔除值首部残留的教师标签（含任意 OCR 错字变体）；
     * ② 值整体就是标签 → 视为空；
     * ③ 剔除首部标签残字（「师周雅颂」→「周雅颂」）；
     * ④ 去职称后缀与 OCR 截断变体；⑤ 只保留中文姓名；
     * ⑥ 合理性校验：非 2~5 字纯姓名一律留空（宁可空着让用户填，也不错填）。
     */
    private fun sanitizeTeacher(raw: String): String {
        var v = cleanFieldValue(raw)
        if (v.isEmpty()) return ""
        // ① 剔除值首部教师标签（繁简归一化 + 编辑距离容错）
        val simp = toSimplified(v)
        val labelHit = findLabels(simp).firstOrNull { it.start == 0 && it.field == Field.TEACHER }
        if (labelHit != null) {
            v = v.substring(labelHit.end.coerceAtMost(v.length))
        }
        v = v.trim(':', '：', ' ', '|').trim()
        if (v.isEmpty()) return ""
        // ①.5 值内混入后续字段标签（教师标签 miss 时值会吞到「上课地点…」等后续内容）：
        // 在最近的非教师标签处截断，避免「郑富上课地点南排球场号」这类超长值被整段拒收。
        // 典型场景：OCR 把教师标签认成无法命中的乱码，但姓名与后续标签仍完整。
        val simp2 = toSimplified(v)
        val otherLabel = collectByField(simp2)
            .values
            .filter { it.start > 0 && it.field != Field.TEACHER }
            .minByOrNull { it.start }
        if (otherLabel != null) {
            v = v.substring(0, otherLabel.start).trim()
        }
        if (v.isEmpty()) return ""
        // ② 值整体就是标签 → 空
        if (isLabelOnly(v)) return ""
        // ③ 剔除值首部的标签残字（如「师周雅颂」→「周雅颂」：标签只残留了"师"）
        while (v.isNotEmpty() && (v.first() == '师' || v.first() == '師' ||
                v.first() == '數' || v.first() == '授' || v.first() == '课' || v.first() == '課' ||
                v.first() == '投' || v.first() == '敦' || v.first() == '谋' || v.first() == '散')
        ) {
            v = v.drop(1)
        }
        if (v.isEmpty()) return ""
        // ④ 职称后缀 + OCR 截断变体（「姚银燕副教」= 副教授截断）
        for (t in teacherTitles) {
            if (v.endsWith(t)) {
                v = v.removeSuffix(t).trim()
                break
            }
        }
        val variants = listOf("副教", "讲", "教散")
        val variant = variants.firstOrNull { v.endsWith(it) && it.length < v.length }
        if (variant != null) v = v.removeSuffix(variant).trim()
        // ⑤ 仅保留中文与「·」，剔除混入的数字/字母/标点
        val kept = v.filter { it in '\u4e00'..'\u9fa5' || it == '·' }
        // ⑥ 合理性：2~5 字纯姓名，否则留空（让用户在核对页手动填）
        return if (kept.length in 2..5) kept else ""
    }

    /**
     * 课程名统一守卫：
     * ① 名字里混入标签行（"課程代码: CJ20010"）→ 在最早标签处截断；
     * ② 常见 OCR 错字修复（"人I智能"→"人工智能"）。
     */
    private fun sanitizeName(raw: String): String {
        var v = cleanName(raw)
        // ① 名字里混入标签（如"課程代码: CJ20010"）→ 在最早标签处截断
        val simp = toSimplified(v)
        var earliest = v.length
        for (h in findLabels(simp)) {
            if (h.start < earliest) earliest = h.start
        }
        if (earliest < v.length) v = v.substring(0, earliest).trim(' ', '：', ':')
        v = v.replace(Regex("""^人[Il1|/\\]智能"""), "人工智能")
        return v.trim()
    }

    /** 网格截图地点清洗："南G座(南G211（全功能智慧)）"→"南G211(全功能智慧)"（取首个括号内的具体教室） */
    private fun cleanGridLocation(v: String): String {
        var s = v.trim().trimEnd(';', '；')
        val first = s.indexOfAny(charArrayOf('(', '（'))
        val last = s.lastIndexOfAny(charArrayOf(')', '）'))
        if (first in 0 until last) s = s.substring(first + 1, last)
        s = s.replace('（', '(').replace('）', ')').trim()
        return s
    }

    /** 广外大节 → 小节映射（第三大节 = 第6、7节，见官方作息表） */
    private fun guangwaiBigSection(n: Int): Pair<Int, Int> = when (n) {
        1 -> 1 to 2
        2 -> 3 to 4
        3 -> 6 to 7
        4 -> 8 to 9
        5 -> 10 to 11
        6 -> 12 to 13
        7 -> 14 to 15
        else -> (n * 2 - 1) to (n * 2)
    }

    private fun cnToInt(cn: String): Int? {
        if (cn.isEmpty()) return null
        if (cn == "十") return 10
        if (cn.length == 1) return cnNumMap[cn]
        if (cn.startsWith("十")) {
            val rest = cnNumMap[cn.substring(1)] ?: return null
            return 10 + rest
        }
        if (cn.contains("十")) {
            val parts = cn.split("十")
            val a = cnNumMap[parts[0]] ?: return null
            val b = if (parts.size > 1 && parts[1].isNotEmpty()) cnNumMap[parts[1]] ?: return null else 0
            return a * 10 + b
        }
        return null
    }
}
