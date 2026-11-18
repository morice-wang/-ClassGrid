package com.classgrid.app

/**
 * 界面主题（色系）抽象。
 *
 * 设计原则（v1.34.0）：
 * - **零回归**：默认仍是「经典」，所有色值与原硬编码值逐字节一致；老用户升级后看到的界面不变。
 * - **只换颜色**：主题只决定色板（主色/背景/卡片/课程 9 色/状态色）与卡片几何参数，
 *   不改布局逻辑、不改数据、不改交互。
 * - **单一入口**：代码里不要再散写 `0xFF...`，统一走 `AppTheme.current(context)` 取色；
 *   新增主题只需在这里加一个 [Spec]，然后加进 [ALL]。
 *
 * 注意：ScheduleView 在主线程 onDraw 里取色，所以 [current] 只做一次 SharedPreferences 读取
 * （`getInt`），并缓存结果；设置页切换主题后调用 [invalidate] 让缓存失效。
 */
object AppTheme {

    const val CLASSIC = 0
    const val JADE = 1
    const val AURORA = 2

    // ==================== 布局风格（v1.35.0）====================
    // 用户反馈：「颜色改了跟没改一样，改动幅度不要只局限于颜色」。
    // 所以主题 = 色板 + 布局风格（LayoutStyle）两层：
    // - 经典保留原布局原色板，保证老用户零变化；
    // - 新主题改布局（卡片形态、导航与顶栏、日程页排版、字号节奏）。
    //
    // 注意 v1.34.x 的极光崩溃（点日程闪退 + 主题自己回退成墨玉）已经定位并修掉：
    // 根因是 setTheme 里 `coerceIn(0, ALL.size - 1)` 顶不住「重建期间再取一次主题」的瞬态，
    // 以及极光图标尚未真正启用就写了 prefs。详见 Prefs.setTheme 与 showThemePicker 的注释。

    /** 卡片形态：决定课表卡片与日程卡片怎么画 */
    const val CARD_STYLE_ROUNDED = 0      // 顶色帽 + 圆角（经典/墨玉）
    const val CARD_STYLE_STRIPE = 1       // 白底 + 左侧粗色条 + 细描边（极光）
    const val CARD_STYLE_GHOST = 2        // 无背板纯文字 + 左侧 3dp 细线
    const val CARD_STYLE_PILL = 3         // 大圆角胶囊 + 柔和底色

    /** 顶栏形态 */
    const val HEADER_PLAIN = 0            // 白底 + 阴影（经典/墨玉）
    const val HEADER_LARGE = 1            // 大标题 + 胶囊周切换（极光）

    /** 底部导航形态 */
    const val NAV_BOTTOM = 0              // 原生 BottomNavigationView（经典/墨玉）
    const val NAV_PILL = 1                // 悬浮胶囊导航（极光）

    /** 日程页排版 */
    const val AGENDA_COMFORT = 0          // 宽松单列（经典/墨玉）
    const val AGENDA_TIMELINE = 1         // 时间轴双栏 + 更密的信息层级（极光）

    /** 字号节奏（sp），主题可整体调整层次感 */
    data class TypeScale(
        val titleSp: Float,       // 顶栏主标题
        val dateSp: Float,        // 日程页日期大标题
        val cardNameSp: Float,    // 日程卡片课程名
        val bodySp: Float,        // 正文 / 列表项
        val captionSp: Float      // 说明文字
    )

    /** 布局风格：与色板正交，决定形状、间距、排版与卡片画法 */
    data class LayoutStyle(
        val cardStyle: Int,
        val cardRadiusDp: Float,
        /** 左侧色条宽度（dp），仅 STRIPE / GHOST 用 */
        val cardStripeDp: Float,
        val cardShadow: Boolean,
        val headerStyle: Int,
        val navStyle: Int,
        val agendaStyle: Int,
        val type: TypeScale,
        /** 课表卡片圆角 */
        val scheduleRadiusDp: Float,
        /** 课表卡片顶部色帽高度（dp），仅 ROUNDED 用 */
        val scheduleCapDp: Float
    )

    data class Spec(
        val id: Int,
        /** 设置页显示名 */
        val label: String,
        /** 设置页副标题（一句话说明风格） */
        val summary: String,

        // ---- 品牌 / 强调色 ----
        val primaryLight: Int,
        val primaryDark: Int,
        val onPrimary: Int,
        val containerLight: Int,
        val onContainerLight: Int,
        val containerDark: Int,
        val onContainerDark: Int,

        // ---- 页面背景 ----
        val windowLight: Int,
        val windowDark: Int,

        // ---- 顶栏 / 卡片 / 文字 ----
        /** 顶栏背景 */
        val headerLight: Int,
        val headerDark: Int,
        /** 卡片表面（日程卡片、设置项、课表表头底） */
        val cardLight: Int,
        val cardDark: Int,
        /** 卡片描边 / 网格线 */
        val lineLight: Int,
        val lineDark: Int,
        /** 主文字 */
        val textLight: Int,
        val textDark: Int,
        /** 次要文字 */
        val textMutedLight: Int,
        val textMutedDark: Int,

        // ---- 日程页状态色 ----
        /** 待办强调色（原 0xFFF59E0B 琥珀） */
        val todoAccent: Int,
        /** 「上课中…」胶囊（原 0xFF7C3AED 紫） */
        val statusOngoing: Int,
        /** 「下一节课」胶囊（原 0xFF10B981 绿） */
        val statusUpcoming: Int,

        // ---- 课表网格 ----
        /** 课表默认背景（跟随主题时） */
        val gridBgLight: Int,
        val gridBgDark: Int,
        /** 表头行背景 */
        val gridHeaderLight: Int,
        val gridHeaderDark: Int,
        /** 节次文字 */
        val sectionTextLight: Int,
        val sectionTextDark: Int,

        // ---- 课程卡片（深色模式下色族需更深的色底 + 更亮的字）----
        val courseBgLight: List<Int>,
        val courseFgLight: List<Int>,
        val courseAccentLight: List<Int>,
        val courseBgDark: List<Int>,
        val courseFgDark: List<Int>,
        val courseAccentDark: List<Int>,

        /** 非本周课程卡片淡化色 */
        val inactiveBgLight: Int,
        val inactiveBgDark: Int,
        val inactiveFgLight: Int,
        val inactiveFgDark: Int,

        /** 布局风格（形状/排版/卡片画法） */
        val layout: LayoutStyle
    )

    /** 经典主题：v1.30–v1.33 的原样配色。数值必须与历史硬编码一致，否则老用户界面会变。 */
    private val classic = Spec(
        id = CLASSIC,
        label = "经典",
        summary = "原本的紫色系，升级后样式完全不变",
        primaryLight = 0xFF4F46E5.toInt(),
        primaryDark = 0xFF6366F1.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        containerLight = 0xFFE0E7FF.toInt(),
        onContainerLight = 0xFF1E1B4B.toInt(),
        containerDark = 0xFF312E81.toInt(),
        onContainerDark = 0xFFE0E7FF.toInt(),
        windowLight = 0xFFF7F8FC.toInt(),
        windowDark = 0xFF0F1117.toInt(),
        headerLight = 0xFFFFFFFF.toInt(),
        headerDark = 0xFF151823.toInt(),
        cardLight = 0xFFFFFFFF.toInt(),
        cardDark = 0xFF1A1F2B.toInt(),
        lineLight = 0xFFEEF0F5.toInt(),
        lineDark = 0xFF262B38.toInt(),
        textLight = 0xFF1F2937.toInt(),
        textDark = 0xFFE5E7EB.toInt(),
        textMutedLight = 0xFF6B7280.toInt(),
        textMutedDark = 0xFF9AA3B2.toInt(),
        todoAccent = 0xFFF59E0B.toInt(),
        statusOngoing = 0xFF7C3AED.toInt(),
        statusUpcoming = 0xFF10B981.toInt(),
        gridBgLight = 0xFFF4F5F6.toInt(),
        gridBgDark = 0xFF0F1115.toInt(),
        gridHeaderLight = 0xFFFFFFFF.toInt(),
        gridHeaderDark = 0xFF151823.toInt(),
        sectionTextLight = 0xFF6B7280.toInt(),
        sectionTextDark = 0xFF8A93A5.toInt(),
        courseBgLight = listOf(
            0xFFDBEAFE.toInt(), 0xFFD1FAE5.toInt(), 0xFFFFEDD5.toInt(), 0xFFFFE4E6.toInt(), 0xFFEDE9FE.toInt(),
            0xFFCFFAFE.toInt(), 0xFFFCE7F3.toInt(), 0xFFFEF3C7.toInt(), 0xFFCCFBF1.toInt(), 0xFFE2E8F0.toInt()
        ),
        courseFgLight = listOf(
            0xFF1E40AF.toInt(), 0xFF065F46.toInt(), 0xFF9A3412.toInt(), 0xFF9F1239.toInt(), 0xFF5B21B6.toInt(),
            0xFF155E75.toInt(), 0xFF9D174D.toInt(), 0xFF92400E.toInt(), 0xFF115E59.toInt(), 0xFF334155.toInt()
        ),
        courseAccentLight = listOf(
            0xFF3B82F6.toInt(), 0xFF10B981.toInt(), 0xFFF97316.toInt(), 0xFFF43F5E.toInt(), 0xFF8B5CF6.toInt(),
            0xFF06B6D4.toInt(), 0xFFEC4899.toInt(), 0xFFF59E0B.toInt(), 0xFF14B8A6.toInt(), 0xFF64748B.toInt()
        ),
        courseBgDark = listOf(
            0xFF1E3A5F.toInt(), 0xFF064E3B.toInt(), 0xFF7C2D12.toInt(), 0xFF881337.toInt(), 0xFF4C1D95.toInt(),
            0xFF164E63.toInt(), 0xFF831843.toInt(), 0xFF713F12.toInt(), 0xFF134E4A.toInt(), 0xFF1E293B.toInt()
        ),
        courseFgDark = listOf(
            0xFFBFDBFE.toInt(), 0xFFA7F3D0.toInt(), 0xFFFED7AA.toInt(), 0xFFFECDD3.toInt(), 0xFFDDD6FE.toInt(),
            0xFFA5F3FC.toInt(), 0xFFFBCFE8.toInt(), 0xFFFDE68A.toInt(), 0xFF99F6E4.toInt(), 0xFFCBD5E1.toInt()
        ),
        courseAccentDark = listOf(
            0xFF60A5FA.toInt(), 0xFF34D399.toInt(), 0xFFFB923C.toInt(), 0xFFFB7185.toInt(), 0xFFA78BFA.toInt(),
            0xFF22D3EE.toInt(), 0xFFF472B6.toInt(), 0xFFFBBF24.toInt(), 0xFF2DD4BF.toInt(), 0xFF94A3B8.toInt()
        ),
        inactiveBgLight = 0xFFEDEFF5.toInt(),
        inactiveBgDark = 0xFF26303F.toInt(),
        inactiveFgLight = 0xFF9AA1AE.toInt(),
        inactiveFgDark = 0xFF6B7280.toInt(),
        layout = LayoutStyle(
            cardStyle = CARD_STYLE_ROUNDED,
            cardRadiusDp = 10f,
            cardStripeDp = 3f,
            cardShadow = true,
            headerStyle = HEADER_PLAIN,
            navStyle = NAV_BOTTOM,
            agendaStyle = AGENDA_COMFORT,
            type = TypeScale(titleSp = 22f, dateSp = 20f, cardNameSp = 16f, bodySp = 14f, captionSp = 12f),
            scheduleRadiusDp = 10f,
            scheduleCapDp = 5f
        )
    )

    /** 墨玉：深青主色 + 冷白底，对比度最高、最沉稳；课程色族偏「砖红/琥珀/湖蓝」。 */
    private val jade = Spec(
        id = JADE,
        label = "墨玉",
        summary = "深青主色 + 冷白底，沉稳清晰、对比度最高",
        primaryLight = 0xFF0F766E.toInt(),
        primaryDark = 0xFF2DD4BF.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        containerLight = 0xFFCCFBF1.toInt(),
        onContainerLight = 0xFF134E4A.toInt(),
        containerDark = 0xFF134E4A.toInt(),
        onContainerDark = 0xFFCCFBF1.toInt(),
        windowLight = 0xFFF8FAFB.toInt(),
        windowDark = 0xFF0B1315.toInt(),
        headerLight = 0xFFFFFFFF.toInt(),
        headerDark = 0xFF10201F.toInt(),
        cardLight = 0xFFFFFFFF.toInt(),
        cardDark = 0xFF14201F.toInt(),
        lineLight = 0xFFE6ECEC.toInt(),
        lineDark = 0xFF233432.toInt(),
        textLight = 0xFF12211F.toInt(),
        textDark = 0xFFE6F2F0.toInt(),
        textMutedLight = 0xFF5C6B69.toInt(),
        textMutedDark = 0xFF8FA7A3.toInt(),
        todoAccent = 0xFFB45309.toInt(),
        statusOngoing = 0xFF0F766E.toInt(),
        statusUpcoming = 0xFF2F855A.toInt(),
        gridBgLight = 0xFFF4F8F7.toInt(),
        gridBgDark = 0xFF0B1315.toInt(),
        gridHeaderLight = 0xFFFFFFFF.toInt(),
        gridHeaderDark = 0xFF10201F.toInt(),
        sectionTextLight = 0xFF5C6B69.toInt(),
        sectionTextDark = 0xFF8FA7A3.toInt(),
        courseBgLight = listOf(
            0xFFCCFBF1.toInt(), 0xFFFEE2E2.toInt(), 0xFFFEF3C7.toInt(), 0xFFE0F2FE.toInt(), 0xFFEDE9FE.toInt(),
            0xFFDCFCE7.toInt(), 0xFFFCE7F3.toInt(), 0xFFE7E5E4.toInt(), 0xFFCFFAFE.toInt(), 0xFFE5E7EB.toInt()
        ),
        courseFgLight = listOf(
            0xFF115E59.toInt(), 0xFF991B1B.toInt(), 0xFF92400E.toInt(), 0xFF075985.toInt(), 0xFF4C1D95.toInt(),
            0xFF166534.toInt(), 0xFF9D174D.toInt(), 0xFF44403C.toInt(), 0xFF155E75.toInt(), 0xFF374151.toInt()
        ),
        courseAccentLight = listOf(
            0xFF0F766E.toInt(), 0xFFDC2626.toInt(), 0xFFD97706.toInt(), 0xFF0284C7.toInt(), 0xFF7C3AED.toInt(),
            0xFF16A34A.toInt(), 0xFFDB2777.toInt(), 0xFF78716C.toInt(), 0xFF0891B2.toInt(), 0xFF6B7280.toInt()
        ),
        courseBgDark = listOf(
            0xFF0F3D3A.toInt(), 0xFF4C1D1D.toInt(), 0xFF452B0D.toInt(), 0xFF0C3A51.toInt(), 0xFF2E2266.toInt(),
            0xFF113D20.toInt(), 0xFF4A1230.toInt(), 0xFF2B2A28.toInt(), 0xFF0E3B45.toInt(), 0xFF24303A.toInt()
        ),
        courseFgDark = listOf(
            0xFFA7F3D0.toInt(), 0xFFFECACA.toInt(), 0xFFFDE68A.toInt(), 0xFFBAE6FD.toInt(), 0xFFDDD6FE.toInt(),
            0xFFBBF7D0.toInt(), 0xFFFBCFE8.toInt(), 0xFFE7E5E4.toInt(), 0xFFA5F3FC.toInt(), 0xFFD1D5DB.toInt()
        ),
        courseAccentDark = listOf(
            0xFF2DD4BF.toInt(), 0xFFF87171.toInt(), 0xFFFBBF24.toInt(), 0xFF38BDF8.toInt(), 0xFFA78BFA.toInt(),
            0xFF4ADE80.toInt(), 0xFFF472B6.toInt(), 0xFFA8A29E.toInt(), 0xFF22D3EE.toInt(), 0xFF94A3B8.toInt()
        ),
        inactiveBgLight = 0xFFEDF2F1.toInt(),
        inactiveBgDark = 0xFF1A2524.toInt(),
        inactiveFgLight = 0xFF9AA8A6.toInt(),
        inactiveFgDark = 0xFF6B7C7A.toInt(),
        layout = LayoutStyle(
            cardStyle = CARD_STYLE_ROUNDED,
            cardRadiusDp = 12f,
            cardStripeDp = 3f,
            cardShadow = true,
            headerStyle = HEADER_PLAIN,
            navStyle = NAV_BOTTOM,
            agendaStyle = AGENDA_COMFORT,
            type = TypeScale(titleSp = 22f, dateSp = 21f, cardNameSp = 16f, bodySp = 14f, captionSp = 12f),
            scheduleRadiusDp = 12f,
            scheduleCapDp = 6f
        )
    )

    /** 极光：靛蓝→青色渐变强调，圆角更饱满，课程色族是清晰的二分色系。 */
    private val aurora = Spec(
        id = AURORA,
        label = "极光",
        summary = "靛蓝到青色的渐变强调，年轻活泼、卡片更圆润",
        primaryLight = 0xFF4338CA.toInt(),
        primaryDark = 0xFF818CF8.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        containerLight = 0xFFE0E7FF.toInt(),
        onContainerLight = 0xFF1E1B4B.toInt(),
        containerDark = 0xFF1E1B4B.toInt(),
        onContainerDark = 0xFFC7D2FE.toInt(),
        windowLight = 0xFFF6F7FB.toInt(),
        windowDark = 0xFF0B1020.toInt(),
        headerLight = 0xFFFFFFFF.toInt(),
        headerDark = 0xFF121A2E.toInt(),
        cardLight = 0xFFFFFFFF.toInt(),
        cardDark = 0xFF161F36.toInt(),
        lineLight = 0xFFE8EBF3.toInt(),
        lineDark = 0xFF232D47.toInt(),
        textLight = 0xFF101828.toInt(),
        textDark = 0xFFE5EAF5.toInt(),
        textMutedLight = 0xFF5B6577.toInt(),
        textMutedDark = 0xFF95A3BD.toInt(),
        todoAccent = 0xFFEA580C.toInt(),
        statusOngoing = 0xFF4338CA.toInt(),
        statusUpcoming = 0xFF0891B2.toInt(),
        gridBgLight = 0xFFF1F3FA.toInt(),
        gridBgDark = 0xFF0B1020.toInt(),
        gridHeaderLight = 0xFFFFFFFF.toInt(),
        gridHeaderDark = 0xFF121A2E.toInt(),
        sectionTextLight = 0xFF5B6577.toInt(),
        sectionTextDark = 0xFF95A3BD.toInt(),
        courseBgLight = listOf(
            0xFFE0E7FF.toInt(), 0xFFCFFAFE.toInt(), 0xFFEDE9FE.toInt(), 0xFFDCFCE7.toInt(), 0xFFFEF3C7.toInt(),
            0xFFFCE7F3.toInt(), 0xFFE0F2FE.toInt(), 0xFFFFE4E6.toInt(), 0xFFCCFBF1.toInt(), 0xFFE2E8F0.toInt()
        ),
        courseFgLight = listOf(
            0xFF3730A3.toInt(), 0xFF155E75.toInt(), 0xFF5B21B6.toInt(), 0xFF166534.toInt(), 0xFF92400E.toInt(),
            0xFF9D174D.toInt(), 0xFF075985.toInt(), 0xFF9F1239.toInt(), 0xFF115E59.toInt(), 0xFF334155.toInt()
        ),
        courseAccentLight = listOf(
            0xFF4F46E5.toInt(), 0xFF06B6D4.toInt(), 0xFF8B5CF6.toInt(), 0xFF22C55E.toInt(), 0xFFF59E0B.toInt(),
            0xFFEC4899.toInt(), 0xFF0EA5E9.toInt(), 0xFFF43F5E.toInt(), 0xFF14B8A6.toInt(), 0xFF64748B.toInt()
        ),
        courseBgDark = listOf(
            0xFF312E81.toInt(), 0xFF164E63.toInt(), 0xFF4C1D95.toInt(), 0xFF14532D.toInt(), 0xFF713F12.toInt(),
            0xFF831843.toInt(), 0xFF0C4A6E.toInt(), 0xFF881337.toInt(), 0xFF134E4A.toInt(), 0xFF1E293B.toInt()
        ),
        courseFgDark = listOf(
            0xFFC7D2FE.toInt(), 0xFFA5F3FC.toInt(), 0xFFDDD6FE.toInt(), 0xFFBBF7D0.toInt(), 0xFFFDE68A.toInt(),
            0xFFFBCFE8.toInt(), 0xFFBAE6FD.toInt(), 0xFFFECDD3.toInt(), 0xFF99F6E4.toInt(), 0xFFCBD5E1.toInt()
        ),
        courseAccentDark = listOf(
            0xFF818CF8.toInt(), 0xFF22D3EE.toInt(), 0xFFA78BFA.toInt(), 0xFF4ADE80.toInt(), 0xFFFBBF24.toInt(),
            0xFFF472B6.toInt(), 0xFF38BDF8.toInt(), 0xFFFB7185.toInt(), 0xFF2DD4BF.toInt(), 0xFF94A3B8.toInt()
        ),
        inactiveBgLight = 0xFFEDEFF7.toInt(),
        inactiveBgDark = 0xFF1B2438.toInt(),
        inactiveFgLight = 0xFF9AA3B8.toInt(),
        inactiveFgDark = 0xFF6B7A93.toInt(),
        // 极光：布局也换一套——大标题顶栏 + 悬浮胶囊导航 + 卡片左侧色条 + 时间轴日程。
        layout = LayoutStyle(
            cardStyle = CARD_STYLE_STRIPE,
            cardRadiusDp = 16f,
            cardStripeDp = 5f,
            cardShadow = true,
            headerStyle = HEADER_LARGE,
            navStyle = NAV_PILL,
            agendaStyle = AGENDA_TIMELINE,
            type = TypeScale(titleSp = 28f, dateSp = 26f, cardNameSp = 17f, bodySp = 15f, captionSp = 12f),
            scheduleRadiusDp = 14f,
            scheduleCapDp = 0f
        )
    )

    val ALL: List<Spec> = listOf(classic, jade, aurora)

    fun of(id: Int): Spec = ALL.getOrElse(id) { classic }

    @Volatile
    private var cachedId: Int = -1

    /** 当前生效主题。onDraw 里高频调用，故只读一次 prefs 后缓存。 */
    fun current(context: android.content.Context): Spec {
        val id = Prefs.theme(context)
        if (id != cachedId) cachedId = id
        return of(id)
    }

    /** 设置页切换主题后调用，让 [current] 的缓存失效 */
    fun invalidate() {
        cachedId = -1
    }

    /** 便于写「跟随深浅色」的取色：light/dark 二选一 */
    fun pick(dark: Boolean, light: Int, darkColor: Int): Int = if (dark) darkColor else light

    /**
     * 主题 + 布局的签名：主题 id 与布局风格任一变化都返回不同的值。
     * MainActivity 用它判断要不要在 onResume 里重建界面。
     *
     * v1.34.x 的 bug 就出在这一带：MainActivity 只记了主题 id，
     * 而 IconSwitcher 切图标会 disable/enable 组件、触发额外的 Activity 重建，
     * 用户于是看到「选项还是极光、界面却变回墨玉」。现在统一以签名比对。
     */
    fun signature(context: android.content.Context): Int {
        val spec = current(context)
        return spec.id * 31 + spec.layout.cardStyle
    }

    // ---------- 语义取色（调用处只关心用途，不关心具体色值）----------
    fun primary(context: android.content.Context, dark: Boolean): Int =
        current(context).let { pick(dark, it.primaryLight, it.primaryDark) }

    fun windowBackground(context: android.content.Context, dark: Boolean): Int =
        current(context).let { pick(dark, it.windowLight, it.windowDark) }

    fun headerBackground(context: android.content.Context, dark: Boolean): Int =
        current(context).let { pick(dark, it.headerLight, it.headerDark) }

    fun cardBackground(context: android.content.Context, dark: Boolean): Int =
        current(context).let { pick(dark, it.cardLight, it.cardDark) }

    fun line(context: android.content.Context, dark: Boolean): Int =
        current(context).let { pick(dark, it.lineLight, it.lineDark) }

    fun text(context: android.content.Context, dark: Boolean): Int =
        current(context).let { pick(dark, it.textLight, it.textDark) }

    fun textMuted(context: android.content.Context, dark: Boolean): Int =
        current(context).let { pick(dark, it.textMutedLight, it.textMutedDark) }

    // ---------- 布局风格的便捷取色 ----------
    fun layout(context: android.content.Context): LayoutStyle = current(context).layout

    /** 课表背景可选项：默认（跟随主题网格底）+ 奶油/天蓝/薄荷/绯粉四款 */
    fun backgroundOptions(context: android.content.Context, dark: Boolean): List<Int> {
        val spec = current(context)
        val base = pick(dark, spec.gridBgLight, spec.gridBgDark)
        return if (dark) {
            listOf(base, 0xFF141210.toInt(), 0xFF0E1620.toInt(), 0xFF0E1A14.toInt(), 0xFF1A1014.toInt())
        } else {
            listOf(base, 0xFFFBF7EE.toInt(), 0xFFF0F6FC.toInt(), 0xFFF0F8F2.toInt(), 0xFFFBF1F3.toInt())
        }
    }
}
