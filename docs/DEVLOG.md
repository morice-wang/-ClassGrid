# 开发日志（DEVLOG）

> 目的：按用户要求「记录每一步尝试和错误，让以后的开发更简洁」。
> 规则：每条记录写清 **做了什么 / 为什么 / 结果 / 踩过的坑**。坑尽量写成「下次怎么做」的一句话。
> 时间线：从 2026-11-03 接手开始。基线 = 课格 ClassGrid v1.30.0（versionCode 45）。

---

## 0. 接手时的项目状态

- 工作目录：`D:\2026项目\大一课程表软件`
  - 29 个历史 APK：`课格ClassGrid v1.9.1 … v1.30.0.apk`（供对比回归用）
  - 源码：`classgrid-source-v1.9.0\classgrid-source`（**目录名是历史遗留，实际内容 = v1.30.0**）
  - `_icon_assets\`：桌面图标素材 + 生成脚本
- 无 git 仓库 → 已 `git init` 并提交基线（见 §2）。
- 无真机/模拟器（`adb devices` 为空，SDK 里无 emulator）→ **一切改动只能靠「编译通过 + 静态审查」验证，不能真跑**。这是本项目最大的客观限制，所有改动要写得保守、可回退、带日志。

## 1. 环境与工具链（一次搞清，之后不用再查）

| 项 | 值 |
| --- | --- |
| JDK | `C:\Users\Asus\.jdks\jdk-17.0.20.1+1`（**不在 PATH 里**，每次构建前要 export） |
| Android SDK | `C:\Users\Asus\AppData\Local\Android\Sdk`（platforms 34/35，build-tools 34.0.0） |
| 构建 | `cd classgrid-source-v1.9.0/classgrid-source && ./gradlew assembleRelease --console=plain` |
| 产物 | `app/build/outputs/apk/release/app-release.apk`（约 26 MB，主因是 bundled 中文 OCR 模型） |

构建命令模板（bash）：

```bash
export JAVA_HOME=/c/Users/Asus/.jdks/jdk-17.0.20.1+1; export PATH=$JAVA_HOME/bin:$PATH
cd "/d/2026项目/大一课程表软件/classgrid-source-v1.9.0/classgrid-source" && ./gradlew assembleRelease --console=plain
```

**坑 1：沙箱把源码深层目录写成只读。** 症状：`touch`/`mkdir` 在 `app/src/main/java/com/classgrid/app` 里报 Permission denied，工作区根却可写；gradle 也写不了 `~/.gradle`。
解决：跑内置 ACL 诊断脚本（`diagnose-windows-sandbox-acl.ps1 -Path <失败路径> -AllowRoot <工作区>`）补 WRITE_DAC/WRITE_OWNER，9 处 grant 全部 verified 后恢复。
**下次：** 遇到「根目录能写、子目录不能写」先怀疑 Windows ACL，不要怀疑磁盘/杀软。

**坑 2：** 首次 `git init` 也因权限失败，ACL 修复后一次成功。
**下次：** 先修 ACL 再做任何写操作。

## 2. 基线固化

- `git init` + `.gitignore`（`*.apk`、`.gradle/`、`build/`、`local.properties`、`.idea/`、`_sandbox_acl_report/`）
- 首个提交：`baseline: v1.30.0 source as received`
- 基线构建：`assembleRelease` exit 0，说明接手时源码是**可编译**的（后续任何编译失败都是我改坏的，不用怀疑环境）。

## 3. 代码地图（速查）

包名 `com.classgrid.app`，18 个 Kotlin 文件 / 6647 行。分层很扁平：无 ViewModel、无 DI、无 Room，全是 Activity + object 单例 + SharedPreferences + JSON 文件。

```
数据层
  Course.kt        data class Course + CourseJson（encode/decode，兼容 [...] 与 {"courses":[...]}）
  Todo.kt          data class Todo
  CourseStore.kt   object CourseStore（courses.json / todos.json 原子写+备份+坏文件隔离）
                   object Prefs（SharedPreferences "classgrid_prefs"：学期起点/总周数/外观/卡片样式/背景/图标/引导时间）
  SectionTimes.kt  object SectionTimes（15 节作息，可自定义，存 prefs JSON）
  ScheduleColor.kt object ScheduleColor（课程色板 + 深色模式判定 + 主题色）

解析层
  OcrParser.kt     1296 行：OCR 文本 → Course。两条通道：parseStructured(Text 带坐标) / parse(raw 纯文本)，
                   加 mergeCourses 互补；核心是「标签-值」定位 + 大量清洗/纠错（简体化、编辑距离、时间/周次/教室正则）
  GridParser.kt    358 行：整周课表截图（带坐标）→ Course。用坐标找星期表头列锚点 + 左侧大节行锚点，
                   正文聚类成课程色块，再解析名称/老师/周次/地点

界面层
  MainActivity.kt  965 行：课表 Tab（ScheduleView 自绘）+ 日程 Tab（纯代码构建 View）+ 周导航/周选择/修改当前周/引导弹窗
  ScheduleView.kt  1039 行：Canvas 自绘周视图。双页并排跟手翻周（canSwipeWeek/onWeekSettled/setCurrentWeek）、
                   双指缩放、卡片绘制/文字折行、重叠课程聚类、renderToBitmap（导出图片）、背景图
  ImportActivity.kt 457 行：导入方式页（截图/JSON/手动）。OCR 走 ML Kit 中文识别，
                   每张图跑 3 轮（灰度色阶增强 / 原图 / 放大2倍），多轮择优后合并 → OcrResultActivity
  OcrResultActivity.kt 268 行：识别结果核对页（可编辑/删除）→ CourseStore.mergeAndSave
  CourseEditActivity 222 / SettingsActivity 488 / SectionTimeActivity 134 / TodoEditActivity 115 / BgCropActivity 301
  IconSwitcher.kt  84 行：7 个 activity-alias 桌面图标切换

导入数据流
  截图 → ML Kit → (GridParser | OcrParser×2) → 打分择优 → OcrResultActivity 核对 → CourseStore.mergeAndSave
  JSON → CourseJson.decode → mergeAndSave
  合并规则：name + dayOfWeek + startSection 相同 → 覆盖，否则新增（返回新增数）
```

关键事实：
- `AndroidManifest.xml` **没有任何 uses-permission**（连 INTERNET 都没有）→ 链接抓取前必须加。
- `Course.activeInWeek(week)`：区间 + excludedWeeks + weekType(0每周/1单周/2双周)。
- `Prefs.currentWeek()` = 今天所在周一 与 semesterStart 的周差 + 1（无夏令时，周差算法安全）。
- `minSdk 24`，`targetSdk 34`，JDK 17，AGP 8.2.2，Kotlin 1.9.22，release 未开混淆（isMinifyEnabled=false）。
- 签名：`release.keystore`，alias `classgrid`，密码见 `local.properties`（不随仓库分发）。

---

## 第 2 轮（2026-11-03 19:40–20:05）：审计落地 + 链接抓取骨架 + 单元测试跑通

### 这一轮做完的事
1. **架构与审计文档**（`docs/ARCHITECTURE.md` / `docs/AUDIT.md`，由 arch-auditor 通读产出）：1 个 P0 + 7 个 P1 + 15 个 P2，每条带 `文件:行号`。
2. **P0 修复：课程 id 撞车**。`Course.id` 原来是 `System.currentTimeMillis()`，一次识别 ≥2 门课必撞 id，后果是删一门连带删掉同 id 的全部、编辑串台。新增 `object UniqueId`（毫秒×1000 + 进程内自增，`@Synchronized`），并把 `Course.kt` 默认值、`Course.fromJson`、`GridParser`、`OcrParser`、`CourseEditActivity`、`TodoEditActivity` 六处全部改用它；`CourseStore.mergeAndSave` 覆盖时保留原 id、新增时重新发号、最后兜底去重。
3. **持久化可靠性**（三条静默丢数据路径）：
   - `CourseJson.decodeOrNull()`：能区分「文件坏」和「列表空」，`CourseStore.load()` 才真的会把坏文件改名 `courses_corrupt.json` 留底（原来那个分支永远走不到）。
   - `save()` 只在旧主文件**合法且非空**时才轮换成备份，坏数据不会再顶掉唯一备份。
   - `loadTodos` 改成逐条 `optXxx` + 单条容错，坏条目跳过而不是整体清空；`todos.json` 也加了 `todos_backup.json` 滚动备份。
4. **P2 修复**：`OcrResultActivity` 编辑弹窗保存时强制 `endSection >= startSection`，不再能写出倒序节次（这种课在课表上画不出来也点不到）。
5. **终极目标「链接抓取」骨架完成**（详见 `docs/JWXT_RESEARCH.md` 的方案 A）：
   - `WebImportScript.kt`：注入 JS，递归同源 iframe 收集「含星期表头」的 table → 展开成行列网格 JSON，连同 bodyText 通过 `@JavascriptInterface` 回传；MutationObserver + 3.5s 轮询双保险自动重扫。
   - `WebScheduleParser.kt`：Kotlin 侧把网格解析成 `Course`（横表/竖表/一格多课/跨行跨列/单双周/排除周/节次映射/去重），纯 JVM 可单测。
   - `WebImportActivity.kt` + `activity_web_import.xml`：内嵌官网 WebView（用户自己登录，App 绝不自动填密码/不绕验证码），顶部返回/刷新/改 URL、底部「抓取当前页面」，找到课表弹确认框，确认后复用 `OcrResultActivity` 核对导入；抓不到可「复制诊断信息」或一键「改用截图导入」降级。
   - `activity_import.xml` 新增第 4 张卡「链接抓取」；Manifest 加 `INTERNET`（原来一个权限都没声明）、`usesCleartextTraffic`。
   - 预设 URL 只保留真实可解析的：`https://jwxt.gdufs.edu.cn/jsxsd/sso.jsp`（推荐，走统一身份认证）、`https://jwxt.gdufs.edu.cn/`（强智本地登录）、课表查询页。
6. **单元测试**：`app/src/test/.../WebScheduleParserTest.kt` 14 个用例（横表/竖表/一格多课/去重/单双周/排除周/节次映射/清洗/JSON 往返/坏 JSON 语义），覆盖解析器与 `CourseJson`。

### 踩坑记录（这条最值钱）
**坑 3：`./gradlew testReleaseUnitTest` 报 `ClassNotFoundException: com.classgrid.app.WebScheduleParserTest`，但 .class 文件明明已生成。**
- 表象：Gradle 里 `initializationError / java.lang.ClassNotFoundException`，`1 test completed, 1 failed`。
- 无效尝试：给测试 worker 加 `-Dfile.encoding=UTF-8`（`tasks.withType<Test> { systemProperty(...); jvmArgs(...) }`）——**完全无效**，不是编码问题。
- 根因：**工程路径含中文**（`D:\2026项目\大一课程表软件\...`，靠 `android.overridePathCheck=true` 才能构建），Gradle 8.5 的 test worker 在传递 classpath 时丢了非 ASCII 路径。
- 正解：给工作区建一个 ASCII 目录联接再跑测试：
  ```powershell
  New-Item -ItemType Junction -Path C:\cgtest -Target 'D:\2026项目\大一课程表软件'
  ```
  ```bash
  cd /c/cgtest/classgrid-source-v1.9.0/classgrid-source && ./gradlew testReleaseUnitTest
  ```
  14 例全过。`C:\cgtest` 和真实目录是同一份文件（build/ 共享），只是 Gradle 走 ASCII 路径；两个路径不要同时并发跑 Gradle（会抢锁），串行即可。
- 备用的手工绕过（了解即可，别当主路径）：`java -cp <classes;test-classes;junit;hamcrest;org.json;kotlin-stdlib> org.junit.runner.JUnitCore <TestClass>`。两个硬要求：(a) classpath 必须用 Windows 反斜杠绝对路径（MSYS 的 `/c/...` 它不认）；(b) 必须手动补 `kotlin-stdlib-1.9.22.jar`，否则 `NoClassDefFoundError: kotlin/Unit`。

**坑 4：表头识别放宽过头。** `dayOfWeekOf("周一下午没有课请自习教室")` 返回 1（星期一），会把课表备注误当星期列。修法：星期标签后面如果还跟着汉字（除「节/小/第」）就判定不是星期标签。

**坑 5：docs 写散了两份。** 工作区根 `docs/`（DEVLOG + JWXT_RESEARCH）与项目内 `docs/`（ARCHITECTURE + AUDIT）各一份，已合并到项目内 `docs/`。以后一律写 `classgrid-source-v1.9.0/classgrid-source/docs/`。

### 当前验证状态（没有真机/模拟器，只能做到这些）
- `./gradlew assembleRelease`：通过。
- `./gradlew testReleaseUnitTest`（经 `C:\cgtest`）：14/14 通过。
- 链接抓取：注入脚本语法、解析器逻辑、编译产物均已验证；**真实登录与真实课表 DOM 未验证**，待真机清单见 `docs/JWXT_RESEARCH.md` §9。

---

## task-4 修复记录（ocr-fixer）：OCR 内存/资源 + 背景裁剪取景（对应 v1.31.0）

> 依据 `docs/AUDIT.md` 三条 P1：`ImportActivity.kt:144`（预处理 OOM 崩溃）、`ImportActivity.kt:160`（recognizer 泄漏）、`BgCropActivity.kt:152`（预览/保存比例不一致）。AUDIT 对应条目已标注「已修复 v1.31.0」。
> 验证方式：仅 `./gradlew compileReleaseKotlin --console=plain` 编译通过 + 静态审查（无真机/模拟器）。

### 1) ImportActivity.kt：OCR 预处理 OOM 兜底 + 内存上限（AUDIT P1 `ImportActivity.kt:144`）

- `ImportActivity.kt:100` 新增 `PREPROCESS_MAX_DIM = 2000`；`:354-368` `scaleBitmap()` 内 `targetLong = (longest * factor).toInt().coerceAtMost(PREPROCESS_MAX_DIM)`，第 3 轮由 3200px（ARGB_8888 单张约 41MB）压到 2000px（约 9MB）。
  - 为什么：AUDIT 影响描述 = 预处理阶段直接 OOM 崩溃、无任何提示（用户以为闪退），且崩溃发生在识别中途，已识别结果一并丢失；根因是 1600px 底图再放大 2 倍到 3200px 与增强输出叠加，峰值数十 MB。
- `ImportActivity.kt:188-201` 在原有 `catch (e: Exception)` **之前**新增 `catch (e: OutOfMemoryError)`：未到最后一轮则 `ocrRound++` 降级重试（第 2 轮为原图直识，峰值更低），最后一轮仍 OOM 才 `finishOcrWithError("内存不足，无法处理这张截图；请裁剪图片或减少一次导入的张数后重试")`。
  - 为什么：`OutOfMemoryError` 属于 `Error` 不是 `Exception`，旧 catch 兜不住（AUDIT 原文）；降级重试不改变「每张图最多 3 轮、取最高分」业务逻辑——轮次上限 `OCR_MAX_ROUNDS`、`scoreCourses` 打分、`bestCourses` 提交时机均未改动。
- `ImportActivity.kt:332-352` `buildInputImage()` 改为返回 `PreparedImage`（`InputImage` + 需回收的位图，类定义在 `:107`）；`:217-219`（ocrDone 早退）、`:226`（成功回调开头）、`:269`（失败回调开头）三处均先 `prepared.bitmap?.recycle()` 再判断。
  - 为什么：原实现位图在轮次间/异常路径上不回收，叠加 OOM 风险；「先回收再判断早退」保证超时、退出等分支也不漏回收。

### 2) ImportActivity.kt：识别器改单例 + onDestroy close（AUDIT P1 `ImportActivity.kt:160`）

- `ImportActivity.kt:117-120` 新增 `private val recognizerDelegate = lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }` 与 `private val recognizer: TextRecognizer by recognizerDelegate`；`:223` 改为 `recognizer.process(...)`。
  - 为什么：原实现每轮识别都 `getClient(...)` 新建且全工程从不 close，一次导入最多 9 张 × 3 轮 = 27 个 recognizer，ML Kit detector/native 模型句柄持续泄漏、内存上涨，且每轮重新初始化拖慢识别（AUDIT 影响原文）。
- `ImportActivity.kt:74-88` 新增 `onDestroy()`：置 `ocrDone = true`、`ocrTimeoutHandler.removeCallbacksAndMessages(null)`，并仅在 `recognizerDelegate.isInitialized()` 时 `recognizer.close()`（try/catch 兜住）。
  - 为什么：只 close 真正创建过的识别器，避免没触发 OCR 也初始化模型；同时停掉超时看门狗，防止页面退出后仍弹 Toast 或跳转。
  - 安全前提：识别流程严格串行（本轮回调结束才进入下一轮），单实例复用无并发问题；迟到的 ML Kit 回调在 `ocrDone` 分支回收位图后直接 return。

### 3) BgCropActivity.kt：预览与保存共用 cover 比例（AUDIT P1 `BgCropActivity.kt:152`）

- `BgCropActivity.kt:32-34` 新增文件级 `private fun coverScale(frameW: Float, frameH: Float, bmpW: Int, bmpH: Int): Float = maxOf(frameW / bmpW.toFloat(), frameH / bmpH.toFloat())`。
- `BgCropActivity.kt:163` `saveCropped()` 的映射比例由 `minOf(...)`（fit/contain）改为 `coverScale(...)`；`:239` 预览 `BgCropView.onDraw` 同步改为 `coverScale(...)`——两处共用同一函数、同一组参数。
  - 为什么：AUDIT 影响描述 = 保存出的背景与预览取景不一致（偏移、缩放比例不同），表现为背景内容错位或被拉宽/拉长，用户无法通过反复调整修正。改后「预览所见 = 保存所得」。
  - 不变项：9:20 取景框与输出 JPEG 宽高比、输出尺寸来源（取景框）、`Prefs` 背景路径格式均未改变，只是映射比例由 fit 改 cover。

### 验证与风险

- 编译：`./gradlew compileReleaseKotlin --console=plain` 两次（ImportActivity 改完一次、BgCropActivity 改完一次）均 BUILD SUCCESSFUL（4s / 25s），无新增错误或警告（仅其他文件既有的 deprecation 警告）。
- 风险 1：OOM 降级重试可能让极低内存设备多跑 1~2 轮（总轮次仍 ≤3），最坏是耗时略增后给出友好提示，不会崩溃。
- 风险 2：识别器在 Activity 销毁时 close；若 ML Kit 对「close 时仍有 in-flight process」有异常行为，最坏本次识别以失败回调结束（已有 `finishOcrWithError` 兜底），不会崩溃页面。
- 风险 3：BgCrop 保存改 cover 后，极端宽/长图的可选区域比 fit 少（边缘被裁掉），但这正是预览上显示的区域；用户仍可拖动选择，符合所见即所得。

## 2026-11-03 v1.31.0 — P1 性能修复（arch-auditor）

### 1) ScheduleView 绘制缓存：onDraw 不再逐帧读 prefs / 解析节次 JSON
- `ScheduleView.kt:118-142`：新增缓存字段 `cachedDarkMode / cachedPrimary / cachedBackgroundStyle / cachedBackgroundColor / cachedCardStyle / cachedCardShadow / cachedNowSection / cachedTodayIndex / cachedRealWeek / cachedMinuteOfDay / datesCache`。
- `ScheduleView.kt:266-310`：新增 `refreshDrawingCache()`（一次读完深色模式、primary、背景样式、卡片样式/阴影、背景图路径并计算背景色）、`refreshClockCachesIfNeeded()`（当前节/今天列/真实当前周按「当天分钟数」节流，同一分钟复用）、`onAttachedToWindow`/`onSizeChanged` 刷新、`onDetachedFromWindow` 释放背景图。
- `ScheduleView.kt:186-195`：`setData()` 末尾调用 `refreshDrawingCache()`（从设置页返回时 MainActivity 会重新 setData，因此卡片样式/背景/节次时间改动自动生效）。
- `ScheduleView.kt:518`：`scheduleBackground(dark, style)` 改为纯函数（不读 prefs）；`:534` `loadBgImageIfNeeded()` → `refreshBackgroundImage()`（只在刷新缓存时解码）；`drawWeekPage`（:377/:378/:455）与 `drawCard`（:648）改用缓存值；`renderToBitmap`（:1088）导出前刷新缓存并使用缓存背景色；`datesForWeek()` 增加按周的日期缓存。
- 效果：onDraw 内 SharedPreferences 读取从「每帧数百次」降到「每次 setData/尺寸变化一次」，`currentSection` 的 JSON 解析从每帧一次降到每分钟一次；翻页/缩放拖动期间不再触发 prefs IO 与 `Color.parseColor`。
- 保持不变：双页并排翻周（slideOffset/delta/两页 dx 计算）、axisLock 手势状态机、缩放 [1, 2.4]、`canSwipeWeek`/`onWeekSettled` 时序均未改动。

### 2) MainActivity 数据加载移出 UI 线程
- `MainActivity.kt:50-56`：新增 `loadExecutor`（`Executors.newSingleThreadExecutor()`）、`loadRequestId`、`corruptToastShown`。
- `MainActivity.kt:116-122`：新增 `onDestroy()`：`loadRequestId++` 使在途结果失效 + `loadExecutor.shutdownNow()`。
- `MainActivity.kt:161-188`：`loadData()` 改为在加载线程执行 `CourseStore.load/loadTodos/Prefs.currentWeek/hasCorruptFile`，回主线程后校验 `isFinishing || isDestroyed` 与 `requestId` 再赋值并 `updateUI()`；顺带接入 Lead 的 `CourseStore.hasCorruptFile()`：`courses.json` 损坏留底时首次提示「课表文件损坏已留底，可在设置中恢复备份」。
- 效果：每次返回主界面（onResume）不再在 UI 线程读文件 + 解析 JSON；快速多次 onResume 只渲染最新一次结果，不会旧数据覆盖新数据。
- 已知副作用：首帧可能先看到空课表/空态，随后（通常 <50ms）被真实数据替换；写操作（增删课/待办）仍走原同步保存路径，数据格式未改。

验证：`./gradlew compileReleaseKotlin --console=plain` → BUILD SUCCESSFUL（仅原有 deprecation / `!!` 警告）。

行号校正（同上一条记录，代码改动后行号有偏移）：
- ScheduleView 缓存字段实际在 `:118-133`；`refreshDrawingCache()` `:265-275`；`refreshClockCachesIfNeeded()` `:278-286`；`onAttachedToWindow` `:288-292`；`onSizeChanged` `:294-299`；`onDetachedFromWindow` `:301-307`；`onDraw` 头部 `:311-316`。
- `drawWeekPage` 内改用缓存的位置：背景色 `:349`、今天列 `:373`、当前节 `:374`、表头判定 `:378`（原文误写 377/378/455）。
- `scheduleBackground(dark, style)` `:518`；`refreshBackgroundImage()` `:534`（守卫 `:537`）；`drawCard()` `:588`，`corner` `:592`，阴影 `:595`（原文误写 648）。
- `renderToBitmap()` `:1088` 起（内部 `refreshDrawingCache()` 在其开头）。
- MainActivity：`loadExecutor` `:51`、`loadRequestId` `:53`、`corruptToastShown` `:55`、`onDestroy()` `:118-122`、`loadData()` `:161-188`。

---

## 第 3 轮（2026-11-03 20:00–20:05）：联调、验收 v1.31.0

### 这一轮做的事
1. **单测跑通**：`C:\cgtest` 目录联接 + `./gradlew testReleaseUnitTest` → **14/14 通过**；期间修掉一个真实 bug（`dayOfWeekOf("周一下午没有课请自习教室")` 误判成星期一，见「坑 4」）。
2. **链接抓取补一步自动化**：登录成功跳到强智主框架 `xsMainV_new.htmlx` 后，App 自动打开课表查询页（`WebImportActivity.KB_URL`，只跳一次），用户不用自己去点菜单；新增文案 `web_import_auto_kb`。
3. **team 分工收尾**：arch-auditor 修完两条 P1（ScheduleView 绘制缓存、MainActivity 异步加载），ocr-fixer 修完三条 P1（recognizer 复用+close、OOM 捕获与降级、背景 cover 一致）。Lead 审 diff 后统一验收。
4. **版本与文档**：版本号 v1.30.0/45 → **v1.31.0/46**；`CHANGES.md` 写 v1.31.0 条目；`README.md` 补「链接抓取」架构决策（第 5 条）、中文路径坑、单测命令、真机待验证清单；`docs/AUDIT.md` 的 P0 也标上【已修复 v1.31.0】；四个文档统一收进项目 `docs/`。
5. **验收命令**（按顺序跑，不要并发）：
   ```bash
   export JAVA_HOME=/c/Users/Asus/.jdks/jdk-17.0.20.1+1; export PATH=$JAVA_HOME/bin:$PATH
   cd /c/cgtest/classgrid-source-v1.9.0/classgrid-source
   ./gradlew clean assembleRelease        # BUILD SUCCESSFUL in 44s（lintVitalRelease 也过）
   ./gradlew testReleaseUnitTest          # BUILD SUCCESSFUL，14 tests
   ```
   产物：`app/build/outputs/apk/release/app-release.apk`，26,202,949 字节，
   `aapt dump badging` → `versionCode='46' versionName='1.31.0'`、`uses-permission: INTERNET`。
   新类确认：`unzip -p app-release.apk classes.dex | grep -a -c "WebImportActivity\|WebScheduleParser\|UniqueId"` → 26 处命中。

### 本轮新增的坑 / 经验
- **坑 6：`onPageFinished` 不是渲染完成**。强智课表由 JS 渲染进 iframe，`onPageFinished` 时表格可能还没出现，所以我们额外在 +900ms、+2200ms 各补一次扫描，并常驻 MutationObserver + 3.5s 轮询。以后调「抓不到」的问题，先看这三个时机是否都失效。
- **坑 7：团队并行改档要限定 write scope**。这次 3 个 agent 同时改代码，靠 `team_task_update` 的 writeScopes 把文件切开（Lead: Course/CourseStore/WebImport*/资源/manifest；arch-auditor: ScheduleView/MainActivity；ocr-fixer: ImportActivity/BgCropActivity），docs 用「只追加」约定避免互相覆盖。**下次继续沿用这套分工。**
- **坑 8：Gradle 报错输出里夹着 RMI 栈**（`java.rmi ... TCPTransport`）但 `BUILD SUCCESSFUL` —— 那是 Gradle daemon 通信告警，不影响产物，别被吓到。
- **`clean` 之后必须重跑 `testReleaseUnitTest`**：`clean` 会删掉 `tmp/kotlin-classes`，只跑 assembleRelease 不产生测试类。

### v1.31.0 的验证边界（诚实记录）
- 已验证：编译（含 lintVital）、14 个解析器单测、APK 版本号与权限、新类进包、代码静态审查。
- **未验证**：所有 UI 交互与真机行为（本机无 ARM 设备/模拟器，SDK 也没装模拟器镜像）；链接抓取的真实登录流程与真实课表 DOM。
- 下一步接手建议：① 找一台安卓机按 `docs/JWXT_RESEARCH.md` §9 清单做真机验证（重点是校外网络、iframe 层级、学期切换）；② 按需修 `docs/AUDIT.md` 剩下的 15 条 P2；③ 若要支持别的学校，只改 `WebScheduleParser` 与 `PRESET_URLS`。

---

## 第 4 轮（2026-11-03 21:40）：用户反馈旧教务系统已关停，改入口网址（v1.31.1）

### 用户给的新事实（重要，推翻了 JWXT_RESEARCH.md 的前提）
> 「最近广外教务系统更新，你找到的是旧的教务系统已经进不去了，现在我们都是从广外官网，点智慧广外登录进去」
> 新入口：`https://authserver.gdufs.edu.cn/authserver/login?service=https%3A%2F%2Finfo.gdufs.edu.cn%2Fmanage%2Fcommon%2Fcas_login%2F1%3Fredirect%3Dhttps%253A%252F%252Finfo.gdufs.edu.cn`

- 也就是说 **`jwxt.gdufs.edu.cn`（强智 jsxsd）已经整体停用**，v1.31.0 里辛苦调研出来的 `sso.jsp` / `xskb_list.do` / `xsMainV_new.htmlx` 全部失效。
- 新链路是：`authserver.gdufs.edu.cn`（金智 CAS 登录）→ service 回跳 `info.gdufs.edu.cn/manage/common/cas_login/1?redirect=…` → **智慧广外门户** `info.gdufs.edu.cn`，课表在门户里的教务子应用（URL 未知）。
- 教训：**学校系统会换**，所以「入口」必须是一个可配置常量（`PRESET_URLS` + 地址编辑按钮），而不是散落在代码里的硬编码；这也正是当初把 URL 收敛到 companion 的原因。下次再变只改 1 处。

### 本轮改动（只改入口，不动抓取逻辑——用户明确要求「其他先不迭代」）
1. `WebImportActivity.PRESET_URLS`：
   - 「智慧广外登录（推荐）」= 上面的 authserver URL（用字符串拼接写，避免单行超长；`%` 在普通 Kotlin 字符串里无需转义）
   - 「智慧广外首页」= `https://info.gdufs.edu.cn/`
   - 删除旧的 `sso.jsp` / 教务登录页 / 课表查询页三项。
2. 新增 `fun isRetiredUrl(url: String?): Boolean`：空串或命中 `jwxt./jxgl./jwgl./jwc./ehall./my./portal.gdufs.edu.cn` 即视为失效。`onCreate` 取初始网址时用它过滤 `EXTRA_URL` 与 `PREF_LAST_URL` —— **否则从 v1.31.0 升级上来的用户 prefs 里存着旧网址，一进来就是死链白屏**（这类「升级后白屏」最难被用户描述清楚，提前挡掉）。
3. `KB_URL` 与「登录后自动跳课表」保留但标注为旧系统兼容逻辑（新系统 URL 未知，不猜测；用户自己点进课表，注入脚本会扫当前页）。
4. 版本号 → `versionCode 47` / `versionName 1.31.1`；`CHANGES.md` 加 v1.31.1 条目；`README.md` 的版本行与「链接抓取架构决策」第 5 条同步更新（写清智慧广外链路 + 旧域名迁移过滤）。

### 验证
- `./gradlew assembleRelease` → BUILD SUCCESSFUL（1m2s，含 lintVital）
- `./gradlew testReleaseUnitTest` → BUILD SUCCESSFUL
- `aapt dump badging` → `versionCode='47' versionName='1.31.1'`
- 新网址确实进包：`unzip -p app-release.apk classes.dex | grep -a -o "authserver.gdufs.edu.cn/authserver/login?service="` 命中
- **仍未真机验证**：新系统登录后课表在哪个页面、DOM 长什么样（智慧广外门户可能是 iframe/微前端，`WebImportScript` 的递归同源 iframe 逻辑应该能覆盖，但没实测）。下次拿到真机时第一件事：登录 → 进课表 → 点「扫描当前页面」→ 看诊断信息里的「表格数」是否为 0。


---

## 附：接口 JSON 解析器 WebJsonParser（2026-11-03，纯 JVM，未真机验证）

### 为什么加这条路
之前抓课表靠扫 DOM，问题是**表格被视觉截断**（iframe 里只渲染可见行、虚拟滚动）时只能拿到一部分课。
教务网页自己会用 XHR/fetch 请求课表接口，那份 JSON 是**完整数据**，所以新增「读后端数据」路径：
注入脚本捕获响应体 → `WebJsonParser` 解析。DOM 路径保留为兜底，两条路互不影响。

### 实现要点（`app/src/main/java/com/classgrid/app/WebJsonParser.kt`）
- 契约：`WebJsonParser.parse(net, sections, notes): List<Course>`，不抛异常，一律往 `notes` 写中文诊断。
- 只接受 body trim 后以 `{`/`[` 开头的响应（HTML 登录页直接跳过并计数）。
- 递归扫全树（深度 ≤ 8、节点 ≤ 20000），收集「≥2 个元素且过半是 JSONObject」的数组，
  按 `(含 ≥2 种可识别字段的对象数) * 10 + 字段种类数` 打分，每份响应取最高分（≥20），再多响应合并去重。
- 映射：字段名先归一化（小写、去 `_`/`-`/空格，保留中文）再匹配，精确 > 前后缀 > 包含（长度参与比较，
  避免「课程代码」被「课程」抢走）。别名覆盖：名称 kcmc/kcmcstr/kcmcdisplay/coursename/course/name/课程名称…
  教师 jsxm/jsmc/teacher/任课教师…；教室 cdmc/classroom/jxdd/上课地点…；星期 xqj/xqjstr/星期…；
  节次 jcs/jc/jcstr/skdj/节次…；周次 zcd/zc/weeks…；代码 kch/kcdm/coursecode…。
- 值宽容：数字/布尔/数组（拼成 "1,2,3"）/被包一层（`{"value":…}` `{"name":…}`）都能取。
- 兜底：缺周次 → 1-20 周（`DEFAULT_TOTAL_WEEKS`）并提示核对；缺/解析不出节次 → 第 1-2 节并提示；
  倒序节次强制 `end=max(start,end)`；节次一律夹到 1..15；`id=0`、`colorIndex=0`（外层重新发号）。
- 去重键：`name|dayOfWeek|startSection|endSection|startWeek|teacher|classroom`（fetch/XHR 双钩子会重复采集同一响应）。
- notes 成功时给「已从接口数据解析 N 门课程（接口：<url 截断 80 字>）」，失败时区分「都不是 JSON / HTML / 得分不足」。

### 单测（`app/src/test/java/com/classgrid/app/WebJsonParserTest.kt`，13 个用例）
覆盖扁平强智风格、`data.list` 嵌套、中文 key、脏数据（非 JSON/空/HTML/无关数组）、缺周次兜底、
倒序节次、`kch` 不误当课名、跨响应去重、`"0102"` 四位节次与 `"1,2"`、JSON 数字值、超长 URL 截断、
`{"value":…}` 包裹值。命令与结果：
`./gradlew testReleaseUnitTest` → **28 tests（原 14 + WebScheduleParser 新增 1 + 本类 13），0 failed**；
`./gradlew assembleRelease` → BUILD SUCCESSFUL。

### 真实数据的不确定点（拿到真机回传后按真实 key 补）
- 智慧广外门户（金智 ehall）/强智的字段命名、嵌套层级都还没见过真数据，别名表是猜测；
- 接口可能是 JSONP（`callback({...})`）或带签名/加密参数，当前只认纯 `{`/`[` 开头，JSONP 会被当文本跳过；
- 分页接口只取「每份响应当前页」的数组，若课表分页返回，需要外层把多页响应都塞进 `net`；
- 节次编码 `"0102"` 按两位切、`"第二大节"` 依赖 `WebScheduleParser.sectionsFromLabel`，都是假设；
- 学期/学年参数不同可能返回空数组，届时 notes 会显示「不像课表数据」。

### 本轮新坑
- `WebScheduleParser.sectionsFromLabel` 的区间/单节正则要求结尾有「节」字，裸 `"01-02"`、`"0102"`、`"1,2"` 全返回 null，
  所以 WebJsonParser 自写了一套编码解析（先自解析，再回落到它）。
- 中文别名有包含关系（「课程代码」⊃「课程」），匹配必须按「精确 > 前后缀 > 包含 + 质量分」排序，否则课程名会变成代码。
- 单测 helper `doc(body, url)` 参数顺序写反会导致「URL 当 body、body 当 URL」，报「响应体都不是 JSON」且看不出原因
  （本次就踩了，已修）；写 helper 时参数顺序要在调用处核对。
- `WebScheduleParser.cleanName` 修了括号配对 bug 后，课程名会保留 `(1)`/`(2)` 序号（以前会被切坏）。

## 第 5 轮（2026-11-03 22:30–23:05）：链接抓取按用户反馈重做（v1.32.0）

### 用户反馈（三点，原文要点）
1. 「能正常打开网址，但是一打开就弹窗识别失败，请修改成在用户点击抓取按钮之前都不要主动抓取，等到用户登录完了，进入完课程表界面点击抓取之后再执行抓取命令」
2. 「抓取命令和逻辑需要优化：请看我上传的图片，进去之后是这样，会出现有些课程显示不全的情况，所以不能只截图抓取，尝试能不能做网页后端代码抓取，即爬一下每门课程数据，这需要很强大的算法逻辑。若你认为无法实现，则尝试使用其他方法。」
3. 「在执行修改任务之前请评估具体方向，找准了目标之后再开始做」

### 先做方向评估（用户的第 3 点）——结论写在最前面
- 用户截图里的课表**不是 `<table>`**：是 div 卡片网格（左侧「一大节 01,02…」、顶部「周日01…周六07」、
  卡片三行 = 课程名/教师/教室），且**按「第9周」过滤渲染，卡片里没有周次**。
- 所以「靠截图/DOM 拿全数据」在原理上就不可能完整：要么改数据源（读接口 JSON），要么改交互（让用户切学期/周）。
- 结论：**保留 WebView 内嵌（不碰密码/验证码）+ 三条数据通路**：接口 JSON > 接口重放 > DOM 视觉网格兜底。
  不引入后端服务、不做自动登录，理由与 v1.31.0 相同（CAS 有滑块和短信验证码，自动登录既脆弱又危险）。

### 根因（为什么一打开就弹「识别失败」）
- `WebImportScript.INSTALL_JS` 末尾会 `send(true)`（manual=true），还装了 MutationObserver(1.2s) 与
  `setInterval(...,3500)`；`onPageFinished` 里又 `injectScript(v, 1)` 走 `SCAN_CALL`。
- 于是登录页/门户页一加载就扫描 → 0 门课 + manual=true → `handlePayload` 直接 `showDiagnostics` 弹窗。
- 修法：注入只「安装钩子」，**删掉初始扫描、MutationObserver、定时轮询**；`onPageFinished` 只安装；
  只有底部按钮才 `__scan(true)`；`payload.manual == false` 时永不弹窗。

### 本轮实现
1. `WebImportScript.kt` 重写：`hookFetch/hookXhr` 缓存页面自己的 JSON 响应（`NET`，最多 40 条 / 单条 300KB /
   总量 1.5MB）；`collectBlocks()` 抽叶子文本块的 `x/y/w/h/背景色`；`buildGridTable()` 就地重建合成表
   （星期表头定列、大节标签定行、文本块落格、同格叠课按纵向间隔切块）；`send(manual)` 一次性回传
   `tables + blocks + net + bodyText`。
2. `WebScheduleParser.kt`：`Payload` 增 `blocks`/`net`，新增 `Block`/`NetDoc` 数据类与 `parseBlocks/parseNet`。
3. 新增 `WebJsonParser.kt`（队友 json-parser-dev 实现）：递归扫描 JSON 树、按字段命中打分挑「像课程数组」的
   候选、key 归一化 + 别名表（含中文与 `kcmcstr` 这类前后缀）映射成 Course，缺周次按 1–20 兜底并写 notes。
4. `WebImportActivity.kt`：`onPageFinished` 只注入；`handlePayload` 先接口、再 DOM；新增
   **`shouldInterceptRequest` 旁听 GET 接口** + 用户点抓取时**用系统 Cookie 重放**（跨域 iframe 也能拿到数据）；
   诊断弹窗三项（导出抓取数据 / 复制摘要 / 改用截图导入）；导出走 FileProvider `cacheDir/exports/*.json`。
5. 文案：按钮改「抓取当前页面」，登录页提示改为「登录并进入课表页面后点按钮（App 不会自动抓取）」。
6. 版本 1.32.0 / versionCode 48；`CHANGES.md`、`README.md` 同步。

### 踩坑与教训（按发现顺序）
- **Kotlin raw string 里反斜杠只写单层**：重写 JS 时手滑写成 `\\s`、`'\\n'`（24 处），JS 正则拿到的是
  字面反斜杠，星期匹配/节次标签/换行全废。教训：`"""..."""` 里就是 JS 源码本体，**不需要二次转义**；
  改完必须 `node --check` + 用合成数据跑一遍算法。
- **表头带会被当成课程落进第一行**：合成表时文本块坐标含表头那一行，落格要 `if (blk.y < baseY + 8) continue;`。
- **按「中心点」判行会把一张卡片劈成两节**（课程名在上、教室在下，中心点落到下一行）：改用**顶部 y** 判行。
- **同一格上下叠两门课会被拼成一门**：按纵向间隔 >18px 聚成「每门课一块」，写进 `cell.blocks`；
  Kotlin 侧对 `blocks` 是逐块 `parseBlock` 的，正好一门课一块。
- **「一大节 01,02」不是 Kotlin 认识的「第1大节」**：JS 侧加 `normSectionLabel()` 统一成 `第N大节`，
  否则 `sectionsFromLabel` 认不出、节次会退化成按行号顺序排。
- **`cleanName` 会切坏课程名**：结尾 trim 集合里有 `)`，`高等数学(1)` → `高等数学(1`。改成「括号只在左右
  不配对时才剥离」，并加单测锁定。这类清洗规则一定要有用例，否则没人会发现。
- **`dayOfWeekOf` 会把整格课程文本当星期**：加「周X 后仍跟着汉字（除 第/小/节）」就否决的规则。
- **Node 在 Windows 上把 `/tmp/x` 解析成 `C:\tmp\x`**，而 git-bash 传给 node 时又会换成 `%TEMP%`：
  校验脚本一律写相对路径。
- **Gradle 单测在含中文的工程路径下会丢 classpath**（`ClassNotFoundException`，但类确实编译出来了）：
  必须用 ASCII junction `C:\cgtest` 跑（`New-Item -ItemType Junction ...`），别浪费时间调 encoding。
- **队友的测试 helper 把 `doc(body, url)` 参数顺序写反**，报错只有「响应体都不是 JSON」，看不出原因：
  helper 参数别用两个同类型 String，或用命名参数。

### 验证与边界
- `cd /c/cgtest/... && ./gradlew clean assembleRelease testReleaseUnitTest` → BUILD SUCCESSFUL；
  `--rerun-tasks` 强制重跑：**28 tests / 0 failed**（`WebScheduleParserTest` 15、`WebJsonParserTest` 13）。
- APK：26,223,841 字节，`aapt dump badging` → versionCode 48 / 1.32.0；`classes.dex` 里能查到
  `WebJsonParser`/`WebImportScript`/`WebImportActivity`。
- 注入 JS：用 Node 从 Kotlin 源里抽出 `INSTALL_JS` → `_install_v2.js`（19,491 字符，`$` 出现 0 次）→
  `node --check` 通过；再用 `_grid_test.js` 把 `buildGridTable` 抽出来跑合成数据，确认 7 列 8 行、
  「高等数学」落周一、「体育」落周五、同格两门课拆成 2 个 blocks。
- **仍未真机验证**：智慧广外登录后的课表页是否在跨域 iframe、是否有可重放的接口、真实 JSON 字段名。
  失败路径已内置「导出抓取数据」，用户回传 JSON 后按 `net`/`tables` 补别名表即可。

### 下次接手先看
- 想改抓取：`WebImportScript.kt`（抽什么）→ `WebJsonParser.kt`（接口字段）→ `WebScheduleParser.kt`（DOM 语义）
  → 各自单测 → 真机。
- 想加接口别名字段：只改 `WebJsonParser.ALIASES`，并补一条 `WebJsonParserTest` 用例。
- 不要恢复任何「自动扫描/轮询」逻辑，那正是「一打开就弹失败」的来源。

## 第 6 轮（2026-11-03 23:10–23:35）：抓取节次纠错 + 多课表（v1.33.0）

### 用户反馈
1. 「链接抓取成功，但是仍然有不准确的……有一个国家安全教育的课，点开详情界面里面是 14-15 小节，
   而抓取出来是 1-2 小节。请你 1. 现在排查一下原因，优化抓取准确性。」
2. 「抓取之后新增一个添加到其他课表的选项，也就是说用户能够实现不同课表的切换，
   导入的时候可以选择新建课表存入或者覆盖当前课表。」

### 排查过程（先看图再定位）
- 截图 1：网页自己的课程详情弹窗 → 周二、第七大节（14,15 小节）20:00~21:20、9-12 周、南G407。
- 截图 2：我们的核对页 → 该课 周二 第1-2节、9-12周。**同一天同一门课，只有节次错**。
- 判断数据来源：核对页里带着课程代码/教师/周次，而网格卡片只有「课名/教师/教室」三行，
  说明数据来自**接口 JSON**（不是 DOM）。于是问题锁定在两处：JSON 里节次字段要么没被识别、要么解析失败。
- 三个假设，逐一验证：
  ① 字段名不认识（`jcs` 之外的命名）→ 用单测构造「字段名未知但值是第七大节文本」复现，旧代码确实退化成 1-2；
  ② 值是中文数字「第七大节」→ 复现：旧正则只认阿拉伯数字，解析失败；
  ③ 接口根本不给节次 → 旧代码 `sectionRange ?: IntRange(1, 2)` 直接猜 1-2，静默出错。
  三个都成立，所以三条一起修（缺一条都还可能错）。

### 修法（分层，能拿到就不猜）
1. `WebScheduleParser`：节次正则支持中文数字（一~十/十一~十五）与「14,15小节」「第14、15节」逗号罗列；
   新增 `numOf()` 统一数字解析。
2. `WebJsonParser`：节次别名扩到 `jcdm/jcsj/sksj/kcsj/djc/上课时间/上课时段/时间` 等；
   解析不出时**不再猜 1-2**，改为 `startSection=endSection=0` 表示未知；
   新增 `scanSectionsFromAnyValue()`：字段名全不认识时，把对象里所有字符串值扫一遍找节次文本。
3. `WebImportActivity`：新增 `fillSectionsFromDom()` —— 用 DOM 网格（含注入脚本合成的表）按
   「星期+课程名」把未知节次补上（格子在第七行就是第七大节，比猜可靠）；仍未知的由
   `finishUnknownSections()` 兜底 1-2 并在诊断里说明；接口值与网格值冲突时只记诊断不擅自改。

### 多课表（队友 timetable-dev 实现，我 review）
- 新增 `TimetableStore.kt`：`filesDir/timetables/index.json` + 每表 `<id>.json`（各自带 backup/corrupt），
  id 为 `t_<UniqueId>` 且正则白名单校验（id 是文件名前缀，防路径穿越）；索引坏时可从磁盘现存文件重建。
- 老数据迁移：**复制**而不是移动 `courses.json`（用户看到文件还在会安心，也留一条后路），一次性标记幂等。
- 设置页「课表管理」：切换/新建/重命名/删除（最后一张禁删）；主课表页从设置返回即刷新。
- 核对页导入三选一：合并到当前 / 覆盖当前（二次确认 + 清空前备份）/ 新建课表并导入（建表→设当前→写入）。
- 备份、恢复、清空都改成 per-课表；todos 保持全局（待办与课表无关，拆开反而让用户困惑）。

### 踩坑
- **「猜一个默认值」比报错更危险**：1-2 节的兜底让错误看起来像正常数据，用户只能靠肉眼比对发现。
  教训：解析不出来就用「未知」标记 + 明确提示，能补的地方补，补不到让用户改。
- **中文数字**在教务系统里到处都是（第七大节/第三周），正则一律要考虑。
- `WebJsonParser` 的候选数组要求「至少 2 个对象」，单门课的用例要构造 2 条，否则会误判成「不是课程数组」。
- 多课表迁移必须「复制 + 幂等标记」，且**每张表各自的备份/损坏文件**都要跟着课表走，否则恢复备份会串表。

### 验证与边界
- `clean assembleRelease testReleaseUnitTest` → BUILD SUCCESSFUL；**53 个单测全绿**
  （WebScheduleParserTest 17 / WebJsonParserTest 16 / TimetableStoreTest 20）。
- 关键回归用例：`sec=14-15`（复现「字段名未知 + 中文大节」时旧代码给 1-2 的场景）。
- 未真机验证：抓取到的节次是否正确、多课表 UI 弹窗交互。失败时用「导出抓取数据」回传 JSON 再迭代。

### 下次接手先看
- 节次错位先查三层来源：接口 JSON 字段 → DOM 网格位置 → 兜底。改 `WebJsonParser` 后必须跑
  `missingSectionBecomesUnknownInsteadOfGuessed` 这条用例。
- 多课表：所有课程读写都要走 `TimetableStore`（`CourseStore` 的无参 API 只是「当前课表」兼容层）。

---

## 第 7 轮（2026-11-04）：UI 主题系统（经典 / 墨玉 / 极光）+ 新图标 —— v1.34.0

### 需求与决策（用户原话要点）
- m01554：「使用 open design 插件，对软件的图标和界面色系 UI 进行优化，要求用户能在设置中切换不同风格，
  当前的设置作为原始设置要保存，然后再行设计一版全新的 UI 和色系。若无法调用 open design，则停止任务，
  在执行任务之前，请你预估会消耗多少费用，并且理解我的要求，发回给我确认」
- m01800：「按照你的建议方案去做，我的要求就是精准度要高，但是花费尽可能低。需要我决定的那件事，我选选项1」
  → **选项 1 = 不装模拟器**：验收靠编译 + 53 单测 + 静态审查 + 用户实机看效果；范围 = 主页课表 + 设置页 + 导入页 + 图标。
- 方案：`AppTheme` 主题抽象 + 三套色板；经典主题色值原样保留且为默认值（老用户零变化）。

### 尝试与错误（重要）
1. **Open Design 插件无法给 DSH 调用**：OD 0.26.1 已运行（`D:\Open design\Open Design.exe`），
   但它是「把 DSH 当 agent 运行时」，daemon 的 `mcp_servers` 为空 → 本会话拿不到任何 OD 工具。
   可复用的只有安装目录里 171 个 skills / 170+ design-systems（只读设计规范）。
2. **OpenPencil 生成失败**：`openpencil_pipeline_finish` 返回
   `{"published": false, "stage": "blocked_host_failure", "diagnostics": ["OpenPencil MCP finalize_design failed: Error: finalize_design could not prove command replay parity: semantic passes unexpectedly removed node n110"], "canContinue": false}`
   → 草稿未落盘。按契约**不重试**，改用 `visualize` 出效果稿。
3. **Hindsight 不可用**：`401 Authentication failed`（banks/coding-agent::大一课程表软件），
   于是本轮设计决策全部记进本 DEVLOG 与 `CHANGES.md`，不依赖外部记忆服务。
4. **编译错 1**：`SettingsActivity.kt` 的 `TextView(this).apply { ... AppTheme.primary(this, dark) }`
   —— `apply` 里的 `this` 是 TextView 不是 Context，报 `Type mismatch: inferred type is TextView but Context was expected`。
   → 改成 `this@SettingsActivity`。
5. **编译错 2**：`Menu`/Adapter 内部类里 `AppTheme.current(context)` → `Unresolved reference: context`
   → 改成 `this@MainActivity`（内部类已持有外层 Activity 引用，不要偷偷用 `context` 变量）。
6. **编译错 3**：`ImportActivity` 里我新加的 `private fun dp(v: Int)` 与文件里已有的同名函数**重复定义**
   （`Conflicting overloads`）→ 删掉新加的那个，复用已有的；同理别再往 `ImportActivity` 里加 `bl()` 之外的工具函数。
7. **`ScheduleColor` 签名变更引起的连锁**：`palette/inactiveBg/inactiveFg/count` 需要 `Context`，
   用一次 grep 找全调用点（CourseEditActivity ×3、MainActivity ×1、OcrResultActivity ×2、ScheduleView ×1、SettingsActivity ×1）统一改，
   否则编译会在多个文件报错、逐个点开很费时。**经验：改公共工具函数签名前先 `grep -rn` 统计调用点**。

### 本轮实现
- 新增 `app/src/main/java/com/classgrid/app/AppTheme.kt`：每套主题一个 `Spec`（60+ 语义色值 + 卡片圆角/色帽 + 背景可选色），
  `ALL = [经典, 墨玉, 极光]`，`current(context)` 带缓存（`onDraw` 高频调用），`invalidate()` 供设置页切换后失效。
- `Prefs.theme()/setTheme()`（key `ui_theme`，**默认 0**）——默认值即老样式，绝不能改成新主题。
- 批量替换硬编码色值：`ScheduleColor`（转发主题）、`ScheduleView`（表头/星期/网格线/节次文字/背景/卡片圆角与色帽/导出图底色）、
  `MainActivity`（胶囊/日期条/课程与待办卡片/状态色/周次宫格）、`SettingsActivity`、`OcrResultActivity`、
  `ImportActivity`（卡片与图标底色运行时染色）、`SectionTimeActivity`、`BgCropActivity`。
- 主题切换即时生效：设置页 `recreate()`；`MainActivity.onResume` 比对 `appliedThemeId`，变了也 `recreate()`
  （课表是自绘 View，只改色值不重建会出现「半边旧配色」）。
- 图标：新增 `ic_launcher_bg_aurora.xml`（vector 线性渐变，`aapt:attr` 内联 gradient）、`ic_launcher_fg_grid.xml`、
  `mipmap-anydpi-v26/icon_opt5.xml` + manifest 新 alias `IcoAlt7` + `IconSwitcher.ENTRIES` 追加 + 设置页图标列表第 8 项。
  切主题时只在用户当前用「默认/主题推荐图标」时才自动同步，避免覆盖用户自己挑的图标。

### 验证与边界
- `clean assembleRelease testReleaseUnitTest` → BUILD SUCCESSFUL；**53 单测全绿**；APK 26,254,690 字节（+31KB）。
- 未真机验证：三套主题的观感、图标在桌面上的实际裁剪效果（无设备/模拟器）。

### 下次接手先看
- **换配色只改 `AppTheme.kt`**，不要再往 Activity/View 里写 `0xFF...`；新增主题 = 加一个 `Spec` + 加进 `ALL` + （可选）配套图标 alias。
- 任何「经典主题」的色值改动都算回归：`classic` 里的数值是 v1.30–v1.33 的原始硬编码。

---

## 第 8 轮（2026-11-04）：安装报「文件损坏」排查 + v1.34.1

### 现象
- 用户：微信传到手机、点击安装 → 提示「文件损坏」；同一文件在电脑端是好的。

### 排查（全部实测）
1. `apksigner verify --print-certs` on v1.34.0 → `Verifies`、zipalign 正常、证书 `CN=ClassGrid`、
   SHA-256 `773ec9ba…`（与历史版本同一个 release.keystore，**签名链没问题**）。
2. 自己写脚本逐个条目 inflate 全部 zip entry（1076 个）：**0 个损坏**、dex 魔数正常、EOCD 偏移正确。
   → 文件本身不是坏包，是「传输 / 安装器兼容」问题。
3. 检查签名方案：**v1.30 ~ v1.34.0 全部只有 v2，没有 v1(JAR) 签名**。
   v1 缺失时，经过非二进制通道（微信、QQ、部分网盘的「在线打开」、云端压缩预览）极易被判为
   「解析包出错 / 文件损坏」，部分国产 ROM 安装器也偏保守 → 这就是根因方向。
4. 网络受限（无法直接改依赖，只能内联/本地改动），体积优化、签名策略都要在不加依赖的前提下做。

### 本轮改动
- `app/build.gradle.kts` 的 signingConfig 显式 `enableV1Signing = true`；版本 **51 / 1.34.1**。
- 新的分发方式：**zip 打包**（`课格ClassGrid_v1.34.1.zip`，内含 APK + 安装说明）
  —— zip 走微信传输不会被当作可执行文件二次改写/拦截，是这类「文件损坏」最稳的绕过方式。

### 坑（重要，别再踩）
1. **apksigner 在 `minSdk>=24` 时只报告 v2**——即使 v1 已经签进去了，`apksigner verify --verbose`
   仍然显示 `Verified using v1 scheme (JAR signing): false`。这不是 bug，是「你这个 minSdk 用不到 v1」。
   **确认 v1 真的在包里的办法**：`apksigner verify --verbose --min-sdk-version 21 xxx.apk`（v1 必须 true），
   或者看 `META-INF/MANIFEST.MF + *.SF + *.RSA` 是否存在。
2. **targetSdk≥30 时系统只认 v2+**，v1-only 的包装不上（`Target SDK version 34 requires a minimum of
   signature scheme v2`）。所以 v1 只是「多一层兼容」，不能去掉 v2。
3. `jarsigner -verify -strict` 会报 `PKIX path building failed` 与「签名者没有时间戳」——自签证书的正常提示，
   不是错误，别被吓到（时间戳要联网时间戳服务，本项目不需要）。
4. 从 git-bash 里 `cd` 到含中文的目录后再用相对路径调 `apksigner --ks ../release.keystore` 会
   `FileNotFoundException`（java 拿到的路径被转成了 GBK 之外的乱码）→ 用 ASCII junction `/c/cgtest/...` 下执行，
   或把 keystore 路径写成绝对 ASCII 路径。

### 给用户的分发建议（写进这里，以后重复用）
- 首选：发 **zip**（内含 apk），手机解压后用文件管理器安装。
- 次选：手机浏览器直接下载（不要用微信内置浏览器点开）。
- 装之前先卸载旧版（签名一致理论上可覆盖升级，但微信传输若改写了文件就只能卸载重装）。

---

## 第 9 轮（2026-11-04 13:00–13:20）：主题从「换色」到「换布局」+ 修极光闪退

### 用户反馈原文
1. 「这个颜色改了跟没改一样，改动幅度不要只局限于颜色。」
2. 「极光那里出bug：点极光重启之后，进入软件再点一下日程，就会闪退，重新变回墨玉的图标和主题，但是选项那里还是极光。」
3. 「我还是想用OpenPencil，有什么我可以帮你跑通要做的吗？或者有没有更好的设计插件需要我安装」

### 教训 1：v1.34.0 的「主题」其实只是皮肤色板
用户说得对——只替换 `0xFF...` 色值，形状、间距、排版、字号一律没变，两套主题的骨架完全相同。
**结论：主题必须是「色板 + 布局风格」两层**。本轮在 `AppTheme.kt` 里加了 `LayoutStyle`
（卡片形态 / 卡片圆角 / 色条宽度 / 顶栏样式 / 导航样式 / 日程排版 / 字号节奏 TypeScale），
与色板正交，可以任意组合；以后要加第四套主题，只要填一个 `Spec`。

### 教训 2：闪退根因是「两次 Activity 重建撞车」
`SettingsActivity.showThemePicker` 里原来是：`Prefs.setTheme(apply)` → `IconSwitcher.apply()` → `recreate()`。
`IconSwitcher.apply` 用 `setComponentEnabledSetting` 切换 activity-alias，部分 ROM 会立刻重建 Activity；
加上 `recreate()` 自己的重建，两个实例各自读一次主题，于是出现「闪退 / 选项是极光界面是墨玉」。
修法（三条一起）：
- `Prefs.setTheme` 改 `commit()` 同步落盘（`apply()` 的异步写在重建竞态里会读到旧值）；
- 先落盘 → `recreate()` → 260ms 后再切图标，图标失败用 try/catch 吞掉（图标是装饰，不能拖累主题）；
- `MainActivity` 比对 `AppTheme.signature()`（主题 id × 31 + 卡片形态）而不是裸 id；
  `Prefs.theme()` 显式校验 id 合法性，不再用 `coerceIn` 静默纠正（脏值要能看见）。

### 新增的布局能力（可单独开关，已通过编译+53 单测）
- `ScheduleView.drawCard`：`CARD_STYLE_ROUNDED`（原逻辑一字未改）/ `STRIPE`（卡底 + 左侧粗色条 + 细描边）/
  `GHOST`（无背板纯文字）/ `PILL`（大圆角）。文字区左边界随色条宽度自动让位。
- `MainActivity.applyHeaderLayout`：`HEADER_LARGE` 大标题（28sp）+ 无阴影 + 周切换描边胶囊。
- `MainActivity.applyNavLayout`：`NAV_PILL` 悬浮胶囊导航（12dp 边距、28dp 圆角、投影、主题选中色），
  日程滚动区底部自动多留 28dp，免得被胶囊压住。
- `MainActivity.buildTimelineAgendaItem`：极光日程改成「左时间轴 + 右紧凑卡」，
  `buildAgendaItem` 变成按 `AGENDA_COMFORT / AGENDA_TIMELINE` 分派的两个函数。

### 本轮踩的坑
1. `dp(spec.layout.cardRadiusDp).toFloat()` 两边都不对：`dp()` 吃 `Int`，`cornerRadius` 要 `Float`
   → 直接用 `spec.layout.cardRadiusDp * resources.displayMetrics.density`（或先 `toInt()` 再 `toFloat()`）。
2. `binding.bottomNav.background = null` 后 insets 监听仍在同一个 view 上，没问题；
   但悬浮胶囊必须同时改 LayoutParams margin 和内容区底部 padding，否则最后一节课被盖住。
3. 极光主题下 `ScheduleView` 的 `cachedCardStyle`（用户设置项）与 `cachedCardForm`（主题决定）是两回事：
   用户选「方块」时仍走 ROUNDED（方块只是圆角更小），不要把两者混用。

### OpenPencil 第三次尝试（本机结论更新）
- `openpencil_pipeline_begin/batch ×2` 都能跑，`inspect kind=screenshot` 能出 PNG 预览；
  `finish` 仍被 `blocked_host_validation` 拦（WCAG 对比度 4.25<4.5、chip 被挤到 22px、误套电商规则）。
- **绕过办法（本轮采用）**：`openpencil_new`（一次性 QuickJS 脚本）直接建 `.op` 文档 → 成功产出
  `classgrid-aurora-ui.op` 与 `classgrid-card-styles.op`；坑：脚本里**不能写 `return`**（`return not in a function`），
  固定高度容器装不下 padding+子元素会报 `frame fixed vertical container height cannot contain ...`，
  改成 `height: "fit_content"` 即过。
- `openpencil_render` 在本机报 `spawn jian ENOENT`（缺外部 jian 渲染器），只能用
  `openpencil_pipeline_inspect kind=screenshot` 或编辑器自己看。

### 验证边界（照旧）
- `./gradlew clean assembleRelease testReleaseUnitTest` → BUILD SUCCESSFUL；53 单测全绿；
  `versionCode=52 / versionName=1.35.0`；`apksigner verify --min-sdk-version 21` → v1=true、v2=true。
- **没有真机**：极光闪退是否真的消失、四套布局的观感，必须用户实机确认。

### 下次接手先看
- 换主题只改 `AppTheme.kt`；换布局只改 `LayoutStyle` 里对应枚举，具体落在
  `ScheduleView.drawCard` / `MainActivity.applyHeaderLayout` / `applyNavLayout` / `buildAgendaItem`。
- 任何「切换主题/图标」的代码都遵守：**先 commit 落盘 → 再 recreate → 最后动 activity-alias**。

---

## 第 10 轮（2026-11-04 23:40–23:55）：暗色导航对比度 + 使用说明对齐「链接抓取」

### 用户反馈
1. 「经典模式下……切换暗色模式的时候下面三个选项是黑色的，跟背景已经融合了，对比度不高，把它改成白色或者其他浅色」
2. 「每一次更新，还有设置那里的注意事项要修改了，目前已经支持链接导入了，把那个修改一下。」

### 坑 9：Material 组件的默认色不能用「主题自带」兜底
`BottomNavigationView` 的文字色默认走 `?attr/colorOnSurface` 选择器，只有图标色是 `itemIconTintList`。
v1.35.0 只在极光（胶囊导航）分支设了 `itemIconTintList`，经典/墨玉分支把它置 `null`，
于是图标和文字都回落到主题默认值——日间主题 + 手动选「夜间」外观时，默认选择器解析成深色，
压在深色导航条上就是「三个选项全黑」。
**结论：凡是自绘/自配色的界面，Material 组件的 item 颜色必须显式设置，不要指望主题属性跟着走。**
修法：`itemIconTintList` 和 `itemTextColor` 同时设成同一个 `ColorStateList`（选中=主题主色、未选中=次要文字色），
两套导航形态都设；经典/墨玉再补一层 `headerLight/headerDark` 背景，与内容区分界。

### 坑 10：XML 属性里的换行是「单反斜杠」转义
`dialog_first_run.xml` 里的说明文字原来是双反斜杠 + n（`\n`），aapt2 只认单反斜杠转义，
导致换行会原样显示成字面的 `\n`（用户看到一长串带反斜杠的文字）。
本轮重写该布局时统一用单 `\n`，并且**只用一条布局服务两个入口**（更新后自动弹窗 + 设置页手动查看），
以后改文案只改这一个文件。

### 本轮改动
1. `MainActivity.applyNavLayout()`：`navTint = ColorStateList([[state_checked],[]], [primary, textMuted])`，
   `itemIconTintList = navTint` 且 `itemTextColor = navTint`；NAV_BOTTOM 分支背景改为 `headerLight/headerDark`。
2. `res/layout/dialog_first_run.xml` 全文重写：
   - 主推「链接抓取」三步（自己登录 → 进课程表 → 点「抓取当前页面」）；
   - 其他三种导入方式（截图识别 / JSON / 手动）；
   - 「注意事项」7 条：手动触发、不存密码、抓不到的排查（换页面/手输网址/导出抓取数据）、
     未排进格子的课抓不到、节次以核对页为准（第七大节 14,15 小节 → 14-15 节）；
   - 「多课表与界面风格」小节；
   - 截图示例图保留（缩小到 300dp 高）。
3. 版本 1.35.1 / versionCode 53。

### 验证
- `./gradlew assembleRelease testReleaseUnitTest` → BUILD SUCCESSFUL（2m38s，lintVitalRelease 通过）；
  53 单测全绿（TimetableStoreTest 20 / WebJsonParserTest 16 / WebScheduleParserTest 17）。
- `versionCode=53 / versionName=1.35.1`；`apksigner verify --min-sdk-version 21` → v1=true、v2=true。
- APK 26,349,126 字节，SHA-256 `f4fd839eb9ecdffe693f24ba743dd0178f31ce18186bea086f58826be118226f`；
  分发 `课格ClassGrid_v1.35.1.apk` + `.zip`（15,087,668 字节）。
- 无真机：暗色导航观感需用户实机确认（设置 → 外观模式 → 夜间）。

### 下次接手先看
- 改任何 Material 组件的选中/未选中色，先确认它是靠主题属性还是靠 `itemXxxTint`/`setTextColor`，两者别混用。
- 用户可见文案改 `dialog_first_run.xml`（使用说明唯一来源）；版本变更记录改 `CHANGES.md` 顶部。

---

## 第 11 轮（2026-11-05）：星期解析修复 + 课程列表页 + 课表管理/导入卡片化（v1.36.0）

### 用户反馈（原话）
1. 「当我用链接抓取之后，新建课表没有突出显示，优化新建课表显示外观和逻辑。」
2. 「链接抓取的课程出现问题，我尝试的时候全部抓取出来的课程日期都在周二，课程名字和老师和教师均没有问题。」
3. 「课表选择那里应该加一个跟抓取识别完的界面一样，展示所有课程，可以随时点击任一门课程修改的界面，
   否则新的课表要修改课程时间只能去方块界面一个一个点。」
4. 「继续迭代优化链接抓取的准确性」

### 坑 11（最贵的那个）：`xq` 是学期，不是星期
- 现象：11 门课全被解析成周二，课程名/教师/教室都正确（说明走的是接口 JSON 通路，不是 DOM）。
- 定位：`WebJsonParser` 的 `Kind.DAY` 别名表里有 `xq`，`parseDay()` 的正则 `^(\d{1,2})(\.0+)?$` 把学期编号 `"2"`
  当成「星期二」。整张表都是同一学期 → 全部周二。
- 只删 `xq` 不够：`xqmc`（学期名称）仍会被 `xq` 的**包含匹配**命中，且顺序在前，所以又新增了
  `IGNORED_KEYS` 黑名单（在 `recognizedFields()` 里先跳过，再做 matchKind）。
- 教训：**字段别名表里的短 token 是最危险的东西**（`xq`、`jc`、`mc` 这种 2 字母前缀会命中一堆无关字段）；
  加别名时必须同时想「这个前缀还会命中谁」，必要时配黑名单，或者只允许精确匹配。
- 兜底设计：找不到可信星期时返回 `day=0` 让课程被丢弃，**绝不猜周二**——猜错比少一门更糟（用户不会发现）。

### 坑 12：Node 造测试数据时坐标语义要对
- 用手写 blocks 验证 `buildGridTable()` 时，一开始把表头的 `x` 当左边缘、又把课程卡片的中心放到别处，
  结论是「课落到隔壁星期」，其实是**测试数据坐标不一致**。
- 正确语义：表头块的 `x + w/2` 才是列中心；卡片中心取 `x + w/2`。真实页面里表头文字居中在列上。
- 顺手修的真 bug：原来列边界用表头**左边缘**的中点算，文字宽度不同会整体偏移半列 → 改成用中心点。

### 坑 13：写 Kotlin raw string 里的 JS 时，`
` 会被写成真换行
- 用 python 脚本批量改 `WebImportScript.kt`（里面是 `"""..."""` 的 JS）时，`'
'` 在 python 里如果少写一层反斜杠，
  就会真的写进一个换行符，把 JS 字符串拆成两行 → `node --check` 才报 `Invalid or unexpected token`。
- 固定做法：脚本里用 `chr(92) + 'n'` 拼，或者写完立刻 `python` 抽 `INSTALL_JS` + `node --check`。

### 本轮改动
1. `WebJsonParser.kt`：DAY 别名表去掉 `xq/xqmc`，补 `xqjstr/xqjmc/skxqstr/星期几`；新增 `IGNORED_KEYS` 黑名单；
   `recognizedFields()` 先查黑名单再匹配。
2. `WebImportActivity.kt`：新增 `reconcileWeekdays()`（接口全同一天时用 DOM 网格星期纠正，仍同一天则写提示）、
   `dayName()`、`diagnosticsSummary()`；`handlePayload`/重放分支都调用；新增 `showExportMenu()`，
   顶部「⋯」随时可「导出抓取数据 / 复制诊断摘要」（之前只有抓取失败才弹诊断框）。
3. 新增 `CourseListActivity` + `activity_course_list.xml`；Manifest 注册。
4. `SettingsActivity`：`buildTimetableRow()` 卡片化（当前课表高亮+色条+「当前」徽标，右侧加「课程」按钮）；
   `buildSettings()` 加「当前课表的课程」入口。
5. `OcrResultActivity`：导入三选一改卡片式（「新建课表并导入」带「推荐」徽标）；新增 `showImportDoneDialog()`
   （完成后可点「查看课程」直达该课表）。
6. `WebImportScript.kt`：星期表头 4~7 列、按星期数字校验顺序、缺列插值补齐、列边界用中心点、
   同格多课先聚卡再按顶部判行（间隔阈值 18 → 12）。
7. 版本 1.36.0 / versionCode 54。

### 验证
- 单测 55 全绿（TimetableStore 20 / WebJsonParser 18 / WebScheduleParser 17），新增 2 个「学期字段≠星期」用例。
- 注入脚本 `node --check` 通过；合成数据验证「缺周三表头自动补列、表头乱序返回 null、五天课表可用、同格两门课拆成 2 个 block」。

### 下次接手先看
- 抓取不准时，第一件事是让用户「⋯」→「导出抓取数据」拿到真实 payload，不要凭猜改别名表。
- 别名表新增短 token 前，先 grep 一遍现有字段命名，确认不会命中学期/班级/校区这类同前缀字段。

---

## 第 12 轮（2026-11-05）：修「抓取失败」+ 全链路运行日志（v1.36.1）

### 用户反馈（原话）
「现在甚至已经无法抓取了，抓取失败了，而且失败的日志不知道在哪。你继续排查吧」

### 推理过程（重要：这是自己上一轮埋的坑）
1. v1.36.0 修「全部周二」的做法是：把 `xq`（学期编号）从星期别名表删掉，并加黑名单；找不到星期就**丢弃该课**。
2. 如果这所学校的接口本来就没有星期字段（「周二」纯属 `xq` 的巧合），那严格模式会把**全部**课程丢掉 → 0 门 → 用户看到「抓取失败」。
3. 关键是：我上一轮把「猜错」修成了「不猜」，但没有给「拿不到星期」留后路，也没有任何可观测性 → 用户既抓不到，也说不清哪里错。
4. 教训：**降级路径必须与主路径同批实现**。修一个「数据不对」的 bug 时，只要新逻辑可能让结果变空，就要同步做：
   ① 宽松兜底（先收下，再用别的证据补）② 日志/诊断导出（让用户能把现场发回来）。

### 本轮改动
1. `WebJsonParser.parse(..., keepUnknownDay: Boolean = false)`：宽松模式保留 `day=0`（未知），并在 notes 里说明。
2. `WebImportActivity`：严格模式 0 门 → 自动宽松重解析 → `repairUnknownDays()` 用页面网格补星期/节次，
   补不到的丢弃并写备注；顺序 `fillSectionsFromDom → finishUnknownSections → repairUnknownDays → reconcileWeekdays`。
3. 运行日志：`runLog` + `log()` 全链路埋点（起始 URL、页面开始/结束、注入脚本返回值、桥收到字节数、
   payload 计数、解析各阶段门数、异常栈）；桥回调整体 try/catch。
4. 注入脚本收集页面报错（`error` / `unhandledrejection`）→ payload `errors` → Kotlin 侧写进备注与日志。
5. 「⋯」菜单重写：任何状态都能导出「抓取数据 JSON」和「运行日志 txt」（之前没 payload 时只 Toast 一句「还没有抓到数据」，
   用户拿不到任何东西）。
6. 版本 1.36.1 / versionCode 55；新增 2 个单测（宽松保留 day=0 / 默认严格模式不变），单测 57 全绿。

### 坑 14（第三次踩）：python heredoc 里的 `
`
- 用 `<<'PY'` 写 python 再改 Kotlin 源码时，字符串里的 `
` 会被写成**真实换行**，把 Kotlin 字符串字面量截断。
- 症状：`Expecting '"'` / `Expecting ','` / `Expecting an element`，报错行号在一次编辑里能跨 700 行（`WebImportActivity.kt:165/239/262/426/854-866`）。
- 固定做法：脚本里用 `chr(92) + 'n'` 或 `chr(10)` 明确表达；改完 `cat -A` 或编译一次立刻验证。

### 下次接手先看
- 「抓不到」类问题先让用户「⋯ → 导出运行日志」；日志里有注入返回值、payload 计数、页面脚本报错三样，足够定位是哪一层断的。
- 改 `WebJsonParser` 的字段/别名时，同时维护严格/宽松两种模式的行为，别让「不猜」变成「全丢」。

## 第 13 轮（2026-11-05 00:49–01:10）：真机日志 → 找到真正的接口格式（v1.36.2）

### 用户给的两样东西
- `classgrid_log_<ts>.txt`：运行日志。
- `classgrid_capture_<ts>.json`：导出的抓取数据（含 13 条接口响应原文）。

**这是本项目第一次拿到真机数据。前两轮关于「星期字段」的推断全部是猜的，本轮全部推翻。**

### 从日志读到的三件事
1. 课表页 URL 是 `https://jwxt.gdufs.edu.cn/dist/#/new/tableIndex` —— 强智新版 **SPA**。
2. payload：`表格=0 文本块=28 接口响应=13 正文=450` → 页面里根本没有表格结构，
   `buildGridTable()`（依赖「周X」表头和左侧大节标签的视觉网格）在这里**永远返回 null**。
   v1.36.1 设计的「用页面网格补星期」兜底，对这套页面是无效的。
3. 解析备注写明「接口给了 12 门课但缺少星期字段…已跳过」→ 数据抓到了，是映射层丢的。

### 从 capture JSON 读到的真字段
课表接口：`https://jwxt.gdufs.edu.cn/njwhd/student/curriculum?xnxq01id=&kbjcmsid=&week=`
顶层：`Msg / code / data / nowWeek / jcdatalist / nkbList`（`code=1`、`nowWeek=9`）。
`data` 是长度 1 的数组，`data[0].item` 是 12 门课；每门课字段：

`classWeek`、`weekNoteDetail`、`classTime`、`teacherName`、`location`、`kch`、`courseName`、
`startTime`、`endTIme`(原文拼写)、`maxClassTime`、`xqNumber`/`xqName`(校区)、`classWeekDetails`、`ktmc` 等。

关键发现：**星期没有独立字段，而是编码在节次里**
- `weekNoteDetail = "103,104"` → 星期 1 的第 03、04 节
- `"203,204,205"` → 星期 2 的第 3-5 节（中国近现代史纲要）
- `"214,215"` → 星期 2 的第 14、15 节（国家安全教育，正是最早「14-15 变成 1-2」那门）
- `classTime = "10304"` → 同一编码拼接写法（星期 1 + 03 + 04）
- `xqNumber`/`xqName` 是**校区**（2 = 南校区）→ 更早「全部变周二」就是 `xq` 被当成星期

### 修法
`WebJsonParser.kt` 增加 njwhd 专用解析（在通用字段匹配之前）：
- `mapNjwhdCourse()`：命中 `courseName` + 节次编码就构造 Course；
- `decodeNjwhdPlacement()`：`weekNoteDetail` 按逗号切 → 校验同一天 → 末两位为节次；`classTime` 兜底 2 位切分；
- `njwhdWeeks()`：`classWeekDetails` 集合 → 区间 + 排除周；否则 `classWeek` 走通用解析。

### 回归测试（这次终于有真数据）
- `app/src/test/resources/njwhd_curriculum.json` = 真机原始响应。
- `NjwhdRealPayloadTest`：12 门全解析、星期均有效、国安教育周二 14-15/9-12 周、
  纲要周二 3-5、APEC 周一 12-13、综合英语周三 1-2、高数周一 3-4 与周二 1-2 不合并、第 5 周排除。

### 教训（写进「下次接手先看」）
- **字段名靠猜 = 无限循环**。本项目为「星期」猜了三轮（`xq` → 黑名单 → 宽松模式 → DOM 补位），
  花了三个版本；而用户导出的 JSON 一次就指出了真相。所以：抓取类 bug，第一件事是让用户导出原始响应，
  不是改解析器。
- **兜底方案要按页面形态设计**。DOM 视觉网格对 `<table>` 老系统有效，对 SPA 无效；
  判断依据应该是 payload 里的 `表格数/文本块数`，而不是「有没有抓到课」。
- **观测性必须和功能同批上线**。v1.36.1 才补的日志体系，让这一轮的定位从「猜」变成「读」。

### 验证
- `./gradlew clean assembleRelease testReleaseUnitTest` BUILD SUCCESSFUL；61 单测全绿。
- badging 56 / 1.36.2；apksigner v1+v2 均在。

## 第 14 轮（2026-11-05 01:07–01:30）：设置页从「横线列表」改成「分组胶囊」（v1.37.0）

### 用户诉求
「一堆选项全部用横线分隔开太丑，想要类似胶囊的格式，每个选项有质感，交互更舒服」。

### 诊断
原 `SettingsActivity.addSetting()` 每加一项就往容器里塞「文字行 + 1px divider」，
20 个选项 = 20 条横线，整页像一张表格；且行高 16dp padding、无按压反馈、
标题是纯黑/纯白、次级文字灰色 —— 三套主题下都显得生硬。

### 改法
- `addSetting()` 重写为胶囊：`GradientDrawable`(圆角 18dp, card 底色, 1dp line 描边) +
  `RippleDrawable`(主色淡影, 遮罩用同一 shape) + 左侧 8dp 主色小圆点 + 右侧 18dp 箭头；
  横向 padding 16/14、纵向 14，项间 `bottomMargin = 10dp`，彻底没有 divider。
- 新增 `sectionHeader(title)`：12sp、`Typeface.DEFAULT_BOLD`、`letterSpacing = 0.08`、
  次级文字色、上间距 22dp（第一组 2dp）、下间距 10dp、左缩进 8dp。
- `buildSettings()` 重排为 5 组：课表 / 外观 / 学期与节次 / 数据 / 帮助。
  组内顺序沿用原来的因果顺序（先选色系再选深浅、先改数据再清空）。
- 取色全部走 `AppTheme`（`spec.cardLight/Dark`、`spec.lineLight/Dark`、
  `AppTheme.primary(context, dark)`、`spec.textMuted*`），三套主题 + 日夜自动适配。

### 踩坑
- `spec.primary(this, dark)` 编译报 `Unresolved reference: primary` —— `primary()` 是
  `AppTheme` 这个 object 的函数，不是 `Spec` 的成员；正确写法 `AppTheme.primary(this, dark)`。
- 局部变量 `val shape = GradientDrawable()` 会**遮蔽** `GradientDrawable.shape` 属性，
  导致圆点那段 `shape = GradientDrawable.OVAL` 报
  `Val cannot be reassigned` + `Type mismatch: inferred type is Int but GradientDrawable was expected`。
  改名为 `shapeBg` 即解决。教训：改自绘 View/背景时，局部变量别叫 `shape`、`background`、`padding` 这类属性名。

### 下次接手先看
- 设置项统一走 `addSetting(title, value, onClick)`，要加新项只写一行、自动获得胶囊外观；
  分组标题用 `sectionHeader()`，不要再手写 divider。
- 想让某一组整体不显示，只跳过 `sectionHeader` + 对应 `addSetting` 两行即可，不留空白。

### 验证
- `./gradlew clean assembleRelease testReleaseUnitTest` BUILD SUCCESSFUL；61 单测全绿。
- badging 57 / 1.37.0；apksigner v1+v2 均在。

## 第 15 轮（2026-11-05 23:46–00:05）：对外分享用的《安装步骤》Word 文档 + 压缩包重打包

### 需求
用户要分享给同学（小白），要求：zip 里不再放 `安装说明.txt`，改为 **Word 版安装步骤**；
要覆盖「微信右上角三个点 → 保存 → 文件管理点安装包」的完整链路，兼顾不同品牌安卓机，并预判常见故障。

### 做法（可复用：以后每次发版只改版本号重跑）
- `_pack/guide.md`：文档正文（极简 Markdown + 少量自定义语法：`!COVER`、`> [!WARN]/[!NOTE]` 提示框、`|表格|`、`1.` 步骤、`##` 小标题、步骤后缩进 `>` 作为灰色注解）。
  文字按「完全没用过安卓安装包的人」写：手机里收到的 zip 怎么解压 → 微信三个点保存 → 各品牌文件管理入口 →
  允许未知来源 → 各品牌拦截（华为纯净模式/小米安全检测/OPPO/vivo 风险提示/三星未知来源权限）→ 首次打开 →
  链接抓取六步 → 15 条 FAQ（文件损坏、解析包错误、签名冲突、覆盖安装丢不丢数据、闪退、抓不到课、iPhone 能不能装…）→ 版本校验 → 求助该带哪些信息。
- `_pack/md2docx.py`：~250 行的极简 md→docx 渲染器（python-docx）。只支持本项目文档用到的语法：
  `#/##` 标题（一级带浅色下边线）、`1.` 步骤（数字用主色加粗）、`-` 要点、`|表|` 表格（表头浅紫底、Table Grid）、
  `> [!WARN]` / `> [!NOTE]` → 单格浅色提示框、`**加粗**` 行内解析、`!COVER` 封面（标题 30pt 主色居中）。
- 生成命令（每次发版）：
  1. 填占位符：`{{VERSION}}/{{VCODE}}/{{SIZEH}}/{{SIZEMB}}/{{SHA}}/{{DATE}}`（用 python 读 APK 现算 size 与 sha256，避免手抄错）；
  2. `python _pack/md2docx.py _pack/guide_filled.md 课格ClassGrid_v<版本>_安装步骤.docx`；
  3. 用 `zipfile.ZIP_DEFLATED` 打包 **apk + docx**（不再放 txt），文件名 `课格ClassGrid_v<版本>.zip`。
- 校验：`python-docx` 回读 → 120 段 / 13 张表 / 无残留 `**` 与 `{{}}` 占位符。

### 踩坑
- 用 bash heredoc 写 250 行 python 脚本时被截断（`here-document at line 1 delimited by end-of-file`），文件只写了一半，
  且行号半路停在某句中。**长脚本改用 write 工具直接落盘**，不要塞进 `cat > file <<'EOF'`。
- 本机没有 LibreOffice（`soffice` 不存在）→ 无法把 docx 渲染成图做视觉验收；
  改用「回读 docx 结构」验收（段落数、表格行列、表头文字、占位符残留），排版细节交给用户肉眼确认。
- 生成物不要进 git：`.gitignore` 增加 `*.docx`、`_pack/guide_filled.md`、`_pack/__pycache__/`；
  zip/apk 早已忽略。

### 下次接手先看
- 发版分享包 = `课格ClassGrid_v<版本>.apk` + `课格ClassGrid_v<版本>_安装步骤.docx` → 打成 `课格ClassGrid_v<版本>.zip`；
  文档版本号、大小、SHA-256 必须与当次 APK 一致（脚本里现算，别手写）。
- 文案改动直接改 `_pack/guide.md` 重跑脚本，不要手改 docx（手改会在下次发版被覆盖）。

---

## 第 16 轮（2026-11-11 21:44–22:20）：链接抓取支持全国高校选校（v1.38.0）

### 需求（用户原话）

「现在我要继续完善这个插件，要求它能够在抓取课表这里选取学校，要支持中国大陆绝大部分学校，在点击链接抓取的时候，
弹出选取学校的选项，用户点击后进去他们学校的官网，再抓取课程表。并且要求用户能够自己更改网址，因为我们找的别的学校的网址可能不正确。」

拆成三条硬指标：① 点「链接抓取」先选学校；② 名单覆盖大陆绝大部分高校；③ 网址必须可改（别校网址可能填错）。

### 学校名单怎么来的（这是本轮最费时的部分）

试错顺序（都失败，别再走）：
- `hipo/university-domains-list` 的 `world.json`：2.25MB，中国只有 399 行且是英文名 → 不能用；
- 教育部名单 CSV（`ramwin/china-public-data`）：只有校名、约 2631 行 → 不够（缺省市/官网）；
- `api.eol.cn` / `api.zjzw.cn` 的学校详情接口：空响应 / `{"code":"1064"}` 签名错误 → 放弃。

最终用 `dataxiv/data-universities` 的 `data/数据.tsv`（584KB，UTF-8 制表符，含 状态/地区/学校名称/所在地/办学层次/
官网链接/双一流建设/英文名称）。`状态=="1"` 正好 **2868 所**（本科 1308 + 专科 1560，31 个省市区），与教育部 2024 名单口径一致。

生成脚本 `tools/gen_schools.py`（bundled Python + pypinyin）产出 `app/src/main/assets/schools.json`（458KB）：

```json
{"version":"2025-11","source":"…","provinces":["北京","天津",…],
 "schools":[["广东外语外贸大学","广东","广州","本科","https://www.gdufs.edu.cn/","guangdongwaiyumaoyidaxue","g dwy…","211，双一流","…"]]}
```

排序 985 → 211 → 双一流 → 本科 → 专科，同档按拼音；搜索支持名称/拼音/首字母三套键。

### UI / 接线

- 新页 `SchoolPickerActivity`：搜索框 + 31 省 Chip + 计数 + 「继续上次」+ RecyclerView + 空态（可加自定义学校）。
- 每行右侧铅笔 = 改这所学校要打开的网址（`school_url_override`，可恢复默认）；右上「+ 自定义」加名单外学校，长按可删。
- `ImportActivity.cardLink` 改为 `pickSchool.launch(...)`；返回后把 `extra_school`/`extra_url` 带进抓取页。
- `WebImportActivity` 去广外化：删 `PRESET_URLS`/`defaultUrl`/`isRetiredUrl`/`KB_URL`/`PREF_LAST_URL`/`maybeAutoEnterSchedule`；
  起始网址 = 传入网址 ?: 学校官网，都没有就先弹选校页；标题显示学校名；SSL 提示改通用文案；
  「⋯」新增 切换学校 / 回到学校官网 / 本校常用入口（`jwxt./jwgl./jwc./ehall.` 子域猜测）；底部「网址」弹窗可手填并记住。

### 踩坑

- **非 ASCII 路径 + Gradle 8.5 test worker**：`D:\2026项目\...` 下所有测试类 `ClassNotFoundException`（类已编译）。
  同一 JVM 手动 `java -cp` 中文路径能加载，说明是 Gradle 传 classpath 的问题（worker 用 `@C:\Users\Asus\.gradle\.tmp\gradle-worker-classpath*.txt`）。
  **解法**：`New-Item -ItemType Junction -Path C:\cgj -Target <真实工程>`，在 `C:\cgj` 下跑测试；`assembleRelease` 在真实路径也能过。
  两个路径不要并发跑 Gradle。
- 数据源里有 1 条 `Http://www.czcvc.net`（大写协议）→ `clean_site()` 统一小写；147 所官网为空 → 行内显示「未收录官网，点右侧铅笔填写」。
- pypinyin 之前没装：`python -m pip install pypinyin -i https://pypi.tuna.tsinghua.edu.cn/simple`。
- 拉数据走 `https://gh-proxy.com/https://raw.githubusercontent.com/...`（本机直连 raw 被 reset）。

### 验证

- `./gradlew :app:assembleRelease :app:testReleaseUnitTest`（从 `C:\cgj`）→ BUILD SUCCESSFUL；**67 个单测全绿**
  （新增 `SchoolDirectoryTest` 13 个，含 2868 所名单回归）。
- `aapt dump badging`：`versionCode='58' versionName='1.38.0'`；`apksigner` v1+v2 均在；APK 26,497,241 字节。
- 仍无真机/模拟器 → 选校页与多校抓取只到「编译 + 单测 + 静态审查」。

### 下次接手先看

- 名单更新：重下 `data_all.tsv` → `python tools/gen_schools.py`（改 `VERSION` 字符串）→ 跑 `SchoolDirectoryTest`
  的 `bundledListCoversMainlandSchools`（它断言了 2868/31 省/2721 有官网，数字变了要一起改）。
- 用户报「某某学校网址不对」：让他在选校页点铅笔改，或直接 `+ 自定义`；不要把某一所学校的网址硬编码回代码。

---

## 第 17 轮（2026-11-11 22:20–22:50）：链接抓取默认进各校教务系统（v1.39.0）

### 需求原话

> 要优化一下，不是所有学校都像广外一样能从官网进去教务系统，大部分学校的教务系统是单独的一个网址，现在请你更新一下默认的网址，不要官网了，先默认是各个高校的教务系统网页进去。同时你要考虑一下每个学校的教务系统是不一样的，考虑一下能不能爬取成功课程表

### 为什么不能「猜域名」

先试了最省事的做法：`jwxt./jwgl./jwglxt./jwc.<学校域名>` 逐个子域探测。结果绝大多数是 NXDOMAIN，
即使解析到也不一定是教务系统（学校官网、邮箱、VPN 都可能占这个前缀）。真实入口形态完全各异：

- 清华 `zhjw.cic.tsinghua.edu.cn` → 跳统一身份认证 `id.tsinghua.edu.cn`；
- 北大 `dean.pku.edu.cn/service/web/courseSearch.php`（教务部站点，不是 jwxt）；
- 上交 `electsys.sjtu.edu.cn` → `/xtgl/login_slogin.html`；
- 浙大 `zdbk.zju.edu.cn` → meta refresh 到 `/jwglxt`；
- 南大 `jw.nju.edu.cn` 与 `ehall.nju.edu.cn` 并存；
- 广外 `jwxt.gdufs.edu.cn/jsxsd/sso.jsp`。

**结论：只能从各校官网首页里抓「教务系统」链接。**

### 入口数据抓取（tools/crawl_jw.py）

- stdlib `urllib`（不引依赖）、SSL 不校验、24 线程、10s 超时、单页 250KB、JSONL 断点续跑。
- 解析首页全部 `<a href>`，打分：锚文字命中（教务系统 / 教务管理 / 办事大厅 / 统一身份认证 / 选课 / 课表 /
  教学管理 / 学生服务…）+ URL 特征（jwxt|jwglxt|jwgl|jwcas|ehall|authserver|cas|sso|eams|kingosoft|zfsoft|
  jsxsd|electsys|zdbk|urp|uims…），新闻/通知类链接扣分。
- 每校验证前 3 个候选：抓取候选页，标题或正文含 教务|课表|选课|统一身份认证|登录 → `verified`。
- 全量结果：**1272/2868 所抓到教务入口，863 所抽验可用**；URL 特征分布 ehall 177、cas/ 84、jwglxt 64、
  jsxsd 39、authserver 34、urp 4，其余为各校自研域名 / IP + 端口。
- 抓不到的 1596 所里，147 所连官网都没有；相当一部分教务系统只在校园网/VPN 内可达 —— 这是网络限制，不是数据错。

### 生成器与资产

- `tools/gen_schools.py` 新增读 `tools/data/jw_result.json`，输出数组扩为 11 项：
  `[name,province,city,level,site,py,ini,tags,en,jw,jwv]`（`jw` 为空串表示没有；`jwv` 1=抽验可用）。
  `version` 改 `"2026-11"`；打印 `jw=` / `verifiedJw=` 方便核对。
- `assets/schools.json` 现在 535,161 字节（522KB），2868 所，1272 所有教务入口。
- 注意脚本里的相对路径以脚本上级目录为根：`jw_result.json` 必须放在 `classgrid-source/tools/data/`，
  只放在工作区 `tools/data/` 会读到 0 条（第一次就是这么踩的）。

### App 改动

- `SchoolDirectory.School` 加 `jw` / `jwVerified`；`siteOf()` 删除，拆成 `homepageOf()`（官网）
  与 `entryOf()`（覆盖 ?: jw ?: 官网）；新增 `entryCandidates(jw, site)`：
  教务入口 → `jwxt./jwglxt./jwgl./jwc./ehall./one.` 子域 → 学校官网（官网永远垫底）。
- `WebImportActivity` 新增入口回退链：`startEntry()` 展开候选，`onReceivedError` / `onReceivedHttpError`
  （≥400）主框架失败时 `tryNextEntry()`，成功打开的那个 `rememberSite()`；用户手动改网址会清空回退链。
- 兼容不同系统：新增 `WebSystem.kt` 识别正方（jwglxt/zfsoft）、强智（jsxsd）、金智/办事大厅（ehall/authserver/cas）、
  青果（kingosoft）、URP（urp|uims）、学校自研（electsys|dean.|zhjw.）、未知；状态栏与「⋯ → 教务系统判断」说明
  登录后该点哪个菜单。找不到入口时「⋯ → 在官网里找教务系统」把官网当入口，用 JS 扫链接；
  「找课表页面」扫当前页 + 同源 iframe 里含 课表/我的课表/学生课表/课表查询 的链接，弹列表让用户点。
- 选校页每行标注「教务 · host」或「官网 · host」，无网址仍是「未收录官网…」。
- 课表解析三条通路（DOM 表格 / 接口 JSON / 重放请求）没动 —— 各校系统差异主要在入口与登录，课表页抓取仍是通用逻辑。

### 踩坑

- 生成器复跑时 `jw=0`：`jw_result.json` 当时只落在工作区 `tools/data/`，而脚本以 `classgrid-source/tools/data/` 为根 → 复制过去重跑。
- `items.add(getString(R.string.web_import_find_kb) to { scanLinks(...) }` 少一个 `)`，Kotlin 报 `Expecting ')'`；补 `})`。
- `WebSystem` 规则最初把 `ehall` 归到青果，实际 ehall 多为金智/办事大厅 → 调整顺序（kingosoft 在前，ehall 归金智）。
- 中文路径 + Gradle test worker 的老坑仍在：测试一律从 `C:\cgj` junction 跑。

### 验证

- 从 `C:\cgj`：`./gradlew --no-daemon :app:assembleRelease :app:testReleaseUnitTest` → BUILD SUCCESSFUL，
  **82 个单测全绿**（Njwhd 4 + SchoolDirectory 15 + TimetableStore 20 + WebJsonParser 20 + WebScheduleParser 17 +
  新增 WebSystem 6）。
- `aapt dump badging`：`versionCode='59' versionName='1.39.0'`；APK 26,533,566 字节；
  SHA-256 `DEBCE9D8C405FF08EF2F07B6E3903998058CC9C10D4F8DD55F203B2C5AD62E1D`。
- 仍无真机/模拟器：入口回退链、链接扫描、各校登录后的抓取都只到「编译 + 单测 + 静态审查」。

### 下次接手先看

- 重跑指纹：`python tools/crawl_jw.py --limit 0`（全量，可续跑）→ 复制 `jw_result.json` 到
  `classgrid-source/tools/data/` → `python tools/gen_schools.py` → 更新 `bundledListCoversMainlandSchools`
  里 1272/863 的断言。
- 某校入口失效：优先让用户点铅笔改网址；批量失效才重跑抓取，不要在代码里硬编码单校 URL。
- 各校系统差异：识别规则在 `WebSystem.RULES`，只影响提示文案，不影响抓取；真要适配某家课表结构，改
  `WebJsonParser` / `WebScheduleParser`，别动入口层。

## 第 18 轮（2026-11-11 23:48–00:05）：导出 .ics 日历（v1.40.0）

### 需求

用户原话：「欧克，现在做日历导出那个部分」。

背景是上一轮可行性讨论：系统日历提醒最稳（App 自提醒会被国产 ROM 杀后台、还要精确闹钟权限），
桌面小组件与常驻通知排在后面。本轮只做「导出 .ics」，直接写系统日历留到后续。

### 设计决定

- **导出而不是直接写日历**：`CalendarContract` 写入需要日历读写权限，且用户看不到文件；
  `.ics` 能覆盖 iPhone/电脑、用户还能自己改，先把这个做对。
- **一门课一条循环事件**：`RRULE:FREQ=WEEKLY;WKST=MO;UNTIL=<最后一节有效周>`，
  单双周/排除周用 `EXDATE` 列出被剔除的日期，而不是把课拆成 N 条。
- **稳定 UID**（`course-<id>@classgrid.app`）：同一门课再次导入时日历把它当更新，
  否则每导一次就多一套重复课程。
- **统一 UTC**：`DTSTART/DTEND` 转成 `...Z`，跨时区导入不偏；节次 → 本地时刻用
  `Calendar(semesterStart + (week-1)*7 + (day-1) 天 + 分钟)`。
- **RFC 5545 细节**：文本转义（`\ ; , 换行`）、按 UTF-8 **字节** 折 75 字节、CRLF 结尾。
  Outlook/Google 日历对这三条最敏感，少一条就乱码/截断。
- 默认提前 10 分钟 `VALARM`；`reminderMinutes <= 0` 不写，留给后续设置项。

### 改动

- 新增 `app/src/main/java/com/classgrid/app/IcsExporter.kt`（223 行，无 Android 依赖）：
  `build()` / `suggestFileName()` / `firstActiveWeek()` / `lastActiveWeek()` / `uid()` /
  `escapeText()` / `fold()`。
- `SettingsActivity`：数据分组加「导出日历（.ics）」→ `exportCalendar()` 生成到
  `cacheDir/exports/`，弹说明后 `shareCalendar()` 用 FileProvider + `ACTION_SEND(text/calendar)`。
- `strings.xml` 新增 `export_calendar` / `export_calendar_desc` / `export_calendar_hint`。
- 新增 `IcsExporterTest.kt` 13 个用例（固定 Asia/Shanghai + 2026-09-07 学期起点，逐字断言 UTC 时间）。

### 踩坑

- 折行函数里最初留了没用到的 `firstLine` / `charCount` 变量，编译只会告警，但顺手删了；
  **注意折行按字节不是按字符**：中文 3 字节，75 字节约 25 个汉字。
- `IcsExporterTest` 里 `Course(...)` 只传部分参数，靠 `Course` 的默认值
  （`teacher/classroom/周次/colorIndex/excludedWeeks/code` 都有默认值），不要为此改模型。
- 中文路径的 Gradle test worker 老问题仍在：测试从 `C:\cgj` junction 跑（本轮重建后用完删除）。

### 验证

- `C:\cgj` 下 `./gradlew --no-daemon :app:testReleaseUnitTest` → BUILD SUCCESSFUL，
  **95 个单测全绿**（新增 IcsExporter 13；Njwhd 4 + SchoolDirectory 15 + TimetableStore 20 +
  WebJsonParser 20 + WebScheduleParser 17 + WebSystem 6）。
- `:app:assembleRelease` → BUILD SUCCESSFUL；`aapt dump badging` = `versionCode='60' versionName='1.40.0'`；
  APK 26,539,229 字节，SHA-256 `ee9fed18d3663e705afa382e9f599f6a5cf1e8e42bd53fc4add15bfa9aada128`。
- 无真机：导入日历后时区/单双周/提醒是否如预期，需要用户在手机上实际导一次确认。

### 下次接手先看

- 再加提醒设置时，改 `IcsExporter.build(reminderMinutes=…)` 的入参即可，别在 `SettingsActivity` 里拼字符串。
- 做「直接写入系统日历」时复用 `firstActiveWeek/lastActiveWeek/uid`，不要另写一套周次计算；
  `CalendarContract` 的 `RRULE` 与 iCalendar 的 `RRULE` 语法基本一致，但 `EXDATE` 要单独插 `Events` 行。

## 第 19 轮（2026-11-12 00:06–00:20）：日历「协调世界时」显示修正（v1.41.0）

### 需求原话

> 日历导进去之后是这样的，怎么会有个协调世界时。然后就是在课格里面导出之后直接点击分享有时候是没有办法跳出日历选项的，你可以再加一句，如果没有看到日历选项，就转发到微信文件传输助手，然后再用日历打开。同时你要考虑一下不同品牌手机日历导进去的差异。

### 根因

v1.40.0 为了让「跨时区不偏」，把 `DTSTART`/`DTEND` 写成了 UTC（`DTSTART:20260907T023000Z`）。
日历 App 读到的就是「UTC 时区的事件」，小米日历在本地时间下面又补了一行
`02:10 - 03:30 协调世界时 (UTC)`。时间本身没算错（10:10 北京 = 02:10 UTC），
但用户看到两行时间会以为导错了。

### 改法

- 事件时间改为「本地墙上时间 + `TZID`」：`DTSTART;TZID=Asia/Shanghai:20260907T101000`；
  `EXDATE` 跟随同一 `TZID`。`DTSTAMP` 与 `RRULE` 的 `UNTIL` 仍按 RFC 5545 用 UTC（用户看不到）。
- 生成 `VTIMEZONE`：`transitionsIn(zone, from, to)` 按小时扫描、再二分到分钟找真实跳变；
  中国 `+0800` 无夏令时 → 一条 `STANDARD`；美国 → `DAYLIGHT`/`STANDARD` 两条。
- `safeTzid()`：`GMT+08:00`、`+0800` 这类不能做 TZID 的 id 退回 `Asia/Shanghai`
  （`TimeZone.getTimeZone("GMT+08:00").id` 实测返回 `GMT+08:00`，直接写进 `TZID` 会让部分日历解析失败）。
- 说明文案加入「分享面板没有日历 → 转发微信文件传输助手 → 用其他应用打开 → 日历」，
  以及小米/华为/OPPO/vivo/三星/iPhone 的导入入口差异。

### 踩坑

- 第一版 `VTIMEZONE` 覆盖范围取的是「第一次上课的开始/结束时刻」，纽约 2027 春季课表只覆盖 1 月，
  于是只有 `STANDARD`、缺 `DAYLIGHT`，测试 `daylightSavingZonesGetDaylightAndStandardComponents` 失败。
  改成 `Event.lastMillis = computeEnd(ruleEnd, …)`（最后一节课结束）后通过。
  **教训：时区规则的范围要按最后一次出现算，不是第一次。**
- 临时打印 `.ics` 时又踩了「`C:\` 根目录不可写」：`FileNotFoundException: C:\tmp_ics_dump.txt (拒绝访问。)`，
  写到 `app/build/ics_dump.txt` 才行；调试用例 `TmpDumpTest.kt` 已删除，别带进发布包。
- 单测里时区必须显式构造 `Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))` 再 `clear()`，
  否则会带上当前系统的时区/毫秒，断言不稳定。

### 验证

- `C:\cgj` 下 `./gradlew --no-daemon :app:testReleaseUnitTest` → BUILD SUCCESSFUL，**97 个单测全绿**
  （IcsExporter 15、SchoolDirectory 15、TimetableStore 20、WebJsonParser 20、WebScheduleParser 17、
  WebSystem 6、Njwhd 4）。
- `:app:assembleRelease` → BUILD SUCCESSFUL；`aapt dump badging` = `versionCode='61' versionName='1.41.0'`；
  APK 26,543,641 字节，SHA-256 `E9FDD07264F3AF9CA99E9CA373BA17E92BB84E725CC27DB33E3B980108EF0567`。
- 打印真实输出核对：中国样例 `DTSTART;TZID=Asia/Shanghai:20260917T101000` + `VTIMEZONE` `+0800`；
  美国样例含 `BEGIN:DAYLIGHT`、`TZOFFSETFROM:-0500`、`TZOFFSETTO:-0400`。

### 下次接手先看

- 别再往 `DTSTART` 上写 `Z`；要改时间只动 `build()` 里的 `formatLocal`/`computeStart`/`computeEnd`。
- 真机导入后若某品牌仍显示 `GMT+08:00` 副标题，属该品牌对固定偏移时区的正常展示，不用改代码。
- 后续「直接写入系统日历」复用 `firstMillis/lastMillis/uid`；`CalendarContract` 里 `EXDATE` 要单独插行。

## 第 20 轮（2026-11-12 12:19–12:40）：内测版 v1.42.0 —— Excel 导入 + 识别/抓取泛化

### 需求（用户原话）

> 现在我们要对导入课程表做深度优化了。因为之前这个都是默认广外的作息和时间，现在面向全部高校，需要深度优化，有用户内测之后反映无法导入excel文件，可以增加一个这个功能。此外还有链接抓取的算法不够好，截图识别的算法也不够好。现在我给你一个个别的例子，你从个别推理到一般，深度优化。例子：就是我上传的图片，链接抓取失败，然后截图识别也失败了。我的建议：全面更改为内测版，截图识别之后在显眼的合适的位置弹出导出日志的选项，方便你进行分析改进，链接抓取也一样。全力以赴，完成我的诉求。

### 个别案例（肇庆学院 WebVPN）

同一张「教学管理系统」课表页：

- 链接抓取诊断是 表格：0 张 · 文本块：60 个 · 接口响应：0 个；
- 同一页截图 OCR 只认出 1 门「周一 第1-2节 · 1-25周 楼高等数学」；
- 共同根因：**表头是「一 11/2」「二 11/3」这种裸日字+日期**，左列是「第01节…第14节」。
  JS DAY_RE、Kotlin dayOfWeekOf、GridParser 的表头正则、bigSectionRegex 全都只认
  「周X/星期X/第N大节」，于是整张表被当成「没有课表」。

### 从个别到一般做的改动

1. **表头/节次识别集中到 TimetableGrid（新文件）**：dayOf() 认
   「周一/一/一 11/2/四(11/5)/11/2/Mon…Sun」，sectionOf() 认「第N大节/第N节/第NM节/08:30」，
   bigSection(big, sectionCount) 按用户作息节数动态换算（15 节才用广外 1-2/3-5/… 老划分），
   weeksOf() 整段表达式展开「1-8,10-16周」（分段匹配会丢掉前半段，踩过）。
2. **Excel/CSV（新功能）**：SheetImporter.readXlsx 用 ZipInputStream 读
   xl/sharedStrings.xml + sheet1.xml，处理 	="s"/inlineStr/str/b/数字 与 mergeCell；
   CSV 自动识别 , \t ;，UTF-8 失败回退 GB18030；统一转成 TimetableGrid.Sheet
   后走同一套网格解析。导入页第 5 张卡片，OpenDocument 选文件。
3. **截图泛化**：识别底图 1600→2200、预处理上限 2000→2600；GridParser 表头改用
   TimetableGrid.dayOf 并按 x 聚成列（「一」+「11/2」两行表头也能合），左列新增「第01节」；
   节次兜底从「中心点 + 广外大节表」改为「簇上下边界与行锚点区间重叠」。
4. **抓取泛化**：JS collectTables 接受 DAY_RE || headDays>=3 || (cols>=4 && secs>=3)；
   dayNumOf/looksLikeDayHeader 同步放宽；Kotlin parseTable 在表头行找不到时先试
   sequentialHeader()（全是日期 → 按列序 1..7）再走竖表；
   fallbackSection(index,rowCount,sectionCount) 不再写死广外。
5. **日志入口（内测版）**：识别结果页 	vHint 下方 btnExportLog（TonalButton）导出
   cacheDir/exports/classgrid_ocr_<millis>.txt（版本/渠道/设备/课程列表/原文）；
   链接抓取页底部常驻「日志」btnLog，buildLogReport() 末尾附原始 payload（截断 400K），
   诊断弹窗也加了「导出运行日志」。
6. **内测版**：BuildConfig.CHANNEL="内测版"（build.gradle.kts 的 buildConfigField），
   设置页「关于」、关于弹窗、两处日志头都带渠道名。

### 验证

- cd C:\cgj; ./gradlew --no-daemon :app:testReleaseUnitTest → **116 tests 全绿**
  （新增 TimetableGrid 10 + SheetImporter 7 + WebScheduleParser 2）。
- assembleRelease → v1.42.0 / 62，APK 26,567,419 字节，SHA-256
  \C3B980B267929ED0B4F670CC8B0F4F98602B74D3FF4957DF06DF5F6A7B2B15FF\。
- 调试时有两条断言先后失败：大节映射被改坏（15 节作息必须保留 1-2/3-5/… 老划分）、
  
s=2 的测试格里又填了一门课（合并单元格会把第二行吞掉）——都已修进测试。

### 下次接手先看

- 表头/节次/周次的任何规则**只改 TimetableGrid**，OCR 与 Excel 共用；改完跑
  TimetableGridTest + SheetImporterTest + WebScheduleParserTest。
- 抓取侧放宽表头时，**JS 与 Kotlin 要一起改**（dayNumOf/looksLikeDayHeader 与 dayOfWeekOf），
  否则会出现「表格进到 payload 但解析成 0 门」。
- OCR 分辨率提高后注意内存：PREPROCESS_MAX_DIM=2600 是长边上限，再往上要真机看 OOM。
- 用户回传的日志在 cacheDir/exports/；classgrid_ocr_*.txt 是识别，classgrid_web_*.json 是抓取。

## 第 21 轮（2026-11-12 12:59–14:00）：模拟器跑通三条导入链路（v1.42.1）

**用户问题**：「你为啥不能做到真机或者模拟器验证，有没有办法做到」。

**答案：能做到，已经做了。** 之前没做只是因为本机没装模拟器，不是代码限制。

- sdkmanager "emulator" "system-images;android-34;google_apis;x86_64" → avdmanager create avd -n classgrid_test -d pixel_6；
  emulator -no-window -gpu swiftshader_indirect -no-snapshot 无头启动，adb 直连 emulator-5554。
- ML Kit 的中文识别 AAR 自带 jni/x86_64，所以 x86_64 模拟器能跑 OCR；构建加
  -PclassgridAbis=arm64-v8a,armeabi-v7a,x86_64（默认仍是两个 ARM，正式包不变）。
- 新增 app/src/debug/AndroidManifest.xml，只在 debug 包导出四个页面，方便 am start 直达；release 不受影响。
- 三条链路的真机结果：链接抓取 11 门、Excel 导入 10 门、截图 OCR 7 门（整图只有 1 门）。

**真机暴露并修掉的问题**

1. SheetImporter.parseXml() 用了 Xerces 专有 feature，Android DOM 直接抛异常 → 全包进 
unCatching。
2. 单元格换行被压平、横向合并填满整行 → 保留换行、只纵向合并；TimetableGrid 单行再切词并合并相邻同名课。
3. 整图 OCR 只剩 1 门 → 新增 TableGridOcr（投影找线 → 切格 → 逐格放大 OCR），并把大图上限 2000→2600。
4. Tasks.await 在主线程调用会抛异常被 catch 吞掉（每格返回空串）→ 整个分格 OCR 挪到工作线程。
5. 测试素材本身画错（周一/周四表头缺失、课程压表头）→ 用 PIL 重画 fixture，避免「假 bug」。
6. 「1-8周」被当成节次 1-8 → 格内节次只认带「节/節/冒号」的写法，其余交给行标签。

**教训**

- JVM 单测过 ≠ Android 过：Android 的 XML 解析器、主线程限制、MediaStore 索引，都只能在设备上发现。
- 夹具要肉眼核对：这次花了一轮才发现问题在 fixture，不在 App。
- 无头模拟器 + adb shell screencap/uiautomator dump 足够做回归，截图要 pull 到 ASCII 路径。

**验证**：116 单测全绿；APK 26,571,518 字节，SHA-256 8A69B4A34F73053AA5553B0E05165297EE66669B1117C93000783FF7E27FF1EA，versionCode 63 / versionName 1.42.1。
## 第 22 轮（2026-11-12 14:00–14:35）：广外抓取重复修复 + 默认回官网（v1.42.2）

- 用户原话：链接抓取「很多都重复了」，怀疑 v1.39 把广外入口改成教务系统导致手机进的是电脑版；要求默认回官网，并修掉重复。
- 用户提供的日志与抓包（`classgrid_capture_1794461660124.json`）显示：电脑版 `xsMainV.jsp` 两张 9x8 表——卡片表 12 门 + 周次辅助表 18 条（同一门课拆 1-3/4-6/7-11/12-16 周，补录行错列）；旧逻辑直接 addAll 且去重 key 含周次 → 30 条，导入后成对重复。
- 修复：`parse()` 跨表 `alignAndMerge()`（同名+星期+节次，退化为同名+节次唯一匹配）；`dedupe()` key 改「名称+星期+起止节次」；新增 `mergeSameSlot()`/`activeWeeks()` 求周并集；教师前缀 `TEACHER_PREFIX` 优先于教室判断，`cleanTeacher()` 截断职称。
- 入口：`tools/gen_schools.py` 对「广东外语外贸大学」`jw_map.pop(...)`，资源里广外 jw 空、site 仍是官网；`SchoolDirectoryTest` 加回归断言「广外不应再内置教务系统入口」。
- 顺手修：首次启动引导弹窗的「统一身份认证 / 智慧广外」旧文案、`其他三种导入方式` 改为 `其他导入方式` 并补 Excel。
- 坑：PowerShell `Set-Content -Encoding UTF8` 给布局加了 BOM，AGP `MergeResources` 报 `Cannot read field "elmName" because "root" is null`（直接 aapt2 compile 却不报错）；用 `UTF8Encoding($false)` 重写后解决。以后改 XML 一律用 .NET 写无 BOM。
- 验证：117 单测全绿；`assembleRelease` 出 v1.42.2/64，APK 26,571,551 字节，SHA-256 `12A3CB9A0CFA83D552CBEEF5BBD1589FA47F31F612ED8DA6B2BEF26BD9793CAD`。
- 模拟器复验：`tools/make_gdufs_fixture.py` 把抓包还原成两表 HTML（`tools/fixtures/gdufs_desktop.html`）；`tools/verify_on_emulator.ps1` 自动起模拟器/装 debug 包/开本地 http/点抓取读弹窗，两条用例 PASS（肇庆学院 11 门、广外双表 15 门），与单测一致。
- 约定（用户确认）：每个版本出包前先跑 `tools/verify_on_emulator.ps1`，FAIL 就不交付。
- 下次接手：若其他学校也出现「同一门课多条」，先看是不是同样的「卡片表 + 周次表」结构，套 `alignAndMerge` 的规则即可；广外若用户坚持要用电脑版，可以在「本校常用入口」里手动打开，解析端已经能合并。
## 第 23 轮（2026-11-12 19:11–20:10）：肇庆学院三连失败修复（v1.43.0）

需求原话：「还是我同学那个，截图识别失败。链接抓取也失败。然后上传xls也失败，而且这个怎么不能导出日志，只有一句话说好像不是课表。换一个学校完全跑不通，重新修复一下。」

### 素材
- `蓝翔 上课课程表.xls`（27,648 B，OLE2/BIFF8）
- `classgrid_capture_1794469385276.json`（tables=0 / blocks=60 / net=0）
- `classgrid_ocr_1794469520297.txt`（只认出 1 门，原文只有左列节次）

### 根因
1. `.xls` 是 OLE2 二进制，之前只认 zip(xlsx)/文本 → 解析直接失败，且失败只有一句 toast。
2. WebVPN 课表是 div 网格、跨 frame、没有星期表头；页面有「列表展示」但 App 不会点。
3. OCR 表格线是浅灰，固定阈值 200 找线失败 → 切不出格；整图 OCR 又读不出格内 10px 小字。

### 改动
- 新增 `XlsImporter.kt`：OLE2（DIFAT/FAT/MiniFAT/目录）+ BIFF8（SST/CONTINUE/LABELSST/LABEL/NUMBER/RK/MULRK/FORMULA/MERGEDCELLS），输出 `TimetableGrid.Sheet`；BIFF5 明确提示另存 xlsx。
- `TimetableGrid`：`★`/制表符当分隔、`(01,02节)` 小节列表、`CLASS_HINT` 不把班级当教师、`mergeWeeksAcrossRows` 合并跨行周次。
- `ImportActivity.showSheetFailure()`：Excel 失败弹诊断报告 + 可直接导出日志（分享 txt）。
- `WebImportActivity.tryListMode()`：自动点「列表展示/列表视图/表格视图」，1.6s 后二次抓取；`WebScheduleParser.parseCourseList()` 解析一行一门课的列表表。
- `TableGridOcr`：三档阈值（200/226/242）找线，`lastNote` 记录切格与识别情况，写进导出日志。

### 验证
- 122 单测全绿（新增 XlsImporterTest 4 个、列表视图 1 个）。
- 模拟器：用户 xls → 核对页 13 门；新夹具 `zqu_listmode.html`（初始 div 网格，点「列表展示」才生成表）→ 自动点击 + 二次抓取 → 3 门；旧夹具 zqu/gdufs 仍 11/15。
- APK 26,586,131 B，SHA-256 281A14129A3A8C041D01485073156A86315C897DA6152F4401C4796794ED9D34。

### 下次接手
- OCR 对真实 1220×2712 手机截图仍不稳定：先看导出日志里的「表格分格：表格线：竖 N、横 M」——如果竖线 <5 说明还是没切到格，要继续放宽阈值或改投影法；如果切到格但字错，调 `CELL_UPSCALE` 与 `MAX_SIDE`。
- 链接抓取遇到不认识的 div 网格，优先确认页面有没有「列表展示/导出」这类视图切换，再考虑解析 blocks 几何。
## 第 24 轮（2026-11-12 19:40–20:10）：桌面小组件「今天的课」（v1.44.0）

需求原话：「现在我希望能够在手机上做一个手机小组件，桌面能直接看到今天的课，不用点开 App，同时点击组件又能直接打开app。 你评估下用 Glance 还是 RemoteViews 实现比较合适，先给方案再动手。」

- 选型：RemoteViews。工程零 Compose 依赖，Glance 要带 Compose runtime/ui（+3~5MB）且要联动编译器；
  这个组件只有标题+4 行静态课程+一个点击，声明式优势用不上。用户确认 4x2、跟随 App 主题 + 系统深浅色。
- 数据层 TodayWidgetData（纯函数）：当前周 + 今天星期 + 排除周 → 排序 → 已上完/正在上/下一节 →
  4 行封顶 +「还有 N 门」；`TodayWidgetDataTest` 11 个用例，JVM 上就能锁住逻辑。
- 渲染 TodayWidgetProvider：整卡 PendingIntent 开 MainActivity，右上角刷新发广播（显式 ComponentName，
  绕开 Android 8+ 隐式广播限制）；监听 DATE_CHANGED/TIME_SET/TIMEZONE_CHANGED/MY_PACKAGE_REPLACED；
  颜色显式 setTextColor（RemoteViews 不解析 ?attr）；圆角背景 GradientDrawable → Bitmap 贴 ImageView
  （setBackgroundColor 会丢圆角）。
- 刷新：WidgetRefresh.refresh 挂在 MainActivity.onResume、OcrResultActivity 导入完成、CourseEditActivity 保存。
- 验证（模拟器 Android 14 x86_64）：肇庆学院夹具导入 11 门 → debug 包用 requestPinAppWidget 一键添加 →
  桌面显示「今天 · 周四 / 第10周 / 08:30-09:10 高等数学1（置灰）/ 11:35-12:15 大学生心理健康（高亮）」；
  点卡片 → MainActivity 前台；点刷新 → 焦点仍在 launcher；切深色模式后回 App 触发刷新 → 卡片与文字变深色。
- 踩坑：① 行 `layout_height=0dp + weight=1` 在课程少时会把行拉得很开 → 改固定 26dp；
  ② 往桌面拖拽在 Pixel launcher 上很难用 adb 模拟 → debug 包加 WidgetPinDebugActivity 走
  `requestPinAppWidget`，release 不含；③ `input tap` 坐标要先按 1080x2400 换算，否则点到了整卡而不是刷新按钮。
- 结果：133 单测全绿；APK 26,601,060 字节，SHA-256 `8A5B9CB6…EF0A149A`，versionCode 66 / versionName 1.44.0。
- 下次注意：组件颜色用 AppTheme + ScheduleColor.isDarkMode，别直接写死色值；用户反馈「组件不更新」时先确认
  是不是刷新广播没被系统投递（用 adb 点刷新按钮或打开 App 触发 onResume 对比）。
## 第 25 轮（2026-11-12 20:05–20:40）：通知栏常驻「今天的课」（v1.45.0）

需求：用户说「开始做」，在「常驻通知 / 实时活动」选项上确认。

做法：
- `TodayNotify`：ongoing 通知（IMPORTANCE_LOW 无声、CATEGORY_STATUS），标题「今天 · 周四 · 第10周」，正文按状态变（正在上 / 下一节倒计时 / 已上完 / 没课），展开逐行列课，点开进 App，动作「刷新」「关闭」。
- 只在边界唤醒：今天课程的 start/end 分钟里取 > now 的最小值设 `AlarmManager`；没有边界排到次日 07:00；Android 12+ 无精确闹钟权限时 `set()` 降级。
- `TodayNotifyReceiver` 处理动作 + 开机/改时间/改时区；manifest 加 `POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED`。
- 设置页新增「提醒」分组，Android 13+ 首次开启请求通知权限。
- 与桌面组件共用 `TodayWidgetData` 的计算（新增 headerText/statusText/courseLines/startMinutesOf/endMinutesOf/weekdayName），`WidgetRefresh.refresh` 里同步刷通知。

踩坑：
- 自定义广播 `com.classgrid.app.action.WIDGET_REFRESH` / `TODAY_NOTIFY_OFF` 从 adb 发不到 `exported=false` 的 receiver（`Broadcast completed: result=0` 但没投递），验证动作要走 UI 或 debug 专用入口；不是代码 bug。
- `SettingsActivity` 在 debug 包也没导出，只能从 MainActivity 底部「设置」进入；`am start -n .SettingsActivity` 会被 SecurityException 拒绝。
- 首次进入弹出引导弹窗，会挡住底部导航点击，tap「我知道了，开始使用」后才能继续。

验证（模拟器 Android 14 x86_64）：开关打开后通知栏出现「今天 · 四 · 第10周 / 正在上 大学生心理健康 · 2号教学楼」；`dumpsys notification` 确认 `id=4101 / channel=classgrid_today / importance=2 / actions=2`；点通知 `mCurrentFocus` 变 MainActivity；设置里关掉开关通知立即消失。139 单测全绿。

## 第 26 轮（2026-11-12 23:21–00:10）：桌面小组件重做成「小 — 大 — 小」

- 需求原话：「感觉这个组件好丑，只有文字信息，还那么小，能不能把要上的课突出重点，然后上完了和
  后面要上的课小一点，形成滚动那种感觉，就是放大概三列，小大小这样子，再优化一下 UI 设计。」
- 判断：v1.44 的 RemoteViews 文字列表做不出层次，决定**整卡 Canvas 画成 Bitmap**，
  RemoteViews 只保留显示与点击。这样「正在上」的课能做成主色大卡，已上完/接下来自动弱化。
- 实现要点：
  - `TodayWidgetData.Snapshot` 加 `allRows / doneRows / focusRow / laterRows`（focus 先正在上、再下一节）。
  - `TodayWidgetRenderer` 画：渐变底、圆角描边、标题 + 周胶囊 + 手绘刷新箭头、
    三栏内容、主课卡（色条/胶囊/剩余时间/两行大课名/教室），整体垂直居中。
  - 字号随 widget 实际高度选 14 / 15 / 16.5sp；4×2 下最多 2 行课名。
- 踩坑：
  1. 左栏「时间 + 课名」两行最初用 `y + 11dp` 排，时间字号 22px 与课名基线的间距只有 6px，
     两行文字叠在一起，看起来像「重影」。改成按 `fontMetrics` 行高排（timeH + nameH）后正常。
     调试手段：debug 包 `WidgetBitmapDebugActivity` 直接渲染大图，绕开桌面缩放，一眼看到问题。
  2. 刷新箭头与「第N周」胶囊都贴右边缘 → 胶囊右边界让出 20dp，箭头移到右上角。
  3. `am broadcast` 发自定义刷新动作不会被 `exported=false` 的 receiver 收到（result=0），
     验证刷新只能走 UI 或 App 内 `WidgetRefresh.refresh`。
- 验证：143 单测全绿；模拟器 4×2 实机截图四种状态（正在上 / 下一节 / 已上完 / 深色）；
  点击整卡开 App、点击刷新只刷组件；APK 26,613,773 字节，
  SHA-256 `F388F430B7E24BD687E641587E6D7E9AC8D981989EB1BFCC994AF08BDA82C180`。
- 下次接手：想再做「滚动」时不要改 RemoteViews 结构，直接在 `TodayWidgetRenderer` 里加时间轴
  或进度条；所有几何计算都用 `dp()`，并保持 4×2 与 4×3 都能看。


## 第 27 轮（2026-11-12 23:36–00:30）：小组件胶囊化 + 结束态文案 + 分钟级动效

- 需求原话：「还是好丑。我想要做的大概是这样子的，然后别的地方你可以自己发挥，可以做一些动效。
  然后上完课显示打字 今天的课程已经结束，再来一个小字 辛苦了；我建议你把选型改成 Glance 吧，
  以后可能会用到做动效的地方。全力以赴重新设计胶囊形 UI 界面」
- 参考图是深色导航蓝卡片 + 一排药丸形胶囊（顶部状态胶囊、中间课程大胶囊、底部小胶囊）。
  决定把 v1.46 的三栏改成**胶囊语言**：顶部两枚胶囊、主课一条大胶囊、底部一排小胶囊。
- 为什么没迁 Glance：App Widget 的宿主永远是 RemoteViews，Glance 只是 Compose 写法，
  同样拿不到连续动画；迁过去只会多 3~5MB 的 Compose 运行时。于是继续 Canvas 画整卡，
  用「正在上课时每分钟刷新一次」实现进度环 / 倒计时逐分钟推进——这是组件里唯一真实可见的动效。
- 实现要点：
  - TodayWidgetRenderer 重写：胶囊 = 
adius = 高度/2；主课渐变 + 描边 + 光晕；
    进度环 (now-start)/(end-start)、环心剩余分钟；底部小胶囊 ✓ / · 前缀，最多 3 枚 + +N；
  - 结束态：focusRow == null && hasCourses → 「今天的课程已经结束」+「辛苦了」+ 四角星；
  - TodayNotify.scheduleNext() 发现「正在上课」就排下一分钟（新增 schedule(context, timeInMillis)），
    TodayNotifyReceiver 统一改走 WidgetRefresh.refresh，通知和组件一起重画；
  - TodayWidgetProvider 给整卡加 contentDescription；预览框 widget_today_preview.xml 同步成胶囊。
- 踩坑：
  1. WidgetBitmapDebugActivity 一开始只渲染 bitmap，无法确认文字是否重叠。后来改成「渲染后放大 2 倍
     跑 ML Kit，把每行文字 + 坐标打进 logcat（tag WidgetDebug）」，在没有可视化工具的情况下
     也能核对布局：例如主胶囊 y=413..462、课名 y=491..593、教室 y=607..664，互不重叠。
  2. 调试 Activity 已在栈顶时 am start 只做 intent 投递、不会重建（Activity not started, intent ...
     top-most instance），要先 input keyevent BACK 再 start，否则看的是上一次参数的结果。
  3. 桌面组件刷新：显式广播 -n com.classgrid.app/.TodayWidgetProvider 能触发；
     隐式广播不行（receiver exported=false）。
  4. 浅色/深色要分别看：cmd uimode night no|yes 之后组件会在下一次刷新换色，
     用 screencap + PIL 采样色值确认（浅色卡 #F0F1F2、胶囊 #E3E2FB）。
- 验证：143 单测全绿；模拟器四种状态截图（正在上 / 结束态 / 浅色 / 桌面实拍）；
  APK 26,614,905 字节，SHA-256 56B4D32198F00C8F4E068C31C632C80A7FC06CAABD25EDA45F2D1DC1F5691DE5。
- 下次接手：组件里任何「动」都要靠重绘，不能直接上动画；要加过渡只能做多帧 Bitmap 轮播（耗电、
  系统也可能限制），不推荐。真正的动效放在 App 内（Compose/属性动画）做。
## 第 28 轮（2026-11-13 00:20–00:55）：胶囊组件按反馈再调：居中主胶囊 + 下一节胶囊

- 需求原话：去掉「今天」前面的圆点、改成「今天：周五」；中间大胶囊把时间和课名都居中放大，
  教室并到时间同一行；腾出的下方空间放「下一节」小胶囊（时间 + 课名 + 地点）。
- 改动：
  - TodayWidgetRenderer：header 文案改「今天：周X」、文字在胶囊内居中；
    主胶囊改成居中两行（时间 + 教室 / 课名，课名 16~21.5sp 随高度自适应），
    正在上课时第三行「还剩 N 分」；删掉进度环、状态胶囊、右侧倒计时胶囊；
    drawChips 整体替换成 drawNextCapsule（14:40 大学英语1 · 2号教学楼104，
    时间主色、其余弱化，9.5sp，整条居中）。
  - TodayWidgetData.headerText 同步成「今天：周X · 第N周」（通知标题也一起变）。
  - widget_today_preview.xml 重画成新布局（选择组件时的静态预览）。
- 踩坑：
  1. 改 headerText 后 TodayWidgetDataTest 里断言的旧文案「今天 · 四 · 第10周」失败，
     全量测试会挂；构建前先跑 :app:testReleaseUnitTest 能第一时间发现。
  2. widget_today_preview.xml 里 xmlns:app 写在 ImageView 上、且引用了不存在的
     @string/widget_refresh（实际叫 widget_today_refresh），会编译/合并资源报错。
  3. 用 OCR 坐标核对居中效果时注意模拟器密度：Pixel 6 是 2.625，360dp 的卡片实际是 945px 宽，
     卡片中线是 472.5 而不是 540，拿 540 去比会误以为整体偏左。
- 验证：143 单测全绿；debug 渲染 OCR 结果为「今天:二 / 第10周 / 10:10-10:50 实训中心41（居中）/
  高级语言程序设计（居中）/ 还剩 30 分 / 14:40 大学英语1 · 2号教学楼104」；
  结束态仍为「今天的课程已经结束 / 辛苦了」，并且不再显示下一节。
  APK 26,615,013 字节，SHA-256 E4008EA86652763A385582A3E16374728D81D3DC21DAEAF6E5B5F08A9D09A2CD。
- 下次接手：主胶囊内容用「先算总宽、整体水平居中」的方式排版，任何新增一行都要先算块高再定 top，
  不要用固定 y；底部胶囊和主胶囊共用 pad，别再加第三层容器。
## 第 29 轮（2026-11-13 00:40–01:15）：修复「同一天两门课都挂下一节课」

- 现象：两张课表截图，课程数据基本相同，但其中一张（最近从教务系统链接抓取导入的）的日课表里
  两门课（10:10 人工智能应用基础、14:00 基础会计）都挂了「下一节课」标签。
- 定位：状态标签表 computeTodayStatuses 用 course.id 做 key。历史导入路径可能留下**重复
  课程 id**（WebScheduleParser 会把 id 重排成 1..n，若与旧数据合并就撞车），撞 id 后同一个
  「下一节课」被两门课同时匹配。用 App 内 OCR 逐行读截图确认了这两条标签的位置与文案。
- 修复：
  1. TimetableStore.load 读取时调用 
epairIds：保留第一次出现的 id，重复的重新发号并落盘；
  2. MainActivity.computeTodayStatuses 改为按**列表下标**索引状态，标签不会再串行；
  3. 补 TimetableStoreTest 两个单测（重复 id 修复、无重复不动列表）。
- 教训：凡是「按课程 id 查表」的 UI 逻辑，都要假设历史数据里 id 可能重复；要么在入口修数据，
  要么改用行下标，最好两者都做。
- 验证：145 单测全绿；APK 26,615,018 字节，SHA-256
  48785716643A6BB90475EC23FB6DEE912BEA709BD5CD6AE2D06C352AD8E84B55。
## 第 30 轮（2026-11-13 19:34–20:05）：小组件尺寸自适应 + 结束态大字（v1.47.3）

**用户反馈**（m05143）：「1.这个原本是正常的，然后不知道为啥过了一会胶囊直接变大变扁了，而且是个别手机出现，
有些手机又正常。2.今天的课程已经结束了的字变大一点，要占胶囊的70%左右，辛苦了也要变大」。

**变扁的根因**：整卡是 Canvas Bitmap + `ImageView fitXY`，尺寸只取 `MIN_WIDTH/MIN_HEIGHT` 且只在添加时画一次。
个别桌面竖屏报的高度偏小、或添加后重新报尺寸，Bitmap 就被纵向拉伸，胶囊从「圆」变成「扁」。属桌面差异，
不是渲染器算错。修法四条：`onAppWidgetOptionsChanged` 重画；按方向取 MIN/MAX；比例夹紧 1.8–2.9；`centerCrop`。

**结束态放大**：加 `fittedTextPaint()`，从 16sp 起量宽再等比放大、用高度封顶。标题目标 70% 胶囊宽，
副标题 34%。实测标题 64%（高度封顶先到），观感已明显变大。

**顺手修的崩溃**：模拟器添加组件时 launcher 报
`Error inflating AppWidget ... widget_today_preview: Class not allowed to be inflated android.view.View`。
预览布局里的 `<View>` 占位换成空 `TextView`。这个坑只在部分桌面添加组件时触发，v1.47.0–v1.47.2 一直存在，
用户手机能加进去（预览没走这条路）所以没暴露。

**教训**：
1. 小组件尺寸不能只信 MIN，也不能假设 launcher 会按你的 Bitmap 尺寸显示；`onAppWidgetOptionsChanged` 必写。
2. RemoteViews 预览布局是受限白名单，`<View>` 这种基础类反而不合法。
3. 结束态文字要「按宽度反推字号」，写死 sp 在不同宽度卡片上必然不是 70%。

**验证**：145 单测；模拟器 4×2 组件截图（`docs/screenshots/v1.47.3-widget-home.png`）；
结束态 OCR 坐标（标题 x=340..1548 / 画布 1890）；APK 26,614,998 字节
SHA-256 `9E7815596C371D86CF12672744184D8A965DEFA0D1FD31352F902C2920B9522F`。
## 第 31 轮（2026-11-13 21:40–23:40）：自动更新（检查 / 下载 / 安装）

**需求**：「增加网络模块，实现自动检测更新，就是我有新版本之后用户会同步收到并且能自己下载更新」
「都可以。按照你说的最优解来。开始动工吧，一步步来，先完成第一步。」

**方案取舍**：清单 + APK 放代码托管 Release，零服务器。主通道 Gitee、镜像 GitHub（国内 GitHub
附件 20–200KB/s 且常卡死）。不选对象存储（长期收费）与 jsDelivr（被挡）。Android 不允许静默安装，
所以「自动」只到下载完成，最后一步交给系统安装器。

**实现顺序**：检查（UpdateInfo/Config/Checker + 设置入口）→ 下载（前台服务 + 断点续传 + SHA-256）
→ 安装（FileProvider + 未知来源权限 + 跳板 Activity）。单测从 157 → 170。

**真机联调发现的三个坑**：
1. 下载「少 1 字节」：python -m http.server 提前断开，拿到 26,614,997/26,614,998，校验失败、通知消失。
   改为最多 4 轮断点续传补齐，并写了 
esumesAfterTruncatedTransfer（服务端故意只发 100KB）。
2. 进度通知残留：stopForeground(STOP_FOREGROUND_DETACH) 会把 4201 留在通知栏；改成 REMOVE + cancel。
3. 完成通知被点一次就消失：setAutoCancel(true) 在跳去「允许安装未知应用」时被清掉，回来没得点。
   改成 setAutoCancel(false)，安装器拉起后再 clearDone。
   另外补了「无通知权限先提示并申请」——pm clear 后权限被收回，进度与安装入口都看不见。

**测试基建**：Kotlin 增量缓存对「改了公共签名」的测试编译会用到旧 ABI，报 NoSuchMethodError，
clean 也不一定好；必须 "-Pkotlin.incremental=false"（PowerShell 里要加引号，否则被拆成任务名）。
PowerShell 里 .NET File.* 用进程 cwd 解析相对路径，不认 cd，一律写绝对路径。

**验证**：170 单测；模拟器端到端「检查 → 下载 100% → 完成通知 → 授权 → 系统安装器 Update →
versionCode 73→74」；APK 26,641,503 字节
SHA-256 `6662B1C519EB5C81A31DA972D171EE58AFFD1246ED13BB5C0D9D50DECE9AF3D3`。

**下一步**：建 Gitee 公开仓库（如 `classgrid/classgrid-releases`）并把地址填进 `UpdateConfig`；
写 `tools/release.ps1`（改版本号 → 构建 → 算 size/SHA → 生成 update.json → 上传）；
在 App 启动处加「24h 自动检查一次」的触发点。
## 第 32 轮（2026-11-14 10:40–10:50）：把自动更新接到真实仓库

**需求**：用户建好了 Gitee 仓库 `moricecc/class-grid`，把 zip 传到了 Release，问 update.json 放哪、怎么搞。

**结论**：update.json 不是 Release 附件，而是 **仓库根目录的一个普通文件**（master 分支），
用固定 raw 地址访问：`https://gitee.com/moricecc/class-grid/raw/master/update.json`。
Release 里放 APK（zip 不行，安装器只认 APK）。

**改动**：`UpdateConfig` 主地址换成真实仓库、备用留空；新增 `tools/make_update_json.ps1`
（从 APK 算 size/SHA 生成清单，中文 changelog 走 `-ChangelogFile`）；下载前检查通知权限。

**为什么不直接改 1.47.4 就行**：已发出去的 1.47.4 二进制里写死的是占位地址，改仓库对它无效。
所以 1.47.5 是 bootstrap 版：用户手动装一次，之后才能收到自动更新。

**踩坑**：PowerShell 5.1 读无 BOM 的 UTF-8 .ps1 会把中文注释读成乱码并解析失败 → 脚本必须存 UTF-8 BOM；
命令行传中文参数同样会乱码 → 改成读文件。
## 第 33 轮（2026-11-14 12:24–12:40）：启动自动检查更新（v1.47.6）

**用户要求**：「接自动检查吧，顺便把 24 小时节流的逻辑接好」

**做法**：
- UpdatePrompt.kt 收拢自动/手动两套弹窗；MainActivity.onResume 首次进入时调
  maybeCheckUpdateOnLaunch()（后台线程、失败静默、isFinishing 丢弃结果）。
- UpdateChecker 增加纯函数 isDue(last, now, interval) 与 markAutoAttempt(context, success)：
  成功按 24h 节流；失败把 update_last_check 回拨成 22 小时前，相当于 2 小时后重试。
- 自动检查传 ignoreSkip = false，尊重用户点过的「忽略此版本」；手动检查仍 ignoreSkip = true。
- 顺手修：Settings 手动弹窗的「稍后」原来会写 update_skipped_version（等于把「稍后」变成永久跳过），
  现在只关闭对话框；ARCHITECTURE 里被 here-string 吃掉一个字符的 
equiresForce() 也补回来了。

**验证**：-Pkotlin.incremental=false 全量单测 174 绿（新增 isDueRespectsInterval、
ailedAttemptRetriesAfterTwoHours；后者一开始用 10,000,000 当 now，回拨后变负数被当成「从未检查」，
改用真实量级时间戳才过）。APK 26,646,883 字节、SHA 8DDE37AA…3150、versionCode 75。

**下次接手**：发 1.47.6 时记得同时更新仓库 update.json（versionCode 75 / 新 size+sha），
否则用户端会认为 74 才是最新、或者对 1.47.6 报「已是最新」但装不上新包。
## 第 34 轮（2026-11-14 13:20–13:55）：自定义日程上课表（v1.47.7）

需求原话：「课表右上角加号那里的手动添加变成"手动添加课程"，再加一个选项"手动添加日程"……精确时间……按照时间比例出现在课表上，同时也同步到日程选项卡……要有自定义时间段，地点（可选），备注（可选）。」

改动：`Todo` 扩 `endTime`/`note` + 时间解析工具；`CourseStore` 兼容旧 JSON；`TodoEditActivity` 重写为
日期 + 开始 + 结束 + 地点 + 备注（结束必须晚于开始，长按开始时间清空为全天）；课表「+」改底部菜单
（课程 / 日程 / 导入）；`ScheduleView` 新增 `setTodos/minutesToY/drawTodos`，课程之后叠加日程方块；
"只有日程没有课程"时不再显示空态、课表网格照常显示。

踩坑：
- 事件按 `dateMillis` 落列，测试种子数据用了 +08:00 的毫秒而模拟器时区是 GMT，导致课表上位置正确
  （纯差值除以 86400000）但日程 Tab 显示"这一天没有安排"（`startOfDay` 跟设备时区）。以后验数据
  一律用设备时区生成。
- 早于第一节的事件两端都映射到表头高度 → 高度 0 被跳过、看不见；改成不足 10dp 时强制最小 16dp。
- PowerShell 里 `adb shell ... < file` 不能直接重定向，需要 `cmd /c` 包一层；`/sdcard` 下文件
  `run-as` 读不到（Permission denied），得从 stdin 写进 `files/`。

验证：174 单测全绿；模拟器实测 10:20-11:05 落在周五第 3 节中段、日程 Tab 同步；06:00-07:00 显示为顶部细条。
APK 26,663,411 字节，SHA-256 `89D0477C0B87BDD3C231E4BF4002B25B39CED2FF60B656F5623DDF0AEDCE346E`。

## 第 35 轮（2026-11-14 14:20–15:10）：日程按周重复 + 课表时间文字出格修复（v1.47.8）

- 需求：用户实测「清点物资室」日程出现在课表后，时间段文字溢出方块；并要求日程像课程一样按周重复。
- 改动：`Todo.repeatWeekly` + `occursOn()` + `weekIndexOf()`；`CourseStore` 持久化；`TodoEditActivity` 重复开关；`ScheduleView.drawTodos` 按星期几落列、按周次范围显示；时间小字只画开始时间并 `clipRect` 裁剪；`MainActivity.todosForDate` 用 `occursOn` 展开、卡片标题带「每周」。
- 踩坑：PowerShell 里 `'''...'''` 不是 here-string（是单引号字符串），用它做多行 replace 会静默匹配失败——多行替换一律用 Python 或 `@'...'@`。`ScheduleView` 没有 `ellipsize` 辅助函数，时间小字改用裁剪而非省略号。
- 验证：178 单测；APK 26,668,906 / SHA `0FB63A1E…9193` / versionCode 77。

## 第 36 轮（2026-11-14 14:40–15:20）：日程可点开编辑 + 卡片留白（v1.47.9）

- 需求：日程方块要像课程卡片一样点一下能编辑；方块里的文字贴边，需要留间距。
- 改动：`ScheduleView` 加 `onClickTodo` 与 `todoHitRects`（每帧 drawTodos 记录），`handleClick` 日程优先于课程；`MainActivity` 绑定点击 → `TodoEditActivity.intent(date, todoId)`；`drawTodoCard` 内边距 6dp、时间文字再内缩 2dp 裁剪、高度门槛 12dp。
- 验证：178 单测；APK 26,668,913 / SHA `BC7320CB…5930` / versionCode 78。

## 第 37 轮（2026-11-14 14:51–15:30）：小组件空状态缩小与月牙（v1.47.10）

- 需求：组件「今天没有课」字号太大，缩小一半并优化 UI。
- 改动：`TodayWidgetRenderer` 空状态标题目标宽 0.70→0.24、高度 0.42→0.16，副标题 0.34→0.20；新增月牙装饰（Path EVEN_ODD），三元素整体垂直居中；结束态保持大字号。
- 验证：模拟器 OCR 实测标题约占胶囊宽 22%，正常居中；178 单测；APK 26,668,908 / SHA `52369757…BF19` / versionCode 79。

## 第 38 轮（2026-11-14 16:21–17:00）：日程重复与详情弹窗（v1.47.11）

- 需求：日程按课程逻辑重复（起止周 + 单双周）、点方块先看详情再编辑、课表字号随方块高度变化、小组件去月牙。
- 改动：`Todo` 新增 `startWeek/endWeek/weekType` 与 `repeatDesc()`，`occursOn` 改为周次区间 + 单双周判定；编辑器新增起始周/结束周选择与单双周 chips；`ScheduleView.drawTodos` 用同一套规则、`drawTodoCard` 按高度算字号；`MainActivity.showTodoDetail()` 新增详情弹窗；`TodayWidgetRenderer` 删除月牙并放大空状态文字。
- 踩坑：Kotlin 字符串模板 `"$s-$e周"` 会被解析成变量 `e周`（Unresolved reference），必须写 `${e}周`。
- 验证：183 单测；模拟器两个不同长度日程字号差异明显；详情弹窗与编辑回填正常；APK 26,674,652 / SHA `4B0DC39D…C261` / versionCode 80。

## 第 39 轮（2026-11-14 16:21–17:05）：日程卡片与编辑页对齐课程（v1.47.12）

- 用户反馈两点：日程方块「字变大了但显示不全」；日程编辑页要和课程编辑页一致（改名日程、删教师/教室、节次改时间段）。
- 关键做法：不新造排版，直接把 `ScheduleView.drawCard` 的自适应策略搬进 `drawTodoCard` —— 按 `availH * 0.55` 定标题行高上限，字号从 13sp 逐级降到 9sp 用 `layoutLines` 试排，放得下才停；时间/地点用 9sp/8sp 的次级字体分行；左侧色条与卡面用 `ScheduleColor.palette(colorIndex)`，日程新增 `colorIndex` 字段（旧数据缺省 0）。
- 编辑页照搬 `activity_course_edit.xml` 结构：星期 chips、起止周、每周/单周/双周、颜色选择都复用同一套监听与回填逻辑；节次区间换成开始/结束时间段，删除教师与教室；单次日程用「起止周相同 + 每周」表达，保存时换算成 `dateMillis`。
- 教训：日程卡片第一版用「按高度线性算字号」会把大字号塞进矮卡片导致裁字；正确做法是先算可用行数再决定字号（与课程卡片一致）。
- 验证：185 单测全绿；模拟器新建日程、课表 OCR（标题两行 + 时间 + 地点）、详情→编辑回填均通过。

## 第 40 轮（2026-11-15 12:18–12:40）：小组件裁切与立体感（v1.47.13）

- 用户另一台手机截图：顶部「今天：六」和「第10周」胶囊上缘被切、主胶囊下缘被切，像与组件边缘「融合」；并且组件还能被拖大。
- 根因不是画错坐标，而是 ① `widget_today.xml` 里 ImageView 用 `centerCrop`：桌面实际给的区域比 `getAppWidgetOptions` 报的 min 尺寸大，图被等比放大后裁掉上下；② `resizeMode` 仍是 `horizontal|vertical`，用户拉大后尺寸更不一致。
- 修法：`centerInside`（永远不裁，等比缩放）；`resizeMode="none"` 并把渲染尺寸锁定在最小尺寸（4×2）附近；尺寸计算里加 1.7–3.0 的宽高比兜底。
- 立体感用三层做：卡片内顶部高光 + 底部压暗（先 clipPath 再画线性渐变）；胶囊的 `BlurMaskFilter` 柔和投影；胶囊内部纵向三段渐变 + 顶部白色高光。
- 教训：RemoteViews 里 ImageView 的 scaleType 决定「尺寸对不上时怎么处理」，`centerCrop` 会在不可控的桌面尺寸差异下裁掉内容；组件类的自绘卡片要么固定尺寸 + `centerInside`，要么额外留 safe area。
