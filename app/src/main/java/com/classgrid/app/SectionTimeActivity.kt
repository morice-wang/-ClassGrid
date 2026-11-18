package com.classgrid.app

import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.classgrid.app.databinding.ActivitySectionTimeBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 作息编辑页（v1.48.0 重做）
 *
 * 三段式：
 *  1. 节次数量：−/＋ 步进 + 10/12/14/15/16 快捷，改数量不丢已填时间（多则按节奏补齐，少则砍末尾）；
 *  2. 批量推算：填「第 1 节开始 / 每节时长 / 课间间隔」，一键铺满；
 *  3. 可编辑表格：节次｜开始｜结束｜间隔。开始格里直接点数字键输 0830 会自动变 08:30，
 *     改开始会按该节时长顺延结束，开了「自动顺延」还会推动后面各节；长按时间格开时间选择器。
 *
 * 所有时间改动都会即时写回 sections，保存时整份写进作息库。
 */
class SectionTimeActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_PROFILE_ID = "profile_id"
        private const val DAY_MINUTES = 24 * 60

        fun intent(context: Context, profileId: String): Intent =
            Intent(context, SectionTimeActivity::class.java).putExtra(EXTRA_PROFILE_ID, profileId)

        /** 兼容旧入口：不带 id 时编辑当前课表的作息 */
        fun intent(context: Context): Intent = Intent(context, SectionTimeActivity::class.java)
    }

    private lateinit var binding: ActivitySectionTimeBinding
    private lateinit var profile: ScheduleProfiles.Profile

    private val sections = mutableListOf<SectionTimes.Section>()
    private val startFields = mutableListOf<TimeField>()
    private val endFields = mutableListOf<TimeField>()
    private val gapLabels = mutableListOf<TextView>()

    private var countLabel: TextView? = null
    private var batchFirst = "08:30"
    /** 批量卡片里的「第 1 节开始」，表格改了第 1 节时同步过来 */
    private var batchFirstField: TimeField? = null
    private var batchDuration = ScheduleProfiles.DEFAULT_DURATION
    private var batchBreak = ScheduleProfiles.DEFAULT_BREAK
    private var autoCascade = true
    private var suppress = false

    private val dark: Boolean get() = ScheduleColor.isDarkMode(this)
    private val primary: Int get() = AppTheme.primary(this, dark)
    private val textColor: Int get() = AppTheme.text(this, dark)
    private val muted: Int get() = AppTheme.textMuted(this, dark)
    private val lineColor: Int get() = AppTheme.line(this, dark)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySectionTimeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_PROFILE_ID)
            ?: ScheduleProfiles.profileIdFor(this, TimetableStore.activeId(this))
        profile = ScheduleProfiles.find(this, id)
            ?: ScheduleProfiles.profileFor(this, TimetableStore.activeId(this))

        sections.clear()
        sections.addAll(profile.sections)
        batchFirst = sections.firstOrNull()?.start ?: "08:30"
        batchDuration = sections.firstOrNull()?.let { ScheduleProfiles.duration(it) } ?: ScheduleProfiles.DEFAULT_DURATION
        batchBreak = if (sections.size >= 2) {
            ((SectionTimes.toMinutes(sections[1].start) ?: 0) - (SectionTimes.toMinutes(sections[0].end) ?: 0))
                .coerceIn(0, 120)
        } else {
            ScheduleProfiles.DEFAULT_BREAK
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.header) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }

        binding.tvTitle.text = profile.name
        binding.btnBack.setOnClickListener { confirmExit() }
        binding.btnRename.setOnClickListener { showRename() }
        binding.btnReset.setOnClickListener { confirmReset() }
        binding.btnSave.setOnClickListener { save() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmExit()
        })

        buildContent()
    }

    // ==================================================================
    // 页面结构
    // ==================================================================

    private fun buildContent() {
        binding.content.removeAllViews()
        startFields.clear()
        endFields.clear()
        gapLabels.clear()

        binding.content.addView(buildCountCard())
        binding.content.addView(buildBatchCard())
        binding.content.addView(buildTableCard())
        updateSubtitle()
    }

    /** 1. 节次数量 */
    private fun buildCountCard(): View {
        val card = card()
        card.addView(rowTitle("节次数量", "一节就够用也行"))

        val stepper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        stepper.addView(stepButton("−") { applyCount(sections.size - 1) })
        countLabel = TextView(this).apply {
            text = "${sections.size}"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            gravity = Gravity.CENTER
        }
        stepper.addView(countLabel, LinearLayout.LayoutParams(0, dp(40), 1f))
        stepper.addView(stepButton("＋") { applyCount(sections.size + 1) })
        card.addView(stepper)

        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        listOf(10, 12, 14, 15, 16).forEach { n ->
            chips.addView(chip("${n} 节", n == sections.size) { applyCount(n) })
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        card.addView(scroll)

        card.addView(hint("改数量会尽量保住已填的时间：变多按最后一节的节奏补齐，变少从末尾删。"))
        return card
    }

    /** 2. 批量推算 */
    private fun buildBatchCard(): View {
        val card = card()
        card.addView(rowTitle("按开始时间批量推算", null))

        val first = TimeField(batchFirst).apply {
            onValue = { v ->
                if (SectionTimes.toMinutes(v) != null) batchFirst = v
            }
        }
        batchFirstField = first
        card.addView(fieldRow("第 1 节开始", first, null))

        val dur = numberField(batchDuration) { batchDuration = it }
        card.addView(fieldRow("每节时长", dur, "分钟"))

        val brk = numberField(batchBreak) { batchBreak = it }
        card.addView(fieldRow("课间间隔", brk, "分钟"))

        val apply = MaterialButton(this).apply {
            text = "重新铺满全部节次"
            textSize = 13f
            cornerRadius = dp(12)
            setOnClickListener {
                if (!refreshBatch()) return@setOnClickListener
                val made = ScheduleProfiles.derive(batchFirst, sections.size, batchDuration, batchBreak)
                sections.clear()
                sections.addAll(made)
                buildContent()
                Toast.makeText(this@SectionTimeActivity, "已按 ${batchFirst} 铺满 ${sections.size} 节", Toast.LENGTH_SHORT).show()
            }
        }
        card.addView(apply, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44)
        ).apply { topMargin = dp(10) })

        card.addView(hint("点一下按这个节奏重排整张表（中途的大课间会被抹平）；只想改某一节，去下面的表格里改。"))
        return card
    }

    /** 3. 可编辑表格 */
    private fun buildTableCard(): View {
        val card = card()
        card.addView(rowTitle("逐节时间", "点格子直接输数字"))

        // 自动顺延放在表格上方：它作用于"改表格里的某一节"，不属于批量推算
        card.addView(SwitchCompat(this).apply {
            text = "改开始时间时，后面各节自动顺延"
            textSize = 12.5f
            isChecked = autoCascade
            setTextColor(textColor)
            setPadding(0, dp(6), 0, dp(2))
            setOnCheckedChangeListener { _, checked -> autoCascade = checked }
        })

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        head.addView(headCell("节次", 34, false))
        head.addView(headCell("开始", 0, true))
        head.addView(headCell("结束", 0, true))
        head.addView(headCell("间隔", 44, false))
        card.addView(head)

        for (i in sections.indices) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(3), 0, dp(3))
            }

            row.addView(TextView(this).apply {
                text = "${i + 1}"
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTextColor(muted)
            }, LinearLayout.LayoutParams(dp(34), dp(40)))

            val start = TimeField(sections[i].start).apply {
                onValue = { v -> onStartEdited(i, v) }
            }
            startFields.add(start)
            row.addView(start, LinearLayout.LayoutParams(0, dp(40), 1f))

            row.addView(TextView(this).apply {
                text = "–"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(muted)
            }, LinearLayout.LayoutParams(dp(16), dp(40)))

            val end = TimeField(sections[i].end).apply {
                onValue = { v -> onEndEdited(i, v) }
            }
            endFields.add(end)
            row.addView(end, LinearLayout.LayoutParams(0, dp(40), 1f))

            val gap = TextView(this).apply {
                text = gapText(i)
                textSize = 10.5f
                gravity = Gravity.CENTER
                setTextColor(muted)
            }
            gapLabels.add(gap)
            row.addView(gap, LinearLayout.LayoutParams(dp(44), dp(40)))

            card.addView(row)
        }

        card.addView(hint("开始/结束都能改：改开始会保住这一节的时长，改结束会保住开始；开了「自动顺延」时后面各节跟着挪。长按可开时间选择器。"))
        return card
    }

    // ==================================================================
    // 编辑逻辑
    // ==================================================================

    private fun onStartEdited(index: Int, value: String) {
        if (suppress) return
        val newStart = SectionTimes.toMinutes(value) ?: return
        val oldStart = SectionTimes.toMinutes(sections[index].start) ?: newStart
        val oldEnd = SectionTimes.toMinutes(sections[index].end) ?: (oldStart + batchDuration)
        val span = (oldEnd - oldStart).coerceIn(1, DAY_MINUTES)

        sections[index] = SectionTimes.Section(
            ScheduleProfiles.hhmm(newStart),
            ScheduleProfiles.hhmm((newStart + span).coerceAtMost(DAY_MINUTES))
        )
        if (autoCascade) shiftAfter(index, newStart - oldStart)
        refreshTable(skip = index)
    }

    private fun onEndEdited(index: Int, value: String) {
        if (suppress) return
        val newEnd = SectionTimes.toMinutes(value) ?: return
        val start = SectionTimes.toMinutes(sections[index].start) ?: return
        if (newEnd <= start) return
        val oldEnd = SectionTimes.toMinutes(sections[index].end) ?: newEnd

        sections[index] = SectionTimes.Section(ScheduleProfiles.hhmm(start), ScheduleProfiles.hhmm(newEnd))
        if (autoCascade) shiftAfter(index, newEnd - oldEnd)
        refreshTable(skip = index)
    }

    /** 把 index 之后各节整体平移 delta 分钟（保持各节时长与间隔不变） */
    private fun shiftAfter(index: Int, delta: Int) {
        if (delta == 0) return
        for (j in index + 1 until sections.size) {
            val s = SectionTimes.toMinutes(sections[j].start) ?: continue
            val e = SectionTimes.toMinutes(sections[j].end) ?: continue
            val span = (e - s).coerceIn(1, DAY_MINUTES)
            val ns = (s + delta).coerceIn(0, DAY_MINUTES - 1)
            val ne = (ns + span).coerceAtMost(DAY_MINUTES)
            sections[j] = SectionTimes.Section(ScheduleProfiles.hhmm(ns), ScheduleProfiles.hhmm(ne))
        }
    }

    /** 重画表格里的文字，skip 这一行的开始格不动（用户正在里面打字） */
    private fun refreshTable(skip: Int = -1) {
        suppress = true
        for (i in sections.indices) {
            if (i != skip && i < startFields.size) startFields[i].setQuiet(sections[i].start)
            if (i < endFields.size) endFields[i].setQuiet(sections[i].end)
            if (i < gapLabels.size) gapLabels[i].text = gapText(i)
        }
        suppress = false
        // 表格第 1 节改过之后，批量卡片里的起点跟着走，避免两处显示不一致
        sections.firstOrNull()?.let {
            batchFirst = it.start
            batchFirstField?.setQuiet(it.start)
        }
        updateSubtitle()
    }

    private fun gapText(i: Int): String =
        if (i == 0) "首节"
        else ScheduleProfiles.gapLabel(sections[i - 1].end, sections[i].start)

    private fun applyCount(target: Int) {
        val n = target.coerceIn(ScheduleProfiles.MIN_SECTIONS, ScheduleProfiles.MAX_SECTIONS)
        if (n == sections.size) return
        val resized = ScheduleProfiles.resize(sections.toList(), n, batchDuration, batchBreak)
        sections.clear()
        sections.addAll(resized)
        buildContent()
    }

    private fun refreshBatch(): Boolean {
        if (SectionTimes.toMinutes(batchFirst) == null) {
            Toast.makeText(this, "第 1 节开始时间格式不对（HH:MM）", Toast.LENGTH_SHORT).show()
            return false
        }
        batchDuration = batchDuration.coerceIn(5, 180)
        batchBreak = batchBreak.coerceIn(0, 120)
        return true
    }

    private fun updateSubtitle() {
        val first = sections.firstOrNull()?.start
        val last = sections.lastOrNull()?.end
        binding.tvSubtitle.text = if (first != null && last != null) {
            "${sections.size} 节 · ${first}–${last}"
        } else {
            "${sections.size} 节"
        }
    }

    // ==================================================================
    // 动作
    // ==================================================================

    private fun save() {
        val err = ScheduleProfiles.validate(sections)
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
            return
        }
        val ok = ScheduleProfiles.update(this, profile.copy(sections = sections.toList()))
        if (!ok) {
            Toast.makeText(this, "保存失败，名字可能不合法", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "已保存「${profile.name}」", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun confirmExit() {
        if (sections == profile.sections) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("还没保存")
            .setMessage("这次的节次时间改动要保存吗？")
            .setPositiveButton(R.string.save) { _, _ -> save() }
            .setNegativeButton("放弃") { _, _ -> finish() }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun showRename() {
        val input = EditText(this).apply {
            setText(profile.name)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
            filters = arrayOf(InputFilter.LengthFilter(ScheduleProfiles.MAX_NAME_LEN))
        }
        val wrap = android.widget.FrameLayout(this).apply {
            setPadding(dp(24), dp(6), dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("作息名称")
            .setView(wrap)
            .setPositiveButton(R.string.confirm) { _, _ ->
                if (ScheduleProfiles.rename(this, profile.id, input.text.toString())) {
                    profile = ScheduleProfiles.find(this, profile.id) ?: profile
                    binding.tvTitle.text = profile.name
                } else {
                    Toast.makeText(this, "名字不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmReset() {
        val preset = ScheduleProfiles.preset(profile.presetKey)
        val target = preset?.sections ?: ScheduleProfiles.gdufsSections()
        val label = preset?.name ?: "广外作息"
        MaterialAlertDialogBuilder(this)
            .setTitle("恢复为「${label}」？")
            .setMessage("会把表格里的开始/结束时间改回 ${target.size} 节的初始设置，保存后才生效。")
            .setPositiveButton("恢复") { _, _ ->
                sections.clear()
                sections.addAll(target)
                batchFirst = target.first().start
                batchDuration = ScheduleProfiles.duration(target.first()) ?: ScheduleProfiles.DEFAULT_DURATION
                buildContent()
                Toast.makeText(this, "已恢复，记得点保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ==================================================================
    // 小组件
    // ==================================================================

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(AppTheme.cardBackground(this@SectionTimeActivity, dark))
            setStroke(dp(1), lineColor)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) }
    }

    private fun rowTitle(title: String, note: String?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (note != null) {
            row.addView(TextView(this).apply {
                text = note
                textSize = 10.5f
                setTextColor(muted)
            })
        }
        return row
    }

    private fun hint(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 10.5f
        setTextColor(muted)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(8), 0, 0)
    }

    private fun stepButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(primary)
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(withAlpha(primary, 0x1A))
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(40))
    }

    private fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 12f
        gravity = Gravity.CENTER
        setTextColor(if (selected) 0xFFFFFFFF.toInt() else textColor)
        isClickable = true
        isFocusable = true
        setPadding(dp(13), dp(7), dp(13), dp(7))
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            if (selected) setColor(primary) else {
                setColor(withAlpha(primary, 0x10))
                setStroke(dp(1), withAlpha(primary, 0x33))
            }
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }
    }

    private fun headCell(label: String, widthDp: Int, weight: Boolean): TextView = TextView(this).apply {
        text = label
        textSize = 10.5f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(muted)
        gravity = if (weight) Gravity.CENTER else Gravity.CENTER
        layoutParams = if (weight) {
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        } else {
            LinearLayout.LayoutParams(dp(widthDp), LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun fieldRow(label: String, field: View, suffix: String?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, 0)
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 12.5f
            setTextColor(textColor)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (field is TimeField) {
            row.addView(field, LinearLayout.LayoutParams(dp(96), dp(40)))
        } else {
            row.addView(field, LinearLayout.LayoutParams(dp(64), dp(40)))
        }
        if (suffix != null) {
            row.addView(TextView(this).apply {
                text = suffix
                textSize = 11.5f
                setTextColor(muted)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        return row
    }

    private fun numberField(value: Int, onValue: (Int) -> Unit): EditText = EditText(this).apply {
        setText(value.toString())
        textSize = 15f
        typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(3))
        isSingleLine = true
        setPadding(0, 0, 0, 0)
        setTextColor(textColor)
        background = fieldBackground()
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val n = s?.toString()?.toIntOrNull() ?: return
                onValue(n)
            }
        })
    }

    private fun fieldBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(withAlpha(primary, if (dark) 0x1F else 0x0D))
        setStroke(dp(1), lineColor)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    // ==================================================================
    // 时间输入格
    // ==================================================================

    private inner class TimeField(initial: String) : AppCompatEditText(this@SectionTimeActivity) {

        var onValue: ((String) -> Unit)? = null
        private var quiet = false

        init {
            setText(initial)
            setSelection(text?.length ?: 0)
            textSize = 15f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(5))
            isSingleLine = true
            setPadding(0, 0, 0, 0)
            setTextColor(textColor)
            background = fieldBackground()

            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (quiet) return
                    val raw = s?.toString() ?: ""
                    val formatted = formatTime(raw)
                    if (formatted != raw) setQuiet(formatted)
                    onValue?.invoke(formatted)
                }
            })

            setOnLongClickListener {
                showPicker()
                true
            }
        }

        fun setQuiet(value: String) {
            if (text.toString() == value) return
            quiet = true
            setText(value)
            setSelection(value.length)
            quiet = false
        }

        private fun formatTime(raw: String): String {
            val minutes = SectionTimes.toMinutes(raw)
            if (minutes != null) return ScheduleProfiles.hhmm(minutes)
            val digits = raw.filter { it.isDigit() }.take(4)
            return if (digits.length <= 2) digits else digits.substring(0, 2) + ":" + digits.substring(2)
        }

        private fun showPicker() {
            val cur = SectionTimes.toMinutes(text.toString()) ?: (8 * 60)
            TimePickerDialog(
                this@SectionTimeActivity,
                { _, hour, minute ->
                    val v = ScheduleProfiles.hhmm(hour * 60 + minute)
                    setQuiet(v)
                    onValue?.invoke(v)
                },
                cur / 60,
                cur % 60,
                true
            ).show()
        }
    }
}
