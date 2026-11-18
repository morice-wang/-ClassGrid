package com.classgrid.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.classgrid.app.databinding.ActivityCourseEditBinding
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.view.ViewGroup

class CourseEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "course_id"
    }

    private lateinit var binding: ActivityCourseEditBinding
    private var editingCourse: Course? = null
    private var selectedDay = 1
    private var selectedWeekType = Course.WEEK_EVERY
    private var selectedColor = 0
    private val colorViews = mutableListOf<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCourseEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.header) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }

        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id > 0) {
            editingCourse = CourseStore.load(this).find { it.id == id }
        }

        setupSpinners()
        setupColorPalette()
        setupListeners()
        populate()
    }

    private fun setupSpinners() {
        val sections = (1..15).map { "第 $it 节" }
        val weeks = (1..40).map { "第 $it 周" }
        setupSpinner(binding.spinnerStartSection, sections)
        setupSpinner(binding.spinnerEndSection, sections)
        setupSpinner(binding.spinnerStartWeek, weeks)
        setupSpinner(binding.spinnerEndWeek, weeks)
    }

    private fun setupSpinner(spinner: Spinner, items: List<String>) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
    }

    private fun setupColorPalette() {
        val dark = ScheduleColor.isDarkMode(this)
        val size = dp(40)
        val margin = dp(12)
        for (i in 0 until ScheduleColor.count(this)) {
            val v = View(this)
            v.layoutParams = ViewGroup.MarginLayoutParams(size, size).apply { marginEnd = margin }
            val pal = ScheduleColor.palette(this, i, dark)
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(pal.bg)
                setStroke(dp(2), pal.bg)
            }
            v.setOnClickListener { selectColor(i) }
            binding.colorPalette.addView(v)
            colorViews.add(v)
        }
    }

    private fun selectColor(index: Int) {
        selectedColor = index
        val dark = ScheduleColor.isDarkMode(this)
        for ((i, v) in colorViews.withIndex()) {
            val pal = ScheduleColor.palette(this, i, dark)
            val gd = v.background as GradientDrawable
            gd.setColor(pal.bg)
            if (i == index) {
                gd.setStroke(dp(3), pal.accent)
            } else {
                gd.setStroke(dp(2), pal.bg)
            }
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnSave.setOnClickListener { save() }

        binding.btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete)
                .setMessage(R.string.delete_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    val courses = CourseStore.load(this).filter { it.id != editingCourse?.id }
                    CourseStore.save(this, courses)
                    finish()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.chipDay.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = group.findViewById<Chip>(checkedIds[0])
                selectedDay = group.indexOfChild(chip) + 1
            }
        }

        binding.chipWeekType.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                selectedWeekType = group.indexOfChild(group.findViewById<Chip>(checkedIds[0]))
            }
        }
    }

    private fun populate() {
        val c = editingCourse
        if (c != null) {
            binding.tvTitle.setText(R.string.edit_title_edit)
            binding.btnDelete.visibility = View.VISIBLE
            binding.etName.setText(c.name)
            binding.etTeacher.setText(c.teacher)
            binding.etClassroom.setText(c.classroom)
            selectedDay = c.dayOfWeek
            selectedWeekType = c.weekType
            selectedColor = c.colorIndex
            binding.spinnerStartSection.setSelection(c.startSection - 1)
            binding.spinnerEndSection.setSelection(c.endSection - 1)
            binding.spinnerStartWeek.setSelection(c.startWeek - 1)
            binding.spinnerEndWeek.setSelection(c.endWeek - 1)
        } else {
            // 新建默认值
            binding.spinnerEndSection.setSelection(1)
            binding.spinnerEndWeek.setSelection(15) // 第16周
        }

        // 选中星期
        (binding.chipDay.getChildAt(selectedDay - 1) as? Chip)?.isChecked = true
        // 选中周类型
        (binding.chipWeekType.getChildAt(selectedWeekType) as? Chip)?.isChecked = true
        // 选中颜色
        selectColor(selectedColor)
    }

    private fun save() {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.name_required, Toast.LENGTH_SHORT).show()
            return
        }
        val startSection = binding.spinnerStartSection.selectedItemPosition + 1
        val endSection = binding.spinnerEndSection.selectedItemPosition + 1
        val startWeek = binding.spinnerStartWeek.selectedItemPosition + 1
        val endWeek = binding.spinnerEndWeek.selectedItemPosition + 1

        if (endSection < startSection) {
            Toast.makeText(this, R.string.section_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        if (endWeek < startWeek) {
            Toast.makeText(this, R.string.week_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        val teacher = binding.etTeacher.text?.toString()?.trim().orEmpty()
        val classroom = binding.etClassroom.text?.toString()?.trim().orEmpty()

        // 若编辑时改动了起止周，清空 excludedWeeks，避免残留与区间矛盾的排除周数据；
        // 未改动起止周则保留已有的 excludedWeeks（如 OCR 导入的非连续周次）。
        val editing = editingCourse
        val keepExcluded = editing != null && editing.startWeek == startWeek && editing.endWeek == endWeek
        val excludedWeeks = if (keepExcluded) editing!!.excludedWeeks else emptySet()

        // 编辑时保留原「课程代码 / 时钟时间」，避免 OCR 导入的附加信息被手动编辑清空
        val newCourse = Course(
            id = editingCourse?.id ?: UniqueId.next(),
            name = name,
            teacher = teacher,
            classroom = classroom,
            dayOfWeek = selectedDay,
            startSection = startSection,
            endSection = endSection,
            startWeek = startWeek,
            endWeek = endWeek,
            weekType = selectedWeekType,
            colorIndex = selectedColor,
            excludedWeeks = excludedWeeks,
            code = editingCourse?.code.orEmpty(),
            startTime = editingCourse?.startTime.orEmpty(),
            endTime = editingCourse?.endTime.orEmpty()
        )

        val courses = CourseStore.load(this).toMutableList()
        val idx = courses.indexOfFirst { it.id == newCourse.id }
        if (idx >= 0) courses[idx] = newCourse else courses.add(newCourse)
        CourseStore.save(this, courses)
        // v1.44.0 桌面小组件：课程变了马上重画（今天的课可能就包含这一门）
        WidgetRefresh.refresh(this)
        finish()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
