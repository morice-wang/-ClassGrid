package com.classgrid.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.classgrid.app.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var courses: List<Course> = emptyList()
    /** 日程 Tab 的自定义待办（用户手动添加的事项） */
    private var todos: List<Todo> = emptyList()
    private var currentWeek = 1
    private var currentTab = R.id.nav_schedule
    /** 日程 Tab 当前选中的日期（当天 00:00 的 millis） */
    private var agendaSelectedDate: Long = 0L
        /** 日程日期切换滑动动画进行中（防连点打断） */
    private var agendaAnimating = false
    /** 日程头部区（日期标题 + 星期胶囊）：切换日期时原地叠化、不滑动 */
    private var agendaHeaderGroup: LinearLayout? = null
    /** 日程课程区：切换日期时滑动 */
    private var agendaCourseGroup: LinearLayout? = null
    private val dayNamesCn = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    /** 周次宫格选择弹窗实例（用于「修改当前周」后关闭） */
    private var weekPickerDialog: AlertDialog? = null

    /** v1.47.6：启动自动检查更新只在本次前台生命周期里试一次，不随每次 onResume 重复 */
    private var autoUpdateCheckStarted = false

    /** 数据加载线程：把 File.readText + JSON 解析移出 UI 线程（每次 onResume 都会触发一次加载） */
    private val loadExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** 加载请求序号：快速多次 onResume 时只接受最新一次的结果，避免旧结果覆盖新结果 */
    private var loadRequestId = 0
    /** 数据文件损坏（已留底）的提示只弹一次，避免每次返回都重复 Toast */
    private var corruptToastShown = false
    /** 上次加载时的当前课表 id：设置页切换课表返回时，用它与最新值比较以提示「已切换到 xxx」 */
    private var loadedTimetableId: String? = null
    /**
     * 上次据此渲染的「主题 + 布局」签名（-1 = 尚未比对）。
     * 课表是自绘 View，主题/布局换了必须整体重绘，所以 onResume 比对到变化就 recreate()。
     * 用签名而不是裸 id：v1.34.x 只比 id，导致布局变化不触发重建、并且和图标切换引发的
     * 额外重建互相干扰（用户看到「选项是极光、界面却是墨玉」）。
     */
    private var appliedThemeSignature: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        // 入口处依据外观设置应用日间/夜间模式（须在 super.onCreate 前，确保本 Activity 主题生效）
        Prefs.applyNightMode(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        agendaSelectedDate = startOfDay(System.currentTimeMillis())

        ViewCompat.setOnApplyWindowInsetsListener(binding.header) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top + dp(6))
            insets
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = bottom)
            insets
        }

        // 悬浮胶囊导航（极光布局）：把底部导航从「贴边条」改成「浮起胶囊」，
        // 内容区留出底部空间，避免最后一行卡片被胶囊盖住。
        applyNavLayout()
        applyHeaderLayout()

        setupListeners()
        // 桌面图标状态校准（恢复用户选择的图标；默认=最初紫色）
        IconSwitcher.ensureApplied(this)
        // 每次安装/更新后第一次打开必弹（不管之前有没有点过）：比较应用"最后更新安装时间"。
        // 延迟到组件状态处理完、首帧绘制后再弹，避免冷启动时图标组件切换把弹窗吞掉。
        val lastUpdate = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        if (lastUpdate > Prefs.guidedUpdateTime(this)) {
            Prefs.setGuidedUpdateTime(this, lastUpdate)
            binding.root.postDelayed({ showGuideDialog() }, 350)
        }
    }

    /**
     * 底部导航形态：
     *  - NAV_BOTTOM（经典/墨玉）：贴边整条，保持原样；
     *  - NAV_PILL（极光）：悬浮胶囊，左右留白 + 大圆角 + 卡面底色 + 投影，
     *    同时给 content 区底部留出空间，免得最后一行课卡被胶囊压住。
     */
    private fun applyNavLayout() {
        val spec = AppTheme.current(this)
        val lp = binding.bottomNav.layoutParams as LinearLayout.LayoutParams

        // v1.35.1：图标与文字都显式上色。
        // 之前只设了 itemIconTintList（且经典模式置 null），文字沿用 Material 默认的
        // ?attr/colorOnSurface 选择器——在「日间主题 + 用户手动选夜间外观」等组合下会解析成
        // 深色字压深色导航条上，肉眼就是「三个选项全黑、跟背景融在一起」。
        // 现在选中=主题主色、未选中=主题次要文字色，两套导航形态都用自己的色，不再依赖主题默认值。
        val navTint = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(AppTheme.primary(this, darkUI), AppTheme.textMuted(this, darkUI))
        )
        binding.bottomNav.itemIconTintList = navTint
        binding.bottomNav.itemTextColor = navTint

        if (spec.layout.navStyle == AppTheme.NAV_PILL) {
            val m = dp(12)
            lp.setMargins(m, dp(4), m, m)
            binding.bottomNav.layoutParams = lp
            binding.bottomNav.elevation = dp(10).toFloat()
            // 背景设在 BottomNavigationView（MaterialCardView 基类）上即可
            binding.bottomNav.background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(AppTheme.pick(darkUI, spec.cardLight, spec.cardDark))
                setStroke(dp(1), AppTheme.pick(darkUI, spec.lineLight, spec.lineDark))
            }
        } else {
            lp.setMargins(0, 0, 0, 0)
            binding.bottomNav.layoutParams = lp
            binding.bottomNav.elevation = 0f
            // 贴边整条：用顶栏/卡面色（深色下比页面背景略亮一档），保证与内容区有分界
            binding.bottomNav.background = GradientDrawable().apply {
                setColor(AppTheme.pick(darkUI, spec.headerLight, spec.headerDark))
            }
        }
        // 内容区底部留白（日程滚动区与课表都被胶囊遮挡不到）
        binding.agendaContainer.setPadding(
            dp(16), dp(12), dp(16),
            if (spec.layout.navStyle == AppTheme.NAV_PILL) dp(28) else dp(24)
        )
    }

    /**
     * 顶栏形态：
     *  - HEADER_PLAIN（经典/墨玉）：小标题 + 周导航同一行，卡面背景 + 投影；
     *  - HEADER_LARGE（极光）：大标题独占一行、背景与页面同色（无投影），
     *    周切换做成一枚描边胶囊，视觉上更像「今天/本周」聚焦式头部。
     */
    private fun applyHeaderLayout() {
        val spec = AppTheme.current(this)
        val type = spec.layout.type
        binding.btnTitle.textSize = type.titleSp
        if (spec.layout.headerStyle == AppTheme.HEADER_LARGE) {
            binding.header.elevation = 0f
            binding.header.background = GradientDrawable().apply {
                setColor(AppTheme.windowBackground(this@MainActivity, darkUI))
            }
            binding.btnTitle.setTextColor(AppTheme.text(this, darkUI))
            binding.btnTitle.setPadding(0, dp(2), 0, dp(2))
            binding.weekNavRow.background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(AppTheme.pick(darkUI, spec.cardLight, spec.cardDark))
                setStroke(dp(1), AppTheme.pick(darkUI, spec.lineLight, spec.lineDark))
            }
            binding.weekNavRow.setPadding(dp(4), dp(2), dp(4), dp(2))
            binding.tvWeek.textSize = 15f
            binding.tvWeek.setTextColor(AppTheme.primary(this, darkUI))
            binding.tvDateRange.textSize = 11f
            binding.tvDateRange.setTextColor(AppTheme.textMuted(this, darkUI))
        } else {
            binding.header.elevation = dp(3).toFloat()
            binding.header.background = GradientDrawable().apply {
                setColor(AppTheme.headerBackground(this@MainActivity, darkUI))
            }
            binding.btnTitle.setTextColor(AppTheme.primary(this, darkUI))
            binding.weekNavRow.background = null
            binding.weekNavRow.setPadding(0, 0, 0, 0)
            binding.tvWeek.textSize = 16f
            binding.tvWeek.setTextColor(AppTheme.text(this, darkUI))
            binding.tvDateRange.textSize = 11f
            binding.tvDateRange.setTextColor(AppTheme.textMuted(this, darkUI))
        }
    }

    /**
     * 新手引导（v1.48.7）：改成一步一步的向导页 [OnboardingActivity]，每屏只讲一件事，
     * 用户点一下再进入下一步。首次安装 / 每次升级后的第一次打开自动进入。
     */
    private fun showGuideDialog() {
        startActivity(OnboardingActivity.intent(this))
    }

    /** 设置页入口：随时手动查看向导（不影响自动弹出逻辑） */
    fun openGuideFromSettings() {
        showGuideDialog()
    }

    override fun onResume() {
        super.onResume()
        // 主题可能在设置页被切换：比对上次渲染用的签名，变了就重建本页（否则自绘课表还是旧配色）
        val themeNow = AppTheme.signature(this)
        if (appliedThemeSignature >= 0 && appliedThemeSignature != themeNow) {
            appliedThemeSignature = themeNow
            AppTheme.invalidate()
            recreate()
            return
        }
        appliedThemeSignature = themeNow
        // v1.48.0：作息可能刚被改过（新建/编辑作息、切换课表），丢掉缓存让课表重新读一份
        SectionTimes.invalidate()
        loadData()
        // v1.44.0 桌面小组件：回到前台时按最新课表/主题重画一次（没加组件时是空操作）
        WidgetRefresh.refresh(this)
        // v1.47.6 自动检查更新：24h 节流，失败 2h 后重试；全程静默，有新版才弹窗
        maybeCheckUpdateOnLaunch()
        // 从设置等页面返回时，底部导航恢复当前 Tab 的选中态
        binding.bottomNav.selectedItemId = currentTab
        // v1.48.7：向导里点了「去导入课表」——回到课表 Tab 后直接弹出添加菜单
        if (OnboardingActivity.requestAddMenu) {
            OnboardingActivity.requestAddMenu = false
            currentTab = R.id.nav_schedule
            binding.bottomNav.selectedItemId = currentTab
            binding.root.postDelayed({ showAddMenu() }, 180)
        }
    }

    /**
     * 启动时自动检查更新（v1.47.6）。
     *
     * - 只在本 Activity 实例里尝试一次，避免切 Tab/返回设置时反复发请求；
     * - 24 小时内检查过就跳过（[UpdateChecker.shouldAutoCheck]），失败只推迟 2 小时；
     * - 尊重用户点过的「忽略此版本」（ignoreSkip = false）；
     * - 网络与解析都在后台线程，失败完全静默，只有确实有新版本才弹 [UpdatePrompt]。
     */
    private fun maybeCheckUpdateOnLaunch() {
        if (autoUpdateCheckStarted) return
        autoUpdateCheckStarted = true
        if (!UpdateChecker.shouldAutoCheck(this)) return
        Thread {
            val result = runCatching { UpdateChecker.check(this, ignoreSkip = false) }.getOrNull()
            UpdateChecker.markAutoAttempt(this, result?.ok == true)
            val info = result?.update ?: return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                UpdatePrompt.show(this, info, fromAutoCheck = true)
            }
        }.apply { name = "auto-update-check" }.start()
    }

    override fun onDestroy() {
        // 让在途的加载结果失效，并停掉加载线程（结果回来时 Activity 已销毁则直接丢弃）
        loadRequestId++
        loadExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun setupListeners() {
        binding.btnPrev.setOnClickListener { switchWeek(-1) }
        binding.btnNext.setOnClickListener { switchWeek(1) }
        binding.btnToday.setOnClickListener { switchToCurrentWeek() }
        binding.weekSelector.setOnClickListener { showWeekPicker() }

        binding.btnAdd.setOnClickListener { showAddMenu() }
        binding.btnTitle.setOnClickListener { showAboutDialog() }
        binding.btnImport.setOnClickListener { openImport() }
        binding.btnManual.setOnClickListener { openEdit(null) }

        binding.scheduleView.setOnClickCourse { showCourseDetail(it) }
        // v1.47.11：日程方块点一下先看详情，再决定是否编辑（与课程一致）
        binding.scheduleView.setOnClickTodo { todo -> showTodoDetail(todo) }
        // 课表左右滑动 = 系统级翻页：View 负责两页并排跟手与吸附；这里只提供边界判定与翻页后的数据提交
        binding.scheduleView.canSwipeWeek = { delta ->
            val total = Prefs.totalWeeks(this)
            (currentWeek + delta) in 1..total
        }
        binding.scheduleView.onWeekSettled = { delta ->
            val total = Prefs.totalWeeks(this)
            currentWeek = (currentWeek + delta).coerceIn(1, total)
            binding.scheduleView.setCurrentWeek(currentWeek)
            binding.tvWeek.text = getString(R.string.week_prefix) + " " + currentWeek + " " + getString(R.string.week_suffix)
            binding.tvDateRange.text = weekDateRange(currentWeek)
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }
    }

    /**
     * 加载课表/待办/当前周并刷新界面。
     * 读文件 + 解析 JSON 放到加载线程执行，结果回主线程再渲染：避免 onResume 阻塞 UI（大课表时卡顿/ANR）。
     * 用 [loadRequestId] 丢弃过期结果，防止快速多次 onResume 时旧数据覆盖新数据。
     */
    private fun loadData() {
        val requestId = ++loadRequestId
        val appContext = applicationContext
        loadExecutor.execute {
            // 多课表：数据源是「当前课表」——设置页切换后会改写索引里的 active，这里每次 onResume 重新读，
            // 因此从设置页返回时课表页立即显示新数据，无需重启 Activity。
            val active = TimetableStore.activeMeta(appContext)
            val loadedCourses = TimetableStore.load(appContext, active.id)
            val loadedTodos = CourseStore.loadTodos(appContext)
            val loadedWeek = Prefs.currentWeek(appContext)
            val corrupt = TimetableStore.hasCorrupt(appContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (requestId != loadRequestId) return@runOnUiThread
                // 只在「之前加载过且 id 变了」时提示，首次进入不打扰
                val switched = loadedTimetableId != null && loadedTimetableId != active.id
                loadedTimetableId = active.id
                courses = loadedCourses
                todos = loadedTodos
                currentWeek = loadedWeek
                updateUI()
                if (switched) {
                    Toast.makeText(
                        this,
                        "已切换到课表「${active.name}」（${loadedCourses.size} 门课）",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                if (corrupt && !corruptToastShown) {
                    corruptToastShown = true
                    Toast.makeText(
                        this,
                        "课表文件损坏已留底，可在设置中恢复备份",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun updateUI() {
        binding.scheduleView.setData(courses, currentWeek)
        binding.scheduleView.setTodos(todos)
        binding.scheduleView.setSemesterStart(Prefs.semesterStart(this))
        binding.scheduleView.setWeekDates(weekDatesForWeek(currentWeek))
        binding.tvWeek.text = getString(R.string.week_prefix) + " " + currentWeek + " " + getString(R.string.week_suffix)
        binding.tvDateRange.text = weekDateRange(currentWeek)
        if (currentTab == R.id.nav_agenda) {
            renderAgenda()
        } else {
            val empty = courses.isEmpty() && todos.isEmpty()
            binding.scheduleView.visibility = if (empty) View.GONE else View.VISIBLE
            binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        }
    }

    private fun switchWeek(delta: Int) {
        setWeek(currentWeek + delta)
    }

    private fun switchToCurrentWeek() {
        setWeek(Prefs.currentWeek(this))
    }

    /** 跳转到指定周（夹取到 1..总周数），沿用切周淡入淡出动效（箭头/周选择器用） */
    private fun setWeek(week: Int) {
        val total = Prefs.totalWeeks(this)
        currentWeek = week.coerceIn(1, total)
        animateWeekChange()
    }

    /** 课表 Tab 周切换动效：淡出更新数据再淡入 */
    private fun animateWeekChange() {
        val sv = binding.scheduleView
        sv.animate().alpha(0.25f).setDuration(90).withEndAction {
            sv.setCurrentWeek(currentWeek)
            sv.setWeekDates(weekDatesForWeek(currentWeek))
            sv.animate().alpha(1f).setDuration(160).start()
        }.start()
        binding.tvWeek.text = getString(R.string.week_prefix) + " " + currentWeek + " " + getString(R.string.week_suffix)
        binding.tvDateRange.text = weekDateRange(currentWeek)
    }

    private fun weekDateRange(week: Int): String {
        val startMillis = Prefs.semesterStart(this)
        val cal = Calendar.getInstance().apply {
            timeInMillis = startMillis
            add(Calendar.WEEK_OF_YEAR, week - 1)
        }
        val sunday = cal.clone() as Calendar
        sunday.add(Calendar.DAY_OF_MONTH, 6)
        val fmt = SimpleDateFormat("M月d日", Locale.getDefault())
        return "${fmt.format(cal.time)} - ${fmt.format(sunday.time)}"
    }

    /** 计算指定周（周一为第一天）7 天日号；semesterStart 缺失或越界返回空列表（表头留空不崩） */
    private fun weekDatesForWeek(week: Int): List<Int> {
        val start = Prefs.semesterStart(this)
        if (start <= 0L) return emptyList()
        val cal = Calendar.getInstance().apply {
            timeInMillis = start
            add(Calendar.WEEK_OF_YEAR, week - 1)
        }
        val result = mutableListOf<Int>()
        for (i in 0 until 7) {
            result.add(cal.get(Calendar.DAY_OF_MONTH))
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return result
    }

    /** 周次选择弹窗：5 列宫格，本周高亮蓝底白字，当前查看周描边区分 */
    private fun showWeekPicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_week_picker, null)
        val rv = dialogView.findViewById<RecyclerView>(R.id.rvWeeks)
        val weeks = Prefs.totalWeeks(this)
        val realWeek = Prefs.currentWeek(this)
        val dark = ScheduleColor.isDarkMode(this)
        val primary = ScheduleColor.primary(this)
        val adapter = WeekGridAdapter(weeks, realWeek, currentWeek, dark, primary) { week ->
            weekPickerDialog?.dismiss()
            setWeek(week)
        }
        rv.layoutManager = GridLayoutManager(this, 5)
        rv.adapter = adapter

        dialogView.findViewById<TextView>(R.id.tvModifyCurrentWeek).setOnClickListener {
            showModifyCurrentWeekDialog()
        }

        weekPickerDialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setCancelable(true)
            .create()
            .also { it.show() }
    }

    /** 「修改当前周」：单选第 1~N 周，保存后按同格式回写学期起点并重算 */
    private fun showModifyCurrentWeekDialog() {
        val weeks = Prefs.totalWeeks(this)
        val items = Array(weeks) { "第 ${it + 1} 周" }
        var checked = (Prefs.currentWeek(this) - 1).coerceIn(0, weeks - 1)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.today_is_week_title)
            .setSingleChoiceItems(items, checked) { _, which -> checked = which }
            .setPositiveButton(R.string.confirm) { _, _ -> applyCurrentWeekOffset(checked + 1) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 把「今天」设为第 chosenWeek 周：按与 CourseStore.Prefs.semesterStart 相同格式
     * （周一 00:00 的 millis）回写学期起点 = 今天所在周一 - (chosenWeek-1)*7 天，并刷新课表与日程。
     */
    private fun applyCurrentWeekOffset(chosenWeek: Int) {
        val nowMonday = Calendar.getInstance().apply {
            timeInMillis = startOfDay(System.currentTimeMillis())
            val dow = get(Calendar.DAY_OF_WEEK)
            add(Calendar.DAY_OF_MONTH, -((dow - Calendar.MONDAY + 7) % 7))
        }
        val newStart = nowMonday.timeInMillis - (chosenWeek - 1) * 7L * 24 * 60 * 60 * 1000L
        Prefs.setSemesterStart(this, newStart)
        currentWeek = Prefs.currentWeek(this)
        weekPickerDialog?.dismiss()
        updateUI()
    }

    // ============ 日程纵览（单日视图） ============

    private fun switchTab(tabId: Int) {
        // 设置走独立页面：不切换当前 Tab，返回后导航保持原选中
        if (tabId == R.id.nav_settings) {
            openSettings()
            return
        }
        currentTab = tabId
        if (tabId == R.id.nav_agenda) {
            // 日程 Tab：隐藏课表的周导航与「回到本周」，显示单日视图
            binding.weekNavRow.visibility = View.GONE
            binding.btnToday.visibility = View.GONE
            binding.scheduleView.visibility = View.GONE
            binding.emptyState.visibility = View.GONE
            binding.agendaScroll.visibility = View.VISIBLE
            renderAgenda()
            binding.agendaScroll.alpha = 0f
            binding.agendaScroll.animate().alpha(1f).setDuration(180).start()
        } else {
            // 课表 Tab：恢复周导航
            binding.weekNavRow.visibility = View.VISIBLE
            binding.btnToday.visibility = View.VISIBLE
            binding.agendaScroll.visibility = View.GONE
            val empty = courses.isEmpty() && todos.isEmpty()
            binding.scheduleView.visibility = if (empty) View.GONE else View.VISIBLE
            binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
            binding.scheduleView.alpha = 0f
            binding.scheduleView.animate().alpha(1f).setDuration(180).start()
        }
    }

    /**
     * 点击胶囊切换日期：
     * - 头部（日期标题 + 胶囊条）位置不动，原地快速叠化"闪一下"并更新选中态；
     * - 只有课程块滑动（旧课滑出、新课从对应方向滑入）。
     */
    private fun selectAgendaDate(millis: Long) {
        if (agendaSelectedDate == millis || agendaAnimating) return
        agendaAnimating = true
        // 未来日期：新课从右侧滑入；过去日期：从左侧滑入
        val direction = if (millis > agendaSelectedDate) 1 else -1
        agendaSelectedDate = millis
        val dark = ScheduleColor.isDarkMode(this)
        val header = agendaHeaderGroup
        val course = agendaCourseGroup

        if (header != null) {
            header.animate().cancel()
            header.alpha = 1f
            // 日期标题 + 胶囊条原地直接更新（不闪黑、不位移），仅选中/今天的胶囊样式变化
            header.removeAllViews()
            header.addView(buildDateTitleRow(millis, dark))
            header.addView(buildCapsuleStrip(millis, dark))
        }

        if (course != null) {
            course.animate().cancel()
            course.animate()
                .translationX(-direction * dp(120).toFloat())
                .alpha(0f)
                .setDuration(120)
                .withEndAction {
                    course.removeAllViews()
                    fillAgendaCourses(course, millis, dark)
                    course.translationX = direction * dp(120).toFloat()
                    course.alpha = 0f
                    course.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(160)
                        .withEndAction { agendaAnimating = false }
                        .start()
                }
                .start()
        } else {
            agendaAnimating = false
        }
    }

    private fun renderAgenda() {
        val container = binding.agendaContainer
        container.removeAllViews()
        val dark = ScheduleColor.isDarkMode(this)
        val selected = if (agendaSelectedDate == 0L) startOfDay(System.currentTimeMillis()) else agendaSelectedDate
        if (agendaSelectedDate == 0L) agendaSelectedDate = selected

        // 头部区（固定不动）
        val headerGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headerGroup.addView(buildDateTitleRow(selected, dark))
        headerGroup.addView(buildCapsuleStrip(selected, dark))

        // 课程区（切换日期时滑动）
        val courseGroup = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fillAgendaCourses(courseGroup, selected, dark)

        container.addView(headerGroup)
        container.addView(courseGroup)
        agendaHeaderGroup = headerGroup
        agendaCourseGroup = courseGroup
    }

    /** 向课程区填充指定日期的课程卡片 + 自定义待办（两者都空才显示空状态） */
    private fun fillAgendaCourses(group: LinearLayout, selected: Long, dark: Boolean) {
        val dayCourses = coursesForDate(selected)
        val dayTodos = todosForDate(selected)
        if (dayCourses.isEmpty() && dayTodos.isEmpty()) {
            val isToday = isSameDay(selected, System.currentTimeMillis())
            group.addView(TextView(this).apply {
                text = if (isToday) "今天没有安排，好好休息" else "这一天没有安排"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (dark) spec.inactiveFgDark else spec.inactiveFgLight)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(48) })
            return
        }

        if (dayCourses.isNotEmpty()) {
            val isToday = isSameDay(selected, System.currentTimeMillis())
            val times = SectionTimes.load(this)
            val nowMinutes = Calendar.getInstance().let {
                it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
            }
            val statuses = if (isToday) computeTodayStatuses(dayCourses, times, nowMinutes) else emptyMap()
            for ((index, c) in dayCourses.withIndex()) {
                val st = statuses[index]
                group.addView(buildAgendaItem(c, dark, st?.first, st?.second))
            }
        }

        // 自定义待办（点击可删除）
        for (t in dayTodos) {
            group.addView(buildTodoItem(t, dark))
        }
    }

    /** 日期标题行：左侧「09月29日 周二」大字加粗，右侧「第N周」灰色小字 */
    private fun buildDateTitleRow(millis: Long, dark: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        val courseDay = courseDayOf(millis)
        val dateStr = SimpleDateFormat("MM月dd日", Locale.getDefault()).format(Date(millis)) +
                " " + cnWeekday(courseDay)
        row.addView(TextView(this).apply {
            text = dateStr
            // 字号节奏由主题 TypeScale 决定（极光日期更大、更醒目）
            textSize = spec.layout.type.dateSp
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (dark) spec.textDark else spec.textLight)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "第${weekForDate(millis)}周"
            textSize = spec.layout.type.captionSp
            setTextColor(if (dark) spec.inactiveFgDark else spec.inactiveFgLight)
        })
        return row
    }

    /** 日期胶囊条：周日~周六横排 7 个，今天默认选中，有课日期带小圆点 */
    private fun buildCapsuleStrip(selectedMillis: Long, dark: Boolean): View {
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(16)
            }
        }
        val cal = Calendar.getInstance().apply { timeInMillis = startOfWeekSunday(selectedMillis) }
        val todayNow = System.currentTimeMillis()
        for (i in 0 until 7) {
            val date = cal.timeInMillis
            val hasEvent = coursesForDate(date).isNotEmpty() || todosForDate(date).isNotEmpty()
            strip.addView(
                buildCapsuleItem(
                    date,
                    isSameDay(date, selectedMillis),
                    isSameDay(date, todayNow),
                    hasEvent,
                    dark
                ) { clicked ->
                    selectAgendaDate(clicked)
                },
                // weight=1 横向均分 + 左右内边距，使 7 个胶囊之间留出 6dp 间隙、视觉分开
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = dp(3)
                    rightMargin = dp(3)
                }
            )
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }
        return strip
    }

    private fun buildCapsuleItem(
        date: Long,
        isSelected: Boolean,
        isToday: Boolean,
        hasEvent: Boolean,
        dark: Boolean,
        onClick: (Long) -> Unit
    ): View {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setOnClickListener { onClick(date) }
        }

        val capsule = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = capsuleBg(isSelected, isToday, dark)
        }
        val cal = Calendar.getInstance().apply { timeInMillis = date }
        val weekday = SimpleDateFormat("EEE", Locale.ENGLISH).format(cal.time)
        val dayNum = SimpleDateFormat("dd", Locale.ENGLISH).format(cal.time)

        capsule.addView(TextView(this).apply {
            text = weekday
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(capsuleTextColor(isSelected, isToday, dark, secondary = true))
        })
        capsule.addView(TextView(this).apply {
            text = dayNum
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(capsuleTextColor(isSelected, isToday, dark, secondary = false))
        })
        item.addView(capsule)

        // 底部中央小圆点：选中/今天恒显，未选中但当天有安排也显示
        val dot = View(this)
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(dotColor(isSelected, isToday, dark))
        }
        val dotSize = dp(5)
        dot.visibility = if (isSelected || isToday || hasEvent) View.VISIBLE else View.INVISIBLE
        item.addView(dot, LinearLayout.LayoutParams(dotSize, dotSize).apply { topMargin = dp(4) })
        return item
    }

    /**
     * 当前主题（主题化取色的唯一来源）。
     * v1.34.0：本类所有硬编码色值改为从主题读取；想换配色只需改 AppTheme，不用动这里。
     */
    private val spec: AppTheme.Spec
        get() = AppTheme.current(this)

    /** 当前是否深色（外观模式 + 系统夜间的合成结果，主题取色统一用它） */
    private val darkUI: Boolean
        get() = ScheduleColor.isDarkMode(this)

    /** 二选一取色（深/浅），等价于 AppTheme.pick */
    private fun pick(dark: Boolean, light: Int, darkColor: Int): Int = if (dark) darkColor else light

    /** 给颜色换 alpha 通道（保留 RGB） */
    private fun bl(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    /** 胶囊背景三态：选中=实心反色填充；今天=描边空心；普通=浅底 */
    private fun capsuleBg(isSelected: Boolean, isToday: Boolean, dark: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            val onColor = if (dark) spec.textDark else spec.textLight
            when {
                isSelected -> setColor(onColor)
                isToday -> {
                    setColor(0x00000000)
                    setStroke(dp(2), onColor)
                }
                else -> {
                    setColor(if (dark) spec.cardDark else spec.cardLight)
                    setStroke(dp(1), if (dark) spec.lineDark else spec.lineLight)
                }
            }
        }

    /** 胶囊文字色：选中=与填充反色；今天=描边同色；普通=主/次文字色 */
    private fun capsuleTextColor(
        isSelected: Boolean,
        isToday: Boolean,
        dark: Boolean,
        secondary: Boolean
    ): Int {
        val onColor = if (dark) spec.textDark else spec.textLight
        return when {
            isSelected -> if (dark) 0xFF111827.toInt() else Color.WHITE // 选中胶囊 = onColor 反色，保留原逻辑
            isToday -> onColor
            secondary -> if (dark) spec.inactiveFgDark else spec.inactiveFgLight
            else -> if (dark) spec.textDark else spec.textLight
        }
    }

    /** 胶囊下方小圆点颜色：选中/今天用反色主色，普通有安排用主题色 */
    private fun dotColor(isSelected: Boolean, isToday: Boolean, dark: Boolean): Int {
        if (isSelected) return if (dark) 0xFF111827.toInt() else Color.WHITE
        if (isToday) return if (dark) spec.textDark else spec.textLight
        return ScheduleColor.primary(this@MainActivity)
    }

    /**
     * 日程条目。两种排版由主题布局层决定：
     * - AGENDA_COMFORT（经典/墨玉）：左节次列 + 色条 + 明细，宽松单列；
     * - AGENDA_TIMELINE（极光）：左侧时间轴（时间在上、圆点连线），右侧紧凑信息卡，
     *   文字更小更密、教室与教师并成一行，一屏能看到更多课。
     */
    private fun buildAgendaItem(
        course: Course,
        dark: Boolean,
        statusLabel: String?,
        statusColor: Int?
    ): View = if (spec.layout.agendaStyle == AppTheme.AGENDA_TIMELINE) {
        buildTimelineAgendaItem(course, dark, statusLabel, statusColor)
    } else {
        buildComfortAgendaItem(course, dark, statusLabel, statusColor)
    }

    /** 极光时间轴排版：左侧「时间 + 圆点」，右侧白卡（课程名 / 教室·教师一行 / 状态） */
    private fun buildTimelineAgendaItem(
        course: Course,
        dark: Boolean,
        statusLabel: String?,
        statusColor: Int?
    ): View {
        val pal = ScheduleColor.palette(this, course.colorIndex, dark)
        val times = SectionTimes.load(this)
        val startT = times.getOrNull(course.startSection - 1)?.start ?: ""
        val endT = times.getOrNull(course.endSection - 1)?.end ?: ""

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        // 左：时间轴（时间文本 + 圆点 + 竖线）
        val gutter = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val startTv = TextView(this).apply {
            text = startT
            textSize = spec.layout.type.captionSp
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppTheme.primary(this@MainActivity, dark))
        }
        val endTv = TextView(this).apply {
            text = endT
            textSize = spec.layout.type.captionSp - 1f
            setTextColor(AppTheme.textMuted(this@MainActivity, dark))
        }
        gutter.addView(startTv)
        gutter.addView(endTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(1) })
        // 圆点：用色族 acg色，标出课程在时间轴上的位置
        gutter.addView(View(this).apply {
            background = GradientDrawable().also {
                it.shape = GradientDrawable.OVAL
                it.setColor(pal.accent)
            }
        }, LinearLayout.LayoutParams(dp(9), dp(9)).apply { topMargin = dp(6) })

        // 右：信息卡
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().also {
                it.cornerRadius = spec.layout.cardRadiusDp * resources.displayMetrics.density
                it.setColor(AppTheme.pick(dark, spec.cardLight, spec.cardDark))
                it.setStroke(dp(1), AppTheme.pick(dark, spec.lineLight, spec.lineDark))
            }
            setPadding(dp(14), dp(11), dp(14), dp(11))
            isClickable = true
            isFocusable = true
        }
        card.addView(TextView(this).apply {
            text = course.name
            textSize = spec.layout.type.cardNameSp
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppTheme.text(this@MainActivity, dark))
        })
        val meta = buildString {
            append("第${course.startSection}-${course.endSection}节")
            if (course.classroom.isNotEmpty()) append(" · ").append(course.classroom)
            if (course.teacher.isNotEmpty()) append(" · ").append(course.teacher)
        }
        card.addView(TextView(this).apply {
            text = meta
            textSize = spec.layout.type.bodySp - 1f
            setTextColor(AppTheme.textMuted(this@MainActivity, dark))
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(3) })
        if (statusLabel != null && statusColor != null) {
            card.addView(statusCapsule(statusLabel, statusColor), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(7) })
        }
        card.setOnClickListener { showCourseDetail(course) }

        row.addView(gutter, LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.WRAP_CONTENT))
        row.addView(card, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) }
        return row
    }

    private fun buildComfortAgendaItem(
        course: Course,
        dark: Boolean,
        statusLabel: String?,
        statusColor: Int?
    ): View {
        val pal = ScheduleColor.palette(this, course.colorIndex, dark)
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = if (dark) spec.lineDark else spec.lineLight
            setCardBackgroundColor(if (dark) spec.cardDark else spec.cardLight)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(14), dp(14), dp(14))
            gravity = Gravity.CENTER_VERTICAL
        }

        // 左侧：节次 + 起止时间
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val secTv = TextView(this).apply {
            text = "${course.startSection}-${course.endSection}节"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(pal.accent)
        }
        val times = SectionTimes.load(this)
        val startT = times.getOrNull(course.startSection - 1)?.start ?: ""
        val endT = times.getOrNull(course.endSection - 1)?.end ?: ""
        val timeTv = TextView(this).apply {
            text = "$startT\n$endT"
            textSize = 10f
            setTextColor(if (dark) spec.inactiveFgDark else spec.inactiveFgLight)
        }
        leftCol.addView(secTv)
        leftCol.addView(timeTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) })
        row.addView(leftCol, LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT))

        // 强调色竖条
        val bar = View(this)
        bar.setBackgroundColor(pal.accent)
        row.addView(bar, LinearLayout.LayoutParams(dp(3), dp(44)).apply { marginEnd = dp(12) })

        // 右侧信息
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply {
            text = course.name
            textSize = spec.layout.type.cardNameSp
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (dark) spec.textDark else spec.textLight)
        })
        if (course.classroom.isNotEmpty()) {
            info.addView(TextView(this).apply {
                text = "上课地点：${course.classroom}"
                textSize = 13f
                setTextColor(if (dark) spec.textMutedDark else spec.textMutedLight)
            }.also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(4) })
        }
        if (course.teacher.isNotEmpty()) {
            info.addView(TextView(this).apply {
                text = "上课老师：${course.teacher}"
                textSize = 13f
                setTextColor(if (dark) spec.textMutedDark else spec.textMutedLight)
            }.also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(2) })
        }
        if (statusLabel != null && statusColor != null) {
            info.addView(statusCapsule(statusLabel, statusColor), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }
        row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        card.addView(row)
        card.setOnClickListener { showCourseDetail(course) }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        return card
    }

    /** 自定义待办卡片：琥珀色标识，左侧显示时间（无时间显示"待办"），点击可删除 */
    private fun buildTodoItem(todo: Todo, dark: Boolean): View {
        val accent = spec.todoAccent
        val card = MaterialCardView(this).apply {
            radius = dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = if (dark) spec.lineDark else spec.lineLight
            setCardBackgroundColor(if (dark) spec.cardDark else spec.cardLight)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(14), dp(14), dp(14))
            gravity = Gravity.CENTER_VERTICAL
        }

        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (todo.hasTimeRange()) {
            // v1.47.7：有完整时间段时显示「开始 / 结束」两行小块时间轴
            leftCol.addView(TextView(this).apply {
                text = todo.time
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accent)
            })
            leftCol.addView(TextView(this).apply {
                text = todo.endTime
                textSize = 11f
                setTextColor(if (dark) spec.textMutedDark else spec.textMutedLight)
            })
        } else {
            leftCol.addView(TextView(this).apply {
                text = if (todo.time.isNotEmpty()) todo.time else "日程"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accent)
            })
        }
        row.addView(leftCol, LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT))

        val bar = View(this)
        bar.setBackgroundColor(accent)
        row.addView(bar, LinearLayout.LayoutParams(dp(3), dp(44)).apply { marginEnd = dp(12) })

        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(TextView(this).apply {
            text = if (todo.repeatWeekly) "${todo.title} · ${todo.repeatDesc(Prefs.totalWeeks(this@MainActivity))}" else todo.title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (dark) spec.textDark else spec.textLight)
        })
        if (todo.location.isNotEmpty()) {
            info.addView(TextView(this).apply {
                text = "地点：${todo.location}"
                textSize = 13f
                setTextColor(if (dark) spec.textMutedDark else spec.textMutedLight)
            }.also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(4) })
        }
        if (todo.note.isNotEmpty()) {
            info.addView(TextView(this).apply {
                text = todo.note
                textSize = 12f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(if (dark) spec.textMutedDark else spec.textMutedLight)
            }.also { (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(4) })
        }
        row.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        card.addView(row)
        card.setOnClickListener { showTodoDetail(todo) }
        card.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(10) }
        return card
    }

    /**
     * 日程详情弹窗（v1.47.11）：先看清楚是什么事，再决定编辑或删除 —— 和课程的详情弹窗一致。
     */
    private fun showTodoDetail(todo: Todo) {
        val weeks = Prefs.totalWeeks(this)
        val dateLabel = java.text.SimpleDateFormat("yyyy年M月d日 EEE", java.util.Locale.CHINA)
            .format(java.util.Date(todo.dateMillis))
        val detail = buildString {
            append(getString(R.string.event_detail_date, dateLabel))
            if (todo.time.isNotBlank()) {
                append("\n")
                append(getString(R.string.event_detail_time, Todo.rangeLabel(todo)))
            }
            if (todo.location.isNotBlank()) {
                append("\n")
                append(getString(R.string.event_detail_location, todo.location))
            }
            if (todo.repeatWeekly) {
                append("\n")
                append(getString(R.string.event_detail_repeat, todo.repeatDesc(weeks)))
            }
            if (todo.note.isNotBlank()) {
                append("\n\n")
                append(todo.note)
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(todo.title)
            .setMessage(detail)
            .setPositiveButton(R.string.edit) { _, _ ->
                startActivity(TodoEditActivity.intent(this, todo.dateMillis, todo.id))
            }
            .setNegativeButton(R.string.delete) { _, _ -> confirmDeleteTodo(todo) }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    /** 删除单条自定义待办 */
    private fun confirmDeleteTodo(todo: Todo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(todo.title)
            .setMessage("删除这条待办？")
            .setPositiveButton(R.string.delete) { _, _ ->
                todos = todos.filter { it.id != todo.id }
                CourseStore.saveTodos(this, todos)
                updateUI()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 状态胶囊：紫色「上课中…」/ 绿色「下一节课」 */
    private fun statusCapsule(text: String, color: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(9), dp(3), dp(9), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(999).toFloat()
                setColor(color)
            }
        }

    /**
     * 根据当前时间计算今天每门课的状态标签（**按列表下标** -> label/color）。
     *
     * v1.47.2：这里原来用 `course.id` 做 key。历史导入数据里偶发重复 id（链接抓取/Excel/截图
     * 都出现过），两门课撞同一个 id 时就会一起挂上「下一节课」标签。改用下标后，即使数据里
     * 还有脏 id，最多也只影响它自己那一行。
     */
    private fun computeTodayStatuses(
        dayCourses: List<Course>,
        times: List<SectionTimes.Section>,
        nowMinutes: Int
    ): Map<Int, Pair<String, Int>> {
        data class Slot(val index: Int, val start: Int, val end: Int)

        val slots = mutableListOf<Slot>()
        dayCourses.forEachIndexed { index, c ->
            val start = times.getOrNull(c.startSection - 1)?.start?.let { SectionTimes.toMinutes(it) } ?: return@forEachIndexed
            val end = times.getOrNull(c.endSection - 1)?.end?.let { SectionTimes.toMinutes(it) } ?: return@forEachIndexed
            slots.add(Slot(index, start, end))
        }

        val current = slots.firstOrNull { nowMinutes >= it.start && nowMinutes <= it.end }
        val next = slots.filter { it.start > nowMinutes }.minByOrNull { it.start }

        val spec = AppTheme.current(this)
        val purple = spec.statusOngoing
        val green = spec.statusUpcoming
        val result = mutableMapOf<Int, Pair<String, Int>>()
        current?.let { result[it.index] = "上课中…" to purple }
        next?.let { if (it.index !in result) result[it.index] = "下一节课" to green }
        return result
    }

    // ============ 日期/周次工具 ============

    /** 指定日期当天（00:00）的 millis */
    private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 指定日期所在周的周日（一周第一天）当天 00:00 */
    private fun startOfWeekSunday(millis: Long): Long = Calendar.getInstance().apply {
        timeInMillis = millis
        val dow = get(Calendar.DAY_OF_WEEK)
        add(Calendar.DAY_OF_MONTH, -(dow - Calendar.SUNDAY))
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
                ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    /** 日历星期（1=周日..7=周六）转 Course.dayOfWeek（1=周一..7=周日） */
    private fun courseDayOf(millis: Long): Int {
        val dow = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)
        return ((dow - Calendar.MONDAY + 7) % 7) + 1
    }

    private fun cnWeekday(courseDay: Int): String = dayNamesCn.getOrElse(courseDay - 1) { "" }

    /** 根据学期开始日期计算指定日期处于第几周（与 Prefs.currentWeek 同口径） */
    private fun weekForDate(millis: Long): Int {
        val start = Prefs.semesterStart(this)
        val monday = Calendar.getInstance().apply {
            timeInMillis = millis
            val dow = get(Calendar.DAY_OF_WEEK)
            add(Calendar.DAY_OF_MONTH, -((dow - Calendar.MONDAY + 7) % 7))
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diffDays = (monday.timeInMillis - start) / (7L * 24 * 60 * 60 * 1000)
        return (diffDays + 1).toInt().coerceAtLeast(1)
    }

    /** 选中日期当天、且处于正确周次的所有课程，按节次排序 */
    private fun coursesForDate(millis: Long): List<Course> {
        val courseDay = courseDayOf(millis)
        val week = weekForDate(millis)
        return courses.filter { it.dayOfWeek == courseDay && it.activeInWeek(week) }
            .sortedBy { it.startSection }
    }

    /** 选中日期当天的自定义待办，按时间排序（无时间的排最后） */
    private fun todosForDate(millis: Long): List<Todo> {
        val day = startOfDay(millis)
        // v1.47.8：按周重复的日程在之后每周同一天都要出现
        val semesterStart = Prefs.semesterStart(this)
        val weeks = Prefs.totalWeeks(this)
        return todos.filter { it.occursOn(day, semesterStart, weeks) }
            .sortedWith(compareBy({ if (it.time.isEmpty()) 1 else 0 }, { it.time }))
    }

    private fun showAddMenu() {
        if (currentTab == R.id.nav_agenda) {
            // 日程 Tab：加号 = 添加日程（与课表菜单里的「手动添加日程」是同一个模块）
            startActivity(TodoEditActivity.intent(this, agendaSelectedDate))
            return
        }
        // v1.47.7：课表 Tab 的加号改为底部菜单——手动添加课程 / 手动添加日程 / 导入课表
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_add_options, null)
        view.findViewById<android.view.View>(R.id.itemCourse).setOnClickListener {
            sheet.dismiss()
            openEdit(null)
        }
        view.findViewById<android.view.View>(R.id.itemEvent).setOnClickListener {
            sheet.dismiss()
            startActivity(TodoEditActivity.intent(this, startOfDay(System.currentTimeMillis())))
        }
        view.findViewById<android.view.View>(R.id.itemImport).setOnClickListener {
            sheet.dismiss()
            openImport()
        }
        sheet.setContentView(view)
        sheet.show()
    }

    private fun openImport() {
        startActivity(Intent(this, ImportActivity::class.java))
    }

    /** 点击标题「课格」弹出的关于信息（与设置页「关于」一致） */
    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("课格 ClassGrid")
            .setMessage("版本 v${BuildConfig.VERSION_NAME}\n\n简洁好用的课程表，支持教务系统截图导入、JSON 导入与手动添加。\n\n祝你新学期一切顺利 ✨")
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun openEdit(course: Course?) {
        val intent = Intent(this, CourseEditActivity::class.java)
        intent.putExtra(CourseEditActivity.EXTRA_ID, course?.id ?: -1L)
        startActivity(intent)
    }

    private fun showCourseDetail(course: Course) {
        val day = getString(when (course.dayOfWeek) {
            1 -> R.string.monday; 2 -> R.string.tuesday; 3 -> R.string.wednesday
            4 -> R.string.thursday; 5 -> R.string.friday; 6 -> R.string.saturday
            else -> R.string.sunday
        })
        val detail = buildString {
            if (course.teacher.isNotEmpty()) append("教师：${course.teacher}\n")
            if (course.classroom.isNotEmpty()) append("教室：${course.classroom}\n")
            append("时间：$day 第${course.startSection}-${course.endSection}节\n")
            append("周次：${course.weekDesc()}")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(course.name)
            .setMessage(detail)
            .setPositiveButton(R.string.edit) { _, _ -> openEdit(course) }
            .setNegativeButton(R.string.delete) { _, _ -> confirmDelete(course) }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(course: Course) {
        MaterialAlertDialogBuilder(this)
            .setTitle(course.name)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                courses = courses.filter { it.id != course.id }
                // 显式写回「当前课表」：多课表下删课不能落到别的课表上
                TimetableStore.save(this, courses)
                updateUI()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 周次宫格适配器：本周蓝底白字「本周」，当前查看周描边区分，其余浅灰圆角底 */
    private inner class WeekGridAdapter(
        private val totalWeeks: Int,
        private val realWeek: Int,
        private val viewingWeek: Int,
        private val dark: Boolean,
        private val primary: Int,
        private val onPick: (Int) -> Unit
    ) : RecyclerView.Adapter<WeekGridAdapter.VH>() {

        // 主题化：宫格底色跟随主题（viewingBg 用主色的低透明度版本，避免再多一个色值）
        private val tspec = AppTheme.current(this@MainActivity)
        private val defaultBg = if (dark) tspec.cardDark else tspec.lineLight
        private val defaultFg = if (dark) tspec.textDark else tspec.textLight
        /** 当前查看周：主色低透明度底（深色 20%、浅色 13%），不额外新增色值 */
        private val viewingBg = bl(if (dark) tspec.primaryDark else tspec.primaryLight, if (dark) 0x33 else 0x22)

        inner class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val tv = layoutInflater.inflate(R.layout.item_week_cell, parent, false) as TextView
            return VH(tv)
        }

        override fun getItemCount(): Int = totalWeeks

        override fun onBindViewHolder(holder: VH, position: Int) {
            val week = position + 1
            val isThis = week == realWeek
            val isViewing = week == viewingWeek
            val tv = holder.tv
            tv.text = if (isThis) "本周" else week.toString()
            when {
                isThis -> {
                    tv.background = cellDrawable(primary, null)
                    tv.setTextColor(Color.WHITE)
                }
                isViewing -> {
                    tv.background = cellDrawable(viewingBg, primary)
                    tv.setTextColor(primary)
                }
                else -> {
                    tv.background = cellDrawable(defaultBg, null)
                    tv.setTextColor(defaultFg)
                }
            }
            tv.setOnClickListener { onPick(week) }
        }

        private fun cellDrawable(bg: Int, stroke: Int?): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(bg)
                if (stroke != null) setStroke(dp(2), stroke)
            }
    }
}
