package com.classgrid.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.classgrid.app.databinding.ActivityScheduleProfilesBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 作息库列表（v1.48.2）
 *
 * - 右上角 + 新建：空白作息，或从广东高校预设导入；
 * - 点左侧圆点或名字 = 把这份作息设为**当前课表**的作息（不同课表可以用不同作息，一份作息也能被多张课表共用）；
 * - 点右侧箭头 = 进去改节次时间；点 ⋯ / 长按 = 编辑、设为当前、重命名、复制、删除。
 */
class ScheduleProfilesActivity : AppCompatActivity() {

    companion object {
        fun intent(context: Context): Intent = Intent(context, ScheduleProfilesActivity::class.java)
    }

    private lateinit var binding: ActivityScheduleProfilesBinding
    private var dialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityScheduleProfilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.header) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAdd.setOnClickListener { showAddSheet() }
        render()
    }

    override fun onResume() {
        super.onResume()
        // 从编辑页回来可能改了名字/节次，重画一次
        render()
    }

    // ==================================================================
    // 列表
    // ==================================================================

    private fun render() {
        val dark = ScheduleColor.isDarkMode(this)
        val primary = AppTheme.primary(this, dark)
        val card = AppTheme.cardBackground(this, dark)
        val lineColor = AppTheme.line(this, dark)
        val textColor = AppTheme.text(this, dark)
        val muted = AppTheme.textMuted(this, dark)

        val list = ScheduleProfiles.list(this)
        val activeId = ScheduleProfiles.profileIdFor(this, TimetableStore.activeId(this))

        binding.listContainer.removeAllViews()
        list.forEach { profile ->
            val isActive = profile.id == activeId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(10), dp(4), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(card)
                    setStroke(dp(if (isActive) 2 else 1), if (isActive) primary else lineColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
                // 行本身不响应点击：圆点/名字切作息，右侧箭头进编辑，避免「想切换却进了编辑页」
                setOnLongClickListener {
                    showItemMenu(profile)
                    true
                }
            }

            row.addView(buildRadio(isActive, primary, lineColor) { bind(profile) })

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                setOnClickListener { bind(profile) }
            }

            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            nameRow.addView(TextView(this).apply {
                text = profile.name
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(if (isActive) primary else textColor)
                typeface = if (isActive) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            })
            if (isActive) nameRow.addView(tag("当前", primary, Color.WHITE))
            val preset = ScheduleProfiles.preset(profile.presetKey)
            if (preset != null) nameRow.addView(tag(preset.school, withAlpha(primary, 0x24), primary))
            col.addView(nameRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            col.addView(TextView(this).apply {
                text = ScheduleProfiles.summary(profile.sections)
                textSize = 11.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(muted)
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            // 右侧箭头：唯一进入「改节次时间」的入口
            val editZone = ImageView(this).apply {
                setImageResource(R.drawable.ic_chevron_right)
                imageTintList = android.content.res.ColorStateList.valueOf(muted)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(9), dp(9), dp(9), dp(9))
                isClickable = true
                isFocusable = true
                contentDescription = "改节次时间"
                setOnClickListener {
                    startActivity(SectionTimeActivity.intent(this@ScheduleProfilesActivity, profile.id))
                }
            }
            row.addView(editZone, LinearLayout.LayoutParams(dp(36), dp(36)))

            val more = ImageView(this).apply {
                setImageResource(R.drawable.ic_more_vert)
                imageTintList = android.content.res.ColorStateList.valueOf(muted)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(8), dp(8), dp(8))
                isClickable = true
                isFocusable = true
            }
            more.setOnClickListener { showItemMenu(profile) }
            row.addView(more, LinearLayout.LayoutParams(dp(38), dp(38)))

            binding.listContainer.addView(row)
        }

        addPresetCatalog(list, primary, card, lineColor, textColor, muted)
    }

    /**
     * 预设目录：把内置高校作息直接摆在列表下方，点一下导入成一份独立副本。
     *
     * 这样「作息库」不只是自己攒的几份，还能一眼看到能用的学校作息；
     * 已导入过的预设标一个「已导入」小标签，避免重复点。
     */
    private fun addPresetCatalog(
        existing: List<ScheduleProfiles.Profile>,
        primary: Int,
        card: Int,
        lineColor: Int,
        textColor: Int,
        muted: Int
    ) {
        binding.listContainer.addView(TextView(this).apply {
            text = "广东高校预设"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(muted)
            setPadding(dp(6), dp(16), dp(6), dp(2))
        })
        binding.listContainer.addView(TextView(this).apply {
            text = "点一下导入成一份独立作息，之后改名、改时间都不影响预设本身。"
            textSize = 11f
            setTextColor(muted)
            setPadding(dp(6), 0, dp(6), dp(8))
        })

        ScheduleProfiles.presets().forEach { preset ->
            val imported = existing.count { it.presetKey == preset.key }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(12), dp(10))
                isClickable = true
                isFocusable = true
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(card)
                    setStroke(dp(1), lineColor)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                setOnClickListener {
                    val created = ScheduleProfiles.createFromPreset(this@ScheduleProfilesActivity, preset.key)
                    if (created != null) openEditor(created)
                    else Toast.makeText(this@ScheduleProfilesActivity, "预设数据缺失", Toast.LENGTH_SHORT).show()
                }
            }

            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val nameRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            nameRow.addView(TextView(this).apply {
                text = preset.name
                textSize = 14.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(textColor)
            })
            if (imported > 0) nameRow.addView(tag(if (imported > 1) "已导入 $imported" else "已导入", withAlpha(primary, 0x24), primary))
            col.addView(nameRow)
            col.addView(TextView(this).apply {
                text = "${preset.school} · ${ScheduleProfiles.summary(preset.sections)}"
                textSize = 11.5f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(muted)
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(TextView(this).apply {
                text = "导入"
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(primary)
                setPadding(dp(10), dp(4), dp(4), dp(4))
            })
            binding.listContainer.addView(row)
        }
    }

    /** 左侧单选圆点：实心=当前课表在用，空心=没在用 */
    private fun buildRadio(checked: Boolean, primary: Int, lineColor: Int, onClick: () -> Unit): View {
        val box = FrameLayout(this).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (checked) {
                    setColor(primary)
                } else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(2), lineColor)
                }
            }
            layoutParams = FrameLayout.LayoutParams(dp(19), dp(19), Gravity.CENTER)
        }
        box.addView(dot)
        box.layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
        return box
    }

    private fun tag(text: String, bg: Int, fg: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 9.5f
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(fg)
        gravity = Gravity.CENTER
        setPadding(dp(5), dp(1), dp(5), dp(1))
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(bg)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { marginStart = dp(6) }
    }

    /** 新建的作息直接设成当前课表在用的，再去编辑页微调 */
    private fun openEditor(profile: ScheduleProfiles.Profile) {
        ScheduleProfiles.bindToTimetable(this, TimetableStore.activeId(this), profile.id)
        render()
        startActivity(SectionTimeActivity.intent(this, profile.id))
    }

    private fun bind(profile: ScheduleProfiles.Profile) {
        val meta = TimetableStore.activeMeta(this)
        ScheduleProfiles.bindToTimetable(this, meta.id, profile.id)
        render()
        Toast.makeText(this, "「${meta.name}」已改用「${profile.name}」", Toast.LENGTH_SHORT).show()
    }

    // ==================================================================
    // 新建 / 菜单
    // ==================================================================

    private fun showAddSheet() {
        val dark = ScheduleColor.isDarkMode(this)
        val textColor = AppTheme.text(this, dark)
        val muted = AppTheme.textMuted(this, dark)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }

        box.addView(optionRow(
            "空白作息", "12 节，08:30 开始，40 分钟一节", textColor, muted
        ) {
            val created = ScheduleProfiles.create(
                this,
                "新作息",
                ScheduleProfiles.derive("08:30", 12, ScheduleProfiles.DEFAULT_DURATION, ScheduleProfiles.DEFAULT_BREAK)
            )
            dialog?.dismiss()
            openEditor(created)
        })

        box.addView(TextView(this).apply {
            text = "从预设导入"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(muted)
            setPadding(dp(12), dp(14), dp(12), dp(4))
        })

        ScheduleProfiles.presets().forEach { preset ->
            box.addView(optionRow(
                preset.name,
                "${preset.school} · ${preset.sections.size} 节",
                textColor, muted
            ) {
                val created = ScheduleProfiles.createFromPreset(this, preset.key)
                dialog?.dismiss()
                if (created != null) {
                    openEditor(created)
                } else {
                    Toast.makeText(this, "预设数据缺失", Toast.LENGTH_SHORT).show()
                }
            })
        }

        val scroll = android.widget.ScrollView(this).apply {
            addView(box)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.62f).toInt()
            )
        }

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("新建作息")
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog?.show()
    }

    private fun optionRow(
        title: String,
        subtitle: String,
        textColor: Int,
        muted: Int,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(textColor)
        })
        row.addView(TextView(this).apply {
            text = subtitle
            textSize = 11.5f
            setTextColor(muted)
            setPadding(0, dp(2), 0, 0)
        })
        return row
    }

    private fun showItemMenu(profile: ScheduleProfiles.Profile) {
        val items = arrayOf("改节次时间", "设为当前课表的作息", "重命名", "复制一份", "删除")
        MaterialAlertDialogBuilder(this)
            .setTitle(profile.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(SectionTimeActivity.intent(this, profile.id))
                    1 -> bind(profile)
                    2 -> showRename(profile)
                    3 -> {
                        val copy = ScheduleProfiles.create(this, "${profile.name} 副本", profile.sections, profile.presetKey)
                        render()
                        Toast.makeText(this, "已复制为「${copy.name}」", Toast.LENGTH_SHORT).show()
                    }
                    else -> confirmDelete(profile)
                }
            }
            .show()
    }

    private fun showRename(profile: ScheduleProfiles.Profile) {
        val input = EditText(this).apply {
            setText(profile.name)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(24), dp(6), dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("重命名作息")
            .setView(wrap)
            .setPositiveButton(R.string.confirm) { _, _ ->
                if (ScheduleProfiles.rename(this, profile.id, input.text.toString())) {
                    render()
                } else {
                    Toast.makeText(this, "名字不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(profile: ScheduleProfiles.Profile) {
        if (ScheduleProfiles.list(this).size <= 1) {
            Toast.makeText(this, "至少要保留一份作息", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("删除「${profile.name}」？")
            .setMessage("用到这份作息的课表会改回其他作息，课程本身不会丢。")
            .setPositiveButton("删除") { _, _ ->
                if (ScheduleProfiles.delete(this, profile.id)) {
                    render()
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "至少要保留一份作息", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
