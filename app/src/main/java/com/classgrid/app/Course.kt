package com.classgrid.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * 进程内唯一 id 生成器。
 *
 * 历史实现直接用 `System.currentTimeMillis()` 当默认 id，同一毫秒内连续构造就会撞车——
 * 一次截图 OCR / 网格识别产出 ≥2 门课时**几乎必然**发生（解析循环是紧循环）。
 * 撞车的后果不是"难看"而是数据错：
 *  - MainActivity 删除用 `filter { it.id != course.id }` → 连删多条；
 *  - CourseEditActivity 用 `indexOfFirst { it.id == ... }` → 编辑串台，改 A 实际改了 B。
 * 现在改为「毫秒时间戳 × 1000 + 进程内自增」：单调递增、单进程内绝不重复，
 * 且比历史数据里的毫秒时间戳大 3 个数量级，不会与已存在的老 id 重叠（无需种子初始化）。
 */
object UniqueId {
    private var last = 0L

    @Synchronized
    fun next(): Long {
        val now = System.currentTimeMillis() * 1000L
        last = if (now > last) now else last + 1L
        return last
    }
}

/**
 * 课程数据模型
 */
data class Course(
    val id: Long = UniqueId.next(),
    val name: String,
    val teacher: String = "",
    val classroom: String = "",
    val dayOfWeek: Int = 1,        // 1=周一 ... 7=周日
    val startSection: Int = 1,
    val endSection: Int = 2,
    val startWeek: Int = 1,
    val endWeek: Int = 16,
    val weekType: Int = WEEK_EVERY, // 每周 / 单周 / 双周
    val colorIndex: Int = 0,
    /** startWeek..endWeek 范围内不上课的周（用于表达非连续周次，如 2-4,6-16周） */
    val excludedWeeks: Set<Int> = emptySet(),
    /** 课程代码（OCR 提取，如 GU20041；可空） */
    val code: String = "",
    /** 上课时钟时间（OCR 提取的辅助信息，如 10:10 / 11:30；可空） */
    val startTime: String = "",
    val endTime: String = ""
) {
    companion object {
        const val WEEK_EVERY = 0
        const val WEEK_ODD = 1
        const val WEEK_EVEN = 2

        fun fromJson(o: JSONObject): Course = Course(
            id = o.optLong("id", UniqueId.next()),
            name = o.optString("name", ""),
            teacher = o.optString("teacher", ""),
            classroom = o.optString("classroom", ""),
            dayOfWeek = o.optInt("dayOfWeek", 1),
            startSection = o.optInt("startSection", 1),
            endSection = o.optInt("endSection", 2),
            startWeek = o.optInt("startWeek", 1),
            endWeek = o.optInt("endWeek", 16),
            weekType = o.optInt("weekType", WEEK_EVERY),
            colorIndex = o.optInt("colorIndex", 0),
            excludedWeeks = o.optJSONArray("excludedWeeks")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optInt(it, -1).takeIf { w -> w > 0 } }.toSet()
            } ?: emptySet(),
            code = o.optString("code", ""),
            startTime = o.optString("startTime", ""),
            endTime = o.optString("endTime", "")
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("teacher", teacher)
        put("classroom", classroom)
        put("dayOfWeek", dayOfWeek)
        put("startSection", startSection)
        put("endSection", endSection)
        put("startWeek", startWeek)
        put("endWeek", endWeek)
        put("weekType", weekType)
        put("colorIndex", colorIndex)
        // excludedWeeks 仅在非空时写入，保持 JSON 简洁（空值往返后仍为空集，序列化对称）
        if (excludedWeeks.isNotEmpty()) {
            val arr = JSONArray()
            excludedWeeks.sorted().forEach { arr.put(it) }
            put("excludedWeeks", arr)
        }
        put("code", code)
        put("startTime", startTime)
        put("endTime", endTime)
    }

    /** 该课程在指定周是否上课 */
    fun activeInWeek(week: Int): Boolean {
        if (week < startWeek || week > endWeek) return false
        if (week in excludedWeeks) return false
        return when (weekType) {
            WEEK_ODD -> week % 2 == 1
            WEEK_EVEN -> week % 2 == 0
            else -> true
        }
    }

    /**
     * 将 [startWeek, endWeek] 排除 [excludedWeeks] 后重组为连续段列表。
     * 例如 2..16 排除 {5} 得到 [(2,4),(6,16)]。
     */
    private fun weekSegments(): List<Pair<Int, Int>> {
        val active = (startWeek..endWeek).filter { it !in excludedWeeks }
        if (active.isEmpty()) return emptyList()
        val segments = mutableListOf<Pair<Int, Int>>()
        var segStart = active.first()
        var prev = active.first()
        for (w in active.drop(1)) {
            if (w == prev + 1) {
                prev = w
            } else {
                segments.add(segStart to prev)
                segStart = w
                prev = w
            }
        }
        segments.add(segStart to prev)
        return segments
    }

    /** 周次描述文字，如 "2-4,6-16周" / "1-16周" / "1-16周 · 单周" */
    fun weekDesc(): String = buildString {
        val segments = weekSegments()
        if (segments.isEmpty()) {
            // 极端情况：全部周被排除，退化回原区间描述
            append("$startWeek-$endWeek 周")
        } else {
            append(segments.joinToString(",") { (a, b) -> if (a == b) "$a" else "$a-$b" })
            append("周")
        }
        when (weekType) {
            WEEK_ODD -> append(" · 单周")
            WEEK_EVEN -> append(" · 双周")
        }
    }
}

/** 课程列表与 JSON 互转 */
object CourseJson {
    fun toJsonArray(courses: List<Course>): JSONArray =
        JSONArray().apply { courses.forEach { put(it.toJson()) } }

    fun fromJsonArray(arr: JSONArray): List<Course> {
        val list = mutableListOf<Course>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name", "").isNotEmpty()) {
                list.add(Course.fromJson(o))
            }
        }
        return list
    }

    fun encode(courses: List<Course>): String = toJsonArray(courses).toString()

    /**
     * 解析失败返回 null（区分「空文件/空列表」与「文件损坏」）。
     * 历史实现内部 catch 后返回 emptyList()，导致 CourseStore.load 里
     * 「损坏文件改名 courses_corrupt.json 留底」的分支永远进不去，用户看到的是"课表突然空了"。
     */
    fun decodeOrNull(text: String): List<Course>? = try {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) emptyList()
        else {
            val arr = when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> JSONObject(trimmed).optJSONArray("courses") ?: JSONArray()
            }
            fromJsonArray(arr)
        }
    } catch (e: Exception) {
        null
    }

    fun decode(text: String): List<Course> = decodeOrNull(text) ?: emptyList()
}
