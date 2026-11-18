package com.classgrid.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import kotlin.math.roundToInt

/**
 * 桌面小组件「今天的课」，v1.48.6 起拆成两层：
 *
 *  - [card]：卡片底（渐变 / 描边 / 顶部高光 / 底部压暗），ImageView 用 fitXY 铺满整格，
 *    所以不管桌面把组件拉成什么比例，卡片本身永远严丝合缝、不会露出桌面壁纸；
 *  - [content]：胶囊与文字，透明底，ImageView 用 centerInside 居中显示 —— 尺寸对不上时
 *    只会多留一点边、或者整体等比缩小，**不会被 fitXY 非等比拉伸**。
 *
 * 这一点是「有的手机看着被压缩」的根因：部分品牌桌面在 options 里把 MAX_HEIGHT 报成
 * 「最多能拉多高」而不是「现在多高」，位图按它画得比格子高一截，fitXY 再压回去，字就被
 * 竖向压扁了，只有拖到接近最大尺寸时才刚好正常。拆层之后内容层永远等比，最坏也只是小一圈。
 *
 * 字号一律用 dp（跟系统字体大小走 sp 缩放），只随卡片**宽度**微调 0.82~1.12 倍：
 * 高度报错、组件被拉高，都不会再把字放大/缩小；行数按可用高度自适应，排不下就少画几行。
 */
object TodayWidgetRenderer {

    // 小组件用到的字体族。默认跟随系统（sans-serif 会被品牌主题字体替换）。
    private const val FONT_FAMILY = "sans-serif"
    private const val FONT_FAMILY_MEDIUM = "sans-serif-medium"

    private fun dp(context: Context, value: Float): Float =
        value * context.resources.displayMetrics.density

    /** 系统字体缩放（设置 → 显示 → 字体大小），上限 1.25 防止超大字体撑破胶囊。 */
    private fun fontScale(context: Context): Float {
        val dm = context.resources.displayMetrics
        if (dm.density <= 0f) return 1f
        return (dm.scaledDensity / dm.density).coerceIn(1f, 1.25f)
    }

    // ================= 卡片底 =================

    fun card(context: Context, widthPx: Int, heightPx: Int, dark: Boolean): Bitmap {
        val w = widthPx.coerceAtLeast(1).toFloat()
        val h = heightPx.coerceAtLeast(1).toFloat()
        val bitmap = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val cardColor = AppTheme.cardBackground(context, dark)
        val accent = AppTheme.primary(context, dark)
        val line = AppTheme.line(context, dark)
        val radius = dp(context, 24f).coerceAtMost(w / 2f).coerceAtMost(h / 2f)

        val tinted = blend(cardColor, accent, if (dark) 0.14f else 0.07f)
        canvas.drawRoundRect(
            RectF(0f, 0f, w, h), radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, w, h,
                    intArrayOf(cardColor, tinted),
                    floatArrayOf(0f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        )

        val path = Path().apply {
            addRoundRect(RectF(0f, 0f, w, h), radius, radius, Path.Direction.CW)
        }
        canvas.save()
        canvas.clipPath(path)
        canvas.drawRect(
            RectF(0f, 0f, w, h * 0.42f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, h * 0.42f,
                    intArrayOf(withAlpha(0xFFFFFF, 30), withAlpha(0xFFFFFF, 0)),
                    null, Shader.TileMode.CLAMP
                )
            }
        )
        canvas.drawRect(
            RectF(0f, h * 0.72f, w, h),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, h * 0.72f, 0f, h,
                    intArrayOf(withAlpha(0x000000, 0), withAlpha(0x000000, if (dark) 40 else 22)),
                    null, Shader.TileMode.CLAMP
                )
            }
        )
        canvas.restore()

        canvas.drawRoundRect(
            RectF(dp(context, 0.5f), dp(context, 0.5f), w - dp(context, 0.5f), h - dp(context, 0.5f)),
            radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp(context, 1f)
                color = line
            }
        )
        return bitmap
    }

    // ================= 内容层 =================

    fun content(
        context: Context,
        snapshot: TodayWidgetData.Snapshot,
        widthPx: Int,
        heightPx: Int,
        dark: Boolean
    ): Bitmap {
        val w = widthPx.coerceAtLeast(1).toFloat()
        val h = heightPx.coerceAtLeast(1).toFloat()
        val bitmap = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val density = context.resources.displayMetrics.density
        // 字号、内边距、胶囊高度只跟宽度相关：把组件拉高不会把字放大，拉窄也不会挤成一团。
        val ui = ((w / density) / 300f).coerceIn(0.82f, 1.12f)
        fun s(value: Float): Float = value * density * ui

        val accent = AppTheme.primary(context, dark)
        val textColor = AppTheme.text(context, dark)
        val muted = AppTheme.textMuted(context, dark)
        val cardColor = AppTheme.cardBackground(context, dark)

        val pad = s(14f)
        val headerH = s(22f)

        // ---- 顶部：今天 + 周次 + 刷新 ----
        val todayLabel = "今天：" + TodayWidgetData.weekdayName(snapshot.weekday)
        val todayPaint = textPaint(context, 11.5f * ui, textColor, medium = true)
        val todayW = todayPaint.measureText(todayLabel)
        val todayPill = RectF(pad, pad, pad + todayW + s(22f), pad + headerH)
        drawPill(
            canvas, context, todayPill, headerH / 2f,
            blend(cardColor, accent, if (dark) 0.24f else 0.13f), dark
        )
        canvas.drawText(
            todayLabel,
            todayPill.centerX() - todayW / 2f,
            todayPill.centerY() + todayPaint.textSize * 0.36f,
            todayPaint
        )

        val weekLabel = "第" + snapshot.week + "周"
        val weekPaint = textPaint(context, 10f * ui, muted)
        val weekW = weekPaint.measureText(weekLabel)
        val weekPill = RectF(
            todayPill.right + s(7f), pad,
            todayPill.right + s(7f) + weekW + s(16f), pad + headerH
        )
        drawPill(canvas, context, weekPill, headerH / 2f, blend(cardColor, muted, 0.12f), dark)
        canvas.drawText(
            weekLabel,
            weekPill.centerX() - weekW / 2f,
            weekPill.centerY() + weekPaint.textSize * 0.36f,
            weekPaint
        )

        drawRefresh(canvas, w - s(18f), pad + headerH / 2f, s(6.5f), muted)

        // ---- 内容区：主胶囊 + 下面的课 ----
        val contentTop = pad + headerH + s(10f)
        val contentBottom = h - pad
        val avail = (contentBottom - contentTop).coerceAtLeast(s(28f))

        val rowH = s(32f)
        val rowGap = s(6f)
        val listGap = s(10f)
        val heroMin = s(62f)
        val heroMax = s(118f)

        val focus = snapshot.focusRow
        val done = snapshot.doneRows.asReversed()
        if (focus == null) {
            // 没有课 / 今天已结束：胶囊高度固定；卡片很高时把今天已上的课接着列出来，不留一大片空白。
            val heroH = avail.coerceAtMost(s(150f)).coerceAtLeast(s(56f))
            val fits = (((avail - heroH - listGap + rowGap) / (rowH + rowGap)).toInt())
                .coerceIn(0, minOf(done.size, 8))
            val top = if (fits == 0) contentTop + (avail - heroH) / 2f else contentTop
            drawRestCapsule(
                canvas, context, snapshot,
                RectF(pad, top, w - pad, top + heroH), ui,
                textColor, muted, accent, dark
            )
            var y = top + heroH + listGap
            for (i in 0 until fits) {
                drawRow(
                    canvas, context, done[i],
                    RectF(pad, y, w - pad, y + rowH),
                    ui, accent, textColor, muted, cardColor, dim = true
                )
                y += rowH + rowGap
            }
            return bitmap
        }

        // 上完的课接在后面的课之后（颜色淡一些），高卡片就能把一整天铺满。
        val candidates = snapshot.laterRows + done
        val fittingRows = (((avail - heroMin - listGap + rowGap) / (rowH + rowGap)).toInt())
            .coerceIn(0, minOf(candidates.size, 8))
        val listH = if (fittingRows == 0) 0f else fittingRows * rowH + (fittingRows - 1) * rowGap
        val heroH = if (fittingRows == 0) {
            avail.coerceAtMost(heroMax)
        } else {
            (avail - listH - listGap).coerceIn(heroMin, heroMax)
        }
        val contentH = heroH + if (fittingRows == 0) 0f else listGap + listH
        // 内容从顶部往下排：卡片再高也不会出现「胶囊悬在正中、上下两片空白」。
        val top0 = contentTop

        val heroRect = RectF(pad, top0, w - pad, top0 + heroH)
        drawFocusCapsule(canvas, context, snapshot, focus, heroRect, ui, textColor, muted, accent, dark)

        var top = heroRect.bottom + listGap
        for (i in 0 until fittingRows) {
            drawRow(
                canvas, context, candidates[i],
                RectF(pad, top, w - pad, top + rowH),
                ui, accent, textColor, muted, cardColor,
                dim = candidates[i].state == TodayWidgetData.STATE_DONE
            )
            top += rowH + rowGap
        }

        // 课不多而卡片很高时，把剩余留白用一句汇总收个尾，避免看起来像漏画了。
        if (avail - contentH > s(30f)) {
            val foot = textPaint(context, 10f * ui, muted)
            val footText = "今天共 " + snapshot.allRows.size + " 节课"
            val footY = contentBottom - s(2f)
            canvas.drawText(
                footText,
                (w - pad) - foot.measureText(footText),
                footY - foot.fontMetrics.descent,
                foot
            )
        }
        return bitmap
    }

    // ================= 主胶囊（正在上 / 下一节） =================

    private fun drawFocusCapsule(
        canvas: Canvas,
        context: Context,
        snapshot: TodayWidgetData.Snapshot,
        focus: TodayWidgetData.Row,
        rect: RectF,
        ui: Float,
        text: Int,
        muted: Int,
        accent: Int,
        dark: Boolean
    ) {
        capsuleBackground(canvas, context, rect, dp(context, 30f), dark, accent)

        val isOngoing = focus.state == TodayWidgetData.STATE_ONGOING
        val timePaint = textPaint(context, 15.5f * ui, text, medium = true)
        val roomPaint = textPaint(context, 10.5f * ui, muted)
        val namePaint = textPaint(context, 19f * ui, text, medium = true)
        val cdPaint = textPaint(context, 10f * ui, accent, medium = true)

        val maxLineW = rect.width() - dp(context, 26f)
        val timeText = focus.time
        val timeW = timePaint.measureText(timeText)
        val roomText = focus.room.trim()
        val gap1 = if (roomText.isEmpty()) 0f else dp(context, 8f)
        val roomShown = if (roomText.isEmpty()) "" else
            ellipsize(roomText, roomPaint, (maxLineW - timeW - gap1).coerceAtLeast(0f))
        val roomW = if (roomShown.isEmpty()) 0f else roomPaint.measureText(roomShown)
        val line1W = timeW + gap1 + roomW

        val nameText = ellipsize(focus.name, namePaint, maxLineW)
        val nameW = namePaint.measureText(nameText)

        val timeFm = timePaint.fontMetrics
        val nameFm = namePaint.fontMetrics
        val line1H = timeFm.descent - timeFm.ascent
        val line2H = nameFm.descent - nameFm.ascent
        val gapV = dp(context, 3f)
        val cdH = if (isOngoing) cdPaint.fontMetrics.descent - cdPaint.fontMetrics.ascent else 0f
        val blockH = line1H + gapV + line2H + if (isOngoing) gapV + cdH else 0f
        var top = rect.centerY() - blockH / 2f

        val centerX = rect.centerX()
        var cursor = centerX - line1W / 2f
        canvas.drawText(timeText, cursor, top - timeFm.ascent, timePaint)
        cursor += timeW + gap1
        if (roomShown.isNotEmpty()) {
            val roomBaseline = top + line1H / 2f -
                (roomPaint.fontMetrics.ascent + roomPaint.fontMetrics.descent) / 2f
            canvas.drawText(roomShown, cursor, roomBaseline, roomPaint)
        }

        top += line1H + gapV
        canvas.drawText(nameText, centerX - nameW / 2f, top - nameFm.ascent, namePaint)

        if (isOngoing) {
            top += line2H + gapV
            val left = (focus.endMinutes - snapshot.nowMinutes).coerceAtLeast(0)
            val cd = if (left <= 0) "即将下课" else "还剩 " + left + " 分"
            val cdW = cdPaint.measureText(cd)
            canvas.drawText(cd, centerX - cdW / 2f, top - cdPaint.fontMetrics.ascent, cdPaint)
        }
    }

    // ================= 没有课 / 今天已结束 =================

    private fun drawRestCapsule(
        canvas: Canvas,
        context: Context,
        snapshot: TodayWidgetData.Snapshot,
        rect: RectF,
        ui: Float,
        text: Int,
        muted: Int,
        accent: Int,
        dark: Boolean
    ) {
        val ended = snapshot.hasCourses
        capsuleBackground(canvas, context, rect, dp(context, 30f), dark, accent)

        val title = if (ended) "今天的课程已经结束" else "今天没有课"
        val sub = if (ended) "辛苦了" else "好好休息一下"
        val maxTextW = rect.width() * 0.78f

        val titlePaint = fitPaint(context, title, 19f * ui, maxTextW, text, medium = true)
        val subPaint = fitPaint(context, sub, 11.5f * ui, maxTextW, muted, medium = false)

        val titleFm = titlePaint.fontMetrics
        val subFm = subPaint.fontMetrics
        val titleH = titleFm.descent - titleFm.ascent
        val subH = subFm.descent - subFm.ascent
        val gapV = dp(context, 4f)
        val blockH = titleH + gapV + subH
        val textTop = rect.centerY() - blockH / 2f
        val centerX = rect.centerX()

        canvas.drawText(
            title, centerX - titlePaint.measureText(title) / 2f,
            textTop - titleFm.ascent, titlePaint
        )
        val subW = subPaint.measureText(sub)
        val subY = textTop + titleH + gapV - subFm.ascent
        if (ended) {
            val sparkle = (subW * 0.09f).coerceIn(dp(context, 2.6f), dp(context, 7f))
            drawSparkle(canvas, centerX - subW / 2f - sparkle * 2.6f, subY - sparkle * 1.1f, sparkle, accent)
        }
        canvas.drawText(sub, centerX - subW / 2f + if (ended) subW * 0.045f else 0f, subY, subPaint)
    }

    // ================= 下面的一门课（一行） =================

    private fun drawRow(
        canvas: Canvas,
        context: Context,
        row: TodayWidgetData.Row,
        rect: RectF,
        ui: Float,
        accent: Int,
        text: Int,
        muted: Int,
        cardColor: Int,
        dim: Boolean = false
    ) {
        val ongoing = row.state == TodayWidgetData.STATE_ONGOING
        val bg = if (ongoing) blend(cardColor, accent, 0.16f) else blend(cardColor, accent, if (dim) 0.04f else 0.07f)
        val radius = (rect.height() / 2f).coerceAtMost(dp(context, 16f))
        canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })

        // 已经上完的课淡一档，方便和还没上的区分。
        val timeColor = if (dim) blend(accent, cardColor, 0.35f) else accent
        val nameColor = if (dim) blend(text, cardColor, 0.45f) else text
        val timePaint = textPaint(context, 11f * ui, timeColor, medium = true)
        val namePaint = textPaint(context, 12.5f * ui, nameColor, medium = true)
        val roomPaint = textPaint(context, 10f * ui, muted)

        val inset = dp(context, 12f)
        val time = row.time.substringBefore('-').trim()
        val baseline = rect.centerY() -
            (timePaint.fontMetrics.ascent + timePaint.fontMetrics.descent) / 2f
        var x = rect.left + inset
        canvas.drawText(time, x, baseline, timePaint)
        x += timePaint.measureText(time) + dp(context, 8f)

        val roomText = row.room.trim()
        val maxRoomW = rect.width() * 0.38f
        val roomShown = if (roomText.isEmpty()) "" else ellipsize(roomText, roomPaint, maxRoomW)
        val roomW = if (roomShown.isEmpty()) 0f else roomPaint.measureText(roomShown)
        val roomX = rect.right - inset - roomW
        val nameMax = (roomX - x - dp(context, 6f)).coerceAtLeast(dp(context, 24f))
        val nameShown = ellipsize(row.name, namePaint, nameMax)
        val nameBase = rect.centerY() -
            (namePaint.fontMetrics.ascent + namePaint.fontMetrics.descent) / 2f
        canvas.drawText(nameShown, x, nameBase, namePaint)
        if (roomShown.isNotEmpty()) canvas.drawText(roomShown, roomX, baseline, roomPaint)
    }

    // ================= 胶囊底 =================

    private fun capsuleBackground(
        canvas: Canvas,
        context: Context,
        rect: RectF,
        maxRadius: Float,
        dark: Boolean,
        accent: Int,
        strength: Float = 1f
    ) {
        val cardColor = AppTheme.cardBackground(context, dark)
        val radius = (rect.height() / 2f).coerceAtMost(maxRadius)

        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(0x000000, if (dark) 90 else 52)
            maskFilter = BlurMaskFilter(dp(context, 6f), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(
            RectF(rect.left + dp(context, 2f), rect.top + dp(context, 3f),
                rect.right - dp(context, 2f), rect.bottom + dp(context, 3f)),
            radius, radius, shadow
        )

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                intArrayOf(
                    blend(cardColor, accent, (if (dark) 0.40f else 0.22f) * strength),
                    blend(cardColor, accent, (if (dark) 0.26f else 0.13f) * strength),
                    blend(cardColor, accent, (if (dark) 0.14f else 0.08f) * strength)
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, fill)

        canvas.save()
        canvas.clipPath(Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        })
        canvas.drawRect(
            RectF(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.5f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    rect.left, rect.top, rect.left, rect.top + rect.height() * 0.5f,
                    intArrayOf(withAlpha(0xFFFFFF, if (dark) 40 else 120), withAlpha(0xFFFFFF, 0)),
                    null, Shader.TileMode.CLAMP
                )
            }
        )
        canvas.restore()

        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(context, 1f)
            color = blend(accent, cardColor, 0.55f)
            alpha = 170
        }
        canvas.drawRoundRect(rect, radius, radius, edge)
    }

    // ================= 小图形 =================

    /** 顶部小胶囊：柔和投影 + 纵向渐变 + 顶部一道高光，让胶囊有厚度 */
    private fun drawPill(
        canvas: Canvas,
        context: Context,
        rect: RectF,
        radius: Float,
        base: Int,
        dark: Boolean
    ) {
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(0x000000, if (dark) 70 else 34)
            maskFilter = BlurMaskFilter(dp(context, 4f), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(
            RectF(rect.left + dp(context, 1f), rect.top + dp(context, 1.5f),
                rect.right - dp(context, 1f), rect.bottom + dp(context, 1.5f)),
            radius, radius, shadow
        )
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                intArrayOf(blend(base, 0xFFFFFF, 0.10f), base, blend(base, 0x000000, 0.06f)),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.save()
        canvas.clipPath(Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        })
        canvas.drawRect(
            RectF(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.55f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    rect.left, rect.top, rect.left, rect.top + rect.height() * 0.55f,
                    intArrayOf(withAlpha(0xFFFFFF, if (dark) 26 else 90), withAlpha(0xFFFFFF, 0)),
                    null, Shader.TileMode.CLAMP
                )
            }
        )
        canvas.restore()
    }

    // v1.48.10：刷新图标的箭头以前画在「大概右上」的位置，并不在圆弧断口上，
    // 小尺寸下就和圆弧糊成一圈，看着像箭头丢了。现在缺口留在右上，箭头做成
    // 实心三角、底边正好压在圆弧端点上，朝顺时针方向，任何尺寸都一眼能认出来。
    private fun drawRefresh(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val stroke = r * 0.23f
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            this.color = color
        }
        // 从 -42° 顺时针 300°，断口落在右上，箭头就长在断口这一侧
        canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -42f, 300f, false, arc)
        val end = Math.toRadians(258.0)
        val vx = cx + r * Math.cos(end).toFloat()
        val vy = cy + r * Math.sin(end).toFloat()
        // 顺时针切向与法向
        val tx = -Math.sin(end).toFloat()
        val ty = Math.cos(end).toFloat()
        val nx = -ty
        val ny = tx
        val len = r * 0.66f
        val half = stroke * 1.45f
        val head = Path()
        head.moveTo(vx + tx * len, vy + ty * len)
        head.lineTo(vx - tx * len * 0.22f + nx * half, vy - ty * len * 0.22f + ny * half)
        head.lineTo(vx - tx * len * 0.22f - nx * half, vy - ty * len * 0.22f - ny * half)
        head.close()
        canvas.drawPath(head, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    /** 四角星：完成态「辛苦了」旁边的小装饰 */
    private fun drawSparkle(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            alpha = 200
        }
        val path = Path()
        path.moveTo(cx, cy - r)
        path.quadTo(cx + r * 0.18f, cy - r * 0.18f, cx + r, cy)
        path.quadTo(cx + r * 0.18f, cy + r * 0.18f, cx, cy + r)
        path.quadTo(cx - r * 0.18f, cy + r * 0.18f, cx - r, cy)
        path.quadTo(cx - r * 0.18f, cy - r * 0.18f, cx, cy - r)
        path.close()
        canvas.drawPath(path, paint)
    }

    // ================= 供 RemoteViews 布局使用的胶囊底 =================
    // v1.48.8：内容层改成真布局后，胶囊底还是画成位图贴到 ImageView 上，
    // 因为强调色随主题变化，静态 drawable 跟不上。布局用 fitXY 拉这张图，
    // 拉的是胶囊本身（不是文字），所以再大的偏差也只会让圆角差一点点，
    // 绝不会像以前那样把整层内容等比缩小、两边留一大片。

    const val PILL_HERO = 0
    const val PILL_ROW = 1
    const val PILL_CHIP = 2

    fun pill(
        context: Context,
        widthPx: Int,
        heightPx: Int,
        dark: Boolean,
        level: Int,
        insetDp: Float = 4f
    ): Bitmap {
        val w = widthPx.coerceIn(2, 4000)
        val h = heightPx.coerceIn(2, 4000)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val accent = AppTheme.primary(context, dark)
        // 留一点边给投影，模糊超出边界的部分会被裁掉，所以内缩而不是贴边。
        // v1.48.10：顶部小胶囊要「文字别顶满胶囊」，所以它的内缩单独调小。
        val inset = dp(context, insetDp).coerceAtMost(h / 4f)
        val rect = RectF(inset, inset, w - inset, h - inset)
        val strength = when (level) {
            PILL_HERO -> 1f
            PILL_ROW -> 0.32f
            else -> 0.70f
        }
        capsuleBackground(canvas, context, rect, rect.height() / 2f, dark, accent, strength)
        return bitmap
    }

    fun refreshIcon(context: Context, sizePx: Int, dark: Boolean): Bitmap {
        val s = sizePx.coerceIn(8, 512)
        val bitmap = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawRefresh(canvas, s / 2f, s / 2f, s * 0.34f, AppTheme.textMuted(context, dark))
        return bitmap
    }

    // ================= 画笔 =================

    private fun textPaint(context: Context, sizeSp: Float, color: Int, medium: Boolean = false): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            // v1.48.4：按 sp 绘制，跟随系统「字体大小」设置。
            textSize = dp(context, sizeSp) * fontScale(context)
            this.color = color
            typeface = Typeface.create(
                if (medium) FONT_FAMILY_MEDIUM else FONT_FAMILY,
                Typeface.NORMAL
            )
            isSubpixelText = true
        }

    /**
     * 只在「一行放不下」时按比例缩一点（最多缩到 72%），不再是「完全按文字宽度反推字号」。
     * 换字体（行楷、宋体…）只会让字更宽，不会再让整段字缩水。
     */
    private fun fitPaint(
        context: Context,
        text: String,
        sizeSp: Float,
        maxWidthPx: Float,
        color: Int,
        medium: Boolean
    ): TextPaint {
        val paint = textPaint(context, sizeSp, color, medium)
        if (text.isEmpty() || maxWidthPx <= 0f) return paint
        val measured = paint.measureText(text)
        if (measured > maxWidthPx && measured > 0f) {
            paint.textSize = paint.textSize * (maxWidthPx / measured).coerceIn(0.72f, 1f)
        }
        return paint
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        val dots = "…"
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (paint.measureText(text.substring(0, mid) + dots) <= maxWidth) lo = mid else hi = mid - 1
        }
        return text.substring(0, lo) + dots
    }

    private fun withAlpha(color: Int, alpha: Int): Int = android.graphics.Color.argb(
        alpha.coerceIn(0, 255), android.graphics.Color.red(color),
        android.graphics.Color.green(color), android.graphics.Color.blue(color)
    )

    private fun blend(a: Int, b: Int, ratio: Float): Int {
        val r = (android.graphics.Color.red(a) * (1 - ratio) + android.graphics.Color.red(b) * ratio).roundToInt()
        val g = (android.graphics.Color.green(a) * (1 - ratio) + android.graphics.Color.green(b) * ratio).roundToInt()
        val bl = (android.graphics.Color.blue(a) * (1 - ratio) + android.graphics.Color.blue(b) * ratio).roundToInt()
        return android.graphics.Color.rgb(r, g, bl)
    }
}
