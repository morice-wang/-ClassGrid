package com.classgrid.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 多课表存储（v1.33.0 新增）。
 *
 * 背景：历史版本只有一份 `filesDir/courses.json`，用户无法在「本学期课表」「下学期课表」
 * 「同学的课表」之间切换。本类把「一份课程文件」升级成「多张课表」，布局如下：
 *
 * ```
 * filesDir/timetables/index.json          // 索引：{"active":"t_xxx","items":[{"id","name","createdAt"}]}
 * filesDir/timetables/<id>.json           // 每张课表的课程（与旧 courses.json 完全同格式）
 * filesDir/timetables/<id>_backup.json    // 该课表的滚动备份（旧 courses_backup.json 的语义）
 * filesDir/timetables/<id>_corrupt.json   // 该课表的坏文件留底（旧 courses_corrupt.json 的语义）
 * ```
 *
 * 设计约束（与 [CourseStore] 保持一致，**不要退化**）：
 *  - 原子写：先写 `.tmp` 再 rename，写前自校验（编码结果必须能被解回来）；
 *  - 滚动备份：只有旧主文件是「合法的非空课表」时才轮换备份，避免把唯一的恢复副本顶替成空；
 *  - 坏文件留底：解析失败改名 `<id>_corrupt.json`，不静默丢弃；
 *  - 索引也是原子写；索引损坏改名 `index_corrupt.json` 后按磁盘上现存课表文件重建。
 *
 * 数据迁移：老用户升级后，`filesDir/courses.json` **复制**（不删除、不改名）成一张名为
 * 「我的课表」的课表并设为当前；迁移标记写在独立 SharedPreferences（`timetable_v2_migrated`），
 * 保证只做一次。旧文件原样保留，是用户最后一道保险。
 *
 * 待办（todos.json）保持全局一份，**不**按课表拆分。
 */
object TimetableStore {

    /** 一张课表的元数据。[id] 同时是课程文件名前缀（`t_` + UniqueId.next()）。 */
    data class Meta(val id: String, val name: String, val createdAt: Long)

    /** 课表索引：当前激活的课表 + 全部课表（按 createdAt 升序）。 */
    data class Index(val active: String, val items: List<Meta>)

    /** 迁移出来的第一张课表的默认名字 */
    const val DEFAULT_NAME = "我的课表"

    /** 课表名最大长度（超出截断，避免设置页一行显示不下） */
    const val MAX_NAME_LEN = 20

    private const val DIR_NAME = "timetables"
    private const val INDEX_NAME = "index.json"
    private const val INDEX_CORRUPT_NAME = "index_corrupt.json"
    private const val PREFS_NAME = "classgrid_timetable"
    private const val KEY_MIGRATED = "timetable_v2_migrated"

    // 旧版本（单课表）文件：迁移时只读，绝不删除
    private const val LEGACY_COURSES = "courses.json"
    private const val LEGACY_BACKUP = "courses_backup.json"
    private const val LEGACY_CORRUPT = "courses_corrupt.json"

    /** id 白名单：只允许内部生成的 `t_<数字>`，防止索引被手改后写出目录外文件 */
    private val ID_REGEX = Regex("^t_[0-9]{1,20}$")

    /**
     * 最近一次 [load] 是否遇到损坏文件（已改名留底）。
     * [CourseStore.lastLoadFailed] 只是它的转发，保持两处语义一致。
     */
    @Volatile
    var lastLoadFailed: Boolean = false
        private set

    private val lock = Any()

    // ==================================================================
    // 纯逻辑（无 Context 依赖，JVM 单测覆盖：见 TimetableStoreTest）
    // ==================================================================

    /** 生成新 id；内部唯一递增（[UniqueId]），单进程内绝不重复 */
    internal fun newId(): String = "t_" + UniqueId.next()

    internal fun isValidId(id: String): Boolean = ID_REGEX.matches(id)

    /**
     * 校验/规范化用户输入的课表名：去首尾空白，空名返回 null，超长截断。
     * 返回 null 表示「这个名字不能用」，调用方应提示用户或退回默认名。
     */
    internal fun validateName(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        return if (t.length > MAX_NAME_LEN) t.substring(0, MAX_NAME_LEN) else t
    }

    /**
     * 在 [existing] 里不重名：重名时追加 " 2" / " 3" …（超长时截断前缀，保证后缀可见）。
     * 用户允许给课表起相同的名字，但 UI 上无法区分，所以这里自动消重。
     */
    internal fun uniqueName(raw: String, existing: List<String>): String {
        val base = validateName(raw) ?: DEFAULT_NAME
        if (existing.none { it == base }) return base
        var n = 2
        while (true) {
            val suffix = " $n"
            val candidate = if (base.length + suffix.length > MAX_NAME_LEN) {
                base.substring(0, MAX_NAME_LEN - suffix.length) + suffix
            } else {
                base + suffix
            }
            if (existing.none { it == candidate }) return candidate
            n++
        }
    }

    /** 索引序列化（写盘前会先 [decodeIndex] 自校验，坏结构不会落盘） */
    internal fun encodeIndex(index: Index): String {
        val arr = JSONArray()
        index.items.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("createdAt", m.createdAt)
            })
        }
        return JSONObject().apply {
            put("active", index.active)
            put("items", arr)
        }.toString()
    }

    /**
     * 索引反序列化。**逐条容错**：某一条缺 id / id 非法 / id 重复就跳过那一条，保留其余；
     * 名字缺失退回默认名；active 指向不存在的课表时退回最早创建的一张。
     * 整份索引不可用（空/非 JSON/没有一条可用课表）返回 null，由调用方重建。
     */
    internal fun decodeIndex(text: String): Index? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val root = try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            return null
        }
        val arr = root.optJSONArray("items") ?: return null
        val seen = HashSet<String>(arr.length() * 2)
        val items = ArrayList<Meta>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim()
            // id 是文件名前缀：非法 id（含路径分隔符/空/非 t_ 前缀）必须丢弃，防止写出目录外
            if (!isValidId(id) || !seen.add(id)) continue
            val name = o.optString("name", "").trim().ifEmpty { DEFAULT_NAME }
                .let { if (it.length > MAX_NAME_LEN) it.substring(0, MAX_NAME_LEN) else it }
            items.add(Meta(id, name, o.optLong("createdAt", 0L)))
        }
        if (items.isEmpty()) return null
        val sorted = items.sortedBy { it.createdAt }
        val wantActive = root.optString("active", "").trim()
        val active = if (sorted.any { it.id == wantActive }) wantActive else sorted.first().id
        return Index(active, sorted)
    }

    /**
     * 旧版 `courses.json` 的内容 → 课程列表。
     * 返回 null 表示**文件存在但已损坏**（调用方要留底、不能当成空课表覆盖掉）；
     * 传入 null（文件不存在）返回空列表。
     */
    internal fun parseLegacy(text: String?): List<Course>? {
        if (text == null) return emptyList()
        return CourseJson.decodeOrNull(text)
    }

    // ==================================================================
    // 初始化 / 迁移
    // ==================================================================

    /** 幂等初始化：确保目录/索引/迁移/各课表数据文件就绪。所有公开 API 入口都会先调它。 */
    fun ensureInit(context: Context) {
        synchronized(lock) { ensureInitLocked(context) }
    }

    private fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    private fun indexFile(context: Context): File = File(dir(context), INDEX_NAME)

    /** 某张课表的课程文件：`filesDir/timetables/<id>.json` */
    internal fun coursesFile(context: Context, id: String): File = File(dir(context), "$id.json")

    private fun backupFile(context: Context, id: String): File =
        File(dir(context), "${id}_backup.json")

    private fun corruptFile(context: Context, id: String): File =
        File(dir(context), "${id}_corrupt.json")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 初始化主流程（调用方必须已持有 [lock]）。
     * 顺序：建目录 → 读索引 → （未迁移则迁移）/（索引坏了则重建）→ 兜底建一张空表 →
     * 校正 active → 回写索引 → 补齐缺失的课程文件。
     */
    private fun ensureInitLocked(context: Context): Index {
        val d = dir(context)
        if (!d.exists()) d.mkdirs()

        val loaded = readIndexLocked(context)
        val initial: Index? = when {
            !prefs(context).getBoolean(KEY_MIGRATED, false) -> migrateLocked(context, loaded)
            // 索引损坏/被删：留底后按磁盘上现存的课表文件重建（课程文件本身不丢）
            loaded == null -> rebuildIndexLocked(context)
            else -> loaded
        }
        // 全新安装：建一张空课表，避免所有调用方都要处理「一张表都没有」
        val index = initial ?: Meta(newId(), DEFAULT_NAME, System.currentTimeMillis())
            .let { Index(it.id, listOf(it)) }

        // active 指向不存在的课表（索引被手改）时退回第一张
        val current = if (index.items.none { it.id == index.active }) {
            Index(index.items.first().id, index.items)
        } else {
            index
        }
        if (current != index || !indexFile(context).exists()) {
            writeIndexLocked(context, current)
        }
        // 补齐缺失的数据文件：load 就不必反复判断「文件不存在」，也便于用户在文件管理器里看到每张表
        current.items.forEach { m ->
            if (!coursesFile(context, m.id).exists()) {
                writeCoursesLocked(context, m.id, emptyList(), backup = false)
            }
        }
        return current
    }

    /**
     * 老用户迁移：`filesDir/courses.json` → 一张名为「我的课表」的课表（**复制，不删原文件**）。
     * 旧备份 `courses_backup.json` 一并复制成该表的滚动备份，迁移后设置页仍可「恢复上次备份」。
     * 旧文件损坏时：把原文复制成 `courses_corrupt.json` 留底，新表留空（绝不覆盖/删除原文件），
     * 设置页的「课表文件损坏」提示照旧可触发。
     */
    private fun migrateLocked(context: Context, existing: Index?): Index {
        val legacyFile = File(context.filesDir, LEGACY_COURSES)
        val rawText = if (legacyFile.exists()) {
            try {
                legacyFile.readText()
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        val legacyCourses = parseLegacy(rawText)

        val items = (existing?.items ?: emptyList()).toMutableList()
        val meta = if (items.isEmpty()) {
            Meta(newId(), DEFAULT_NAME, System.currentTimeMillis()).also { items.add(it) }
        } else {
            items[0]
        }

        // 只在该表还没有数据文件时写入，避免覆盖用户已开始使用的新数据
        if (!coursesFile(context, meta.id).exists()) {
            if (legacyCourses != null) {
                writeCoursesLocked(context, meta.id, legacyCourses, backup = false)
            } else {
                // 旧文件存在但解析不了：复制一份留底（改名会让用户以为文件丢了，所以复制）
                if (rawText != null) {
                    try {
                        val corrupt = File(context.filesDir, LEGACY_CORRUPT)
                        if (!corrupt.exists()) corrupt.writeText(rawText)
                    } catch (_: Exception) {
                    }
                }
                writeCoursesLocked(context, meta.id, emptyList(), backup = false)
            }
        }
        copyLegacyBackup(context, meta.id)

        val index = Index(meta.id, items)
        writeIndexLocked(context, index)
        prefs(context).edit().putBoolean(KEY_MIGRATED, true).apply()
        return index
    }

    /** 旧备份 → 新表备份（仅目标不存在时复制；失败不影响主流程） */
    private fun copyLegacyBackup(context: Context, id: String) {
        try {
            val src = File(context.filesDir, LEGACY_BACKUP)
            val dst = backupFile(context, id)
            if (src.exists() && !dst.exists()) src.copyTo(dst, overwrite = false)
        } catch (_: Exception) {
        }
    }

    /**
     * 索引损坏/丢失后的重建：坏索引改名 `index_corrupt.json` 留底，
     * 再扫描 `timetables/` 下所有 `<t_xxx>.json` 生成新索引（课程数据一条不丢）。
     * 名字无法从文件里恢复，按「我的课表 / 课表 2 / 课表 3 …」重新命名。
     */
    private fun rebuildIndexLocked(context: Context): Index? {
        val f = indexFile(context)
        if (f.exists()) {
            try {
                f.renameTo(File(dir(context), INDEX_CORRUPT_NAME))
            } catch (_: Exception) {
            }
        }
        val ids = dir(context).listFiles()?.mapNotNull { file ->
            if (!file.isFile || !file.name.endsWith(".json")) return@mapNotNull null
            val id = file.name.removeSuffix(".json")
            if (isValidId(id)) id else null
        }?.sorted() ?: emptyList()
        if (ids.isEmpty()) return null

        val now = System.currentTimeMillis()
        val items = ArrayList<Meta>(ids.size)
        ids.forEachIndexed { i, id ->
            val wanted = if (i == 0) DEFAULT_NAME else "课表 ${i + 1}"
            val name = uniqueName(wanted, items.map { it.name })
            items.add(Meta(id, name, now + i))
        }
        return Index(items.first().id, items)
    }

    private fun readIndexLocked(context: Context): Index? {
        val f = indexFile(context)
        if (!f.exists()) return null
        val text = try {
            f.readText()
        } catch (e: Exception) {
            return null
        }
        return decodeIndex(text)
    }

    private fun writeIndexLocked(context: Context, index: Index): Boolean {
        val d = dir(context)
        if (!d.exists()) d.mkdirs()
        val main = indexFile(context)
        val tmp = File(d, INDEX_NAME + ".tmp")
        return try {
            val json = encodeIndex(index)
            // 写前自校验：编码结果必须能被解回来（与课程文件同一条规矩）
            if (decodeIndex(json) == null) return false
            tmp.writeText(json)
            if (!tmp.renameTo(main)) main.writeText(json)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 把 id 参数（可为 null / 已失效）解析成一张确定存在的课表 id */
    private fun resolveId(id: String?, index: Index): String {
        val want = id?.trim().orEmpty()
        return if (want.isNotEmpty() && index.items.any { it.id == want }) want else index.active
    }

    private fun loadLocked(context: Context, id: String): List<Course> {
        lastLoadFailed = false
        val f = coursesFile(context, id)
        if (!f.exists()) return emptyList()
        val text = try {
            f.readText()
        } catch (e: Exception) {
            return emptyList()
        }
        val parsed = CourseJson.decodeOrNull(text)
        if (parsed != null) return parsed
        // 解析失败：改名留底，避免被下次保存永久覆盖
        try {
            if (f.exists()) f.renameTo(corruptFile(context, id))
        } catch (_: Exception) {
        }
        lastLoadFailed = true
        return emptyList()
    }

    /**
     * 写一张课表的课程文件（原子写 + 滚动备份）。
     * [backup] = true 时沿用旧语义：旧主文件合法且非空才轮换成备份，否则删掉旧主文件（空/坏不占备份位）。
     */
    private fun writeCoursesLocked(
        context: Context,
        id: String,
        courses: List<Course>,
        backup: Boolean
    ): Boolean {
        return try {
            val d = dir(context)
            if (!d.exists()) d.mkdirs()
            val main = coursesFile(context, id)
            val json = CourseJson.encode(courses)
            // 写前自校验：防止写出结构性损坏的文件
            if (CourseJson.decodeOrNull(json) == null) return false

            val oldText = if (main.exists()) {
                try {
                    main.readText()
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            val oldValid = oldText != null && CourseJson.decodeOrNull(oldText)?.isNotEmpty() == true

            // 原子写：先写完整临时文件，成功后 rename 替换主文件
            val tmp = File(d, "$id.json.tmp")
            tmp.writeText(json)

            if (backup && oldValid) {
                try {
                    main.renameTo(backupFile(context, id))
                } catch (_: Exception) {
                }
            } else if (main.exists()) {
                try {
                    main.delete()
                } catch (_: Exception) {
                }
            }
            if (!tmp.renameTo(main)) {
                // rename 失败（极少数文件系统问题）：直接写主文件兜底
                main.writeText(json)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    // ==================================================================
    // 课表元数据 CRUD
    // ==================================================================

    /** 全部课表（按创建时间升序） */
    fun list(context: Context): List<Meta> = synchronized(lock) {
        ensureInitLocked(context).items
    }

    /** 当前课表 id */
    fun activeId(context: Context): String = synchronized(lock) {
        ensureInitLocked(context).active
    }

    /** 当前课表元数据（设置页「当前课表」条目用） */
    fun activeMeta(context: Context): Meta = synchronized(lock) {
        val index = ensureInitLocked(context)
        index.items.firstOrNull { it.id == index.active } ?: index.items.first()
    }

    /** 切换当前课表；id 不存在返回 false */
    fun setActive(context: Context, id: String): Boolean = synchronized(lock) {
        val index = ensureInitLocked(context)
        if (index.items.none { it.id == id }) return@synchronized false
        if (index.active == id) return@synchronized true
        val ok = writeIndexLocked(context, Index(id, index.items))
        SectionTimes.invalidate()
        ok
    }

    /**
     * 新建课表（默认设为当前课表）。名字非法时退回 [DEFAULT_NAME]，重名自动加 " 2" / " 3"。
     * [courses] 非空时直接写入这批课程（「新建课表并导入」用）。
     */
    fun create(
        context: Context,
        name: String,
        courses: List<Course> = emptyList()
    ): Meta = synchronized(lock) {
        val index = ensureInitLocked(context)
        val meta = Meta(
            id = newId(),
            name = uniqueName(name, index.items.map { it.name }),
            createdAt = System.currentTimeMillis()
        )
        writeCoursesLocked(context, meta.id, courses, backup = false)
        writeIndexLocked(context, Index(meta.id, index.items + meta))
        SectionTimes.invalidate()
        meta
    }

    /** 重命名；名字非法或课表不存在返回 false（重名自动消重） */
    fun rename(context: Context, id: String, newName: String): Boolean = synchronized(lock) {
        val index = ensureInitLocked(context)
        val clean = validateName(newName) ?: return@synchronized false
        val at = index.items.indexOfFirst { it.id == id }
        if (at < 0) return@synchronized false
        val name = uniqueName(clean, index.items.filterIndexed { i, _ -> i != at }.map { it.name })
        val items = index.items.toMutableList()
        items[at] = items[at].copy(name = name)
        writeIndexLocked(context, Index(index.active, items))
    }

    /**
     * 删除课表。**至少保留一张**：只剩一张时返回 false（UI 提示「最后一张课表不能删」）。
     * 删的是当前课表时，自动切到剩下的第一张。先写索引再删文件，避免留下指向空文件的索引。
     */
    fun delete(context: Context, id: String): Boolean = synchronized(lock) {
        val index = ensureInitLocked(context)
        if (index.items.size <= 1) return@synchronized false
        if (index.items.none { it.id == id }) return@synchronized false
        val items = index.items.filterNot { it.id == id }
        val active = if (index.active == id) items.first().id else index.active
        writeIndexLocked(context, Index(active, items))
        try {
            coursesFile(context, id).delete()
        } catch (_: Exception) {
        }
        try {
            backupFile(context, id).delete()
        } catch (_: Exception) {
        }
        try {
            corruptFile(context, id).delete()
        } catch (_: Exception) {
        }
        // v1.48.0：课表没了，它的作息绑定也一并清掉，避免作息库里留下指向已删课表的悬空绑定
        try {
            ScheduleProfiles.unbindTimetable(context, id)
        } catch (_: Exception) {
        }
        SectionTimes.invalidate()
        true
    }

    /** 某张课表的课程数（[id] 为 null 时取当前课表） */
    fun courseCount(context: Context, id: String? = null): Int = load(context, id).size

    // ==================================================================
    // 课程读写（默认作用于当前课表；id 显式指定时作用于该课表）
    // ==================================================================

    /** 读课程。[id] 为 null 或已失效时读当前课表。 */
    fun load(context: Context, id: String? = null): List<Course> = synchronized(lock) {
        val index = ensureInitLocked(context)
        val targetId = resolveId(id, index)
        val courses = loadLocked(context, targetId)
        // v1.47.2：历史上导入（链接抓取/Excel/截图）留下的重复课程 id 会让「按 id 取状态」
        // 的错误扩散到同一天的另一门课上（典型：两门课都挂「下一节课」）。
        // 读的时候顺手修一次并落盘，之后 id 就稳定了。
        val repaired = repairIds(courses)
        if (repaired !== courses) writeCoursesLocked(context, targetId, repaired, backup = false)
        repaired
    }

    /**
     * 修复重复的课程 id：保留第一次出现的 id，后续重复的重新发号。
     * 没有重复时原样返回（不产生新列表）。
     */
    internal fun repairIds(courses: List<Course>): List<Course> {
        if (courses.size < 2) return courses
        val seen = HashSet<Long>(courses.size * 2)
        var changed = false
        val out = ArrayList<Course>(courses.size)
        for (c in courses) {
            if (seen.add(c.id)) {
                out.add(c)
            } else {
                out.add(c.copy(id = UniqueId.next()))
                changed = true
            }
        }
        return if (changed) out else courses
    }

    /** 写课程（原子写 + 滚动备份）。返回是否写入成功。 */
    fun save(context: Context, courses: List<Course>, id: String? = null): Boolean =
        synchronized(lock) {
            val index = ensureInitLocked(context)
            writeCoursesLocked(context, resolveId(id, index), courses, backup = true)
        }

    /**
     * 合并导入：同一门课（同名 + 同星期 + 同起始节）视为重复，覆盖旧记录并**保留原 id**；
     * 新增的课统一在本层发号（杜绝导入数据里的重复 id）。返回新增门数。
     */
    fun mergeAndSave(context: Context, courses: List<Course>, id: String? = null): Int =
        synchronized(lock) {
            val index = ensureInitLocked(context)
            val targetId = resolveId(id, index)
            val existing = loadLocked(context, targetId).toMutableList()
            var added = 0
            for (c in courses) {
                val at = existing.indexOfFirst {
                    it.name == c.name && it.dayOfWeek == c.dayOfWeek && it.startSection == c.startSection
                }
                if (at >= 0) {
                    existing[at] = c.copy(id = existing[at].id)
                } else {
                    existing.add(c.copy(id = UniqueId.next()))
                    added++
                }
            }
            // 兜底：修复历史数据里已经存在的重复 id（旧版本撞车留下的烂摊子）
            val seen = HashSet<Long>(existing.size * 2)
            for (i in existing.indices) {
                if (!seen.add(existing[i].id)) existing[i] = existing[i].copy(id = UniqueId.next())
            }
            writeCoursesLocked(context, targetId, existing, backup = true)
            added
        }

    /**
     * 覆盖导入：清空该课表后写入这批课程。
     * 实现上先落一笔空表再写新数据 —— 覆盖前的旧数据因此留在滚动备份里，
     * 用户误点「覆盖」仍可在设置页「恢复上次备份」捞回来。
     */
    fun replace(context: Context, courses: List<Course>, id: String? = null): Boolean =
        synchronized(lock) {
            val index = ensureInitLocked(context)
            val targetId = resolveId(id, index)
            writeCoursesLocked(context, targetId, emptyList(), backup = true)
            writeCoursesLocked(context, targetId, courses, backup = true)
        }

    // ---- 备份 / 损坏检测（按当前课表） ----

    /** 当前课表是否存在可恢复的滚动备份 */
    fun hasBackup(context: Context): Boolean = synchronized(lock) {
        val index = ensureInitLocked(context)
        backupFile(context, index.active).exists()
    }

    /**
     * 是否有损坏留底：当前课表的 `<id>_corrupt.json`，或迁移前留下的 `courses_corrupt.json`
     * （老用户升级时旧文件坏了也要能提示到）。
     */
    fun hasCorrupt(context: Context): Boolean = synchronized(lock) {
        val index = ensureInitLocked(context)
        corruptFile(context, index.active).exists() ||
            File(context.filesDir, LEGACY_CORRUPT).exists()
    }

    /**
     * 从当前课表的滚动备份恢复。直接原子写主文件、**不**走 save()（避免把当前备份顶替掉）。
     */
    fun restoreBackup(context: Context): Boolean = synchronized(lock) {
        val index = ensureInitLocked(context)
        val bak = backupFile(context, index.active)
        if (!bak.exists()) return@synchronized false
        try {
            val list = CourseJson.decodeOrNull(bak.readText()) ?: return@synchronized false
            writeCoursesLocked(context, index.active, list, backup = false)
        } catch (e: Exception) {
            false
        }
    }
}
