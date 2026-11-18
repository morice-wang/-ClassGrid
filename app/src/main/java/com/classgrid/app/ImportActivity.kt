package com.classgrid.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import java.io.IOException
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.classgrid.app.databinding.ActivityImportBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

class ImportActivity : AppCompatActivity() {

    companion object {
        /** 从主界面「截图识别」进入时置 true：自动拉起选图，跳过重复的导入方式选择 */
        const val EXTRA_AUTO_PICK = "auto_pick"
    }

    private lateinit var binding: ActivityImportBinding

    /** 多选截图（最多 9 张），逐张识别后合并结果 */
    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(9)) { uris ->
            if (uris.isNotEmpty()) runOcrQueue(uris.map { it.toString() })
        }

    /**
     * 选学校（v1.38.0）：链接抓取先让用户选是哪所学校，再打开该校官网。
     * 不再像 v1.37.0 那样直接进广外教务系统 —— 全国 2868 所高校里只有极少数人上广外。
     */
    private val pickSchool =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val name = data.getStringExtra(SchoolPickerActivity.EXTRA_SCHOOL_NAME)
                ?: return@registerForActivityResult
            val url = data.getStringExtra(SchoolPickerActivity.EXTRA_SCHOOL_URL)
                ?: return@registerForActivityResult
            startActivity(
                Intent(this, WebImportActivity::class.java)
                    .putExtra(WebImportActivity.EXTRA_SCHOOL, name)
                    .putExtra(WebImportActivity.EXTRA_URL, url)
            )
        }

    /** Excel / CSV 导入（v1.42.0）：教务系统导出的整周课表文件，最常见的内测反馈需求。 */
    private val pickSheet =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) importSheet(uri)
        }

    /**
     * 主题化（v1.34.0）：导入页的卡片背景 / 图标底色 / 图标着色全部跟着主题走。
     * 布局里仍保留 ?attr/colorPrimary 作为兜底（XML 预览可见），运行时在这里覆盖成主题色，
     * 这样换主题不需要为每种主题各写一份 layout。
     */
    private fun applyThemeTint() {
        val dark = ScheduleColor.isDarkMode(this)
        val spec = AppTheme.current(this)
        val primary = AppTheme.primary(this, dark)
        val cardBg = if (dark) spec.cardDark else spec.cardLight
        val iconBg = bl(primary, if (dark) 0x33 else 0x1F)
        // MaterialCardView 内部只有一个子节点（LinearLayout），图标就在它下面
        val cards = listOf(binding.cardScreenshot, binding.cardLink, binding.cardJson, binding.cardExcel, binding.cardManual)
        for (card in cards) {
            card.setCardBackgroundColor(cardBg)
            card.strokeWidth = dp(1)
            card.strokeColor = if (dark) spec.lineDark else spec.lineLight
            val icon = ((card.getChildAt(0) as? android.view.ViewGroup)?.getChildAt(0)) as? android.widget.ImageView
            icon?.let {
                it.imageTintList = android.content.res.ColorStateList.valueOf(primary)
                it.backgroundTintList = android.content.res.ColorStateList.valueOf(iconBg)
            }
        }
        // 返回箭头 / 右箭头等次级图标也用主题次要文字色
        val muted = android.content.res.ColorStateList.valueOf(AppTheme.textMuted(this, dark))
        binding.btnBack.imageTintList = muted
    }

    private fun bl(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.header) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }

        applyThemeTint()
        binding.btnBack.setOnClickListener { finish() }
        binding.cardScreenshot.setOnClickListener {
            pickImages.launch(
                androidx.activity.result.PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    .build()
            )
        }
        binding.cardJson.setOnClickListener { showJsonImport() }
        binding.cardExcel.setOnClickListener {
            pickSheet.launch(
                arrayOf(
                    "text/csv",
                    "text/comma-separated-values",
                    "text/plain",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                )
            )
        }
        // 链接抓取：先选学校 → 打开该校官网 → 用户登录教务系统后抓取（v1.38.0 起支持全国高校）
        binding.cardLink.setOnClickListener {
            pickSchool.launch(SchoolPickerActivity.intent(this))
        }
        binding.cardManual.setOnClickListener {
            startActivity(Intent(this, CourseEditActivity::class.java))
        }

        // 从主界面加号「截图识别」进入：直接拉起系统选图，不再重复选择导入方式
        if (intent.getBooleanExtra(EXTRA_AUTO_PICK, false)) {
            binding.root.post { binding.cardScreenshot.performClick() }
        }
    }

    override fun onDestroy() {
        // 先停看门狗并置结束标记：识别中途退出时，迟到的 ML Kit 回调会走 ocrDone 分支
        // 回收位图后立即返回，不会向已销毁页面弹 Toast 或继续跳转核对页。
        ocrDone = true
        ocrTimeoutHandler.removeCallbacksAndMessages(null)
        // 只关闭真正创建过的识别器；回收 ML Kit detector/native 资源（AUDIT P1 ImportActivity.kt:160）
        if (recognizerDelegate.isInitialized()) {
            try {
                recognizer.close()
            } catch (e: Exception) {
                // 关闭失败不影响页面退出
            }
        }
        super.onDestroy()
    }

    /** 识别重试轮次：第 1 轮=灰度+色阶增强，第 2 轮=原图直识，第 3 轮=放大 2 倍+更温和增强 */
    private val OCR_MAX_ROUNDS = 3
    /** 单图整体超时上限（毫秒） */
    private val OCR_TIMEOUT_MS = 25000L
    /**
     * 预处理位图长边上限（px）。
     * 原实现第 3 轮把 1600px 底图再放大 2 倍到 3200px 级，ARGB_8888 单张峰值约 41MB，
     * 叠加底图与增强输出后数十 MB，低内存设备会 OOM（AUDIT P1 ImportActivity.kt:144）。
     * 限制到 2000px 后单张约 9MB；第 3 轮仍是 3 轮里分辨率最高的一轮，多轮择优语义不变。
     */
    private val PREPROCESS_MAX_DIM = 2600
    private val ocrTimeoutHandler = Handler(Looper.getMainLooper())
    private var ocrRound = 0
    private var ocrUri: android.net.Uri? = null
    private var ocrDone = false

    /** 一轮预处理的产物：识别输入 + 本轮结束需回收的位图（fromFilePath 兜底时无位图） */
    private class PreparedImage(val input: InputImage, val bitmap: android.graphics.Bitmap?)

    /**
     * 复用同一个中文识别器（Activity 生命周期内单例）。
     *
     * 原实现每轮识别都 `TextRecognition.getClient(...)` 新建且全工程从不 close：
     * 一次导入最多 9 张 × 3 轮 = 27 个 recognizer，ML Kit 的 detector/native 模型句柄持续泄漏，
     * 内存上涨且每轮重新初始化拖慢识别（AUDIT P1 ImportActivity.kt:160）。
     * 识别流程严格串行（本轮回调结束才进入下一轮），单实例复用安全；onDestroy 统一 close。
     */
    private val recognizerDelegate = lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val recognizer: TextRecognizer by recognizerDelegate

    // ---- 多图队列：逐张识别（每张三轮择优），结果合并后一次性进入核对页 ----
    private val ocrQueue = mutableListOf<String>()
    private val ocrResults = mutableListOf<Course>()
    private val ocrRawTexts = mutableListOf<String>()
    private var ocrFirstUri = ""

    /** 多轮择优：每轮都解析并评分，全部轮次跑完后提交字段最全的一组 */
    private var bestCourses: List<Course> = emptyList()
    private var bestScore = -1

    /** 多图入口：把选中的多张图加入队列，逐张识别 */
    private fun runOcrQueue(uris: List<String>) {
        ocrQueue.clear()
        ocrQueue.addAll(uris)
        ocrResults.clear()
        ocrRawTexts.clear()
        ocrFirstUri = uris.firstOrNull() ?: ""
        ocrDone = false
        Toast.makeText(this, "开始识别 ${uris.size} 张截图…", Toast.LENGTH_SHORT).show()

        // 超时看门狗：单图 25s + 每多一张追加 8s，全部轮次超时仍未完成才报错
        ocrTimeoutHandler.removeCallbacksAndMessages(null)
        ocrTimeoutHandler.postDelayed({
            if (!ocrDone) {
                ocrDone = true
                Toast.makeText(this, "识别超时，请重试", Toast.LENGTH_LONG).show()
            }
        }, OCR_TIMEOUT_MS + uris.size * 8000L)

        recognizeNext()
    }

    private fun runOcr(uriString: String) {
        runOcrQueue(listOf(uriString))
    }

    /** 队列为空 = 全部识别完成 → 合并提交；否则取下一张开始识别 */
    private fun recognizeNext() {
        if (ocrDone) return
        if (ocrQueue.isEmpty()) {
            ocrDone = true
            val raw = ocrRawTexts.joinToString("\n")
            launchResult(ocrFirstUri, raw, ocrResults)
            return
        }
        val uri = ocrQueue.removeAt(0)
        ocrUri = android.net.Uri.parse(uri)
        bestCourses = emptyList()
        bestScore = -1
        ocrRound = 0
        recognizeRound()
    }

    /**
     * 单轮识别。与旧逻辑"第一轮解析非空即提交"不同：
     * 现在三轮全部跑完，每轮结果与已有最优比较（字段完整度打分），取最全的一组提交。
     * 用户无感知，以耗时换取成功率（治"多试几次突然可以"与"第一轮漏字段"）。
     *
     * 图片读取/预处理较重（解码、放大、矩阵绘制），放到后台线程执行，
     * 避免阻塞主线程造成掉帧；识别引擎回调回到主线程处理。
     */
    private fun recognizeRound() {
        val uri = ocrUri ?: return
        Thread {
            val prepared = try {
                buildInputImage(uri, ocrRound)
            } catch (e: OutOfMemoryError) {
                // OutOfMemoryError 不是 Exception，原实现会直接闪退且丢失已识别结果
                //（AUDIT P1 ImportActivity.kt:144）。这里降级到下一轮（第 1 轮为原图直识，内存占用更低），
                // 最后一轮仍 OOM 才转成友好提示；不改变「每张图最多 3 轮、取最高分」的业务逻辑。
                runOnUiThread {
                    if (ocrDone) return@runOnUiThread
                    if (ocrRound + 1 < OCR_MAX_ROUNDS) {
                        ocrRound++
                        recognizeRound()
                    } else {
                        finishOcrWithError("内存不足，无法处理这张截图；请裁剪图片或减少一次导入的张数后重试")
                    }
                }
                return@Thread
            } catch (e: Exception) {
                // 读取/预处理失败：仍尝试后续轮次（下一轮可能用 fromFilePath 兜底），全部失败再报错
                runOnUiThread {
                    if (ocrDone) return@runOnUiThread
                    if (ocrRound + 1 < OCR_MAX_ROUNDS) {
                        ocrRound++
                        recognizeRound()
                    } else {
                        finishOcrWithError("识别失败(读取图片)：${e.message ?: "未知错误"}")
                    }
                }
                return@Thread
            }
            runOnUiThread {
                // 超时/已结束：忽略迟到的结果，避免超时后仍跳转；但本轮位图必须先回收
                if (ocrDone) {
                    prepared.bitmap?.recycle()
                    return@runOnUiThread
                }

                // 复用 Activity 级单例识别器（onDestroy 中 close），不再每轮新建
                recognizer.process(prepared.input)
                    .addOnSuccessListener { visionText ->
                        if (ocrDone) {
                            prepared.bitmap?.recycle()
                            return@addOnSuccessListener
                        }
                        val raw = visionText.text

                        // 优先尝试「整周课表网格」解析：提取带坐标的行，
                        // 若检测到星期表头+大节锚点（其他课表程序/教务网页的整周截图），直接解析出多门课
                        val lineBoxes = mutableListOf<GridParser.LineBox>()
                        for (block in visionText.textBlocks) {
                            for (line in block.lines) {
                                val b = line.boundingBox
                                if (b != null) {
                                    lineBoxes.add(
                                        GridParser.LineBox(line.text, b.left, b.top, b.right, b.bottom)
                                    )
                                }
                            }
                        }
                        val gridCourses = GridParser.parse(lineBoxes)

                        if (gridCourses != null) {
                            prepared.bitmap?.recycle()
                            commitRound(raw, gridCourses)
                            return@addOnSuccessListener
                        }

                        // 整图 OCR 认不出表头时，试试「先切表格线，再逐格放大 OCR」：
                        // 整周课表截图的小字在整图里会糊，分格放大后准确率高得多。
                        // 注意：ML Kit 的 Tasks.await 不能在主线程调用，所以这一路放到工作线程里同步做。
                        val bitmap = prepared.bitmap
                        if (bitmap == null || bitmap.isRecycled) {
                            commitRound(raw, fallbackCourses(visionText, raw))
                            return@addOnSuccessListener
                        }
                        Thread {
                            val tableCourses = try {
                                val rows = TableGridOcr.read(recognizer, bitmap)
                                rows?.let { TimetableGrid.parse(it) }
                            } catch (t: Throwable) {
                                android.util.Log.e("ClassGrid", "表格分格 OCR 失败", t)
                                null
                            }
                            runOnUiThread {
                                if (ocrDone) {
                                    if (!bitmap.isRecycled) bitmap.recycle()
                                    return@runOnUiThread
                                }
                                if (!bitmap.isRecycled) bitmap.recycle()
                                val merged = if (tableCourses != null && tableCourses.size >= 2) {
                                    tableCourses
                                } else {
                                    fallbackCourses(visionText, raw)
                                }
                                commitRound(raw, merged)
                            }
                        }.start()
                    }
                    .addOnFailureListener { e ->
                        prepared.bitmap?.recycle()
                        if (ocrDone) return@addOnFailureListener
                        if (ocrRound + 1 < OCR_MAX_ROUNDS) {
                            ocrRound++
                            recognizeRound()
                        } else {
                            finishOcrWithError("识别失败(识别引擎)：${e.message ?: "未知错误"}")
                        }
                    }
            }
        }.start()
    }

    /** 双通道文本解析（结构化坐标定位 + 纯文本标签匹配），分格 OCR 不可用时的兜底 */
    private fun fallbackCourses(
        visionText: com.google.mlkit.vision.text.Text,
        raw: String
    ): List<Course> = OcrParser.mergeCourses(
        OcrParser.parseStructured(visionText),
        OcrParser.parse(raw)
    )

    /** 一轮识别结束：择优、决定继续下一轮还是提交 */
    private fun commitRound(raw: String, merged: List<Course>) {
        val sc = scoreCourses(merged)
        if (sc > bestScore) {
            bestScore = sc
            bestCourses = merged
        }
        // 全部轮次跑完才提交最优结果；中途不因"有结果"提前终止
        if (ocrRound + 1 < OCR_MAX_ROUNDS) {
            ocrRound++
            recognizeRound()
        } else {
            // 单张识别完成：结果并入队列，继续下一张或合并提交
            onImageRecognized(raw, bestCourses)
        }
    }

    /** 识别完整度打分：用于多轮择优（字段越全分越高） */
    private fun scoreCourses(cs: List<Course>): Int {
        if (cs.isEmpty()) return -1
        var s = 0
        for (c in cs) {
            if (c.name.isNotEmpty()) s += 2
            if (c.code.isNotEmpty()) s += 1
            if (c.teacher.isNotEmpty()) s += 2
            if (c.classroom.isNotEmpty()) s += 2
            if (c.dayOfWeek in 1..7) s += 1
            if (c.startSection in 1..15 && c.endSection in 1..15) s += 1
            if (c.startWeek != 1 || c.endWeek != 16) s += 1
            if (c.startTime.isNotEmpty() && c.endTime.isNotEmpty()) s += 1
        }
        return s
    }

    /** 单张图三轮识别结束：有结果则并入，原文暂存，随后识别下一张或合并提交 */
    private fun onImageRecognized(raw: String, courses: List<Course>) {
        if (ocrDone) return
        if (courses.isNotEmpty()) ocrResults.addAll(courses)
        ocrRawTexts.add(raw)
        recognizeNext()
    }

    /**
     * Excel / CSV → 课程（v1.42.0）。
     * 教务系统导出的课表大多是「周一…周日 + 第N节」的二维表，读到网格后交给 TimetableGrid 解析，
     * 再走同一套核对页 —— 用户能逐条改，不用为每种系统写专门的导入器。
     *
     * v1.43.0：失败时不再只弹一句 toast —— 弹窗里直接给「导出导入日志」（文件信息 + 文件头 + 网格预览 + 异常），
     * 用户回传后能定位是格式不支持（BIFF5/加密/HTML 假 xls）还是表格结构没认出来。
     */
    private fun importSheet(uri: android.net.Uri) {
        var name = ""
        var bytes = ByteArray(0)
        var sheet: TimetableGrid.Sheet? = null
        try {
            name = queryDisplayName(uri)
            bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
            if (bytes.isEmpty()) {
                showSheetFailure(name, bytes, null, getString(R.string.import_excel_empty))
                return
            }
            val parsed = SheetImporter.read(name, bytes)
            sheet = parsed
            if (parsed.rows.isEmpty()) {
                showSheetFailure(name, bytes, parsed, getString(R.string.import_excel_empty))
                return
            }
            val courses = TimetableGrid.parse(parsed)
            if (courses.isEmpty()) {
                showSheetFailure(name, bytes, parsed, getString(R.string.import_excel_fail))
                return
            }
            launchResult("", parsed.toText(), courses)
        } catch (t: Throwable) {
            android.util.Log.e("ClassGrid", "importSheet failed", t)
            showSheetFailure(name, bytes, sheet, t.message ?: t.javaClass.simpleName)
        }
    }

    /** 读表格失败：把能定位问题的东西都写进日志，用户一键导出回传 */
    private fun showSheetFailure(
        name: String,
        bytes: ByteArray,
        sheet: TimetableGrid.Sheet?,
        detail: String
    ) {
        val report = buildString {
            appendLine("课格 ClassGrid 导入日志（内测版）")
            appendLine("版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.CHANNEL}")
            appendLine("时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date())}")
            appendLine("设备：${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
            appendLine()
            appendLine("== 失败原因 ==")
            appendLine(detail)
            appendLine()
            appendLine("== 文件信息 ==")
            appendLine(SheetImporter.describe(name, bytes))
            if (sheet != null && sheet.rows.isNotEmpty()) {
                appendLine()
                appendLine("== 读到的表格（前 24 行，制表符分隔）==")
                appendLine(sheet.toText(24))
            }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_excel_failed_title)
            .setMessage(report)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ocr_export_log) { _, _ -> shareImportLog(report) }
            .create()
        dialog.show()
    }

    /** 导出导入日志（分享 txt）：失败路径也能回传，不再只有一句 toast */
    private fun shareImportLog(report: String) {
        try {
            val dir = java.io.File(cacheDir, "exports")
            if (!dir.exists()) dir.mkdirs()
            val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.CHINA).format(java.util.Date())
            val file = java.io.File(dir, "classgrid_import_$stamp.txt")
            file.writeText(report)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "$packageName.fileprovider", file
            )
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ocr_export_log))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, getString(R.string.ocr_export_log)))
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun queryDisplayName(uri: android.net.Uri): String {
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx)?.let { return it }
                    }
                }
        } catch (_: Throwable) {
        }
        val path = uri.lastPathSegment ?: ""
        return if (path.contains('.')) path else "sheet.csv"
    }

    private fun launchResult(uriString: String, raw: String, courses: List<Course>) {
        // 页面已销毁则不再跳转，避免在失效 Activity 上启动新页面
        if (isFinishing || isDestroyed) return
        ocrDone = true
        ocrTimeoutHandler.removeCallbacksAndMessages(null)
        val intent = Intent(this, OcrResultActivity::class.java)
        intent.putExtra(OcrResultActivity.EXTRA_IMAGE_URI, uriString)
        intent.putExtra(OcrResultActivity.EXTRA_RAW_TEXT, raw)
        intent.putExtra(OcrResultActivity.EXTRA_COURSES, CourseJson.encode(courses))
        startActivity(intent)
    }

    private fun finishOcrWithError(msg: String) {
        ocrDone = true
        ocrTimeoutHandler.removeCallbacksAndMessages(null)
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    /**
     * 按轮次构造识别输入图：
     *  - 第 0 轮：降采样后灰度 + 色阶拉伸（blackPoint=190，小图放大到 1600）
     *  - 第 1 轮：原图直识（不做任何预处理）
     *  - 第 2 轮：降采样后放大 2 倍 + 更激进色阶（blackPoint=178）
     * 任一异常都兜底交给 ML Kit 从文件路径自行读取。
     */
    private fun buildInputImage(uri: android.net.Uri, round: Int): PreparedImage {
        return try {
            val base = loadDownsampled(uri, 2200)
            when (round) {
                0 -> {
                    val bmp = enhanceForOcr(base, 190f, upscaleSmall = true)
                    PreparedImage(InputImage.fromBitmap(bmp, 0), bmp)
                }
                1 -> PreparedImage(InputImage.fromBitmap(base, 0), base)
                else -> {
                    // 第 3 轮放大：scaleBitmap 内部把长边限制在 PREPROCESS_MAX_DIM（≤2000px），避免 3200px OOM
                    val bigger = scaleBitmap(base, 2f)
                    val bmp = enhanceForOcr(bigger, 178f, upscaleSmall = false)
                    PreparedImage(InputImage.fromBitmap(bmp, 0), bmp)
                }
            }
        } catch (e: Exception) {
            // 兜底交给 ML Kit 从文件路径自行读取（位图由 ML Kit 内部管理，不需要我们回收）
            PreparedImage(InputImage.fromFilePath(this, uri), null)
        }
    }

    /**
     * 按 factor 放大位图，但长边上限为 [PREPROCESS_MAX_DIM]（≤2000px）。
     * 原实现在 1600px 底图上再放大 2 倍到 3200px，ARGB_8888 单张约 41MB，
     * 与底图/增强输出叠加后峰值数十 MB，低内存设备直接 OOM（AUDIT P1 ImportActivity.kt:144）。
     */
    private fun scaleBitmap(src: android.graphics.Bitmap, factor: Float): android.graphics.Bitmap {
        val longest = maxOf(src.width, src.height)
        val targetLong = (longest * factor).toInt().coerceAtMost(PREPROCESS_MAX_DIM)
        val scale = targetLong.toFloat() / longest
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        val out = android.graphics.Bitmap.createScaledBitmap(src, w, h, true)
        if (out != src) src.recycle()
        return out
    }

    /**
     * 单流读取并降采样解码图片，最长边约 maxDim 像素。
     *
     * 修复点：整个流程只调用一次 [ContentResolver.openInputStream] 并一次性读入 ByteArray，
     * 之后两次 [android.graphics.BitmapFactory.decodeByteArray]（先取尺寸再真解码），
     * 避免部分国产 ROM（如 OPPO 系）相册 content provider 对同一 URI 二次开流返回 null/异常的问题。
     *
     * 任何读取或解码失败都抛出带具体异常类名与 message 的异常，不再吞掉，交由调用方上报。
     */
    @Throws(Exception::class)
    private fun loadDownsampled(uri: android.net.Uri, maxDim: Int): android.graphics.Bitmap {
        // 1) 只开一次流，整图读入内存
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IOException("contentResolver.openInputStream 返回 null（Uri: $uri）")
        } catch (e: Exception) {
            throw Exception("读取图片流失败：${e.javaClass.simpleName}: ${e.message}", e)
        }
        if (bytes.isEmpty()) {
            throw Exception("读取图片流失败：字节数为 0（Uri: $uri）")
        }

        // 2) 仅取尺寸（inJustDecodeBounds）
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw Exception(
                "读取图片失败：无法解析尺寸（outWidth=${bounds.outWidth}, outHeight=${bounds.outHeight}）"
            )
        }

        // 3) 计算 inSampleSize（目标最长边约 maxDim）
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: throw Exception("读取图片失败：decodeByteArray 返回 null（sampleSize=$sample）")

        // 4) 必要时精确缩放到 maxDim，确保最长边不超过目标值
        val longest = maxOf(bmp.width, bmp.height)
        return if (longest > maxDim) {
            val scale = maxDim.toFloat() / longest
            val scaled = android.graphics.Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * scale).toInt().coerceAtLeast(1),
                (bmp.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled != bmp) bmp.recycle()
            scaled
        } else {
            bmp
        }
    }

    /**
     * OCR 字级识别精度增强预处理。
     *
     * 针对「弹窗截图标签/值文字是浅灰色、与白底反差小」导致 ML Kit 漏识别标签或值
     * （典型表现：教室等字段整段丢失）的场景做增强：
     *   1) 小图放大（[upscaleSmall]=true）：最长边 < 1600px 时等比放大到 1600px；
     *   2) 灰度 + 色阶拉伸（levels）：把亮度低于 [blackPoint] 的像素压向纯黑、
     *      高于它的白底保持纯白。
     *
     * 为什么不用旧的"对比度(contrast)"方案：对比度矩阵以 128 灰阶为中心，
     * 浅灰文字（亮度 150~200，在 128 以上）增强后反而向白色移动、更看不清；
     * 色阶拉伸则把灰字一律压黑、白底保持白（如亮度 170 的标签、136 的值 → 纯黑，
     * 255 背景 → 纯白），字与背景反差拉满，标签和值都不再漏识别。
     *
     * 内存安全：输入已降采样，放大上限受控；中间位图在复用前 recycle。
     */
    private fun enhanceForOcr(
        src: android.graphics.Bitmap,
        blackPoint: Float,
        upscaleSmall: Boolean
    ): android.graphics.Bitmap {
        // 1) 最长边 < 1600px 时等比放大到 1600px（仅向上放大）
        val longest = maxOf(src.width, src.height)
        val scaled = if (upscaleSmall && longest < 1600) {
            val ratio = 1600f / longest
            val up = android.graphics.Bitmap.createScaledBitmap(
                src,
                (src.width * ratio).toInt().coerceAtLeast(1),
                (src.height * ratio).toInt().coerceAtLeast(1),
                true
            )
            if (up != src) src.recycle()
            up
        } else {
            src
        }

        // 2) 灰度 + 色阶拉伸：out = (in - blackPoint) / (255 - blackPoint) * 255
        //    即线性映射斜率 k 与平移 offset，超出 [0,255] 的值由颜色管线自动截断
        val k = 255f / (255f - blackPoint)
        val off = -blackPoint * k
        val base = android.graphics.ColorMatrix().apply { setSaturation(0f) }
        val levels = android.graphics.ColorMatrix(
            floatArrayOf(
                k, 0f, 0f, 0f, off,
                0f, k, 0f, 0f, off,
                0f, 0f, k, 0f, off,
                0f, 0f, 0f, 1f, 0f
            )
        )
        base.postConcat(levels)

        val out = android.graphics.Bitmap.createBitmap(
            scaled.width, scaled.height, android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(base)
        }
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        if (scaled != out) scaled.recycle()
        return out
    }

    private fun showJsonImport() {
        val input = EditText(this)
        input.minLines = 8
        input.gravity = android.view.Gravity.TOP
        input.setText(readClipboard())

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_json)
            .setView(container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val json = input.text.toString().trim()
                val imported = CourseJson.decode(json)
                if (imported.isEmpty()) {
                    Toast.makeText(this, "未解析到课程，请检查 JSON 格式", Toast.LENGTH_SHORT).show()
                } else {
                    val added = CourseStore.mergeAndSave(this, imported)
                    val msg = if (added > 0) "已导入 $added 门课程"
                    else "导入完成，无新增（重复课程已更新）"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun readClipboard(): String {
        return try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
