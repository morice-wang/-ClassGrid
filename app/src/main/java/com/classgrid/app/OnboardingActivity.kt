package com.classgrid.app

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * 新手引导（v1.48.7）：一步一步的卡片式向导，每屏只讲一件事，用户点一下再进入下一步。
 * 首次安装 / 每次升级后的第一次打开自动进入；设置里的「使用说明」也可以随时再看。
 */
class OnboardingActivity : androidx.activity.ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var stepLabel: TextView
    private lateinit var tagText: TextView
    private lateinit var titleText: TextView
    private lateinit var descText: TextView
    private lateinit var bulletBox: LinearLayout
    private lateinit var dotsRow: LinearLayout
    private lateinit var primaryBtn: TextView
    private lateinit var secondaryBtn: TextView
    private lateinit var art: OnboardingArtView
    private lateinit var skipBtn: TextView

    private var step = 0
    private val dark by lazy { ScheduleColor.isDarkMode(this) }
    private val accent by lazy { AppTheme.primary(this, dark) }
    private val ink by lazy { AppTheme.text(this, dark) }
    private val muted by lazy { AppTheme.textMuted(this, dark) }
    private val surface by lazy { AppTheme.cardBackground(this, dark) }
    private val bg by lazy { AppTheme.windowBackground(this, dark) }

    override fun onCreate(savedInstanceState: Bundle?) {
        Prefs.applyNightMode(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        buildUi()
        render(false)
    }

    private fun buildUi() {
        root = FrameLayout(this)
        root.setBackgroundColor(bg)
        setContentView(root)

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top + dp(8), bottom = bars.bottom + dp(8))
            insets
        }

        // 顶部：品牌 + 跳过
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(4), dp(16), dp(4))
        }
        column.addView(top, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        val brand = TextView(this).apply {
            text = "课格 ClassGrid"
            setTextColor(accent)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        top.addView(brand, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        skipBtn = TextView(this).apply {
            text = "跳过"
            setTextColor(muted)
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { finish() }
        }
        top.addView(skipBtn)

        // 中间内容
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), 0, dp(28), 0)
        }
        column.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val spacerTop = View(this)
        content.addView(spacerTop, LinearLayout.LayoutParams(1, 0, 0.30f))

        // 插画：每一步一张示意图，占住上半屏，避免整页只有文字
        art = OnboardingArtView(this).apply {
            accentColor = accent
            surfaceColor = surface
            isDark = dark
        }
        val artLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(172)
        )
        content.addView(art, artLp)

        stepLabel = TextView(this).apply {
            setTextColor(muted)
            textSize = 12f
            letterSpacing = 0.14f
        }
        val stepLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        stepLp.topMargin = dp(22)
        content.addView(stepLabel, stepLp)

        tagText = TextView(this).apply {
            setTextColor(accent)
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = pill(blend(accent, surface, 0.85f), dp(20))
        }
        val tagLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tagLp.topMargin = dp(14)
        content.addView(tagText, tagLp)

        titleText = TextView(this).apply {
            setTextColor(ink)
            textSize = 26f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        val titleLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        titleLp.topMargin = dp(14)
        content.addView(titleText, titleLp)

        descText = TextView(this).apply {
            setTextColor(muted)
            textSize = 15f
            setLineSpacing(dp(6).toFloat(), 1f)
        }
        val descLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        descLp.topMargin = dp(12)
        content.addView(descText, descLp)

        // 要点卡片
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = card(blend(surface, ink, if (dark) 0.06f else 0.03f))
        }
        val cardLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        cardLp.topMargin = dp(20)
        content.addView(card, cardLp)
        bulletBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(bulletBox, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        val spacerBottom = View(this)
        content.addView(spacerBottom, LinearLayout.LayoutParams(1, 0, 0.45f))

        // 底部：圆点 + 主按钮
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(8), dp(28), dp(10))
        }
        column.addView(bottom, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        dotsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        bottom.addView(dotsRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // 次要动作（例如「现在就去导入课表」）：不打断向导，用户想立刻动手时才走
        secondaryBtn = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(accent)
            textSize = 14f
            setPadding(0, dp(11), 0, dp(11))
            setOnClickListener { goImport() }
        }
        val secLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        bottom.addView(secondaryBtn, secLp)

        primaryBtn = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = pill(accent, dp(26))
            setOnClickListener { advance() }
        }
        val btnLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
        )
        btnLp.topMargin = dp(18)
        bottom.addView(primaryBtn, btnLp)
    }

    /** 主按钮：永远是「下一步」，走到最后一屏就结束 */
    private fun advance() {
        if (step >= STEPS.size - 1) {
            finish()
            return
        }
        step++
        render(true)
    }

    /** 次要按钮：关掉向导，让主界面直接弹出添加菜单 */
    private fun goImport() {
        requestAddMenu = true
        finish()
    }

    private fun render(animate: Boolean) {
        val s = STEPS[step]
        art.stepIndex = step
        art.invalidate()
        stepLabel.text = "第 " + (step + 1) + " 步 / 共 " + STEPS.size + " 步"
        tagText.text = s.tag
        titleText.text = s.title
        descText.text = s.desc
        primaryBtn.text = s.cta
        val sec = s.secondary
        if (sec == null) {
            secondaryBtn.visibility = View.GONE
        } else {
            secondaryBtn.visibility = View.VISIBLE
            secondaryBtn.text = sec
        }
        skipBtn.visibility = if (step == STEPS.size - 1) View.INVISIBLE else View.VISIBLE

        bulletBox.removeAllViews()
        for (line in s.bullets) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val dot = View(this).apply { background = pill(accent, dp(3)) }
            val dotLp = LinearLayout.LayoutParams(dp(6), dp(6))
            dotLp.topMargin = dp(7)
            dotLp.marginEnd = dp(10)
            row.addView(dot, dotLp)
            val tv = TextView(this).apply {
                text = line
                setTextColor(if (dark) 0xFFD6D3D1.toInt() else 0xFF44403C.toInt())
                textSize = 13.5f
                setLineSpacing(dp(4).toFloat(), 1f)
            }
            row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bulletBox.addView(row)
        }

        dotsRow.removeAllViews()
        for (i in STEPS.indices) {
            val dot = View(this).apply {
                background = pill(if (i == step) accent else blend(muted, bg, 0.55f), dp(4))
            }
            val lp = LinearLayout.LayoutParams(if (i == step) dp(22) else dp(8), dp(8))
            lp.marginStart = dp(4)
            lp.marginEnd = dp(4)
            dotsRow.addView(dot, lp)
        }

        if (animate) {
            content.alpha = 0f
            content.translationY = dp(14).toFloat()
            content.animate().alpha(1f).translationY(0f).setDuration(220).start()
        }
    }

    private fun pill(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun card(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(18).toFloat()
        setStroke(dp(1), AppTheme.line(this@OnboardingActivity, dark))
    }

    private fun blend(a: Int, b: Int, ratio: Float): Int {
        val r = (Color.red(a) * (1 - ratio) + Color.red(b) * ratio).toInt()
        val g = (Color.green(a) * (1 - ratio) + Color.green(b) * ratio).toInt()
        val bl = (Color.blue(a) * (1 - ratio) + Color.blue(b) * ratio).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Step(
        val tag: String,
        val title: String,
        val desc: String,
        val bullets: List<String>,
        val cta: String,
        val secondary: String? = null
    )

    companion object {
        /** 向导里点了「去导入课表」：主界面回到前台后直接弹出添加菜单 */
        var requestAddMenu = false

        fun intent(context: Context): Intent = Intent(context, OnboardingActivity::class.java)

        private val STEPS = listOf(
            Step(
                tag = "从导入开始",
                title = "会跟着周次走的电子课表",
                desc = "导入一次，之后每周自动对上；课程能点、能改、能换周，不用再翻截图。",
                bullets = listOf(
                    "课表 / 日程 / 设置三个页签，底部随时切",
                    "单双周、跳周、临时调课都按周次显示",
                ),
                cta = "下一步"
            ),
            Step(
                tag = "导入",
                title = "最准的导入方式：链接抓取",
                desc = "在应用里打开学校教务系统，自己登录，一路点到课表页，再点底部的「抓取当前页面」。",
                bullets = listOf(
                    "截图识别：截课程详情弹窗或整周课表",
                    "Excel / CSV、JSON、手动添加也都能用",
                ),
                cta = "下一步",
                secondary = "现在就去导入课表"
            ),
            Step(
                tag = "桌面",
                title = "长按桌面，加一个今日课程小组件",
                desc = "长按桌面空白 → 小组件 → 课格，拖到桌面上即可。默认 4×2 最好看，也能拉成 2×2 或 4×4。",
                bullets = listOf(
                    "显示正在上的课、还剩多少分钟、下一节",
                    "深浅色会跟着系统自动切换",
                ),
                cta = "下一步"
            ),
            Step(
                tag = "作息",
                title = "每个学校节次时间不一样，这里改",
                desc = "设置 → 作息库：可以一键导入广外 / 广工 / 华工 / 华师 / 中大 / 深大的作息，也能自己建一份。",
                bullets = listOf(
                    "一份作息可以给多张课表复用",
                    "节次数量、开始时间、课间间隔都能自己定",
                ),
                cta = "下一步"
            ),
            Step(
                tag = "外观",
                title = "三套界面，随时换",
                desc = "设置 → 界面主题：经典 / 墨玉 / 极光，改完立即生效，随时能换回来。",
                bullets = listOf(
                    "深色模式跟随系统设置",
                    "桌面图标也能单独换（课格右上角「换个图标」）",
                ),
                cta = "开始使用"
            )
        )
    }
}
/**
 * 每一步的示意图：用形状画「课表方格 / 导入列表 / 桌面小组件 / 作息表 / 主题色块」，
 * 不依赖图片资源，深浅色跟着主题走。
 */
class OnboardingArtView(context: Context) : View(context) {

    var accentColor: Int = 0
    var surfaceColor: Int = 0
    var isDark: Boolean = false
    var stepIndex: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private fun px(v: Float): Float = v * d

    private fun paintOf(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

    private fun blend(a: Int, b: Int, ratio: Float): Int {
        val r = (Color.red(a) * (1 - ratio) + Color.red(b) * ratio).toInt()
        val g = (Color.green(a) * (1 - ratio) + Color.green(b) * ratio).toInt()
        val bl = (Color.blue(a) * (1 - ratio) + Color.blue(b) * ratio).toInt()
        return Color.rgb(r, g, bl)
    }

    private fun bar(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float) {
        canvas.drawRoundRect(RectF(l, t, r, b), radius, radius, paintOf(color))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val tint = if (isDark) 0.20f else 0.10f
        bar(canvas, 0f, 0f, w, h, blend(surfaceColor, accentColor, tint), px(24f))

        val pad = px(20f)
        val left = pad
        val top = pad
        val right = w - pad
        val bottom = h - pad
        val strong = blend(accentColor, surfaceColor, if (isDark) 0.18f else 0.12f)
        val soft = blend(accentColor, surfaceColor, if (isDark) 0.55f else 0.45f)
        val faint = blend(accentColor, surfaceColor, if (isDark) 0.78f else 0.72f)

        when (stepIndex) {
            0 -> {
                // 课表：顶部星期点 + 5×4 宫格，几门课点亮
                val cols = 5
                val rows = 4
                val gapX = px(6f)
                val gapY = px(6f)
                val headY = top + px(4f)
                for (c in 0 until cols) {
                    val cw = px(10f)
                    val x = left + c * (px(30f) + gapX)
                    bar(canvas, x, headY, x + cw, headY + px(10f), if (c == 2) strong else faint, px(3f))
                }
                val gridTop = headY + px(20f)
                val cellW = (right - left - gapX * (cols - 1)) / cols
                val cellH = (bottom - gridTop - gapY * (rows - 1)) / rows
                val filled = setOf(intArrayOf(0, 1), intArrayOf(1, 0), intArrayOf(2, 0), intArrayOf(2, 1), intArrayOf(3, 2), intArrayOf(4, 1))
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val x = left + c * (cellW + gapX)
                        val y = gridTop + r * (cellH + gapY)
                        val on = filled.any { it[0] == c && it[1] == r }
                        val color = when {
                            on && c == 2 -> strong
                            on -> soft
                            else -> faint
                        }
                        bar(canvas, x, y, x + cellW, y + cellH, color, px(6f))
                    }
                }
            }
            1 -> {
                // 导入：三行「图标 + 两行字」的列表
                val rowH = px(34f)
                val gap = px(12f)
                for (i in 0 until 3) {
                    val y = top + px(2f) + i * (rowH + gap)
                    canvas.drawCircle(left + px(15f), y + rowH / 2f, px(14f), paintOf(if (i == 0) strong else faint))
                    bar(canvas, left + px(40f), y + px(6f), left + px(150f), y + px(15f), if (i == 0) soft else faint, px(5f))
                    bar(canvas, left + px(40f), y + px(20f), left + px(112f), y + px(28f), blend(surfaceColor, accentColor, 0.28f), px(4f))
                }
            }
            2 -> {
                // 桌面小组件：一张小卡片 + 顶部两枚胶囊 + 主胶囊 + 一行
                val cardTop = top + px(2f)
                bar(canvas, left, cardTop, right, bottom - px(2f), blend(surfaceColor, accentColor, if (isDark) 0.30f else 0.18f), px(14f))
                bar(canvas, left + px(12f), cardTop + px(10f), left + px(74f), cardTop + px(24f), soft, px(7f))
                bar(canvas, left + px(82f), cardTop + px(10f), left + px(122f), cardTop + px(24f), faint, px(7f))
                bar(canvas, left + px(12f), cardTop + px(32f), right - px(12f), cardTop + px(76f), strong, px(14f))
                bar(canvas, left + px(12f), cardTop + px(84f), right - px(12f), cardTop + px(100f), faint, px(8f))
            }
            3 -> {
                // 作息：左侧时间短条 + 右侧节次长条
                val rowH = px(22f)
                val gap = px(9f)
                for (i in 0 until 4) {
                    val y = top + px(2f) + i * (rowH + gap)
                    canvas.drawCircle(left + px(6f), y + rowH / 2f, px(4f), paintOf(soft))
                    bar(canvas, left + px(18f), y + px(5f), left + px(64f), y + rowH - px(5f), faint, px(4f))
                    bar(canvas, left + px(74f), y, right - px(6f), y + rowH, if (i == 1) soft else faint, px(6f))
                }
            }
            else -> {
                // 外观：三块主题色 + 两条说明
                val r = px(22f)
                val cy = top + h * 0.28f
                val colors = listOf(strong, blend(strong, Color.BLACK, if (isDark) 0.25f else 0.10f), blend(accentColor, surfaceColor, 0.65f))
                for (i in 0 until 3) {
                    canvas.drawCircle(left + r + i * (r * 2.6f), cy, r, paintOf(colors[i]))
                }
                bar(canvas, left, cy + px(40f), right - px(30f), cy + px(54f), faint, px(7f))
                bar(canvas, left, cy + px(64f), left + px(110f), cy + px(78f), faint, px(7f))
            }
        }
    }
}