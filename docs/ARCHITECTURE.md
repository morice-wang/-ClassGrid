#| `TableGridOcr.kt` (230) | object | 【v1.42.1】灰度投影找表格线 → 逐格放大 OCR → 二维文本网格 | 多阈值 200/226/242 找浅灰线；`lastNote` 进识别日志；必须在工作线程跑（`Tasks.await` 不能在主线程） |
| `XlsImporter.kt` (450) | object | 【v1.43.0】OLE2 + BIFF8 解析老式 `.xls` | 纯 Kotlin 无第三方库；BIFF5 明确报错；产出 `TimetableGrid.Sheet` |
| `TodayWidgetData.kt` (160) | object | 【v1.44.0】小组件数据层：今天的课 + 状态 + 4 行截断 | 纯函数，`TodayWidgetDataTest` 11 个用例；不碰 RemoteViews |
| `TodayWidgetProvider.kt` (~200) | AppWidgetProvider | 【v1.44.0】RemoteViews 渲染 + 刷新广播 + PendingIntent | 颜色跟随 AppTheme/深浅色；圆角背景画成 Bitmap；整卡开 App、右上角只刷新 |
| `WidgetRefresh.kt` (~20) | object | 【v1.44.0】课表变更后刷新组件的统一入口 | MainActivity.onResume / 导入完成 / 保存课程时调用 | 课格 ClassGrid 架构说明

> 版本基线：`versionName 1.30.0` / `versionCode 45`（`app/build.gradle.kts:14-15`）
> 说明：本文全部结论来自**静态代码阅读**（开发机无 ARM 设备/模拟器，未做运行验证）。
> 路径约定：Kotlin 文件相对 `app/src/main/java/com/classgrid/app/`；其他文件写仓库相对路径。

---

## 0. 快速事实

| 项 | 值 |
|---|---|
| 包名 / applicationId | `com.classgrid.app` |
| minSdk / targetSdk / compileSdk | 24 / 34 / 34 |
| 语言与 UI | Kotlin 1.9.22 + AppCompat + Material 3（DayNight） |
| 构建 | AGP 8.2.2，Java 17，`viewBinding=true`，`buildConfig=true` |
| 架构形态 | 单模块、无 ViewModel / 无 Room / 无协程调度：Activity + 全局 `object` + 同步函数调用 |
| 数据存储 | `filesDir` 下 JSON 文件 + `SharedPreferences("classgrid_prefs")` |
| OCR | ML Kit `text-recognition-chinese:16.0.1`（bundled 离线模型） |
| ABI | 仅 `arm64-v8a`、`armeabi-v7a`（`app/build.gradle.kts:18-20`，x86 模拟器无法安装） |
| 代码规模 | 17 个 .kt，约 7.9k 行（最大：OcrParser 1296、ScheduleView 1039、MainActivity 965） |

---

## 1. 分层与依赖方向

没有依赖注入、没有仓储接口、没有响应式流；所有层直接以 `Xxx.xxx()` 静态调用串联，**调用发生在哪个线程就在哪个线程执行 IO**（这是 AUDIT 中大量「主线程 IO」问题的结构性根源）。

```
┌─────────────────────────── 表现层（Activity / 自定义 View）────────────────────────────┐
│ MainActivity          课表 Tab + 日程 Tab + 底部导航，持有 courses/todos/currentWeek    │
│ ScheduleView          纯 Canvas 自绘课表（缩放 1~2.4×、滚动、左右切周、导出位图）      │
│ ImportActivity        截图 OCR 导入（3 轮识别 + 打分）、JSON 导入、入口分发            │
│ OcrResultActivity     OCR 结果核对/行内编辑/删除，确认后合并入库                        │
│ CourseEditActivity    手动新增/编辑/删除单门课程                                      │
│ TodoEditActivity      新增自定义待办（仅新增，不支持编辑）                             │
│ SettingsActivity      设置项（外观/背景/节次/图标/导入导出/清空/恢复备份）             │
│ SectionTimeActivity   15 节作息时间自定义                                             │
│ BgCropActivity        自定义课表背景：9:20 取景框裁剪并落盘                            │
└───────────────────────────────────────┬───────────────────────────────────────────────┘
                                        │ 直接静态调用（无回调抽象）
┌───────────────────────────────────────┴───────────────────────────────────────────────┐
│ 领域/解析层                                                                            │
│ Course(+CourseJson)  课程模型、JSON 编解码、周次判定、课表文案                        │
│ OcrParser            纯文本 → List<Course>（三级流水线 + 字段级合并）                 │
│ GridParser           ML Kit 文本块坐标 → 整周网格 List<Course>（失败返回 null）       │
│ SectionTimes         节次时间表（15 节默认，可自定义），含 currentSection 判定         │
│ ScheduleColor        10 套课程配色（亮/暗双表）+ 主色                                   │
│ IconSwitcher          桌面图标别名切换（activity-alias）                              │
└───────────────────────────────────────┬───────────────────────────────────────────────┘
┌───────────────────────────────────────┴───────────────────────────────────────────────┐
│ 持久化层                                                                               │
│ CourseStore          filesDir 下 courses.json / courses_backup.json / todos.json        │
│ Prefs                与 CourseStore 同文件中的 object，封装 11 个 SharedPreferences 键 │
└───────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 数据流图（文字版）

### 2.1 启动与刷新

```
MainActivity.onCreate (MainActivity.kt:50)
  ├─ Prefs.applyNightMode(this)                      ← 必须在 super.onCreate 前，否则主题不生效
  ├─ enableEdgeToEdge() + WindowInsets 处理
  ├─ agendaSelectedDate = startOfDay(now)
  ├─ setupListeners()
  ├─ IconSwitcher.ensureApplied(this)                ← 对齐桌面图标别名 enable 状态
  └─ if (PackageInfo.lastUpdateTime > Prefs.guidedUpdateTime)
        → Prefs.setGuidedUpdateTime(now); root.postDelayed(350) { showGuideDialog() }

MainActivity.onResume (MainActivity.kt:104)
  ├─ loadData() (MainActivity.kt:142)                ← ⚠ 全部在 UI 线程同步执行
  │    ├─ courses = CourseStore.load(this)           → filesDir/courses.json 读文件+JSON 解析
  │    ├─ todos   = CourseStore.loadTodos(this)      → filesDir/todos.json    读文件+JSON 解析
  │    ├─ currentWeek = Prefs.currentWeek(this)      → 由 semester_start 推算
  │    └─ updateUI() (MainActivity.kt:149)
  │         ├─ ScheduleView.setData(courses, currentWeek)  → 内部再读 SectionTimes.load()
  │         ├─ ScheduleView.setSemesterStart / setWeekDates
  │         └─ currentTab==日程 → renderAgenda()；否则按 courses.isEmpty() 切课表/空态
  └─ bottomNav.selectedItemId = currentTab
```

### 2.2 周切换（三入口、同一状态源 `currentWeek`）

```
顶栏 ‹ / ›        → switchWeek(±1)        → animateWeekChange → setWeek(coerce 1..totalWeeks)
周次宫格         → WeekGridAdapter.onPick  → setWeek(week)
课表左右拖动     → ScheduleView.slideOffset（两张周页并排）→ commitWeek(delta)
                    → onWeekSettled(delta) 回调 → MainActivity 更新 currentWeek + 标题
「修改当前周」   → showModifyCurrentWeekDialog → applyCurrentWeekOffset(chosenWeek)
                    → 以「今天所在周一」反推并写回 Prefs.semesterStart（相当于重新锚定学期）
```

- `ScheduleView` 内部维护 `currentWeek`（显示周）与 `realCurrentWeek()`（按学期开始推算的真实周，`ScheduleView.kt:215`）；只有真实周才高亮“今天”列与当前节。
- `canSwipeWeek(delta)` 由 MainActivity 注入（`MainActivity.kt:126` 附近），保证不越界。

### 2.3 OCR 截图导入（最长链路）

```
ImportActivity
  cardScreenshot ──► PickMultipleVisualMedia(9) ──► runOcrQueue(uris) (ImportActivity.kt:89)
      ├─ Toast「开始识别 N 张截图…」
      ├─ watchdog: postDelayed(25000 + n*8000) 超时提示（不作废识别任务）
      └─ 对每张图：
            for round in 0..2                        // 0=灰度+色阶 1=原图 2=放大2倍+色阶
                buildInputImage(uri, round)  (后台 Thread)
                    ├─ loadDownsampled(uri, 1600) ×1
                    └─ enhanceForOcr / scaleBitmap(×2)
                ──► ML Kit TextRecognition(中文).process(image)
                    └─ onSuccess:
                        lineBoxes = visionText 的 line 文本框坐标
                        gridCourses = GridParser.parse(lineBoxes)          // 通道 A：整周网格
                        merged = gridCourses
                              ?: OcrParser.mergeCourses(                    // 通道 B：文本
                                     OcrParser.parseStructured(visionText),
                                     OcrParser.parse(rawText))
                        score = scoreCourses(merged)  → 保留分数最高的一轮
            ──► onImageRecognized(raw, bestCourses)
            ──► launchResult() → Intent(OcrResultActivity)
                  EXTRA_IMAGE_URI / EXTRA_RAW_TEXT / EXTRA_COURSES = CourseJson.encode(courses)

OcrResultActivity
  ├─ candidates = CourseJson.decode(EXTRA_COURSES)；colorIndex==0 的重排为 i%10
  ├─ 列表逐条展示 + 行内编辑（名称/代码/教师/教室/星期/起止节/起止周）+ 删除
  └─ btnImport → CourseStore.mergeAndSave(candidates) → 写 courses.json → finish
```

超时/失败路径：`finishOcrWithError(msg)`（`ImportActivity.kt:254`）只 Toast，不恢复按钮状态，也不取消已提交的 ML Kit 任务。

### 2.4 JSON 导入 / 导出

```
导入：ImportActivity.cardJson → showJsonImport (ImportActivity.kt:412)
        ├─ 预填 readClipboard()
        ├─ CourseJson.decode(text)  → 支持顶层数组 或 {"courses":[...]}
        └─ CourseStore.mergeAndSave(imported)  → 按 name+day+startSection 判重合并

导出 JSON：SettingsActivity.exportJson (SettingsActivity.kt:425)
        CourseStore.load → CourseJson.encode → 写剪贴板 + ACTION_SEND(text/plain)

导出图片：SettingsActivity.exportImage (SettingsActivity.kt:442)
        new ScheduleView(this) → setData() → renderToBitmap(360dp 宽)
        → cacheDir/exports/schedule_*.png（UI 线程压缩）
        → FileProvider.getUriForFile(...) → ACTION_SEND(image/png)
```

### 2.5 待办

```
新增：MainActivity（日程 Tab）+ 按钮 → showAddMenu → TodoEditActivity(EXTRA_DATE=选中日)
        → todo = Todo(id = System.currentTimeMillis(), title, dateMillis, time, location)
        → CourseStore.loadTodos + add + saveTodos
展示：renderAgenda → fillAgendaCourses → todosForDate(selected) → buildTodoItem
删除：点击待办卡片 → confirmDeleteTodo → todos.filter{ id != todo.id } → saveTodos
```

### 2.6 外观 / 背景 / 节次设置

```
设置项变更（SettingsActivity）
  ├─ 外观：setAppearance + applyNightMode + recreate()（AppCompatDelegate 全局生效）
  ├─ 卡片样式/阴影：写 Prefs → 返回主界面后 ScheduleView.onDraw 逐帧读取，立即生效
  ├─ 背景主题：写 Prefs.backgroundStyle → ScheduleView.scheduleBackground() 取色
  ├─ 自定义背景：PickVisualMedia → BgCropActivity → 裁剪保存 filesDir/bg/background.jpg
  │                 → Prefs.setBackgroundImage(绝对路径) → ScheduleView.loadBgImageIfNeeded() 读文件
  ├─ 节次时间：SectionTimeActivity → SectionTimes.save(JSON 字符串写入同一 prefs 文件)
  │                 → 返回主界面重新 loadData → setData 中重新 load SectionTimes
  └─ 图标：IconSwitcher.apply() → 切换 activity-alias 的 enabled 状态
```

---

### 2.7 链接抓取（v1.42.2：选学校 → 入口（官网或教务系统）→ 抓取；跨表按周次合并）

```
导入页 cardLink ──► SchoolPickerActivity
                     │ schools = SchoolDirectory.all() = assets/schools.json + 用户自定义
                     │ 搜索/省份筛选 → SchoolDirectory.search()
                     ▼ 点行：entryOf() 为空 → 弹「设置网址」；否则 setResult(name, url)
导入页 ──► WebImportActivity(EXTRA_SCHOOL, EXTRA_URL)
            │ startUrl = normalizeUrl(EXTRA_URL) ?: entryOf(school)  → 都没有则再弹选校页
            │ startEntry()：教务入口 → jwxt/jwglxt/jwgl/jwc/ehall/one 子域 → 官网
            │ 主框架加载失败（onReceivedError / HTTP≥400）自动换下一个候选
            ▼ 用户在 WebView 里自己登录、进课表页
          点「抓取当前页面」→ 注入 JS 收 payload → WebJsonParser / WebScheduleParser
            ▼
          OcrResultActivity（核对 → 导入）
```

- v1.39.0 起**默认打开各校教务系统**：`entryOf()` = 用户手改的网址 ?: 内置 `jw` ?: 官网；
  `entryCandidates()` 给出「教务系统 → 六个常用子域 → 学校官网」的回退链，WebView 打不开就自动试下一个，
  成功打开的那个按学校 `rememberSite()` 记住。
- 内置教务入口由 `tools/crawl_jw.py` 从各校官网首页抓取（1272/2868 所有入口，863 所抽验可用），
  写入 `schools.json` 第 10/11 项（`jw` / `jwv`）；只在校园网/VPN 开放的教务系统抓不到属正常，用户可手改网址。
- v1.42.2 起**广外默认回官网**（官网自适应，手机竖屏/电脑横屏都能抓）：`tools/gen_schools.py` 把广外的内置 `jw` 置空，
  手机从官网进手机版；电脑版入口仍可从「⋯ → 本校常用入口」手动打开。
- v1.42.2 起**电脑版两张表按周次合并**：`WebScheduleParser.parse()` 第一张表照常收集，后续表走 `alignAndMerge()`
  （同名 + 星期 + 节次；退化为同名 + 节次的唯一匹配），`mergeSameSlot()` 求周并集、互补时补 `excludedWeeks`；
  `dedupe()` 的 key 改为「名称 + 星期 + 起止节次」，不再把周次/教师/教室算进去。
- 用户改过的网址写进 `school_url_override`；`entryOf()` 先查覆盖、其次内置教务入口、最后官网，
  所以名单数据升级不会冲掉用户设置。
- 「⋯ → 本校常用入口」只是这一次跳转（`openUrl(remember = false)`），不会把猜出来的入口写成默认网址；
  只有用户手填网址（`remember = true`）或回退链自动成功的候选才 `rememberSite()`。
- 各校系统不同：`WebSystem.detect(url, title)` 识别正方/强智/金智/青果/URP/自研，状态栏与「⋯ → 教务系统判断」
  给出登录后该点哪个菜单；「在官网里找教务系统」「找课表页面」用 `scanLinks()` 扫主文档 + 同源 iframe 的链接。
- 选校页「继续上次」读 `school_last`，搜索框为空且未筛省份时才显示。

### 2.8 日历导出（v1.40.0 / v1.41.0 时区修正：课表 → .ics）

```
设置页「导出日历（.ics）」
  → IcsExporter.build(CourseStore.load(), Prefs.semesterStart(), SectionTimes.load())
       │ 每门课：firstActiveWeek/lastActiveWeek → RRULE UNTIL=最后一节有效周（UTC，规范要求）
       │         单双周 + excludedWeeks 被剔除的周 → EXDATE（跟随 DTSTART 的 TZID，本地时间）
       │         节次 → 本地时刻 → DTSTART;TZID=Asia/Shanghai:yyyyMMdd'T'HHmmss
       ▼
  cacheDir/exports/课格课表_yyyyMMdd.ics
  → 说明弹窗 → FileProvider uri → ACTION_SEND(text/calendar) → 系统日历 / Outlook / iPhone 导入
```

- `IcsExporter` 是**纯 Kotlin**（只依赖 `Course` / `SectionTimes.Section`），时间换算用
  `Calendar + TimeZone`，所以 `IcsExporterTest` 能在 JVM 上逐字断言输出。
- **事件时间用本地墙上时间 + `TZID`**，并生成对应 `VTIMEZONE`；`DTSTAMP` 与 `RRULE` 的 `UNTIL`
  按 RFC 5545 仍写 UTC。不要把 `DTSTART`/`DTEND` 写成 `…Z`：日历 App 会当 UTC 事件，
  详情页会多出一行「协调世界时」（v1.40.0 的 bug，v1.41.0 修）。
- `VTIMEZONE` 由 `vtimezone(zone, tzid, from, to)` 生成：`transitionsIn` 按小时扫描 + 二分到
  分钟找真实时区跳变，无跳变（如中国 `+0800` 无夏令时）写一条 `STANDARD`，有跳变逐条写
  `DAYLIGHT`/`STANDARD`。覆盖范围取 `events.minOf { firstMillis }` 到
  `events.maxOf { lastMillis }`——**必须算到最后一节课结束**，只取第一次会缺 `DAYLIGHT`。
- 稳定 `UID: course-<id>@classgrid.app`：同一门课重复导入是更新日历事件而不是新增。
- 文本转义 + 按 UTF-8 字节折 75 字节 + 全 CRLF（RFC 5545），否则 Outlook/Google 日历会截断或乱码。
- 提醒写成 `VALARM TRIGGER:-PT10M`，由日历进程触发；App 不申请精确闹钟、不发通知。
- 分享面板没有「日历」时按说明转发微信「文件传输助手」再打开；各品牌日历入口不同，见 CHANGES v1.41.0。
- 后续「直接写入系统日历（CalendarContract）」复用同一套周次/UID 规则，不要另写一套时间算法。

## 3. 类清单与职责

| 文件（行数） | 类型 | 职责 | 关键点 |
|---|---|---|---|
| `MainActivity.kt` (965) | Activity | 主界面：课表 Tab / 日程 Tab / 底部导航；周切换；课程详情与删除；引导弹窗 | 持有全部内存态；`onResume` 全量重载数据 |
| `ScheduleView.kt` (1039) | View | 纯 Canvas 课表：绘制网格/课程卡片/背景图；缩放、滚动、两页并排切周；导出位图 | 手势状态机 `axisLock`；`weekDayMapCache`；`onDraw` 内读 Prefs |
| `ImportActivity.kt` (586) | Activity | 导入入口：截图 OCR（3 轮×多图 + 打分择优）、**链接抓取先选学校**、**Excel/CSV 选文件（v1.42.0）**、JSON 粘贴导入、手动添加 | 自建子线程解码；watchdog；ML Kit client 每轮新建；表格先走 `GridParser` → 文本回退 `OcrParser` |
| `SchoolPickerActivity.kt` (378) | Activity | 【v1.38.0】选校页：搜索（名称/拼音/首字母）+ 省份 Chip + 列表；每行铅笔改网址；「+ 自定义」加名单外学校 | `setResult` 回传 `extra_school_name`/`extra_school_url`；无网址先弹设置框；行内标注「教务 · …」/「官网 · …」 |
| `SchoolDirectory.kt` (478) | object + `School` | 【v1.38.0/v1.39.0】学校名单（`assets/schools.json` 2868 所 + 每校教务入口）、搜索打分、网址规范化、自定义学校与「每校一个网址覆盖」持久化 | `entryOf()` = 覆盖 ?: `jw` ?: 官网；`entryCandidates()` 生成回退链；名单只读不改，用户改动落 `classgrid_prefs` |
| `WebSystem.kt` (74) | object | 【v1.39.0】按 URL/标题识别教务系统家族（正方/强智/金智/青果/URP/自研/未知）并给出菜单提示 | 纯函数 `detect(url, title)`，单测 `WebSystemTest` |
| `IcsExporter.kt` (360) | object | 【v1.40.0 / v1.41.0】课表 → 标准 .ics 日历：RRULE/EXDATE、TZID + VTIMEZONE、75 字节折行、VALARM | 纯 Kotlin（无 Android 依赖），稳定 UID `course-<id>@classgrid.app`；单测 `IcsExporterTest` |
| `TableGridOcr.kt` (230) | object | 【v1.42.1】灰度投影找表格线 → 逐格放大 OCR → 二维文本网格 | 多阈值 200/226/242 找浅灰线；`lastNote` 进识别日志；必须在工作线程跑 |
| `XlsImporter.kt` (450) | object | 【v1.43.0】OLE2 + BIFF8 解析老式 `.xls` | 纯 Kotlin 无第三方库；BIFF5 明确报错；产出 `TimetableGrid.Sheet` |
| `TodayWidgetData.kt` (160) | object | 【v1.44.0】小组件数据层：今天的课 + 状态 + 4 行截断 | 纯函数，`TodayWidgetDataTest` 11 个用例 |
| `TodayWidgetProvider.kt` (~200) | AppWidgetProvider | 【v1.44.0】RemoteViews 渲染 + 刷新广播 + PendingIntent | 颜色跟随 AppTheme/深浅色；整卡开 App、右上角只刷新 |
| `TodayWidgetRenderer.kt` (~330) | object | 【v1.46.0】整卡 Canvas 绘制：三栏「小-大-小」+ 渐变底/胶囊/剩余时间 | 输出 Bitmap 交给 RemoteViews；字色/背景色走 AppTheme 与深浅色；4×2 高度自适应字号 |

| `WidgetRefresh.kt` (~20) | object | 【v1.44.0】课表变更后刷新组件的统一入口 | onResume / 导入完成 / 保存课程时调用 || `TimetableGrid.kt` (~420) | object + `Sheet`/`WeekSpec` | 【v1.42.0】二维网格 → 课程：表头（周一/一/一 11/2/11/2/Mon）、节次（第N大节/第N节/HH:MM）、周次（1-16周/单双周/1-8,10-16周）、一格多课 | OCR 与 Excel/CSV 共用；`bigSection()` 按作息节数换算（15 节保留广外划分）；单测 `TimetableGridTest` |
| `SheetImporter.kt` (~300) | object | 【v1.42.0】xlsx（zip + sharedStrings + sheet1.xml + mergeCell）/ CSV / TSV → `TimetableGrid.Sheet` | 纯 Kotlin，无第三方库；UTF-8 失败回退 GB18030；单测 `SheetImporterTest` |
| `WebImportActivity.kt` (1388) | Activity | 【v1.31.0 / v1.39.0 默认教务入口】链接抓取：WebView 打开所选学校教务系统，用户自己登录后抓课表；三条数据通路 + 入口回退链 + 链接扫描 + 诊断导出 | 起始网址 = `EXTRA_URL` ?: `entryOf`；没有就弹选校页；主框架失败自动试下一个候选 |
| `OcrResultActivity.kt` (268) | Activity | OCR 结果核对：列表、行内编辑对话框、逐条删除、确认合并 | 代码构建全部行视图；无起止节校验 |
| `CourseEditActivity.kt` (222) | Activity | 单门课程编辑/新增/删除 | 有节次与周次区间校验；无 id 重复检测 |
| `SettingsActivity.kt` (488) | Activity | 设置页（手写 Preference 列表） | `onCreate`+`onResume` 双次构建；导出 JSON/图片 |
| `SectionTimeActivity.kt` (134) | Activity | 15 节作息时间编辑、恢复默认 | 校验 HH:mm 与先后顺序 |
| `TodoEditActivity.kt` (115) | Activity | 新增待办（日期 + 可选时间/地点） | 仅新增；findViewById 不用 viewBinding |
| `BgCropActivity.kt` (301) | Activity + 内部 `BgCropView` | 背景图 9:20 取景裁剪、落盘、写 Prefs | 预览 cover / 保存 fit 不一致 |
| `Course.kt` (160) | data class + `CourseJson` | 课程模型、JSON 编解码、周次判定与文案 | `id` 默认取毫秒时间戳 |
| `CourseStore.kt` (310) | object + 内部 `Prefs` | 课程/待办 JSON 读写（tmp+rename）、合并导入、备份恢复、全部偏好读写 | 异常一律静默；备份单份 |
| `SectionTimes.kt` (98) | object + `Section` | 节次时间表读写与“当前第几节”判定 | 直接使用 `"classgrid_prefs"` 字面量 |
| `OcrParser.kt` (1296) | object (+`ParsedCourse`) | 纯文本 OCR → 课程（normalize → L1/L2/L3 → 字段合并 → 校验） | 编辑距离匹配标签；部分成功也返回 |
| `GridParser.kt` (358) | object + `LineBox` | 用文本块坐标还原整周网格课表 | 门槛高（≥10 行/≥4 天头/≥2 锚点/≥2 门课） |
| `ScheduleColor.kt` (64) | object + `Palette` | 10 套课程配色（亮/暗）、主色、深浅判定 | `palette(index)` 取模防越界 |
| `IconSwitcher.kt` (84) | object | 7 个 launcher alias 的启用切换 | Handler 延迟 400ms 关闭其余别名 |
| `Todo.kt` (18) | data class | 待办模型 | `dateMillis` 为当天 00:00 |

---

## 4. 关键函数签名

### 4.1 持久化与偏好（`CourseStore.kt` / `SectionTimes.kt`）

```kotlin
object CourseStore {
    fun load(context: Context): List<Course>                       // :20  坏文件→courses_corrupt.json 后返回空
    fun save(context: Context, courses: List<Course>)              // :35  .tmp → 轮换 backup → rename
    fun hasBackup(context: Context): Boolean                       // :62
    fun loadTodos(context: Context): List<Todo>                    // :69
    fun saveTodos(context: Context, todos: List<Todo>)             // :89
    fun restoreBackup(context: Context): Boolean                   // :114
    fun mergeAndSave(context: Context, newCourses: List<Course>): Int  // :131  返回“新增”条数
}

object Prefs {                                                     // CourseStore.kt:151
    fun semesterStart(context: Context): Long                      // :183  未设置→按月份推算且不写回
    fun setSemesterStart(context: Context, millis: Long)           // :189
    fun totalWeeks(context: Context): Int                          // :193  默认 20（读取不 coerce）
    fun setTotalWeeks(context: Context, weeks: Int)                // :196  coerceIn(1,40)
    fun appearance(context: Context): Int                          // :201  0 跟随 / 1 日间 / 2 夜间
    fun setAppearance(context: Context, mode: Int)                 // :205
    fun cardStyle(context: Context): Int                           // :210  0 圆角 / 1 直角
    fun cardShadow(context: Context): Boolean                      // :220  默认 true
    fun backgroundStyle(context: Context): Int                     // :228  0 跟随 / 1..4 主题色
    fun backgroundImage(context: Context): String?                 // :238  绝对路径字符串
    fun setBackgroundImage(context: Context, uri: String?)         // :241
    fun guidedUpdateTime(context: Context): Long                   // :248
    fun setGuidedUpdateTime(context: Context, t: Long)             // :251
    fun launcherIcon(context: Context): Int                        // :261  coerceIn(0,6)
    fun applyNightMode(context: Context)                           // :269  AppCompatDelegate
    fun currentWeek(context: Context): Int                         // :279  1 + 距开学周数，≥1
    fun defaultSemesterStart(): Long                               // :296  8 月后取 9 月首个周一
}

object SectionTimes {                                              // SectionTimes.kt:10
    data class Section(val start: String, val end: String)          // :12
    fun default(): MutableList<Section>                             // :17  广外 15 节
    fun load(context: Context): MutableList<Section>                // :35  失败回落默认
    fun save(context: Context, list: List<Section>)                 // :53
    fun reset(context: Context)                                     // :65
    fun sectionAt(context: Context, section: Int): Section?         // :71
    fun currentSection(context: Context): Int                       // :77  当前第几节，-1=不在任何一节
    fun toMinutes(hhmm: String): Int?                               // :89
    fun isValid(hhmm: String): Boolean                              // :97
}
```

### 4.2 模型（`Course.kt`）

```kotlin
data class Course(..., val id: Long = System.currentTimeMillis(), ...)   // :10
fun fromJson(o: JSONObject): Course            // :34 字段缺省齐全，不做范围校验
fun toJson(): JSONObject                        // :55
fun activeInWeek(week: Int): Boolean            // :79 周区间 + excludedWeeks + 单双周
fun weekDesc(): String                          // :113 "2-4,6-16周 · 单周"
object CourseJson {
    fun toJsonArray(courses: List<Course>): JSONArray   // :131
    fun fromJsonArray(arr: JSONArray): List<Course>     // :134 跳过空名/非对象
    fun encode(courses: List<Course>): String           // :145
    fun decode(text: String): List<Course>              // :147 全部异常 → emptyList()
}
```

### 4.3 课表绘制与交互（`ScheduleView.kt`）

```kotlin
fun setData(newCourses: List<Course>, week: Int)   // :168  内部 reload SectionTimes
fun setCurrentWeek(week: Int)                      // :178
fun setWeekDates(dates: List<Int>)                 // :187  仅接受 size==7
fun setSemesterStart(millis: Long)                 // :193
fun setOnClickCourse(listener: (Course) -> Unit)   // :227
override fun onDraw(canvas: Canvas)                // :238
private fun drawWeekPage(canvas, week, dark, dx)   // :260  单周页（翻页时画两页）
private fun drawCard(canvas, c, l, t, r, b, dark)  // :506  b卡片+自适应字号文本
private fun layoutLines(text, paint, maxWidth, maxLines): List<String>  // :639 字符级折行
private fun handleClick(touchX: Float, touchY: Float)   // :984 命中课程→onClickCourse
override fun onTouchEvent(e: MotionEvent): Boolean // :869  缩放/滚动/翻页三态互斥
private fun settleWeekSwipe(flingVx: Float)        // :898  松手吸附或回弹
private fun commitWeek(delta: Int)                 // :945  提交周次并回调 onWeekSettled
fun renderToBitmap(targetWidthPx: Int): Bitmap     // :1006 导出（会临时 measure/layout 自身）
```

### 4.4 主界面（`MainActivity.kt`）

```kotlin
override fun onCreate(savedInstanceState: Bundle?)  // :50
private fun loadData()                              // :142 读文件（UI 线程）
private fun updateUI()                              // :149
private fun switchWeek(delta: Int)                  // :164
private fun setWeek(week: Int)                      // :173
private fun weekDateRange(week: Int): String        // :191
private fun weekDatesForWeek(week: Int): List<Int>  // :204
private fun switchTab(tabId: Int)                   // :277 nav_settings 会直接跳设置页
private fun renderAgenda()                          // :355
private fun coursesForDate(millis: Long): List<Course>  // :825
private fun todosForDate(millis: Long): List<Todo>      // :833
private fun showCourseDetail(course: Course)        // :874
private fun confirmDelete(course: Course)           // :896  按 id 过滤（见 AUDIT 的 P0 条）
```

### 4.5 导入链路（`ImportActivity.kt` / `OcrResultActivity.kt` / 解析器）

```kotlin
// ImportActivity
fun runOcrQueue(uris: List<String>)                 // :89
fun recognizeRound()                                // :139
fun scoreCourses(cs: List<Course>): Int             // :218
fun buildInputImage(uri: Uri, round: Int): InputImage  // :267
fun loadDownsampled(uri: Uri, maxDim: Int): Bitmap  // :301
fun enhanceForOcr(...)                              // :364
fun showJsonImport()                                // :412

// OcrResultActivity
fun buildItem(course: Course, index: Int, dark: Boolean): View  // :109
fun showEditDialog(index: Int)                      // :180

// 解析
object GridParser { fun parse(lines: List<LineBox>): List<Course>? }   // GridParser.kt:56
object OcrParser {
    fun normalize(raw: String): String                                  // :204
    fun parse(rawText: String): List<Course>                            // :270
    fun mergeCourses(structured: List<Course>, text: List<Course>): List<Course>  // :300
    fun parseStructured(text: Text): List<Course>                       // :347
}
```

---

## 5. 持久化位置

### 5.1 文件（`context.filesDir`）

| 路径 | 内容 | 写入者 | 读取者 | 备注 |
|---|---|---|---|---|
| `courses.json` | 课程数组（`CourseJson`） | `CourseStore.save` / `mergeAndSave` / `restoreBackup` | `CourseStore.load` | 先写 `.tmp` 再 rename；失败回退 `writeText` |
| `courses_backup.json` | 上一版课程 | `CourseStore.save`（每次保存轮换） | `restoreBackup` | **只有一份**，会被下一次保存覆盖 |
| `courses_corrupt.json` | 损坏文件留底 | `CourseStore.load` 的异常分支 | 无（不提供 UI 恢复） | 因 `decode` 内部吞异常，实际几乎不可达（见 AUDIT） |
| `todos.json` | 待办数组 | `saveTodos` | `loadTodos` | 无备份、无损坏留底 |
| `bg/background.jpg` | 自定义课表背景 | `BgCropActivity.saveCropped` | `ScheduleView.loadBgImageIfNeeded`（经 Prefs 里的绝对路径） | JPEG 质量 90，最长边≤1600 |

### 5.2 SharedPreferences（`"classgrid_prefs"` 单文件）

| Key | 类型 | 默认 | 语义 | 实际使用方 |
|---|---|---|---|---|
| `semester_start` | Long | 0 → 按月份推算 | 学期开始（周一 00:00） | Prefs / MainActivity / ScheduleView |
| `total_weeks` | Int | 20 | 学期总周数（写入时 1..40） | MainActivity 周选择器 |
| `appearance` | Int | 0 | 0 跟随系统 / 1 日间 / 2 夜间 | Prefs.applyNightMode |
| `card_style` | Int | 0 | 0 圆角 / 1 直角 | ScheduleView.drawCard |
| `card_shadow` | Boolean | true | 卡片阴影 | ScheduleView.drawCard |
| `schedule_background` | Int | 0 | 0 跟随 / 1 米黄 / 2 淡蓝 / 3 淡绿 / 4 淡粉 | ScheduleView.scheduleBackground |
| `schedule_bg_image_uri` | String? | null | **绝对路径**（非 content URI） | ScheduleView / Settings |
| `guided_update_time` | Long | 0 | 上次展示引导时的 `PackageInfo.lastUpdateTime` | MainActivity.onCreate |
| `launcher_icon` / `_v2` / `_v3` | Int | — | 仅声明，未读写（迁移遗留） | — |
| `launcher_icon_v4` | Int | 0 | 当前桌面图标索引 0..6 | Prefs.launcherIcon / Settings |
| `section_times` | String(JSON) | 15 节默认 | 节次时间表 | `SectionTimes`（直接取同名 prefs 文件） |
| `school_custom` | String(JSON) | null | 用户自定义学校数组（名字/地区/网址…） | `SchoolDirectory` |
| `school_url_override` | String(JSON) | null | 学校名 → 用户改过的网址（覆盖内置教务入口/官网） | `SchoolDirectory.entryOf` |
| `school_recent` | String(JSON) | null | 最近点过的学校名（最多 8 个） | `SchoolDirectory.markRecent` |
| `school_last` | String | null | 上次选的学校名（选校页「继续上次」） | `SchoolDirectory.lastSchool` |

> 注：`SectionTimes.kt:36/61/66` 直接 `getSharedPreferences("classgrid_prefs", ...)`，与 `Prefs.NAME` 是同一文件的**硬编码副本**，键名当前无冲突。

### 5.3 其他

| 位置 | 内容 | 说明 |
|---|---|---|
| `cacheDir/exports/schedule_*.png` | 导出图片 | 通过 `FileProvider`（authority `${applicationId}.fileprovider`）分享 |
| `cacheDir/exports/课格课表_yyyyMMdd.ics` | 导出日历（v1.40.0） | `IcsExporter.build` 生成，`ACTION_SEND(text/calendar)` 分享给日历 App；缓存文件可随时删 |
| `release.keystore`（仓库根） | 签名密钥 | 与 `app/build.gradle.kts` 中的明文口令配套（见 AUDIT） |
| Android 自动备份 | `courses.json` / `todos.json` | 清单 `allowBackup=true`，无 `fullBackupContent` 排除规则 |

---

## 6. Activity 跳转关系

```
                        ┌────────────────────────────┐
      launcher-alias ──►│       MainActivity         │◄── 底部导航「课表 / 日程」（同一 Activity 内切 Tab）
      (7 个图标别名)     └──────┬──────┬──────┬───────┘
                              │      │      │
             ┌────────────────┘      │      └──────────────────┐
             ▼                       ▼                         ▼
   ┌──────────────────┐   ┌────────────────────┐     ┌──────────────────┐
   │  ImportActivity  │   │ CourseEditActivity │     │ SettingsActivity │
   └───┬──────┬───────┘   └────────────────────┘     └───┬──────────┬───┘
       │      │                                           │          │
       │      └─► CourseEditActivity（手动添加）           │          └─► SectionTimeActivity
       ▼                                                   ▼
┌────────────────────┐                            ┌──────────────────┐
│ OcrResultActivity  │                            │  BgCropActivity  │
└────────────────────┘  （OCR 完成后）            └──────────────────┘
                   
MainActivity（日程 Tab）──► TodoEditActivity（新增待办）
```

| 起点 | 触发 | 目标 | Extra / 结果 | 备注 |
|---|---|---|---|---|
| launcher alias | 点图标 | `MainActivity` | — | 7 个 alias 共享同一 Activity |
| MainActivity | `btnAdd`（课表 Tab） | `ImportActivity` | — | `showAddMenu` 课表 Tab 直接进导入页 |
| MainActivity | `btnAdd`（日程 Tab） | `TodoEditActivity` | `extra_date=agendaSelectedDate` | |
| MainActivity | 空态 `btnImport` / `btnManual` | `ImportActivity` / `CourseEditActivity` | — / 无 id | `openEdit(null)` |
| MainActivity | 课程卡片 / 详情「编辑」 | `CourseEditActivity` | `course_id`（-1=新增） | |
| MainActivity | 底部导航「设置」 | `SettingsActivity` | — | **不切换 Tab**，返回后仍在原 Tab |
| ImportActivity | 选图后识别成功 | `OcrResultActivity` | `image_uri` / `raw_text` / `courses`(JSON) | `finishOcrWithError` 时留在本页 |
| ImportActivity | `cardManual` | `CourseEditActivity` | — | |
| ImportActivity | `cardLink`（链接抓取） | `SchoolPickerActivity` | — | v1.38.0 起先选学校，不再直接开广外；v1.39.0 默认进教务入口 |
| SchoolPickerActivity | 点学校行 / 「继续上次」/ 自定义保存 | （`setResult` 返回） | `extra_school_name` + `extra_school_url` | 该学校没网址时先弹「设置网址」，保存后再回传 |
| ImportActivity | 选校返回 `RESULT_OK` | `WebImportActivity` | `extra_school` + `extra_url` | `startActivity`，不等待返回 |
| WebImportActivity | 「⋯ → 切换学校」/ 网址弹窗「选择学校」 | `SchoolPickerActivity` | 返回同上 | 选完在当前 WebView 直接打开新学校教务入口；「⋯ → 回到学校官网」只跳官网不记忆 |
| OcrResultActivity | 确认导入 | —（`finish`） | — | 写库后返回 MainActivity.onResume 重载 |
| SettingsActivity | 节次时间 | `SectionTimeActivity` | — | 返回后重建设置页 |
| SettingsActivity | 自定义背景 | `BgCropActivity` | `image_uri` | 裁剪结果写 `filesDir/bg` |
| 任意 | 返回键 | 上一层 | — | 无显式 `onBackPressedDispatcher` 定制 |

---

## 7. UI 结构

### 7.1 `activity_main.xml`（233 行）

```
CoordinatorLayout
└─ LinearLayout(vertical)
   ├─ @id/header（?attr/colorSurface，elevation 3dp）
   │   ├─ 行1: btnTitle「课格」22sp bold + @id/weekNavRow
   │   │        （btnPrev ‹ / @id/weekSelector(tvWeek, tvDateRange) / btnNext ›）+ btnAdd ＋
   │   └─ 行2: btnToday「回到本周」（TextButton，9sp）
   ├─ FrameLayout(weight=1)
   │   ├─ @id/scheduleView  (com.classgrid.app.ScheduleView, match_parent)
   │   ├─ @id/agendaScroll  (ScrollView, gone) → @id/agendaContainer(LinearLayout)
   │   └─ @id/emptyState    (gone；ic_today 96dp + 文案 + btnImport + btnManual)
   └─ @id/bottomNav (BottomNavigationView, labeled)
         nav_schedule(课表) / nav_agenda(日程) / nav_settings(设置)
```

- 日程页的所有子视图（日期标题、7 个日期胶囊、课程卡片、待办卡片）都是 Kotlin 代码动态构建，没有独立 item 布局。
- `nav_settings` 是一个“伪 Tab”：点中后立即 `startActivity(SettingsActivity)` 并保持 `currentTab` 不变。

### 7.2 `ScheduleView` 绘制顺序（`onDraw` → `drawWeekPage`）

```
1. 页面背景：scheduleBackground(dark)                     ← 每帧读 Prefs.backgroundStyle
2. 自定义背景图（仅浅色）：loadBgImageIfNeeded() → 缩放裁剪绘制  ← 每帧读 Prefs 路径，首次需 IO
3. 表头背景 + 星期/日期（今天列：主色胶囊 + 白字；周末灰字）
4. 网格线
5. 当前节高亮带：nowSection = SectionTimes.currentSection()  ← 每帧读 prefs + 解析 JSON
6. drawCourses()：按天分组 → clusterByOverlap 处理同节重叠 → drawCard
7. 左侧固定时间列（节号 + 起止时间）重绘，覆盖滚动内容
```

`drawCard` 每张卡片还会读取 `Prefs.cardStyle` / `Prefs.cardShadow`；主色 `ScheduleColor.primary()` 每帧 `Color.parseColor`。

### 7.3 手势状态机（`axisLock`）

| 状态 | 含义 | 进入条件 | 结束 |
|---|---|---|---|
| 0 | 未锁定 | DOWN | — |
| 1 | 左右切周（翻页） | 无横向内容可滚（`maxSX()==0`）且横向位移占优（>12dp 且 >1.5×纵向） | UP/CANCEL → `settleWeekSwipe()` |
| 2 | 自由滚动/缩放 | 纵向位移 >8dp 且 >1.2×横向，或已放大后的横向拖动 | 惯性滚动 `OverScroller` |

- 翻页时 `slideOffset ∈ [-width, width]`，`onDraw` 同时绘制当前周与相邻周两页（1:1 跟手）；松手后单次吸附动画 140~300ms，越界方向带 0.32 阻尼。
- 缩放 `scale ∈ [1, 2.4]`，以手指焦点为中心；`weekDayMapCache` 缓存“周→天→课程”映射，避免每帧过滤。

### 7.4 日程页（`renderAgenda` / `fillAgendaCourses`）

```
agendaContainer
├─ headerGroup: 「MM月dd日 周X」+「第N周」 + 7 个日期胶囊（周日..周六）
└─ courseGroup: 按开始节次排序的课程卡片（左列节次+起止时间，右列课名/地点/教师，可带「上课中/下一节课」状态胶囊）
               + 当天的待办卡片（琥珀色，点击删除）
```

切换日期：旧内容滑出 120ms → 重建 → 滑入 160ms（`agendaAnimating` 防重入）。

---

## 8. OCR 解析：双通道 + 三轮 + 打分

### 8.1 通道 A：`GridParser`（整周网格，精度高、门槛也高）

`GridParser.parse(lines)` 在以下任一条件不满足时返回 `null`，让位给文本通道：

| 条件 | 位置 | 说明 |
|---|---|---|
| 文本框 ≥ 10 个 | `GridParser.kt:57` | 行数太少不可能是整周表 |
| 识别到 ≥4 个“星期X”表头 | `GridParser.kt:65` | 用表头中心 x 建立「星期→列」映射 |
| 列数 ≥ 4 且锚点行 ≥ 2 | `GridParser.kt:80-101` | 锚点优先用“第 N 大节”，否则用“时间:”行 |
| 最终课程数 ≥ 2 | `GridParser.kt:149` | 单门课也放弃网格路径 |

满足后用 `avgColW` 阈值把文本框归列，按中位行高 ×1.8 切分课程块，再 `buildCourse()` 逐字段抽取（课程名/教师/周次/节次/地点）。

### 8.2 通道 B：`OcrParser`（纯文本，鲁棒但需启发式）

```
normalize(raw)                             // :204 全角/繁体/噪声清洗
  ├─ L1 parseFullText()                    // :598 全文按标签切分
  ├─ L2 parseTaggedLines()                 // :645 逐行「标签: 值」解析
  └─ L3 parseFieldLevel()                  // :776 字段级扫描（ROI 内教师/教室）
  → mergeParsed(base, other)               // :1040 缺失字段互补、非法值替换
  → ParsedCourse.build(): Course?          // :1093 合理性校验（day 1..7、节次 1..15、周次 1..25、HH:MM）
```

只要“有课程名”或“有教师且能推出时间”，就输出一条记录（部分成功也算），交给核对页人工修正。

### 8.3 择优打分（`ImportActivity.kt:218-233`）

| 命中 | 加分 |
|---|---|
| 课程名非空 | +2 |
| 有课程代码 | +1 |
| 有教师 | +2 |
| 有教室 | +2 |
| 星期 ∈ 1..7 | +1 |
| 起止节次 ∈ 1..15 | +1 |
| 周次不是默认 1-16 | +1 |
| 起止时间（HH:MM）都有 | +1 |

每张图最多跑 3 轮（灰度+色阶 / 原图 / 放大 2 倍+色阶），取最高分结果；多张图按顺序各自识别，最终合并成一个列表传给核对页。

### 8.4 核对页可编辑项

名称、代码、教师、教室、星期（Spinner）、起止节次（Spinner 1..15）、起止周（数字输入，1..40）。**没有节次先后校验**，也没有字段范围外的输入约束（见 AUDIT）。

---

## 9. 关键设计决策（与代价）

| 决策 | 理由 | 代价 / 风险 |
|---|---|---|
| JSON 文件替代 Room | 数据量小、无迁移成本、便于导出/导入 | 手写原子写、无事务、无查询；异常静默易吞数据 |
| Canvas 自绘课表 | 任意缩放、两页并排 1:1 跟手、可控绘制细节 | 全部文本排版/命中测试/无障碍需手写；`onDraw` 里读 Prefs 直接伤害帧率 |
| 全局 `object` + 同步调用 | 代码量小、调用直观 | 无生命周期感知，IO 落在调用线程（多为 UI 线程）；无法注入/测试 |
| ML Kit bundled 中文模型 | 局域网/无网可用，识别率高 | APK ~25MB；仅 ARM ABI；初始化与内存开销大 |
| 双通道解析（网格 + 文本） | 网格图精度高，弹窗图只能靠文本 | 两套启发式需分别回归；网格门槛高易退化为文本通道 |
| `Prefs` 直接封装 SharedPreferences | 无 DataStore/LiveData 依赖 | 无变更通知，读者（`ScheduleView`）只能每次重读 |
| `activity-alias` 实现换图标 | 无需重启、系统级更换 | 7 个别名需 `IconSwitcher` 手动对齐状态，冷启动需 `ensureApplied` |

---

## 10. 代码导航（按任务找入口）

| 想改什么 | 从这里开始 |
|---|---|
| 课表外观/配色/卡片、手势、翻页动画 | `ScheduleView.kt`（`onDraw`、`drawCard`、`onScroll`、`settleWeekSwipe`） |
| 主界面布局、日程页、周切换 | `MainActivity.kt`（`updateUI`、`renderAgenda`、`switchWeek`） |
| 数据格式、JSON 兼容、周次判定 | `Course.kt`（`fromJson`/`toJson`/`activeInWeek`） |
| 存储与备份策略 | `CourseStore.kt`（`load`/`save`/`mergeAndSave`） |
| OCR 识别准确率 | `GridParser.kt`（网格）与 `OcrParser.kt`（文本） |
| 识别流程、超时、并发 | `ImportActivity.kt`（`runOcrQueue`/`recognizeRound`） |
| 设置项与默认值 | `SettingsActivity.kt` + `CourseStore.kt` 的 `Prefs` |
| 节次时间 | `SectionTimes.kt` + `SectionTimeActivity.kt` |
| 应用图标切换 | `IconSwitcher.kt` + `AndroidManifest.xml` 的 alias 段 |

---

## 11. 已知限制与交接注意

1. **版本标识不一致**：代码/构建是 `1.30.0 (45)`，但目录名 `classgrid-source-v1.9.0`、`README.md:12` 仍写 `v1.9.0 (13)`。唯一可信版本源是 `app/build.gradle.kts`。
2. **无法在本机构建后运行**：`abiFilters` 不含 x86，开发机无 ARM 设备/模拟器，只能做静态审计与 `assembleRelease` 编译验证。
3. **无课程列表管理页**：删除入口只有课表卡片与日程卡片，字段非法的课程一旦写库就“隐形”（见 AUDIT P2）。
4. **无 i18n**：文案大量硬编码在 Kotlin 中（设置页、日程页），`values/strings.xml` 只覆盖一部分；CJK 折行按单字符处理（`ScheduleView.layoutLines`）。
5. **无撤销/回收站**：清空课表、恢复备份、删除课程都是即时生效。
6. **单一备份**：`courses_backup.json` 只保留上一版，`todos.json` 完全没有备份。
7. **无 ViewModel/状态持久化**：旋转屏幕会重建 Activity 并重新 `loadData()`（列表/周次等状态不保存，`currentTab` 也会回默认）。
8. **节次固定上限 15**：`SectionTimes` 与各处 Spinner 均按 15 节设计，`Course.startSection/endSection` 用 `coerceIn(1,15)` 约束。
9. **CHANGES.md**（~41KB，31 个版本条目）是唯一版本史，可用来判断某处代码为何如此，不建议全文通读。

### 2.10 老式 .xls 与「列表展示」兜底（v1.43.0）

- **`.xls` 走 XlsImporter**：SheetImporter.read 先看文件头 `D0 CF 11 E0`，是 OLE2 就交给 XlsImporter
  （OLE2 复合文档 → `Workbook` 流 → BIFF8 记录），产出与 xlsx 相同的 `TimetableGrid.Sheet`；
  BIFF5 直接报「请另存为 .xlsx」，不再把二进制当文本读。
- **老式课表格格式**：`名称★周次(节次)★教室★班级`。TimetableGrid.splitCell 把 `★`/制表符当换行，
  WEEK_SECTION_PAIR 把 `1-18(01,02节)` 拆成周次与节次；CLASS_HINT 保证班级不会被当成教师；
  mergeWeeksAcrossRows 把同一门课跨多行的周次段并成一条。
- **导入失败必须可报价**：ImportActivity.showSheetFailure 把异常、文件头 hex、表格前 24 行做成诊断弹窗，
  一键导出 txt —— 用户反馈「只有一句 toast」就是这一步缺的。
- **列表视图兜底**：WebImportActivity.tryListMode 在页面上点「列表展示/列表视图/表格视图」再抓一次
  （含同源 iframe）；WebScheduleParser.parseCourseList 解析「一行一门课」的列表表。
  适用场景：课表是 div 网格、又没有星期表头（肇庆学院 WebVPN）。
- **分格 OCR 可观测**：TableGridOcr 用 200/226/242 三档阈值找线，lastNote 记录「表格线：竖 N、横 M」与
  「识别 N 格、M 格有文字」，导出识别日志时带出，便于判断是切格失败还是文字识别失败。
### 2.11 桌面小组件「今天的课」（v1.44.0；v1.46.0 三栏；v1.47.0 胶囊化；v1.47.3 尺寸自适应）

- **宿主仍是 RemoteViews，内容改成 Bitmap**：RemoteViews 里只放一个 ImageView（整卡 Bitmap）
  和两个透明点击区（整卡 / 右上刷新）。这样字号、圆角、渐变、胶囊、进度都能逐像素控制，
  又不引来 Glance/Compose（APK 不涨、minSdk 24 全支持）。Glance 只是 Compose 写法，
  最终同样渲染成 RemoteViews，拿不到连续动画，所以没有迁。
- **数据来自同一个 Snapshot**：`TodayWidgetData.Snapshot` 除 `rows/extra` 外还有
  `allRows / doneRows / focusRow / laterRows`：
  `focusRow` 先找「正在上」，没有则「下一节」，都没有说明今天上完了。
  于是「今天该显示哪些课」仍是纯函数，JVM 可测（TodayWidgetDataTest 15 个）。
- **胶囊布局（v1.47.1 起定型）**：
  - 顶部两枚胶囊：「今天：周X」+「第N周」（9.5sp muted）+ 右上刷新图标；
  - 主课是一条大胶囊：`radius = 高度/2`、主色渐变、1dp 主色描边、下方主色光晕；内容**整体居中**——
    第一行「时间 + 教室」，第二行放大加粗的课名（12.5–21.5sp 随高度），正在上课时第三行小字「还剩 N 分」；
  - 底部一条「下一节」胶囊：`14:00 基础会计 · 2号教学楼`（时间主色、其余 muted）；没有下一节时不画，
    主胶囊自动占满；
  - 结束态：`focusRow == null && hasCourses` 时大胶囊显示「今天的课程已经结束」+「辛苦了」+ 四角星，
    字号由 `fittedTextPaint()` 按目标宽度反推（标题 ≈ 胶囊宽 70%、副标题 ≈ 34%）；没课时「今天没有课 / 好好休息一下」。
- **动效**：组件不能做补间动画，改为「正在上课时每分钟刷新一次」——
  `TodayNotify.scheduleNext()` 检测到今天有 `start<=now<end` 的课，就把下一次刷新排到下一分钟
  （`schedule(context, timeInMillis)` 统一走精确/降级两条路），进度环与倒计时逐分钟推进。
- **刷新时机**：① onUpdate；② onReceive 的 ACTION_REFRESH / DATE_CHANGED / TIME_SET /
  TIMEZONE_CHANGED / MY_PACKAGE_REPLACED；③ App 内 WidgetRefresh.refresh —— MainActivity.onResume、
  OcrResultActivity 导入完成、CourseEditActivity 保存课程（同时刷新通知）。没有添加组件时等于空操作。
- **颜色跟随主题**：RemoteViews 不解析 ?attr，因此颜色全部在 renderer 里用
  `AppTheme.primary/text/textMuted/line/cardBackground` + `ScheduleColor.isDarkMode` 取好后画进位图。
- **无障碍**：`setContentDescription(widgetCanvas, TodayWidgetData.statusText(snapshot))`。
- **添加方式**：正式包长按桌面 → 小组件 → 课格；debug 包另有 WidgetPinDebugActivity（requestPinAppWidget）
  与 WidgetBitmapDebugActivity（按指定星期/分钟渲染大图 + ML Kit OCR 自检，logcat tag `WidgetDebug`）。
- **尺寸与变形（v1.47.3）**：`widgetSize()` 按方向取值——竖屏 `MIN_WIDTH + MAX_HEIGHT`、横屏
  `MAX_WIDTH + MIN_HEIGHT`，缺失互为兜底，再兜底 300×130dp；宽高比夹在 1.8–2.9。
  `onAppWidgetOptionsChanged()` 里重画（改大小/换方向立即生效），`widgetCanvas` 用 `centerCrop`
  而不是 `fitXY`——个别桌面报的尺寸偏小或延迟更新时，宁可裁边也不把胶囊拉扁。
- **预览布局限制**：`widget_today_preview.xml` 会被桌面当作 RemoteViews 预览膨胀，只能用白名单控件；
  用 `<View>` 占位会让部分桌面直接报「Couldn't add widget」，占位一律用空 `TextView`。
- **尺寸**：minWidth 250dp / minHeight 110dp，targetCell 4×2；长按拖大可到 4×3，会按实际像素重新渲染。

### 2.9 表格化导入与内测日志（v1.42.0）

- **一条网格解析链，三种来源**：Excel/CSV（SheetImporter）、截图（ImportActivity 把 ML Kit 行坐标交给
  GridParser）、网页抓取（WebScheduleParser 的表路径）都落到「表头定列 + 左列定行 + 格内拆课」这同一套规则，
  规则集中在 TimetableGrid：dayOf / sectionOf / bigSection / weeksOf。
  改表头写法只改这里，不要三处各写一份正则。
- **兜底顺序**：截图 → GridParser（坐标）→ OcrParser（纯文本）；网页 → 接口 JSON → 接口重放 → DOM 表 → 文本块合成。
  Excel → TimetableGrid.parse，失败再转置一次（竖表）。
- **内测版可观测性**：BuildConfig.CHANNEL 进关于页与日志头；识别结果页「导出识别日志」
  （原文 + 解析结果 + 版本/设备）与抓取页底部常驻「日志」（buildLogReport() 带原始 payload，截断 400K）。
  两条日志都写 cacheDir/exports/ 后走 FileProvider 分享。
- **节次换算**：TimetableGrid.bigSection(big, sectionCount) —— 用户作息是 15 节时保留广外
  1-2,3-5,6-7,8-9,10-11,12-13,14-15 的 7 大节划分，其他节数按偶数两节一组，避免别校 12/13 节错位。
### 2.12 通知栏常驻「今天的课」（v1.45.0）

- **不是前台服务**：一节课才变一次，没必要常驻进程。用 ongoing 通知（`IMPORTANCE_LOW` 无声、
  `CATEGORY_STATUS`），平时零唤醒，只在今天课程的 start/end 分钟边界用 `AlarmManager` 唤醒重画。
  Android 12+ 未授予精确闹钟权限时降级为 `set()`；当天没有边界就排到次日 07:00，跨天自动换内容。
- **Android 16 Live Updates 暂不支持**：状态栏胶囊需要 API 36 的 `setRequestPromotedOngoing`，
  本项目 compileSdk/targetSdk 都是 34；升到 36 时加一个 SDK 分支即可，系统会自动把 ongoing 通知提升成胶囊。
- **与桌面组件同源**：`TodayWidgetData` 提供 `headerText / statusText / courseLines / startMinutesOf /
  endMinutesOf / weekdayName`，`TodayNotify` 只负责组装通知；`WidgetRefresh.refresh` 同时刷新组件与通知，
  导入/改课/切课表/`onResume` 后立即同步。
- **开关与权限**：设置 → 提醒 → 「通知栏常驻今天的课」。Android 13+ 首次开启请求 `POST_NOTIFICATIONS`
  （拒绝后设置页保留「已关闭」并提示）；关闭时写 `today_notify=false` 并撤通知 + 取消闹钟。
- **动作**：整卡点开 `MainActivity`；「刷新」只重画不打开 App；「关闭」关掉通知与闹钟（同一个 receiver，`exported=false`，
  只接受 App 自己的 PendingIntent 与系统广播）。
### 2.13 自动更新：检查 → 下载 → 交给系统安装（v1.47.4）

- **数据流**：SettingsActivity.checkUpdate() → UpdateChecker.check()（UpdateConfig.MANIFEST_URLS
  顺序试 Gitee / GitHub，8s/10s 超时、64KB 上限）→ UpdateInfo.parse 严格校验 → 比 versionCode →
  弹窗 → 点「下载更新」→ UpdateService.start() → UpdateDownloader（Range 续传、最多 4 轮补齐、
  边下边算 SHA-256、镜像重试）→ 完成通知 → UpdateInstallActivity → 系统安装器。
- **清单字段**：versionCode / versionName / minVersionCode / size / sha256 / url / mirrors /
  changelog / force / publishedAt。
- `requiresForce()` = force 或当前版本 < minVersionCode。
  只收 https（debug 包允许 http 便于本地联调），sha256 必须 64 位十六进制。
- **自动检查（v1.47.6）**：MainActivity.onResume → maybeCheckUpdateOnLaunch()（每个 Activity 实例
  只试一次，后台线程，失败静默）→ UpdateChecker.shouldAutoCheck()（isDue：上次检查 <=0 或距今
  >= 24h）→ check(ignoreSkip = false)（尊重「忽略此版本」）→ 有新版才 UpdatePrompt.show(..., fromAutoCheck = true)。
  检查收尾统一走 markAutoAttempt()：成功按 24h 节流，失败把上次检查时间回拨 22h（即 2h 后重试）。
  清单 force/minVersionCode 命中时弹窗不可关闭，只留「下载更新」。
- **弹窗统一在 UpdatePrompt.kt**：自动（下载 / 忽略此版本 / 稍后）与手动（下载 / 复制地址 / 稍后）共用，
  手动检查的「稍后」**不**写 skip（v1.47.5 之前误把「稍后」当跳过）。
- **不能静默安装**：Android 只允许系统安装器装 APK；本模块能自动的是「检查 + 下载 + 校验」，
  最后一步必须用户点一次。未知来源权限用 canRequestPackageInstalls() 检测并引导设置页。
- **通知与权限**：进度通知 4201、完成通知 4202（setAutoCancel(false)，拉起安装器后主动清除）；
  Android 13+ 下载前检查 POST_NOTIFICATIONS，否则用户看不见进度与安装入口。
- **发版流程**：`tools/make_update_json.ps1` 从构建好的 APK 算 size/SHA 并生成 update.json；APK 传 Release，update.json 提交到 master 根目录。真实仓库 `moricecc/class-grid`（Gitee public，默认分支 master），清单固定地址 `https://gitee.com/moricecc/class-grid/raw/master/update.json`。
### 2.14 自定义日程：精确时间段 → 课表比例方块（v1.47.7）

**数据**：`Todo(id, title, dateMillis, time, endTime, location, note)`；`time/endTime` 是 `HH:MM`，
`hasTimeRange()` 要求两者都能解析且 end > start。`CourseStore.loadTodos/saveTodos` 逐条容错，
旧 JSON 缺 `endTime/note` 用空串，不迁移、不丢数据。

**入口**：课表「+」→ BottomSheet（`dialog_add_options.xml`）三选一：手动添加课程（`CourseEditActivity`）、
手动添加日程（`TodoEditActivity`）、导入课表（`ImportActivity`）。日程 Tab 的「+」直接进
`TodoEditActivity`，与菜单里的「手动添加日程」是同一个模块。

**画到课表**：`MainActivity.updateUI()` 把 `todos` 交给 `ScheduleView.setTodos()`；`drawWeekPage` 在
`drawCourses` 之后调用 `drawTodos`。布局按 `dateMillis` 定位列：`dayIndex = (dateMillis - weekStart)/86400000`，
`weekStart = semesterStart + (week-1)*7d`。纵向用 `minutesToY()`：以 `sectionTimes` 每节 start/end 为锚点，
节内和课间空档都做线性插值，保证与课程卡片在同一条时间轴；找不到锚点（无节次设置）时退回表头高度。
早于第一节的事件按最小 16dp 高度画在当天列顶部，避免 0 高度消失。

**视觉**：浅透明底色 + 1.2dp 主题色描边（无顶部色帽），文字用既有 `textSub/textTiny` 画笔，
标题最多两行、放不下自动加省略号。

**注意**：日程按日期（不是按星期）落到具体那一天的列，所以跨周翻页时会自然消失/出现；
没有时间段的日程只出现在日程 Tab，不上课表。