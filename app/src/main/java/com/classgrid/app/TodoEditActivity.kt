package com.classgrid.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.classgrid.app.databinding.ActivityTodoEditBinding
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Calendar

/**
 * 添加 / 编辑日程。
 *
 * v1.47.12：界面与课程编辑器保持一致（名称、星期、时间段、起止周、单双周、颜色、地点、备注），
 * 不同之处只有：节次换成了具体时间段、没有教师与教室字段。
 * 起止周相同且「每周」时视为单次日程，只在对应那一天出现。
 */
class TodoEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTodoEditBinding
    private var editingTodo: Todo? = null
    private var selectedDay = 1
    private var selectedWeekType = Course.WEEK_EVERY
    private var selectedColor = 0
    private val colorViews = mutableListOf<View>()
    private var semesterStart = 0L
    private var totalWeeks = 20

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityTodoEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.header) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }

        semesterStart = Prefs.semesterStart(this)
        totalWeeks = Prefs.totalWeeks(this).coerceIn(1, 40)

        val id = intent.getLongExtra(EXTRA_TODO_ID, -1L)
        if (id > 0) {
            editingTodo = CourseStore.loadTodos(this).find { it.id == id }
        }

        setupSpinners()
        setupColorPalette()
        setupListeners()
        populate()
        updateTimeButtons()
    }

    private fun setupSpinners() {
        val weeks = (1..40).map { "第 $it 周" }
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
            if (i == index) gd.setStroke(dp(3), pal.accent) else gd.setStroke(dp(2), pal.bg)
        }
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnSave.setOnClickListener { save() }

        binding.btnDelete.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete)
                .setMessage(R.string.event_delete_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    val id = editingTodo?.id
                    if (id != null) {
                        CourseStore.saveTodos(this, CourseStore.loadTodos(this).filter { it.id != id })
                    }
                    finish()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.btnStart.setOnClickListener { pickTime(isStart = true) }
        binding.btnEnd.setOnClickListener { pickTime(isStart = false) }
        // 长按开始时间：清空为全天
        binding.btnStart.setOnLongClickListener {
            startTime = ""
            endTime = ""
            updateTimeButtons()
            Toast.makeText(this, R.string.event_time_cleared, Toast.LENGTH_SHORT).show()
            true
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

    private var startTime: String = ""
    private var endTime: String = ""

    private fun populate() {
        val t = editingTodo
        if (t != null) {
            binding.tvTitle.setText(R.string.event_edit_title)
            binding.btnDelete.visibility = View.VISIBLE
            binding.etName.setText(t.title)
            binding.etLocation.setText(t.location)
            binding.etNote.setText(t.note)
            selectedDay = Todo.dayOfWeek(t.dateMillis).coerceIn(1, 7)
            selectedWeekType = t.weekType
            selectedColor = t.colorIndex
            startTime = t.time
            endTime = t.endTime
            val start = t.effectiveStartWeek()
            val end = t.effectiveEndWeek(totalWeeks)
            binding.spinnerStartWeek.setSelection((start - 1).coerceIn(0, 39))
            binding.spinnerEndWeek.setSelection((end - 1).coerceIn(0, 39))
        } else {
            val date = intent.getLongExtra(EXTRA_DATE, 0L)
            val dayMillis = if (date > 0L) startOfDay(date) else startOfDay(System.currentTimeMillis())
            selectedDay = Todo.dayOfWeek(dayMillis)
            val week = if (semesterStart > 0L) {
                Todo.weekIndexOf(dayMillis, semesterStart).coerceIn(1, 40)
            } else 1
            binding.spinnerStartWeek.setSelection(week - 1)
            binding.spinnerEndWeek.setSelection(week - 1)
        }

        (binding.chipDay.getChildAt(selectedDay - 1) as? Chip)?.isChecked = true
        (binding.chipWeekType.getChildAt(selectedWeekType.coerceIn(0, 2)) as? Chip)?.isChecked = true
        selectColor(selectedColor)
    }

    private fun updateTimeButtons() {
        binding.btnStart.text = startTime.ifBlank { getString(R.string.event_all_day) }
        binding.btnEnd.text = endTime.ifBlank { getString(R.string.event_end_empty) }
        binding.tvTimeHint.setText(
            when {
                startTime.isBlank() -> R.string.event_hint_no_time
                endTime.isBlank() -> R.string.event_hint_point
                else -> R.string.event_hint_range
            }
        )
    }

    private fun pickTime(isStart: Boolean) {
        val current = Todo.parseMinutes(if (isStart) startTime else endTime)
        val cal = Calendar.getInstance()
        if (current != null) {
            cal.set(Calendar.HOUR_OF_DAY, current / 60)
            cal.set(Calendar.MINUTE, current % 60)
        } else {
            cal.set(Calendar.HOUR_OF_DAY, if (isStart) 9 else 10)
            cal.set(Calendar.MINUTE, 0)
        }
        TimePickerDialog(
            this,
            { _, hour, minute ->
                val value = Todo.formatMinutes(hour * 60 + minute)
                if (isStart) {
                    startTime = value
                    // 开始晚于结束（或没有结束）时，自动补一小时
                    val s = Todo.parseMinutes(startTime)
                    val e = Todo.parseMinutes(endTime)
                    if (s != null && (e == null || e <= s)) endTime = Todo.formatMinutes(s + 60)
                } else {
                    endTime = value
                }
                updateTimeButtons()
            },
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun save() {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.event_need_title, Toast.LENGTH_SHORT).show()
            return
        }
        val startWeek = binding.spinnerStartWeek.selectedItemPosition + 1
        val endWeek = binding.spinnerEndWeek.selectedItemPosition + 1
        if (endWeek < startWeek) {
            Toast.makeText(this, R.string.week_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        if (startTime.isNotBlank() && endTime.isNotBlank()) {
            val s = Todo.parseMinutes(startTime)
            val e = Todo.parseMinutes(endTime)
            if (s != null && e != null && e <= s) {
                Toast.makeText(this, R.string.event_end_before_start, Toast.LENGTH_SHORT).show()
                return
            }
        }

        val weekly = !(startWeek == endWeek && selectedWeekType == Course.WEEK_EVERY)
        val startMillis = if (semesterStart > 0L) {
            Todo.millisOf(semesterStart, startWeek, selectedDay)
        } else {
            startOfDay(System.currentTimeMillis())
        }

        val todo = Todo(
            id = editingTodo?.id ?: UniqueId.next(),
            title = name,
            dateMillis = startMillis,
            time = startTime,
            endTime = endTime,
            location = binding.etLocation.text?.toString()?.trim().orEmpty(),
            note = binding.etNote.text?.toString()?.trim().orEmpty(),
            colorIndex = selectedColor,
            repeatWeekly = weekly,
            startWeek = startWeek,
            endWeek = endWeek,
            weekType = selectedWeekType
        )

        val todos = CourseStore.loadTodos(this).toMutableList()
        val index = todos.indexOfFirst { it.id == todo.id }
        if (index >= 0) todos[index] = todo else todos.add(todo)
        CourseStore.saveTodos(this, todos)
        Toast.makeText(this, R.string.event_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun startOfDay(millis: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return c.timeInMillis
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_DATE = "extra_date"
        const val EXTRA_TODO_ID = "extra_todo_id"

        fun intent(context: Context, dateMillis: Long = 0L, todoId: Long = -1L): Intent =
            Intent(context, TodoEditActivity::class.java)
                .putExtra(EXTRA_DATE, dateMillis)
                .putExtra(EXTRA_TODO_ID, todoId)
    }
}
