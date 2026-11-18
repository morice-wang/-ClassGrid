package com.classgrid.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.RemoteViews
import java.util.Calendar
import kotlin.math.roundToInt

/**
 * 桌面小组件「今天的课」。
 *
 * v1.46.0 起整张卡片由 [TodayWidgetRenderer] 画成 Bitmap（小-大-小三栏：已上完 / 正在上或下一节 / 接下来），
 * 不再用一列 TextView。点击行为不变：整卡打开 App，右上角刷新只刷卡片。
 *
 * 为什么用 RemoteViews 而不是 Glance：见 docs/ARCHITECTURE.md 2.11（零新增依赖、minSdk 24）。
 * 刷新时机：系统 updatePeriodMillis、跨天/改时间/时区广播、App 内 WidgetRefresh.refresh。
 */
class TodayWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> render(context, appWidgetManager, id) }
    }

    /**
     * v1.47.3：桌面改了组件尺寸，或有桌面（少数品牌）在添加后重新报了一次尺寸时，
     * 必须马上按新尺寸重画；否则旧 Bitmap 被 fitXY 拉伸，看起来就是「胶囊突然变大变扁」。
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?
    ) {
        render(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_CONFIGURATION_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> refreshAll(context)
        }
    }

    /**
     * 按桌面真实报的尺寸算像素。
     *
     * v1.48.3：恢复自定义尺寸（桌面可拖动缩放），所以必须用「当前实际占位」而不是最小尺寸。
     * 官方约定：竖屏用 MIN_WIDTH + MAX_HEIGHT，横屏用 MAX_WIDTH + MIN_HEIGHT。
     * v1.47.13 一律取 MIN_*，桌面把 4x2 撑到 4 列宽时位图仍只有 250dp 宽，
     * 于是整张卡在 4x2 的格子里比别的组件小一圈。
     *
     * v1.48.4：一部分品牌桌面（MIUI/HyperOS、ColorOS、OriginOS、One UI 等）在 options 里把
     * MAX_HEIGHT 报成「这个组件最多能拉伸多高」，而不是当前占位。位图按它画得比桌面格子高一截，
     * ImageView 的 fitXY 再把它压回去 —— 用户看到的就是「内容被压扁、有点小」，
     * 只有拖到接近最大值时才刚好正常。手机上没法反查桌面真实格子，这里加一道高度护栏：
     * 高度不超过宽度的 1.7 倍（正常 4x2 ≈0.45、4x4 ≈1.0，只有离谱回报会被削平）。
     */
    private fun widgetSize(context: Context, options: android.os.Bundle?): IntArray {
        val density = context.resources.displayMetrics.density
        fun opt(key: String): Int = options?.getInt(key, 0) ?: 0
        val portrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val minW = opt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val maxW = opt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        val minH = opt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        val maxH = opt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        var wDp = if (portrait) minW else maxW
        if (wDp <= 0) wDp = if (minW > 0) minW else maxW
        var hDp = if (portrait) maxH else minH
        if (hDp <= 0) hDp = if (maxH > 0) maxH else minH
        if (wDp <= 0) wDp = 250
        if (hDp <= 0) hDp = 110
        // 允许 2x2 ~ 6x6；桌面偶尔报 0 或离谱值，收敛到安全区间
        wDp = wDp.coerceIn(110, 640)
        hDp = hDp.coerceIn(90, 640)
        // v1.48.4：部分品牌桌面把 MAX_HEIGHT 报成「可拉伸的最大值」（正常桌面 max 与 min 只差一格）。
        // 两个轴一对比就能看出来：高度跨度远超 min 时，max 不是当前占位，退回默认 2 行 ≈ minH * 2。
        if (minH > 0 && hDp > minH * 2.2f) hDp = minH * 2
        // 再加一道形状护栏：正常 4x2 ≈0.45、4x4 ≈1.0，只有离谱回报会被削到这里。
        val heightCap = (wDp * 1.7f).roundToInt().coerceAtLeast(110)
        if (hDp > heightCap) hDp = heightCap
        val widthPx = (wDp * density).roundToInt().coerceAtLeast(1)
        val heightPx = (hDp * density).roundToInt().coerceAtLeast(1)
        // v1.48.8：宽高 dp 一起带出去，provider 用宽度决定要不要换紧凑布局
        return intArrayOf(widthPx, heightPx, wDp, hDp)
    }

    /**
     * v1.48.8：内容层改成真正的布局（TextView + 胶囊底位图），不再画整张内容位图。
     *
     * 旧做法的死穴：内容位图按 [widgetSize] 报的尺寸画，再用 centerInside 塞进格子。
     * 部分品牌桌面把 MAX_HEIGHT 报成「最多能拉多高」，位图比真实格子高，
     * centerInside 只能等比缩小 —— 于是「胶囊变短、两边留一大片」，且只有拉大才正常。
     * 现在宽高由 LinearLayout / layout_weight 自己量，桌面报的尺寸再错也不会缩水；
     * 文字交给 TextView（sp，跟随系统字体与字号），只有胶囊底还是位图，被 fitXY 拉也只影响圆角。
     */
    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val snapshot = runCatching { TodayWidgetData.snapshot(context) }.getOrElse {
            TodayWidgetData.build(
                emptyList(),
                Prefs.currentWeek(context),
                TodayWidgetData.weekdayOf(Calendar.getInstance()),
                0,
                SectionTimes.load(context)
            )
        }

        val options = runCatching { manager.getAppWidgetOptions(widgetId) }.getOrNull()
        val size = widgetSize(context, options)
        val widthPx = size[0]
        val heightPx = size[1]
        val widthDp = size[2]

        val dark = ScheduleColor.isDarkMode(context)
        val accent = AppTheme.primary(context, dark)
        val text = AppTheme.text(context, dark)
        val muted = AppTheme.textMuted(context, dark)

        // 2x2 这种窄格子换紧凑布局（只有「今天」和主胶囊），否则文字会被挤成一条缝
        val compact = widthDp < 190
        val views = RemoteViews(
            context.packageName,
            if (compact) R.layout.widget_today_compact else R.layout.widget_today
        )

        val cardBmp = runCatching {
            TodayWidgetRenderer.card(context, widthPx, heightPx, dark)
        }.getOrNull() ?: return
        views.setImageViewBitmap(R.id.widgetCard, cardBmp)

        val density = context.resources.displayMetrics.density
        fun px(dp: Float): Int = (dp * density).roundToInt().coerceAtLeast(1)
        // 系统字体调大时（用户把字号拉到 1.3），主胶囊里的三行字也会变高，
        // 胶囊必须跟着长，否则文字会溢出到胶囊外面。夹在 1..1.25 与 textPaint 一致。
        val uiFontScale = (context.resources.displayMetrics.scaledDensity / density).coerceIn(1f, 1.25f)

        // 文案：进行中/下一节 → 课；只有已上完 → 结束；今天没课 → 空态
        val focus = snapshot.focusRow
        val title: String
        val sub: String
        val timeText: String
        val roomText: String
        if (focus != null) {
            timeText = focus.time
            roomText = focus.room
            title = focus.name
            sub = if (focus.state == TodayWidgetData.STATE_ONGOING) {
                val left = focus.endMinutes - snapshot.nowMinutes
                if (left > 0) "还剩 " + left + " 分" else "即将下课"
            } else {
                "即将开始"
            }
        } else if (snapshot.hasCourses) {
            timeText = ""
            roomText = ""
            title = "今天的课程已经结束"
            sub = "辛苦了"
        } else {
            timeText = ""
            roomText = ""
            title = "今天没有课"
            sub = "好好休息一下"
        }

        // 胶囊大小只跟卡片宽度走（不随高度变）：格子被拉高时胶囊形状不变，
        // 布局里 ImageView 用 wrap_content + center_vertical 居中。
        // 内容从顶部往下排，能塞几行由真实格子高度算，塞不下的不显示，永远不溢出。
        // 主胶囊高度：v1.48.9 调回「1.47.1 那种正常的宽高」——比 0.22 更饱满，
        // 且不低于三行字撑出来的高度（16.5sp + 19sp + 11sp + 上下内边距），
        // 保证「时间/教室 + 课名 + 状态」三行都稳稳在胶囊里，不出现文字跑出胶囊。
        val heroTextMinPx = px(88f * uiFontScale)
        val heroDesignPx = if (compact) {
            px(74f * uiFontScale)
        } else if (focus == null) {
            (widthPx * 0.34f).toInt().coerceIn(px(96f * uiFontScale), px(150f * uiFontScale))
        } else {
            maxOf(
                (widthPx * 0.30f).toInt().coerceIn(px(56f), px(126f)),
                heroTextMinPx
            )
        }
        val rowDesignPx = (widthPx * 0.105f).toInt().coerceIn(px(30f), px(50f))
        val candidates = if (focus != null) snapshot.laterRows else snapshot.allRows
        // 留 10dp 余量：有些桌面把 MAX_HEIGHT 报得比真实格子高，多算一行会被裁掉
        val fitPx = heightPx - px(12f) * 2 - px(26f) - px(8f) - heroDesignPx - px(10f)
        val fitRows = (fitPx / (px(6f) + rowDesignPx).toFloat()).toInt()
        val shownRows = if (compact) 0 else fitRows.coerceIn(0, minOf(candidates.size, 4))
        val heroBmp = runCatching {
            TodayWidgetRenderer.pill(context, widthPx, heroDesignPx, dark, TodayWidgetRenderer.PILL_HERO)
        }.getOrNull()
        val rowBmp = runCatching {
            TodayWidgetRenderer.pill(context, widthPx, rowDesignPx, dark, TodayWidgetRenderer.PILL_ROW)
        }.getOrNull()
        if (heroBmp != null) views.setImageViewBitmap(R.id.heroBg, heroBmp)

        if (compact) {
            views.setTextViewText(
                R.id.txtToday,
                "今天：周" + TodayWidgetData.weekdayName(snapshot.weekday)
            )
            views.setTextColor(R.id.txtToday, text)
            if (timeText.isEmpty()) {
                views.setViewVisibility(R.id.txtHeroTime, View.GONE)
            } else {
                views.setViewVisibility(R.id.txtHeroTime, View.VISIBLE)
                views.setTextViewText(R.id.txtHeroTime, timeText)
                views.setTextColor(R.id.txtHeroTime, accent)
            }
            views.setTextViewText(R.id.txtHeroName, title)
            views.setTextColor(R.id.txtHeroName, text)
            views.setTextViewText(R.id.txtHeroSub, sub)
            views.setTextColor(R.id.txtHeroSub, accent)
        } else {
            val refreshBmp = runCatching {
                TodayWidgetRenderer.refreshIcon(context, px(22f), dark)
            }.getOrNull()
            if (refreshBmp != null) views.setImageViewBitmap(R.id.iconRefresh, refreshBmp)

            // v1.48.11：小胶囊的宽度按文字实际宽度画（文字宽 + 左右内边距），
            // 不再用固定 96dp / 80dp。布局里背景 ImageView 是 match_parent，
            // 位图的「固有宽度」就是外面 wrap_content FrameLayout 的宽度来源 ——
            // 固定宽度会让文字靠在左边、右边空出一大片（用户看到的「第 11 周」怪样）。
            val todayText = "今天：周" + TodayWidgetData.weekdayName(snapshot.weekday)
            val weekText = context.getString(R.string.widget_today_week, Prefs.currentWeek(context))
            val chipPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            fun chipWidth(text: String, sp: Float, padDp: Float, medium: Boolean): Int {
                chipPaint.textSize = android.util.TypedValue.applyDimension(
                    android.util.TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics
                )
                chipPaint.typeface = if (medium) {
                    android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                } else {
                    android.graphics.Typeface.DEFAULT
                }
                val textPx = chipPaint.measureText(text).roundToInt()
                // 左右各留 padDp，再给 2dp 余量，避免个别字体量宽少一点被裁
                return (textPx + px(padDp) * 2 + px(2f)).coerceAtLeast(px(30f))
            }
            val chipToday = runCatching {
                TodayWidgetRenderer.pill(
                    context, chipWidth(todayText, 12.5f, 13f, true), px(26f),
                    dark, TodayWidgetRenderer.PILL_CHIP, 2f
                )
            }.getOrNull()
            val chipWeek = runCatching {
                TodayWidgetRenderer.pill(
                    context, chipWidth(weekText, 11f, 11f, false), px(26f),
                    dark, TodayWidgetRenderer.PILL_CHIP, 2f
                )
            }.getOrNull()
            if (chipToday != null) views.setImageViewBitmap(R.id.chipTodayBg, chipToday)
            if (chipWeek != null) views.setImageViewBitmap(R.id.chipWeekBg, chipWeek)
            views.setTextViewText(R.id.txtToday, todayText)
            views.setTextColor(R.id.txtToday, accent)
            views.setTextViewText(R.id.txtWeek, weekText)
            views.setTextColor(R.id.txtWeek, muted)

            if (timeText.isEmpty()) {
                views.setViewVisibility(R.id.heroTimeRow, View.GONE)
            } else {
                views.setViewVisibility(R.id.heroTimeRow, View.VISIBLE)
                views.setTextViewText(R.id.txtHeroTime, timeText)
                views.setTextColor(R.id.txtHeroTime, text)
                views.setTextViewText(R.id.txtHeroRoom, roomText)
                views.setTextColor(R.id.txtHeroRoom, muted)
            }
            views.setTextViewText(R.id.txtHeroName, title)
            views.setTextColor(R.id.txtHeroName, text)
            views.setTextViewText(R.id.txtHeroSub, sub)
            views.setTextColor(R.id.txtHeroSub, accent)

            // 后续课程：能塞几行显示几行，其余隐藏
            val rows = candidates
            val boxes = intArrayOf(R.id.row1Box, R.id.row2Box, R.id.row3Box, R.id.row4Box)
            val bgs = intArrayOf(R.id.row1Bg, R.id.row2Bg, R.id.row3Bg, R.id.row4Bg)
            val times = intArrayOf(R.id.row1Time, R.id.row2Time, R.id.row3Time, R.id.row4Time)
            val names = intArrayOf(R.id.row1Name, R.id.row2Name, R.id.row3Name, R.id.row4Name)
            val rooms = intArrayOf(R.id.row1Room, R.id.row2Room, R.id.row3Room, R.id.row4Room)
            for (i in boxes.indices) {
                val row = if (i < shownRows) rows.getOrNull(i) else null
                if (row == null) {
                    views.setViewVisibility(boxes[i], View.GONE)
                } else {
                    views.setViewVisibility(boxes[i], View.VISIBLE)
                    if (rowBmp != null) views.setImageViewBitmap(bgs[i], rowBmp)
                    views.setTextViewText(times[i], row.time.substringBefore('-'))
                    views.setTextColor(
                        times[i],
                        if (row.state == TodayWidgetData.STATE_DONE) muted else accent
                    )
                    views.setTextViewText(names[i], row.name)
                    views.setTextColor(
                        names[i],
                        if (row.state == TodayWidgetData.STATE_DONE) muted else text
                    )
                    views.setTextViewText(rooms[i], row.room)
                    views.setTextColor(rooms[i], muted)
                }
            }
        }

        // 读屏软件读到的是文字摘要，而不是一堆图形
        runCatching {
            views.setContentDescription(R.id.widgetRoot, TodayWidgetData.statusText(snapshot))
        }
        views.setOnClickPendingIntent(R.id.widgetRoot, openAppIntent(context))
        views.setOnClickPendingIntent(R.id.btnRefresh, refreshIntent(context))
        manager.updateAppWidget(widgetId, views)
    }

    companion object {
        const val ACTION_REFRESH = "com.classgrid.app.action.WIDGET_REFRESH"

        private const val REQ_OPEN_APP = 100
        private const val REQ_REFRESH = 101

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
            if (ids.isEmpty()) return
            ids.forEach { id -> TodayWidgetProvider().render(context, manager, id) }
        }

        private fun openAppIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                REQ_OPEN_APP,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun refreshIntent(context: Context): PendingIntent {
            val intent = Intent(context, TodayWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            return PendingIntent.getBroadcast(
                context,
                REQ_REFRESH,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
