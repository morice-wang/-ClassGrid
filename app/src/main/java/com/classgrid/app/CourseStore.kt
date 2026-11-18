package com.classgrid.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import java.util.Calendar

/**
 * 数据存储：课程列表存 JSON 文件，设置存 SharedPreferences
 *
 * v1.9.1 数据安全加固：
 * - 原子写：先写 .tmp 再 rename，避免写入中途被杀导致 JSON 损坏
 * - 滚动备份：每次保存前把旧数据留底为 courses_backup.json，误删/清空后可在设置页恢复
 * - 坏文件保留：读取失败时不静默丢弃，把损坏文件改名为 courses_corrupt.json 留底
 *
 * v1.33.0 多课表：
 * 课程文件从「单份 courses.json」升级为「每张课表一个文件」，真正的读写实现在 [TimetableStore]。
 * 本类保留**全部历史无参 API**作为兼容层 —— 它们一律作用于「当前课表」，
 * 因此 MainActivity / CourseEditActivity / ImportActivity 等处无需改调用即可自动跟随课表切换。
 * 待办（todos）保持全局一份，与课表无关。
 */
object CourseStore {
    /**
     * 最近一次 [load] 是否遇到损坏文件（已改名留底）。
     * UI 可以据此提示「课表文件损坏，已留底，可在设置里恢复备份」，而不是让用户面对空课表发愣。
     */
    @Volatile
    var lastLoadFailed: Boolean = false
        private set

    /** 读当前课表的课程（兼容层，等价于 `TimetableStore.load(context)`） */
    fun load(context: Context): List<Course> =
        TimetableStore.load(context).also { lastLoadFailed = TimetableStore.lastLoadFailed }

    /**
     * 写当前课表的课程（兼容层）。
     * 返回是否写入成功：无参历史调用方不需要返回值，新调用方可据此提示失败。
     */
    fun save(context: Context, courses: List<Course>): Boolean =
        TimetableStore.save(context, courses)

    /** 当前课表是否存在损坏留底文件（含迁移前遗留的 courses_corrupt.json） */
    fun hasCorruptFile(context: Context): Boolean = TimetableStore.hasCorrupt(context)

    /** 当前课表是否存在可恢复的备份 */
    fun hasBackup(context: Context): Boolean = TimetableStore.hasBackup(context)

    // ============ 自定义待办（日程）独立 JSON ============

    private const val TODO_FILE = "todos.json"
    private const val TODO_BACKUP_FILE = "todos_backup.json"
    private const val TODO_CORRUPT_FILE = "todos_corrupt.json"

    /**
     * 读取待办。**逐条容错**：某一条缺字段/类型不对就跳过那一条，保留其余。
     * 历史实现用 getJSONObject/getLong/getString 强取 + 整体 catch，导致
     * todos.json 里任意一条坏字段就让"全部待办静默消失"，且下次保存把残留也抹掉。
     */
    fun loadTodos(context: Context): List<Todo> {
        val f = java.io.File(context.filesDir, TODO_FILE)
        if (!f.exists()) return emptyList()
        val text = try {
            f.readText()
        } catch (e: Exception) {
            return emptyList()
        }
        val arr = try {
            org.json.JSONArray(text)
        } catch (e: Exception) {
            // 整份文件损坏：留底，别让下次保存把它覆盖掉
            try {
                f.renameTo(java.io.File(context.filesDir, TODO_CORRUPT_FILE))
            } catch (_: Exception) {
            }
            return emptyList()
        }
        val out = ArrayList<Todo>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title", "").trim()
            if (title.isEmpty()) continue
            out.add(
                Todo(
                    id = o.optLong("id", UniqueId.next()),
                    title = title,
                    dateMillis = o.optLong("date", 0L),
                    time = o.optString("time", ""),
                    endTime = o.optString("endTime", ""),
                    location = o.optString("location", ""),
                    note = o.optString("note", ""),
                    colorIndex = o.optInt("colorIndex", 0),
                    repeatWeekly = o.optBoolean("repeatWeekly", false),
                    startWeek = o.optInt("startWeek", 0),
                    endWeek = o.optInt("endWeek", 0),
                    weekType = o.optInt("weekType", 0)
                )
            )
        }
        return out
    }

    fun saveTodos(context: Context, todos: List<Todo>) {
        try {
            val arr = org.json.JSONArray()
            todos.forEach { t ->
                arr.put(org.json.JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("date", t.dateMillis)
                    put("time", t.time)
                    put("endTime", t.endTime)
                    put("location", t.location)
                    put("note", t.note)
                    put("colorIndex", t.colorIndex)
                    put("repeatWeekly", t.repeatWeekly)
                    put("startWeek", t.startWeek)
                    put("endWeek", t.endWeek)
                    put("weekType", t.weekType)
                })
            }
            val dir = context.filesDir
            val json = arr.toString()
            val main = java.io.File(dir, TODO_FILE)
            val tmp = java.io.File(dir, TODO_FILE + ".tmp")
            tmp.writeText(json)
            // 与课程一致的数据保护：旧内容合法且非空才滚动备份
            if (main.exists()) {
                val old = try {
                    main.readText()
                } catch (e: Exception) {
                    null
                }
                val oldValid = old != null && try {
                    org.json.JSONArray(old).length() > 0
                } catch (e: Exception) {
                    false
                }
                if (oldValid) {
                    try {
                        main.renameTo(java.io.File(dir, TODO_BACKUP_FILE))
                    } catch (_: Exception) {
                    }
                } else {
                    try {
                        main.delete()
                    } catch (_: Exception) {
                    }
                }
            }
            if (!tmp.renameTo(main)) main.writeText(json)
        } catch (_: Exception) {
        }
    }

    /**
     * 从当前课表的滚动备份恢复课表。直接以原子写方式写主文件，
     * 不走 [save]（避免其"旧数据→备份"逻辑覆盖当前备份）。
     */
    fun restoreBackup(context: Context): Boolean = TimetableStore.restoreBackup(context)

    /**
     * 合并导入到**当前课表**：同一门课（同名+同星期+同起始节）视为重复，覆盖旧记录。
     * 覆盖时**保留原 id**（同一门课不应该换身份）；新增时统一在本层发号，
     * 杜绝导入数据里重复/缺失 id（历史版本用毫秒时间戳当 id，一次导入多门必撞车）。
     * 需要指定目标课表时用 [TimetableStore.mergeAndSave]。
     */
    fun mergeAndSave(context: Context, newCourses: List<Course>): Int =
        TimetableStore.mergeAndSave(context, newCourses)
}

/** 外观模式：跟随系统 / 日间(白) / 夜间(黑) */
const val APPEARANCE_FOLLOW = 0
const val APPEARANCE_LIGHT = 1
const val APPEARANCE_DARK = 2

/** 学期设置 */
object Prefs {
    private const val NAME = "classgrid_prefs"
    private const val KEY_SEMESTER_START = "semester_start"
    private const val KEY_TODAY_NOTIFY = "today_notify"
    private const val KEY_TOTAL_WEEKS = "total_weeks"
    private const val KEY_APPEARANCE = "appearance"
    private const val KEY_THEME = "ui_theme"
    private const val KEY_CARD_STYLE = "card_style"
    private const val KEY_CARD_SHADOW = "card_shadow"
    private const val KEY_BACKGROUND = "schedule_background"
    private const val KEY_BG_IMAGE_URI = "schedule_bg_image_uri"
    private const val KEY_FIRST_RUN_GUIDE = "first_run_guide_v2"
    private const val KEY_GUIDE_VERSION = "guide_shown_version"
    private const val KEY_GUIDED_UPDATE_TIME = "guided_update_time"
    private const val KEY_LAUNCHER_ICON = "launcher_icon"
    private const val KEY_LAUNCHER_ICON_V2 = "launcher_icon_v2"
    private const val KEY_LAUNCHER_ICON_V3 = "launcher_icon_v3"
    private const val KEY_LAUNCHER_ICON_V4 = "launcher_icon_v4"
    private const val KEY_UPDATE_LAST_CHECK = "update_last_check"
    private const val KEY_UPDATE_SKIPPED_VERSION = "update_skipped_version"
    private const val KEY_DEBUG_UPDATE_MANIFEST = "debug_update_manifest"

    // 卡片样式：0 = 圆角，1 = 方块
    const val CARD_STYLE_ROUNDED = 0
    const val CARD_STYLE_SQUARE = 1

    // 课表背景：0 = 跟随主题，1 = 米黄，2 = 淡蓝，3 = 淡绿，4 = 淡粉
    const val BG_THEME = 0
    const val BG_CREAM = 1
    const val BG_SKY = 2
    const val BG_MINT = 3
    const val BG_BLUSH = 4

    private fun sp(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 学期开始日期（周一 00:00 的 millis），未设置时返回本自然年 9 月的第一个周一 */
    fun semesterStart(context: Context): Long {
        val saved = sp(context).getLong(KEY_SEMESTER_START, 0L)
        if (saved > 0L) return saved
        return defaultSemesterStart()
    }

    fun setSemesterStart(context: Context, millis: Long) {
        sp(context).edit().putLong(KEY_SEMESTER_START, millis).apply()
    }

    fun totalWeeks(context: Context): Int =
        sp(context).getInt(KEY_TOTAL_WEEKS, 20)

    fun setTotalWeeks(context: Context, weeks: Int) {
        sp(context).edit().putInt(KEY_TOTAL_WEEKS, weeks.coerceIn(1, 40)).apply()
    }

    /** 上次检查更新的时间（millis），0 表示从未检查过。 */
    fun updateLastCheck(context: Context): Long =
        sp(context).getLong(KEY_UPDATE_LAST_CHECK, 0L)

    fun setUpdateLastCheck(context: Context, millis: Long) {
        sp(context).edit().putLong(KEY_UPDATE_LAST_CHECK, millis).apply()
    }

    /** 用户选过「跳过此版本」的 versionCode，0 表示没有跳过。 */
    fun updateSkippedVersion(context: Context): Int =
        sp(context).getInt(KEY_UPDATE_SKIPPED_VERSION, 0)

    fun setUpdateSkippedVersion(context: Context, versionCode: Int) {
        sp(context).edit().putInt(KEY_UPDATE_SKIPPED_VERSION, versionCode).apply()
    }

    /**
     * 调试包专用：把更新清单指到本地服务器（真机联调）。
     * 只有 BuildConfig.DEBUG 时 UpdateChecker 才会读它，正式包永远是 null。
     */
    fun debugUpdateManifest(context: Context): String? =
        sp(context).getString(KEY_DEBUG_UPDATE_MANIFEST, null)

    fun setDebugUpdateManifest(context: Context, url: String?) {
        sp(context).edit().apply {
            if (url.isNullOrBlank()) remove(KEY_DEBUG_UPDATE_MANIFEST)
            else putString(KEY_DEBUG_UPDATE_MANIFEST, url.trim())
        }.apply()
    }

    /** 外观模式，默认跟随系统 */
    fun appearance(context: Context): Int =
        sp(context).getInt(KEY_APPEARANCE, APPEARANCE_FOLLOW)
            .coerceIn(APPEARANCE_FOLLOW, APPEARANCE_DARK)

    fun setAppearance(context: Context, mode: Int) {
        sp(context).edit().putInt(KEY_APPEARANCE, mode.coerceIn(APPEARANCE_FOLLOW, APPEARANCE_DARK)).apply()
    }

    /**
     * 界面主题（色系）：0 经典（默认，老版本原样）/ 1 墨玉 / 2 极光。
     * 默认值必须是 0 —— 老用户升级后界面零变化；擅自改默认等于强制换肤，属于回归。
     */
    fun theme(context: Context): Int {
        val raw = sp(context).getInt(KEY_THEME, AppTheme.CLASSIC)
        // 防御历史脏值（v1.34.x 出现过「设置里显示极光、界面却是墨玉」的错位）：
        // 只接受确实存在的主题 id，越界一律回落到经典，绝不抛异常。
        return if (raw in AppTheme.ALL.indices) raw else AppTheme.CLASSIC
    }

    fun setTheme(context: Context, id: Int) {
        val safe = if (id in AppTheme.ALL.indices) id else AppTheme.CLASSIC
        // 用 commit() 而不是 apply()：设置页紧接着就 recreate()，
        // 异步落盘在极端情况下可能被下一次读取抢先，导致「切了却没生效」。
        sp(context).edit().putInt(KEY_THEME, safe).commit()
        AppTheme.invalidate()
    }

    /** 课表卡片样式：圆角（默认）/ 方块 */
    fun cardStyle(context: Context): Int =
        sp(context).getInt(KEY_CARD_STYLE, CARD_STYLE_ROUNDED).let {
            if (it == CARD_STYLE_SQUARE) it else CARD_STYLE_ROUNDED
        }

    fun setCardStyle(context: Context, style: Int) {
        sp(context).edit().putInt(KEY_CARD_STYLE, style).apply()
    }

    /** 课表卡片阴影：默认开 */
    fun cardShadow(context: Context): Boolean =
        sp(context).getBoolean(KEY_CARD_SHADOW, true)

    fun setCardShadow(context: Context, on: Boolean) {
        sp(context).edit().putBoolean(KEY_CARD_SHADOW, on).apply()
    }

    /** 课表背景样式，默认跟随主题 */
    fun backgroundStyle(context: Context): Int =
        sp(context).getInt(KEY_BACKGROUND, BG_THEME).coerceIn(BG_THEME, BG_BLUSH)

    fun setBackgroundStyle(context: Context, style: Int) {
        sp(context).edit().putInt(KEY_BACKGROUND, style.coerceIn(BG_THEME, BG_BLUSH)).apply()
    }

    // ---- 自定义背景图片（图片 URI；设置后课表以半透明方式铺底显示） ----

    /** 自定义背景图片的 content URI 字符串；为空表示未设置 */
    fun backgroundImage(context: Context): String? =
        sp(context).getString(KEY_BG_IMAGE_URI, null)?.takeIf { it.isNotBlank() }

    fun setBackgroundImage(context: Context, uri: String?) {
        sp(context).edit().putString(KEY_BG_IMAGE_URI, uri).apply()
    }

    // ---- 首次使用 / 更新引导（按应用"最后更新安装时间"触发） ----

    /** 已处理过引导的 lastUpdateTime（0=从未）。当前应用 lastUpdateTime 更大 = 刚安装/更新，需弹一次 */
    fun guidedUpdateTime(context: Context): Long =
        sp(context).getLong(KEY_GUIDED_UPDATE_TIME, 0L)

    fun setGuidedUpdateTime(context: Context, t: Long) {
        sp(context).edit().putLong(KEY_GUIDED_UPDATE_TIME, t).apply()
    }

    // ---- 桌面启动图标选择 ----

    /**
     * 当前选中的桌面图标：0=默认（最初紫色九宫格），1..6=可选图标（对应 IconSwitcher.ENTRIES）。
     * v1.25.0 起用 V4 key，默认 0 紫色；用户主动切换才写入其他值。
     */
    fun launcherIcon(context: Context): Int =
        sp(context).getInt(KEY_LAUNCHER_ICON_V4, 0).coerceIn(0, 6)

    fun setLauncherIcon(context: Context, index: Int) {
        sp(context).edit().putInt(KEY_LAUNCHER_ICON_V4, index.coerceIn(0, 6)).apply()
    }

    /** 依据外观设置应用夜间模式（日间=NO / 夜间=YES / 跟随=FOLLOW_SYSTEM） */
    fun applyNightMode(context: Context) {
        val mode = when (appearance(context)) {
            APPEARANCE_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            APPEARANCE_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /** 计算并缓存当前周 */
    fun currentWeek(context: Context): Int {
        val start = semesterStart(context)
        val now = Calendar.getInstance()
        val nowMonday = Calendar.getInstance().apply {
            timeInMillis = now.timeInMillis
            val dow = get(Calendar.DAY_OF_WEEK) // 1=周日 ... 7=周六
            val offset = ((dow - Calendar.MONDAY + 7) % 7)
            add(Calendar.DAY_OF_MONTH, -offset)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diffDays = (nowMonday.timeInMillis - start) / (7 * 24 * 60 * 60 * 1000L)
        return (diffDays + 1).toInt().coerceAtLeast(1)
    }

    /** v1.45.0 常驻通知开关（默认关） */
    fun todayNotify(context: Context): Boolean = sp(context).getBoolean(KEY_TODAY_NOTIFY, false)

    fun setTodayNotify(context: Context, enabled: Boolean) {
        sp(context).edit().putBoolean(KEY_TODAY_NOTIFY, enabled).apply()
    }

    private fun defaultSemesterStart(): Long {
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) // 0-based
        // 9 月 1 日之后开学季，9 月；否则 2 月
        val targetMonth = if (month >= Calendar.AUGUST) Calendar.SEPTEMBER else Calendar.FEBRUARY
        cal.set(year, targetMonth, 1, 0, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // 对齐到周一
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val offset = ((Calendar.MONDAY - dow + 7) % 7)
        cal.add(Calendar.DAY_OF_MONTH, offset)
        return cal.timeInMillis
    }
}
