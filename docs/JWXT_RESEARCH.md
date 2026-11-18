# 广外教务系统接入调研（WebView 登录 → 自动抓课表）

| 项 | 值 |
| --- | --- |
| 文档 | `docs/JWXT_RESEARCH.md` |
| 目标 | 为「课格 ClassGrid」新增第 4 种导入方式：内嵌 WebView 打开广外教务 → 用户自己登录 → 进入课表页 → App 自动抓取解析入库 |
| 代码基线 | `classgrid-source-v1.9.0/classgrid-source`（实际内容 = v1.30.0，versionName 1.30.0 / versionCode 45，minSdk 24 / targetSdk 34） |
| 调研方式 | `curl` 直连实测（异地公网 IP，无广外账号）+ 公开源码/文档取证 |
| 日期 | 2026-11-03 |
| 证据等级 | **【已证实】** = 本次实测或有公开源码/官方文档直接支撑；**【未证实/推测】** = 合理推断，必须真机+真实账号复核 |

> 全文关键结论都附来源 URL。**能实测的都实测了**：域名、证书、HTTP 状态码、404 对照、登录页 DOM、CAS 跳转参数都已抓取；**登录之后的页面无法实测**（没有账号），这部分一律标【未证实/推测】，并归入第 9 章的真机验证清单。

---

## 0. 结论速览（TL;DR）

| # | 结论 | 证据等级 |
| --- | --- | --- |
| 1 | 广外本科教务是**湖南强智科技（Qiangzhi）新一代 jsxsd 系统**，不是正方 jwglxt、不是 URP。登录页页脚「Copyright © 2020.湖南强智科技发展有限公司」。 | 【已证实】 |
| 2 | 系统入口 `https://jwxt.gdufs.edu.cn/`（旧域名 `jxgl.gdufs.edu.cn` 已不可用）。**全站 HTTPS，公网可直接打开登录页**。注意：首页是**强智本地账号登录**（账号/密码 + 微信扫一扫 + USBKey），**不是**统一身份认证页。 | 【已证实】 |
| 3 | 官方「统一身份认证」入口是 `https://jwxt.gdufs.edu.cn/jsxsd/sso.jsp`（教务处官网/学校首页「教务系统」都链到它）→ 302 到**金智 Wisedu CAS** `https://authserver.gdufs.edu.cn/authserver/login?service=...`，**有滑块拼图验证码**，还有手机验证码登录与多因子 reAuth 入口 → **App 绝不能做自动登录**，必须用户手动登录。**建议 App 直接加载 sso.jsp 而非首页**（CAS 是官方主入口，必然可用）。 | 【已证实】 |
| 4 | 课表页是**服务端渲染的 HTML 大表格**（非 JSON 接口）：`https://jwxt.gdufs.edu.cn/jsxsd/xskb/xskb_list.do?Ves632DSdyV=NEW_XSD_PYGL`，切换学期用 POST 的 `xnxq01id` 参数。 | URL 存在性【已证实】（404 对照实验）；登录后 DOM 结构【未证实】 |
| 5 | 抓取方式推荐**方案 A：WebView 注入 JS 读 DOM**（用户登录后点「导入」，JS 遍历 iframe 抓 `#kbtable .kbcontent` 的 `<font title=...>` 文本）；**方案 B（Cookie 转原生请求）作为可选增强**；**方案 C（拦 XHR）不适用**，因为课表是整页 HTML，没有 XHR/JSON 可拦。 | 推荐结论 |
| 6 | **失败降级**：解析不到课程 → 弹「改用截图识别」直接回落到现有 OCR 链路（`ImportActivity` 已有完整 OCR 队列）。 | 设计 |
| 7 | 公网登录页与 CAS 页 HTTP 200 可达，但「校外能否真正登录并取到课表」【未证实】——校方多处文档写明校外访问教务需 WebVPN，必须真机验证。 | 【未证实】 |
| 8 | 现仓库**没有任何 WebView / CookieManager 代码**，`AndroidManifest.xml` 也**没有 INTERNET 权限** → 新功能要新增权限 + 全新 Activity，无第三方依赖（`android.webkit.*` 原生即可）。 | 【已证实】 |
| 9 | WebView 兼容性**不是拦路虎**：登录页确有 UA 门禁，但源码只拦 `IE5~IE10` 与字面量 `chrome69.0`，现代 WebView（`Chrome/1xx`）不会被拦；USBKey 登录走 `http://127.0.0.1:1300` 本地中间件、仅用户主动点击时触发，与普通账号密码登录无关。 | 【已证实】读登录页 JS |

**一句话推荐**：做一个 `WebViewImportActivity`，**首选 URL 加载 `https://jwxt.gdufs.edu.cn/jsxsd/sso.jsp`**（官方统一身份认证入口；备用 `https://jwxt.gdufs.edu.cn/` 本地账号登录），让用户自己完成「账号密码 + 滑块验证码」登录；App 只做三件事——①把 WebView 原生回退键/进度条/错误页处理好；②在用户点到「课表查询」页面后注入 JS 抓表格文本；③解析成功跳现有 `OcrResultActivity` 让用户确认，解析失败提示「改用截图识别」。

---

## 1. 调研目标与边界

**要回答的问题**
1. 广外教务真实地址 + 认证方式 + 是否要校园网/VPN + 是否有验证码；
2. 厂商/版本 + 课表页 URL + 页面结构（HTML 表格 or JSON）；
3. 现成可抄的实现（GitHub / WakeUp / 小爱课程表）；
4. 三种技术方案的可行性与风险，给出推荐 + 失败降级；
5. 哪些点只能真机+真实账号验证。

**不在本文档范围**：不登录、不抓取任何真实数据、不做自动登录/绕过验证码；不改任何代码（本任务只写 `docs/`）。

**一个重要的现实前提**（来自 `docs/DEVLOG.md`）：本项目**没有真机也没有模拟器**（`adb devices` 为空）。所以本文档的所有「验证」都是静态的 + 网络探测，第 9 章的清单必须由有真机的人执行。

---

## 2. 广外教务系统画像

### 2.1 厂商与版本【已证实】

| 证据 | 内容 |
| --- | --- |
| 登录页页脚 | `Copyright © 2020.湖南强智科技发展有限公司` → **强智科技**，不是正方（正方页脚是「正方软件」且 URL 常含 `jwglxt`） |
| 页面文案 | 「湖南强智科技发展有限公司（www.qzdatasoft.com）…强智新一代综合教学…」 |
| 前端指纹 | layui + jQuery：`/assets_newL/plugin/layui/layui.all.js`、`/framework/commonJs/jquery.min.js`、`/js/usbkey.js`；背景图 `/Logon.do?method=syzpload&lxmc=2` |
| 路由指纹 | 新版用 `.htmlx` 后缀（如 `/findmm_new.htmlx`、`/jsxsd/framework/xsMainV_new.htmlx`）；老版强智是 `.jsp`/`.do` |
| 数据接口指纹 | `/jsxsd/kscj/cjcx_list` 直接返回 JSON `{code,data,msg}` → **新版强智的查询接口确实是 JSON**，但**课表页仍是 HTML**（见 §5） |

来源：<https://jwxt.gdufs.edu.cn/>（登录页）、<https://github.com/Lucifer-Jimmy/gdufs-jwxt-next-origin>（广外学生自建的「增强版广外教务」反向代理，源码里有全部真实端点，是本次最硬的第三方证据）。

> 判厂商的经验规则（WakeUp 官方文档也这么写）：**看登录页页脚公司名**。强智 = 「湖南强智科技发展有限公司」；正方 = 「正方软件股份有限公司」。
> 来源：<https://www.wakeup.fun/doc/import_from_eas.html>

### 2.2 域名、证书与可达性【已证实】

| 项 | 实测结果 |
| --- | --- |
| 主入口 | `https://jwxt.gdufs.edu.cn/` → **HTTP 200**，title=登录 |
| HTTP/HTTPS | `http://jwxt.gdufs.edu.cn/` → **302 跳 https**；响应头 `strict-transport-security: max-age=15768000`（HSTS 半年） |
| 证书 | `CN=*.gdufs.edu.cn, O=广东外语外贸大学`，签发链 `cnTrus OV SSL CA → USERTrust RSA Certification Authority → AAA Certificate Services`；有效期（本次实测）jwxt `2026-04-22 ~ 2026-11-06`，authserver `2026-10-23 ~ 2027-04-22` |
| 证书含义 | **公共可信 CA、非自签** → Android WebView 不会弹证书错误，**不需要** `onReceivedSslError` 里 `proceed`（写放行反而是安全隐患） |
| 旧域名 | `jxgl.gdufs.edu.cn` 解析到 `202.116.196.71` 但 443 连接直接关闭 → 旧系统已下线（与 `Systina12/GDUFS-Toolkit` README「项目暂时不可用，教务更新了」一致） |
| 公网可达 | 登录页、CAS 页从异地公网均 200 可达；**没有任何 campus-only 拦截**作用于这两个页面 |

> ⚠️ 注意：证书有效期约半年（本次实测 jwxt 证书距到期仅数天），说明学校**频繁换证**。**不要 pin 证书/公钥**，否则哪天续证你的 App 就全白屏。

### 2.3 认证方式：统一身份认证 CAS【已证实】

**广外有两条登录路径（别混）**

| 入口 | 页面 | 说明 | 等级 |
| --- | --- | --- | --- |
| A. 官方统一身份认证 | `https://jwxt.gdufs.edu.cn/jsxsd/sso.jsp` | 学校首页「服务导航 → 教务系统」、教务部官网「教务系统」都指向它；302 到金智 CAS，账号=学号，密码=智慧广外密码（初始为身份证后六位） | 【已证实】 |
| B. 强智本地登录页 | `https://jwxt.gdufs.edu.cn/` | title=登录；表单 `POST /Logon.do?method=logon`（字段 `userAccount/userPassword/encoded/loginMethod/userlanguage`）；另有「使用微信"扫一扫"登录」「忘记密码 `/findmm_new.htmlx`」「USBKey」；页面里**没有**任何 `sso.jsp`/`authserver`/「统一身份认证」字样；有 `切换验证码` JS（失败后可能出验证码） | 【已证实】页面结构；**本地账号是否仍有效【未证实】** |

→ **App 首选 A（sso.jsp）**：它是官方主入口、必然可用，且和「智慧广外」同一套账号密码，学生不用记第二套密码。

CAS 完整链路（A 路径）：

```
用户打开 https://jwxt.gdufs.edu.cn/jsxsd/sso.jsp
GET https://jwxt.gdufs.edu.cn/jsxsd/sso.jsp
  → 200，响应体是一段 JS：
    window.location.href='https://authserver.gdufs.edu.cn/authserver/login
      ?service=https%3A%2F%2Fjwxt.gdufs.edu.cn%2Fjsxsd%2Fsso.jsp'
  → 金智统一身份认证平台（title=统一身份认证平台）
     账号密码登录：input#username + input#pwdEncryptSalt(AES key) + input#execution
     表单字段：username / password(AES) / captcha / _eventId=submit
              / cllt=userNameLogin / dllt=generalLogin / lt / execution
  → 认证成功 → 带 ticket 回跳 /jsxsd/sso.jsp
  → 下发 Cookie：bzb_jsxsd=...; Path=/jsxsd; HttpOnly   +   SERVERID_jsxsd=...
  → 进入 /jsxsd/framework/xsMainV_new.htmlx?t1=1
```

- **CAS 服务端是金智（Wisedu）authserver**：`/authserver/login`、隐藏域 `_eventId/lt/execution/cllt/dllt`、资源版本号 `v=20260724.140937`、滑块脚本 `/authserver/customWhites/static/web/js/longbow.slidercaptchas.js`（文案「向右滑动填充拼图」）——这些是金智 CAS 的标准特征。
- 同一套 CAS 还保护其他系统，例如教评 `https://jxpg.gdufs.edu.cn/`（页面同样出现「统一身份认证平台 / 向右滑动填充拼图」）。
- 教务部官网 `https://jwb.gdufs.edu.cn/` 的「教务系统」链接 = `https://jwxt.gdufs.edu.cn/jsxsd/sso.jsp`，所以**这条 CAS 链路就是官方推荐入口**。
- 密码找回/绑定手机：<https://yx.gdufs.edu.cn/info/1035/1368.htm>（统一身份认证个人中心 `https://authserver.gdufs.edu.cn/authserver/index.do`）。

**对 App 的直接含义（重要）**
1. **不能做自动登录**：CAS 有滑块验证码 + 手机验证码登录 + 多因子 reAuth，自动化登录等于对抗风控，既违规也没必要。
   - 多因子证据：广外自建代理脚本里存在 `https://authserver.gdufs.edu.cn/authserver/reAuthCheck/reAuthLoginView.do?isMultifactor=true&service=https://jwxt.gdufs.edu.cn/sso.jsp`（源码：`prototypes/auth_prototype.py`）→ 某些情况下登录会要求二次认证。
2. **同站 Cookie 没问题**：`jwxt.gdufs.edu.cn` 与 `authserver.gdufs.edu.cn` 同属 `*.gdufs.edu.cn` 注册域，CAS 回跳下发的 Cookie 在 WebView 里算**同站**，不会被 SameSite 拦（WebView 的第三方 Cookie 限制不影响本场景）。
3. **密码只做 AES 混淆**（`randomString(64)+password` 用 `pwdEncryptSalt` AES-CBC 加密），但**我们根本不需要碰它**——用户自己在 WebView 里输入，App 不接触明文密码，这是隐私上最干净的方案。

### 2.4 验证码 / 反自动化现状

| 位置 | 现象 | 等级 |
| --- | --- | --- |
| 统一身份认证 | **滑块拼图验证码**（`longbow.slidercaptchas.js`，页面文案「向右滑动填充拼图」），另有「使用手机验证码登录」Tab | 【已证实】 |
| 强智本地登录壳 | 页面**没有渲染**验证码图片，但代码里有 `/verifycode.servlet` + `SafeCodeImg`/`ReShowCode()` 刷新逻辑 → 是否需要验证码由服务端下发的模板决定，**失败重试后可能出验证码** | 【未证实触发条件】 |
| 强智登录页的浏览器门禁 | JS 硬编码只拦 `IE5~IE10` 和字面量 `chrome69.0`（`if(browser=='IE10.0'||…||browser=='chrome69.0')` 才提示「当前浏览器版本不适用」）→ 现代 WebView（UA 里 `Chrome/1xx`）**不会**被拦 | 【已证实】读登录页 JS |
| USBKey 登录 | 走本地中间件 `http://127.0.0.1:1300/api/`（`/js/usbkey.js`，同步 XHR），且只在用户点「USBKey 登录」时触发；普通学生账号密码登录**不经过**它 | 【已证实】读 JS；对 App 无影响 |
| 旧系统（jxgl） | 老强智是「账号密码+图片验证码」（`/verifycode.servlet` + `/xk/LoginToXkLdap`），已被新系统取代 | 【已证实，已失效】 |
| 二次认证 | 存在 `reAuthLoginView.do?isMultifactor=true` 入口 | 【已证实入口存在 / 触发条件未证实】 |

---

## 3. 关键 URL 清单

| 用途 | URL | 等级 |
| --- | --- | --- |
| 强智本地登录页 | `https://jwxt.gdufs.edu.cn/` | 【已证实】200；纯强智本地账号表单，无 CAS 字样；是否仍可用【未证实】 |
| CAS 入口（**App 推荐 URL**） | `https://jwxt.gdufs.edu.cn/jsxsd/sso.jsp` | 【已证实】200，JS 跳 authserver |
| 统一身份认证登录 | `https://authserver.gdufs.edu.cn/authserver/login?service=https://jwxt.gdufs.edu.cn/jsxsd/sso.jsp` | 【已证实】200 |
| 认证后主页框架 | `https://jwxt.gdufs.edu.cn/jsxsd/framework/xsMainV_new.htmlx?t1=1` | 【已证实】路径存在（第三方源码 + 本次 200），登录后内容【未证实】 |
| **学生课表查询** | `https://jwxt.gdufs.edu.cn/jsxsd/xskb/xskb_list.do?Ves632DSdyV=NEW_XSD_PYGL` | 路径存在【已证实】（404 对照见附录 A）；页面结构【未证实】 |
| 成绩查询（JSON） | `https://jwxt.gdufs.edu.cn/jsxsd/kscj/cjcx_list` | 【已证实】路径存在；返回 JSON `{code,data,msg}`（第三方源码） |
| 平时成绩明细 | `https://jwxt.gdufs.edu.cn/jsxsd/kscj/pscj_list.do` | 【已证实】（第三方源码） |
| 强智移动端 API | `https://jwxt.gdufs.edu.cn/app.do?method=authUser` / `getKbcxAzc` | 【未证实】见 §5.3 |
| 微信登录跳转 | 登录页「使用微信"扫一扫"登录」 | 【已证实】按钮存在 |
| WebVPN | `https://webvpn.gdufs.edu.cn/`（→ `/users/sign_in`） | 【已证实】公网可达 |

**校外网络说明**：校方文档明确「WebVPN 适合在校园网范围外通过浏览器访问校内专用业务系统（如：财务系统、教务系统…）」（<https://etr.gdufs.edu.cn/info/1303/5144.htm>），学校首页也写「校外请使用 VPN 或 WebVPN」（<https://www.gdufs.edu.cn/info/1350/58072.htm>）。但本次实测**教务登录页与 CAS 页从公网直接 200 可达**，说明入口未做 IP 白名单；**登录/取数据阶段是否被拦【未证实】**，必须真机测。

**⚠️ 常见误配域名（本次逐个实测，别写进代码）**：广外教务是**强智**（`jwxt`），不是正方，所以下面这些「看着像」的域名要么不存在、要么不是教务系统：

| 候选域名 | 实测结果 | 结论 |
| --- | --- | --- |
| `https://jwgl.gdufs.edu.cn/`（正方风格） | DNS 无解析（curl `000`，无 remote_ip） | ❌ 不存在，别用 |
| `http://jwgl.gdufs.edu.cn/` | 同上 | ❌ 不存在 |
| `https://jwc.gdufs.edu.cn/` | DNS 无解析（`000`） | ❌ 不存在；教务处正式域名是 `jwb.gdufs.edu.cn`（200，`202.116.192.8`） |
| `https://my.gdufs.edu.cn/` | DNS 有解析（`202.116.193.34`），但公网 HTTPS 连接失败（`000`） | ⚠️ 疑似校园网/门户内网服务，公网不可用，不能当入口 |
| `https://portal.gdufs.edu.cn/` | DNS 有解析（`202.116.193.30`），公网连接失败（`000`） | ⚠️ 同上 |
| `https://ehall.gdufs.edu.cn/` | DNS 无解析（`000`） | ❌ 不存在 |
| **`https://jwxt.gdufs.edu.cn/`** | **200** | ✅ 强智登录页 |
| **`https://jwxt.gdufs.edu.cn/jsxsd/sso.jsp`** | **200（JS 跳 CAS）** | ✅ **App 推荐入口** |
| `https://jwb.gdufs.edu.cn/` | 200 | ✅ 教务部官网（只用来抄链接，不是业务系统） |

> 复现：`curl -s -o /dev/null -w "%{http_code} %{remote_ip}\n" https://<host>/`，`000` + 空 IP = DNS 解析失败。

---

## 4. 登录链路细节（为什么 WebView 方案天然可行）

1. 强智本地登录页的「登录」按钮走 `loginajax()`：先 POST `/Logon.do?method=logon&flag=sess` 拿 `scode#sxh`，再用 `userAccount+"%%%"+userPassword+"%%%"` 与 scode/sxh 逐位拼接生成 `encoded` 隐藏域，最后 `form.submit()`。
   → 说明登录**完全在浏览器 JS 里完成**，没有 ActiveX/客户端控件，**WebView 能跑**。
2. **B 路径（强智本地账号）也能在 WebView 里跑**，但它是学校自己的 LA/LDAP 账号体系，是否仍有效、和智慧广外是否同一套密码都【未证实】→ UX 上引导用户走 A（统一身份认证），B 作为「打不开时」的备用入口。
3. 全链路都是 `https://*.gdufs.edu.cn`，无混合内容、无自签证书、无 WebSocket → WebView 默认配置即可。
4. **浏览器门禁：存在，但对现代 WebView 无效**（已读源码，不是推测）。强智登录页顶部写「推荐使用谷歌 Chrome 浏览器以及360极速模式，推荐在分辨率 1920*1080 下使用最佳」，并且真有 UA 分支：
   ```js
   var browser = getBrowser(); // UA 正则取 "内核+主版本"，如 "chrome120.0"
   if(browser=='IE10.0'||browser=='IE9.0'||browser=='IE8.0'||browser=='IE7.0'
      ||browser=='IE6.0'||browser=='IE5.0'||browser=='chrome69.0'){
       $("#showMsg").text("当前浏览器版本不适用，请使用推荐浏览器");
       document.getElementById("btn-login").innerHTML="查看推荐浏览器";
       document.getElementById("btn-login").onclick = myAlert;
   }
   ```
   → 只命中 **IE5~IE10** 和字面量 **`chrome69.0`**。Android WebView 的 UA 形如 `…; wv) AppleWebKit/537.36 … Chrome/120.0.0.0 Mobile Safari/537.36`，`getBrowser()` 得到 `chrome120.0`，**不会**被拦。**结论：不必强行改 UA**；真机若发现白屏，再降级为「覆盖成桌面 Chrome UA」这一保险丝（成本一行代码）。
5. **USBKey（加密狗）路径与普通学生无关，但要防 JS 报错**：`/js/usbkey.js` 走本地中间件 `baseUrl = "http://127.0.0.1:1300/api/"` 的**同步 XHR**，且只在用户主动点「USBKey 登录」→ `validatedogLogin(dogpwd)` → `checkDog()` 时才执行（Chrome 分支靠浏览器扩展 `postMessage("SNTL_FROM_HOST")`，IE 分支靠 `ActiveXObject`）。页面加载时**不**自动调用，所以不影响账号密码登录；万一用户误点，「接口调用失败,请检查中间件是否安装或启动」是它自带的 catch，不会把 App 打崩。
6. **微信扫码登录在 WebView 内基本不可用**（微信 OAuth 限制），文案上要引导「请用账号密码登录」。

---

## 5. 课表页与数据结构

### 5.1 结论【已证实路径存在，未证实 DOM】

- 课表页 URL：`https://jwxt.gdufs.edu.cn/jsxsd/xskb/xskb_list.do?Ves632DSdyV=NEW_XSD_PYGL`
- 返回 **服务端渲染 HTML**（整页 `<table id="kbtable">`），**不是 JSON**。
- 切换学期：**POST** 同一 URL，表单 `bodycj0701id=&zc=&demo=&xnxq01id=2026-2027-1&sfFD=1`（`xnxq01id` = 「学年-学年+1-学期号」）。
- 证据链（三重）：
  1. 旧广外系统（`jxgl.gdufs.edu.cn/jsxsd`）的完整实现：`core/course.py` 里 `query_url = url+'/xskb/xskb_list.do?Ves632DSdyV=NEW_XSD_PYGL'`，POST 上述参数，然后 `BeautifulSoup` 解析 `<div>`（<https://github.com/Systina12/GDUFS-Toolkit>，2025-10 仍在维护，README 说教务换成新系统后不可用，但接口形态是同一套强智 jsxsd）。
  2. 强智课表接口的通用文档（北林 `newjwxt.bjfu.edu.cn` 同构）：<https://www.jet-lab.site/blogs/qiangzhi>
  3. 本次实测：该路径在**未登录**时 200 + 50KB 登录壳页；而随便编一个 `/nonexistent-zz9` 是 **404**（727 字节）→ 说明服务端真的区分「不存在」和「被登录拦截」，**该课表路径真实存在于 `jwxt` 上**（附录 A）。

### 5.2 页面结构（强智 `kbcontent` 家族）

**A. 北林/HEU 版本（小爱课程表适配实录，2020，结构最清晰）**
来源：<https://www.cnblogs.com/hed10ne/p/miui_schedule_document.html>，代码：<https://github.com/Holit/HEU_edusys_miui>

```html
<table id="kbtable" class="Nsb_r_list Nsb_table" border="1" cellspacing="0" cellpadding="0">
  <!-- 5 大节 × 7 天 = 35 个单元格 div -->
  <div id="5180478CC0C746CC94534AF06163E808-3-2" class="kbcontent">
    线性代数与解析几何A<br>
    <font title="老师">廉春波</font><br>
    <font title="周次">4-18(周)</font><br>
    <font title="节次">[0102节]</font><br>
    <font title="教室">21B 502中</font><br>
  </div>
</table>
```

解析规则（小爱官方适配模板）：
- 选择器 `#kbtable .kbcontent`；
- div 的 `id` 末两段 = `{星期}-{大节}`（`-3-2` = 周三第 2 大节）；
- 空单元格：`div.children.length == 1`（只有一个空白文本节点）；
- 课程名 = 第一个文本节点；教师/周次/节次/教室 = 按 `<font title="...">` 取。

**B. 广外旧系统版本（2025 实测可用代码，字段名略有差异 —— 最贴近广外）**
来源：<https://github.com/Systina12/GDUFS-Toolkit/blob/master/core/course.py>

```python
divs = soup.find_all('div', id=re.compile(r'.*-2$'))     # 只取 id 以 -2 结尾的 div
weekday = re.search(r'-([1-7])-2$', div_id)              # 从 id 取星期
pattern = r'<font\s+title="(.*?)">(.*?)</font>'         # 取所有 font
# font title 集合： "周次(节次)"（周次和节次合并成一个 font！）、"老师"、"教室"
# 周次： re.search(r'([\d\-]+)\(周\)', text)   → "1-16"
# 节次： re.search(r'\[([\d\-]+)节]', text)    → "01-02" → [1,2]
# 另外：红色 font「未安排时间课程：」后面的文本 = 没有排进表格的课
```

> ⚠️ **两份结构不完全一致**（HEU：`-{星期}-{大节}` 且 `周次`/`节次` 是两个 font；广外 2025：id 形如 `*-{星期}-2` 且 `周次(节次)` 合并成一个 font）。**结论：解析器不能硬编码 id 语义，也不能假设 font title 一定叫「周次」，必须先采集页面上实际的 `font[title]` 名字集合再匹配**（第 8.4 节脚本就是这么写的：按 title 关键字模糊匹配 周/节次/老师/教室）。
> 广外的 `-2` 到底是「大节」还是别的，只能真机看一眼 HTML 才能定论 → 第 9 章第 5 项。

### 5.3 强智移动端 JSON API（【未证实】别当主方案）

通用强智移动接口（其他学校实测）：
- `POST /app.do?method=authUser&xh=<学号>&pwd=<密码>` → `{"success":true,"token":"eyJ..."}`
- `GET /app.do?method=getKbcxAzc&xh=<学号>&xnxqid=2024-2025-1&zc=1`（Header 带 token）
- 字段：`kcmc` 课程名、`jsxm` 教师、`jsmc` 教室、`kkzc` 周次（`"1"`/`"a-b"`/`"a,b,c"`/`"a-b,c-d"`）、`kcsj` 课程时间（`"10102"` = 周 1 第 01-02 节）、`kssj`/`jssj` 起止时间、`sjbz` 单双周（1=单，2=双）

**广外情况【未证实】**：`https://jwxt.gdufs.edu.cn/app.do?method=...` 无论 method 是否存在都返回 200 + 860 字节「用户没有登录，请重新登录！」（附录 A）→ `/app.do` 是**兜底 servlet，任何 method 都同一响应**，所以**无法据此判断广外的移动 API 存在**。另外该接口要**明文密码**，与「不接触密码」原则冲突，**不建议采用**。

### 5.4 广外的节次与时间（重要！与 ClassGrid 模型不匹配）

广外教务课表是 **15 小节/天**（旧系统工具里的节次时间表，`pages/course.py`）：

| 节 | 时间 | 节 | 时间 | 节 | 时间 |
| --- | --- | --- | --- | --- | --- |
| 1 | 8:30-9:10 | 6 | 12:30-13:10 | 11 | 16:20-17:00 |
| 2 | 9:10-9:50 | 7 | 13:10-13:50 | 12 | 18:30-19:10 |
| 3 | 10:10-10:50 | 8 | 14:00-14:40 | 13 | 19:10-19:50 |
| 4 | 10:50-11:30 | 9 | 14:40-15:20 | 14 | 20:00-20:40 |
| 5 | 11:35-12:15 | 10 | 15:40-16:20 | 15 | 20:40-21:20 |

（以上为旧系统工具硬编码，新系统是否相同【未证实】；ClassGrid 默认 `totalSections = 12`，见 `ScheduleView.kt:86`）
→ **解析后必须做节次归一化**：把广外 15 小节压到 ClassGrid 的 12 节，或按「大节」映射（建议实现为可配置映射表 + 首次导入时提示用户在校对页确认，别静默猜）。

---

## 6. 现成参考实现

| 项目 | 关系 | 可抄的点 | 链接 |
| --- | --- | --- | --- |
| **Lucifer-Jimmy/gdufs-jwxt-next-origin** | **广外专用**（学生自建增强版，Python/Django） | 全套真实端点：CAS 登录表单字段、AES 加盐、`ticket1` 回跳、`bzb_jsxsd` Cookie、`xsMainV_new.htmlx?t1=1` 主页、成绩 JSON 接口 | <https://github.com/Lucifer-Jimmy/gdufs-jwxt-next-origin> |
| **Systina12/GDUFS-Toolkit** | **广外专用**（PyQt/NiceGUI 工具箱，含课表查询） | 课表 URL + POST 参数 + `BeautifulSoup` 解析 `kbcontent` 的完整代码、15 小节时间表 | <https://github.com/Systina12/GDUFS-Toolkit> |
| Holit/HEU_edusys_miui | 小爱课程表「强智」适配 | `#kbtable .kbcontent` 的 DOM 结构与解析思路、`scheduleHtmlProvider` 内同步 XHR 取 HTML 的做法 | <https://github.com/Holit/HEU_edusys_miui> |
| 小爱课程表适配文档（HEU 实录） | 文档 | 「provider 递归遍历 iframe/frame 取 contentDocument」的官方模板写法 | <https://www.cnblogs.com/hed10ne/p/miui_schedule_document.html> |
| WakeUp 课程表官方文档 | 竞品做法 | 判断教务类型的经验（看页脚公司名）；「导入页网址可自行修改，一边是内嵌网页，看到课程页面后点导入按钮」的交互模型；旧强智在 App 里会报「无法创建对象」，需 HTML 文件导入 | <https://www.wakeup.fun/doc/import_from_eas.html> |
| canliture/CrawlerCourseTable | 强智 Java 爬虫 | 原生 HTTP 抓课表的参数与解析 | <https://github.com/canliture/CrawlerCourseTable> |
| inannan423/classCrawl | 强智 | 同上（多校适配） | <https://github.com/inannan423/classCrawl> |
| Yorick-Ryu/NIIT_getCourse | 金智教务 | 金智（CAS/教务）取数思路 | <https://github.com/Yorick-Ryu/NIIT_getCourse> |

**WakeUp / 小爱 的 WebView 抓取模型（值得直接照搬）**
1. App 内嵌一个可编辑 URL 的 WebView，用户自己登录并把页面点到课表；
2. App 注入一段 JS，在**当前同源页面**内同步 XHR `GET /jsxsd/xskb/xskb_list.do?...` 拿 `responseText`（登录态自动带上），或直接读 DOM；
3. 把 HTML/文本交给解析器（纯正则/字符串处理，不依赖在页面里跑复杂库）；
4. 解析结果落到「确认页」→ 保存。
5. 判「用户已到课表页」：`document` 里出现 `#kbtable`/`.kbcontent`，或 URL 含 `xskb`。**别只靠 URL**，强智新版主框架是 `xsMainV_new.htmlx` + iframe，URL 可能一直是主框架地址。

---

## 7. 方案对比（A / B / C）与推荐

### 7.1 三个方案是什么

| 方案 | 做法 | 依赖 |
| --- | --- | --- |
| **A. 注入 JS 读 DOM** | WebView 里用户手动登录并进入课表页后，`evaluateJavascript()` 注入脚本，遍历 `document` + 所有同源 iframe 的 `contentDocument`，抓 `#kbtable .kbcontent` 的 innerHTML/文本，回传 JSON | 无（`android.webkit.*`） |
| **B. Cookie 转原生请求** | `CookieManager.getInstance().getCookie("https://jwxt.gdufs.edu.cn")` 拿 `bzb_jsxsd`+`SERVERID_jsxsd`，用 `HttpURLConnection`/OkHttp `POST /jsxsd/xskb/xskb_list.do` 拿整页 HTML，再在 App 内用解析器处理 | 无（HttpURLConnection 即可）；如果想用 OkHttp 需加依赖（现工程未用） |
| **C. 拦截 XHR 响应** | `WebViewClient.shouldInterceptRequest()` 监听课表接口的响应体 | 无 |

### 7.2 可行性 / 风险对比

| 维度 | A 注入读 DOM | B Cookie + 原生请求 | C 拦 XHR |
| --- | --- | --- | --- |
| 对广外的可行性 | **高**。课表是服务端渲染 HTML，DOM 里就有全部字段；强智页面无复杂前端框架，`querySelectorAll` 直接可用 | **高**，但多一层风险：Cookie 可能被 `HttpOnly` + 服务端 session 绑定（本次实测 `bzb_jsxsd` 是 HttpOnly，JS 读不到，但 `CookieManager` 能读到；原生请求能否通过校验【未证实】） | **不适用**：课表没有 XHR/JSON 响应可拦（见 §5.1） |
| 关键风险 | iframe 跨域/嵌套层级（`xsMainV_new.htmlx` 很可能是 frameset，必须递归遍历）；DOM 结构随版本变（font title 名会变） | Cookie 需要人工从 `CookieManager` 拼 header；学期切换要 POST；session 失效/二次认证会 302 到 CAS，返回登录页 HTML（要识别并提示重新登录） | 无收益，白做 |
| 用户体验 | 用户看到的就是真实教务页面，点「导入」即出结果 | 用户看不到抓取过程，失败时难解释 | — |
| 隐私 | **App 不接触密码**，最干净 | 同样不接触密码，但把登录凭据（Cookie）搬进 App 进程做请求 | — |
| 工作量 | 小（1 个 Activity + 1 段 JS + 解析器 + 映射） | 中（+Cookie 拼接、请求、错误分类、会话失效处理） | 小但无用 |
| 版本脆弱性 | 中：解析器要「先探测结构再匹配」 | 中：同 A，且接口参数可能变 | — |

### 7.3 推荐：A 为主，B 作可选增强，C 放弃

**推荐路径（按优先级）**
1. **主方案 A**：WebView 手动登录 → 点「导入」→ 注入 JS 抓 DOM。
   - 注入脚本自带「结构探测」：先 `document.querySelectorAll('font[title]')` 统计 title 集合，再决定字段匹配策略；再把候选 HTML 整段回传，解析尽量放在 **Kotlin 侧**（好调试、好改、好写单测）。
2. **增强（P2，可选）**：A 拿到的 HTML 里如果发现学期选择参数，允许用户「导入其他学期」；实现上用 B 在同一 WebView 里 `POST`（`fetch`）更简单——**不建议**用原生 HTTP 重放 Cookie，除非真机验证过 session 校验。
3. **失败降级（必须有）**：脚本返回空/解析出 0 门课/用户没进入课表页 → 弹提示：
   「没能在页面上找到课表。可以：① 确认已进入『课表查询』页面后再点导入；② 改用『截图识别』导入（推荐）」
   点「截图识别」→ 直接 `startActivity(Intent(this, ImportActivity::class.java))` 并带 `EXTRA_START_PICK=true`（现有 `ImportActivity` 已有「从主界面截图识别进入时自动拉起选图」的开关，见 `ImportActivity.kt:26`）。
4. **永远不做的**：自动填账号密码、自动点登录、破解/打码滑块、模拟请求移动端 `authUser`（需明文密码）。

> 为什么不让 AI 直接猜方案 C：强智课表是「一次 GET 返回整页 HTML」，浏览器里不会发生课表 XHR；`shouldInterceptRequest` 只会在加载 HTML 时触发一次，拿到的就是同一份 HTML——**没有比 A 更省事的收益**。

---

## 8. 落地设计（ClassGrid 内，仅设计，不实现）

### 8.1 改动面（文件级清单，供实施 PR 参考）

| 文件 | 改动 |
| --- | --- |
| `app/src/main/AndroidManifest.xml` | 加 `<uses-permission android:name="android.permission.INTERNET"/>`（**调研时点没有**，这是 WebView 白屏的头号原因）；注册 `WebViewImportActivity`（目标 API 34 无需 `<queries>`，纯 HTTPS 也无需 cleartext 配置） |
| `app/src/main/res/layout/activity_import.xml` | 新增一张卡片 `cardWeb`（「从教务系统导入」），与现有 `cardScreenshot`/`cardJson`/`cardManual` 同级 |
| `app/src/main/java/com/classgrid/app/ImportActivity.kt` | `cardWeb` 点击 → `startActivity(Intent(this, WebViewImportActivity::class.java))` |
| **新增** `WebViewImportActivity.kt` | WebView 容器 + 顶部工具条（返回/刷新/「导入当前页面」）+ 进度条 + 错误页 + 底部说明 |
| **新增** `JwxtParser.kt` / `JwxtHtmlParser.kt` | 纯 Kotlin：HTML 字符串 → `List<Course>`（建议加 `src/test` 单测，用固定 HTML 样本） |
| 复用 `OcrResultActivity` | 解析成功 → `EXTRA_COURSES=CourseJson.encode(courses)`、`EXTRA_RAW_TEXT=抓到的纯文本`、`EXTRA_IMAGE_URI=""`，走同一个确认页 → 用户校对后入库 |
| 可选 `ImportActivity` 的降级入口 | 失败提示里跳回截图 OCR |

### 8.2 WebView 配置（最小可用）

```kotlin
val web = findViewById<WebView>(R.id.webView)
web.settings.apply {
    javaScriptEnabled = true            // 必须
    domStorageEnabled = true            // 强智/layui 用 localStorage
    databaseEnabled = true
    loadsImagesAutomatically = true
    // 不设置 mixedContentMode（全站 HTTPS，无需放行）
    // UA：先用默认，真机确认强智是否白屏；若白屏再覆盖成桌面 Chrome UA
}
// 入口 URL：优先统一身份认证（官方主入口）；加载失败再提供「用教务系统账号登录」切到首页
web.loadUrl("https://jwxt.gdufs.edu.cn/jsxsd/sso.jsp")
web.setBackgroundColor(Color.TRANSPARENT)
WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)  // chrome://inspect 调试注入脚本
web.webViewClient = object : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String) { updateToolbarState(url) }
    override fun onReceivedError(...) { showRetryPage() }   // 网络错误要有可重试 UI
}
CookieManager.getInstance().setAcceptCookie(true)
// 退出登录/清理：CookieManager.getInstance().removeAllCookies(null) + web.clearCache(true)
```

要点：
- **不要** `onReceivedSslError` 里 `handler.proceed()`（证书是公共 CA，放行只会降低安全性）；
- **尽量不用** `addJavascriptInterface`：注入结果用 `evaluateJavascript` 的 `ValueCallback<String>` 回传就够了。教务页面是第三方网页，一旦暴露 Java 对象，等于把攻击面交给它；
  如果为了「页面自己触发扫描」必须用桥（本仓实施中确实用了），**只暴露一个** `@JavascriptInterface fun onScan(json: String)` 这类纯数据入口，并在 Kotlin 侧校验：`json` 长度上限、必须是合法 JSON、课程字段类型/范围（`dayOfWeek in 1..7`、`startSection>=1`）——**绝不要**暴露任何通用能力（读文件、发 HTTP、执行任意 SQL、`Runtime.exec`）。
- 用户按返回键先 `web.canGoBack()`（`OnBackPressedDispatcher` 里处理，避免直接退出整个导入页）；
- 微信扫码登录：WebView 里点了会失败（微信 OAuth 限制在微信客户端内），提示文案写「请使用『统一身份认证』账号密码登录」。

### 8.3 如何判断「用户已进入课表页」

按成本从低到高，建议**两个条件同时满足**再允许点击「导入」（也可以让「导入」按钮一直可点，由脚本自己判断，体验更好）：

```kotlin
// 判定 1：URL 关键字（弱）
url.contains("/xskb/") || url.contains("xskb_list.do")
// 判定 2：DOM 探测（强）——注入一段轻量 JS
"(function(){function scan(d){try{if(d.querySelector('#kbtable, .kbcontent'))return true;}catch(e){}
 for(const f of d.querySelectorAll('iframe,frame')){try{const c=f.contentDocument;if(c&&scan(c))return true;}catch(e){}}return false;}
 return scan(document);})()"
```

强智新版主框架（`xsMainV_new.htmlx`）很可能是 **frameset/iframe 布局**（小爱官方 provider 模板专门递归 `iframe`/`frame` 的 `contentDocument` 就是这个原因）→ **所有 DOM 操作必须递归进 iframe**，且因为同源（都是 `jwxt.gdufs.edu.cn`），`contentDocument` 可直接访问，无需 postMessage。

### 8.4 注入脚本（可直接用的第一版）

```javascript
(function () {
  var out = { url: location.href, frames: [], cells: [], raw: '', probe: {} };

  // 1) 收集所有同源 document（含 iframe/frame 递归）
  function collectDocs(doc, acc, depth) {
    if (!doc || depth > 4) return;
    acc.push(doc);
    var frames = doc.querySelectorAll('iframe,frame');
    for (var i = 0; i < frames.length; i++) {
      try { collectDocs(frames[i].contentDocument, acc, depth + 1); } catch (e) {}
    }
  }
  var docs = []; collectDocs(document, docs, 0);
  out.frames = docs.map(function (d) { return d.URL || d.location.href; });

  // 2) 结构探测：页面上实际出现了哪些 font[title]
  var titles = {};
  docs.forEach(function (d) {
    d.querySelectorAll('font[title]').forEach(function (f) { titles[f.getAttribute('title')] = 1; });
  });
  out.probe.fontTitles = Object.keys(titles);

  // 3) 抓单元格：优先 #kbtable .kbcontent；退化为 .kbcontent；再退化为「含 font[title] 的 div」
  docs.forEach(function (d) {
    var cells = d.querySelectorAll('#kbtable .kbcontent, .kbcontent');
    if (!cells.length) cells = d.querySelectorAll('div:has(> font[title])');
    for (var i = 0; i < cells.length; i++) {
      var el = cells[i];
      if (el.children.length === 1 && !el.innerText.trim()) continue; // 空课
      var fields = {};
      el.querySelectorAll('font[title]').forEach(function (f) {
        fields[f.getAttribute('title')] = (f.textContent || '').trim();
      });
      out.cells.push({
        id: el.id || '',
        name: (el.innerText || '').split('\n')[0].trim(),
        text: (el.innerText || '').replace(/\s+/g, ' ').trim(),
        fields: fields
      });
    }
  });

  // 4) 兜底：整页 HTML（解析放 Kotlin 侧，方便单元测试与快速修 bug）
  out.raw = out.cells.length ? '' : (function () {
    var best = '';
    docs.forEach(function (d) { if (d.body && d.body.innerHTML.length > best.length) best = d.body.innerHTML; });
    return best.slice(0, 500000);
  })();

  return JSON.stringify(out);
})();
```

Kotlin 侧：`web.evaluateJavascript(script) { json -> ... }`，注意返回的是**JSON 编码后的字符串**（含外层引号与 `\n` 转义），要先 `JSONTokener(json).nextValue() as String` 再 `JSONObject(...)`。

### 8.5 解析规则（Kotlin 侧，需对真机 HTML 校准）

| 字段 | 正则/逻辑 | 说明 |
| --- | --- | --- |
| 课程名 | 单元格第一个非空文本行 | 也可能在 `<span>` 里 |
| 周次 | `(\d+)\s*-\s*(\d+)` / 单双周关键字（`单周`/`双周`/`单`/`双`） | 支持 `1-16(周)`、`1-16周`、`1,3,5`、`1-8,10-16`；单双周 → `Course.weekType`（`WEEK_ODD=1`/`WEEK_EVEN=2`） |
| 节次 | `\[?(\d{2})-?(\d{2})\]?节` 或 `[0102节]` | 两位数一对 = 一节；`[01-02节]` 取 1..2；`[0103节]` 视为 1..3（连堂） |
| 星期 | 优先 `div.id` 的 `-([1-7])-` 段；退化：用单元格在表格中的**列序**（thead 里 周一..周日） | ⚠️ 广外 id 格式与北林不同（§5.2），**必须真机核** |
| 教室 | font title 含「教室/地点」 | 去掉多余空格 |
| 教师 | font title 含「老师/教师」 | 多人用 `,`/`、` 分隔，ClassGrid 只有单字符串，保留原文 |

映射到 `Course`（`app/src/main/java/com/classgrid/app/Course.kt`）：

```kotlin
Course(
  name = name, teacher = teacher, classroom = room,
  dayOfWeek = weekday,                     // 1..7
  startSection = minOf(sections), endSection = maxOf(sections),
  startWeek = minWeek, endWeek = maxWeek,
  weekType = if (含单周) WEEK_ODD else if (含双周) WEEK_EVEN else WEEK_EVERY,
  excludedWeeks = 周次字符串里「不连续」的部分（如 1-16 去掉 5,6 → setOf(5,6)）
)
```
⚠️ **15 小节 → 12 节**必须显式映射（§5.4），建议先做成常量表 + 在 `OcrResultActivity` 校对页让用户可改。

### 8.6 失败处理矩阵

| 失败现象 | 原因 | 处理 |
| --- | --- | --- |
| 白屏 / 只有背景图 | UA 被判定不支持 | 覆盖 UA 为桌面 Chrome 后 reload（重试 1 次） |
| 停在 authserver 登录页 | 未登录 / session 过期 | 提示「请先完成登录」，不重试 |
| 停在主框架页没进课表 | 用户没点菜单 | 提示「请在页面里点开『课表查询』→ 看到课表后再点导入」，并给文字路径指引 |
| 注入返回 cells=[] | DOM 结构变了 | 回传 raw HTML → 提示「解析失败，可改用截图识别」，并把 raw 存到 `filesDir` 便于远程排查（需用户同意/或只在 debug 版） |
| 解析出课程但字段空 | font title 改名 | 用 `probe.fontTitles` 记日志，发布前对真机 HTML 校准 |
| 网络错误 | 校外被拦 | 提示「校外可能需要连校园网/WebVPN」+ 重试按钮 |

### 8.7 安全与隐私（必须写进用户可读的说明）

1. **不收集密码**：登录完全在 WebView 内，App 不读、不存、不传表单值。
2. **不绕过验证码**：滑块/短信验证码由用户本人完成。
3. **Cookie 生命周期**：默认存在 App 私有目录（WebView 默认行为）；提供「退出并清除登录状态」（`removeAllCookies` + `clearCache` + `clearFormData`）。
4. **只抓用户自己的课表**：不做批量、不做后台自动刷新、不做服务端上传。
5. **从 App 商店合规角度**：功能描述写清「需要用户自行登录学校系统」，避免被认定为「代登录/爬虫服务」。
6. 参考同类产品的做法（WakeUp：「网址可自行修改」「需要用户自己进入课程页面再点导入」），保持「用户主导」的交互，风险和审核都最低。

---

## 9. 必须真机 + 真实账号才能验证的清单

> 前置：本项目当前没有真机/模拟器（`adb devices` 为空），以下全部**未验证**。建议按顺序做，每项记录「现象 + 截图 + 是否通过」。

| # | 验证项 | 方法 | 预期/判定 |
| --- | --- | --- | --- |
| 1 | 校外（4G/5G，关校园网/VPN）能否打开登录页并完成 CAS 登录 | 手机浏览器 + App WebView 各试一次 | 浏览器能打开=入口没封；能否登录成功是关键 |
| 2 | 校园网内能否登录 | 连校园网 | 对照组（排除账号问题） |
| 3 | WebView（默认 UA）是否白屏/被提示不支持浏览器 | 装 debug 包，`chrome://inspect` 看 console | **源码分析后预期通过**（门禁只拦 IE5-10 与字面量 chrome69.0，见 §4.4）；若失败 → 覆盖 UA 再测 |
| 4 | 微信扫码登录在 WebView 是否可用 | 点「微信扫一扫登录」 | 预期不可用（微信 OAuth 限制）→ 文案引导账号密码 |
| 5 | 登录成功后落点 URL 与页面结构 | `chrome://inspect` 看 `location.href` + `document.documentElement.outerHTML` | 记录：是否 `xsMainV_new.htmlx`、是否 frameset/iframe、有几个 frame |
| 6 | 「学生课表查询」菜单在哪、课表页真实 URL | 手动点菜单，记录 URL | 验证是否为 `/jsxsd/xskb/xskb_list.do?Ves632DSdyV=NEW_XSD_PYGL` |
| 7 | 课表 DOM 真实结构 | 在课表页 console 跑 `document.querySelectorAll('.kbcontent').length`、`[...document.querySelectorAll('font[title]')].map(f=>f.title)` 去重、`document.querySelector('.kbcontent')?.id`、`outerHTML` 前 3KB | 决定 §8.5 的解析规则；**重点确认 id 里 `-2` 的含义**、font title 是否叫「周次(节次)」 |
| 8 | iframe 层级 | `document.querySelectorAll('iframe,frame').length` 与各自 `contentDocument` 是否可访问 | 同源应可访问；确认递归深度 |
| 9 | 学期切换 | 在课表页切学期，抓 `xnxq01id` 实际值 | 确认格式 `2026-2027-1` |
| 10 | 15 小节 vs ClassGrid 12 节 | 对照真实课表与 App 网格 | 定映射表（大节=两小节？） |
| 11 | 是否有二次认证（reAuth/多因子） | 多次登录、换设备登录 | 若触发，WebView 方案天然能过（用户自己输），但要确认不会卡死 |
| 12 | 登录失败/重试是否弹验证码 | 连续输错密码 2-3 次 | 【未证实】，记录文案 |
| 13 | 方案 B 是否可行（可选） | `CookieManager.getCookie()` 取 `bzb_jsxsd`/`SERVERID_jsxsd`，用 `HttpURLConnection` POST 课表 URL 看返回是否含 `kbcontent` | 若可行 → 可做「刷新课表」；若 302 到 CAS → 放弃 B |
| 14 | 长时间停留是否掉线 | WebView 打开 10 min 后再导入 | 决定是否要「重新登录」提示 |
| 15 | 弱网/断网表现 | 飞行模式打开 | 错误页可重试，不崩溃 |
| 16 | targetSdk 34 的 WebView Cookie 行为 | 真机测 CAS 回跳后 Cookie 是否带上（`CookieManager.getCookie` 非空） | 预期同站 OK |

---

## 10. 风险登记

| 风险 | 概率 | 影响 | 缓解 |
| --- | --- | --- | --- |
| 校外登录被拦（需 WebVPN） | 中 | 功能对校外用户不可用 | 文案提示连校园网；不做 WebVPN 内嵌（复杂度高，另立项） |
| UA 嗅探导致 WebView 白屏 | 低（源码只拦 IE5-10/chrome69.0，见 §4.4） | 功能不可用 | 保险丝：覆盖桌面 Chrome UA + 重试；真机清单第 3 项 |
| 课表 DOM/字段名与旧系统不同 | 中高 | 解析失败 | 「先探测再匹配」+ 回传 raw HTML + 降级 OCR |
| 教务改版（强智升级） | 高（学校刚换过系统） | 解析全废 | 解析器集中在 `JwxtParser.kt` + 单测样本；versionName 里做灰度开关 |
| 15→12 小节映射错 | 中 | 课表错位 | 校对页显式展示「广外节次 → App 节次」对照，允许用户改 |
| 合规/审核风险 | 低 | 上架被拒 | 用户手动登录、不存密码、不自动请求；页面写明数据仅本机使用 |

---

## 附录 A：本次实测证据（可复现）

**A.1 HTTP 状态与 404 对照**（未登录、无账号、异地公网）

```
404   727B   /nonexistent-zz9                          → 强智 404 页（说明服务端区分「不存在」）
200   860B   /app.do?method=__no_such_method__          → 「用户没有登录，请重新登录！」（兜底 servlet，method 无关）
200   860B   /app.do?method=authUser&xh=1&pwd=1         → 同上（不能证明该 API 存在）
200  50485B  /jsxsd/xskb/xskb_list.do                   → 登录壳页（路径存在、被登录过滤器拦截）
200  50465B  /jsxsd/framework/xsMainV_new.htmlx?t1=1    → 登录壳页（路径存在）
200  50478B  /jsxsd/kscj/cjcx_list                      → 登录壳页（路径存在）
```

复现命令：

```bash
for p in "/nonexistent-zz9" "/app.do?method=__no_such_method__" "/jsxsd/xskb/xskb_list.do"; do
  code=$(curl -s -o out.bin -w "%{http_code}" -m 20 "https://jwxt.gdufs.edu.cn$p")
  echo "$code $(wc -c < out.bin) $p"
done
```

**A.2 CAS 跳转**（`GET https://jwxt.gdufs.edu.cn/jsxsd/sso.jsp` → 200，body 内联 JS）

```js
window.location.href='https://authserver.gdufs.edu.cn/authserver/login?service=https%3A%2F%2Fjwxt.gdufs.edu.cn%2Fjsxsd%2Fsso.jsp';
```

**A.3 证书**

```
subject=CN=*.gdufs.edu.cn, O=广东外语外贸大学
issuer=cnTrus OV SSL CA (Zhejiang Huluwa) → USERTrust RSA Certification Authority → AAA Certificate Services
```

**A.4 登录后 Cookie 名**（第三方源码 + 本次未登录响应头一致）

```
bzb_jsxsd          Path=/jsxsd   HttpOnly
SERVERID_jsxsd     Path=/
```

**A.5 旧域名**

```
nslookup jxgl.gdufs.edu.cn → 202.116.196.71
curl https://jxgl.gdufs.edu.cn/ → 连接被关闭（000）
```

## 附录 B：来源索引

- 广外教务登录页：<https://jwxt.gdufs.edu.cn/>
- 官方 CAS 入口：<https://jwxt.gdufs.edu.cn/jsxsd/sso.jsp>
- 统一身份认证：<https://authserver.gdufs.edu.cn/authserver/login>
- 教务部（链接来源）：<https://jwb.gdufs.edu.cn/>
- 学校首页服务导航（教务系统/校外用 VPN）：<https://www.gdufs.edu.cn/info/1350/58072.htm>
- WebVPN 说明：<https://etr.gdufs.edu.cn/info/1303/5144.htm>、<https://etr.gdufs.edu.cn/info/1303/5210.htm>
- 智慧广外密码找回：<https://yx.gdufs.edu.cn/info/1035/1368.htm>
- 广外增强版教务（逆向出全部端点）：<https://github.com/Lucifer-Jimmy/gdufs-jwxt-next-origin>
- 广外工具箱（课表解析实现）：<https://github.com/Systina12/GDUFS-Toolkit>
- 强智课表接口通用文档：<https://www.jet-lab.site/blogs/qiangzhi>
- 小爱课程表强智适配实录：<https://www.cnblogs.com/hed10ne/p/miui_schedule_document.html>、<https://github.com/Holit/HEU_edusys_miui>
- WakeUp 课程表导入说明：<https://www.wakeup.fun/doc/import_from_eas.html>
- 强智课表爬虫参考：<https://github.com/canliture/CrawlerCourseTable>、<https://github.com/inannan423/classCrawl>
- 金智教务参考：<https://github.com/Yorick-Ryu/NIIT_getCourse>

---

## 11. 下一步建议（给 Lead）

1. **先做最小可用版**：`WebViewImportActivity` + `JwxtParser` + 复用 `OcrResultActivity`，不碰方案 B/C。
2. **把「真机验证」当独立任务**：§9 的 1-9 项做完再写解析规则（否则解析规则只能靠猜，返工概率高）。
3. **解析器与 UI 解耦**：`JwxtParser` 输入 HTML 字符串、输出 `List<Course>`，用真机抓到的 HTML 做单测样本，这样教务改版时改动面最小。
4. **降级路径先写好**：解析失败 → 一键回到截图 OCR（现有链路最稳，广外课表截图 OCR 的解析器 `OcrParser.kt`/`GridParser.kt` 已经能处理「星期表头+大节锚点」的整周截图）。
