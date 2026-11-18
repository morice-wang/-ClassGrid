package com.classgrid.app

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.classgrid.app.databinding.ActivitySchoolPickerBinding
import com.classgrid.app.databinding.ItemSchoolBinding
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 选择学校（v1.38.0 新增）。
 *
 * 为什么要有这一页：v1.37.0 点「链接抓取」只会打开广外教务系统，别的学校用户一进来就是
 * 一个不相干的登录页。现在先在这里选学校（内置 2868 所，覆盖 31 个省市区），选完再打开
 * 该校官网，用户自己登录教务系统后抓课表。
 *
 * 网址一定可能不对（名单里有 147 所没有官网，也有学校的教务系统换了域名），所以：
 *  - 每行右侧铅笔可以直接改这所学校要打开的网址，改完记住，下次点这所学校就是新网址；
 *  - 名单里没有的学校用右上角「+ 自定义」自己加。
 *
 * 结果：RESULT_OK + [EXTRA_SCHOOL_NAME] + [EXTRA_SCHOOL_URL]，调用方（导入页/抓取页）负责打开。
 */
class SchoolPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SCHOOL_NAME = "extra_school_name"
        const val EXTRA_SCHOOL_URL = "extra_school_url"

        fun intent(context: Context): Intent = Intent(context, SchoolPickerActivity::class.java)
    }

    private lateinit var binding: ActivitySchoolPickerBinding
    private val adapter = SchoolAdapter()
    private var schools: List<SchoolDirectory.School> = emptyList()
    private var query = ""
    private var province = ""

    private val dark: Boolean get() = ScheduleColor.isDarkMode(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySchoolPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.header) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top + dp(6))
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = bottom)
            insets
        }

        applyTheme()
        binding.btnBack.setOnClickListener { finish() }
        binding.searchBox.background = rounded(
            AppTheme.cardBackground(this, dark),
            AppTheme.line(this, dark),
            23
        )
        binding.tvRecent.background = rounded(
            AppTheme.cardBackground(this, dark),
            AppTheme.line(this, dark),
            12
        )
        binding.tvRecent.setTextColor(AppTheme.primary(this, dark))

        schools = SchoolDirectory.all(this)
        updateSubtitle()
        buildProvinceChips()

        binding.etSearch.doAfterTextChanged { editable ->
            query = editable?.toString()?.trim().orEmpty()
            binding.btnClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            applyFilter()
        }
        binding.btnClear.setOnClickListener {
            binding.etSearch.setText("")
            binding.etSearch.requestFocus()
        }
        binding.btnCustom.setOnClickListener { showCustomDialog() }
        binding.btnAddCustom.setOnClickListener { showCustomDialog() }

        binding.rvSchools.layoutManager = LinearLayoutManager(this)
        binding.rvSchools.adapter = adapter
        applyFilter()
    }

    private fun applyTheme() {
        binding.root.setBackgroundColor(AppTheme.windowBackground(this, dark))
        binding.tvTitle.setTextColor(AppTheme.text(this, dark))
        binding.tvSubtitle.setTextColor(AppTheme.textMuted(this, dark))
        binding.tvCount.setTextColor(AppTheme.textMuted(this, dark))
        binding.tvEmpty.setTextColor(AppTheme.textMuted(this, dark))
        binding.tvFooter.setTextColor(AppTheme.textMuted(this, dark))
        binding.btnCustom.setTextColor(AppTheme.primary(this, dark))
        binding.btnClear.setTextColor(AppTheme.textMuted(this, dark))
        binding.etSearch.setTextColor(AppTheme.text(this, dark))
        binding.etSearch.setHintTextColor(AppTheme.textMuted(this, dark))
        binding.ivSearch.setColorFilter(AppTheme.textMuted(this, dark))
    }

    private fun updateSubtitle() {
        val version = SchoolDirectory.dataVersion(this)
        binding.tvSubtitle.text = buildString {
            append(getString(R.string.school_picker_subtitle, schools.size))
            if (version.isNotEmpty()) append(" · 数据 ").append(version)
        }
    }

    private fun buildProvinceChips() {
        val group = binding.provinceGroup
        group.removeAllViews()
        group.addView(makeChip(getString(R.string.school_picker_all_provinces), ""))
        for (p in SchoolDirectory.provinces(this)) group.addView(makeChip(p, p))
        (group.getChildAt(0) as? Chip)?.isChecked = true
        group.setOnCheckedStateChangeListener { g: ChipGroup, checkedIds: List<Int> ->
            province = if (checkedIds.isEmpty()) {
                ""
            } else {
                (g.findViewById<Chip>(checkedIds.first())?.tag as? String).orEmpty()
            }
            applyFilter()
        }
    }

    private fun makeChip(label: String, value: String): Chip = Chip(this).apply {
        text = label
        tag = value
        isCheckable = true
        isClickable = true
        chipMinHeight = dp(32).toFloat()
        textSize = 13f
    }

    private fun applyFilter() {
        val result = SchoolDirectory.search(schools, query, province)
        adapter.submit(result)
        binding.tvCount.text = getString(R.string.school_picker_count, result.size)

        val empty = result.isEmpty()
        binding.emptyBox.visibility = if (empty) View.VISIBLE else View.GONE
        binding.rvSchools.visibility = if (empty) View.GONE else View.VISIBLE
        if (empty) {
            binding.tvEmpty.text = if (query.isEmpty() && province.isEmpty()) {
                getString(R.string.school_picker_empty)
            } else {
                getString(R.string.school_picker_no_result, query.ifEmpty { province })
            }
        }
        updateRecent()
    }

    /** 顶部「继续上次」：一进来就能回到上次那所学校，不用重新搜 */
    private fun updateRecent() {
        val last = SchoolDirectory.lastSchool(this)
        val show = last != null && query.isEmpty() && province.isEmpty()
        binding.tvRecent.visibility = if (show) View.VISIBLE else View.GONE
        if (!show || last == null) return
        binding.tvRecent.text = getString(R.string.school_picker_continue_last, last.name)
        binding.tvRecent.setOnClickListener { select(last) }
    }

    private fun select(school: SchoolDirectory.School) {
        // 优先打开教务系统入口；这所学校没有内置教务入口时退回官网
        val url = SchoolDirectory.entryOf(this, school)
        if (url.isBlank()) {
            showUrlDialog(school)
            return
        }
        SchoolDirectory.setLastSchool(this, school.name)
        setResult(
            RESULT_OK,
            Intent()
                .putExtra(EXTRA_SCHOOL_NAME, school.name)
                .putExtra(EXTRA_SCHOOL_URL, url)
        )
        finish()
    }

    private fun showUrlDialog(school: SchoolDirectory.School) {
        val current = SchoolDirectory.entryOf(this, school)
        val input = EditText(this).apply {
            setText(current)
            setSelection(text.length)
            hint = getString(R.string.web_import_url_hint)
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(TextView(context).apply {
                text = getString(R.string.school_picker_url_hint)
                textSize = 12f
                setTextColor(AppTheme.textMuted(this@SchoolPickerActivity, dark))
                setPadding(0, 0, 0, dp(6))
            })
            addView(input)
        }
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.school_picker_set_url, school.name))
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save) { _, _ -> saveUrl(school, input.text.toString()) }
        if (SchoolDirectory.hasSiteOverride(this, school)) {
            builder.setNeutralButton(R.string.school_picker_reset_url) { _, _ -> resetUrl(school) }
        }
        builder.show()
    }

    private fun saveUrl(school: SchoolDirectory.School, raw: String) {
        val url = SchoolDirectory.normalizeUrl(raw)
        if (url.isEmpty()) {
            toast(getString(R.string.web_import_url_invalid))
            return
        }
        SchoolDirectory.rememberSite(this, school, url)
        toast(getString(R.string.school_picker_url_saved, school.name))
        adapter.notifyDataSetChanged()
    }

    private fun resetUrl(school: SchoolDirectory.School) {
        SchoolDirectory.resetSite(this, school)
        toast(getString(R.string.school_picker_reset_done))
        adapter.notifyDataSetChanged()
    }

    private fun showCustomDialog() {
        val nameInput = EditText(this).apply {
            hint = getString(R.string.school_picker_custom_name)
            setSingleLine()
        }
        val siteInput = EditText(this).apply {
            hint = getString(R.string.school_picker_custom_site)
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(nameInput)
            addView(siteInput)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.school_picker_custom_title)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.school_picker_add) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.school_picker_custom_need_name))
                    return@setPositiveButton
                }
                if (!SchoolDirectory.addCustom(this, name, siteInput.text.toString())) {
                    toast(getString(R.string.school_picker_custom_duplicate, name))
                    return@setPositiveButton
                }
                schools = SchoolDirectory.all(this)
                updateSubtitle()
                applyFilter()
                toast(getString(R.string.school_picker_custom_added, name))
            }
            .show()
    }

    private fun showRowMenu(school: SchoolDirectory.School) {
        val labels = mutableListOf(getString(R.string.school_picker_edit_url))
        if (school.custom) labels.add(getString(R.string.school_picker_delete_custom))
        MaterialAlertDialogBuilder(this)
            .setTitle(school.name)
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == 0) showUrlDialog(school) else confirmRemoveCustom(school)
            }
            .show()
    }

    private fun confirmRemoveCustom(school: SchoolDirectory.School) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.school_picker_delete_title)
            .setMessage(getString(R.string.school_picker_delete_message, school.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                SchoolDirectory.removeCustom(this, school.name)
                schools = SchoolDirectory.all(this)
                updateSubtitle()
                applyFilter()
            }
            .show()
    }

    private inner class SchoolAdapter : RecyclerView.Adapter<SchoolAdapter.Holder>() {

        private val items = mutableListOf<SchoolDirectory.School>()

        fun submit(list: List<SchoolDirectory.School>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemSchoolBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        inner class Holder(private val b: ItemSchoolBinding) : RecyclerView.ViewHolder(b.root) {
            fun bind(school: SchoolDirectory.School) {
                b.tvName.text = school.name
                b.tvMeta.text = school.meta()
                val host = this@SchoolPickerActivity
                val entry = SchoolDirectory.entryOf(host, school)
                // 标出这一行点进去是「教务系统」还是「学校官网」，用户一眼能判断要不要点
                val isJw = SchoolDirectory.hasSiteOverride(host, school) ||
                    SchoolDirectory.hasBuiltinJw(school)
                b.tvSite.text = when {
                    entry.isBlank() -> getString(R.string.school_picker_no_site)
                    isJw -> getString(
                        R.string.school_picker_jw_prefix,
                        SchoolDirectory.displaySite(entry)
                    )
                    else -> getString(
                        R.string.school_picker_site_prefix,
                        SchoolDirectory.displaySite(entry)
                    )
                }
                b.tvSite.setTextColor(
                    if (entry.isBlank()) AppTheme.textMuted(this@SchoolPickerActivity, dark)
                    else AppTheme.primary(this@SchoolPickerActivity, dark)
                )
                b.btnEdit.setColorFilter(AppTheme.textMuted(this@SchoolPickerActivity, dark))
                b.ivChevron.setColorFilter(AppTheme.textMuted(this@SchoolPickerActivity, dark))

                b.root.setOnClickListener { select(school) }
                b.root.setOnLongClickListener {
                    showRowMenu(school)
                    true
                }
                b.btnEdit.setOnClickListener { showUrlDialog(school) }
            }
        }
    }

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
