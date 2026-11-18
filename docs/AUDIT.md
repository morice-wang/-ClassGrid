# 课格 ClassGrid 静态审计报告（v1.30.0 / versionCode 45）

> 审计对象：`app/src/main/java/com/classgrid/app/`（17 个 .kt，约 7.9k 行）+ 清单/资源/构建脚本
> 审计方式：**纯静态阅读 + grep/符号核对**。开发机无 ARM 设备/模拟器（`abiFilters` 只有 arm64-v8a、armeabi-v7a），未运行 App；构建由接管人负责，本报告不依赖运行时日志。
> 路径约定：`Xxx.kt:N` 均指 `app/src/main/java/com/classgrid/app/Xxx.kt` 第 N 行；非 Kotlin 文件写仓库相对路径。行号对应上述版本，若源文件被改动请以符号名为准。
> 口径：**P0** = 会导致用户数据错误/丢失且难察觉，必须修；**P1** = 可复现的数据丢失、崩溃、明显卡顿或资源泄漏；**P2** = 边界条件缺陷、体验/健壮性瑕疵、工程规范问题。

## 摘要

| 级别 | 数量 | 主题 |
|---|---|---|
| P0 | 1 | 课程主键 id 冲突 → 删除/编辑误伤多条课程 |
| P1 | 7 | 待办全量静默丢失；损坏文件留底失效 + 备份被空数据顶替；主线程 IO；`onDraw` 每帧读 Prefs/JSON；OCR 预处理 OOM 未捕获；ML Kit recognizer 泄漏；背景裁剪取景与预览不一致 |
| P2 | 15 | 周数无下限校验崩溃；倒序节次；JSON 导入无范围校验；Intent 载荷过大；主线程解码大图；主题未统一应用；学期起始不落盘；prefs 文件名重复；延迟弹窗无生命周期保护；Handler 未在 onDestroy 清理；明文签名口令；导出图片阻塞 UI；合并判重键过窄；版本标识不一致；设置页重复构建 |

**总体判断**：代码结构与注释质量较好，UI 细节（Canvas 排版、翻页动画）投入明显；主要风险集中在**数据可靠性**（id 撞车、容错把异常吞成空数据、备份只有一份）与**线程模型**（无 ViewModel/协程，IO 与偏好读取都落在 UI 线程），其次是 OCR 链路的资源与内存边界。

---

## P0

[P0] Course.kt:10 — 课程主键 `id` 默认取 `System.currentTimeMillis()`，同样模式的构造点还有 Course.kt:35（`fromJson` 缺 id 时的 `optLong` 默认值）、GridParser.kt:277（网格解析循环内逐条 `Course(id = System.currentTimeMillis(), …)`）、OcrParser.kt:1119（`ParsedCourse.build()`）、CourseEditActivity.kt:197、TodoEditActivity.kt:83 — 触发条件：一次截图 OCR/网格识别产出 ≥2 门课（同一毫秒内连续构造，几乎必然撞车），或导入缺 `id` 字段的 JSON（`fromJsonArray` 循环内同样连号）— 影响：MainActivity.kt:901 `courses = courses.filter { it.id != course.id }` 会把同 id 的课程**一起删除**且界面只显示一门消失；CourseEditActivity.kt:215 `indexOfFirst { it.id == newCourse.id }` 只替换第一条，保存后另一门同 id 课仍保留旧内容（改 A 显示成改 B）；App 没有课程列表管理页，误删只能整表清空重录 — 建议修法：`Course` 的 id 交由工厂/存储层分配（如 `AtomicLong` 自增，初值取现有 `max(id)+1`；或 `UUID`），解析器内部先产出无 id 的草稿对象，在 `CourseStore` 入库时统一编号；`mergeAndSave` 内对导入项重建 id，删除改为「id + 名称 + 星期 + 起止节」多重校验后再删。**【已修复 v1.31.0：新增 `object UniqueId`（毫秒×1000 + 进程内自增，`@Synchronized`）统一发号，Course.kt 默认值/fromJson/GridParser/OcrParser/CourseEditActivity/TodoEditActivity 六处全部改用它；`CourseStore.mergeAndSave` 覆盖时保留原 id、新增入库时重新发号、末尾 HashSet 兜底修复历史重复 id】**

---

## P1

[P1] CourseStore.kt:73-83 — `loadTodos` 用 `arr.getJSONObject(i)`、`o.getLong("id")`、`o.getString("title")`、`o.getLong("date")` **强取**字段，第 :84 行的 `catch (e: Exception)` 把任何一条的缺失/类型错误吞掉并整体返回 `emptyList()` — 触发条件：`todos.json` 中任意一条缺字段、字段类型变化（旧版本写 `date` 为字符串等）、或写入中断导致文件尾部不完整 — 影响：所有自定义待办在界面上**静默消失**；随后用户新增一条待办时 TodoEditActivity.kt:80 读到空列表、:90 再 `saveTodos`，把仅存的残留条目也一并抹掉，不可恢复（`saveTodos` 无备份）— 建议修法：改用 `optLong`/`optString` + 逐条 `try/catch`，坏条目跳过并保留其余；`saveTodos` 复用 `save()` 的 `.tmp + 备份` 写入策略，并为 `todos.json` 增加损坏留底。

[P1] Course.kt:147-159 — `CourseJson.decode` 在函数内部 `catch (Exception) { emptyList() }`，异常不再冒泡，因此 CourseStore.kt:25-32 中「解析失败 → 把文件改名为 `courses_corrupt.json` 留底」的分支**实际不可达**；同时 CourseStore.kt:46-51 的 `save()` 会在写新文件前无条件把当前主文件 `renameTo(BACKUP_FILE)`，覆盖唯一旧备份 — 触发条件：`courses.json` 被写坏（写一半掉电、外部编辑器改坏、磁盘错误）后用户继续使用 — 影响：1) 打开 App 看到空课表，与「损坏文件会留底可恢复」的注释承诺相反；2) 一旦触发保存（增删课/清空/导入），唯一的 `courses_backup.json` 被“空列表或残缺列表”顶替，再保存一次后原始数据彻底无副本 — 建议修法：`decode` 区分「空文件」与「解析失败」（失败时抛出或返回 `null`/`Result`）；`load` 在解析失败时不要自动进入可写状态，改为提示并提供「尝试恢复备份」；`save` 只在新的 json 非空且校验通过时轮换备份，并用 `.tmp` 完成后 `fsync`/二次校验再 rename。

[P1] MainActivity.kt:143 — `onResume → loadData()` 在 UI 线程同步执行 `CourseStore.load`（内部 `File.readText()` + `JSONArray` 解析，CourseStore.kt:24）与 `loadTodos`（CourseStore.kt:73）；同样的同步读写在 CourseEditActivity.kt:47/115/214/217、SettingsActivity.kt:321/426/443/456/472、OcrResultActivity.kt:92、ImportActivity.kt:436、TodoEditActivity.kt:80/90、BgCropActivity.kt:51/157-167 — 触发条件：任何一次「返回主界面」（课程/待办越多越慢）、编辑页保存、设置页导出、OCR 确认导入 — 影响：主线程被磁盘 IO 与 JSON 解析阻塞，典型 5~50ms（数百条数据时更久），表现为返回掉帧、点击无响应；低端机/慢存储上存在 ANR 风险 — 建议修法：引入 `lifecycleScope + Dispatchers.IO`（或最小改动：`Executors.newSingleThreadExecutor`）加载后切主线程渲染；内存中保留一份 `courses/todos` 作为唯一状态，写操作走同一条串行队列，避免每次 `onResume` 全量重读。**【已修复 v1.31.0】**

[P1] ScheduleView.kt:301 — `onDraw` 内调用 `SectionTimes.currentSection(context)`，其内部 `SectionTimes.load()`（SectionTimes.kt:35-51）每次都 `SharedPreferences.getString` + `JSONArray` 解析；同文件 :305/:442（`Prefs.backgroundStyle`）、:454（`Prefs.backgroundImage`）、:510（`Prefs.cardStyle`）、:513（`Prefs.cardShadow`）以及 `onDraw` 开头的 `ScheduleColor.primary()`（`Color.parseColor`）也都是逐帧/逐卡片取值 — 触发条件：任何滚动、双指缩放、左右翻周（拖动期间 60fps 连续重绘），课程越多 `drawCard` 调用越多 — 影响：每帧数百次 SharedPreferences 读取与字符串解析、每帧一次 `Color.parseColor` 与 JSON 解析，造成明显掉帧、拖动发涩、GC 抖动；低端机翻页动画不达 60fps — 建议修法：把 `sectionTimes/currentSection`、`backgroundStyle/背景图`、`cardStyle/cardShadow`、`primaryColor` 全部缓存为成员变量，在 `setData`/`onAttachedToWindow`/设置返回后刷新；`currentSection` 按分钟（或 15~30s）节流，`onDraw` 内只做纯绘制。**【已修复 v1.31.0】**

[P1] ImportActivity.kt:144 — `recognizeRound` 的预处理（:267-281 `buildInputImage` → :301-346 `loadDownsampled` 1600px、第 3 轮再 :283 放大 2 倍 → 3200px 级 ARGB_8888，峰值数十 MB）用 `catch (e: Exception)` 兜底，**捕获不到 `OutOfMemoryError`** — 触发条件：选入 4K/长截图、一次连续导入 9 张、低内存设备（同时还有 ML Kit 模型常驻）— 影响：预处理阶段直接 OOM 崩溃（无任何提示，用户以为闪退），且崩溃发生在识别中途，已识别结果一并丢失 — 建议修法：`catch (e: Throwable)` 或单独 `catch (e: OutOfMemoryError)` 转成友好错误；限制预处理目标像素（长边 ≤2000px）、第 3 轮复用第 1 轮位图而不是再放大、每轮结束 `recycle()`；必要时在 `largeHeap`/`onTrimMemory` 上做降级。**【已修复 v1.31.0】**

[P1] ImportActivity.kt:160 — 每轮识别都 `TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())` 新建 `TextRecognizer`，全工程没有任何 `close()` 调用 — 触发条件：每张图最多 3 轮、一次导入最多 9 张（单次最多 27 个 recognizer），反复导入 — 影响：ML Kit 的 detector/native 资源持续泄漏，内存上涨（中文识别器持有模型句柄）；每轮重新初始化也拖慢识别 — 建议修法：在 `ImportActivity` 内 `private val recognizer by lazy { TextRecognition.getClient(...) }` 复用，`onDestroy` 中 `recognizer.close()`；或提到 `object` 级单例并明确生命周期。**【已修复 v1.31.0】**

[P1] BgCropActivity.kt:152 — `saveCropped()` 用 `minOf(fw/src.width, fh/src.height)`（contain/fit）把 9:20 取景框映射回原图，而预览 `BgCropView.onDraw` 在 :227 用 `maxOf(frame.width()/bmp.width, frame.height()/bmp.height)`（cover）铺满取景框 — 触发条件：选入宽高比与 9:20 差异较大的图片（横图、长截图、窄长图）作为课表背景 — 影响：保存出的背景与用户在预览里看到的取景**不一致**（偏移、缩放比例不同），表现为背景内容错位或被拉宽/拉长；用户无法通过反复调整修正 — 建议修法：抽 `private fun coverScale(frameW, frameH, bmpW, bmpH) = maxOf(...)`，预览与保存共用同一比例；或把预览改为 fit 绘制（允许留白）以对齐保存逻辑。**【已修复 v1.31.0】**

---

## P2

[P2] CourseStore.kt:193-194 — `totalWeeks()` 直接 `getInt(KEY_TOTAL_WEEKS, 20)`，读取时不做范围限制（只有 `setTotalWeeks` 在 :197 做 `coerceIn(1,40)`）；MainActivity.kt:248 用 `Array(weeks) { … }`、:249 用 `(…).coerceIn(0, weeks - 1)` — 触发条件：`total_weeks` 为 0 或负数（旧版本遗留、adb 修改、跨版本恢复数据）— 影响：打开「修改当前周」弹窗时抛 `NegativeArraySizeException`（weeks<0）或 `IllegalArgumentException: Cannot coerce value to an empty range`（weeks==0），闪退 — 建议修法：getter 同样 `coerceIn(1, 40)`，并在 `Array(weeks)` 前对 weeks 做兜底；`showModifyCurrentWeekDialog` 里 `weeks <= 0` 直接 return。

[P2] OcrResultActivity.kt:256-257 — 保存对话框直接取 `startSpinner.position + 1` 与 `endSpinner.position + 1`，**未校验 start ≤ end**（CourseEditActivity.kt:177-180 有该校验）— 触发条件：用户在核对页把「结束节」调到「开始节」之前 — 影响：写入 `startSection > endSection` 的脏课程；`ScheduleView.drawCard` 对 `b <= t` 有守卫会直接跳过绘制，于是课表上不显示，但日程页仍会列出该条（且课表上无法点击删除），用户只能整表清空 — 建议修法：确认前校验并 Toast（复用 `R.string.section_invalid`），或在 `copy` 时对二者排序。

[P2] Course.kt:34-52 — `fromJson` 全部使用 `opt*` 且**不做范围钳制**（`dayOfWeek` 可为 9，`startSection` 可为 99，`startWeek` 可大于 `endWeek`），CourseStore.kt:131-142 的 `mergeAndSave` 又直接入库；App 没有课程列表管理页 — 触发条件：粘贴/导入第三方生成的 JSON，或历史版本写入的越界字段 — 影响：这类课程不参与任何绘制与删除入口（`ScheduleView.handleClick` 只命中 1..7 与有效节次区间），变成“隐形数据”，只能靠设置页「清空课表」整体清掉 — 建议修法：`fromJson` 内 clamp（day 1..7、节次 1..15 且 start≤end、周 1..40 且 start≤end）或丢弃非法条目，并在导入结果里提示被忽略的条数；补一个课程列表/管理入口。

[P2] ImportActivity.kt:249-250 — 通过 Intent extra 传递完整 OCR 原文（多张拼接，:117 `joinToString`）和全部课程 `CourseJson.encode(...)` 给 OcrResultActivity — 触发条件：一次导入 9 张截图、原文累计数百 KB（含大量噪声行）— 影响：接近 Binder 1MB 事务上限时抛 `TransactionTooLargeException`，恰好发生在识别成功跳转时，用户看到崩溃且结果全丢 — 建议修法：原文与课程改为写入临时文件（或进程内单例缓存/ViewModel）后只传 id/路径；至少对原文做长度截断（核对页也只展示 3 行）。

[P2] OcrResultActivity.kt:62 — `binding.imgPreview.setImageURI(Uri.parse(imageUri))` 在 `onCreate` 主线程按**原图尺寸**解码再缩放到 `maxHeight 220dp` 的预览（activity_ocr_result.xml:51-58）— 触发条件：每次 OCR 识别完成进入核对页（截图通常 1080×2400 以上）— 影响：首帧进入卡顿 100ms~1s，超大图有 OOM 风险 — 建议修法：用 `BitmapFactory.Options.inSampleSize` 在后台线程解码缩略图后再 `setImageBitmap`（或用 Glide/Coil）。

[P2] MainActivity.kt:52 — `Prefs.applyNightMode(this)` 只在 MainActivity.kt:52 与 SettingsActivity.kt:50 调用，其余 Activity（CourseEditActivity、ImportActivity、OcrResultActivity、SectionTimeActivity、TodoEditActivity、BgCropActivity）都不设置夜间模式 — 触发条件：进程被回收后，用户从最近任务/通知直接回到上述页面 — 影响：AppCompat 默认 `MODE_NIGHT_UNSPECIFIED` 时按系统设置渲染，用户选「夜间」但在这些页面看到浅色底/浅色控件，出现深浅混排与白屏闪烁 — 建议修法：抽 `BaseActivity`（或 `Application.onCreate` 中统一 `AppCompatDelegate` 初始化），所有 Activity 在 `super.onCreate` 前应用；`Application` 初始化是更稳的做法。

[P2] CourseStore.kt:183-187 — 未设置过学期开始日期时，`semesterStart()` 每次按“当前月份”临时推算（:296-309：8 月及以后取当年 9 月第一个周一，否则取 2 月第一个周一）且**不写回** prefs — 触发条件：用户从未在设置里选过日期，跨过 2 月/8 月边界继续使用（如寒假结束进入春季学期）— 影响：`currentWeek` 突然跳变（可能从第 20 周跳回第 1 周），课表标题周次与课程周次显示错位，用户以为课表“自己变了” — 建议修法：首次读取时把推算结果写回；或在设置页/首启引导里显式确认学期开始日期。

[P2] SectionTimes.kt:36 — `getSharedPreferences("classgrid_prefs", MODE_PRIVATE)` 在三处（:36、:61、:66）硬编码文件名字面量，与 `Prefs.NAME`（CourseStore.kt:152）是同一值的副本 — 触发条件：未来任一方改文件名/做迁移 — 影响：节次时间设置静默丢失并回落默认 15 节；且两个 `object` 共享同一 prefs 文件，键名冲突没有编译期保护（当前 keys 无冲突）— 建议修法：`SectionTimes` 复用 `Prefs` 的 `sp()`/`NAME`，或把节次读写收进 `Prefs`。

[P2] MainActivity.kt:79 — `binding.root.postDelayed({ showGuideDialog() }, 350)` 的回调里（:86）直接 `MaterialAlertDialogBuilder(this).show()`，没有 `isFinishing || isDestroyed` 判断；项目内**没有任何 `onDestroy` 覆写**，`weekPickerDialog` 等引用也不会被清理 — 触发条件：冷启动/更新后 350ms 内 Activity 被销毁（快速返回、系统回收、旋转屏幕）— 影响：`WindowManager.BadTokenException`（Unable to add window -- token ... is not valid）崩溃，或窗口泄漏日志 — 建议修法：回调内先判 `isFinishing || isDestroyed` 再弹；`onDestroy` 中 `removeCallbacks` 并 `dismiss` 持有的对话框。

[P2] ImportActivity.kt:100 — OCR watchdog 通过主线程 `Handler`（:73）`postDelayed(25000 + n*8000)`，只在 `launchResult`（:246）与 `finishOcrWithError`（:256）里 `removeCallbacksAndMessages`，**`onDestroy` 不清理**（全工程无 `onDestroy`）— 触发条件：识别进行中用户按返回退出导入页 — 影响：`Handler` 持有已销毁的 Activity 最长约 25s + 9×8s ≈ 97s，期间 Activity 无法回收（内存泄漏）；若回调触发且未被 `ocrDone` 拦住，还会弹 Toast 指向已销毁页面 — 建议修法：`onDestroy` 中 `ocrTimeoutHandler.removeCallbacksAndMessages(null)` 并置 `ocrDone = true`，`Thread`/ML Kit 任务回调里先判 `isFinishing`。

[P2] app/build.gradle.kts:26 — release 签名配置把 `storePassword = "******"`（:26）与 `keyPassword = "******"`（:28）明文写死，`storeFile = file("../release.keystore")`（:25）也随仓库分发（README 同样公开了口令）— 触发条件：仓库外泄或对外开源 — 影响：任何人都能用该密钥签名同包名 APK，构造可覆盖安装的分发面攻击；也违反基本密钥管理规范 — 建议修法：改从 `local.properties`/环境变量读取，keystore 加入 `.gitignore`；对外发布前换新密钥，并撤销已泄露口令在历史文档中的记载。

[P2] SettingsActivity.kt:451 — 导出图片在 UI 线程链式执行 `ScheduleView.renderToBitmap(360dp)`（内部：`measure/layout` 自身（ScheduleView.kt:1018-1022）、创建约 1080×2800 的 `ARGB_8888`（:1023，≈12MB）、`draw(canvas)`）与 `bmp.compress(PNG, 100)`（:456）— 触发条件：设置 → 导出图片 — 影响：界面冻结 0.5~2s，低端机 OOM 风险；此外 `renderToBitmap` 会临时 `measure/layout` 这个 View 且**不恢复**自身的测量尺寸（当前调用点用的是 detached 实例，暂未表现，但方法本身不安全）— 建议修法：在后台线程完成位图创建/绘制/压缩（Canvas 与 Bitmap 不要求主线程），`renderToBitmap` 结束时恢复原 `measuredWidth/Height` 或改用独立的离屏 View/`Picture`；PNG 可考虑 JPEG 或降低分辨率。

[P2] CourseStore.kt:135-137 — `mergeAndSave` 的判重键是 `name + dayOfWeek + startSection`，**不包含 `endSection`、起止周、`weekType`、教师/教室**，命中即整条替换 — 触发条件：导入“同一门课、同一星期、同一开始节，但结束节或周次区间不同”的记录（如 1-2 节与 1-4 节分成两条、前 8 周/后 8 周分开写）— 影响：后导入的记录覆盖先导入的，周次/时长信息丢失；反之“同名不同开始节”会作为两条独立课程并存，课表上叠画、日程里重复 — 建议修法：判重键加入 `endSection` 与 `startWeek/endWeek`，或对「同名 + 同星期 + 节次区间相交」的条目做周次区间合并（记录 `excludedWeeks`）。

[P2] README.md:12 — 交接文档写「当前版本 v1.9.0（versionCode 13）」，仓库目录名也是 `classgrid-source-v1.9.0`，而 `app/build.gradle.kts:14-15` 实际为 `versionCode 45 / versionName 1.30.0`（`CHANGES.md` 最新条目 v1.30.0）— 触发条件：新接手者据此判断版本/回滚 — 影响：误以为拿到旧版本源码，或按错误版本定位变更；审计/回归时选错基线 — 建议修法：README 版本号改为指向 `app/build.gradle.kts` 单一事实源（不写具体数字），或交接时同步更新；目录名建议随版本重命名。

[P2] SettingsActivity.kt:63 — `onCreate` 末尾 `buildSettings()`，`onResume`（:69）再次 `buildSettings()`，首次进入会把整页设置行构建两遍（`removeAllViews` 后重建全部子 View）— 触发条件：每次打开设置页 — 影响：多余 10~20ms 构建开销与潜在闪烁；`onResume` 与 `onCreate` 行为重复，后续改动容易只改一处导致状态不一致 — 建议修法：`onCreate` 只建一次，`onResume` 改为仅刷新需要变化的文案（外观/背景/周数/节次摘要）或去掉重复调用。

---

## 已核查、未发现问题的项（避免误报，供复核）

- **minSdk 24 API 兼容**：全工程无 `java.time.*`、无 `java.util.stream`、无 `List.of`/`Set.of`、无 `Objects.requireNonNull`；唯一 `isBlank()` 在 OcrParser.kt:705，是 Kotlin 标准库对 `CharSequence` 的扩展（内部只判空白字符，不依赖 Java 11 API），minSdk 24 安全。AndroidX（`enableEdgeToEdge`、`PickVisualMedia`、`registerForActivityResult`）均带 API 24 兼容实现；`Calendar`/`SimpleDateFormat` 的使用也与 24 兼容。
- **`!!` 空安全**：共 13 处（BgCropActivity.kt:58、CourseEditActivity.kt:193、GridParser.kt:143，以及 OcrParser.kt:363/403/427/663/680/682/854/855/990/991），逐一核对均有前置判空/过滤或同表达式守卫，未发现可达的 NPE 路径。
- **Canvas 除零 / NaN**：`colW()/secH()` 由 `base × scale` 得出，`scale ∈ [1, 2.4]` 不会为 0；`handleClick`（ScheduleView.kt:984）在宽度极小时可能产生 Inf/NaN，但随后 `day !in 1..7` / `section` 范围判断会过滤；`drawCard` 有 `r <= l || b <= t` 守卫；`animateSettle` 的时长计算有 `coerceIn(140, 300)`；未发现可触发崩溃的除零路径。
- **静态持有 Activity/Context**：未发现 `static`/`companion object` 字段持有 Activity、View 或非 Application Context；`ScheduleView` 只持有自身 `context`（View 正常行为）。
- **未注销 Listener**：项目未注册广播接收器、传感器监听、ContentObserver 或 `addOnPreDrawListener`，故不存在“注册未注销”类泄漏；`ScheduleView` 的 `GestureDetector`/`ScaleGestureDetector` 只回调自身。
- **深色模式资源**：`values-night/themes.xml`（主题色反转、`windowLightStatusBar=false`）与 `values-night/colors_extra.xml`（`window_bg #0F1117`）齐备；布局中无硬编码 `#RRGGBB`（仅 drawable 矢量与 values 中有字面色），13 个 ImageView 全部使用 `app:tint="?attr/..."` 或系统属性色；Kotlin 侧颜色均按 `ScheduleColor.isDarkMode()` 取值。**未发现深色资源缺失**（死资源仅有 `colors.xml` 的 `schedule_grid_line`、`section_text`，全工程零引用，可清理）。
- **Prefs 键冲突**：`Prefs` 的 11 个键与 `SectionTimes.KEY_SECTION_TIMES` 互不相同，当前无冲突（结构隐患见上文「SectionTimes 硬编码 prefs 文件名」条）。
- **`readClipboard`**（ImportActivity.kt:447-454）：`getItemAt(0)` 越界异常被整体 `try/catch` 捕获，安全。
- **OCR 合并语义**（OcrParser.kt:1040-1069）：`mergeParsed` 只在 base 字段缺失或非法时才采用 other，不会用低质量结果覆盖高质量字段；`ParsedCourse.build()`（:1093-1134）对节次/周次/地点做了兜底与校验。
- **备份写入的原子性**：`save()` 采用「写 `.tmp` → 轮换备份 → rename」的顺序，正常路径下不会出现半写主文件（风险在异常路径的“空数据顶替备份”，见上文 `decode`/备份条）。

---

## 最值得优先修的三件事

1. **课程 id 的唯一性（P0）**——一条改动就能消掉「删一门删一片」「编辑串台」这类用户无法自行发现的静默数据错误；建议在存储层统一分配 id，解析器只产出草稿。这是唯一会让用户数据**错误**（而不仅是丢失/卡顿）的问题，优先级最高。
2. **持久化可靠性（`loadTodos` 容错 + `decode`/备份策略）**——两者是独立的“静默丢数据”路径，且都发生在用户无感的启动/保存环节；修法局部（`opt*` + 逐条容错、`decode` 区分失败、备份只在合法数据时轮换），收益是彻底消除“课表/待办突然全空”的支持成本。
3. **线程与绘制开销（主线程 IO + `onDraw` 每帧读 prefs/JSON）**——这是日常体验里最持续可感的问题（每次返回掉帧、拖动发涩），也最容易在中低端机上放大成 ANR 投诉；建议先做「缓存偏好与节次表 + 数据加载移后台」两点，改动集中、风险低。
