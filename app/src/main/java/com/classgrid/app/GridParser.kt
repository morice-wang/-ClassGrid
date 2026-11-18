package com.classgrid.app

/**
 * 整周课表网格解析：
 * 输入一张「周视图课表」截图（教务系统网页 / 其他课表 App 的整周界面），利用每行文字的坐标：
 * ① 定位顶部星期表头 → 列锚点；② 定位左侧大节/时间 → 行锚点；
 * ③ 正文行按坐标分配到列；④ 列内按纵向间隙聚类为课程色块；
 * ⑤ 解析色块内 名称 / 老师 / 时间(周次+节次) / 地点。
 * 非网格截图（无星期表头）返回 null，交由「课程详情弹窗」解析通道处理。
 */
object GridParser {

    data class LineBox(
        val text: String,
        val left: Int, val top: Int, val right: Int, val bottom: Int
    ) {
        val cx get() = (left + right) / 2
        val cy get() = (top + bottom) / 2
        val height get() = bottom - top
    }

    /** 表头候选：day>0 表示已识别出星期几；dated=true 表示只看出是日期，需要按列顺序推断 */
    private class HeadHit(val line: LineBox, val day: Int, val dated: Boolean)

    private val dayRegex = Regex("""(?:星期|周|週|禮拜|礼拜)([一二三四五六日天1-7])""")
    private val bigSectionRegex = Regex("""第([一二三四五六七八九十1-7])大?节""")
    private val smallSectionRegex = Regex("""第\s*(\d{1,2})\s*节""")
    private val timeRegex = Regex("""(\d{1,2})[:：](\d{2})""")

    /** 大节 → 小节范围（广外作息，与 ScheduleView 映射一致） */
    private val bigToSmall = mapOf(
        1 to (1..2), 2 to (3..5), 3 to (6..7), 4 to (8..9),
        5 to (10..11), 6 to (12..13), 7 to (14..15)
    )

    private val teacherLineRegex =
        Regex("""(?:老师|教师|授課老師|授课老师|授课教师)\s*[:：]?\s*([一-龥·]{2,5})""")
    private val titleAnchorRegex =
        Regex("""([一-龥·]{2,4})(?:副教授|高级实验师|高级工程师|副研究员|教授|讲师|实验师|工程师|研究员|助教|老师|教师|講師|教師|老師)""")
    private val teacherTitles = listOf(
        "副教授", "高级实验师", "高级工程师", "副研究员", "教授", "讲师",
        "实验师", "工程师", "研究员", "助教", "老师", "教师", "講師", "教師", "老師"
    )
    private val timeFieldRegex = Regex("""时间\s*[:：]\s*([^;；]+)""")
    private val weeksInFieldRegex = Regex("""([0-9,\-~～、]+)\s*周""")
    private val sectionBracketRegex = Regex("""\[([0-9\-~～,，、]+)\s*节?\]""")
    private val placeFieldRegex = Regex("""地点\s*[:：]\s*([^;；]+)""")

    private val roomRegex =
        Regex("""[一-龥]{1,4}[A-Za-z]\d{2,4}|(?<![A-Za-z])[A-Za-z]\d{3}(?![A-Za-z\d])""")
    private val roomValidRegex = Regex(
        """[一-龥]{1,5}[A-Za-z]\d{2,4}|(?<![A-Za-z])[A-Za-z]\d{3}|[一-龥]{1,6}(?:场|馆|楼|堂|园|厅|室|中心|基地)\d*号?"""
    )
    private val bracketTailRegex = Regex("""[\(（][^()（）]{0,10}[\)）]""")
    private val suffixWords = listOf(
        "全功能智慧", "智慧", "多媒体", "语音室", "实验室", "机房",
        "报告厅", "微格", "实训", "阶梯教室"
    )

    fun parse(lines: List<LineBox>): List<Course>? {
        if (lines.size < 10) return null

        // ---------- ① 星期表头 → 列锚点 ----------
        // v1.42.0：不再只认「周一/星期一」。大量教务系统截图的表头是「一 11/2」「二」「四(11/5)」，
        // 甚至只有日期「11/2」「11/3」——旧实现直接 return null，于是整张课表被当成"识别失败"。
        // 这里按 x 位置把表头文字聚成列，再分别用「星期几」或「列顺序」还原列↔星期。
        val headerCandidates = lines.mapNotNull { line ->
            val named = TimetableGrid.dayOf(line.text)
            val dated = named == 0 && TimetableGrid.isDateHeader(line.text)
            if (named > 0) HeadHit(line, named, false)
            else if (dated) HeadHit(line, 0, true)
            else null
        }
        if (headerCandidates.size < 3) return null

        val headerTop = headerCandidates.minOf { it.line.top }
        val headerBand = headerCandidates.filter { it.line.top in headerTop - 40..headerTop + 90 }
        // 同一列可能被拆成上下两行（「一」在上面、「11/2」在下面）：按 x 聚类
        val clusters = mutableListOf<MutableList<HeadHit>>()
        for (hit in headerBand.sortedBy { it.line.top }) {
            val last = clusters.lastOrNull()
            val lastCx = last?.map { it.line.cx }?.average()?.toInt() ?: Int.MIN_VALUE
            if (last != null && Math.abs(hit.line.cx - lastCx) <= 40) {
                last.add(hit)
            } else {
                clusters.add(mutableListOf(hit))
            }
        }
        if (clusters.size < 3) return null

        var namedDays = clusters.map { cl -> cl.firstNotNullOfOrNull { it.day.takeIf { d -> d > 0 } } ?: 0 }
        if (namedDays.count { it > 0 } < 2) {
            // 表头只有日期（或日期被 OCR 拆碎）：按 x 顺序映射为周一…周日（最多 7 列）
            namedDays = clusters.indices.map { it + 1 }.map { it.coerceAtMost(7) }
        }
        // 认出了至少两个「星期几」时，保留真实星期编号；没认出的列记 0（该列不参与解析），
        // 这样即使 OCR 漏掉「一 11/2」，其余列也不会整体错位成周二…周六。
        val cols = clusters.map { it.map { h -> h.line.cx }.average().toInt() }
        val colDays = namedDays
        val firstColX = cols.first()
        val avgColW = (cols.last() - cols.first()).toFloat() / (cols.size - 1).coerceAtLeast(1)

        // ---------- ② 左侧节次/时间 → 行锚点 ----------
        // v1.42.0：兼容「第01节…第14节」这种按小节标行的教务系统（肇庆学院等），
        // 以及「第N大节」「08:30」三种左列写法；锚点携带真实小节区间，不再统一走广外 7 大节映射。
        val leftLines = lines.filter { it.cx < firstColX - avgColW * 0.25f }
        val rowAnchors = mutableListOf<Pair<Int, IntRange>>()
        for (line in leftLines) {
            val big = bigSectionRegex.find(line.text)
            if (big != null) {
                val n = cnNumToInt(big.groupValues[1]) ?: continue
                rowAnchors.add(line.cy to (bigToSmall[n] ?: IntRange(n, n)))
                continue
            }
            val small = smallSectionRegex.find(line.text)
            if (small != null) {
                val n = small.groupValues[1].toIntOrNull() ?: continue
                if (n in 1..15) rowAnchors.add(line.cy to IntRange(n, n))
                continue
            }
            if (timeRegex.containsMatchIn(line.text)) {
                // 只给钟点时间时，按出现顺序两节一行兜底（真正的换算交给结果页让用户核对）
                rowAnchors.add(line.cy to IntRange(-1, -1))
            }
        }
        if (rowAnchors.size < 2) return null
        val rowAnchorYs = rowAnchors.sortedBy { it.first }.map { it.first }

        // ---------- ③ 正文行分配到列 ----------
        val headerBottom = headerBand.maxOf { it.line.bottom }
        val bodyLines = lines.filter {
            it.top > headerBottom - 10 && it.cx > firstColX - avgColW * 0.4f
        }
        fun columnOf(x: Int): Int? {
            var best = -1
            var bestD = Float.MAX_VALUE
            for (i in cols.indices) {
                val d = Math.abs(x - cols[i]).toFloat()
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            return if (bestD <= avgColW * 0.6f) best else null
        }

        // ---------- ④ 列内按纵向间隙聚类 → 课程色块 ----------
        val courses = mutableListOf<Course>()
        for (ci in cols.indices) {
            val day = colDays[ci]
            if (day <= 0) continue
            val colLines = bodyLines
                .filter { columnOf(it.cx) == ci }
                .sortedBy { it.cy }
            if (colLines.isEmpty()) continue

            val sortedH = colLines.map { it.height }.sorted()
            val medH = sortedH[sortedH.size / 2].coerceAtLeast(1)
            val gapThreshold = medH * 1.8f

            var cluster = mutableListOf<LineBox>()
            var prev: LineBox? = null
            fun flush() {
                if (cluster.isNotEmpty()) {
                    buildCourse(cluster, day, rowAnchors)?.let { courses.add(it) }
                }
                cluster = mutableListOf()
            }
            for (line in colLines) {
                if (prev != null && line.top - prev!!.bottom > gapThreshold) flush()
                cluster.add(line)
                prev = line
            }
            flush()
        }
        return if (courses.size >= 2) courses else null
    }

    /** 解析一个课程色块为 Course */
    private fun buildCourse(
        cluster: List<LineBox>,
        day: Int,
        rowAnchors: List<Pair<Int, IntRange>>
    ): Course? {
        val joined = cluster.joinToString(";") { it.text.trim() }

        // 课程名：簇内首个「非标签且非时间行」的行。
        // 时间行特征：含「第X-Y节 / 第X大节 / HH:MM」且含「周/节」（如「周一 第3-5节·1-16周」），
        // 不能当课程名；真正的课程名（如「高等数学(1)」）在时间行之后。
        val labelPrefixes = listOf("老师", "教师", "时间", "地点", "上课", "授课", "星期", "周次")
        val timeRowRegex = Regex(
            """第\s*[0-9一二三四五六七八九十]+\s*[-~～]\s*[0-9一二三四五六七八九十]+\s*节|第\s*[一二三四五六七八九十]+\s*大?节|\d{1,2}[:：]\d{2}"""
        )
        var name = ""
        val detailLines = mutableListOf<String>()
        for (line in cluster) {
            val t = line.text.trim().trim(';')
            val isLabel = labelPrefixes.any { t.startsWith(it) } ||
                teacherLineRegex.containsMatchIn(t) ||
                timeFieldRegex.containsMatchIn(t) ||
                placeFieldRegex.containsMatchIn(t)
            val isTimeRow = timeRowRegex.containsMatchIn(t) && (t.contains("周") || t.contains("节"))
            if (name.isEmpty() && !isLabel && !isTimeRow) name = t
            if (isLabel || isTimeRow) detailLines.add(t)
        }
        val detail = detailLines.joinToString(";")
        if (name.isEmpty() && detail.isEmpty()) return null

        // 老师（标签可能在被判定为课程名的首行，故在全文里找）
        var teacher = ""
        teacherLineRegex.find(joined)?.let { m ->
            var v = m.groupValues[1]
            // 职称后缀清洗：支持完整职称与 OCR 截断变体（副教授→副教、教师→教散）
            val titles = teacherTitles + listOf("副教", "教散")
            val title = titles.filter { v.endsWith(it) }.maxByOrNull { it.length }
                ?: titles.filter { v.contains(it) }.maxByOrNull { it.length }
            if (title != null && v.length > title.length) {
                v = v.substring(0, v.indexOf(title))
            }
            teacher = v.filter { it in '一'..'龥' || it == '·' }
        }
        // 职称锚点兜底：「姓名+职称」结构（讲师/教授/副教授…）直接提取，不依赖"老师"标签
        if (teacher.isEmpty()) {
            titleAnchorRegex.find(joined)?.let { m ->
                teacher = m.groupValues[1].filter { it in '一'..'龥' || it == '·' }
            }
        }

        // 时间字段：周次 + [节次]
        var startWeek = 1
        var endWeek = 16
        var excluded = emptySet<Int>()
        var weekType = Course.WEEK_EVERY
        var startSection = -1
        var endSection = -1

        val timeField = timeFieldRegex.find(joined)?.groupValues?.get(1)
        if (timeField != null) {
            weeksInFieldRegex.find(timeField)?.let { m ->
                val weeks = expandNumberList(m.groupValues[1])
                if (weeks.isNotEmpty()) {
                    startWeek = weeks.min()
                    endWeek = weeks.max()
                    excluded = (startWeek..endWeek).filter { it !in weeks }.toSet()
                    weekType = when {
                        weeks.all { it % 2 == 1 } -> Course.WEEK_ODD
                        weeks.all { it % 2 == 0 } -> Course.WEEK_EVEN
                        else -> Course.WEEK_EVERY
                    }
                }
            }
            sectionBracketRegex.find(timeField)?.let { m ->
                val secs = expandNumberList(m.groupValues[1])
                if (secs.isNotEmpty()) {
                    startSection = secs.min()
                    endSection = secs.max()
                }
            }
        }

        // 节次兜底：用课程色块的纵向范围与左列锚点区间取交集（同一门课可能跨 2-3 个锚点行）
        if (startSection <= 0) {
            val sorted = rowAnchors.sortedBy { it.first }
            var lo = Int.MAX_VALUE
            var hi = -1
            val top = cluster.minOf { it.top }
            val bottom = cluster.maxOf { it.bottom }
            for (i in sorted.indices) {
                val y = sorted[i].first
                val prevY = sorted.getOrNull(i - 1)?.first ?: (y - 10000)
                val nextY = sorted.getOrNull(i + 1)?.first ?: (y + 10000)
                val bandTop = (prevY + y) / 2
                val bandBottom = (y + nextY) / 2
                if (bottom > bandTop && top < bandBottom) {
                    val r = sorted[i].second
                    if (r.first > 0) {
                        lo = minOf(lo, r.first)
                        hi = maxOf(hi, r.last)
                    }
                }
            }
            if (hi >= lo) {
                startSection = lo
                endSection = hi
            }
        }

        // 地点
        var room = ""
        placeFieldRegex.find(joined)?.let { m ->
            room = cleanRoomText(m.groupValues[1], joined)
        }
        // 地点合理性：必须符合教室/场地模式（房号或 场/馆/楼/堂/号），否则留空让用户填
        if (room.isNotEmpty() && !roomValidRegex.containsMatchIn(room)) room = ""

        if (startSection <= 0) {
            startSection = 1
            endSection = 2
        }
        if (endSection < startSection) endSection = startSection

        return Course(
            id = UniqueId.next(),
            name = name.ifEmpty { "未命名课程" },
            teacher = teacher,
            classroom = room,
            dayOfWeek = day,
            startSection = startSection.coerceIn(1, 15),
            endSection = endSection.coerceIn(1, 15),
            startWeek = startWeek.coerceIn(1, 40),
            endWeek = endWeek.coerceIn(1, 40),
            weekType = weekType,
            excludedWeeks = excluded,
            colorIndex = 0
        )
    }

    /** 清洗地点：嵌套括号里取最具体房号（如「南G座(南G211（全功能智慧)）」→「南G211(全功能智慧)」）；
     *  地点字段被 OCR 截断（如「南G座(南G」「南实B座」）时，从簇内完整文本兜底扫房号 */
    private fun cleanRoomText(raw: String, joined: String): String {
        var t = raw.trim().trimEnd(';', '；', ')', '）').trim()
        val hits = roomRegex.findAll(t).toList()
        if (hits.isNotEmpty()) {
            val last = hits.last()
            val specific = last.value
            val after = t.substring(last.range.last)
            val bracket = bracketTailRegex.find(after)
            val suffix = bracket?.value?.trim('(', '（', ')', '）')
                ?: suffixWords.firstOrNull { t.contains(it) }
            return specific + (suffix?.let { "($it)" } ?: "")
        }
        // 兜底：地点字段内无房号（截断），在簇内全文找房号 + 括号/后缀
        val allHits = roomRegex.findAll(joined).toList()
        if (allHits.isNotEmpty()) {
            val last = allHits.last()
            val specific = last.value
            val after = joined.substring(last.range.last)
            val bracket = bracketTailRegex.find(after)
            val suffix = bracket?.value?.trim('(', '（', ')', '）')
                ?: suffixWords.firstOrNull { joined.contains(it) }
            return specific + (suffix?.let { "($it)" } ?: "")
        }
        return t
    }

    /** 展开「2-4,6-16」这类数字列表为集合 */
    private fun expandNumberList(s: String): Set<Int> {
        val out = sortedSetOf<Int>()
        for (part in s.split(",", "，", "、")) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val range = p.split("-", "~", "～")
            when (range.size) {
                1 -> p.toIntOrNull()?.let { out.add(it) }
                else -> {
                    val a = range.first().toIntOrNull()
                    val b = range.last().toIntOrNull()
                    if (a != null && b != null) for (w in a..b) out.add(w)
                }
            }
        }
        return out
    }

    private fun dayCharToInt(c: String): Int? = when (c) {
        "一", "1" -> 1
        "二", "2" -> 2
        "三", "3" -> 3
        "四", "4" -> 4
        "五", "5" -> 5
        "六", "6" -> 6
        "日", "天", "7" -> 7
        else -> null
    }

    private fun cnNumToInt(c: String): Int? {
        c.toIntOrNull()?.let { return it }
        return when (c) {
            "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5
            "六" -> 6; "七" -> 7; "八" -> 8; "九" -> 9; "十" -> 10
            else -> null
        }
    }
}
