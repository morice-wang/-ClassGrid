package com.classgrid.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 作息时间库（v1.48.0「全国高校适配」）
 *
 * 一份「作息」= 若干节次时间 + 名字，全局保存，可在所有课表之间复用：
 * - 每张课表可绑定其中一份作息；没绑定时跟随「当前作息」。
 * - 内置 6 所广东高校预设，导入后是一份独立副本，可改名、改时间、删除。
 * - 老的单份配置（classgrid_prefs / section_times）在首次使用时会自动迁移成一份作息，不丢数据。
 *
 * 这里只放数据与纯逻辑（便于单测），不碰任何 View。
 */
object ScheduleProfiles {

    data class Profile(
        val id: String,
        val name: String,
        val sections: List<SectionTimes.Section>,
        val presetKey: String? = null,
        val createdAt: Long = 0L,
    )

    /** 内置预设施：导入时复制成 [Profile]，presetKey 记住来源，便于「恢复默认」 */
    data class Preset(
        val key: String,
        val name: String,
        val school: String,
        val note: String,
        val sections: List<SectionTimes.Section>,
    )

    private const val PREFS = "classgrid_prefs"
    private const val KEY_LIST = "schedule_profiles_v1"
    private const val KEY_ACTIVE = "schedule_profile_active"
    private const val KEY_BINDING = "schedule_profile_binding"
    private const val LEGACY_KEY = "section_times"

    const val MAX_NAME_LEN = 16
    const val MIN_SECTIONS = 1
    const val MAX_SECTIONS = 20
    const val DEFAULT_DURATION = 40
    const val DEFAULT_BREAK = 10

    // ==================================================================
    // 时间工具
    // ==================================================================

    /** 分钟数 → "HH:MM"（超出 24 小时回绕，避免异常数据写入非法字符串） */
    fun hhmm(minutes: Int): String {
        val m = ((minutes % 1440) + 1440) % 1440
        return String.format("%02d:%02d", m / 60, m % 60)
    }

    fun startMinutes(s: SectionTimes.Section): Int? = SectionTimes.toMinutes(s.start)

    fun endMinutes(s: SectionTimes.Section): Int? = SectionTimes.toMinutes(s.end)

    /** 第 index 节与上一节之间的课间分钟数；第 0 节返回 null */
    fun gaps(list: List<SectionTimes.Section>): List<Int?> = list.mapIndexed { i, s ->
        if (i == 0) null
        else {
            val prevEnd = endMinutes(list[i - 1])
            val curStart = startMinutes(s)
            if (prevEnd == null || curStart == null) null else curStart - prevEnd
        }
    }

    /** 一节课的时长（分钟），数据非法返回 null */
    fun duration(s: SectionTimes.Section): Int? {
        val a = startMinutes(s) ?: return null
        val b = endMinutes(s) ?: return null
        return b - a
    }

    /** 表格里显示的「间隔」文案 */
    fun gapLabel(prevEnd: String?, start: String): String {
        val a = SectionTimes.toMinutes(prevEnd ?: return "—")
        val b = SectionTimes.toMinutes(start) ?: return "—"
        val d = b - (a ?: return "—")
        return when {
            d > 0 -> "${d}分"
            d == 0 -> "连堂"
            d < 0 -> "重叠"
            else -> "—"
        }
    }

    // ==================================================================
    // 推算（批量填开始时间 → 自动算结束时间与课间）
    // ==================================================================

    /** 按「首节开始 + 每节时长 + 课间」铺满 count 节；起始时间非法时返回空列表 */
    fun derive(firstStart: String, count: Int, durationMinutes: Int, breakMinutes: Int): List<SectionTimes.Section> {
        val start = SectionTimes.toMinutes(firstStart) ?: return emptyList()
        if (count <= 0) return emptyList()
        val dur = durationMinutes.coerceIn(5, 180)
        val gap = breakMinutes.coerceIn(0, 120)
        val out = ArrayList<SectionTimes.Section>(count)
        var s = start
        for (i in 0 until count) {
            out.add(SectionTimes.Section(hhmm(s), hhmm(s + dur)))
            s += dur + gap
        }
        return out
    }

    /** 只改开始时间：把第 index 节改成 start，并按节奏顺延后面所有节次 */
    fun cascade(list: List<SectionTimes.Section>, index: Int, start: String, durationMinutes: Int, breakMinutes: Int): List<SectionTimes.Section> {
        val s0 = SectionTimes.toMinutes(start) ?: return list
        if (index !in list.indices) return list
        val dur = durationMinutes.coerceIn(5, 180)
        val gap = breakMinutes.coerceIn(0, 120)
        val out = list.toMutableList()
        var s = s0
        for (i in index until out.size) {
            out[i] = SectionTimes.Section(hhmm(s), hhmm(s + dur))
            s += dur + gap
        }
        return out
    }

    /** 改节数时尽量保住已有数据：变多按上一节节奏往后排，变少砍掉末尾 */
    fun resize(
        list: List<SectionTimes.Section>,
        count: Int,
        durationMinutes: Int = DEFAULT_DURATION,
        breakMinutes: Int = DEFAULT_BREAK
    ): List<SectionTimes.Section> {
        val target = count.coerceIn(MIN_SECTIONS, MAX_SECTIONS)
        if (target == list.size) return list
        val out = list.toMutableList()
        if (target < out.size) {
            while (out.size > target) out.removeAt(out.size - 1)
            return out
        }
        if (out.isEmpty()) return derive("08:30", target, durationMinutes, breakMinutes)
        val dur = out.last().let { duration(it) } ?: durationMinutes
        val gap = gaps(out).lastOrNull()
        var last = endMinutes(out.last()) ?: return out
        while (out.size < target) {
            val s = last + (gap ?: DEFAULT_BREAK)
            out.add(SectionTimes.Section(hhmm(s), hhmm(s + dur)))
            last = s + dur
        }
        return out
    }

    // ==================================================================
    // 校验与摘要
    // ==================================================================

    /** 校验整份作息；返回 null 表示通过，否则返回第一条错误的提示文案 */
    fun validate(list: List<SectionTimes.Section>): String? {
        if (list.isEmpty()) return "至少要有一节"
        for (i in list.indices) {
            val s = list[i]
            val a = startMinutes(s) ?: return "第 ${i + 1} 节开始时间格式不对"
            val b = endMinutes(s) ?: return "第 ${i + 1} 节结束时间格式不对"
            if (b <= a) return "第 ${i + 1} 节结束时间要晚于开始时间"
            if (i > 0) {
                val prevEnd = endMinutes(list[i - 1]) ?: return "第 $i 节结束时间格式不对"
                if (a < prevEnd) return "第 ${i + 1} 节开始时间早于第 $i 节结束时间"
            }
        }
        return null
    }

    /** "15 节 · 08:30–21:20" */
    fun summary(list: List<SectionTimes.Section>): String {
        if (list.isEmpty()) return "未设置"
        return "${list.size} 节 · ${list.first().start}–${list.last().end}"
    }

    /** 名字校验：去空白、折叠空格，超长截断；空名返回 null */
    fun cleanName(raw: String?): String? {
        val t = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (t.isEmpty()) return null
        return if (t.length > MAX_NAME_LEN) t.take(MAX_NAME_LEN).trim() else t
    }

    /** 重名自动加 (2)/(3)… */
    fun uniqueName(name: String, existing: List<String>): String {
        val base = cleanName(name) ?: "作息"
        if (existing.none { it == base }) return base
        var i = 2
        while (i < 100) {
            val candidate = "$base($i)"
            if (existing.none { it == candidate }) return candidate
            i++
        }
        return "$base(${(System.currentTimeMillis() % 1000)})"
    }

    // ==================================================================
    // 序列化（纯函数，便于单测）
    // ==================================================================

    fun encodeSections(list: List<SectionTimes.Section>): String {
        val arr = JSONArray()
        list.forEach { s -> arr.put(JSONObject().apply { put("start", s.start); put("end", s.end) }) }
        return arr.toString()
    }

    /** 解析失败或为空返回 null */
    fun decodeSections(json: String?): List<SectionTimes.Section>? {
        if (json.isNullOrBlank()) return null
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<SectionTimes.Section>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val s = o.optString("start")
                val e = o.optString("end")
                if (SectionTimes.toMinutes(s) != null && SectionTimes.toMinutes(e) != null) {
                    out.add(SectionTimes.Section(s, e))
                }
            }
            out.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    fun encodeProfiles(list: List<Profile>): String {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("sections", JSONArray(encodeSections(p.sections)))
                put("presetKey", p.presetKey ?: JSONObject.NULL)
                put("createdAt", p.createdAt)
            })
        }
        return arr.toString()
    }

    fun decodeProfiles(json: String?): List<Profile> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<Profile>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val sections = decodeSections(o.optString("sections", "[]")) ?: continue
                if (id.isEmpty()) continue
                out.add(
                    Profile(
                        id = id,
                        name = cleanName(o.optString("name")) ?: "作息",
                        sections = sections.take(MAX_SECTIONS),
                        presetKey = if (o.isNull("presetKey")) null else o.optString("presetKey").ifEmpty { null },
                        createdAt = o.optLong("createdAt", 0L),
                    )
                )
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encodeBinding(map: Map<String, String>): String {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    fun decodeBinding(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(json)
            val out = HashMap<String, String>()
            o.keys().forEach { k ->
                val v = o.optString(k)
                if (v.isNotEmpty()) out[k] = v
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ==================================================================
    // 预设（数据来源见 tools/school_bells_research.md）
    // ==================================================================

    private fun sec(range: String): SectionTimes.Section {
        val parts = range.split("-")
        return SectionTimes.Section(parts[0].trim(), parts.getOrElse(1) { parts[0] }.trim())
    }

    private fun preset(key: String, name: String, school: String, note: String, vararg ranges: String): Preset =
        Preset(key, name, school, note, ranges.map { sec(it) })

    /** 广外默认 15 节（老版本的硬编码数据，保持完全一致，避免迁移后跳变） */
    val GDUFS: List<SectionTimes.Section> = listOf(
        "08:30-09:10", "09:10-09:50", "10:10-10:50", "10:50-11:30", "11:35-12:15",
        "12:30-13:10", "13:10-13:50", "14:00-14:40", "14:40-15:20", "15:40-16:20",
        "16:20-17:00", "18:30-19:10", "19:10-19:50", "20:00-20:40", "20:40-21:20"
    ).map { sec(it) }

    private val PRESETS: List<Preset> = listOf(
        Preset("gdufs", "广外作息", "广东外语外贸大学", "15 节 · 8:30 开始 · 沿用旧版默认", GDUFS),
        preset(
            "gdufs_6", "广外作息（6 大节）", "广东外语外贸大学",
            "12 小节 · 按官方 6 大节划分 · 不含中午课",
            "08:30-09:10", "09:10-09:50", "10:10-10:50", "10:50-11:30",
            "14:00-14:40", "14:40-15:20", "15:40-16:20", "16:20-17:00",
            "18:30-19:10", "19:10-19:50", "20:10-20:45", "20:45-21:20"
        ),
        preset(
            "gdut", "广工作息（大学城）", "广东工业大学",
            "12 节 · 8:30 开始 · 45 分钟一节",
            "08:30-09:15", "09:20-10:05", "10:25-11:10", "11:15-12:00",
            "13:50-14:35", "14:40-15:25", "15:30-16:15", "16:30-17:15", "17:20-18:05",
            "18:30-19:15", "19:20-20:05", "20:10-20:55"
        ),
        preset(
            "gdut_df", "广工作息（东风路/龙洞）", "广东工业大学",
            "12 节 · 比大学城整体早 15 分钟",
            "08:15-09:00", "09:05-09:50", "10:10-10:55", "11:00-11:45",
            "13:35-14:20", "14:25-15:10", "15:15-16:00", "16:15-17:00", "17:05-17:50",
            "18:15-19:00", "19:05-19:50", "19:55-20:40"
        ),
        preset(
            "scut", "华工作息（五山）", "华南理工大学",
            "11 节 · 8:00 开始 · 45 分钟一节",
            "08:00-08:45", "08:55-09:40", "10:00-10:45", "10:55-11:40",
            "14:30-15:15", "15:25-16:10", "16:20-17:05", "17:15-18:00",
            "19:00-19:45", "19:55-20:40", "20:50-21:35"
        ),
        preset(
            "scut_gzic", "华工作息（大学城/国际）", "华南理工大学",
            "11 节 · 8:50 开始 · 45 分钟一节",
            "08:50-09:35", "09:40-10:25", "10:40-11:25", "11:30-12:15",
            "14:00-14:45", "14:50-15:35", "15:45-16:30", "16:35-17:20",
            "19:00-19:45", "19:55-20:40", "20:50-21:35"
        ),
        preset(
            "scnu", "华师作息（石牌）", "华南师范大学",
            "10 节 · 8:30 开始 · 40 分钟一节",
            "08:30-09:10", "09:20-10:00", "10:20-11:00", "11:10-11:50",
            "14:30-15:10", "15:20-16:00", "16:10-16:50", "17:00-17:40",
            "19:00-19:40", "19:50-20:30"
        ),
        preset(
            "scnu_uc", "华师作息（大学城）", "华南师范大学",
            "10 节 · 下午 14:00 开始",
            "08:30-09:10", "09:20-10:00", "10:20-11:00", "11:10-11:50",
            "14:00-14:40", "14:50-15:30", "15:40-16:20", "16:30-17:10",
            "19:00-19:40", "19:50-20:30"
        ),
        preset(
            "scnu_nh", "华师作息（南海）", "华南师范大学",
            "11 节 · 8:15 开始 · 40 分钟一节",
            "08:15-08:55", "09:05-09:45", "09:55-10:35", "10:45-11:25",
            "14:00-14:40", "14:50-15:30", "15:40-16:20", "16:30-17:10",
            "19:40-20:20", "20:30-21:10", "21:20-22:00"
        ),
        preset(
            "sysu", "中大作息", "中山大学",
            "11 节 · 8:00 开始 · 45 分钟一节",
            "08:00-08:45", "08:55-09:40", "10:10-10:55", "11:05-11:50",
            "14:20-15:05", "15:15-16:00", "16:30-17:15", "17:25-18:10",
            "19:00-19:45", "19:55-20:40", "20:50-21:35"
        ),
        preset(
            "szu", "深大作息", "深圳大学",
            "14 小节 · 8:30 开始 · 晚上连堂已拆",
            "08:30-09:10", "09:15-09:55", "10:15-10:55", "11:00-11:40", "11:45-12:25",
            "13:30-14:10", "14:15-14:55", "15:00-15:40", "16:00-16:40", "16:45-17:25",
            "19:00-19:40", "19:40-20:20", "20:30-21:10", "21:10-21:45"
        ),
    )

    fun presets(): List<Preset> = PRESETS

    fun preset(key: String?): Preset? = PRESETS.firstOrNull { it.key == key }

    /** 广外 15 节（SectionTimes.default() 的唯一数据来源，避免出现两份硬编码） */
    fun gdufsSections(): List<SectionTimes.Section> = GDUFS

    // ==================================================================
    // 存取（SharedPreferences）
    // ==================================================================

    private fun sp(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sameAs(a: List<SectionTimes.Section>, b: List<SectionTimes.Section>): Boolean =
        a.size == b.size && a.indices.all { a[it].start == b[it].start && a[it].end == b[it].end }

    /** 首次访问：把老的 section_times 迁成一份作息；没有就建一份广外作息 */
    @Synchronized
    fun ensureInit(context: Context): List<Profile> {
        val store = sp(context)
        var list = decodeProfiles(store.getString(KEY_LIST, null))
        if (list.isEmpty()) {
            val legacy = decodeSections(store.getString(LEGACY_KEY, null)) ?: gdufsSections()
            val isGdufs = sameAs(legacy, gdufsSections())
            val first = Profile(
                id = newId(),
                name = if (isGdufs) "广外作息" else "我的作息",
                sections = legacy,
                presetKey = if (isGdufs) "gdufs" else null,
                createdAt = System.currentTimeMillis(),
            )
            list = listOf(first)
            store.edit().putString(KEY_LIST, encodeProfiles(list)).putString(KEY_ACTIVE, first.id).apply()
        }
        if (store.getString(KEY_ACTIVE, null).isNullOrEmpty()) {
            store.edit().putString(KEY_ACTIVE, list.first().id).apply()
        }
        return list
    }

    fun list(context: Context): List<Profile> = ensureInit(context)

    fun find(context: Context, id: String?): Profile? =
        if (id == null) null else ensureInit(context).firstOrNull { it.id == id }

    /** 「当前作息」：新建课表的默认值 */
    @Synchronized
    fun activeId(context: Context): String {
        val list = ensureInit(context)
        val id = sp(context).getString(KEY_ACTIVE, null)
        if (id != null && list.any { it.id == id }) return id
        return list.first().id
    }

    fun active(context: Context): Profile = find(context, activeId(context)) ?: ensureInit(context).first()

    @Synchronized
    fun setActive(context: Context, id: String) {
        val list = ensureInit(context)
        if (list.none { it.id == id }) return
        sp(context).edit().putString(KEY_ACTIVE, id).apply()
        SectionTimes.invalidate()
    }

    // ---- 课表绑定 ----

    @Synchronized
    fun binding(context: Context): Map<String, String> =
        decodeBinding(sp(context).getString(KEY_BINDING, null))

    /** 某张课表实际生效的作息 id（没绑定就跟随当前作息） */
    fun profileIdFor(context: Context, timetableId: String?): String {
        if (!timetableId.isNullOrEmpty()) {
            val bound = binding(context)[timetableId]
            if (bound != null && find(context, bound) != null) return bound
        }
        return activeId(context)
    }

    fun profileFor(context: Context, timetableId: String?): Profile =
        find(context, profileIdFor(context, timetableId)) ?: active(context)

    /** 设为某张课表的作息（同时记为全局当前作息，新建课表默认沿用） */
    @Synchronized
    fun bindToTimetable(context: Context, timetableId: String?, profileId: String) {
        val list = ensureInit(context)
        if (list.none { it.id == profileId }) return
        if (!timetableId.isNullOrEmpty()) {
            val map = binding(context).toMutableMap()
            map[timetableId] = profileId
            sp(context).edit().putString(KEY_BINDING, encodeBinding(map)).apply()
        }
        sp(context).edit().putString(KEY_ACTIVE, profileId).apply()
        SectionTimes.invalidate()
    }

    @Synchronized
    fun unbindTimetable(context: Context, timetableId: String) {
        val map = binding(context).toMutableMap()
        if (map.remove(timetableId) != null) {
            sp(context).edit().putString(KEY_BINDING, encodeBinding(map)).apply()
            SectionTimes.invalidate()
        }
    }

    // ---- CRUD ----

    @Synchronized
    fun create(
        context: Context,
        name: String?,
        sections: List<SectionTimes.Section>,
        presetKey: String? = null
    ): Profile {
        val list = ensureInit(context)
        val profile = Profile(
            id = newId(),
            name = uniqueName(name ?: "新作息", list.map { it.name }),
            sections = sections.take(MAX_SECTIONS).ifEmpty { gdufsSections() },
            presetKey = presetKey,
            createdAt = System.currentTimeMillis(),
        )
        saveProfile(context, list + profile)
        return profile
    }

    /** 从预设复制一份；presetKey 无效返回 null */
    @Synchronized
    fun createFromPreset(context: Context, presetKey: String): Profile? {
        val p = preset(presetKey) ?: return null
        return create(context, p.name, p.sections, p.key)
    }

    /** 更新（改名 / 改节次时间）；名字非法返回 false */
    @Synchronized
    fun update(context: Context, profile: Profile): Boolean {
        val list = ensureInit(context)
        val at = list.indexOfFirst { it.id == profile.id }
        if (at < 0) return false
        val clean = cleanName(profile.name) ?: return false
        val name = uniqueName(clean, list.filterIndexed { i, _ -> i != at }.map { it.name })
        val next = list.toMutableList()
        next[at] = profile.copy(name = name, sections = profile.sections.take(MAX_SECTIONS))
        saveProfile(context, next)
        return true
    }

    @Synchronized
    fun rename(context: Context, id: String, newName: String?): Boolean {
        val p = find(context, id) ?: return false
        val n = newName ?: return false
        return update(context, p.copy(name = n))
    }

    /** 至少保留一份：只剩一份时返回 false */
    @Synchronized
    fun delete(context: Context, id: String): Boolean {
        val list = ensureInit(context)
        if (list.size <= 1) return false
        if (list.none { it.id == id }) return false
        val next = list.filterNot { it.id == id }
        saveProfile(context, next)
        val store = sp(context)
        if (store.getString(KEY_ACTIVE, null) == id) {
            store.edit().putString(KEY_ACTIVE, next.first().id).apply()
        }
        val map = binding(context).toMutableMap()
        val stale = map.entries.filter { it.value == id }.map { it.key }
        if (stale.isNotEmpty()) {
            stale.forEach { map.remove(it) }
            store.edit().putString(KEY_BINDING, encodeBinding(map)).apply()
        }
        SectionTimes.invalidate()
        return true
    }

    @Synchronized
    private fun saveProfile(context: Context, list: List<Profile>) {
        sp(context).edit().putString(KEY_LIST, encodeProfiles(list)).apply()
        SectionTimes.invalidate()
    }

    private var uid = 0L

    private fun newId(): String {
        uid += 1
        return "p_" + System.currentTimeMillis().toString(36) + "_" + uid.toString(36)
    }
}
