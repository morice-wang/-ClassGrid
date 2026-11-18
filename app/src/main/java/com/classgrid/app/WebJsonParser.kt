package com.classgrid.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * 从「页面自己请求过的接口响应」里解析课程（**读后端数据**路径）。
 *
 * ===== 为什么要有这条路 =====
 * [WebScheduleParser] 读的是渲染后的 DOM 表格，视觉上被截断/懒加载/合并的课程会丢；
 * 而接口 JSON 是原始数据，周次节次往往完整。难点在于**各校各厂商 key 完全不一样**
 * （湖南强智 jsxsd、金智 ehall、正方 jwglxt 各写各的），且用户设备会回传未知系统的响应，
 * 所以这里刻意不做「按固定 schema 反序列化」，而是：
 *   1. 不看 URL、不看 path —— 只要响应体是 JSON 就递归扫全树；
 *   2. 找「数组里装着一堆对象、对象上带两三个课程字段」的那个数组（打分排序，
 *      同一页面可能有多个接口，每个响应各自取最优数组）；
 *   3. key 归一化后做「别名 + 包含」匹配（kcmc ⊂ kcmcstr、课程名称 ⊂ 课程名称展示），
 *      值的语义解析**复用 [WebScheduleParser] 的 public 工具**（周次/节次/星期的规则只维护一份，
 *      避免两个解析器对「1-16(周)[01-02节]」给出不同结论）。
 *
 * ===== 契约（上层已按此接线，签名不要改）=====
 * - 任何异常都在内部吃掉：解析失败返回空列表 + 向 [parse] 的 notes 写人类可读原因，
 *   绝不把异常抛给 UI 线程（崩溃比「没解析出来」严重得多）；
 * - 输出 `Course.id` 一律 `0L`（外层会重新发号），去重键见 [dedupe]；
 * - 周次缺失时按 1-[DEFAULT_TOTAL_WEEKS] 周兜底并提示用户去核对页确认。
 */
object WebJsonParser {

    /** 接口没给周次时的兜底总周数（广外一学期 16-18 周，留到 20 周不会漏掉后半学期课） */
    private const val DEFAULT_TOTAL_WEEKS = 20

    /** 与 [WebScheduleParser] 保持一致的上限；周次/节次越界一律夹紧而不是丢弃课程 */
    private const val MAX_WEEK = 30
    private const val MAX_SECTION = 15

    /** 递归扫描的边界：真实课表 payload 远小于此；防线是防止巨型/恶意 JSON 卡死 UI 线程 */
    private const val MAX_DEPTH = 8
    private const val MAX_NODES = 20000

    /** 候选数组的最低得分：至少 2 个对象各含 2 个可识别字段（2 * 10 + 字段种类数） */
    private const val MIN_SCORE = 20

    /** notes 里 URL 的截断长度：诊断弹窗不是日志，太长会撑爆界面 */
    private const val URL_NOTE_LIMIT = 80

    // ------------------------------------------------------------------ 字段模型

    /** 课程字段的「语义种类」。key 千奇百怪，但一个课程对象只需要这 7 种值。 */
    private enum class Kind { NAME, TEACHER, ROOM, DAY, SECTION, WEEKS, CODE }

    /**
     * 别名表（全部预先归一小写）。匹配优先级：完全相等 > 前缀/后缀 > 包含。
     *
     * 为什么用「包含」：真实接口里同一个字段会被厂商加前后缀，比如 `kcmcstr`、`kcmcdisplay`、
     * `课程名称展示`；如果只做精确匹配，换一版教务系统就全军覆没。
     * 为什么 NAME 里没有 `kch/kcdm`：那是课程代码，被当成名字的话，课表上会出现一列 "12345"
     * （见单测 kchIsNotCourseName），所以代码类字段单独归到 [Kind.CODE]，靠「完全匹配优先」
     * 保证 `课程代码` 不会被 NAME 的「课程」包含匹配抢走。
     */
    private val ALIASES: List<Pair<Kind, List<String>>> = listOf(
        Kind.NAME to listOf(
            "kcmc", "kcmcstr", "kcmcdisplay", "coursename", "course", "kcmc1",
            "name", "课程名称", "课程名", "课程"
        ),
        Kind.TEACHER to listOf(
            "jsxm", "jsmc", "jsname", "teacher", "teachername", "skjs",
            "教师", "任课教师", "授课教师"
        ),
        Kind.ROOM to listOf(
            "cdmc", "cdmcstr", "classroom", "room", "jxdd", "jxddmc", "skdd",
            "教室", "上课地点", "地点"
        ),
        Kind.DAY to listOf(
            "xqj", "xqjstr", "xqjmc", "xingqi", "weekday", "dayofweek", "skxq", "skxqstr",
            "星期", "星期几", "上课星期"
        ),
        // 节次别名要「宽」：真实接口里可能叫 jcs/jc/jcor，也可能只给一段上课时间文本
        // （"第七大节（14,15小节）20:00 ~ 21:20"）。这些形态都交给 parseSections 统一消化，
        // 宁可多认几个 key，也不要因为字段名不同就把整门课塞到第 1-2 节（v1.32.0 的真机反馈）。
        Kind.SECTION to listOf(
            "jcs", "jc", "jcstr", "jcor", "jcorstr", "sections", "section", "skjc", "sjd",
            "jcdm", "jcsj", "sksj", "sksjstr", "kcsj", "skjcstr", "djc",
            "节次", "上课节次", "上课时间", "上课时段", "时间"
        ),
        Kind.WEEKS to listOf(
            "zcd", "zc", "zcstr", "zcmc", "weeks", "week", "skzc",
            "周次", "上课周次"
        ),
        Kind.CODE to listOf(
            "kch", "kcdm", "coursecode", "code", "课程代码", "课程号"
        )
    ).map { (kind, list) -> kind to list.map { normalizeKey(it) } }

    /** 归一化后的 key 与某个字段种类的一次匹配结果 */
    private data class FieldMatch(val kind: Kind, val quality: Int)

    /**
     * 学期字段黑名单：`xq` / `xqmc` / `xnxq` 这类 key 在教务与门户接口里几乎都是「学期」，
     * 不是「星期几」。它们一旦被当成星期字段，值恰好是 1..7 的数字就会把**所有课程**
     * 塞进同一列。
     *
     * v1.36.0 真机问题「抓取后全部课程都在周二」的根因就在这里：
     * DAY 别名表里原本含 `xq`（学期编号，值 "2"）→ parseDay("2") = 2 → 全部周二。
     * 因此这里把这些 key 从所有字段匹配中直接剔除，而不是只在 DAY 里删别名
     * （`xqmc` 还会被 `xq` 前缀包含匹配命中，删别名删不干净）。
     */
    private val IGNORED_KEYS: Set<String> = setOf(
        "xq", "xqmc", "xqid", "xnxq", "xnxqid", "xnxqmc", "semester", "term",
        "学期", "学年", "学期名称", "学年学期"
    ).map { normalizeKey(it) }.toSet()

    /** 一个候选数组（可能来自任意接口、任意层级） */
    private data class Candidate(
        val url: String,
        val array: JSONArray,
        val objectCount: Int,
        val score: Int
    )

    /** 映射成功的一条课程 + 两项「用了兜底值」的标记，供 notes 提示用户核对 */
    private data class Mapped(
        val course: Course,
        val weekMissing: Boolean,
        val sectionMissing: Boolean
    )

    /** 扫描预算：深度/节点数双限，避免一个 10MB 的响应把解析拖到 ANR */
    private class ScanBudget {
        var nodes = 0
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 从页面捕获到的接口响应里找课表数据。
     *
     * @param net 页面自己请求过的接口响应（已由 [WebScheduleParser.parsePayload] 解析好）
     * @param sections 用户节次作息（默认广外 15 节），用于「接口只给钟点时间」时反推小节号
     * @param notes 解析提示（要往这里 add 人类可读的中文说明，供 App 诊断弹窗展示）
     * @return Course 列表；找不到返回空列表（不要抛异常）
     */
    fun parse(
        net: List<WebScheduleParser.NetDoc>,
        sections: List<SectionTimes.Section> = SectionTimes.default(),
        notes: MutableList<String> = mutableListOf(),
        keepUnknownDay: Boolean = false
    ): List<Course> = try {
        parseInternal(net, sections, notes, keepUnknownDay)
    } catch (e: Exception) {
        // 兜底：即便某个诡异的 org.json 实现/极端数据抛了运行期异常，也不能让导入页崩掉
        notes.add("接口数据解析异常：" + (e.message ?: e.javaClass.simpleName))
        emptyList()
    }

    private fun parseInternal(
        net: List<WebScheduleParser.NetDoc>,
        sections: List<SectionTimes.Section>,
        notes: MutableList<String>,
        keepUnknownDay: Boolean
    ): List<Course> {
        if (net.isEmpty()) {
            notes.add("没有捕获到任何接口响应：页面可能还没发出课表请求，或请求发生在注入脚本之前")
            return emptyList()
        }

        var htmlCount = 0      // 看起来是 HTML 页面（登录页/门户页）
        var textCount = 0      // 非 HTML 也不是 JSON（JSONP、纯文本、base64 等）
        var emptyCount = 0     // 空 body
        var jsonCount = 0      // 真正解析成功的 JSON
        var badJsonCount = 0   // 看着像 JSON 但语法错误
        var diagnosticBest: Candidate? = null
        val perDocBest = mutableListOf<Candidate>()

        for (doc in net) {
            // BOM 会让 startsWith("{") 失败，先去掉；这也是真实抓包里常见的前缀噪声
            val body = doc.body.replace("\uFEFF", "").trim()
            if (body.isEmpty()) {
                emptyCount++
                continue
            }
            // 契约第 1 步：不是以 { 或 [ 开头就不是 JSON 接口（HTML 页面直接忽略）
            if (!body.startsWith("{") && !body.startsWith("[")) {
                if (body.startsWith("<")) htmlCount++ else textCount++
                continue
            }
            val root: Any = try {
                if (body.startsWith("{")) JSONObject(body) else JSONArray(body)
            } catch (e: Exception) {
                badJsonCount++
                continue
            }
            jsonCount++

            val candidates = mutableListOf<Candidate>()
            walk(root, 0, ScanBudget(), doc.url, candidates)
            if (candidates.isEmpty()) continue

            // 诊断用：哪怕得分不够，也记下「最像课表的数组」，失败提示能说清卡在哪一步
            val docTop = candidates.maxByOrNull { it.score }
            if (docTop != null && (diagnosticBest == null || docTop.score > diagnosticBest.score)) {
                diagnosticBest = docTop
            }

            // 每个响应各取「最高分且达标」的数组：
            // 同一页面可能抓不到多个接口（课表 + 考试安排），每个响应内部只需要最像课表的那个；
            // 多个响应之间再合并去重（同一个接口被 fetch/XHR 双钩子重复采集是常态）。
            val qualifying = candidates
                .filter { it.objectCount >= 2 && it.score >= MIN_SCORE }
                .maxByOrNull { it.score }
            if (qualifying != null) perDocBest.add(qualifying)
        }

        if (perDocBest.isEmpty()) {
            addFailureNotes(
                netSize = net.size, jsonCount = jsonCount, badJsonCount = badJsonCount,
                htmlCount = htmlCount, textCount = textCount, emptyCount = emptyCount,
                best = diagnosticBest, notes = notes
            )
            return emptyList()
        }

        // ---- 候选数组 → Course ----
        val courses = mutableListOf<Course>()
        var skipped = 0
        var missingWeeks = 0
        var missingSections = 0
        var unknownDay = 0
        for (cand in perDocBest) {
            for (i in 0 until cand.array.length()) {
                val obj = cand.array.optJSONObject(i) ?: continue
                val mapped = mapObject(obj, sections, keepUnknownDay)
                if (mapped == null) {
                    skipped++
                    continue
                }
                if (mapped.weekMissing) missingWeeks++
                if (mapped.sectionMissing) missingSections++
                if (mapped.course.dayOfWeek !in 1..7) unknownDay++
                courses.add(mapped.course)
            }
        }

        if (courses.isEmpty()) {
            val objectTotal = perDocBest.sumOf { it.objectCount }
            notes.add("接口数组里有 $objectTotal 个对象，但没有一条能同时拿到课程名和有效星期，已放弃")
            return emptyList()
        }

        val deduped = dedupe(courses)
        if (deduped.size != courses.size) {
            notes.add("合并重复课程 ${courses.size - deduped.size} 门")
        }
        if (skipped > 0) {
            notes.add("有 $skipped 个对象缺少课程名或有效星期，已跳过")
        }
        if (unknownDay > 0) {
            notes.add("有 $unknownDay 门课接口里没有可识别的星期字段，星期待定（会用页面网格位置补，补不到就跳过）")
        }
        if (missingWeeks > 0) {
            notes.add("部分课程缺少周次信息，已按 1-$DEFAULT_TOTAL_WEEKS 周填写，请在核对页确认")
        }
        if (missingSections > 0) {
            notes.add("有 $missingSections 门课接口里没给节次，已尝试按页面网格位置补齐；仍缺的暂按第 1-2 节填写")
        }
        if (perDocBest.size > 1) {
            notes.add("从 ${perDocBest.size} 个接口响应中合并解析")
        }

        // 成功提示放在最后：诊断弹窗里先展示细节、最后一句才是结论，符合阅读顺序
        val url = perDocBest.maxByOrNull { it.score }?.url ?: ""
        notes.add("已从接口数据解析 ${deduped.size} 门课程（接口：${shortUrl(url)}）")
        return deduped
    }

    /** 一个都没解析出来时，把「卡在哪一步」写清楚（诊断弹窗只有这些文字可用） */
    private fun addFailureNotes(
        netSize: Int,
        jsonCount: Int,
        badJsonCount: Int,
        htmlCount: Int,
        textCount: Int,
        emptyCount: Int,
        best: Candidate?,
        notes: MutableList<String>
    ) {
        val skippedParts = mutableListOf<String>()
        if (htmlCount > 0) skippedParts.add("$htmlCount 个看起来是 HTML 页面")
        if (textCount > 0) skippedParts.add("$textCount 个是纯文本/非 JSON")
        if (badJsonCount > 0) skippedParts.add("$badJsonCount 个 JSON 格式非法")
        if (emptyCount > 0) skippedParts.add("$emptyCount 个响应体为空")
        val skippedDesc = if (skippedParts.isEmpty()) "" else "（已跳过：" + skippedParts.joinToString("，") + "）"

        if (jsonCount == 0) {
            // 典型场景：没登录 → 抓到的全是 HTML 登录页；或者接口是 JSONP/加密文本
            notes.add("捕获到 $netSize 个接口响应，但响应体都不是 JSON$skippedDesc")
        } else {
            notes.add("捕获到 $netSize 个接口响应，但都不像课表数据$skippedDesc")
            if (best != null) {
                notes.add(
                    "最像课表的是 ${best.objectCount} 个对象的数组，但字段匹配得分只有 ${best.score}" +
                        "（需要 ≥ $MIN_SCORE：至少 2 个对象各带 2 个课程字段）"
                )
            }
        }
    }

    // ------------------------------------------------------------------ 全树扫描

    /** 递归扫 JSON 树，收集所有「大部分元素是对象」的数组作为候选 */
    private fun walk(
        node: Any?,
        depth: Int,
        budget: ScanBudget,
        url: String,
        out: MutableList<Candidate>
    ) {
        if (node == null) return
        if (depth > MAX_DEPTH || budget.nodes >= MAX_NODES) return
        budget.nodes++
        when (node) {
            is JSONArray -> {
                evaluateArray(node, url)?.let { out.add(it) }
                for (i in 0 until node.length()) {
                    if (budget.nodes >= MAX_NODES) return
                    walk(node.opt(i), depth + 1, budget, url, out)
                }
            }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    if (budget.nodes >= MAX_NODES) return
                    walk(node.opt(keys.next()), depth + 1, budget, url, out)
                }
            }
            // 标量没有可扫的子树
            else -> Unit
        }
    }

    /**
     * 给一个数组打分：`(含 ≥2 个可识别字段的对象数) * 10 + 出现的字段种类数`。
     * 这样既能排除「[{"foo":"bar"}]」这种无关数组，也能在「课表数组」和
     * 「只有课程名的通知数组」之间选出前者（后者每个对象只有 1 种字段，不得分）。
     */
    private fun evaluateArray(arr: JSONArray, url: String): Candidate? {
        if (arr.length() < 2) return null
        var objectCount = 0
        var recognizedObjects = 0
        val kinds = HashSet<Kind>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            objectCount++
            val fields = recognizedFields(o)
            if (fields.size >= 2) {
                recognizedObjects++
                kinds.addAll(fields.keys)
            }
        }
        // 「大部分元素是 JSONObject」：对象数必须过半，排除 [1,2,3] 或 [ ["a"], ["b"] ] 这类数组
        if (objectCount < 2 || objectCount * 2 <= arr.length()) return null
        return Candidate(url, arr, objectCount, recognizedObjects * 10 + kinds.size)
    }

    // ------------------------------------------------------------------ 对象 → Course

    /**
     * 广外新版教务（强智 njwhd 前端）课表接口 `/njwhd/student/curriculum` 的专用解析。
     *
     * 2026-11-05 用户抓包（classgrid_capture_*.json）里的真实字段：
     *   courseName / kch / teacherName / location / classWeek / classWeekDetails
     *   weekNoteDetail = "103,104"  → 星期1 的第 03、04 节（**星期藏在节次编码的第一位**）
     *   classTime      = "10304"    → 同上（星期1 + 03 + 04）
     *   maxClassTime   = "第二大节"、startTime / endTIme = "10:10" / "11:30"
     *   xqNumber / xqName = 校区编号 / 校区名（**不是**星期！这正是之前"全变周二"和"抓不到"的根源）
     *
     * 通用解析拿不到星期时会按 keepUnknownDay 收下再等 DOM 补，但这所学校 DOM 是 SPA（文本块只有 28 个），
     * 补不出来 → 全部丢弃。所以这里直接按编码还原，命中该格式就不再走通用逻辑。
     */
    private fun mapNjwhdCourse(obj: JSONObject): Mapped? {
        val name = WebScheduleParser.cleanName(obj.optString("courseName", ""))
        if (name.isEmpty()) return null
        val decoded = decodeNjwhdPlacement(
            obj.optString("weekNoteDetail", ""),
            obj.optString("classTime", "")
        ) ?: return null
        val weeks = njwhdWeeks(obj)
        val startWeek = (weeks?.startWeek ?: 1).coerceIn(1, MAX_WEEK)
        val endWeek = (weeks?.endWeek ?: DEFAULT_TOTAL_WEEKS).coerceIn(startWeek, MAX_WEEK)
        val course = Course(
            id = 0L,
            name = name,
            teacher = obj.optString("teacherName", "").trim(),
            classroom = obj.optString("location", "").trim(),
            dayOfWeek = decoded.first,
            startSection = decoded.second.first.coerceIn(1, MAX_SECTION),
            endSection = decoded.second.last.coerceIn(1, MAX_SECTION),
            startWeek = startWeek,
            endWeek = endWeek,
            weekType = weeks?.weekType ?: Course.WEEK_EVERY,
            colorIndex = 0,
            excludedWeeks = weeks?.excluded ?: emptySet(),
            code = obj.optString("kch", "").trim(),
            startTime = obj.optString("startTime", "").trim(),
            endTime = obj.optString("endTIme", obj.optString("endTime", "")).trim()
        )
        return Mapped(course = course, weekMissing = weeks == null, sectionMissing = false)
    }

    /** 把 "103,104" / "10304" 解成「星期 + 节次闭区间」；解不出返回 null（交给通用解析） */
    private fun decodeNjwhdPlacement(note: String, classTime: String): Pair<Int, IntRange>? {
        val noteTokens = note.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (noteTokens.isNotEmpty()) {
            var day = -1
            val secs = ArrayList<Int>()
            for (token in noteTokens) {
                if (token.length < 3 || token.any { !it.isDigit() }) { secs.clear(); break }
                val d = token[0] - '0'
                if (d !in 1..7) { secs.clear(); break }
                if (day == -1) day = d else if (day != d) { secs.clear(); break }
                // 末两位是节次："103" -> 03、"214" -> 14
                val sec = token.substring(token.length - 2).toIntOrNull() ?: 0
                if (sec !in 1..MAX_SECTION) { secs.clear(); break }
                secs.add(sec)
            }
            if (day in 1..7 && secs.isNotEmpty()) {
                return day to (secs.min()..secs.max())
            }
        }
        // classTime 兜底："10304" = 星期1 + 03 + 04
        val ct = classTime.trim()
        if (ct.length >= 3 && ct.all { it.isDigit() }) {
            val day = ct[0] - '0'
            if (day in 1..7) {
                val rest = ct.substring(1)
                if (rest.length % 2 == 0) {
                    val secs = ArrayList<Int>()
                    var i = 0
                    while (i < rest.length) {
                        val sec = rest.substring(i, i + 2).toIntOrNull() ?: -1
                        if (sec !in 1..MAX_SECTION) { secs.clear(); break }
                        secs.add(sec)
                        i += 2
                    }
                    if (secs.isNotEmpty()) return day to (secs.min()..secs.max())
                }
            }
        }
        return null
    }

    /** njwhd 的周次：优先 classWeekDetails=",2,3,4,...," ；没有就用 classWeek="2-4,6-16" */
    private fun njwhdWeeks(obj: JSONObject): WebScheduleParser.WeeksSpec? {
        val details = obj.optString("classWeekDetails", "")
        val set = LinkedHashSet<Int>()
        for (raw in details.split(',')) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            val v = t.toIntOrNull() ?: continue
            if (v in 1..MAX_WEEK) set.add(v)
        }
        if (set.isNotEmpty()) {
            val start = set.min()
            val end = set.max()
            val all = (start..end).toSet()
            val excluded = all - set
            return if (excluded.isEmpty()) WebScheduleParser.WeeksSpec(start, end, Course.WEEK_EVERY, emptySet())
            else WebScheduleParser.WeeksSpec(start, end, Course.WEEK_EVERY, excluded)
        }
        val classWeek = obj.optString("classWeek", "").trim()
        if (classWeek.isEmpty()) return null
        // "2-16" / "2-4,6-16" → 补上「周」字交给通用周次解析
        return WebScheduleParser.parseWeeks(if (classWeek.contains('周')) classWeek else classWeek + "周")
    }

    private fun mapObject(
        obj: JSONObject,
        sections: List<SectionTimes.Section>,
        keepUnknownDay: Boolean = false
    ): Mapped? {
        // 广外新版教务（强智 njwhd）的课表对象：星期藏在节次编码里，通用字段匹配认不出，先走专用解析
        mapNjwhdCourse(obj)?.let { return it }

        val fields = recognizedFields(obj)

        // 课程名：清洗后为空就丢弃。宁可少一门课，也不能把 "12345"（课程代码）当课名导进去。
        val name = WebScheduleParser.cleanName(bestValue(fields, Kind.NAME))
        if (name.isEmpty()) return null

        // 星期：接口里可能是 1 / "1" / "周一" / "星期一"；认不出就丢弃。
        // 星期错会让整门课错到另一列，比丢一门更难被发现，所以这里不做兜底猜测。
        // 星期：认不出时，默认丢弃（错误星期比丢课更难被发现）。
        // 但 v1.36.0 真机反馈「有用户接口里根本没有星期字段」→ 整张表 0 门课、什么都抓不到。
        // 所以保留一个 keepUnknownDay 模式：先按 dayOfWeek = 0 收下来，交给上层用页面网格位置补星期。
        val parsedDay = parseDay(bestValue(fields, Kind.DAY))
        if (parsedDay !in 1..7 && !keepUnknownDay) return null
        val day = if (parsedDay in 1..7) parsedDay else 0

        // 节次：接口编码五花八门（"01-02" / "0102" / "第1-2节" / "1,2" / "第7大节"），统一解析成闭区间。
        // 解析不出来时**不要**在这里猜 1-2：那正是 v1.32.0 把「国家安全教育(第七大节 14-15)」导成
        // 「第1-2节」的原因。改成 startSection/endSection = 0 作为「未知」标记，由上层用页面网格
        // 位置（DOM 合成表）补齐；补不到才在最后兜底成 1-2 并提示用户核对。
        val sectionRaw = bestValue(fields, Kind.SECTION)
        val sectionRange = parseSections(sectionRaw, sections) ?: scanSectionsFromAnyValue(obj, sections)
        val range = sectionRange

        // 周次：缺失不丢课程，按 1-20 周兜底并提示核对（用户可从核对页改）
        val weeks = parseWeeksValue(bestValue(fields, Kind.WEEKS))
        val startWeek = (weeks?.startWeek ?: 1).coerceIn(1, MAX_WEEK)
        val endWeek = (weeks?.endWeek ?: DEFAULT_TOTAL_WEEKS).coerceIn(startWeek, MAX_WEEK)

        return Mapped(
            course = Course(
                id = 0L, // 外层重新发号，避免与已保存课程 id 撞车
                name = name,
                teacher = bestValue(fields, Kind.TEACHER),
                classroom = bestValue(fields, Kind.ROOM),
                dayOfWeek = day,
                startSection = range?.first ?: 0,
                endSection = range?.last ?: 0,
                startWeek = startWeek,
                endWeek = endWeek,
                weekType = weeks?.weekType ?: Course.WEEK_EVERY,
                colorIndex = 0,
                excludedWeeks = weeks?.excluded ?: emptySet(),
                code = bestValue(fields, Kind.CODE)
            ),
            weekMissing = weeks == null,
            sectionMissing = sectionRange == null
        )
    }

    /**
     * 最后的节次兜底：当我们没认出任何一个「节次」字段名时，把对象里所有字符串值都当成
     * 潜在节次文本扫一遍。真实接口里见过把节次塞进 `remark`/`sjms`/"上课时间" 这类
     * 自定义字段的情况；只认含「节/大节」或 `HH:MM-HH:MM` 形态的文本，避免把课名/教室误当节次。
     */
    private fun scanSectionsFromAnyValue(obj: JSONObject, sections: List<SectionTimes.Section>): IntRange? {
        val keys = obj.keys()
        while (keys.hasNext()) {
            val v = obj.opt(keys.next()) ?: continue
            val text = when (v) {
                is String -> v
                is JSONObject -> v.optString("value") + " " + v.optString("name")
                else -> continue
            }
            if (text.length !in 2..80) continue
            val looksLikeSection = text.contains("节") || text.contains("大节") ||
                Regex("""\d{1,2}\s*[:：]\s*\d{2}\s*[-~—－至]""").containsMatchIn(text)
            if (!looksLikeSection) continue
            WebScheduleParser.sectionsFromLabel(text, sections)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------------ key / value 宽容匹配

    /**
     * key 归一化：小写 + 去掉 `_` `-` 空格（含全角空格），中文原样保留。
     * 接口里 `KCMC`、`kc_mc`、`kc-mc`、"课程 名称" 都是同一回事。
     */
    private fun normalizeKey(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            when (ch) {
                '_', '-', ' ', '\u3000' -> Unit
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString()
    }

    /**
     * 找出 key 最可能的字段种类。
     *
     * 质量分设计：
     * - 完全相等 1000+长度：`课程代码` 精确命中 CODE，不会因为 NAME 的「课程」包含匹配被抢走；
     * - 前缀/后缀 500+长度：`kcmcstr` 命中 NAME 的 `kcmc`；
     * - 包含 100+长度：`课程名称展示` 命中 NAME。
     * 同一 key 命中多个种类时取质量最高者（长度参与比较，长别名更具体、更可信）。
     */
    private fun matchKind(normalizedKey: String): FieldMatch? {
        if (normalizedKey.isEmpty()) return null
        var bestKind: Kind? = null
        var bestQuality = Int.MIN_VALUE
        for ((kind, aliases) in ALIASES) {
            for (alias in aliases) {
                val quality = when {
                    normalizedKey == alias -> 1000 + alias.length
                    normalizedKey.startsWith(alias) || normalizedKey.endsWith(alias) -> 500 + alias.length
                    normalizedKey.contains(alias) -> 100 + alias.length
                    else -> Int.MIN_VALUE
                }
                if (quality > bestQuality) {
                    bestQuality = quality
                    bestKind = kind
                }
            }
        }
        return bestKind?.let { FieldMatch(it, bestQuality) }
    }

    /**
     * 一个 JSONObject 里所有可识别的课程字段：种类 → (匹配质量, 值) 列表。
     * 同一种类可能有多个 key（kcmc + kcmcstr），取值时按质量排序，见 [bestValue]。
     * 空值直接忽略：接口常把所有字段都平铺出来，空字符串不代表字段存在。
     */
    private fun recognizedFields(obj: JSONObject): Map<Kind, List<Pair<Int, String>>> {
        val out = LinkedHashMap<Kind, MutableList<Pair<Int, String>>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val normalized = normalizeKey(key)
            // 学期类字段直接跳过（见 IGNORED_KEYS：`xq` = 学期编号，不是星期几）
            if (normalized in IGNORED_KEYS) continue
            val match = matchKind(normalized) ?: continue
            val text = asText(obj.opt(key)).trim()
            if (text.isEmpty()) continue
            out.getOrPut(match.kind) { mutableListOf() }.add(match.quality to text)
        }
        return out
    }

    /** 同一种字段有多个 key 时，取匹配质量最高的非空值 */
    private fun bestValue(fields: Map<Kind, List<Pair<Int, String>>>, kind: Kind): String {
        val list = fields[kind] ?: return ""
        return list.sortedByDescending { it.first }.firstOrNull()?.second ?: ""
    }

    /**
     * 值 → 文本。接口里的值不一定是字符串：可能是数字、布尔，也可能被包一层
     * （`{"value":"..."}` / `{"name":"..."}` / `{"content":"..."}`，金智/门户接口很常见），
     * 周次还可能直接给数组 `[1,2,3,...16]`（拼成 "1,2,3" 交给周次解析）。
     */
    private fun asText(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        is Number, is Boolean -> value.toString()
        is JSONArray -> (0 until value.length())
            .mapNotNull { i -> asText(value.opt(i)).takeIf { it.isNotBlank() } }
            .joinToString(",")
        is JSONObject -> {
            var found = ""
            for (k in listOf("value", "name", "content", "text", "mc", "str")) {
                val v = asText(value.opt(k))
                if (v.isNotBlank()) {
                    found = v
                    break
                }
            }
            found
        }
        else -> value.toString()
    }

    // ------------------------------------------------------------------ 值解析

    /**
     * 星期值 → 1..7。兼容数字（1 / "1" / "01" / "1.0"）、中文标签（周一 / 星期一 / 礼拜二）。
     * 中文标签复用 [WebScheduleParser.dayOfWeekOf]，避免维护第二份「星期表」。
     */
    private fun parseDay(raw: String): Int {
        val t = raw.trim()
        if (t.isEmpty()) return 0
        Regex("""^(\d{1,2})(?:\.0+)?$""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let {
            return if (it in 1..7) it else 0
        }
        val byLabel = WebScheduleParser.dayOfWeekOf(t)
        if (byLabel in 1..7) return byLabel
        // "1周" 这种倒装写法（少见但存在）
        Regex("""^([1-7])\s*周$""").find(t)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return 0
    }

    /**
     * 节次值 → 闭区间（保证 end ≥ start，且夹在 1..15）。
     *
     * 接口里的节次至少见过这几种形态：
     *  - "01-02"（强智，带前导零）
     *  - "0102"（强智 4 位编码，必须按两位切，不能当 102 节）
     *  - "第1-2节" / "1-2节" / "3-5" / "1,2"（人读标签）
     *  - "第二大节"（大节编码，映射成 3-5 这种小节区间）
     * 纯编码自己解析，其余（含「第N大节」、钟点时间）交给 [WebScheduleParser.sectionsFromLabel]。
     */
    private fun parseSections(raw: String, sections: List<SectionTimes.Section>): IntRange? {
        val t = raw.trim()
        if (t.isEmpty()) return null

        // 先做纯编码解析：去掉「第/节/括号/中文逗号」，只留数字与分隔符
        val compact = t
            .replace(" ", "")
            .replace("第", "")
            .replace("节", "")
            .replace("小节", "")
            .replace("（", "(")
            .replace("）", ")")
            .replace("，", ",")
            .replace("、", ",")
            .trim('(', ')', '[', ']', '{', '}', '【', '】')

        // "1-2" / "01-02" / "1,2" / "1~2" / "1至2"：取所有数字的上下界
        if (compact.matches(Regex("""^\d{1,2}(?:[-~—－至,/]+\d{1,2})+$"""))) {
            val nums = Regex("""\d{1,2}""").findAll(compact).mapNotNull { it.value.toIntOrNull() }.toList()
            if (nums.isNotEmpty()) return clampRange(nums.min(), nums.max())
        }
        // "1" / "03"（单节）
        if (compact.matches(Regex("""^\d{1,2}$"""))) {
            val n = compact.toIntOrNull() ?: return null
            return clampRange(n, n)
        }
        // 4 位纯数字 "0102" → 01,02（强智小节课号按两位拼接，这是真机抓包里最常见的坑）
        if (compact.matches(Regex("""^\d{4}$"""))) {
            val a = compact.substring(0, 2).toIntOrNull() ?: return null
            val b = compact.substring(2, 4).toIntOrNull() ?: return null
            if (a in 1..MAX_SECTION && b in 1..MAX_SECTION) return clampRange(a, b)
        }

        // 带「节/大节/钟点」的标签：复用表格解析器（"第1-2节"、"第二大节"、"08:30-09:10"）
        WebScheduleParser.sectionsFromLabel(t, sections)?.let {
            return clampRange(it.first, it.last)
        }
        // 兜底：任意文本里出现 "1-2" 这类区间就取它（如 "第1-2节(南G211)"）
        val range = Regex("""(\d{1,2})\s*[-~—－至]\s*(\d{1,2})""").find(t) ?: return null
        val a = range.groupValues[1].toIntOrNull() ?: return null
        val b = range.groupValues[2].toIntOrNull() ?: return null
        return clampRange(a, b)
    }

    /** 节次归一化：夹到 1..15，且保证 end ≥ start（倒序输入不能原样传出去） */
    private fun clampRange(a: Int, b: Int): IntRange {
        val lo = minOf(a, b).coerceIn(1, MAX_SECTION)
        val hi = maxOf(a, b).coerceIn(lo, MAX_SECTION)
        return lo..hi
    }

    /**
     * 周次值 → [WebScheduleParser.WeeksSpec]（含起止周、单双周、非连续周的排除集合）。
     * 优先交给 [WebScheduleParser.parseWeeks]；接口只给裸数字（"1-16" / "1" / "1,2,3"）
     * 或给数组（JSONArray 已被拼成 "1,2,3"）时，用数字集合自己还原区间。
     */
    private fun parseWeeksValue(raw: String): WebScheduleParser.WeeksSpec? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        WebScheduleParser.parseWeeks(t)?.let { return it }

        // 裸数字兜底：抓出所有 1..30 的数字，按「最小-最大 + 排除集合」还原
        val nums = Regex("""\d{1,2}""").findAll(t)
            .mapNotNull { it.value.toIntOrNull() }
            .filter { it in 1..MAX_WEEK }
            .toSortedSet()
        if (nums.isEmpty()) return null
        val start = nums.first()
        val end = nums.last()
        val excluded: Set<Int> =
            if (nums.size == end - start + 1) emptySet()
            else (start..end).filter { it !in nums }.toSet()
        val weekType = when {
            t.contains("单") -> Course.WEEK_ODD
            t.contains("双") -> Course.WEEK_EVEN
            else -> Course.WEEK_EVERY
        }
        return WebScheduleParser.WeeksSpec(start, end, weekType, excluded)
    }

    // ------------------------------------------------------------------ 去重 / 提示

    /**
     * 去重键按需求固定为「课名+星期+节次+起始周+教师+教室」：
     * 同一门课被多个接口响应（或 fetch/XHR 双钩子）抓到时会重复，保留第一条即可。
     */
    private fun dedupe(list: List<Course>): List<Course> {
        val seen = HashSet<String>(list.size)
        val out = ArrayList<Course>(list.size)
        for (c in list) {
            val key = "${c.name}|${c.dayOfWeek}|${c.startSection}|${c.endSection}|" +
                "${c.startWeek}|${c.teacher}|${c.classroom}"
            if (seen.add(key)) out.add(c)
        }
        return out
    }

    /** notes 里的 URL 截断：空 URL 用「未知接口」，超长截到 80 字 */
    private fun shortUrl(url: String): String {
        val t = url.trim().ifEmpty { "未知接口" }
        return if (t.length <= URL_NOTE_LIMIT) t else t.substring(0, URL_NOTE_LIMIT)
    }
}
