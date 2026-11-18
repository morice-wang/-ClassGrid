package com.classgrid.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.classgrid.app.databinding.ActivityOcrResultBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OcrResultActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
        const val EXTRA_RAW_TEXT = "raw_text"
        const val EXTRA_COURSES = "courses"
    }

    private lateinit var binding: ActivityOcrResultBinding
    private val candidates = mutableListOf<Course>()
    private val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 当前主题（主题化取色的唯一来源，v1.34.0） */
    private val spec: AppTheme.Spec
        get() = AppTheme.current(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityOcrResultBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.header) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }

        val imageUri = intent.getStringExtra(EXTRA_IMAGE_URI)
        val rawText = intent.getStringExtra(EXTRA_RAW_TEXT).orEmpty()
        val coursesJson = intent.getStringExtra(EXTRA_COURSES).orEmpty()
        candidates.addAll(CourseJson.decode(coursesJson))

        // OCR 导入的课程默认同色（colorIndex=0），按顺序自动分配色板颜色，便于核对区分
        val palCount = ScheduleColor.count(this)
        candidates.indices.forEach { i ->
            val c = candidates[i]
            if (c.colorIndex == 0) candidates[i] = c.copy(colorIndex = i % palCount)
        }

        if (!imageUri.isNullOrEmpty()) {
            binding.imgPreview.setImageURI(android.net.Uri.parse(imageUri))
        }

        // OCR 原文（可展开），便于核对与反馈
        if (rawText.isNotBlank()) {
            binding.tvRaw.visibility = View.VISIBLE
            binding.tvRaw.text = rawText
            binding.tvRaw.maxLines = 3
            binding.tvRaw.setOnClickListener {
                binding.tvRaw.maxLines = if (binding.tvRaw.maxLines == 3) 1000 else 3
            }
        } else {
            binding.tvRaw.visibility = View.GONE
        }

        val hint = if (candidates.isEmpty()) {
            "未自动识别出课程，可返回手动添加；或粘贴文本到 JSON 导入"
        } else {
            "已识别出 ${candidates.size} 门课程，请核对（点击编辑，右侧删除）\n" +
                "导入目标：当前课表「${TimetableStore.activeMeta(this).name}」"
        }
        binding.tvHint.text = hint

        renderList()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnDiscard.setOnClickListener { finish() }
        binding.btnImport.setOnClickListener {
            if (candidates.isEmpty()) {
                Toast.makeText(this, R.string.recognize_fail, Toast.LENGTH_SHORT).show()
            } else {
                showImportTargetDialog()
            }
        }

        // v1.42.0 内测版：识别结果页显眼位置提供「导出识别日志」，
        // 用户不用回退到导入页翻菜单，直接在核对页就能把原文和解析结果发出来。
        binding.btnExportLog.setOnClickListener { exportRecognitionLog(rawText) }
        binding.btnExportLog.setOnLongClickListener {
            Toast.makeText(this, R.string.ocr_export_log_hint, Toast.LENGTH_LONG).show()
            true
        }
    }

    /**
     * 导出识别日志（v1.42.0 内测版）：原文 + 解析结果 + 设备信息。
     * 识别不准时这是用户唯一能一键回传的证据，别藏在菜单里。
     */
    private fun exportRecognitionLog(rawText: String) {
        try {
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "classgrid_ocr_" + System.currentTimeMillis() + ".txt")
            val sb = StringBuilder()
            sb.append("课格 ClassGrid 识别日志（内测版）").append('\n')
            sb.append("版本：").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(')').append('\n')
            sb.append("时间：").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
            sb.append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE).append('\n')
            sb.append("识别出课程：").append(candidates.size).append(" 门").append('\n')
            if (TableGridOcr.lastNote.isNotBlank()) {
                sb.append("表格分格：").append(TableGridOcr.lastNote).append('\n')
            }
            sb.append('\n').append("== 解析结果 ==").append('\n')
            val days = arrayOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")
            candidates.forEachIndexed { i, c ->
                val day = days.getOrNull(c.dayOfWeek) ?: "?"
                sb.append(i + 1).append(". ").append(day)
                    .append(" 第").append(c.startSection).append('-').append(c.endSection).append("节")
                    .append(" ").append(c.startWeek).append('-').append(c.endWeek).append("周")
                    .append(" ").append(c.name)
                if (c.teacher.isNotBlank()) sb.append(" 教师:").append(c.teacher)
                if (c.classroom.isNotBlank()) sb.append(" 教室:").append(c.classroom)
                sb.append('\n')
            }
            sb.append('\n').append("== 识别原文 ==").append('\n').append(rawText)
            file.writeText(sb.toString(), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ClassGrid 识别日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.ocr_export_log)))
        } catch (t: Throwable) {
            Toast.makeText(this, "导出失败：" + (t.message ?: t.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    // ============ 导入到哪张课表（多课表） ============

    /**
     * 点「导入」后选目标课表（v1.36.0 改成卡片式，突出「新建课表」）：
     * 1. 合并到当前课表（同名+同天+同起始节覆盖，其余保留）；
     * 2. 覆盖当前课表（先清空，二次确认里写明会清掉多少门）；
     * 3. 新建课表并导入（推荐项：高亮卡片 + 「推荐」徽标，建好后提示可立即查看课程）。
     * 三个选项都能取消，取消后停留本页、识别结果不丢。
     */
    private fun showImportTargetDialog() {
        val active = TimetableStore.activeMeta(this)
        val count = TimetableStore.courseCount(this)
        val dark = ScheduleColor.isDarkMode(this)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(4))
        }
        container.addView(
            importOptionRow(
                title = "合并到当前课表「${active.name}」",
                subtitle = "保留原有 $count 门课；重复的课程会被这次的结果更新",
                highlighted = false,
                dark = dark
            ) { doMergeIntoCurrent() }
        )
        container.addView(
            importOptionRow(
                title = "覆盖当前课表「${active.name}」",
                subtitle = "先清空 $count 门课，再写入这次核对后的 ${candidates.size} 门（清空前自动备份）",
                highlighted = false,
                dark = dark
            ) { confirmReplaceCurrent(active, count) }
        )
        container.addView(
            importOptionRow(
                title = "新建课表并导入",
                subtitle = "单独存成一张新课表并设为当前课表，原来的课表不受影响",
                highlighted = true,
                dark = dark,
                badge = "推荐"
            ) { promptNewTimetableAndImport(active) }
        )

        val scroll = ScrollView(this).apply {
            addView(container)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("导入到哪张课表？")
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 导入方式卡片：整块可点，标题 + 说明 +（可选）推荐徽标。
     * highlighted=true 时用主色淡底 + 2dp 主色描边，让「新建课表」在三个选项里一眼可见。
     */
    private fun importOptionRow(
        title: String,
        subtitle: String,
        highlighted: Boolean,
        dark: Boolean,
        badge: String? = null,
        onClick: () -> Unit
    ): View {
        val primary = AppTheme.primary(this, dark)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(
                    if (highlighted) withAlpha(primary, if (dark) 0x33 else 0x1F)
                    else AppTheme.cardBackground(this@OcrResultActivity, dark)
                )
                setStroke(
                    dp(if (highlighted) 2 else 1),
                    if (highlighted) primary else AppTheme.line(this@OcrResultActivity, dark)
                )
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = title
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (highlighted) primary else AppTheme.text(this@OcrResultActivity, dark))
        })
        if (!badge.isNullOrEmpty()) {
            val badgeTv = TextView(this).apply {
                text = badge
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
                badgeTv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6) }
            )
        }
        row.addView(titleRow)

        row.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(AppTheme.textMuted(this@OcrResultActivity, dark))
            setPadding(0, dp(4), 0, 0)
        })

        row.setOnClickListener { onClick() }
        return row
    }

    /** 导入成功后统一提示：明确「导入到了哪张课表 / 多少门」，并给一个「查看课程」入口 */
    private fun showImportDoneDialog(tableId: String?, message: String) {
        // v1.44.0 桌面小组件：导入完成立刻重画，不用等用户回到首页
        WidgetRefresh.refresh(this)
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle("导入完成")
            .setMessage(message)
            .setPositiveButton("完成") { _, _ -> finish() }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
        if (tableId != null) {
            builder.setNeutralButton("查看课程") { _, _ ->
                startActivity(CourseListActivity.intent(this, tableId))
                finish()
            }
        }
        builder.show()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    /** 选项 1：合并（保留原课程，重复课程更新） */
    private fun doMergeIntoCurrent() {
        val active = TimetableStore.activeMeta(this)
        val added = TimetableStore.mergeAndSave(this, candidates)
        val msg = if (added > 0) {
            "已向「${active.name}」合并导入 $added 门课程（重复课程已按最新结果更新）。"
        } else {
            "导入完成，无新增课程（已有课程均已按这次核对结果更新）。"
        }
        showImportDoneDialog(TimetableStore.activeId(this), msg)
    }

    /** 选项 2：覆盖当前课表（二次确认写清楚会清空多少门） */
    private fun confirmReplaceCurrent(active: TimetableStore.Meta, currentCount: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle("覆盖当前课表")
            .setMessage(
                "将清空课表「${active.name}」的 $currentCount 门课程，再写入这次核对后的 ${candidates.size} 门课程。\n\n" +
                    "清空前会自动保留备份（可在设置里恢复），确定覆盖吗？"
            )
            .setPositiveButton("覆盖导入") { _, _ ->
                if (TimetableStore.replace(this, candidates)) {
                    showImportDoneDialog(
                        TimetableStore.activeId(this),
                        "已用 ${candidates.size} 门课程覆盖「${active.name}」。"
                    )
                } else {
                    Toast.makeText(this, "导入失败，请重试或检查存储空间", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 选项 3：新建课表并导入（起名 + 二次确认：当前课表不受影响） */
    private fun promptNewTimetableAndImport(active: TimetableStore.Meta) {
        val et = EditText(this).apply {
            hint = "课表名称（最多 ${TimetableStore.MAX_NAME_LEN} 字）"
            setText(TimetableStore.uniqueName("课表", TimetableStore.list(this@OcrResultActivity).map { it.name }))
            setSelection(text.length)
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        wrap.addView(et)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("新建课表并导入")
            .setMessage(
                "将新建一张课表并设为当前课表，再导入这次核对后的 ${candidates.size} 门课程。\n\n" +
                    "当前课表「${active.name}」不受影响。"
            )
            .setView(wrap)
            .setPositiveButton("新建并导入", null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        // 名字非法时**不关闭弹窗**（直接在 onShow 里覆盖按钮点击）：否则用户刚看到提示就被弹回核对页，
        // 得重新点一遍「导入」再选一次「新建课表」，体验很差。
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = TimetableStore.validateName(et.text.toString())
                if (name == null) {
                    Toast.makeText(this, "课表名称不能为空", Toast.LENGTH_SHORT).show()
                    et.requestFocus()
                    return@setOnClickListener
                }
                val meta = TimetableStore.create(this, name, candidates)
                dialog.dismiss()
                // 新建课表后给出明确结果 + 直达课程列表（用户反馈「新建课表没有突出显示」）
                showImportDoneDialog(
                    meta.id,
                    "已新建课表「${meta.name}」并导入 ${candidates.size} 门课程，已设为当前课表。"
                )
            }
        }
        dialog.show()
    }

    private fun renderList() {
        binding.courseList.removeAllViews()
        val dark = ScheduleColor.isDarkMode(this)
        for ((idx, course) in candidates.withIndex()) {
            binding.courseList.addView(buildItem(course, idx, dark))
        }
    }

    private fun buildItem(course: Course, index: Int, dark: Boolean): View {
        val pal = ScheduleColor.palette(this, course.colorIndex, dark)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val dot = View(this)
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(pal.bg)
            setStroke(dp(2), pal.accent)
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(16), dp(16)).apply {
            marginEnd = dp(12)
        })

        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nameTv = TextView(this).apply {
            text = course.name
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (dark) Color.WHITE else Color.BLACK)
        }
        val subTv = TextView(this).apply {
            val detail = buildString {
                append(dayNames.getOrElse(course.dayOfWeek - 1) { "" })
                append(" 第${course.startSection}-${course.endSection}节")
                append(" · ${course.startWeek}-${course.endWeek}周")
                if (course.startTime.isNotEmpty() && course.endTime.isNotEmpty()) {
                    append(" · ${course.startTime}-${course.endTime}")
                }
            }
            text = detail
            textSize = 12f
            setTextColor(if (dark) spec.textMutedDark else spec.textMutedLight)
        }
        val extraTv = TextView(this).apply {
            val parts = mutableListOf<String>()
            if (course.code.isNotEmpty()) parts.add("代码 ${course.code}")
            if (course.teacher.isNotEmpty()) parts.add(course.teacher)
            if (course.classroom.isNotEmpty()) parts.add(course.classroom)
            // 字段不全 → 提示待补全
            val incomplete = course.teacher.isEmpty() || course.classroom.isEmpty() ||
                course.dayOfWeek !in 1..7 || course.startSection !in 1..15
            if (parts.isEmpty() && incomplete) parts.add("待补全")
            text = parts.joinToString(" · ")
            textSize = 12f
            setTextColor(if (dark) spec.textMutedDark else spec.textMutedLight)
        }
        textCol.addView(nameTv)
        textCol.addView(subTv)
        textCol.addView(extraTv)
        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val del = ImageView(this).apply {
            setImageResource(R.drawable.ic_delete)
            setColorFilter(if (dark) spec.inactiveFgDark else spec.inactiveFgLight)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        del.setOnClickListener {
            candidates.removeAt(index)
            renderList()
        }
        row.addView(del, LinearLayout.LayoutParams(dp(40), dp(40)))

        row.setOnClickListener { showEditDialog(index) }
        return row
    }

    private fun showEditDialog(index: Int) {
        val course = candidates[index]
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
        }

        val etName = EditText(this).apply {
            setText(course.name)
            hint = getString(R.string.course_name)
        }
        container.addView(etName)

        val etCode = EditText(this).apply {
            setText(course.code)
            hint = "课程代码（如 GU20041）"
        }
        container.addView(etCode)

        val etTeacher = EditText(this).apply {
            setText(course.teacher)
            hint = "授课教师"
        }
        container.addView(etTeacher)

        val etRoom = EditText(this).apply {
            setText(course.classroom)
            hint = "上课地点"
        }
        container.addView(etRoom)

        val daySpinner = Spinner(this)
        daySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, dayNames)
        daySpinner.setSelection((course.dayOfWeek - 1).coerceIn(0, 6))
        container.addView(daySpinner)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val startSpinner = Spinner(this)
        startSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, (1..15).map { "第${it}节" })
        startSpinner.setSelection((course.startSection - 1).coerceIn(0, 14))
        val endSpinner = Spinner(this)
        endSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, (1..15).map { "第${it}节" })
        endSpinner.setSelection((course.endSection - 1).coerceIn(0, 14))
        row.addView(startSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(endSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(row)

        val weekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val etStartWeek = EditText(this).apply {
            setText(course.startWeek.toString())
            hint = "起始周"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val etEndWeek = EditText(this).apply {
            setText(course.endWeek.toString())
            hint = "结束周"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        weekRow.addView(etStartWeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        weekRow.addView(etEndWeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(weekRow)

        MaterialAlertDialogBuilder(this)
            .setTitle("编辑课程")
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val sw = etStartWeek.text.toString().toIntOrNull()?.coerceIn(1, 40) ?: course.startWeek
                val ew = etEndWeek.text.toString().toIntOrNull()?.coerceIn(sw, 40) ?: course.endWeek
                // 节次也要保证 start <= end：历史实现直接取 Spinner 位置，
                // 用户把结束节选到起始节之前时会写出倒序课程（课表上画不出来、也点不到，等于脏数据）
                val ss = startSpinner.selectedItemPosition + 1
                val es = (endSpinner.selectedItemPosition + 1).coerceAtLeast(ss)
                candidates[index] = course.copy(
                    name = name,
                    code = etCode.text.toString().trim(),
                    teacher = etTeacher.text.toString().trim(),
                    classroom = etRoom.text.toString().trim(),
                    dayOfWeek = daySpinner.selectedItemPosition + 1,
                    startSection = ss,
                    endSection = es,
                    startWeek = sw,
                    endWeek = ew
                )
                renderList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
