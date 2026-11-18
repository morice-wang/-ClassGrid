package com.classgrid.app

import android.content.Context
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import java.util.Calendar
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 课表网格自定义视图
 * - 左侧节次栏（节次号 + 起止时间）+ 顶部星期栏（今天列以 primary 圆角底高亮，仍显示星期名）
 * - 课程卡片：顶部色帽 + 多行课程名 + @教室 + 次要信息；支持阴影（设置可关）与圆角/方块样式
 * - 默认一周七天平铺整屏（无横向滚动），双指放大后可横向滚动看细节
 * - 左右滑动切换周次（跟手翻页动效）+ 顶部箭头切换；双击放大/还原（带动效）
 */
class ScheduleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    // 基准尺寸（scale = 1 时）：收紧间距，让一周七天平铺整屏
    private val baseTimeCol = dp(46f)
    private val baseSectionH = dp(58f)
    private val headerHeight = dp(38f)
    private val cardGap = dp(2f)
    // 圆角 / 色帽高度现在由主题决定（AppTheme.Spec.cardRadiusDp 等），见 cachedCardRadius
    private val cardPadding = dp(5f)

    // 卡片文字字号（基准 scale=1，绘制时乘以 scale 联动双指缩放）
    private val nameTextSp = 13f
    /** 课程名自适应字号下限：窄列长名自动缩小到该值，让每行容纳更多字 */
    private val nameMinSp = 9f
    private val subTextSp = 9f
    private val tinyTextSp = 8f
    private val nameMaxLines = 5

    // 缩放与滚动状态：scale 下限 1.0（一周七天平铺），上限 2.4
    private var scale = 1f
    private var scrollXF = 0f
    private var scrollYF = 0f

    /** 翻页横向偏移（像素，1:1 跟手；正值=页面右移=回上一周，负值=下一周） */
    private var slideOffset = 0f

    /** 本次手势方向锁定：0 未锁定 / 1 水平翻页 / 2 纵向或放大后滚动（锁定后不互相抢占，解决斜滑误触） */
    private var axisLock = 0
    /** 是否正在水平拖页（手指未离开） */
    private var paging = false
    /** 学期开始（周一 00:00），用于推算任意周表头日期；<=0 时退回 weekDates */
    private var semesterStartMillis = 0L

    /** 目标周是否可翻（delta +1 下一周 / -1 上一周）；越界返回 false 时跟手带阻尼、松手回弹 */
    var canSwipeWeek: ((delta: Int) -> Boolean)? = null

    /** 翻页动画结束时回调：在此把数据切到相邻周（View 随后更新 currentWeek 并把偏移归零） */
    var onWeekSettled: ((delta: Int) -> Unit)? = null

    /** 滑动/缩放动画（切周跟手、双击缩放、缩放吸附共用） */
    private var animator: ValueAnimator? = null

    private val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private var courses: List<Course> = emptyList()
    /** v1.47.7：自定义日程（带时间段的按时间比例画在课表上） */
    private var todos: List<Todo> = emptyList()
    private var onClickTodo: ((Todo) -> Unit)? = null
    private val todoHitRects = ArrayList<Pair<Todo, android.graphics.RectF>>()
    private var currentWeek: Int = 1
    /** 当周 7 天日号（周一~周日），为空时表头仅显示星期名，不崩溃 */
    private var weekDates: List<Int>? = null
    private var totalSections: Int = 12
    private val totalWeeks: Int = Prefs.totalWeeks(context)
    private var sectionTimes: List<SectionTimes.Section> = SectionTimes.default()
    private var onClickCourse: ((Course) -> Unit)? = null

    // 每帧绘制需要"按天分组并过滤指定周"的结果；翻页时相邻两周同时在屏，需缓存多周分组。
    // 课程列表引用变化时整体清空（setData 也会清）。
    private var cacheCoursesRef: List<Course>? = null
    private val weekDayMapCache = HashMap<Int, Map<Int, List<Course>>>()

    /** 取指定周的「按天分组、按节次排序」课程表（带缓存） */
    private fun dayMapForWeek(week: Int): Map<Int, List<Course>> {
        if (cacheCoursesRef !== courses) {
            cacheCoursesRef = courses
            weekDayMapCache.clear()
        }
        return weekDayMapCache.getOrPut(week) {
            (1..7).associateWith { d ->
                courses.filter { it.dayOfWeek == d && it.activeInWeek(week) }
                    .sortedBy { it.startSection }
            }
        }
    }

    private val scroller = OverScroller(context)
    private val gestureDetector by lazy { GestureDetector(context, gestureListener) }

    // ---- 自定义背景图片（设置页选择，半透明铺底）----
    private var bgBitmap: android.graphics.Bitmap? = null
    private var bgBitmapUri: String? = null
    private val bgImagePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or
        android.graphics.Paint.FILTER_BITMAP_FLAG).apply { alpha = 105 } // ~41% 透明度

    // ---- 绘制缓存：onDraw 内只做纯绘制，不再读 SharedPreferences / 解析节次 JSON / parseColor ----
    // 刷新时机：setData（数据或设置变化后 MainActivity 会重新调用）、onAttachedToWindow、
    // onSizeChanged。时间相关字段（当前节 / 今天列 / 真实当前周）按「分钟」节流，跨分钟才重算。
    private var cachedDarkMode = false
    private var cachedPrimary = 0xFF4F46E5.toInt()
    /** 当前主题（onDraw 内高频使用，随 refreshDrawingCache 一起刷新） */
    private var cachedSpec: AppTheme.Spec = AppTheme.ALL[AppTheme.CLASSIC]
    private var cachedCardRadius = 0f
    private var cachedCardRadiusSquare = 0f
    private var cachedCardCap = 0f
    /** v1.35.0：卡片形态与左侧色条宽度（主题布局层决定，见 AppTheme.LayoutStyle） */
    private var cachedCardForm = AppTheme.CARD_STYLE_ROUNDED
    private var cachedCardStripe = 0f
    private var cachedBackgroundStyle = Prefs.BG_THEME
    private var cachedBackgroundColor: Int? = null
    private var cachedCardStyle = Prefs.CARD_STYLE_ROUNDED
    private var cachedCardShadow = true
    private var cachedNowSection = -1
    private var cachedTodayIndex = 0
    private var cachedRealWeek = 1
    /** 上次计算时间相关缓存时的「当天分钟数」；-1 表示需立即重算 */
    private var cachedMinuteOfDay = -1
    /** 星期几 → 表头日期，避免 onDraw 内每帧新建 Calendar */
    private val datesCache = HashMap<Int, List<Int>>()

    private val scaleDetector by lazy {
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val old = scale
                val focusX = d.focusX
                val focusY = d.focusY
                val cx = focusX + scrollXF
                val cy = focusY + scrollYF
                scale = (scale * d.scaleFactor).coerceIn(1f, 2.4f)
                val r = scale / old
                scrollXF = (cx * r - focusX).coerceIn(0f, maxSX())
                scrollYF = (cy * r - focusY).coerceIn(0f, maxSY())
                invalidate()
                return true
            }

            override fun onScaleEnd(d: ScaleGestureDetector) {
                // 缩放吸附：略放大后松手，回落到 1.0（保持一周平铺）；明显放大则保留
                if (scale < 1.18f) {
                    animateScaleTo(1f)
                }
            }
        })
    }

    // 动态布局尺寸
    private fun timeColW() = baseTimeCol * scale
    // 去掉最小列宽下限：一周七天精确平铺整屏（用户要求"间距做挤、不用缩放看到整周"）
    private fun colW() = (width - baseTimeCol) / 7f * scale
    private fun secH() = baseSectionH * scale
    private fun contentW() = timeColW() + colW() * 7f
    private fun contentH() = headerHeight + secH() * totalSections + dp(24f)
    private fun maxSX() = max(0f, contentW() - width)
    private fun maxSY() = max(0f, contentH() - height)

    // Paints
    private val paintHeader = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintDayText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val paintDaySub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val paintDayToday = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val paintSectionText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val paintGridLine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintCard = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintSectionBg = Paint(Paint.ANTI_ALIAS_FLAG)

    private val textName = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val textSub = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val textTiny = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private val rectF = RectF()

    fun setData(newCourses: List<Course>, week: Int) {
        courses = newCourses
        currentWeek = week
        sectionTimes = SectionTimes.load(context)
        // 节次总数取「作息节数」与「课程实际用到的最大节次」的较大者：
        // 用户把作息改成 10 节之后，原本排在 11/12 节的课不会被藏起来
        totalSections = computeTotalSections()
        // 数据或设置可能已变化（例如从设置页返回后 MainActivity 会重新 setData）：一并刷新绘制缓存
        refreshDrawingCache()
        clampScroll()
        invalidate()
    }

    /** 作息节数与课程实际最大节次取大，保证课程永远不会被作息节数藏起来 */
    private fun computeTotalSections(): Int {
        val fromCourses = courses.maxOfOrNull { it.endSection } ?: 0
        return maxOf(sectionTimes.size, fromCourses, 1)
    }

    fun setCurrentWeek(week: Int) {
        currentWeek = week
        invalidate()
    }

    /**
     * 设置当周 7 天日号（周一为第一天，与 dayNames 顺序一致）。
     * 传入空列表或长度不为 7 的列表时视为无日期，表头仅画星期名。
     */
    fun setWeekDates(dates: List<Int>) {
        weekDates = dates.takeIf { it.size == 7 }
        invalidate()
    }

    /** 设置学期开始（周一 00:00），用于在翻页时推算任意周的表头日期 */
    fun setSemesterStart(millis: Long) {
        semesterStartMillis = millis
        datesCache.clear()
        invalidate()
    }

    /** v1.47.7：传入全部自定义日程；只有带完整时间段的会画到课表上 */
    fun setTodos(list: List<Todo>) {
        todos = list
        invalidate()
    }

    /**
     * 把「一天中的分钟数」映射到内容区 y 坐标。
     *
     * 以节次时间表为锚点：第 i 节的上沿对应该节开始时间、下沿对应结束时间；
     * 节内线性插值，课间空档在「上一节下沿 → 下一节上沿」之间线性插值，
     * 这样日程方块和课程卡片在同一条时间轴上对齐。
     */
    private fun minutesToY(minutes: Int): Float {
        val sh = secH()
        val times = sectionTimes
        if (times.isEmpty()) return headerHeight
        val firstStart = SectionTimes.toMinutes(times.first().start) ?: (8 * 60)
        if (minutes <= firstStart) return headerHeight
        for (i in times.indices) {
            val s = SectionTimes.toMinutes(times[i].start) ?: continue
            val e = SectionTimes.toMinutes(times[i].end) ?: continue
            val top = headerHeight + i * sh
            val bottom = headerHeight + (i + 1) * sh
            if (minutes in s..e) {
                val span = (e - s).coerceAtLeast(1)
                return top + (minutes - s).toFloat() / span * (bottom - top)
            }
            val nextStart = times.getOrNull(i + 1)?.let { SectionTimes.toMinutes(it.start) }
            if (nextStart != null && minutes > e && minutes < nextStart) {
                val span = (nextStart - e).coerceAtLeast(1)
                val nextTop = headerHeight + (i + 1) * sh
                return bottom + (minutes - e).toFloat() / span * (nextTop - bottom)
            }
        }
        return headerHeight + times.size * sh
    }

    /** 当前周第 [week] 周周一 00:00 的毫秒值（学期开始未设置返回 0） */
    private fun weekStartMillis(week: Int): Long {
        val start = semesterStartMillis
        if (start <= 0L) return 0L
        return start + (week - 1).toLong() * 7L * 24L * 3600L * 1000L
    }

    /** v1.47.7：把带时间段的日程按时间比例画到课表对应列（在课程卡片之后画，避免被盖住） */
    private fun drawTodos(canvas: Canvas, week: Int, dark: Boolean) {
        if (todos.isEmpty()) return
        val weekStart = weekStartMillis(week)
        if (weekStart <= 0L) return
        val cw = colW()
        val tcw = timeColW()
        todoHitRects.clear()
        for (t in todos) {
            if (!t.hasTimeRange()) continue
            val startMin = Todo.parseMinutes(t.time) ?: continue
            val endMin = Todo.parseMinutes(t.endTime) ?: continue
            // v1.47.11：重复日程与课程同一套周次逻辑（起止周 + 每周/单周/双周）
            val dayIndex: Int
            if (t.repeatWeekly) {
                val start = if (t.startWeek > 0) t.startWeek else 1
                val end = when {
                    t.endWeek > 0 -> t.endWeek
                    totalWeeks > 0 -> totalWeeks
                    else -> 20
                }
                if (week < start || week > end) continue
                if (t.weekType == Course.WEEK_ODD && week % 2 == 0) continue
                if (t.weekType == Course.WEEK_EVEN && week % 2 == 1) continue
                dayIndex = weekdayIndexOf(t.dateMillis)
            } else {
                dayIndex = ((t.dateMillis - weekStart) / (24L * 3600L * 1000L)).toInt()
            }
            if (dayIndex !in 0..6) continue
            val top = minutesToY(startMin)
            var bottom = minutesToY(endMin)
            // 早于第一节（或极短时间段）的事件：在当天列顶部画一条最小高度的条，
            // 否则会被压成 0 高度、在课表上完全看不见。
            if (bottom - top < dp(10f)) bottom = top + dp(16f)
            val left = tcw + cw * dayIndex + cardGap
            val right = tcw + cw * (dayIndex + 1) - cardGap
            todoHitRects.add(t to android.graphics.RectF(left, top, right, bottom))
            drawTodoCard(canvas, t, left, top, right, bottom, dark)
        }
    }

    /** 当前周第 [week] 周周一 00:00 的毫秒值对应的星期索引（周一 = 0） */
    private fun weekdayIndexOf(millis: Long): Int {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        return (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
    }

    /** 日程方块：与课程卡片区分——浅底、虚线描边、无顶部色帽 */
    private fun drawTodoCard(canvas: Canvas, t: Todo, l: Float, top: Float, r: Float, bottom: Float, dark: Boolean) {
        if (r <= l || bottom <= top) return
        val pal = ScheduleColor.palette(context, t.colorIndex, dark)
        rectF.set(l, top, r, bottom)
        val corner = cachedCardRadius.coerceAtLeast(dp(6f))

        // 卡面 + 描边：与课程卡片同一套视觉
        paintCard.style = Paint.Style.FILL
        paintCard.clearShadowLayer()
        paintCard.color = pal.bg
        canvas.drawRoundRect(rectF, corner, corner, paintCard)
        paintCard.style = Paint.Style.STROKE
        paintCard.strokeWidth = dp(1f)
        paintCard.color = if (dark) cachedSpec.lineDark else cachedSpec.lineLight
        canvas.drawRoundRect(rectF, corner, corner, paintCard)
        paintCard.style = Paint.Style.FILL

        // 左侧色条（与 STRIPE 形态一致）
        val stripeW = maxOf(cachedCardStripe, dp(3f))
        if (bottom - top > dp(8f)) {
            paintCard.color = pal.accent
            canvas.drawRoundRect(
                l + dp(2f), top + dp(2f), l + dp(2f) + stripeW, bottom - dp(2f),
                stripeW / 2f, stripeW / 2f, paintCard
            )
        }

        // ===== 文本排版：与课程卡片完全一致（自适应字号 + 55% 块高 + 逐行折行） =====
        val textLeft = l + stripeW + dp(2f) + cardPadding
        val availW = r - l - stripeW - dp(2f) - cardPadding * 2f
        if (availW <= 0f) return
        val textTopBase = top + cardPadding
        val textBottom = bottom - cardPadding
        val availH = textBottom - textTopBase
        if (availH <= 0f) return

        textSub.textSize = sp(subTextSp) * scale
        textSub.typeface = Typeface.DEFAULT
        textSub.alpha = 215
        textTiny.textSize = sp(tinyTextSp) * scale
        textTiny.typeface = Typeface.DEFAULT
        textTiny.alpha = 160
        val subLineH = textSub.fontSpacing
        val tinyLineH = textTiny.fontSpacing

        // 标题：13sp → 9sp 自适应，最多占 55% 可用高度
        val nameMaxBlockH = availH * 0.55f
        var nameLines: List<String> = emptyList()
        for (sizeSp in nameTextSp.toInt() downTo nameMinSp.toInt()) {
            val sz = sizeSp.toFloat()
            textName.textSize = sp(sz) * scale
            val lh = textName.fontSpacing
            val maxLinesForBlock = max(1, floor(nameMaxBlockH / lh).toInt())
            val maxLines = min(maxLinesForBlock, nameMaxLines)
            val natural = layoutLines(t.title, textName, availW, Int.MAX_VALUE)
            if (natural.size <= maxLines) {
                nameLines = natural
                break
            }
        }
        if (nameLines.isEmpty()) {
            textName.textSize = sp(nameMinSp) * scale
            val lh = textName.fontSpacing
            val maxLines = max(1, min(floor(nameMaxBlockH / lh).toInt(), nameMaxLines))
            nameLines = layoutLines(t.title, textName, availW, maxLines)
        }
        textName.color = pal.fg
        val nameLineH = textName.fontSpacing
        var cursor = textTopBase
        drawTextLines(canvas, nameLines, textLeft, cursor, textName, nameLineH)
        cursor += nameLines.size * nameLineH
        if (cursor >= textBottom) return

        // 时间：只显示开始时间（与课程卡片的次要信息同字号/同位置优先级）
        if (t.time.isNotBlank()) {
            val timeLines = layoutLines(t.time, textSub, availW, 1)
            val h = timeLines.size * subLineH
            if (cursor + h <= textBottom) {
                textSub.color = if (dark) cachedSpec.textMutedDark else cachedSpec.textMutedLight
                drawTextLines(canvas, timeLines, textLeft, cursor, textSub, subLineH)
                cursor += h
            } else return
        }

        // 地点：最低优先级，空间不够就省略
        if (t.location.isNotBlank()) {
            val roomLines = layoutLines("@" + t.location, textTiny, availW, 1)
            val h = roomLines.size * tinyLineH
            if (cursor + h <= textBottom) {
                textTiny.color = if (dark) cachedSpec.textMutedDark else cachedSpec.textMutedLight
                drawTextLines(canvas, roomLines, textLeft, cursor, textTiny, tinyLineH)
            }
        }
    }

    /** 按像素宽度把文本折成最多 [maxLines] 行（简单逐字断行，中文友好） */
    private fun wrapText(text: String, paint: Paint, maxWidth: Float, maxLines: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>(maxLines)
        var start = 0
        while (start < text.length && out.size < maxLines) {
            var count = paint.breakText(text, start, text.length, true, maxWidth, null)
            if (count <= 0) count = 1
            if (start + count < text.length && out.size == maxLines - 1) {
                // 最后一行放不下就截断加省略号
                var cut = count
                while (cut > 1 && paint.measureText(text.substring(start, start + cut) + "…") > maxWidth) cut--
                out.add(text.substring(start, start + cut) + "…")
                return out
            }
            out.add(text.substring(start, start + count))
            start += count
        }
        return out
    }

    /** 推算第 [week] 周周一~周日的日号（学期开始未设置时退回当前周 weekDates） */
    private fun datesForWeek(week: Int): List<Int>? {
        val start = semesterStartMillis
        if (start <= 0L) return weekDates
        datesCache[week]?.let { return it }
        val cal = Calendar.getInstance().apply {
            timeInMillis = start
            add(Calendar.WEEK_OF_YEAR, week - 1)
        }
        val out = ArrayList<Int>(7)
        for (i in 0..6) {
            out.add(cal.get(Calendar.DAY_OF_MONTH))
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        datesCache[week] = out
        return out
    }

    /** 以今天推算"真实当前周"（用于翻页时仅在真实周高亮今天列/当前节） */
    private fun realCurrentWeek(): Int {
        val start = semesterStartMillis
        if (start <= 0L) return currentWeek
        val monday = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            val dow = get(Calendar.DAY_OF_WEEK)
            add(Calendar.DAY_OF_MONTH, -((dow - Calendar.MONDAY + 7) % 7))
        }
        val diff = (monday.timeInMillis - start) / (7L * 24L * 3600L * 1000L)
        return (diff + 1).toInt().coerceAtLeast(1)
    }

    fun setOnClickCourse(listener: (Course) -> Unit) {
        onClickCourse = listener
    }

    /** v1.47.9：日程方块也能点开编辑 */
    fun setOnClickTodo(listener: (Todo) -> Unit) {
        onClickTodo = listener
    }

    private fun clampScroll() {
        scrollXF = scrollXF.coerceIn(0f, maxSX())
        scrollYF = scrollYF.coerceIn(0f, maxSY())
    }

    // ============ 绘制缓存刷新 ============

    /**
     * 刷新全部绘制缓存（深色模式 / 主题色 / 背景样式 / 卡片样式 / 背景图）。
     * 只在 setData、attach、尺寸变化时调用；onDraw 内不再触碰 SharedPreferences，避免每帧读盘。
     */
    private fun refreshDrawingCache() {
        cachedDarkMode = ScheduleColor.isDarkMode(context)
        cachedSpec = AppTheme.current(context)
        cachedPrimary = AppTheme.primary(context, cachedDarkMode)
        cachedCardForm = cachedSpec.layout.cardStyle
        cachedCardStripe = dp(cachedSpec.layout.cardStripeDp)
        cachedCardRadius = dp(cachedSpec.layout.scheduleRadiusDp)
        cachedCardRadiusSquare = dp(cachedSpec.layout.scheduleRadiusDp * 0.3f)
        cachedCardCap = dp(cachedSpec.layout.scheduleCapDp)
        invalidate()
        cachedBackgroundStyle = Prefs.backgroundStyle(context)
        cachedBackgroundColor = scheduleBackground(cachedDarkMode, cachedBackgroundStyle)
        cachedCardStyle = Prefs.cardStyle(context)
        cachedCardShadow = Prefs.cardShadow(context)
        cachedMinuteOfDay = -1 // 强制下次绘制重算与时间相关的缓存
        datesCache.clear()
        refreshBackgroundImage()
    }

    /** 与「此刻」相关的缓存（当前节 / 今天列 / 真实当前周）：同一分钟内复用，跨分钟才重算 */
    private fun refreshClockCachesIfNeeded() {
        val cal = Calendar.getInstance()
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (minuteOfDay == cachedMinuteOfDay) return
        cachedMinuteOfDay = minuteOfDay
        cachedTodayIndex = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
        cachedNowSection = SectionTimes.currentSection(context)
        cachedRealWeek = realCurrentWeek()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 首次显示（或 Activity 重建）时刷新一次缓存，保证首帧即用最新设置
        refreshDrawingCache()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 尺寸变化会影响布局尺寸与裁剪，顺便让缓存与新尺寸保持一致（背景图只会在路径变化时重新解码）
        clampScroll()
        refreshDrawingCache()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 释放半透明背景图，避免 View 销毁后仍持有大位图
        bgBitmap?.recycle()
        bgBitmap = null
        bgBitmapUri = null
    }

    // ============ 绘制 ============

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 时间相关缓存（当前节 / 今天列 / 真实当前周）按分钟刷新，其余缓存由 refreshDrawingCache() 维护
        refreshClockCachesIfNeeded()
        val dark = cachedDarkMode
        val primary = cachedPrimary
        val realWeek = cachedRealWeek

        if (slideOffset == 0f) {
            drawWeekPage(canvas, currentWeek, datesForWeek(currentWeek), currentWeek == realWeek, 0f, dark, primary)
        } else {
            // 两页并排（ViewPager 式）：delta +1 下一周（页面左移）/ -1 上一周（页面右移）
            val delta = if (slideOffset > 0f) -1 else 1
            drawWeekPage(canvas, currentWeek, datesForWeek(currentWeek), currentWeek == realWeek, slideOffset, dark, primary)
            val adjWeek = currentWeek + delta
            drawWeekPage(canvas, adjWeek, datesForWeek(adjWeek), adjWeek == realWeek, slideOffset + delta * width, dark, primary)
        }
    }

    /**
     * 绘制一整周页面（背景 + 表头 + 网格 + 课程 + 节次栏），整体平移到 dx。
     * 翻页时对相邻两周各调用一次，两页并排、自带背景，全程无数据交接跳变。
     * 仅当 [highlightToday] 为真（该页就是真实当前周）才高亮"今天列"与"当前节"。
     */
    private fun drawWeekPage(
        canvas: Canvas,
        week: Int,
        dates: List<Int>?,
        highlightToday: Boolean,
        dx: Float,
        dark: Boolean,
        primary: Int
    ) {
        canvas.save()
        canvas.translate(dx, 0f)
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())

        // 页面背景随页一起移动（避免两页之间透出底层，翻页干净无穿透）
        val bg = cachedBackgroundColor
        if (bg != null) canvas.drawColor(bg)
        if (!dark) {
            val bgi = bgBitmap
            if (bgi != null) {
                val sW = bgi.width
                val sH = bgi.height
                val s = maxOf(width.toFloat() / sW, height.toFloat() / sH)
                val cropW = (width / s).toInt().coerceAtMost(sW)
                val cropH = (height / s).toInt().coerceAtMost(sH)
                val sx = (sW - cropW) / 2
                val sy = (sH - cropH) / 2
                canvas.drawBitmap(
                    bgi,
                    android.graphics.Rect(sx, sy, sx + cropW, sy + cropH),
                    android.graphics.Rect(0, 0, width, height),
                    bgImagePaint
                )
            }
        }

        val tcw = timeColW()
        val cw = colW()
        val sh = secH()
        val todayIndex = cachedTodayIndex
        val nowSection = if (highlightToday) cachedNowSection else -1

        // 顶部背景：自定义色背景时表头同色；跟随主题时保持白/深层次
        paintHeader.color = if (dark) cachedSpec.gridHeaderDark else cachedSpec.gridHeaderLight
        if (bg != null && cachedBackgroundStyle != Prefs.BG_THEME) paintHeader.color = bg
        canvas.drawRect(0f, 0f, width.toFloat(), headerHeight, paintHeader)

        // 星期栏（随横向滚动移动）：上行星期名，下行日期数字
        val weekdayCenter = dp(13f)
        val dateCenter = dp(27f)
        for (i in 0 until 7) {
            val cx = tcw + cw * i - scrollXF + cw / 2f
            if (cx < -cw / 2f || cx > width + cw / 2f) continue
            val dateNum = dates?.getOrNull(i)
            if (highlightToday && i == todayIndex) {
                // 今天列：primary 圆角底（包住两行）+ 白字加粗
                val label = dayNames[i]
                val dateStr = dateNum?.toString().orEmpty()
                paintDayToday.typeface = Typeface.DEFAULT_BOLD
                paintDayToday.textSize = sp(13f)
                val wName = paintDayToday.measureText(label)
                val wDate = if (dateStr.isNotEmpty()) {
                    paintDayToday.textSize = sp(11f)
                    paintDayToday.measureText(dateStr)
                } else 0f
                val pillW = max(wName, wDate) + dp(16f)
                val padV = dp(2f)
                paintDayToday.color = primary
                canvas.drawRoundRect(
                    cx - pillW / 2f, padV, cx + pillW / 2f, headerHeight - padV,
                    dp(12f), dp(12f), paintDayToday
                )
                paintDayToday.textSize = sp(13f)
                paintDayToday.color = Color.WHITE
                canvas.drawText(
                    label, cx,
                    weekdayCenter - (paintDayToday.ascent() + paintDayToday.descent()) / 2f,
                    paintDayToday
                )
                if (dateStr.isNotEmpty()) {
                    paintDayToday.textSize = sp(11f)
                    canvas.drawText(
                        dateStr, cx,
                        dateCenter - (paintDayToday.ascent() + paintDayToday.descent()) / 2f,
                        paintDayToday
                    )
                }
            } else {
                paintDayText.typeface = Typeface.DEFAULT_BOLD
                paintDayText.textSize = sp(13f)
                paintDayText.color = if (dark) cachedSpec.textMutedDark else cachedSpec.textMutedLight
                if (i >= 5) paintDayText.color = if (dark) cachedSpec.inactiveFgDark else cachedSpec.inactiveFgLight
                canvas.drawText(
                    dayNames[i], cx,
                    weekdayCenter - (paintDayText.ascent() + paintDayText.descent()) / 2f,
                    paintDayText
                )
                if (dateNum != null) {
                    paintDaySub.typeface = Typeface.DEFAULT
                    paintDaySub.textSize = sp(11f)
                    paintDaySub.color = if (dark) cachedSpec.inactiveFgDark else cachedSpec.inactiveFgLight
                    canvas.drawText(
                        dateNum.toString(), cx,
                        dateCenter - (paintDaySub.ascent() + paintDaySub.descent()) / 2f,
                        paintDaySub
                    )
                }
            }
        }

        // 星期栏分隔线
        paintGridLine.color = if (dark) cachedSpec.lineDark else cachedSpec.lineLight
        canvas.drawLine(0f, headerHeight, width.toFloat(), headerHeight, paintGridLine)

        // ===== 主体（滚动 + 缩放） =====
        canvas.save()
        canvas.clipRect(0f, headerHeight, width.toFloat(), height.toFloat())
        canvas.translate(-scrollXF, -scrollYF)

        // 网格线
        paintGridLine.strokeWidth = dp(1f)
        for (s in 0..totalSections) {
            val y = headerHeight + s * sh
            canvas.drawLine(0f, y, contentW(), y, paintGridLine)
        }
        for (d in 0..7) {
            val x = tcw + cw * d
            canvas.drawLine(x, headerHeight, x, headerHeight + totalSections * sh, paintGridLine)
        }

        // 当前节次高亮背景（仅真实当前周）
        if (nowSection in 1..totalSections) {
            val top = headerHeight + (nowSection - 1) * sh
            paintSectionBg.color = withAlpha(cachedPrimary, if (dark) 0x14 else 0x0F)
            canvas.drawRect(tcw, top, contentW(), top + sh, paintSectionBg)
        }

        drawCourses(canvas, week, dark)
        drawTodos(canvas, week, dark)

        canvas.restore()

        // ===== 左侧节次栏（固定列宽，随纵向滚动） =====
        paintHeader.color = if (dark) cachedSpec.gridHeaderDark else cachedSpec.gridHeaderLight
        if (bg != null) paintHeader.color = bg
        canvas.drawRect(0f, 0f, tcw, height.toFloat(), paintHeader)
        canvas.save()
        canvas.clipRect(0f, headerHeight, tcw, height.toFloat())
        canvas.translate(0f, -scrollYF)
        for (s in 1..totalSections) {
            val top = headerHeight + (s - 1) * sh
            val isNow = s == nowSection

            paintSectionText.typeface = Typeface.DEFAULT_BOLD
            paintSectionText.textSize = sp(13f) * scale
            paintSectionText.color = if (isNow) primary
            else if (dark) cachedSpec.sectionTextDark else cachedSpec.sectionTextLight
            canvas.drawText("$s", tcw / 2f, top + 17f * density * scale, paintSectionText)

            val sec = sectionTimes.getOrNull(s - 1)
            if (sec != null) {
                paintSectionText.typeface = Typeface.DEFAULT
                paintSectionText.textSize = sp(10f) * scale
                paintSectionText.color = if (isNow) primary
                else if (dark) cachedSpec.inactiveFgDark else cachedSpec.inactiveFgLight
                canvas.drawText(sec.start, tcw / 2f, top + 33f * density * scale, paintSectionText)
                canvas.drawText(sec.end, tcw / 2f, top + 47f * density * scale, paintSectionText)
            }
        }
        canvas.restore()
        canvas.drawLine(tcw, 0f, tcw, height.toFloat(), paintGridLine)

        canvas.restore()
    }

    /**
     * 课表背景色。
     * - 深色模式：固定柔和墨色，不随背景样式/图片切换（用户要求深色背景不可换色）。
     * - 浅色模式：默认中性浅灰白（替代之前偏冷蓝、显脏的颜色），其余彩色样式保留。
     */
    /**
     * 课表背景色（纯计算，不读 SharedPreferences）。
     * - 深色模式：固定柔和墨色，不随背景样式/图片切换（用户要求深色背景不可换色）。
     * - 浅色模式：默认中性浅灰白（替代之前偏冷蓝、显脏的颜色），其余彩色样式保留。
     */
    private fun scheduleBackground(dark: Boolean, style: Int): Int? {
        if (dark) return cachedSpec.gridBgDark
        val options = AppTheme.backgroundOptions(context, dark)
        // 下标 0 = 跟随主题，1..4 = 奶油/天蓝/薄荷/绯粉（顺序与 Prefs.BG_* 一致）
        return options.getOrElse(style) { options[0] }
    }

    /**
     * 设置页换了背景图才解码（路径/URI 变化时）；降采样到屏幕 2 倍以内控制内存。
     * 只在 refreshDrawingCache() 中调用（数据/尺寸变化时），保证 onDraw 内不再做文件读与位图解码。
     */
    private fun refreshBackgroundImage() {
        val pathOrUri = Prefs.backgroundImage(context)
        // 路径未变且已按当前主题处理过（深色不加载图片 / 浅色已尝试解码失败或成功）→ 直接复用
        if (pathOrUri == bgBitmapUri && (pathOrUri.isNullOrEmpty() || cachedDarkMode || bgBitmap != null)) return
        bgBitmapUri = pathOrUri
        bgBitmap?.recycle()
        bgBitmap = null
        if (pathOrUri.isNullOrEmpty() || cachedDarkMode) return
        bgBitmap = try {
            // 新版本保存的是 App 私有目录绝对路径（持久可靠）；旧值可能是相册 content://（临时权限已失效则忽略）
            val bytes = if (pathOrUri.startsWith("/")) {
                java.io.File(pathOrUri).readBytes()
            } else {
                context.contentResolver.openInputStream(android.net.Uri.parse(pathOrUri))
                    ?.use { it.readBytes() }
            } ?: return
            val target = maxOf(
                resources.displayMetrics.widthPixels,
                resources.displayMetrics.heightPixels
            ) * 2
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            var dim = maxOf(bounds.outWidth, bounds.outHeight)
            while (dim / (sample * 2) >= target) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        }
    }

    private fun drawCourses(canvas: Canvas, week: Int, dark: Boolean) {
        val cw = colW()
        val sh = secH()
        val tcw = timeColW()
        val dayCourseMap = dayMapForWeek(week)

        for (d in 1..7) {
            val dayList = dayCourseMap[d] ?: continue
            val clusters = clusterByOverlap(dayList)
            for (cluster in clusters) {
                val n = cluster.size
                val cellWidth = cw / n
                for ((idx, c) in cluster.withIndex()) {
                    val left = tcw + cw * (d - 1) + cellWidth * idx
                    val top = headerHeight + (c.startSection - 1) * sh
                    val bottom = headerHeight + c.endSection * sh
                    drawCard(canvas, c, left + cardGap, top + cardGap, left + cellWidth - cardGap, bottom - cardGap, dark)
                }
            }
        }
    }

    /** 给颜色换一个 alpha 通道（保留 RGB），用于「当前节次」淡色高亮带 */
    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    private fun drawCard(canvas: Canvas, c: Course, l: Float, t: Float, r: Float, b: Float, dark: Boolean) {
        if (r <= l || b <= t) return
        val pal = ScheduleColor.palette(context, c.colorIndex, dark)
        rectF.set(l, t, r, b)

        // ===== v1.35.0：卡片形态由主题布局层决定（CARD_STYLE_*）=====
        // 经典/墨玉 = ROUNDED（顶部色帽 + 圆角，原逻辑一字未改）；
        // 极光 = STRIPE（卡底 + 左侧粗色条 + 细描边），文字区整体右移色条宽度。
        val form = if (cachedCardStyle == Prefs.CARD_STYLE_SQUARE) AppTheme.CARD_STYLE_ROUNDED else cachedCardForm
        val corner = cachedCardRadius
        val cap = if (form == AppTheme.CARD_STYLE_ROUNDED) cachedCardCap else 0f
        var stripeW = 0f

        if (form == AppTheme.CARD_STYLE_ROUNDED) {
            // 卡片阴影（图二悬浮感）：整卡带软阴影，主体覆盖时不重复加影
            if (cachedCardShadow) {
                paintCard.setShadowLayer(dp(6f), dp(0f), dp(2f), 0x33000000)
            } else {
                paintCard.clearShadowLayer()
            }
            // 1. 整卡先画 accent 色（形成顶部色帽），阴影随整卡绘制
            paintCard.color = pal.accent
            canvas.drawRoundRect(rectF, corner, corner, paintCard)
            // 2. 主体色盖住帽以下部分（关闭阴影避免帽/主体交界处重影）
            val save = canvas.save()
            canvas.clipRect(l, t + cap, r, b)
            paintCard.clearShadowLayer()
            paintCard.color = pal.bg
            canvas.drawRoundRect(rectF, corner, corner, paintCard)
            canvas.restoreToCount(save)
            paintCard.clearShadowLayer()
        } else {
            // STRIPE / GHOST / PILL：先画卡面（可选阴影），再画左侧色条
            if (form != AppTheme.CARD_STYLE_GHOST && cachedCardShadow) {
                paintCard.setShadowLayer(dp(4f), dp(0f), dp(1.5f), 0x1F000000)
            } else {
                paintCard.clearShadowLayer()
            }
            paintCard.color = if (form == AppTheme.CARD_STYLE_GHOST) 0x00000000 else pal.bg
            paintCard.style = if (form == AppTheme.CARD_STYLE_GHOST) Paint.Style.FILL else Paint.Style.FILL
            if (form != AppTheme.CARD_STYLE_GHOST) {
                canvas.drawRoundRect(rectF, corner, corner, paintCard)
            }
            paintCard.clearShadowLayer()
            if (form != AppTheme.CARD_STYLE_GHOST) {
                paintCard.style = Paint.Style.STROKE
                paintCard.strokeWidth = dp(1f)
                paintCard.color = if (dark) cachedSpec.lineDark else cachedSpec.lineLight
                canvas.drawRoundRect(rectF, corner, corner, paintCard)
                paintCard.style = Paint.Style.FILL
            }
            stripeW = when (form) {
                AppTheme.CARD_STYLE_GHOST -> dp(3f)
                else -> maxOf(cachedCardStripe, dp(3f))
            }
            // 左侧色条：贴卡片左边、上下各留 2dp，圆角与小卡面一致
            val sL = l + dp(2f)
            val sT = t + dp(2f)
            val sB = b - dp(2f)
            if (sB > sT) {
                paintCard.color = pal.accent
                canvas.drawRoundRect(sL, sT, sL + stripeW, sB, stripeW / 2f, stripeW / 2f, paintCard)
            }
            stripeW += dp(2f)
        }

        // 3. 文本排版：课程名（自适应字号）→ @教室（完整优先）→ 教师 + 周次（最低优先）
        //    统一排版策略：各卡片信息优先级一致、风格一致；空间够就显示全，先用了全部空间才省略。
        val textLeft = l + stripeW + cardPadding
        val availW = r - l - stripeW - cardPadding * 2f
        if (availW <= 0f) return
        val textTop = t + cap + cardPadding
        val textBottom = b - cardPadding
        val availH = textBottom - textTop
        if (availH <= 0f) return

        // 副信息 / 次要信息字号（固定，与 scale 联动）
        textSub.textSize = sp(subTextSp) * scale
        textSub.typeface = Typeface.DEFAULT
        textSub.alpha = 215
        textTiny.textSize = sp(tinyTextSp) * scale
        textTiny.typeface = Typeface.DEFAULT
        textTiny.alpha = 160

        val subLineH = textSub.fontSpacing
        val tinyLineH = textTiny.fontSpacing

        // ---- 课程名：自适应字号（nameTextSp 14sp → nameMinSp 11sp） ----
        // 目标：短名用大字号；长名在窄列自动缩小，让每行容纳更多字，避免「每行挤 2 字」。
        // 约束：课程名占用不超过可用文字高度的 55%，从而为 @教室 预留空间（修问题 1/2）。
        val nameMaxBlockH = availH * 0.55f
        var nameLines: List<String> = emptyList()
        for (sizeSp in nameTextSp.toInt() downTo nameMinSp.toInt()) {
            val sz = sizeSp.toFloat()
            textName.textSize = sp(sz) * scale
            val lh = textName.fontSpacing
            val maxLinesForBlock = max(1, floor(nameMaxBlockH / lh).toInt())
            val maxLines = min(maxLinesForBlock, nameMaxLines)
            // 自然折行行数（不封顶），用于判断能否在 55% 块高内放下
            val natural = layoutLines(c.name, textName, availW, Int.MAX_VALUE)
            if (natural.size <= maxLines) {
                nameLines = natural
                break
            }
        }
        if (nameLines.isEmpty()) {
            // 即便最小字号仍超出 55% 块高：以最小字号布局，超出行用「…」收尾
            textName.textSize = sp(nameMinSp) * scale
            val lh = textName.fontSpacing
            val maxLines = max(1, min(floor(nameMaxBlockH / lh).toInt(), nameMaxLines))
            nameLines = layoutLines(c.name, textName, availW, maxLines)
        }

        textName.color = pal.fg
        val nameLineH = textName.fontSpacing
        var cursor = textTop
        drawTextLines(canvas, nameLines, textLeft, cursor, textName, nameLineH)
        cursor += nameLines.size * nameLineH
        if (cursor >= textBottom) return

        // ---- @教室：优先级高于教师/周次，尽量完整显示（最多折 2 行，极端才省略） ----
        if (c.classroom.isNotEmpty()) {
            val roomText = "@" + simplifyClassroom(c.classroom)
            val roomLines = layoutLines(roomText, textSub, availW, 2)
            val roomH = roomLines.size * subLineH
            val restH = textBottom - cursor
            if (roomH <= restH) {
                // 完整显示教室，剩余空间再放教师 + 周次
                textSub.color = pal.fg
                drawTextLines(canvas, roomLines, textLeft, cursor, textSub, subLineH)
                cursor += roomH
                drawTeacherWeek(canvas, c, textLeft, cursor, textBottom, availW, pal, tinyLineH)
            } else if (restH >= subLineH) {
                // 极小卡片：优先保证教室可见（单行，必要时省略号），按优先级不再显示教师/周次
                textSub.color = pal.fg
                val oneLine = layoutLines(roomText, textSub, availW, 1)
                drawTextLines(canvas, oneLine, textLeft, cursor, textSub, subLineH)
            }
            // restH < subLineH：仅显示课程名（空间真的不足，按优先级省略后续）
        } else {
            drawTeacherWeek(canvas, c, textLeft, cursor, textBottom, availW, pal, tinyLineH)
        }
    }

    /** 次要信息：教师 + 周次（最低优先级，仅当剩余空间足够才显示） */
    private fun drawTeacherWeek(
        canvas: Canvas,
        c: Course,
        x: Float,
        startY: Float,
        bottom: Float,
        availW: Float,
        pal: ScheduleColor.Palette,
        tinyLineH: Float
    ) {
        var cursor = startY
        if (cursor >= bottom) return
        val extra = buildString {
            if (c.teacher.isNotEmpty()) append(c.teacher).append(" ")
            append("${c.startWeek}-${c.endWeek}周")
        }
        if (extra.isNotBlank() && bottom - cursor >= tinyLineH) {
            textTiny.color = pal.fg
            val tinyLine = truncateWithEllipsis(extra, textTiny, availW)
            canvas.drawText(tinyLine, x, cursor - textTiny.ascent(), textTiny)
        }
    }

    /**
     * 将文本按可用宽度折行，返回每行字符串（最多 [maxLines] 行）。
     * 规则：中英文字符间可断行；括号（）/() 整体不断开；Latin 连续字母数字成词不断开；
     * 若内容超过 [maxLines]，末行以「…」结尾，并通过 measureText 保证整字省略（绝不出现半截字）。
     */
    private fun layoutLines(text: String, paint: TextPaint, maxWidth: Float, maxLines: Int): List<String> {
        if (text.isEmpty() || maxWidth <= 0f) return emptyList()
        val segs = tokenize(text)
        val out = mutableListOf<String>()
        var i = 0
        while (i < segs.size) {
            // 已完成 maxLines-1 行后，剩余全部并入末行并加省略号
            if (out.size >= maxLines - 1) {
                val tail = StringBuilder()
                while (i < segs.size) { tail.append(segs[i]); i++ }
                out.add(truncateWithEllipsis(tail.toString(), paint, maxWidth))
                break
            }
            val line = StringBuilder()
            var lineW = 0f
            while (i < segs.size) {
                val seg = segs[i]
                val segW = paint.measureText(seg)
                if (lineW + segW <= maxWidth || line.isEmpty()) {
                    line.append(seg)
                    lineW += segW
                    i++
                } else {
                    break
                }
            }
            if (line.isNotEmpty()) out.add(line.toString())
        }
        return out
    }

    /** 把文本切成「断行单元」：括号整体、Latin 词整体、其余逐字符（CJK 可任意断行） */
    private fun tokenize(text: String): List<String> {
        val tokens = mutableListOf<String>()
        var i = 0
        val n = text.length
        while (i < n) {
            val ch = text[i]
            when {
                ch == '（' || ch == '(' -> {
                    var j = i + 1
                    while (j < n && text[j] != '）' && text[j] != ')') j++
                    if (j < n) {
                        tokens.add(text.substring(i, j + 1))
                        i = j + 1
                    } else {
                        tokens.add(ch.toString())
                        i++
                    }
                }
                ch.isLetterOrDigit() && ch.code < 0x2E80 -> {
                    var j = i
                    while (j < n && text[j].isLetterOrDigit() && text[j].code < 0x2E80) j++
                    tokens.add(text.substring(i, j))
                    i = j
                }
                else -> {
                    tokens.add(ch.toString())
                    i++
                }
            }
        }
        return tokens
    }

    /** 单行长文本按宽度截断，末尾加「…」，整字省略（用 measureText 保证不出现半截字） */
    private fun truncateWithEllipsis(text: String, paint: TextPaint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellW = paint.measureText(ellipsis)
        val maxBody = maxWidth - ellW
        if (maxBody <= 0f) return ellipsis
        val sb = StringBuilder()
        var w = 0f
        for (ch in text) {
            val cw = paint.measureText(ch.toString())
            if (w + cw <= maxBody) {
                sb.append(ch)
                w += cw
            } else {
                break
            }
        }
        return sb.toString() + ellipsis
    }

    /** 智能精简长教室名：去描述性括号、去「座」后重复楼栋前缀，保留核心门牌号 */
    private fun simplifyClassroom(raw: String): String {
        if (raw.isEmpty()) return raw
        var s = raw
        // 1) 去除「描述性」括号内容（智慧教室/功能/多媒体/报告/实验/中心 等）
        s = s.replace(
            Regex("[（(][^（）()]*?(智慧|功能|多媒体|教室|报告|实验|中心)[^（）()]*?[）)]"),
            ""
        )
        // 2) 「座」后若重复楼栋前缀，则直接取门牌号部分（如 南G座南G211 → 南G211）
        val zuo = s.indexOf('座')
        if (zuo > 0 && zuo < s.length - 1) {
            val building = s.substring(0, zuo)
            val after = s.substring(zuo + 1)
            if (after.startsWith(building)) {
                s = after
            }
        }
        // 3) 清理空白与多余首尾标点
        s = s.replace(Regex("\\s+"), "")
            .replace(Regex("^[·\\-—\\s]+|[·\\-—\\s]+$"), "")
        return s.ifEmpty { raw }
    }

    private fun drawTextLines(canvas: Canvas, lines: List<String>, x: Float, top: Float, paint: TextPaint, lineH: Float) {
        var y = top
        for (line in lines) {
            canvas.drawText(line, x, y - paint.ascent(), paint)
            y += lineH
        }
    }

    private fun clusterByOverlap(sorted: List<Course>): List<List<Course>> {
        val clusters = mutableListOf<List<Course>>()
        var current = mutableListOf<Course>()
        var maxEnd = 0
        for (c in sorted) {
            if (current.isNotEmpty() && c.startSection > maxEnd) {
                clusters.add(current)
                current = mutableListOf()
                maxEnd = 0
            }
            current.add(c)
            maxEnd = max(maxEnd, c.endSection)
        }
        if (current.isNotEmpty()) clusters.add(current)
        return clusters
    }

    // ============ 手势：滚动 / 缩放 / 滑动切周 / 点击 ============

    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        // 手势起点：用绝对坐标计算跟手位移（方向始终与手指一致，不依赖系统增量方向）
        private var startX = 0f
        private var startY = 0f
        private var startScrollX = 0f
        private var startScrollY = 0f

        override fun onDown(e: MotionEvent): Boolean {
            startX = e.x
            startY = e.y
            startScrollX = scrollXF
            startScrollY = scrollYF
            // 记录翻页起点偏移（可能在翻页动画中途按下），让拖动能从当前位置接续
            startSlideOffset = slideOffset
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaleDetector.isInProgress) return true
            val totalDx = e2.x - startX
            val totalDy = e2.y - startY
            // 首次越过阈值才锁定方向，避免斜滑/微抖在切周与纵向滚动之间乱跳
            if (axisLock == 0) {
                when {
                    // 未放大时明显的水平手势 → 跟手翻页（角度阈值 1.5，不再"左右太灵敏"）
                    maxSX() == 0f && Math.abs(totalDx) > dp(12f) &&
                            Math.abs(totalDx) > Math.abs(totalDy) * 1.5f -> axisLock = 1
                    // 明显的纵向手势 → 滚动
                    Math.abs(totalDy) > dp(8f) &&
                            Math.abs(totalDy) > Math.abs(totalDx) * 1.2f -> axisLock = 2
                    // 放大后明显的横向手势 → 横向滚动
                    Math.abs(totalDx) > dp(12f) &&
                            Math.abs(totalDx) > Math.abs(totalDy) * 1.5f -> axisLock = 2
                }
            }
            if (axisLock == 1) {
                // 1:1 跟手：整页随手指移动；越界方向加阻尼，只能拉出一点点
                paging = true
                animator?.cancel()
                val raw = startSlideOffset + totalDx
                val delta = if (raw > 0f) -1 else 1
                val can = canSwipeWeek?.invoke(delta) ?: true
                slideOffset = if (can) {
                    raw.coerceIn(-width.toFloat(), width.toFloat())
                } else {
                    (raw * 0.32f).coerceIn(-width * 0.5f, width * 0.5f)
                }
                invalidate()
                return true
            }
            if (axisLock == 2) {
                // 标准滚动语义：手指上推看下面（totalDy 负 → scrollY 增大），方向与手指一致
                scrollXF = (startScrollX - totalDx).coerceIn(0f, maxSX())
                scrollYF = (startScrollY - totalDy).coerceIn(0f, maxSY())
                invalidate()
                return true
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaleDetector.isInProgress) return true
            // 已锁定翻页，或未放大时明显的水平快甩 → 按甩动速度决定翻页/回弹
            if (axisLock == 1 || (maxSX() == 0f && Math.abs(vx) > Math.abs(vy) * 1.5f &&
                            Math.abs(vx) > dp(260f))) {
                paging = false
                axisLock = 0
                settleWeekSwipe(vx)
                return true
            }
            axisLock = 0
            scroller.fling(
                scrollXF.toInt(), scrollYF.toInt(),
                -vx.toInt(), -vy.toInt(),
                0, maxSX().toInt(), 0, maxSY().toInt()
            )
            postInvalidateOnAnimation()
            return true
        }

        // 单击立即响应（不用 onSingleTapConfirmed，避免 300ms 判定延迟造成"点卡片响应慢"）
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (scaleDetector.isInProgress) return true
            // 正在翻页（内容已偏移）时不算点击
            if (Math.abs(slideOffset) > dp(6f)) return true
            handleClick(e.x, e.y)
            return true
        }
    }

    /** 翻页手势起点的横向偏移（onDown 时记录），让动画中途按下可接续拖动 */
    private var startSlideOffset = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            scroller.forceFinished(true)
            animator?.cancel()
            axisLock = 0
            paging = false
            // 不主动清零 slideOffset：若在吸附动画中途按下，onDown 会把当前 offset 记为起点，
            // 手势可从当前位置平滑接续（由 onScroll 负责）
            invalidate()
        }
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            if (paging) {
                paging = false
                settleWeekSwipe(0f)
            }
            axisLock = 0
            if (!scroller.isFinished) postInvalidateOnAnimation()
        }
        return true
    }

    // ---- 周次翻页：松手后一次吸附动画（ViewPager 式），自然缓出、无二段交接 ----

    /**
     * 松手/快甩后吸附：拖过阈值或甩动速度足够 → 翻到相邻周（目标 ±width）；
     * 没到阈值或已到边界 → 回弹到 0。delta：+1 下一周（左滑）/ -1 上一周（右滑）。
     */
    private fun settleWeekSwipe(flingVx: Float) {
        val w = width.toFloat()
        val delta = if (Math.abs(flingVx) > dp(300f)) {
            if (flingVx > 0f) -1 else 1
        } else {
            if (slideOffset > 0f) -1 else 1
        }
        val can = canSwipeWeek?.invoke(delta) ?: true
        val speedOk = Math.abs(flingVx) > dp(420f) && ((flingVx > 0f) == (delta < 0))
        val dragOk = Math.abs(slideOffset) > w * 0.32f
        val go = can && (speedOk || dragOk)
        val target = if (go) (-delta * w) else 0f
        animateSettle(target, go, delta)
    }

    /** 从当前 offset 平滑到 [target]（Decelerate 自然缓出，先快后慢收尾）；翻页结束才提交新周 */
    private fun animateSettle(target: Float, go: Boolean, delta: Int) {
        val from = slideOffset
        if (Math.abs(target - from) < 0.5f) {
            if (go) commitWeek(delta)
            return
        }
        val w = width.toFloat()
        val distance = Math.abs(target - from)
        // 时长随剩余距离：整页约 250ms，短回弹约 140ms；区间 140~300ms，避免"闪一下"
        val dur = (distance / w * 250f).toLong().coerceIn(140L, 300L)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { a ->
                slideOffset = from + (target - from) * (a.animatedValue as Float)
                invalidate()
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(a: android.animation.Animator) {}
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (go) commitWeek(delta)
                }
                override fun onAnimationCancel(a: android.animation.Animator) {}
                override fun onAnimationRepeat(a: android.animation.Animator) {}
            })
            start()
        }
    }

    /** 提交新周：末帧相邻页恰在屏内 0 位，此时切数据并把 offset 归零，视觉无缝不跳变 */
    private fun commitWeek(delta: Int) {
        currentWeek += delta
        slideOffset = 0f
        invalidate()
        onWeekSettled?.invoke(delta)
    }

    /** 缩放动效：平滑过渡到目标倍率（以 focus 为缩放中心），用于双击与缩放吸附 */
    private fun animateScaleTo(target: Float, focusX: Float = width / 2f, focusY: Float = height / 2f) {
        animator?.cancel()
        val from = scale
        val fromX = scrollXF
        val fromY = scrollYF
        val cx = focusX + fromX
        val cy = focusY + fromY
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                scale = from + (target - from) * t
                val r = scale / from
                scrollXF = (cx * r - focusX).coerceIn(0f, maxSX())
                scrollYF = (cy * r - focusY).coerceIn(0f, maxSY())
                invalidate()
            }
            start()
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollXF = scroller.currX.toFloat().coerceIn(0f, maxSX())
            scrollYF = scroller.currY.toFloat().coerceIn(0f, maxSY())
            invalidate()
            postInvalidateOnAnimation()
        }
    }

    private fun handleClick(touchX: Float, touchY: Float) {
        val x = touchX + scrollXF
        val y = touchY + scrollYF
        if (y < headerHeight || x < timeColW()) return
        val cw = colW()
        val day = ((x - timeColW()) / cw).toInt() + 1
        val section = ((y - headerHeight) / secH()).toInt() + 1
        if (day !in 1..7) return

        // v1.47.9：先判断日程方块，命中就进编辑（与课程卡片一样可点）
        for ((todo, rect) in todoHitRects) {
            if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom) {
                onClickTodo?.invoke(todo)
                return
            }
        }

        val candidates = courses.filter {
            it.dayOfWeek == day && it.activeInWeek(currentWeek) &&
                    section >= it.startSection && section <= it.endSection
        }
        if (candidates.isNotEmpty()) {
            val fracInDay = (x - timeColW()) / cw - (day - 1)
            val n = candidates.size
            val idx = (fracInDay * n).toInt().coerceIn(0, n - 1)
            onClickCourse?.invoke(candidates[idx])
        }
    }

    /** 离屏渲染完整课表为 Bitmap（用于导出图片，固定 1x） */
    fun renderToBitmap(targetWidthPx: Int): android.graphics.Bitmap {
        sectionTimes = SectionTimes.load(context)
        totalSections = computeTotalSections()
        // 导出用的 View 可能未 attach（设置页 new ScheduleView(context)），先刷新一次缓存保证颜色/背景正确
        refreshDrawingCache()
        val savedScale = scale
        val savedSX = scrollXF
        val savedSY = scrollYF
        val savedSlide = slideOffset
        scale = 1f
        scrollXF = 0f
        scrollYF = 0f
        slideOffset = 0f
        val contentH = contentH().toInt()
        measure(
            View.MeasureSpec.makeMeasureSpec(targetWidthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(contentH, View.MeasureSpec.EXACTLY)
        )
        layout(0, 0, targetWidthPx, contentH)
        val bmp = android.graphics.Bitmap.createBitmap(targetWidthPx, contentH, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(
            cachedBackgroundColor
                ?: if (cachedDarkMode) cachedSpec.windowDark else cachedSpec.windowLight
        )
        draw(canvas)
        scale = savedScale
        scrollXF = savedSX
        scrollYF = savedSY
        slideOffset = savedSlide
        return bmp
    }

    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
