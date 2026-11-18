package com.classgrid.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.updatePadding
import com.classgrid.app.databinding.ActivityCourseListBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 课程列表（v1.36.0 新增）。
 *
 * 为什么要有这一页：课表是方块网格，想改某一门课的时间得在那个小方格里点半天，
 * 而且课程多时很容易点错。这里按星期分组列出当前课表的**全部课程**，
 * 点任意一门就能改（和「截图识别」后的核对页是同一种交互），改完立即落盘。
 *
 * 入口：
 *  - 设置 → 课表管理 → 某张课表的「课程」按钮（可以看/改非当前课表）
 *  - 设置 → 当前课表的课程（N 门）
 *  - 导入完成后的提示（导入成功 → 查看课程）
 */
class CourseListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TABLE_ID = "extra_table_id"

        fun intent(context: Context, tableId: String? = null): Intent =
            Intent(context, CourseListActivity::class.java).apply {
                if (!tableId.isNullOrEmpty()) putExtra(EXTRA_TABLE_ID, tableId)
            }
    }

    private lateinit var binding: ActivityCourseListBinding
    private lateinit var tableId: String
    private lateinit var tableName: String
    private val courses = mutableListOf<Course>()

    private val dayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    private val spec: AppTheme.Spec get() = AppTheme.current(this)
    private val dark: Boolean get() = ScheduleColor.isDarkMode(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityCourseListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.header) { v, insets ->
            val top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top + dp(6))
            insets
        }

        // 目标课表：外部指定的 id 优先（可能不是当前课表），否则用当前课表
        val wanted = intent.getStringExtra(EXTRA_TABLE_ID)
        val all = TimetableStore.list(this)
        val meta = all.firstOrNull { it.id == wanted } ?: TimetableStore.activeMeta(this)
        tableId = meta.id
        tableName = meta.name
        courses.clear()
        courses.addAll(TimetableStore.load(this, tableId))

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAdd.setOnClickListener { openEditor(null) }
        binding.btnAdd.setTextColor(AppTheme.primary(this, dark))
        binding.tvTitle.setTextColor(AppTheme.text(this, dark))
        binding.tvSubtitle.setTextColor(AppTheme.textMuted(this, dark))
        binding.tvEmpty.setTextColor(AppTheme.textMuted(this, dark))
        binding.root.setBackgroundColor(AppTheme.windowBackground(this, dark))

        render()
    }

    override fun onResume() {
        super.onResume()
        // 从别的页面回来（例如在设置里切换了课表）重新读一次，避免显示过期数据
        val fresh = TimetableStore.load(this, tableId)
        if (fresh.size != courses.size || fresh != courses) {
            courses.clear()
            courses.addAll(fresh)
            render()
        }
    }

    private fun persist() {
        val ok = TimetableStore.save(this, courses, tableId)
        if (!ok) {
            Toast.makeText(this, "保存失败：请检查存储空间后重试", Toast.LENGTH_SHORT).show()
        }
    }

    private fun render() {
        binding.tvSubtitle.text = buildString {
            append(tableName)
            append(" · ${courses.size} 门课")
            if (tableId == TimetableStore.activeId(this@CourseListActivity)) append(" · 当前课表")
        }
        binding.tvEmpty.visibility = if (courses.isEmpty()) View.VISIBLE else View.GONE
        binding.scroll.visibility = if (courses.isEmpty()) View.GONE else View.VISIBLE

        binding.courseList.removeAllViews()
        // 按星期分组（1..7），同一天按起始节次排；空的那一天不显示分组标题
        for (day in 1..7) {
            val dayCourses = courses
                .withIndex()
                .filter { it.value.dayOfWeek == day }
                .sortedBy { it.value.startSection }
            if (dayCourses.isEmpty()) continue
            binding.courseList.addView(buildDayHeader(day, dayCourses.size))
            dayCourses.forEach { indexed ->
                binding.courseList.addView(buildCourseRow(indexed.value, indexed.index))
            }
        }
        binding.tvEmpty.text = "这张课表还没有课程，点右上角「+ 添加」或去导入课表"
    }

    private fun buildDayHeader(day: Int, count: Int): View {
        val tv = TextView(this).apply {
            text = "${dayNames[day - 1]}  ·  $count 门"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppTheme.primary(this@CourseListActivity, dark))
            setPadding(0, dp(14), 0, dp(4))
        }
        return tv
    }

    private fun buildCourseRow(course: Course, index: Int): View {
        val pal = ScheduleColor.palette(this, course.colorIndex, dark)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(6), dp(10))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(AppTheme.cardBackground(this@CourseListActivity, dark))
                setStroke(dp(1), AppTheme.line(this@CourseListActivity, dark))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val dot = View(this)
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(pal.bg)
            setStroke(dp(2), pal.accent)
        }
        card.addView(dot, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(10) })

        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(TextView(this).apply {
            text = course.name
            textSize = spec.layout.type.cardNameSp
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(AppTheme.text(this@CourseListActivity, dark))
        })
        textCol.addView(TextView(this).apply {
            text = buildString {
                append("第${course.startSection}-${course.endSection}节")
                append(" · ${course.weekDesc()}")
                if (course.startTime.isNotEmpty() && course.endTime.isNotEmpty()) {
                    append(" · ${course.startTime}-${course.endTime}")
                }
            }
            textSize = 12f
            setTextColor(AppTheme.textMuted(this@CourseListActivity, dark))
        })
        val extra = buildList {
            if (course.code.isNotEmpty()) add("代码 ${course.code}")
            if (course.teacher.isNotEmpty()) add(course.teacher)
            if (course.classroom.isNotEmpty()) add(course.classroom)
        }
        if (extra.isNotEmpty()) {
            textCol.addView(TextView(this).apply {
                text = extra.joinToString(" · ")
                textSize = 12f
                setTextColor(AppTheme.textMuted(this@CourseListActivity, dark))
            })
        }
        card.addView(
            textCol,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val del = ImageView(this).apply {
            setImageResource(R.drawable.ic_delete)
            setColorFilter(AppTheme.textMuted(this@CourseListActivity, dark))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        del.setOnClickListener { confirmDelete(index) }
        card.addView(del, LinearLayout.LayoutParams(dp(40), dp(40)))

        card.setOnClickListener { openEditor(index) }
        return card
    }

    private fun confirmDelete(index: Int) {
        val course = courses.getOrNull(index) ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("删除课程")
            .setMessage("确定从「$tableName」删除「${course.name}」吗？")
            .setPositiveButton("删除") { _, _ ->
                courses.removeAt(index)
                persist()
                render()
                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 编辑弹窗：index 为 null 时是「新增」。
     * 保存后会立刻写入当前课表（不需要再点别的按钮），并刷新列表。
     */
    private fun openEditor(index: Int?) {
        val original = if (index != null) courses.getOrNull(index) else null
        val base = original ?: Course(
            name = "",
            dayOfWeek = 1,
            startSection = 1,
            endSection = 2,
            startWeek = 1,
            endWeek = Prefs.totalWeeks(this)
        )
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), 0)
        }

        val etName = EditText(this).apply {
            setText(base.name)
            hint = getString(R.string.course_name)
            setSelection(text.length)
        }
        container.addView(etName)
        val etCode = EditText(this).apply { setText(base.code); hint = "课程代码（可选）" }
        container.addView(etCode)
        val etTeacher = EditText(this).apply { setText(base.teacher); hint = "授课教师（可选）" }
        container.addView(etTeacher)
        val etRoom = EditText(this).apply { setText(base.classroom); hint = "上课地点（可选）" }
        container.addView(etRoom)

        val daySpinner = Spinner(this)
        daySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, dayNames)
        daySpinner.setSelection((base.dayOfWeek - 1).coerceIn(0, 6))
        container.addView(daySpinner)

        val secRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val sectionLabels = (1..15).map { "第${it}节" }
        val startSpinner = Spinner(this)
        startSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sectionLabels)
        startSpinner.setSelection((base.startSection - 1).coerceIn(0, 14))
        val endSpinner = Spinner(this)
        endSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sectionLabels)
        endSpinner.setSelection((base.endSection - 1).coerceIn(0, 14))
        secRow.addView(startSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        secRow.addView(endSpinner, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(secRow)

        val weekRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val etStartWeek = EditText(this).apply {
            setText(base.startWeek.toString())
            hint = "起始周"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val etEndWeek = EditText(this).apply {
            setText(base.endWeek.toString())
            hint = "结束周"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        weekRow.addView(etStartWeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        weekRow.addView(etEndWeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(weekRow)

        val scrollWrap = android.widget.ScrollView(this).apply { addView(container) }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (original == null) "添加课程" else "编辑课程")
            .setView(scrollWrap)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = etName.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val ss = startSpinner.selectedItemPosition + 1
                // 结束节必须 >= 起始节，否则会写出画不出来也点不到的倒序课程
                val es = (endSpinner.selectedItemPosition + 1).coerceAtLeast(ss)
                val sw = etStartWeek.text.toString().toIntOrNull()?.coerceIn(1, 40) ?: base.startWeek
                val ew = etEndWeek.text.toString().toIntOrNull()?.coerceIn(sw, 40) ?: base.endWeek
                val edited = base.copy(
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
                if (index != null && index in courses.indices) {
                    courses[index] = edited
                } else {
                    // 新增：颜色按已有课程数轮转，避免全是同一种色
                    courses.add(edited.copy(colorIndex = courses.size % ScheduleColor.count(this)))
                }
                persist()
                render()
                Toast.makeText(this, if (original == null) "已添加" else "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
