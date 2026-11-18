package com.classgrid.app

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.classgrid.app.databinding.ActivityWebImportBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 链接抓取（v1.38.0 起支持全国高校）：应用内打开**用户所选学校的官网** → 用户自己登录教务系统
 * → 进入课表页 → 自动提取课程。
 *
 * 为什么先选学校：v1.37.0 只会打开广外教务系统，对其它学校的人毫无用处。各校的教务入口
 * 千差万别（CAS 单点登录、统一身份认证、门户里的子应用、甚至换了域名），App 不可能内置全部路径，
 * 所以：用 [SchoolPickerActivity] 选学校（内置 2868 所，覆盖 31 个省市区）→ 从学校官网开始 →
 * 用户在熟悉的界面里自己点进教务系统并登录 → 点「抓取当前页面」。
 *
 * 网址随时可改：底部「网址」按钮或选校页的铅笔都能改，改完按学校记住
 * （[SchoolDirectory.rememberSite]），下次点这所学校直接打开新网址 —— 因为名单里的网址
 * 有可能填错，学校也可能换了域名。
 *
 * 为什么用内嵌 WebView 而不是"让用户粘贴 Cookie / 自己发请求"：
 * - 教务系统普遍有 CAS 单点登录、验证码、可能的二次认证，自动化登录既脆弱又涉及用户密码；
 * - 内嵌浏览器让用户在自己熟悉的界面里完成登录，App 只读页面 DOM，不接触账号密码；
 * - Cookie 由系统 CookieManager 持久化，下次进来通常还是登录态。
 *
 * 数据流：
 * WebView 页面 →（注入 WebImportScript.INSTALL_JS）→ 结构化表格 JSON →（@JavascriptInterface）
 * → [WebScheduleParser] → List<Course> → 确认弹窗 → OcrResultActivity（复用已有的核对/编辑/导入页面）。
 *
 * 关键取舍：
 * - JS 只做无语义提取、Kotlin 做语义解析（可单测），换教务系统只改解析器；
 * - 页面 DOM 变化（SPA/XHR 换页）由 MutationObserver 自动重扫，所以用户"点进课表就自动抓到"；
 * - 同一个 payload 只自动弹一次确认框，避免 SPA 页面反复刷新时弹窗轰炸。
 */
class WebImportActivity : AppCompatActivity() {

    companion object {
        /** 指定初始网址：调用方（选校页 / 导入页）已经选好学校时带上 */
        const val EXTRA_URL = "extra_url"

        /** 指定学校名：只用于标题显示与「记住这所学校的网址」，不影响抓取逻辑 */
        const val EXTRA_SCHOOL = "extra_school"

        /** 最多旁听多少个接口请求（FIFO 淘汰最旧的）：页面一次加载可能有几十个请求，全重放会拖慢 */
        private const val API_REQUEST_LIMIT = 8

        /** 重放响应体上限（400KB）：课表 JSON 通常几十 KB，超出的部分对解析没意义，还会占内存 */
        private const val MAX_REPLAY_BODY = 400_000
    }

    private lateinit var binding: ActivityWebImportBinding
    private val handler = Handler(Looper.getMainLooper())

    /** 最近一次解析出的课程 */
    private var lastCourses: List<Course> = emptyList()

    /** 最近一次收到的原始 payload（诊断导出用） */
    private var lastRawPayload: String = ""

    /** 最近一次收到的 payload 对象（诊断摘要用） */
    private var lastPayload: WebScheduleParser.Payload? = null

    /** 最近一次解析的备注（随时导出诊断用） */
    private var lastNotes: List<String> = emptyList()

    /**
     * 页面自己发出的、看起来像课表接口的 GET 请求（由 [WebViewClient.shouldInterceptRequest] 记录）。
     *
     * 为什么还要这一层：课表可能放在**跨域 iframe** 里（智慧广外门户把教务子应用嵌进来），
     * 注入脚本只能进同源文档，看不到跨域 iframe 内部的 XHR/fetch；但 WebViewClient 能看到
     * 浏览器发出的每一个请求。用户点「抓取当前页面」时，用系统 Cookie 把这些请求重放一次，
     * 就能拿到页面渲染课表所用的后端数据 —— 这正是用户要的「爬每门课程的数据」。
     */
    private val apiRequests = LinkedHashMap<String, Boolean>()

    /** 本次页面加载里是否已经试过重放（避免用户连点按钮时反复打接口） */
    private var replayTried = false

    /** 本次页面是否已经试过「切换到列表展示」再抓一次（v1.43.0，div 网格课表用） */
    private var listModeTried = false

    /** 重放请求在后台线程做：主线程跑 HttpURLConnection 在 Android 7+ 会直接抛 NetworkOnMainThreadException */
    private val netExecutor = Executors.newSingleThreadExecutor()

    /** 已处理过的 payload 指纹（同样的内容不重复解析） */
    private var lastPayloadFingerprint = ""

    /**
     * 运行日志（内存环形，最多 300 条）。
     *
     * 为什么需要：用户反馈「抓取失败了，但不知道日志在哪」。
     * 抓取链路跨 WebView → 注入脚本 → JS 桥 → 解析器四层，任何一层出问题在界面上只会表现为
     * 「没找到课表」。所以每一步都记一行，失败时用户点右上角「⋯」→「导出运行日志」就能回传。
     */
    private val runLog = ArrayDeque<String>()

    private fun log(msg: String) {
        val line = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date()) + " " + msg
        synchronized(runLog) {
            runLog.addLast(line)
            while (runLog.size > 300) runLog.removeFirst()
        }
        if (BuildConfig.DEBUG) android.util.Log.d("ClassGridWeb", line)
    }

    private fun logDump(): String = synchronized(runLog) { runLog.joinToString("\n") }

    private var pageFinished = false

    /** 当前学校（可能为空：外部直接指定网址、没经过选校页） */
    private var school: SchoolDirectory.School? = null

    /**
     * 这所学校的候选入口链（v1.39.0）：
     * 真实入口 → jwxt/jwglxt/jwgl/jwc/ehall/one 子域 → 官网。
     *
     * 为什么需要：各校教务系统域名毫无规律（清华 zhjw.cic、北大 dean、上交 electsys、南大 jw/jn），
     * 猜子域基本猜不中，但猜不中不代表链上后面的官网也没用 —— 官网首页大多能点进教务。
     * 主框架加载失败就自动试下一个，比让用户对着一片白屏自己想办法好。
     */
    private val entryChain = mutableListOf<String>()
    private var entryIndex = -1

    /** 由我们 loadEntry() 发起、还没成功加载的那个候选；只有它失败才自动换下一个 */
    private var pendingEntryUrl: String? = null

    /** 加载完下一张页面后要扫描的链接关键词（「在官网里找教务系统」用） */
    private var pendingScan: Array<String>? = null

    private var currentFamily: WebSystem.Family = WebSystem.Family.UNKNOWN

    /** 在抓取页里换学校：选完直接在当前 WebView 打开新学校网址，不用退回导入页 */
    private val pickSchool =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                // 没选就返回：本来也没有学校在抓，就别留在一个空页面
                if (school == null && binding.webView.url.isNullOrBlank()) finish()
                return@registerForActivityResult
            }
            val data = result.data ?: return@registerForActivityResult
            val name = data.getStringExtra(SchoolPickerActivity.EXTRA_SCHOOL_NAME)
                ?: return@registerForActivityResult
            val url = data.getStringExtra(SchoolPickerActivity.EXTRA_SCHOOL_URL)
                ?: return@registerForActivityResult
            school = SchoolDirectory.find(this, name)
            applySchool()
            log("切换学校：" + name + " -> " + url)
            startEntry(url)
        }

    // ------------------------------------------------------------------ 生命周期

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityWebImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.header) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.updatePadding(bottom = bottom + dp(10))
            insets
        }

        binding.btnBack.setOnClickListener { confirmClose() }
        binding.btnRefresh.setOnClickListener {
            binding.webView.reload()
            resetState()
        }
        binding.btnScan.setOnClickListener { onScanClicked() }
        binding.btnUrl.setOnClickListener { showUrlDialog() }
        // v1.42.0 内测版：日志入口要显眼 —— 抓不到课表时，用户不需要翻「⋯」就知道点哪里回传数据
        binding.btnLog.setOnClickListener { exportLog() }
        binding.btnMore.setOnClickListener { showExportMenu() }

        setupWebView()

        // 学校由调用方传入（导入页选校后带过来）；没有的话**先弹出选校页**，
        // 而不是替用户决定打开哪所学校（v1.37.0 固定打开广外，正是这次要修的）
        val schoolName = intent.getStringExtra(EXTRA_SCHOOL)
        if (!schoolName.isNullOrBlank()) {
            school = SchoolDirectory.find(this, schoolName)
        }
        applySchool()

        // 起始地址默认是「教务系统入口」，不是官网（v1.39.0）：大多数学校的教务是独立网址，
        // 从官网首页再点进去多一步，而且很多人找不到入口在哪。入口打不开会自动回退到官网。
        val startUrl = intent.getStringExtra(EXTRA_URL)
            ?.let { SchoolDirectory.normalizeUrl(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: school?.let { SchoolDirectory.entryOf(this, it) }?.takeIf { it.isNotEmpty() }
        if (startUrl == null) {
            log("没有指定学校/网址，先打开选校页")
            openSchoolPicker()
        } else {
            log("打开抓取页：学校=" + (school?.name ?: "-") + "，起始地址=" + startUrl)
            startEntry(startUrl)
        }

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }

    override fun onPause() {
        super.onPause()
        // Cookie 落盘：否则杀掉进程后要重新登录
        CookieManager.getInstance().flush()
        binding.webView.onPause()
        binding.webView.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.resumeTimers()
        binding.webView.onResume()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        netExecutor.shutdownNow()
        binding.webView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeJavascriptInterface(WebImportScript.BRIDGE)
            destroy()
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            confirmClose()
        }
    }

    /** 有已抓到的课程且还没导入时，返回前提醒一下，避免白抓 */
    private fun confirmClose() {
        if (lastCourses.isNotEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("放弃本次抓取？")
                .setMessage("已抓到 ${lastCourses.size} 门课程还没导入。")
                .setNegativeButton("继续抓取", null)
                .setPositiveButton("放弃") { _, _ -> finish() }
                .show()
        } else {
            finish()
        }
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val web = binding.webView
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            useWideViewPort = true
            // 课表页通常很宽：允许缩放，但不用 overview 强制缩小（否则文字不可读）
            loadWithOverviewMode = false
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            // 有些教务系统按 UA 判断移动端；保留默认 UA，仅追加 App 标识便于排查
            userAgentString = userAgentString + " ClassGrid/" + BuildConfig.VERSION_NAME
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // CAS 单点登录常跨域，必须允许第三方 Cookie，否则登录后一直跳回登录页
            setAcceptThirdPartyCookies(web, true)
        }

        web.addJavascriptInterface(Bridge(), WebImportScript.BRIDGE)

        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                pageFinished = false
                resetState()
                log("页面开始加载：" + (url ?: "-"))
                binding.progress.visibility = View.VISIBLE
                setStatus(getString(R.string.web_import_loading))
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                pageFinished = true
                binding.progress.visibility = View.GONE
                // 只「安装」提取钩子（网络响应缓存 + DOM 采集函数），绝不主动扫描/抓取：
                // 抓取时机完全由用户点底部「抓取当前页面」决定，避免登录页一打开就弹识别失败。
                view?.let { injectScript(it, 0) }
                log("页面加载完成：" + (url ?: "-"))
                val landed = url ?: view?.url
                currentFamily = WebSystem.detect(landed, view?.title)
                log("教务系统判断：" + WebSystem.label(this@WebImportActivity, currentFamily))
                // 自动回退成功了：把真正能打开的入口按学校记住，下次直接进
                if (entryIndex > 0 && school != null && landed != null &&
                    landed.startsWith(entryChain.getOrNull(entryIndex).orEmpty())
                ) {
                    SchoolDirectory.rememberSite(this@WebImportActivity, school!!, landed)
                    log("记住可用入口：" + landed)
                }
                pendingEntryUrl = null
                val scan = pendingScan
                if (scan != null) {
                    pendingScan = null
                    view?.let { scanLinks(it, scan) }
                } else if (lastCourses.isEmpty()) {
                    val s = school
                    setStatus(
                        when {
                            s == null -> getString(R.string.web_import_login_hint)
                            currentFamily != WebSystem.Family.UNKNOWN ->
                                getString(
                                    R.string.web_import_school_system_hint,
                                    s.name,
                                    WebSystem.label(this@WebImportActivity, currentFamily)
                                )
                            else -> getString(R.string.web_import_school_login_hint, s.name)
                        }
                    )
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleExternalScheme(url)
            }

            /**
             * 记录页面发出的「像课表接口」的 GET 请求，供用户点抓取时重放。
             *
             * 只看 GET：POST 可能是提交/退课/删除这类有副作用的动作，重放会真改服务器数据。
             * 返回 null（= 不拦截）让 WebView 正常加载，这一层只旁听，不改行为。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): android.webkit.WebResourceResponse? {
                val url = request?.url?.toString()
                if (url != null && request.method == "GET") rememberApiRequest(url)
                return super.shouldInterceptRequest(view, request)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleExternalScheme(url ?: return false)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    binding.progress.visibility = View.GONE
                    val desc = error?.description?.toString() ?: "unknown"
                    val failed = request.url?.toString().orEmpty()
                    if (shouldFallback(failed)) {
                        tryNextEntry(failed, desc)
                    } else {
                        setStatus(getString(R.string.web_import_error, desc))
                    }
                }
            }

            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: android.webkit.WebResourceResponse?
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                val code = errorResponse?.statusCode ?: 0
                if (request?.isForMainFrame == true && code >= 400) {
                    val failed = request.url?.toString().orEmpty()
                    if (shouldFallback(failed)) {
                        tryNextEntry(failed, "HTTP $code")
                    }
                }
            }

            override fun onReceivedSslError(view: WebView?, h: SslErrorHandler?, error: SslError?) {
                // 学校系统常见"证书过期/自签"：不静默放行，交给用户判断（默认取消）
                val url = error?.url ?: ""
                MaterialAlertDialogBuilder(this@WebImportActivity)
                    .setTitle("证书校验失败")
                    .setMessage("$url\n\n可能是学校的自签名证书或证书已过期。仅当确定这是学校自己的网站时才继续。")
                    .setNegativeButton("取消") { _, _ -> h?.cancel(); binding.progress.visibility = View.GONE }
                    .setPositiveButton("继续访问") { _, _ -> h?.proceed() }
                    .setCancelable(false)
                    .show()
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 100) binding.progress.visibility = View.GONE
                else binding.progress.visibility = View.VISIBLE
            }

            // target=_blank / window.open：新窗口的 URL 塞回同一个 WebView
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val tmp = WebView(this@WebImportActivity)
                tmp.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        v: WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        val u = request?.url?.toString()
                        if (!u.isNullOrBlank()) binding.webView.loadUrl(u)
                        tmp.destroy()
                        return true
                    }
                }
                (resultMsg?.obj as? WebView.WebViewTransport)?.webView = tmp
                resultMsg?.sendToTarget()
                return true
            }
        }
    }

    /** 非 http(s) 的协议交给系统处理（tel:/mailto:/微信/支付宝…），其余都在应用内打开 */
    private fun handleExternalScheme(url: String): Boolean {
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) return false
        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (e: Exception) {
            true
        }
    }

    /** 注入提取脚本；scan=true 只补一次扫描，不重复安装监听器 */
    /**
     * v1.43.0：课表不是 <table> 而是 div 网格时（肇庆学院 WebVPN 这类），
     * 页面上通常有个「列表展示 / 列表视图」开关 —— 点它，等页面重绘后再抓一次。
     */
    private fun tryListMode(): Boolean {
        val web = binding.webView
        if (web.url.isNullOrBlank()) return false
        setStatus(getString(R.string.web_import_try_list_mode))
        log("尝试切换到列表展示再抓一次")
        val js = """
            (function () {
              var words = ['列表展示', '列表视图', '列表显示', '表格视图', '课表列表', '列表模式'];
              function clickIn(doc) {
                var nodes = doc.querySelectorAll('a,button,span,div,li,input[type=button],input[type=submit]');
                for (var i = 0; i < nodes.length; i++) {
                  var el = nodes[i];
                  var t = (el.innerText || el.value || '').replace(/\s+/g, '');
                  if (!t || t.length > 8) continue;
                  for (var w = 0; w < words.length; w++) {
                    if (t === words[w]) {
                      try { el.click(); return words[w]; } catch (e) {}
                    }
                  }
                }
                return '';
              }
              var hit = clickIn(document);
              if (!hit) {
                var frames = document.querySelectorAll('iframe');
                for (var f = 0; f < frames.length; f++) {
                  try { hit = clickIn(frames[f].contentDocument); } catch (e) {}
                  if (hit) break;
                }
              }
              return hit || '';
            })();
        """.trimIndent()
        try {
            web.evaluateJavascript(js) { result ->
                log("列表展示点击结果：" + (result ?: "null"))
            }
        } catch (e: Exception) {
            log("列表展示脚本异常：" + e)
        }
        // 页面重绘需要时间：1.6 秒后如果还没抓到，再扫一次
        web.postDelayed({
            if (lastCourses.isEmpty()) {
                setStatus(getString(R.string.web_import_scanning))
                injectScript(web, 1)
            }
        }, 1600)
        return true
    }

    private fun injectScript(web: WebView, scan: Int) {
        val js = if (scan == 1) WebImportScript.SCAN_CALL else WebImportScript.INSTALL_JS
        try {
            web.evaluateJavascript(js) { result ->
                // 注入失败几乎都发生在页面 CSP / 无 JS 环境；把返回值记下来才能看明白
                log("注入脚本" + (if (scan == 1) "（扫描）" else "（安装钩子）") + " 返回：" + (result ?: "null"))
            }
        } catch (e: Exception) {
            log("注入脚本异常：" + e)
        }
    }

    // ------------------------------------------------------------------ JS 桥

    private inner class Bridge {
        @JavascriptInterface
        fun onScan(json: String) {
            // 桥接方法在 JS 线程被调用，必须切主线程
            val size = json.length
            runOnUiThread {
                log("收到页面数据 (" + size + " 字节)")
                try {
                    handlePayload(json)
                } catch (t: Throwable) {
                    // 解析层出任何异常都不能静默：写日志 + 给用户一条可回传的路径
                    log("解析异常：" + android.util.Log.getStackTraceString(t))
                    setStatus(getString(R.string.web_import_none))
                    Toast.makeText(
                        this@WebImportActivity,
                        "抓取数据处理出错，点右上角「⋯」→「导出运行日志」发给开发者",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * 收到 JS 抓取结果。[json] 里既有页面自己请求到的接口响应（net），也有 DOM 表格/视觉块，
     * 解析优先级：接口 JSON → DOM 表格/合成网格。这样只要网页是通过 XHR/fetch 取课表数据，
     * 我们拿到的就是原始字段（周次/教师/教室完整），而不是被 CSS 截断的显示文本。
     */
    private fun handlePayload(json: String) {
        if (isFinishing || isDestroyed) return
        val payload = WebScheduleParser.parsePayload(json) ?: run {
            log("payload 解析失败：不是预期的 JSON 结构")
            return
        }
        val fingerprint = payload.url + "#" + payload.tables.size + "#" +
            payload.tables.sumOf { t -> t.cells.sumOf { it.text.length } } + "#" +
            payload.bodyText.length + "#" + payload.net.size
        if (fingerprint == lastPayloadFingerprint) return
        lastPayloadFingerprint = fingerprint
        lastRawPayload = json
        lastPayload = payload
        log(
            "payload: 表格=" + payload.tables.size + " 文本块=" + payload.blocks.size +
                " 接口响应=" + payload.net.size + " 正文=" + payload.bodyText.length + " 手动=" + payload.manual
        )

        val sections = try {
            SectionTimes.load(this)
        } catch (e: Exception) {
            SectionTimes.default()
        }
        val notes = mutableListOf<String>()
        if (payload.errors.isNotEmpty()) {
            // 页面/注入脚本自己报的错：抓不到时这是最直接的线索，必须让用户能看见
            notes.add("页面脚本报错：" + payload.errors.joinToString("；") { it.take(160) })
            payload.errors.forEach { log("页面脚本报错：" + it) }
        }

        // 1) 优先：页面自己请求的接口数据
        var courses = emptyList<Course>()
        if (payload.net.isNotEmpty()) {
            courses = try {
                WebJsonParser.parse(payload.net, sections, notes)
            } catch (e: Exception) {
                notes.add("接口数据解析异常：" + (e.message ?: e.javaClass.simpleName))
                emptyList()
            }
            if (courses.isNotEmpty()) {
                notes.add(0, getString(R.string.web_import_source_api, payload.net.size))
            } else {
                notes.add(getString(R.string.web_import_net_no_course, payload.net.size))
                // 现实里确实存在「接口里没有星期字段」的教务系统：strict 模式会把整张表丢掉，
                // 表现为「什么都抓不到」。这时退一步：允许星期未知，交给下面的页面网格补。
                val loose = try {
                    WebJsonParser.parse(payload.net, sections, notes, keepUnknownDay = true)
                } catch (e: Exception) {
                    notes.add("接口数据解析异常（宽松模式）：" + (e.message ?: e.javaClass.simpleName))
                    emptyList()
                }
                if (loose.isNotEmpty()) {
                    courses = loose
                    notes.add(0, "接口给了 " + loose.size + " 门课但缺少星期字段，正在用页面网格位置补")
                    log("严格模式 0 门，宽松模式 " + loose.size + " 门（星期未知）")
                }
            }
        }

        // 2) DOM 解析结果：既当兜底，也用来补接口里缺的节次。
        //    新版课表页是 div 卡片网格，格子位置本身就等于"第几大节"，比接口猜 1-2 节可靠得多。
        //    （v1.32.0 真机问题：国家安全教育接口没给节次 → 被兜底成 1-2 节，实际是第七大节 14-15。）
        var domCourses = emptyList<Course>()
        var domNotes = emptyList<String>()
        if (payload.tables.isNotEmpty()) {
            val result = WebScheduleParser.parse(payload, sections)
            domCourses = result.courses
            domNotes = result.notes
        }

        if (courses.isEmpty()) {
            courses = domCourses
            if (domNotes.isNotEmpty()) notes.addAll(domNotes)
        } else if (domCourses.isNotEmpty()) {
            courses = fillSectionsFromDom(courses, domCourses, notes)
        }

        courses = finishUnknownSections(courses, notes)

        // 3) 星期未知的课：用页面网格位置按课程名补；补不到就跳过（并写清楚缩了几门）
        courses = repairUnknownDays(courses, domCourses, notes)

        // 4) 星期自检/纠正（v1.36.0 真机反馈：抓出来所有课都落在周二）
        courses = reconcileWeekdays(courses, domCourses, notes)
        log("解析结果：接口/DOM=" + domCourses.size + " 门，最终 " + courses.size + " 门")

        lastCourses = courses
        lastNotes = notes.toList()
        if (courses.isEmpty()) {
            setStatus(getString(R.string.web_import_none))
            // 只有用户主动点抓取才继续往下走；自动流程一律不动作、不弹窗
            if (!payload.manual) return
            // 第二条路（v1.43.0）：页面是 div 网格、又没有表头可重建时（如肇庆学院 WebVPN），
            // 先尝试点「列表展示 / 列表视图」切到列表，再抓一次 —— 列表视图通常是标准表格。
            if (!listModeTried) {
                listModeTried = true
                if (tryListMode()) return
            }
            // 第三条路：页面可能是跨域 iframe / SPA，JS 钩子看不到课表请求，
            // 但 WebViewClient 旁听到过它们 —— 用系统 Cookie 重放一次。
            if (!replayTried) {
                replayTried = true
                tryReplayApiRequests(notes) { replayed ->
                    if (replayed.isNotEmpty()) {
                        // 重放拿到的接口数据同样可能缺节次：再用一遍网格位置补齐
                        var fixed = finishUnknownSections(
                            fillSectionsFromDom(replayed, domCourses, notes), notes
                        )
                        fixed = repairUnknownDays(fixed, domCourses, notes)
                        fixed = reconcileWeekdays(fixed, domCourses, notes)
                        lastCourses = fixed
                        lastNotes = notes.toList()
                        setStatus(getString(R.string.web_import_found, fixed.size))
                        showImportConfirm(fixed, notes)
                    } else {
                        setStatus(getString(R.string.web_import_none))
                        showDiagnostics(payload, notes)
                    }
                }
                return
            }
            showDiagnostics(payload, notes)
            return
        }

        setStatus(getString(R.string.web_import_found, courses.size))
        showImportConfirm(courses, notes)
    }

    // ------------------------------------------------------------------ 节次补全

    /**
     * 用 DOM 网格（含注入脚本合成的网格表）给接口课程补节次。
     *
     * 背景：接口给的是"数据"，不一定带节次；页面网格给的是"位置"，格子在哪一行就是第几大节。
     * 两者按「星期 + 课程名」配对，只填 startSection==0 的未知项，绝不覆盖接口已经给出的值。
     */
    private fun fillSectionsFromDom(
        courses: List<Course>,
        dom: List<Course>,
        notes: MutableList<String>
    ): List<Course> {
        val usable = dom.filter { it.startSection in 1..15 && it.endSection >= it.startSection }
        if (usable.isEmpty()) return courses
        val exact = usable.groupBy { it.dayOfWeek to courseNameKey(it.name) }
        var filled = 0
        var mismatch = 0
        val out = courses.map { c ->
            val known = c.startSection in 1..15 && c.endSection >= c.startSection
            val hit = exact[c.dayOfWeek to courseNameKey(c.name)]?.firstOrNull()
                ?: usable.firstOrNull { it.dayOfWeek == c.dayOfWeek && namesSimilar(it.name, c.name) }
            if (known) {
                // 接口给了节次、网格位置却是另一段：不擅自改（接口可能是对的），但写进诊断，
                // 方便下次从「导出抓取数据」的 JSON 里判断到底哪一边可信。
                if (hit != null && (hit.startSection != c.startSection || hit.endSection != c.endSection)) {
                    mismatch++
                }
                return@map c
            }
            if (hit != null) {
                filled++
                c.copy(startSection = hit.startSection, endSection = hit.endSection)
            } else {
                c
            }
        }
        if (filled > 0) notes.add("有 $filled 门课的节次是按页面网格位置补的（接口里没给）")
        if (mismatch > 0) notes.add("有 $mismatch 门课的接口节次与页面网格位置不一致（已采用接口值，可在核对页确认）")
        return out
    }

    /** 最后兜底：确实拿不到节次的，按 1-2 节写并明确告诉用户改哪儿 */
    private fun finishUnknownSections(courses: List<Course>, notes: MutableList<String>): List<Course> {
        val unknown = courses.count { it.startSection !in 1..15 || it.endSection < it.startSection }
        if (unknown == 0) return courses
        notes.add("有 $unknown 门课实在拿不到节次，暂按第 1-2 节填写，请在核对页对照网页详情改")
        return courses.map {
            if (it.startSection !in 1..15 || it.endSection < it.startSection) {
                it.copy(startSection = 1, endSection = 2)
            } else {
                it
            }
        }
    }

    /**
     * 星期自检与纠正（v1.36.0）。
     *
     * 真机反馈：抓取后**所有课程都落在星期二**，课程名/教师/教室都对。
     * 说明数据拿到了，但"星期"这一列被算成了一天。可能的原因有两类：
     *   a) 接口 JSON 里没有可靠的星期字段（或字段是学期无关的编号），全部退化成同一个值；
     *   b) 页面网格的星期表头没识别出多列，所有卡片落进同一列。
     * 处理原则：**页面网格是用户眼睛看到的事实**，当接口结果全都同一天、而网格里有多天时，
     * 就按课程名逐个改用网格的星期；两者都只有一天时不擅自改，只提示用户去核对页确认。
     */
    /**
     * 给「星期未知」的课（dayOfWeek = 0）从页面网格里找同一条课的位置。
     *
     * 场景：某些教务接口只给课程名/教师/教室，不给星期（v1.36.0 真机反馈「一个都抓不到」就是这个）。
     * 页面网格里课程摆在哪个列就是周几，所以按课程名匹配即可补回；补不到的必须丢弃 ——
     * Course 的星期是 1..7，留 0 会让这门课在课表里彻底消失又占着数据。
     */
    private fun repairUnknownDays(
        courses: List<Course>,
        dom: List<Course>,
        notes: MutableList<String>
    ): List<Course> {
        if (courses.none { it.dayOfWeek !in 1..7 }) return courses
        val usable = dom.filter { it.dayOfWeek in 1..7 }
        val exact = usable.groupBy { courseNameKey(it.name) }
        var fixed = 0
        var lost = 0
        val out = ArrayList<Course>(courses.size)
        for (c in courses) {
            if (c.dayOfWeek in 1..7) {
                out.add(c)
                continue
            }
            val hit = exact[courseNameKey(c.name)]?.firstOrNull()
                ?: usable.firstOrNull { namesSimilar(it.name, c.name) }
            if (hit == null) {
                lost++
                continue
            }
            fixed++
            out.add(
                c.copy(
                    dayOfWeek = hit.dayOfWeek,
                    startSection = if (c.startSection in 1..15) c.startSection else hit.startSection,
                    endSection = if (c.endSection in 1..15) c.endSection else hit.endSection
                )
            )
        }
        if (fixed > 0) notes.add("有 $fixed 门课的星期是按页面网格位置补的（接口里没给星期）")
        if (lost > 0) notes.add("有 $lost 门课接口和页面里都对不上星期，已跳过（可导出抓取数据发我继续修）")
        return out
    }

    private fun reconcileWeekdays(
        courses: List<Course>,
        dom: List<Course>,
        notes: MutableList<String>
    ): List<Course> {
        if (courses.size < 2) return courses
        val apiDays = courses.map { it.dayOfWeek }.distinct()
        val domDays = dom.map { it.dayOfWeek }.distinct()
        if (apiDays.size == 1 && domDays.size >= 2) {
            var fixed = 0
            val out = courses.map { c ->
                val hit = dom.firstOrNull {
                    it.dayOfWeek != c.dayOfWeek && courseNameKey(it.name) == courseNameKey(c.name)
                } ?: dom.firstOrNull {
                    it.dayOfWeek != c.dayOfWeek && namesSimilar(it.name, c.name)
                }
                if (hit != null) {
                    fixed++
                    c.copy(dayOfWeek = hit.dayOfWeek)
                } else {
                    c
                }
            }
            if (fixed > 0) {
                notes.add(0, "已按页面网格位置修正 $fixed 门课的星期（接口返回的星期不可靠）")
                return out
            }
        }
        if (courses.size >= 4 && apiDays.size == 1) {
            notes.add(
                "注意：${courses.size} 门课全部落在同一天（${dayName(apiDays.first())}），" +
                    "如果和网页上看到的不一致，请点右上角「⋯」→「导出抓取数据」把 JSON 发我，我按真实字段修解析"
            )
        }
        return courses
    }

    private fun dayName(day: Int): String =
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
            .getOrNull(day - 1) ?: "未知"

    /** 课程名比对用的键：去掉空白/标点，只留字母数字汉字（"高等数学(1)" → "高等数学1"） */
    private fun courseNameKey(name: String): String =
        name.filter { it.isLetterOrDigit() }.lowercase()

    /** 名字相似：完全相等，或一个包含另一个且较短的那个至少 4 个字（防止 "体育" 误配 "体育舞蹈"） */
    private fun namesSimilar(a: String, b: String): Boolean {
        val x = courseNameKey(a)
        val y = courseNameKey(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val min = minOf(x.length, y.length)
        return min >= 4 && (x.contains(y) || y.contains(x))
    }

    // ------------------------------------------------------------------ 交互

    private fun onScanClicked() {
        // 已经抓到课程：再点就重新弹确认框（用户可能刚关掉）
        if (lastCourses.isNotEmpty()) {
            showImportConfirm(lastCourses, emptyList())
            return
        }
        setStatus(getString(R.string.web_import_scanning))
        // 只在这里触发扫描：__scan(true) 里的 true 表示"用户手动"，用于决定失败时是否弹诊断框
        injectScript(binding.webView, 1)
        handler.postDelayed({
            // 重放接口可能要好几秒（每个请求 8s 超时），这期间不能报"没找到"
            if (!isFinishing && !isDestroyed && lastCourses.isEmpty() && !replayTried) {
                setStatus(getString(R.string.web_import_none))
                Toast.makeText(this, R.string.web_import_none, Toast.LENGTH_LONG).show()
            }
        }, 2500)
    }

    private fun showImportConfirm(courses: List<Course>, notes: List<String>) {
        val msg = StringBuilder()
        msg.append(getString(R.string.web_import_found_message, courses.size))
        val preview = courses.take(5).joinToString("\n") { "${it.name} · 周${it.dayOfWeek} · ${it.startSection}-${it.endSection}节" }
        if (preview.isNotEmpty()) msg.append("\n\n").append(preview)
        if (courses.size > 5) msg.append("\n…")
        if (notes.isNotEmpty()) msg.append("\n\n").append(notes.take(2).joinToString("\n"))

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.web_import_found_title)
            .setMessage(msg.toString())
            .setNegativeButton("稍后", null)
            .setPositiveButton(R.string.web_import_import) { _, _ -> goToResult(courses) }
            .show()
    }

    private fun goToResult(courses: List<Course>) {
        val intent = Intent(this, OcrResultActivity::class.java).apply {
            putExtra(OcrResultActivity.EXTRA_COURSES, CourseJson.encode(courses))
            putExtra(OcrResultActivity.EXTRA_RAW_TEXT, "")
        }
        startActivity(intent)
        finish()
    }

    /**
     * 诊断摘要文本（抓取失败弹窗 + 随时导出菜单共用）。
     * 只放"对定位解析问题有用"的信息：URL/标题/抓到多少表格与接口响应/解析备注/正文开头。
     */
    private fun diagnosticsSummary(payload: WebScheduleParser.Payload, notes: List<String>): String {
        val sb = StringBuilder()
        sb.append("URL：").append(payload.url).append('\n')
        sb.append("标题：").append(payload.title).append('\n')
        sb.append("表格：").append(payload.tables.size)
            .append(" 张 · 文本块：").append(payload.blocks.size)
            .append(" 个 · 接口响应：").append(payload.net.size).append(" 个\n")
        notes.take(10).forEach { sb.append("· ").append(it).append('\n') }
        val preview = payload.bodyText.take(200)
        if (preview.isNotBlank()) sb.append("\n页面文本（前 200 字）：\n").append(preview)
        return sb.toString()
    }

    /**
     * 右上角「⋯」菜单：**不管抓取成功还是失败**都能用。
     * 之前只有抓取失败才弹诊断框，导致"抓到了但数据不对"（比如全落在周二）时用户没法把真实数据回传。
     */
    private fun showExportMenu() {
        val payload = lastPayload
        val hasPayload = payload != null && lastRawPayload.isNotEmpty()
        // 菜单不再依赖「有没有抓到数据」：抓取彻底失败（JS 没跑起来 / 页面跨域）时，
        // 运行日志是唯一能说明问题在哪一层的东西。
        val items = mutableListOf<Pair<String, () -> Unit>>()
        // v1.39.0：默认先打开教务系统入口；打不开就自动沿候选链往下试（子域猜测 → 官网）
        items.add(getString(R.string.web_import_switch_school) to { openSchoolPicker() })
        val current = school
        if (current != null) {
            val entry = SchoolDirectory.entryOf(this, current)
            if (entry.isNotEmpty() && entry != binding.webView.url) {
                items.add(getString(R.string.web_import_open_jw) to { openUrl(entry) })
            }
            val home = SchoolDirectory.homepageOf(current)
            if (home.isNotEmpty() && home != binding.webView.url) {
                items.add(getString(R.string.web_import_home_site) to { openUrl(home) })
            }
            items.add(getString(R.string.web_import_find_jw) to { findJwFromHomepage(current) })
            items.add(getString(R.string.web_import_find_kb) to {
                scanLinks(binding.webView, kbLinkKeywords.toTypedArray())
            })
            items.add(getString(R.string.web_import_gateways) to { showGatewayDialog(current) })
        }
        items.add(getString(R.string.web_import_system_title) to { showSystemInfo() })
        if (hasPayload) {
            items.add(getString(R.string.web_import_diag_export) to { exportPayload() })
            items.add(getString(R.string.web_import_diag_copy) to {
                copyToClipboard(diagnosticsSummary(payload!!, lastNotes))
                Toast.makeText(this, R.string.web_import_diag_copied, Toast.LENGTH_SHORT).show()
            })
        }
        items.add("导出运行日志" to { exportLog() })
        items.add("复制运行日志" to {
            copyToClipboard(buildLogReport())
            Toast.makeText(this, R.string.web_import_diag_copied, Toast.LENGTH_SHORT).show()
        })
        items.add(getString(R.string.web_import_diag_fallback) to { fallbackToOcr() })
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.web_import_more_title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                items[which].second()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 「本校常用入口」：内置/手改的教务入口 + 按国内高校常见子域猜的入口 + 官网（打不开就回退官网找） */
    private fun showGatewayDialog(current: SchoolDirectory.School) {
        val candidates = SchoolDirectory.entryCandidates(current.jw, current.site)
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.web_import_school_site_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = candidates.map { it.first }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.web_import_gateways)
            .setMessage(R.string.web_import_gateways_hint)
            .setItems(labels) { _, which -> openUrl(candidates[which].second) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 抓不到时的降级：改用截图 OCR 导入 */
    private fun fallbackToOcr() {
        startActivity(
            Intent(this, ImportActivity::class.java)
                .putExtra(ImportActivity.EXTRA_AUTO_PICK, true)
        )
        finish()
    }

    /** 运行日志 + 诊断摘要拼成一份可直接发微信的纯文本 */
    private fun buildLogReport(): String {
        val sb = StringBuilder()
        sb.append("课格 ClassGrid ").append(BuildConfig.VERSION_NAME).append(" ").append(BuildConfig.CHANNEL)
            .append(" 抓取诊断").append('\n')
        sb.append("时间：")
            .append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
            .append('\n')
        sb.append("当前页面：").append(binding.webView.url ?: "-").append('\n')
        sb.append("页面加载完成：").append(pageFinished).append('\n')
        sb.append("已旁听到接口请求：").append(synchronized(apiRequests) { apiRequests.size }).append('\n')
        val p = lastPayload
        if (p == null) {
            sb.append("payload：未收到（注入脚本没有回调：页面没加载完 / 被 CSP 拦 / 课表在跨域 iframe 里）").append('\n')
        } else {
            sb.append("payload：表格=").append(p.tables.size)
                .append(" 文本块=").append(p.blocks.size)
                .append(" 接口响应=").append(p.net.size)
                .append(" 正文=").append(p.bodyText.length).append('\n')
            sb.append("页面标题：").append(p.title).append('\n')
            if (p.errors.isNotEmpty()) {
                sb.append("页面脚本报错：").append('\n')
                p.errors.forEach { sb.append("  - ").append(it).append('\n') }
            }
        }
        if (lastNotes.isNotEmpty()) {
            sb.append("解析备注：").append('\n')
            lastNotes.forEach { sb.append("  - ").append(it).append('\n') }
        }
        sb.append("运行日志：").append('\n').append(logDump())
        // v1.42.0：把原始 payload 一并带上（截断），这样"没解析出课程"时能直接看到页面到底给了哪些表格/文本块
        if (lastRawPayload.isNotEmpty()) {
            sb.append('\n').append("原始 payload（截断 ").append(MAX_REPLAY_BODY).append(" 字符）：").append('\n')
            sb.append(lastRawPayload.take(MAX_REPLAY_BODY))
        }
        return sb.toString()
    }

    /** 把运行日志导出成 txt 分享（抓取失败时用户唯一能回传的东西） */
    private fun exportLog() {
        try {
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "classgrid_log_" + System.currentTimeMillis() + ".txt")
            file.writeText(buildLogReport(), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ClassGrid 抓取运行日志")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "导出运行日志"))
        } catch (e: Exception) {
            log("导出运行日志失败：" + e)
            Toast.makeText(this, "导出失败：" + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    /** 抓不到时的诊断信息：方便用户反馈、也方便开发者迭代解析规则 */
    private fun showDiagnostics(payload: WebScheduleParser.Payload, notes: List<String>) {
        val summary = diagnosticsSummary(payload, notes)
        val items = arrayOf(
            getString(R.string.web_import_diag_export),
            getString(R.string.web_import_diag_copy),
            "导出运行日志",
            getString(R.string.web_import_diag_fallback)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.web_import_diag_title)
            .setMessage(summary)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> exportPayload()
                    1 -> {
                        copyToClipboard(summary)
                        Toast.makeText(this, R.string.web_import_diag_copied, Toast.LENGTH_SHORT).show()
                    }
                    2 -> exportLog()
                    else -> fallbackToOcr()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /**
     * 把这次抓到的原始数据整包导出成 JSON 分享出去。
     * 抓取失败时这是唯一能把"用户设备上的真实网页数据"回传给开发者的办法：
     * 里面有接口响应原文 + 全部表格 + 文本块坐标，拿回来就能离线复现整套解析流程。
     */
    private fun exportPayload() {
        val raw = lastRawPayload
        if (raw.isEmpty()) {
            Toast.makeText(this, R.string.web_import_diag_export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "classgrid_capture_" + System.currentTimeMillis() + ".json")
            file.writeText(raw, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "ClassGrid 抓取数据")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.web_import_diag_export)))
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败：" + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 改网址：用户最需要的能力 —— 名单里别的学校的网址可能填错，学校也可能换了域名。
     * 改完按学校记住（[SchoolDirectory.rememberSite]），下次点这所学校直接打开新地址。
     */
    private fun showUrlDialog() {
        val current = binding.webView.url?.takeIf { it.startsWith("http", true) }
            ?: school?.let { SchoolDirectory.entryOf(this, it) }.orEmpty()
        val input = EditText(this).apply {
            setText(current)
            setSelection(text.length)
            hint = getString(R.string.web_import_url_hint)
            setSingleLine()
        }
        val hintView = TextView(this).apply {
            text = buildString {
                val s = school
                if (s != null) append("当前学校：").append(s.name).append('\n')
                append("网址填错、或学校换了域名都可以直接改；改完会记住，下次点这所学校就用新网址。")
            }
            textSize = 12f
            setTextColor(
                AppTheme.textMuted(
                    this@WebImportActivity,
                    ScheduleColor.isDarkMode(this@WebImportActivity)
                )
            )
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
            addView(hintView)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.web_import_url_title)
            .setView(box)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.web_import_url_choose) { _, _ -> openSchoolPicker() }
            .setPositiveButton(R.string.web_import_url_confirm) { _, _ ->
                openUrl(input.text.toString(), remember = true)
            }
            .show()
    }

    /**
     * 在 WebView 里打开一个网址。
     * [remember] 只在用户手填网址时给 true：手填是用户的明确选择，要按学校记住；
     * 「回到官网 / 常用入口」只是这次跳转，不能把猜测出来的入口写成这所学校以后的默认网址。
     */
    private fun openUrl(raw: String, remember: Boolean = false) {
        val url = SchoolDirectory.normalizeUrl(raw)
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.web_import_url_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        val s = school
        if (remember && s != null) {
            SchoolDirectory.rememberSite(this, s, url)
            log("记住 " + s.name + " 的网址：" + url)
            Toast.makeText(this, R.string.web_import_url_remembered, Toast.LENGTH_SHORT).show()
        }
        // 手填/站内跳转是明确目标：清掉回退链，别让它把用户带到别的入口去
        entryChain.clear()
        entryIndex = -1
        pendingEntryUrl = null
        resetState()
        binding.webView.loadUrl(url)
        setStatus(getString(R.string.web_import_loading))
    }

    private fun openSchoolPicker() {
        pickSchool.launch(SchoolPickerActivity.intent(this))
    }

    /** 标题显示当前学校：抓到一半也能随时确认抓的是哪所学校 */
    private fun applySchool() {
        val s = school
        binding.tvTitle.text = if (s != null) s.name else getString(R.string.web_import_title)
    }

    // ------------------------------------------------------------------ 入口链 / 找入口（v1.39.0）

    /** 课表相关链接关键词：「找课表页面」用 */
    private val kbLinkKeywords = listOf(
        "课表", "我的课表", "学生课表", "课表查询", "课表信息", "周课表",
        "课程表", "schedule", "timetable", "kbcx", "xskb"
    )

    /** 教务入口关键词：「在官网里找教务系统」用 */
    private val jwLinkKeywords = listOf(
        "教务", "教务系统", "教务管理", "选课", "办事大厅", "服务大厅",
        "统一身份认证", "本科生院", "教学管理", "jwxt", "jwgl", "ehall", "cas", "sso"
    )

    /**
     * 从这所学校的「教务入口 → 子域猜测 → 官网」链的头部开始加载。
     * [first] 是外部明确给的地址（选校页点进来的 = entryOf，或手填网址），永远排第一。
     */
    private fun startEntry(first: String) {
        entryChain.clear()
        entryChain.add(first)
        val s = school
        if (s != null) {
            SchoolDirectory.entryCandidates(s.jw, s.site).forEach { (_, url) ->
                if (!entryChain.contains(url)) entryChain.add(url)
            }
        }
        entryIndex = -1
        loadEntry(0)
    }

    private fun loadEntry(index: Int) {
        val url = entryChain.getOrNull(index) ?: return
        entryIndex = index
        pendingEntryUrl = SchoolDirectory.normalizeUrl(url)
        resetState()
        binding.webView.loadUrl(url)
        setStatus(getString(R.string.web_import_loading))
    }

    /** 只有「我们自己的候选入口」加载失败才自动换下一个；用户站内点链接失败不换 */
    private fun shouldFallback(failedUrl: String): Boolean {
        val pending = pendingEntryUrl ?: return false
        return failedUrl.startsWith(pending, true) && entryIndex in 0 until entryChain.lastIndex
    }

    /** 换下一个候选入口；全试完就提示用户手动填网址 */
    private fun tryNextEntry(failedUrl: String, reason: String) {
        val nextUrl = entryChain.getOrNull(entryIndex + 1)
        if (nextUrl == null) {
            log("入口全部失败：" + reason)
            setStatus(getString(R.string.web_import_entry_exhausted))
            pendingEntryUrl = null
            return
        }
        log("入口打不开（" + reason + "）：" + failedUrl + " → 换 " + nextUrl)
        setStatus(
            getString(R.string.web_import_entry_fallback, SchoolDirectory.displaySite(failedUrl))
        )
        loadEntry(entryIndex + 1)
    }

    /** 「在官网里找教务系统」：加载官网，加载完扫链接（很多学校官网有「教务系统」快捷入口） */
    private fun findJwFromHomepage(current: SchoolDirectory.School) {
        val home = SchoolDirectory.homepageOf(current)
        if (home.isEmpty()) {
            Toast.makeText(this, R.string.web_import_school_site_missing, Toast.LENGTH_SHORT).show()
            return
        }
        pendingScan = jwLinkKeywords.toTypedArray()
        openUrl(home)
    }

    /** 扫描当前页面的 <a> 链接，让用户点一个打开（框架页/入口藏在页面里时很有用） */
    private fun scanLinks(view: WebView, keywords: Array<String>) {
        val kws = org.json.JSONArray()
        keywords.forEach { kws.put(it) }
        val js = buildString {
            append("(function(){")
            append("var kws=").append(kws).append(";")
            append("var out=[],seen={},docs=[document];")
            // 很多教务系统是「框架页」：课表入口在 iframe 里，只扫主文档会漏
            append("try{var fs=document.querySelectorAll('iframe,frame');")
            append("for(var f=0;f<fs.length;f++){try{var d=fs[f].contentDocument;")
            append("if(d&&docs.indexOf(d)<0)docs.push(d);}catch(e){}}}catch(e){}")
            append("for(var di=0;di<docs.length;di++){")
            append("var nodes=docs[di].querySelectorAll('a[href]');")
            append("for(var i=0;i<nodes.length&&out.length<50;i++){")
            append("var a=nodes[i],t=(a.textContent||'').replace(/\\s+/g,' ').trim(),h=a.href||'';")
            append("if(!/^https?:/i.test(h))continue;")
            append("var hay=(t+' '+h).toLowerCase(),hit=false;")
            append("for(var j=0;j<kws.length;j++){")
            append("if(hay.indexOf(String(kws[j]).toLowerCase())>=0){hit=true;break;}}")
            append("if(!hit||seen[h])continue;seen[h]=1;out.push({t:t.slice(0,48),h:h});")
            append("}}")
            append("return out;})()")
        }
        view.evaluateJavascript(js) { raw ->
            val items = runCatching { org.json.JSONArray(raw) }.getOrNull()
            if (items == null || items.length() == 0) {
                Toast.makeText(this, R.string.web_import_find_none, Toast.LENGTH_LONG).show()
                return@evaluateJavascript
            }
            val labels = ArrayList<String>()
            val urls = ArrayList<String>()
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                val url = obj.optString("h")
                if (url.isBlank()) continue
                labels.add(obj.optString("t").ifBlank { url })
                urls.add(url)
            }
            if (labels.isEmpty()) {
                Toast.makeText(this, R.string.web_import_find_none, Toast.LENGTH_LONG).show()
                return@evaluateJavascript
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.web_import_find_title, labels.size))
                .setItems(labels.toTypedArray()) { _, which -> openUrl(urls[which]) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    /** 「教务系统判断」：告诉用户这是哪套系统、课表在哪个菜单下 */
    private fun showSystemInfo() {
        val family = WebSystem.detect(binding.webView.url, binding.webView.title)
        val label = WebSystem.label(this, family)
        val hint = WebSystem.hint(this, family)
        log("查看系统判断：" + label)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.web_import_system_title)
            .setMessage(getString(R.string.web_import_system_msg, label, hint))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private fun resetState() {
        log("重置抓取状态")
        lastCourses = emptyList()
        lastPayloadFingerprint = ""
        lastRawPayload = ""
        lastPayload = null
        replayTried = false
    }

    // ------------------------------------------------------------------ 接口请求旁听 / 重放

    /** 页面加载的资源里哪些看着像课表数据接口（路径含这些词） */
    private val apiUrlHint = Regex(
        """(kb|kcb|kbcx|kbsp|course|schedule|timetable|curriculum|xsall|xsgr|xsxx|jcsj|query)""",
        RegexOption.IGNORE_CASE
    )

    /** 明显不是数据接口的资源：静态文件与登录/退出接口不记录（后者重放会有副作用） */
    private val skipUrlHint = Regex(
        """(\.js|\.css|\.png|\.jpe?g|\.gif|\.svg|\.ico|\.woff2?|\.ttf|/static/|login|logout|sso)""",
        RegexOption.IGNORE_CASE
    )

    private fun rememberApiRequest(url: String) {
        if (!url.startsWith("http")) return
        if (!apiUrlHint.containsMatchIn(url)) return
        if (skipUrlHint.containsMatchIn(url)) return
        synchronized(apiRequests) {
            if (apiRequests.containsKey(url)) return
            apiRequests[url] = true
            while (apiRequests.size > API_REQUEST_LIMIT) {
                val oldest = apiRequests.keys.firstOrNull() ?: break
                apiRequests.remove(oldest)
            }
        }
    }

    /**
     * 用系统 Cookie 把旁听到的接口请求重放一遍，喂给 [WebJsonParser]。
     *
     * 只在「用户点了抓取、但 JS 两条路都没拿到课程」时才调用；结果通过 [onDone] 回主线程。
     */
    private fun tryReplayApiRequests(notes: MutableList<String>, onDone: (List<Course>) -> Unit) {
        val urls = synchronized(apiRequests) { apiRequests.keys.toList() }
        if (urls.isEmpty()) {
            onDone(emptyList())
            return
        }
        setStatus(getString(R.string.web_import_replay))
        netExecutor.execute {
            val docs = ArrayList<WebScheduleParser.NetDoc>()
            for (u in urls) {
                val body = fetchQuietly(u) ?: continue
                if (body.isBlank()) continue
                docs.add(WebScheduleParser.NetDoc(url = u, status = 200, body = body))
            }
            val courses = if (docs.isEmpty()) {
                emptyList()
            } else {
                try {
                    WebJsonParser.parse(docs, SectionTimes.load(this), notes)
                } catch (e: Exception) {
                    notes.add("接口重放解析异常：" + (e.message ?: e.javaClass.simpleName))
                    emptyList()
                }
            }
            if (courses.isNotEmpty()) {
                notes.add(0, getString(R.string.web_import_source_replay, docs.size))
            }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) onDone(courses)
            }
        }
    }

    /** 重放一个 GET；任何异常都返回 null（抓取流程不能因为某个接口超时就崩） */
    private fun fetchQuietly(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
                binding.webView.settings.userAgentString?.let { setRequestProperty("User-Agent", it) }
                CookieManager.getInstance().getCookie(url)?.let { setRequestProperty("Cookie", it) }
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.isEmpty()) {
                null
            } else {
                val capped = if (bytes.size > MAX_REPLAY_BODY) bytes.copyOf(MAX_REPLAY_BODY) else bytes
                String(capped, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (e: Exception) {
                // 关连接失败不影响结果
            }
        }
    }

    private fun setStatus(text: String) {
        binding.tvStatus.text = text
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("classgrid_web_diagnostics", text))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
