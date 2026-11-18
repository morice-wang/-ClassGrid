package com.classgrid.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * 全国高校目录（v1.38.0）。
 *
 * 为什么要有这一层：v1.37.0 的「链接抓取」只会打开广外教务系统，别的学校用户点进来
 * 只能看到一个不相干的登录页。现在改成「先选学校 → 打开该校官网 → 用户自己登录 → 抓课表」。
 *
 * 数据来源：`app/src/main/assets/schools.json`，由 `tools/gen_schools.py` 从
 * 教育部全国高等学校名单（2868 所，本科 1308 + 专科 1560，覆盖 31 个省市区）生成，
 * 附带官网地址、拼音全拼、拼音首字母，用于搜索。
 *
 * 设计要点：
 * - **官网地址可改**：学校整理来的网址不一定对（有 147 所没收录、也有个别域名换了）。
 *   用户改过的网址按学校名存在 SharedPreferences 里，覆盖内置数据；内置数据升级也不会被冲掉。
 * - **可自定义学校**：名单没收录的（分校/新校区/境外校区）用户自己加，同样可改网址。
 * - **纯逻辑与 Context 分离**：解析/搜索/网址规范化/候选入口都是纯函数，JVM 单测能直接覆盖。
 */
object SchoolDirectory {

    private const val ASSET_NAME = "schools.json"
    private const val PREFS_NAME = "classgrid_prefs"
    private const val KEY_CUSTOM = "school_custom"
    private const val KEY_OVERRIDE = "school_url_override"
    private const val KEY_RECENT = "school_recent"
    private const val KEY_LAST = "school_last"
    private const val RECENT_LIMIT = 8

    /** 一所学校。字段与 assets/schools.json 里的数组顺序一一对应。 */
    data class School(
        val name: String,
        val province: String,
        val city: String,
        val level: String,
        val site: String,
        val pinyin: String,
        val initials: String,
        val tags: String,
        val english: String,
        /** 教务系统入口（从官网抓取/人工整理，可能为空；空时退回 [site]） */
        val jw: String = "",
        /** 教务入口是否抽验过（能打开且页面像教务系统） */
        val jwVerified: Boolean = false,
        val custom: Boolean = false
    ) {
        /** 「北京 · 本科 · 985 · 211 · 双一流」这类一行摘要 */
        fun meta(): String {
            val parts = mutableListOf<String>()
            if (province.isNotEmpty() && province != CUSTOM_PROVINCE) parts.add(province)
            if (level.isNotEmpty()) parts.add(level)
            parts.addAll(tagList())
            return parts.joinToString(" · ")
        }

        /** 标签按 985 → 211 → 双一流 排序（数据里是「211，985，双一流」这种顺序） */
        fun tagList(): List<String> {
            if (tags.isBlank()) return emptyList()
            val set = tags.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
            val ordered = mutableListOf<String>()
            if (set.contains("985")) ordered.add("985")
            if (set.contains("211")) ordered.add("211")
            if (set.contains("双一流")) ordered.add("双一流")
            return ordered
        }
    }

    private const val CUSTOM_PROVINCE = "自定义"

    // ---------------------------------------------------------------- 内置数据

    @Volatile
    private var builtinCache: List<School>? = null

    @Volatile
    private var provincesCache: List<String>? = null

    @Volatile
    private var versionCache: String? = null

    @Volatile
    private var customCache: List<School>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readAsset(context: Context): String? = try {
        context.applicationContext.assets.open(ASSET_NAME)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
    } catch (e: Exception) {
        null
    }

    /** 内置名单（不含用户自定义）。解析失败时返回空表，不能因此让选校页崩掉。 */
    fun builtin(context: Context): List<School> {
        builtinCache?.let { return it }
        synchronized(this) {
            builtinCache?.let { return it }
            val text = readAsset(context)
            val list = if (text.isNullOrEmpty()) {
                emptyList()
            } else {
                try {
                    parse(text)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            builtinCache = list
            return list
        }
    }

    /** 省份顺序（与数据文件一致，按行政区划排）：用于选校页的地区筛选 */
    fun provinces(context: Context): List<String> {
        provincesCache?.let { return it }
        synchronized(this) {
            provincesCache?.let { return it }
            val text = readAsset(context)
            val list = try {
                val arr = text?.let { JSONObject(it).optJSONArray("provinces") } ?: JSONArray()
                (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotEmpty() }
            } catch (e: Exception) {
                emptyList()
            }
            val result = if (list.isNotEmpty()) list else builtin(context).map { it.province }.distinct()
            provincesCache = result
            return result
        }
    }

    /** 数据版本（例如 2025-11），选校页副标题里展示，方便用户判断名单新旧 */
    fun dataVersion(context: Context): String {
        versionCache?.let { return it }
        synchronized(this) {
            versionCache?.let { return it }
            val text = readAsset(context)
            val v = try {
                text?.let { JSONObject(it).optString("version", "") } ?: ""
            } catch (e: Exception) {
                ""
            }
            versionCache = v
            return v
        }
    }

    // ---------------------------------------------------------------- 用户自定义 / 网址覆盖

    fun custom(context: Context): List<School> {
        customCache?.let { return it }
        synchronized(this) {
            customCache?.let { return it }
            val raw = prefs(context).getString(KEY_CUSTOM, null)
            val list = if (raw.isNullOrEmpty()) emptyList() else parseCustom(raw)
            customCache = list
            return list
        }
    }

    /** 全部学校：内置在前（已按 985/211/双一流/本科/专科 + 拼音排好），自定义在后 */
    fun all(context: Context): List<School> = builtin(context) + custom(context)

    fun find(context: Context, name: String): School? =
        all(context).firstOrNull { it.name == name.trim() }

    /** 学校官网（内置数据，不受「用户改过的抓取地址」影响） */
    fun homepageOf(school: School): String = school.site

    /**
     * 抓取时默认打开的地址：优先「教务系统」，没有就退回官网。
     *
     * 用户改过的网址永远优先——v1.38.0 里改的也是「抓取时打开的地址」，
     * 所以老用户升级后行为不变（他们改过的地址继续生效）。
     */
    fun entryOf(context: Context, school: School): String {
        val override = overrides(context)[school.name]
        if (!override.isNullOrEmpty()) return override
        if (school.jw.isNotEmpty()) return school.jw
        return school.site
    }

    /** 这所学校的教务入口是不是内置数据里就有（不需要再猜） */
    fun hasBuiltinJw(school: School): Boolean = school.jw.isNotEmpty()

    /** 记住用户为某校填的网址（会覆盖内置网址，升级后依然生效） */
    fun rememberSite(context: Context, school: School, url: String) {
        val normalized = normalizeUrl(url)
        if (normalized.isEmpty()) return
        val map = overrides(context).toMutableMap()
        // 和学校自带网址一样就没必要单独存覆盖项
        if (normalized == school.site || normalized == school.jw) map.remove(school.name) else map[school.name] = normalized
        writeOverrides(context, map)
    }

    /** 清掉用户覆盖，回到内置网址 */
    fun resetSite(context: Context, school: School) {
        val map = overrides(context).toMutableMap()
        map.remove(school.name)
        writeOverrides(context, map)
    }

    fun hasSiteOverride(context: Context, school: School): Boolean =
        !overrides(context)[school.name].isNullOrEmpty()

    /** 新增自定义学校；重名返回 false（重名应该去搜索里改网址，而不是加两份） */
    fun addCustom(context: Context, name: String, site: String): Boolean {
        val n = name.trim()
        if (n.isEmpty()) return false
        if (all(context).any { it.name == n }) return false
        val list = custom(context).toMutableList()
        list.add(
            School(
                name = n,
                province = CUSTOM_PROVINCE,
                city = "",
                level = "",
                site = normalizeUrl(site),
                pinyin = "",
                initials = "",
                tags = "",
                english = "",
                custom = true
            )
        )
        writeCustom(context, list)
        return true
    }

    fun removeCustom(context: Context, name: String) {
        val list = custom(context).filterNot { it.name == name }
        writeCustom(context, list)
        val map = overrides(context).toMutableMap()
        map.remove(name)
        writeOverrides(context, map)
    }

    fun setLastSchool(context: Context, name: String) {
        prefs(context).edit().putString(KEY_LAST, name).apply()
        markRecent(context, name)
    }

    fun lastSchool(context: Context): School? {
        val name = prefs(context).getString(KEY_LAST, null) ?: return null
        return find(context, name)
    }

    fun markRecent(context: Context, name: String) {
        val list = recentNames(context).toMutableList()
        list.remove(name)
        list.add(0, name)
        while (list.size > RECENT_LIMIT) list.removeAt(list.size - 1)
        prefs(context).edit().putString(KEY_RECENT, JSONArray(list).toString()).apply()
    }

    fun recentNames(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_RECENT, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 最近用过的学校（按最近顺序），用于选校页顶部的一键继续 */
    fun recent(context: Context): List<School> =
        recentNames(context).mapNotNull { name -> find(context, name) }

    private fun overrides(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY_OVERRIDE, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = LinkedHashMap<String, String>()
            obj.keys().forEach { key -> map[key] = obj.optString(key, "") }
            map
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun writeOverrides(context: Context, map: Map<String, String>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> if (v.isNotEmpty()) obj.put(k, v) }
        prefs(context).edit().putString(KEY_OVERRIDE, obj.toString()).apply()
    }

    private fun writeCustom(context: Context, list: List<School>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONArray().apply {
                put(s.name)
                put(s.site)
            })
        }
        prefs(context).edit().putString(KEY_CUSTOM, arr.toString()).apply()
        customCache = list
    }

    // ---------------------------------------------------------------- 纯逻辑（可单测）

    /** 解析 assets/schools.json 全文 */
    fun parse(json: String): List<School> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("schools") ?: return emptyList()
        return parseSchools(arr)
    }

    private fun parseSchools(arr: JSONArray): List<School> {
        val out = ArrayList<School>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            val name = row.optString(0, "").trim()
            if (name.isEmpty()) continue
            out.add(
                School(
                    name = name,
                    province = row.optString(1, ""),
                    city = row.optString(2, ""),
                    level = row.optString(3, ""),
                    site = row.optString(4, ""),
                    pinyin = row.optString(5, ""),
                    initials = row.optString(6, ""),
                    tags = row.optString(7, ""),
                    english = row.optString(8, ""),
                    jw = normalizeUrl(row.optString(9, "")),
                    jwVerified = row.optInt(10, 0) == 1
                )
            )
        }
        return out
    }

    /** 自定义学校存的是 [名称, 网址] 的二元数组 */
    private fun parseCustom(raw: String): List<School> {
        val arr = try {
            JSONArray(raw)
        } catch (e: Exception) {
            return emptyList()
        }
        val out = ArrayList<School>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.optJSONArray(i) ?: continue
            val name = row.optString(0, "").trim()
            if (name.isEmpty()) continue
            out.add(
                School(
                    name = name,
                    province = CUSTOM_PROVINCE,
                    city = "",
                    level = "",
                    site = normalizeUrl(row.optString(1, "")),
                    pinyin = "",
                    initials = "",
                    tags = "",
                    english = "",
                    custom = true
                )
            )
        }
        return out
    }

    /**
     * 搜索：校名包含、拼音全拼包含、拼音首字母包含、英文名包含都能命中。
     * 结果按「完全匹配 → 名称前缀 → 拼音前缀 → 首字母前缀 → 名称包含 → 其他包含」排序，
     * 同分保持原有顺序（985/211 在前），所以「清华」的第一条一定是清华大学。
     */
    fun search(schools: List<School>, query: String, province: String = ""): List<School> {
        val q = query.trim().lowercase()
        val scoped = if (province.isEmpty()) schools else schools.filter { it.province == province }
        if (q.isEmpty()) return scoped
        val scored = ArrayList<Pair<School, Int>>()
        for (s in scoped) {
            val score = matchScore(s, q)
            if (score >= 0) scored.add(s to score)
        }
        // sortedBy 是稳定排序：同分保持数据文件里的学校层级顺序
        return scored.sortedBy { it.second }.map { it.first }
    }

    private fun matchScore(school: School, q: String): Int {
        val name = school.name.lowercase()
        val py = school.pinyin.lowercase()
        val ini = school.initials.lowercase()
        val en = school.english.lowercase()
        return when {
            name == q -> 0
            name.startsWith(q) -> 1
            py.startsWith(q) -> 2
            ini.startsWith(q) -> 3
            name.contains(q) -> 4
            py.contains(q) -> 5
            ini.contains(q) -> 6
            en.contains(q) -> 7
            else -> -1
        }
    }

    /**
     * 网址规范化：补协议、丢掉非法输入。
     * 用 java.net.URI（Android 与 JVM 都有），这样单测能直接跑，不依赖 android.net.Uri。
     */
    fun normalizeUrl(raw: String): String {
        var t = raw.trim()
        if (t.isEmpty()) return ""
        if (!t.startsWith("http://", true) && !t.startsWith("https://", true)) {
            t = "https://$t"
        }
        return try {
            val uri = URI(t)
            val host = uri.host ?: return ""
            if (!host.contains(".") || host.startsWith(".") || host.endsWith(".")) return ""
            val scheme = if (uri.scheme.equals("http", true)) "http" else "https"
            val authority = uri.rawAuthority ?: host
            val path = uri.rawPath ?: ""
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            val fragment = uri.rawFragment?.let { "#$it" } ?: ""
            "$scheme://$authority$path$query$fragment"
        } catch (e: Exception) {
            ""
        }
    }

    /** 取域名（带端口时保留端口） */
    fun hostOf(url: String): String {
        val normalized = normalizeUrl(url)
        if (normalized.isEmpty()) return ""
        return try {
            URI(normalized).host ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    /** 列表里显示的网址：去掉协议与结尾斜杠，避免一长串 https:// */
    fun displaySite(site: String): String {
        val s = site.trim()
        if (s.isEmpty()) return ""
        return s.removePrefix("https://").removePrefix("http://").trimEnd('/')
    }

    /**
     * 抓取时依次尝试的入口（越靠前越可能是教务系统）：
     *   1. 内置教务入口（从各校官网抓到并抽验过）
     *   2. jwxt / jwglxt / jwgl / jwc / ehall / one 子域（国内高校最常见的几种）
     *   3. 学校官网
     *
     * 返回 (显示名, 网址)。调用方先在 WebView 里打开第一个，打不开再依次往下试。
     */
    fun entryCandidates(jw: String, site: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val jwUrl = normalizeUrl(jw)
        if (jwUrl.isNotEmpty()) out.add("教务系统" to jwUrl)
        val home = normalizeUrl(site)
        if (home.isNotEmpty()) {
            val host = hostOf(home)
            if (host.isNotEmpty()) {
                val bare = host.removePrefix("www.")
                for (prefix in listOf("jwxt", "jwglxt", "jwgl", "jwc", "ehall", "one")) {
                    val h = "$prefix.$bare"
                    if (h.equals(host, ignoreCase = true)) continue
                    val guess = "https://$h/"
                    if (out.none { it.second == guess }) out.add("$prefix 子域" to guess)
                }
            }
            out.add("学校官网" to home)
        }
        return out
    }

    /** 兼容旧调用：只有官网时按候选子域猜（不含教务入口） */
    fun candidateUrls(site: String): List<Pair<String, String>> = entryCandidates("", site)
}
