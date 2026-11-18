package com.classgrid.app

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.classgrid.app.databinding.ActivitySettingsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** 课表管理弹窗实例：切换/重命名/删除后据此关闭并重新打开，保持用户停留在这个界面 */
    private var timetableDialog: AlertDialog? = null

    /** 选择自定义背景图片（仅图片类型；权限与系统相册交互由 PickVisualMedia 托管） */
    private val pickBgImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            // 进入内置裁剪页：拖动图片选区域，确认后由 BgCropActivity 保存到私有目录
            startActivity(
                Intent(this, BgCropActivity::class.java)
                    .putExtra(BgCropActivity.EXTRA_IMAGE_URI, uri.toString())
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 依据外观设置应用日间/夜间模式（须在 super.onCreate 前，确保本 Activity 主题生效）
        Prefs.applyNightMode(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.header) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }

        binding.btnBack.setOnClickListener { finish() }
        buildSettings()
    }

    override fun onResume() {
        super.onResume()
        // v1.48.7：向导里点了「去导入课表」——退到主界面，由 MainActivity 弹添加菜单
        if (OnboardingActivity.requestAddMenu) {
            finish()
            return
        }
        // 从裁剪页/其他页返回时刷新列表（如背景已设置/移除）
        buildSettings()
    }

    /** 当前主题（主题化取色的唯一来源，v1.34.0） */
    private val spec: AppTheme.Spec
        get() = AppTheme.current(this)

    private fun buildSettings() {
        binding.settingsContainer.removeAllViews()

        // ===== 课表 =====
        // 多课表是本页最需要被找到的功能，放最上面。
        val activeMeta = TimetableStore.activeMeta(this)
        val timetableCount = TimetableStore.courseCount(this)
        sectionHeader("课表")
        addSetting(
            "课表管理",
            "${activeMeta.name}（$timetableCount 门课）"
        ) { showTimetableManager() }
        // 课表方块点进去改一门课太费劲，这里直接按星期列出全部课程，随时点开改。
        addSetting(
            "当前课表的课程",
            "$timetableCount 门 · 点开可逐门修改"
        ) { startActivity(CourseListActivity.intent(this)) }

        // ===== 学期与节次 =====
        // v1.48.0：这块整体上移到「外观」之前——学期起止和作息决定课表能不能用，
        // 比配色更靠前；作息也从「一份全局时间表」升级成「作息库」。
        val fmt = SimpleDateFormat("yyyy年M月d日", Locale.getDefault())
        val startDate = fmt.format(Date(Prefs.semesterStart(this)))
        val activeProfile = SectionTimes.activeProfile(this)
        sectionHeader("学期与作息")
        addSetting("学期开始日期（周一）", startDate) { showDatePicker() }
        addSetting("学期总周数", "${Prefs.totalWeeks(this)} 周") { showWeeksPicker() }
        addSetting(
            "作息库",
            "${activeProfile.name} · ${activeProfile.sections.size} 节"
        ) {
            startActivity(ScheduleProfilesActivity.intent(this))
        }

        // ===== 外观 =====
        // 经典 = v1.33 及之前的原样配色（默认，老用户升级后界面不变）；其余为新配色。
        val appearanceLabels = arrayOf("跟随系统", "日间（白）", "夜间（黑）")
        sectionHeader("外观")
        addSetting("界面主题", AppTheme.current(this).label) { showThemePicker() }
        addSetting(getString(R.string.appearance), appearanceLabels[Prefs.appearance(this)]) { showAppearancePicker() }
        addSetting("课表卡片样式", if (Prefs.cardStyle(this) == Prefs.CARD_STYLE_SQUARE) "方块" else "圆角") { showCardStylePicker() }
        addSetting("课表卡片阴影", if (Prefs.cardShadow(this)) "开" else "关") { showCardShadowPicker() }
        addSetting("应用图标", iconLabel(Prefs.launcherIcon(this))) { showIconPicker() }
        if (ScheduleColor.isDarkMode(this)) {
            // 深色模式背景固定，不允许切换颜色/图片
            addSetting("课表背景", "深色固定") {
                Toast.makeText(
                    this,
                    "深色模式使用固定背景，切换到浅色模式可自定义颜色或图片",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            addSetting("课表背景", backgroundLabel(Prefs.backgroundStyle(this))) { showBackgroundPicker() }
        }
        addSetting("随机打乱课程颜色", "一键换色") { shuffleCourseColors() }

        // ===== 提醒 =====
        sectionHeader("提醒")
        addSetting(
            getString(R.string.today_notify_title),
            if (Prefs.todayNotify(this)) {
                getString(R.string.today_notify_on)
            } else {
                getString(R.string.today_notify_off)
            }
        ) { toggleTodayNotify() }

        // ===== 数据 =====
        sectionHeader("数据")
        addSetting(getString(R.string.export_json), null) { exportJson() }
        addSetting(getString(R.string.export_image), null) { exportImage() }
        addSetting(getString(R.string.export_calendar), getString(R.string.export_calendar_desc)) { exportCalendar() }
        // 多课表：这一项只清空当前课表，标签写明「当前课表」避免误解成清空全部课表
        addSetting("清空当前课表课程", null) { clearAll() }
        // 有可恢复备份时才显示恢复入口
        if (CourseStore.hasBackup(this)) {
            addSetting(getString(R.string.restore_backup), null) { showRestoreBackupDialog() }
        }

        // ===== 帮助 =====
        sectionHeader("帮助")
        addSetting("使用说明 / 注意事项", "随时查看") { showGuideManual() }
        // 自动更新第一阶段：只检查、只展示；下载安装下一阶段接入。
        addSetting(
            getString(R.string.update_title),
            getString(R.string.update_desc, BuildConfig.VERSION_NAME, BuildConfig.CHANNEL),
            onClick = { checkUpdate() },
            // 调试包专用：长按可把清单地址指到本地服务器，方便真机联调（release 不生效）
            onLongClick = if (BuildConfig.DEBUG) ({ showDebugManifestDialog() }) else null
        )
        addSetting("关于", "v${BuildConfig.VERSION_NAME} · ${BuildConfig.CHANNEL}") { showAbout() }
    }

    /**
     * 检查更新（v1.47.4 起）。
     *
     * 网络请求必须在后台线程：HttpURLConnection 是阻塞的，放主线程会 ANR。
     * 结果回到主线程再弹窗/Toast，Activity 可能已经关闭，所以先判 isFinishing/isDestroyed。
     */
    private fun checkUpdate() {
        Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
        Thread {
            val result = UpdateChecker.check(this)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val info = result.update
                when {
                    result.error != null ->
                        Toast.makeText(this, R.string.update_failed, Toast.LENGTH_SHORT).show()
                    info != null -> showUpdateAvailable(info)
                    else -> Toast.makeText(
                        this,
                        getString(R.string.update_latest, BuildConfig.VERSION_NAME),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.apply { name = "update-check" }.start()
    }

    private fun showUpdateAvailable(info: UpdateInfo) {
        // v1.47.6：与启动自动检查共用同一套弹窗（下载 / 复制地址 / 稍后）
        UpdatePrompt.show(this, info, fromAutoCheck = false)
    }

    /**
     * 调试包专用：设置本地清单地址。
     * 真机联调时把 update.json 放在电脑上（模拟器用 http://10.0.2.2:8000/update.json），
     * 就不必为了测试去发一次真实 Release。长按「检查更新」打开，输入空串恢复默认。
     */
    private fun showDebugManifestDialog() {
        val input = android.widget.EditText(this).apply {
            setText(Prefs.debugUpdateManifest(this@SettingsActivity).orEmpty())
            hint = "http://10.0.2.2:8000/update.json"
            setPadding(dp(22), dp(14), dp(22), dp(14))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_debug_manifest_title)
            .setMessage(R.string.update_debug_manifest_hint)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val text = input.text.toString().trim()
                Prefs.setDebugUpdateManifest(this, text.ifEmpty { null })
                Toast.makeText(this, R.string.update_debug_manifest_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** v1.45.0 常驻通知开关：Android 13+ 先要通知权限 */
    private fun toggleTodayNotify() {
        if (Prefs.todayNotify(this)) {
            Prefs.setTodayNotify(this, false)
            TodayNotify.cancel(this)
            buildSettings()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        enableTodayNotify()
    }

    private fun enableTodayNotify() {
        Prefs.setTodayNotify(this, true)
        TodayNotify.refresh(this)
        buildSettings()
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            enableTodayNotify()
        } else {
            Toast.makeText(this, R.string.today_notify_denied, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 分组小标题：胶囊列表之间的视觉分隔。
     * 用留白 + 小字做分组，取代原先一整列 1px 横线（用户反馈「一堆横线太丑」）。
     */
    private fun sectionHeader(title: String) {
        val tv = TextView(this).apply {
            text = title
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            setTextColor((if (ScheduleColor.isDarkMode(this@SettingsActivity))
                spec.textMutedDark else spec.textMutedLight))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.marginStart = dp(8)
        lp.topMargin = if (binding.settingsContainer.childCount == 0) dp(2) else dp(22)
        lp.bottomMargin = dp(10)
        binding.settingsContainer.addView(tv, lp)
    }

    /** 从设置里手动查看使用说明：打开逐步向导（不写版本标记，也不影响自动弹出） */
    private fun showGuideManual() {
        startActivity(OnboardingActivity.intent(this))
    }

    /** 桌面启动图标选择：7 个候选（默认=最初紫色 + 6 个可选图标），点选即切换 launcher 图标 */
    private fun showIconPicker() {
        val names = listOf(
            "经典 · 紫色九宫格", "课表方块", "日历对勾", "横条圆点", "挂扣对勾", "文档笔记", "青绿渐变", "极光渐变"
        )
        val icons = listOf(
            R.mipmap.icon_opt4, R.mipmap.icon_opt1, R.mipmap.icon_opt2,
            R.mipmap.icon_opt2a, R.mipmap.icon_opt2b, R.mipmap.icon_opt3,
            R.mipmap.ic_launcher, R.mipmap.icon_opt5
        )
        val current = Prefs.launcherIcon(this)
        val dark = ScheduleColor.isDarkMode(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(10), dp(24), dp(10))
        }
        var dialog: androidx.appcompat.app.AlertDialog? = null

        val rows = (names.size + 2) / 3
        for (row in 0 until rows) {
            val rowLay = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (col in 0 until 3) {
                val idx = row * 3 + col
                if (idx >= names.size) break
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    isClickable = true
                    isFocusable = true
                }
                val iv = ImageView(this).apply {
                    setImageResource(icons[idx])
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                item.addView(iv, LinearLayout.LayoutParams(dp(76), dp(76)))
                val tv = TextView(this).apply {
                    text = names[idx]
                    textSize = 12f
                    setTextColor(if (dark) Color.WHITE else Color.BLACK)
                    gravity = Gravity.CENTER
                }
                item.addView(
                    tv,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(6) }
                )
                item.setOnClickListener {
                    if (idx != current) {
                        IconSwitcher.apply(this, idx)
                        Toast.makeText(
                            this,
                            "已切换为「${names[idx]}」。桌面若未更新，请重启手机；期间可能短暂出现两个图标，属正常",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(this, "当前已在使用该图标", Toast.LENGTH_SHORT).show()
                    }
                    dialog?.dismiss()
                    buildSettings()
                }
                rowLay.addView(item, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            root.addView(rowLay)
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("应用图标")
            .setView(root)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun iconLabel(index: Int): String = when (index) {
        0 -> "经典 · 紫色九宫格"
        1 -> "课表方块"
        2 -> "日历对勾"
        3 -> "横条圆点"
        4 -> "挂扣对勾"
        5 -> "文档笔记"
        6 -> "青绿渐变"
        else -> "极光渐变"
    }

    // ============ 多课表管理 ============

    /**
     * 课表管理弹窗：
     * - 列表单选：点一行即切换当前课表（返回主界面立即显示新数据，主界面 onResume 重载）；
     * - 右侧「重命名 / 删除」：删除有二次确认，且**最后一张课表不能删**；
     * - 底部「新建课表」：输入名字后新建并自动切换过去。
     */
    private fun showTimetableManager() {
        val list = TimetableStore.list(this)
        val activeId = TimetableStore.activeId(this)
        val dark = ScheduleColor.isDarkMode(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        list.forEach { meta ->
            container.addView(buildTimetableRow(meta, meta.id == activeId, list.size, dark))
        }

        // 课表较多时给列表一个高度上限，避免弹窗被撑出屏幕
        val scroll = ScrollView(this).apply {
            addView(container)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (list.size > 4) (resources.displayMetrics.heightPixels * 0.45f).toInt()
                else ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        timetableDialog = MaterialAlertDialogBuilder(this)
            .setTitle("课表管理")
            .setView(scroll)
            .setPositiveButton("新建课表") { _, _ -> showCreateTimetableDialog() }
            .setNegativeButton(R.string.cancel, null)
            .create()
        timetableDialog?.show()
    }

    /**
     * 单行课表项（v1.36.0 改为卡片式）：
     * - 当前课表：主色淡底 + 2dp 主色描边 + 左侧主色竖条 + 「当前」徽标（三重信号，不只靠颜色）；
     * - 点击整行 = 切换课表；
     * - 右侧「课程 / 重命名 / 删除」，「课程」直接进课程列表页逐门修改。
     */
    private fun buildTimetableRow(
        meta: TimetableStore.Meta,
        isActive: Boolean,
        total: Int,
        dark: Boolean
    ): View {
        val primary = AppTheme.primary(this, dark)
        val lineColor = AppTheme.line(this, dark)
        val cardColor = AppTheme.cardBackground(this, dark)
        val textColor = AppTheme.text(this, dark)
        val muted = AppTheme.textMuted(this, dark)
        val courseCount = TimetableStore.courseCount(this, meta.id)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(11), dp(4), dp(11))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(if (isActive) withAlpha(primary, if (dark) 0x33 else 0x1F) else cardColor)
                setStroke(dp(if (isActive) 2 else 1), if (isActive) primary else lineColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
                bottomMargin = dp(8)
            }
        }

        // 左侧竖条：当前课表才有，作为「不只是颜色」的第二视觉信号
        val stripe = View(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(primary)
            }
            visibility = if (isActive) View.VISIBLE else View.INVISIBLE
        }
        row.addView(stripe, LinearLayout.LayoutParams(dp(4), dp(38)).apply { marginEnd = dp(10) })

        val nameCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameTv = TextView(this).apply {
            text = meta.name
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (isActive) primary else textColor)
            typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        titleRow.addView(nameTv)
        if (isActive) {
            val badge = TextView(this).apply {
                text = "当前"
                textSize = 10f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(1), dp(6), dp(1))
                background = GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat()
                    setColor(primary)
                }
            }
            titleRow.addView(
                badge,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6) }
            )
        }
        nameCol.addView(titleRow)
        nameCol.addView(TextView(this).apply {
            text = if (isActive) "$courseCount 门课 · 正在使用" else "$courseCount 门课"
            textSize = 11f
            setTextColor(muted)
        })
        row.addView(nameCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val courseBtn = TextView(this).apply {
            text = "课程"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(primary)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
        }
        courseBtn.setOnClickListener {
            startActivity(CourseListActivity.intent(this, meta.id))
        }
        actions.addView(courseBtn)

        val actionColor = muted
        val renameBtn = TextView(this).apply {
            text = "重命名"
            textSize = 12f
            setTextColor(actionColor)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
        }
        renameBtn.setOnClickListener { showRenameTimetableDialog(meta) }
        actions.addView(renameBtn)

        val deleteBtn = TextView(this).apply {
            text = "删除"
            textSize = 12f
            // 只剩一张时置灰：点击给出明确提示，而不是静默无反应
            setTextColor(if (total <= 1) (if (dark) spec.inactiveFgDark else spec.inactiveFgLight) else actionColor)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isClickable = true
            isFocusable = true
        }
        deleteBtn.setOnClickListener { confirmDeleteTimetable(meta, total) }
        actions.addView(deleteBtn)
        row.addView(actions)

        row.setOnClickListener {
            if (isActive) {
                Toast.makeText(this, "已经在使用「${meta.name}」", Toast.LENGTH_SHORT).show()
            } else if (TimetableStore.setActive(this, meta.id)) {
                Toast.makeText(this, "已切换到「${meta.name}」", Toast.LENGTH_SHORT).show()
                refreshTimetableManager()
            } else {
                Toast.makeText(this, "切换失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
        return row
    }

    /** 给颜色加透明度（0..255），用于淡底高亮 */
    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** 课表信息变更后：关掉旧弹窗 → 刷新设置页 → 重开列表，保证用户看到最新状态 */
    private fun refreshTimetableManager() {
        timetableDialog?.dismiss()
        timetableDialog = null
        buildSettings()
        showTimetableManager()
    }

    /** 默认新名字：避免和已有课表重名（「课表」→「课表 2」→ …） */
    private fun defaultTimetableName(): String {
        val existing = TimetableStore.list(this).map { it.name }
        return TimetableStore.uniqueName("课表", existing)
    }

    /** 新建课表：输入名字（空名/超长有校验），建好后自动设为当前课表 */
    private fun showCreateTimetableDialog() {
        val et = EditText(this).apply {
            hint = "课表名称（最多 ${TimetableStore.MAX_NAME_LEN} 字）"
            setText(defaultTimetableName())
            setSelection(text.length)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        wrap.addView(et)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("新建课表")
            .setMessage("新建后会自动切换到这张新课表，原有课表不受影响。")
            .setView(wrap)
            .setPositiveButton("新建", null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        // 名字非法时不关闭弹窗（否则提示一闪而过、用户得重新点一遍）
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = TimetableStore.validateName(et.text.toString())
                if (name == null) {
                    Toast.makeText(this, "课表名称不能为空", Toast.LENGTH_SHORT).show()
                    et.requestFocus()
                    return@setOnClickListener
                }
                val meta = TimetableStore.create(this, name)
                Toast.makeText(this, "已新建并切换到「${meta.name}」", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                refreshTimetableManager()
            }
        }
        dialog.show()
    }

    /** 重命名课表：空名不提交；重名自动消重（「课表」→「课表 2」） */
    private fun showRenameTimetableDialog(meta: TimetableStore.Meta) {
        val et = EditText(this).apply {
            setText(meta.name)
            setSelection(text.length)
            hint = "课表名称（最多 ${TimetableStore.MAX_NAME_LEN} 字）"
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        wrap.addView(et)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("重命名课表")
            .setView(wrap)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = TimetableStore.validateName(et.text.toString())
                if (name == null) {
                    Toast.makeText(this, "课表名称不能为空", Toast.LENGTH_SHORT).show()
                    et.requestFocus()
                    return@setOnClickListener
                }
                if (TimetableStore.rename(this, meta.id, name)) {
                    val now = TimetableStore.list(this).firstOrNull { it.id == meta.id }?.name ?: name
                    Toast.makeText(this, "已重命名为「$now」", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    refreshTimetableManager()
                } else {
                    Toast.makeText(this, "重命名失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    /** 删除课表：二次确认（写清会连带删除多少课程）；最后一张禁止删除 */
    private fun confirmDeleteTimetable(meta: TimetableStore.Meta, total: Int) {
        if (total <= 1) {
            Toast.makeText(this, "至少保留一张课表，最后一张不能删除", Toast.LENGTH_SHORT).show()
            return
        }
        val count = TimetableStore.courseCount(this, meta.id)
        MaterialAlertDialogBuilder(this)
            .setTitle("删除课表")
            .setMessage(
                "确定删除课表「${meta.name}」吗？\n\n" +
                    "其中 $count 门课程会一并删除，且该课表的备份也会清掉，删除后无法恢复。"
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                if (TimetableStore.delete(this, meta.id)) {
                    Toast.makeText(this, "已删除「${meta.name}」", Toast.LENGTH_SHORT).show()
                    refreshTimetableManager()
                } else {
                    Toast.makeText(this, "删除失败：至少需要保留一张课表", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRestoreBackupDialog() {
        val name = TimetableStore.activeMeta(this).name
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_backup)
            .setMessage("将用「$name」上次保存前的课表覆盖这张课表，确定恢复吗？")
            .setPositiveButton(R.string.confirm) { _, _ ->
                if (CourseStore.restoreBackup(this)) {
                    Toast.makeText(this, R.string.restored_backup, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_SHORT).show()
                }
                buildSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 主题选择：单选列表；切换后立即重建界面（本页即时生效，返回主页也会用新配色重绘） */
    private fun showThemePicker() {
        val specs = AppTheme.ALL
        val labels = specs.map { it.label }.toTypedArray()
        val current = Prefs.theme(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("界面主题")
            .setSingleChoiceItems(labels, current) { dialog, which ->
                dialog.dismiss()
                val id = specs[which].id
                // 顺序很重要（v1.34.x 极光闪退 + 「选项是极光、界面变墨玉」的根源）：
                //   1) 先把主题同步落盘（Prefs.setTheme 内部用 commit，不再 apply 竞态）；
                //   2) 再设置主题缓存；
                //   3) 图标切换放到 260ms 之后 —— IconSwitcher 会 disable/enable activity-alias，
                //      某些 ROM 会在这时立刻重建 Activity，若与 recreate() 撞在一起，
                //      两个 Activity 实例会各自读一次主题，表现为界面/图标错位（闪退也来自重复重建）。
                Prefs.setTheme(this, id)
                AppTheme.invalidate()
                Toast.makeText(this, "已切换到「${specs[which].label}」：${specs[which].summary}", Toast.LENGTH_SHORT).show()
                val recommended = when (id) {
                    AppTheme.JADE -> 6
                    AppTheme.AURORA -> 7
                    else -> 0
                }
                val cur = Prefs.launcherIcon(this)
                val syncIcon = (cur == 0 || cur == 6 || cur == 7) && cur != recommended
                // 重建界面：无需重启 App，本页与主页下次显示即用新主题（含布局）
                recreate()
                if (syncIcon) {
                    val ctx = applicationContext
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            IconSwitcher.apply(ctx, recommended)
                        } catch (t: Throwable) {
                            // 图标同步失败不影响主题本身：桌面图标是可选装饰
                        }
                    }, 260L)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAppearancePicker() {
        val labels = arrayOf("跟随系统", "日间（白）", "夜间（黑）")
        val current = Prefs.appearance(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.appearance)
            .setSingleChoiceItems(labels, current) { d, which ->
                Prefs.setAppearance(this, which)
                Prefs.applyNightMode(this)
                d.dismiss()
                recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 课表卡片样式：圆角 / 方块 */
    private fun showCardStylePicker() {
        val labels = arrayOf("圆角", "方块")
        val current = Prefs.cardStyle(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("课表卡片样式")
            .setSingleChoiceItems(labels, current) { d, which ->
                Prefs.setCardStyle(this, which)
                d.dismiss()
                buildSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 课表卡片阴影开关 */
    private fun showCardShadowPicker() {
        val labels = arrayOf("开", "关")
        val current = if (Prefs.cardShadow(this)) 0 else 1
        MaterialAlertDialogBuilder(this)
            .setTitle("课表卡片阴影")
            .setSingleChoiceItems(labels, current) { d, which ->
                Prefs.setCardShadow(this, which == 0)
                d.dismiss()
                buildSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 课表背景：跟随主题 / 米黄 / 淡蓝 / 淡绿 / 淡粉 / 自定义图片 */
    private fun showBackgroundPicker() {
        val hasImg = Prefs.backgroundImage(this) != null
        val labels = if (hasImg) {
            arrayOf("跟随主题", "米黄", "淡蓝", "淡绿", "淡粉", "自定义图片（已设置）", "移除自定义图片")
        } else {
            arrayOf("跟随主题", "米黄", "淡蓝", "淡绿", "淡粉", "自定义图片")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("课表背景")
            .setItems(labels) { d, which ->
                if (which < 5) {
                    Prefs.setBackgroundStyle(this, which)
                } else if (which == 5) {
                    pickBgImage.launch(
                        PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.ImageOnly
                        )
                    )
                } else {
                    Prefs.setBackgroundImage(this, null)
                    Toast.makeText(this, "已恢复为纯色背景", Toast.LENGTH_SHORT).show()
                }
                d.dismiss()
                buildSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun backgroundLabel(style: Int): String {
        if (Prefs.backgroundImage(this) != null) return "自定义图片"
        return when (style) {
            Prefs.BG_CREAM -> "米黄"
            Prefs.BG_SKY -> "淡蓝"
            Prefs.BG_MINT -> "淡绿"
            Prefs.BG_BLUSH -> "淡粉"
            else -> "跟随主题"
        }
    }

    /** 一键随机打乱课程方块颜色（同一门课的多个时段保持同色；课程数 ≤ 色板数时颜色互不重复） */
    private fun shuffleCourseColors() {
        val loaded = CourseStore.load(this)
        if (loaded.isEmpty()) {
            Toast.makeText(this, "还没有课程，先添加课程吧", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("随机打乱课程颜色")
            .setMessage("将为每门课随机分配新的方块颜色（同一门课的多个时段保持同色）。确定吗？")
            .setPositiveButton(R.string.confirm) { _, _ ->
                val names = loaded.map { it.name }.distinct()
                val colorCount = ScheduleColor.count(this)
                // 洗牌色板索引：前 colorCount 门课颜色不重复，之后循环
                val shuffled = (0 until colorCount).shuffled()
                val nameColor = HashMap<String, Int>()
                names.forEachIndexed { i, name -> nameColor[name] = shuffled[i % colorCount] }
                val newCourses = loaded.map { c ->
                    c.copy(colorIndex = nameColor[c.name] ?: c.colorIndex)
                }
                CourseStore.save(this, newCourses)
                Toast.makeText(this, "已随机换色，共 ${names.size} 门课", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 单个设置项渲染成一枚「胶囊」：
     * 圆角 18dp 卡片底 + 1dp 描边 + 主色涟漪按压反馈 + 左侧小圆点，替代原来的「文字行 + 横线」。
     * 项与项之间留 10dp 间距，视觉上是一列独立可点的胶囊，而不是一张密密麻麻的表。
     */
    private fun addSetting(
        title: String,
        value: String?,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ) {
        val dark = ScheduleColor.isDarkMode(this)
        val radius = dp(18).toFloat()
        val card = if (dark) spec.cardDark else spec.cardLight
        val line = if (dark) spec.lineDark else spec.lineLight
        val primary = AppTheme.primary(this, dark)

        val shapeBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(card)
            setStroke(dp(1), line)
        }
        // 涟漪用「主色淡影」铺在胶囊形状上，按下时整枚胶囊轻微亮起，反馈比横线列表更明确
        val ripple = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(withAlpha(primary, if (dark) 0x26 else 0x1F)),
            shapeBg,
            shapeBg
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            background = ripple
            isClickable = true
            isFocusable = true
        }
        row.setOnClickListener { onClick() }
        if (onLongClick != null) {
            row.setOnLongClickListener {
                onLongClick.invoke()
                true
            }
        }

        // 左侧小圆点：不抢戏，但让每一项都有"标记点"的质感
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(withAlpha(primary, if (dark) 0x4D else 0x38))
            }
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(12) })

        val titleTv = TextView(this).apply {
            text = title
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(if (dark) spec.textDark else spec.textLight)
        }
        row.addView(titleTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (value != null) {
            val valueTv = TextView(this).apply {
                text = value
                textSize = 13f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(170)
                setTextColor(if (dark) spec.textMutedDark else spec.textMutedLight)
            }
            row.addView(valueTv)
        }

        val chevron = ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(if (dark) spec.inactiveFgDark else spec.inactiveFgLight)
        }
        row.addView(chevron, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginStart = dp(8) })

        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10)
        binding.settingsContainer.addView(row, lp)
    }

    private fun showDatePicker() {
        val current = Prefs.semesterStart(this)
        val cal = Calendar.getInstance().apply { timeInMillis = current }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val newCal = Calendar.getInstance().apply {
                    set(year, month, day, 0, 0, 0)
                    set(Calendar.MILLISECOND, 0)
                    val dow = get(Calendar.DAY_OF_WEEK)
                    add(Calendar.DAY_OF_MONTH, (Calendar.MONDAY - dow + 7) % 7)
                }
                Prefs.setSemesterStart(this, newCal.timeInMillis)
                buildSettings()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun showWeeksPicker() {
        val np = NumberPicker(this).apply {
            minValue = 1
            maxValue = 40
            value = Prefs.totalWeeks(this@SettingsActivity)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.total_weeks)
            .setView(np)
            .setPositiveButton(R.string.confirm) { _, _ ->
                Prefs.setTotalWeeks(this, np.value)
                buildSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun exportJson() {
        val courses = CourseStore.load(this)
        if (courses.isEmpty()) {
            Toast.makeText(this, "暂无课程可导出", Toast.LENGTH_SHORT).show()
            return
        }
        val json = CourseJson.encode(courses)
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("courses", json))
        Toast.makeText(this, R.string.json_copied, Toast.LENGTH_SHORT).show()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, json)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_via)))
    }

    private fun exportImage() {
        val courses = CourseStore.load(this)
        if (courses.isEmpty()) {
            Toast.makeText(this, "暂无课程可导出", Toast.LENGTH_SHORT).show()
            return
        }
        val view = ScheduleView(this)
        view.setData(courses, Prefs.currentWeek(this))
        val width = (360 * resources.displayMetrics.density).toInt()
        val bmp = view.renderToBitmap(width)

        // 导出到 cache/exports 子目录，与 FileProvider 的导出路径配置保持一致
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "schedule_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }

        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_via)))
    }

    /**
     * 导出 .ics 日历文件：用户导入系统日历/第三方日历后，
     * 上课提醒由日历进程触发，不受课格被国ROM省电策略清理的影响。
     */
    private fun exportCalendar() {
        val courses = CourseStore.load(this)
        if (courses.isEmpty()) {
            Toast.makeText(this, "暂无课程可导出", Toast.LENGTH_SHORT).show()
            return
        }
        val ics = IcsExporter.build(
            courses = courses,
            semesterStartMillis = Prefs.semesterStart(this),
            sections = SectionTimes.load(this)
        )
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, IcsExporter.suggestFileName())
        FileOutputStream(file).use { it.write(ics.toByteArray(Charsets.UTF_8)) }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_calendar)
            .setMessage(R.string.export_calendar_hint)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.share_via) { _, _ -> shareCalendar(file) }
            .show()
    }

    private fun shareCalendar(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/calendar"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "课格课表")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_via)))
    }

    /**
     * 清空课程：多课表下只清空**当前课表**（其他课表不受影响），文案里点名是哪一张，
     * 避免用户误以为是清空全部课表。清空前数据会自动留成该课表的滚动备份。
     */
    private fun clearAll() {
        val name = TimetableStore.activeMeta(this).name
        val count = TimetableStore.courseCount(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("清空当前课表")
            .setMessage(
                "确定清空课表「$name」的全部 $count 门课程？\n\n" +
                    "其他课表不受影响；清空前数据会自动保留为备份，可在设置中恢复。"
            )
            .setPositiveButton(R.string.confirm) { _, _ ->
                TimetableStore.save(this, emptyList())
                Toast.makeText(this, "已清空「$name」", Toast.LENGTH_SHORT).show()
                buildSettings()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle("课格 ClassGrid")
            .setMessage("版本 v${BuildConfig.VERSION_NAME} · ${BuildConfig.CHANNEL}\n\n简洁好用的课程表，支持截图识别、Excel/CSV、链接抓取、JSON 与手动导入。\n\n这是内测版：遇到识别不准，请用结果页的「导出识别日志」把原文发回来，我会据此改进。\n\n祝你新学期一切顺利 ✨")
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
