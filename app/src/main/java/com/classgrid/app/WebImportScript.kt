package com.classgrid.app

/**
 * 注入到 WebView 的 JS 脚本（链接抓取用）。
 *
 * ===== v1.32 变化（用户反馈驱动，详见 docs/DEVLOG.md 第 5 轮）=====
 * 1. **绝不自动扫描**：只在页面加载完「安装钩子」，不解析、不弹窗。用户登录并进入课表页面后，
 *    自己点底部「抓取当前页面」才执行 `__scan(true)`。（v1.31 的缺陷：onPageFinished 主动
 *    `__scan(true)` 会把"手动"标记传给 Kotlin，于是任何页面一打开就弹「没有识别出课表」。）
 * 2. **同时抓「接口数据」**：教务 H5（智慧广外/新版课表）是用 XHR/fetch 拿 JSON 再渲染的，
 *    页面上的卡片往往会把文字截断，而且不显示周次。所以这里给 fetch / XMLHttpRequest 打钩子，
 *    把页面自己请求到的 JSON 响应缓存下来（`window.ClassGrid.__net`），一次抓取连同 DOM 一起回传。
 *    Kotlin 侧的 [WebJsonParser] 优先从这些 JSON 里解析——这才是「完整数据」的来源。
 * 3. **DOM 视觉网格兜底**：很多新系统课表不是 `<table>` 而是 div 网格（左侧大节、顶部星期/日期）。
 *    这时按元素坐标 + 颜色抽文本块（`blocks`），并在 JS 里就地还原成一张"合成表格"塞进 `tables`，
 *    这样 Kotlin 侧不用再加一套解析器。
 * 4. 仍然 **递归同源 iframe**；仍然只暴露一个 `onScan(String)` 桥；仍然零第三方依赖。
 *
 * ===== 安全 =====
 * 钩子只读 `fetch`/`XHR` 的响应文本并与页面同源数据一起回传，不修改请求、不注入页面逻辑。
 * 桥只暴露 onScan(String)，Kotlin 侧只把它交给解析器，不执行页面传入的任何指令。
 */
object WebImportScript {

    /** JS 桥接对象名（Kotlin 侧 addJavascriptInterface(桥, 同名)） */
    const val BRIDGE = "ClassGrid"

    /** 用户点「抓取当前页面」时调用：只扫描，不重复安装钩子 */
    const val SCAN_CALL = "window.ClassGrid && window.ClassGrid.__scan(true);"

    /** 安装钩子（fetch/XHR 捕获、DOM 提取工具），**不做任何扫描** */
    val INSTALL_JS: String = """
(function () {
  try {
    window.ClassGrid = window.ClassGrid || {};
    // 已安装过（SPA 路由切换时会被再次注入）：什么都不做，等用户点抓取
    if (window.ClassGrid.__installed) { return; }
    window.ClassGrid.__installed = true;

    var BRIDGE = 'ClassGrid';
    var MAX_TABLES = 8;
    var MAX_DEPTH = 4;
    var MAX_BLOCKS = 600;
    var NET_MAX_DOCS = 40;
    var NET_MAX_BODY = 300000;
    var NET_MAX_TOTAL = 1500000;
    var HOOKED_FLAG = '__cgHooked';

    // ------------------------------------------------------------ 通用文本工具

    function normText(s) {
      if (s === null || s === undefined) return '';
      return String(s).replace(/\u00a0/g, ' ').replace(/\r/g, '');
    }

    // 按行清洗：去掉每行首尾空白与全角空格，丢弃空行，保留换行结构（课程块内字段靠换行分隔）
    function cleanLines(raw) {
      var arr = normText(raw).split('\n');
      var out = [];
      for (var i = 0; i < arr.length; i++) {
        var v = arr[i].replace(/^[\s\u3000]+/, '').replace(/[\s\u3000]+$/, '');
        if (v.length > 0) out.push(v);
      }
      return out;
    }

    function cellText(el) { return cleanLines(el.innerText || el.textContent || '').join('\n'); }

    // 一个单元格里可能塞了多门课（周次不同/同一时段两门），
    // 多数系统用子 div 把每门课包起来，所以优先把子元素文本作为"课程块"；没有子元素时退回整格文本。
    function cellBlocks(el) {
      var out = [];
      var kids = el.children || [];
      for (var i = 0; i < kids.length; i++) {
        var k = kids[i];
        var tag = (k.tagName || '').toUpperCase();
        if (tag === 'BR') continue;
        var t = cellText(k);
        if (t.length === 0) continue;
        out.push(t);
      }
      if (out.length <= 1) {
        var whole = cellText(el);
        return whole.length > 0 ? [whole] : [];
      }
      return out;
    }

    // ------------------------------------------------------------ 网络响应捕获（后端数据）

    var NET = [];

    function pushNet(url, status, body) {
      try {
        if (!url || typeof body !== 'string' || body.length === 0) return;
        if (body.length > NET_MAX_BODY) body = body.substring(0, NET_MAX_BODY);
        NET.push({ u: String(url), s: status | 0, b: body });
        if (NET.length > NET_MAX_DOCS) NET.shift();
        // 总量上限：跨域桥传 1MB 以上的字符串会明显卡顿，超出就丢最旧的响应
        var total = 0;
        for (var i = 0; i < NET.length; i++) total += NET[i].b.length;
        while (total > NET_MAX_TOTAL && NET.length > 1) {
          total -= NET[0].b.length;
          NET.shift();
        }
      } catch (e) {}
    }

    function looksJson(s) {
      if (typeof s !== 'string') return false;
      var t = s.replace(/^\s+/, '');
      if (t.length === 0) return false;
      var c = t.charAt(0);
      return c === '{' || c === '[';
    }

    // 课表接口的 URL 特征（拿不准就多收几个，Kotlin 侧会按内容打分自动挑最像课表的那个数组）
    var API_HINT = /(kb|kcb|kbcx|kbsp|course|schedule|timetable|curriculum|xsall|xsgr|xsxx|query)/i;

    // 钩子：fetch
    function hookFetch(win) {
      try {
        if (!win || !win.fetch || win.fetch[HOOKED_FLAG]) return;
        var orig = win.fetch;
        var wrapped = function (input, init) {
          var url = '';
          try {
            url = typeof input === 'string' ? input : (input && input.url ? String(input.url) : '');
          } catch (e) { url = ''; }
          var p = orig.apply(this, arguments);
          try {
            if (p && typeof p.then === 'function') {
              p.then(function (resp) {
                try {
                  if (!resp) return resp;
                  var ct = '';
                  try { ct = resp.headers && resp.headers.get ? String(resp.headers.get('content-type') || '') : ''; } catch (e) { ct = ''; }
                  var want = /json|javascript|text/i.test(ct) || API_HINT.test(url);
                  if (!want) return resp;
                  var cp = resp.clone ? resp.clone() : resp;
                  cp.text().then(function (t) {
                    try { if (looksJson(t) || API_HINT.test(url)) pushNet(url, resp.status, t); } catch (e) {}
                  }).catch(function () {});
                } catch (e) {}
                return resp;
              }).catch(function () {});
            }
          } catch (e) {}
          return p;
        };
        wrapped[HOOKED_FLAG] = true;
        win.fetch = wrapped;
      } catch (e) {}
    }

    // 钩子：XMLHttpRequest
    function hookXhr(win) {
      try {
        var X = win && win.XMLHttpRequest;
        if (!X || !X.prototype || X.prototype[HOOKED_FLAG]) return;
        var origOpen = X.prototype.open;
        var origSend = X.prototype.send;
        X.prototype.open = function (m, u) {
          try { this.__cgUrl = u ? String(u) : ''; } catch (e) {}
          return origOpen.apply(this, arguments);
        };
        X.prototype.send = function () {
          var self = this;
          try {
            self.addEventListener('load', function () {
              try {
                var url = String(self.__cgUrl || '');
                var ct = '';
                try { ct = String(self.getResponseHeader('content-type') || ''); } catch (e) { ct = ''; }
                if (!(/json/i.test(ct) || API_HINT.test(url))) return;
                var t = '';
                try {
                  if (!self.responseType || self.responseType === 'text') t = self.responseText;
                  else t = JSON.stringify(self.response);
                } catch (e) { t = ''; }
                if (typeof t === 'string' && (looksJson(t) || API_HINT.test(url))) pushNet(url, self.status, t);
              } catch (e) {}
            });
          } catch (e) {}
          return origSend.apply(this, arguments);
        };
        X.prototype[HOOKED_FLAG] = true;
      } catch (e) {}
    }

    // ------------------------------------------------------------ 同源文档收集

    // 递归收集同源文档：主文档 + 所有 iframe/frame 的 contentDocument（跨域会抛异常，忽略）
    function collectDocs(root, out, depth) {
      if (!root || depth > MAX_DEPTH || out.length >= 12) return;
      out.push(root);
      var frames = [];
      try { frames = root.querySelectorAll('iframe,frame'); } catch (e) { frames = []; }
      for (var i = 0; i < frames.length; i++) {
        var d = null;
        try { d = frames[i].contentDocument; } catch (e) { d = null; }
        if (d && d.documentElement) collectDocs(d, out, depth + 1);
      }
    }

    // iframe 里的 fetch/XHR 是子窗口自己的，父窗口钩不到；同源的话把钩子注入子窗口
    function hookFrames() {
      var docs = [];
      collectDocs(document, docs, 0);
      for (var i = 1; i < docs.length && i < 8; i++) {
        try { hookFetch(docs[i].defaultView); hookXhr(docs[i].defaultView); } catch (e) {}
      }
    }

    // ------------------------------------------------------------ 表格提取（原生 <table>）

    function tableModel(tb, frameIdx) {
      var rows = tb.rows || [];
      var model = {
        id: tb.id ? String(tb.id) : '',
        cls: tb.className ? String(tb.className) : '',
        frame: frameIdx,
        rows: []
      };
      for (var r = 0; r < rows.length; r++) {
        var cells = rows[r].cells || [];
        var rm = { cells: [] };
        for (var c = 0; c < cells.length; c++) {
          var cell = cells[c];
          rm.cells.push({
            rs: cell.rowSpan ? cell.rowSpan : 1,
            cs: cell.colSpan ? cell.colSpan : 1,
            text: cellText(cell),
            blocks: cellBlocks(cell)
          });
        }
        model.rows.push(rm);
      }
      return model;
    }

    var DAY_RE = /(?:星期|周|週|礼拜|禮拜)\s*[一二三四五六日天1-7]/;

    function collectTables() {
      var docs = [];
      collectDocs(document, docs, 0);
      var out = [];
      for (var d = 0; d < docs.length && out.length < MAX_TABLES; d++) {
        var all = [];
        try { all = docs[d].querySelectorAll('table'); } catch (e) { all = []; }
        for (var i = 0; i < all.length && out.length < MAX_TABLES; i++) {
          var tb = all[i];
          if (!tb.rows || tb.rows.length < 3) continue;
          if (!tb.rows[0] || !tb.rows[0].cells || tb.rows[0].cells.length < 3) continue;
          var joined = normText(tb.innerText || tb.textContent || '');
          // v1.42.0：表头不一定是「周X/星期X」。允许裸日字「一」、日字+日期「一 11/2」、纯日期「11/2」，
          // 也允许没有像样表头但「≥4 列 + 左列 ≥3 个节次/时间标签」的表格（很多教务系统表头是图片或被拆碎）。
          var headDays = 0, cols = 0, secs = 0;
          var scanRows = Math.min(tb.rows.length, 3);
          for (var hr = 0; hr < scanRows; hr++) {
            var hc = tb.rows[hr] && tb.rows[hr].cells ? tb.rows[hr].cells : [];
            if (hc.length > cols) cols = hc.length;
            for (var hi = 0; hi < hc.length; hi++) {
              var ht = normText(hc[hi].innerText || hc[hi].textContent || '');
              if (looksLikeDayHeader(ht)) headDays++;
            }
          }
          for (var sr = 0; sr < tb.rows.length; sr++) {
            var sc = tb.rows[sr] && tb.rows[sr].cells ? tb.rows[sr].cells[0] : null;
            if (!sc) continue;
            var st = normText(sc.innerText || sc.textContent || '');
            if (/(第\s*\d{1,2}\s*[节大]|\d{1,2}\s*[:：]\s*\d{2}|\d{1,2}\s*[-~]\s*\d{1,2}\s*[小节])/.test(st)) secs++;
          }
          var okTable = DAY_RE.test(joined) || headDays >= 3 || (cols >= 4 && secs >= 3);
          if (!okTable) continue;
          out.push(tableModel(tb, d));
        }
      }
      return out;
    }

    function hasKbTable() {
      var docs = [];
      collectDocs(document, docs, 0);
      for (var i = 0; i < docs.length; i++) {
        try {
          if (docs[i].querySelector('#kbtable, .kbcontent, #shijian, #kbcontent')) return true;
        } catch (e) {}
      }
      return false;
    }

    // ------------------------------------------------------------ DOM 视觉网格（div 课表兜底）

    function visible(el) {
      try {
        var r = el.getBoundingClientRect();
        if (!r || r.width < 3 || r.height < 3) return false;
        var st = null;
        try { st = el.ownerDocument.defaultView.getComputedStyle(el); } catch (e) { st = null; }
        if (st) {
          if (st.visibility === 'hidden' || st.display === 'none') return false;
          if (parseFloat(st.opacity || '1') < 0.05) return false;
        }
        return true;
      } catch (e) { return false; }
    }

    // 收集"叶子文本元素"（没有元素子节点、有文本、可见），带上文档坐标 + 背景色 + 字号。
    // 课表卡片通常是有背景色的 div，其文本被多个 span/div 分层，所以同时保留叶子节点即可。
    function collectBlocks() {
      var docs = [];
      collectDocs(document, docs, 0);
      var out = [];
      var docsLen = docs.length;
      for (var d = 0; d < docsLen && out.length < MAX_BLOCKS; d++) {
        var doc = docs[d];
        var win = null;
        try { win = doc.defaultView; } catch (e) { win = null; }
        if (!win) continue;
        var sx = 0, sy = 0;
        try { sx = win.scrollX || 0; sy = win.scrollY || 0; } catch (e) {}
        var all = [];
        try { all = doc.querySelectorAll('div,span,a,td,li,p,strong,b,font,section,article'); } catch (e) { all = []; }
        for (var i = 0; i < all.length && out.length < MAX_BLOCKS; i++) {
          var el = all[i];
          if (!el || el.children.length > 0) continue;   // 只要叶子节点，避免父节点把整块文本重复上报
          var t = normText(el.innerText || el.textContent || '').replace(/^\s+|\s+$/g, '');
          if (t.length === 0 || t.length > 160) continue;
          if (!visible(el)) continue;
          var r = el.getBoundingClientRect();
          var c = '';
          try {
            var st = win.getComputedStyle(el);
            c = String(st.backgroundColor || '');
            if (!c || c === 'rgba(0, 0, 0, 0)' || c === 'transparent') {
              // 卡片背景常挂在上层容器，向上找两层取到颜色为止
              var p = el.parentElement;
              for (var k = 0; k < 3 && p; k++) {
                var pc = String(win.getComputedStyle(p).backgroundColor || '');
                if (pc && pc !== 'rgba(0, 0, 0, 0)' && pc !== 'transparent') { c = pc; break; }
                p = p.parentElement;
              }
            }
          } catch (e) { c = ''; }
          out.push({
            t: t,
            x: Math.round((r.left + sx) * 10) / 10,
            y: Math.round((r.top + sy) * 10) / 10,
            w: Math.round(r.width * 10) / 10,
            h: Math.round(r.height * 10) / 10,
            c: c,
            f: d
          });
        }
      }
      return out;
    }

    // 把 div 网格就地还原成"合成表格"：
    //   找到「星期/周X」表头（同一水平带、x 递增）→ 列 ↔ 星期
    //   找到最左侧「大节/小节/时间」标签 → 行 ↔ 节次
    //   剩下的文本块按中心点落格；一个格子里多门课用 \n\n 连接（Kotlin 侧按 blocks 拆）
    // 这样 Kotlin 的表格解析器可以原样复用，不必为 div 网格再写一套坐标算法。
    // 中文数字 → 阿拉伯数字：新版教务行头写成「一大节 / 二大节」，Kotlin 解析器只认「第N大节」
    var CN_DIGIT = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9, '十': 10 };

    function normSectionLabel(t) {
      var s = String(t || '').trim();
      var m = s.match(/^([一二三四五六七八九十])\s*大\s*节/);
      if (m && CN_DIGIT[m[1]]) {
        var rest = s.substring(m[0].length).replace(/^[\s,，、]+/, '');
        return '第' + CN_DIGIT[m[1]] + '大节' + (rest.length > 0 ? ' ' + rest : '');
      }
      return s;
    }

    // 从「周日01 / 星期一 / 礼拜3 / 一 / 一 11/2 / Mon」这类文本里取星期几（1..7），取不到返回 0。
    // v1.42.0：大量教务系统的表头是裸日字或「一 11/2」，旧规则只认「周X/星期X」，导致整张表被丢掉。
    var DATE_ONLY_RE = /^\d{1,2}\s*[\/月.\-]\s*\d{1,2}\s*日?$/;
    function dayNumOf(t) {
      var s = normText(t || '').replace(/\s+/g, '');
      if (!s || s.length > 16) return 0;
      var m = /(?:星期|周|週|礼拜|禮拜)\s*([一二三四五六日天1-7])/.exec(s);
      if (m) {
        var c = m[1];
        if (c >= '1' && c <= '7') return parseInt(c, 10);
        if (c === '日' || c === '天') return 7;
        var idx = '一二三四五六'.indexOf(c);
        return idx >= 0 ? idx + 1 : 0;
      }
      var bare = /^([一二三四五六日天1-7])(?:[(（]?\d{1,2}[\/月.\-]\d{1,2}日?[)）]?)?$/.exec(s);
      if (bare) {
        var ch = bare[1];
        if (ch >= '1' && ch <= '7') return parseInt(ch, 10);
        if (ch === '日' || ch === '天') return 7;
        var bi = '一二三四五六'.indexOf(ch);
        return bi >= 0 ? bi + 1 : 0;
      }
      var en = /^(mon|tue|tues|wed|thur|thurs|fri|sat|sun)(day)?$/i.exec(s);
      if (en) {
        var key = en[1].toLowerCase().substring(0, 3);
        var order = { mon: 1, tue: 2, wed: 3, thu: 4, fri: 5, sat: 6, sun: 7 };
        return order[key] || 0;
      }
      return 0;
    }

    function looksLikeDayHeader(t) {
      return dayNumOf(t) > 0 || DATE_ONLY_RE.test(normText(t || '').replace(/\s+/g, ''));
    }

    function dayLabel(n) {
      return '周' + '一二三四五六日'.charAt(n - 1);
    }

    // 把表头块整理成「一列一天」：星期的数字必须严格递增，中间缺的列按相邻两列的 x 线性插值补出来。
    // 表头认不出星期（不是「周X」形态）时原样返回，交给 Kotlin 侧按列位置和表头文字自行判断。
    function fillDayColumns(cols) {
      var days = [];
      var ords = [];
      var first = 0;
      for (var i = 0; i < cols.length; i++) {
        var d = dayNumOf(cols[i].t);
        if (d < 1) return cols;
        if (i === 0) first = d;
        // 很多课表第一列是「周日」，所以要按「从首列起的偏移」比较，不能直接比数字大小
        ords.push(((d - first) + 7) % 7);
        days.push(d);
      }
      for (var j = 1; j < ords.length; j++) {
        if (ords[j] <= ords[j - 1]) return null;   // 顺序错乱，放弃这一页的网格
      }
      var out = [];
      for (var k = 0; k < cols.length; k++) {
        if (k > 0 && ords[k] - ords[k - 1] > 1) {
          var prevC = cols[k - 1].x + (cols[k - 1].w || 0) / 2;
          var curC = cols[k].x + (cols[k].w || 0) / 2;
          var span = ords[k] - ords[k - 1];
          for (var dd = 1; dd < span; dd++) {
            var midC = prevC + (curC - prevC) * dd / span;
            out.push({ x: midC - (cols[k].w || 0) / 2, y: cols[k].y, w: cols[k].w, h: cols[k].h, t: dayLabel(((first - 1 + ords[k - 1] + dd) % 7) + 1) });
          }
        }
        out.push({ x: cols[k].x, y: cols[k].y, w: cols[k].w, h: cols[k].h, t: dayLabel(days[k]) });
      }
      return out;
    }

    function buildGridTable(blocks) {
      try {
        var dayHeads = [];
        for (var i = 0; i < blocks.length; i++) {
          var b = blocks[i];
          if (b.t.length > 16) continue;
          if (!looksLikeDayHeader(b.t)) continue;
          dayHeads.push(b);
        }
        if (dayHeads.length < 4) return null;   // 4 天以上才可能是课表（有些系统隐藏周末列）

        // 同一水平带：以出现次数最多的 y 为基准（容差 24px），避免把"星期"文字别的行也算进来
        dayHeads.sort(function (a, b) { return a.y - b.y; });
        var baseY = dayHeads[0].y;
        var band = [];
        for (var j = 0; j < dayHeads.length; j++) {
          if (Math.abs(dayHeads[j].y - baseY) <= 24) band.push(dayHeads[j]);
        }
        if (band.length < 4) band = dayHeads.slice(0, 7);
        band.sort(function (a, b) { return a.x - b.x; });

        // 去掉重复列（同一 x 附近重复上报）
        var cols = [];
        for (var k = 0; k < band.length; k++) {
          var last = cols.length > 0 ? cols[cols.length - 1] : null;
          if (last && Math.abs(last.x - band[k].x) < 12) continue;
          cols.push(band[k]);
        }
        if (cols.length < 4) return null;

        // 星期列校正：按表头文字取出「星期几」，用来
        //  1) 保证列顺序确实是从周一往周日（顺序错乱就直接放弃，宁可不抓也不能抓错星期）；
        //  2) 补齐中间漏识别的列（真实页面某些表头文字可能是图片/被拆碎，少一列会让后面所有课串列）。
        cols = fillDayColumns(cols);
        if (!cols) return null;

        var firstColX = cols[0].x;
        // 列的横向边界：以每个表头文字的中心为列中心，相邻中心的中点为分界。
        // （不能用表头左边缘：文字宽度不同会整体偏移半列，把课塞进隔壁星期）
        var colCenters = [];
        for (var c0 = 0; c0 < cols.length; c0++) {
          colCenters.push(cols[c0].x + (cols[c0].w || 0) / 2);
        }
        var colBounds = [];
        colBounds.push(colCenters[0] - (colCenters.length > 1 ? (colCenters[1] - colCenters[0]) / 2 : 30));
        for (var cb = 1; cb < colCenters.length; cb++) {
          colBounds.push((colCenters[cb - 1] + colCenters[cb]) / 2);
        }
        colBounds.push(colCenters[colCenters.length - 1] + (colCenters.length > 1 ? (colCenters[colCenters.length - 1] - colCenters[colCenters.length - 2]) / 2 : 30));

        // 行标签：位于第一列左侧、文本像节次/时间
        var SEC_LABEL_RE = /(大节|小节|第\s*\d+\s*节|\d{1,2}\s*[:：]\s*\d{2})/;
        var rowsLabels = [];
        for (var r1 = 0; r1 < blocks.length; r1++) {
          var rb = blocks[r1];
          if (rb.x + rb.w > firstColX + 6) continue;   // 必须在第一列左边
          if (rb.y < baseY - 4) continue;              // 表头以上不要
          if (!SEC_LABEL_RE.test(rb.t)) continue;
          rowsLabels.push(rb);
        }
        rowsLabels.sort(function (a, b) { return a.y - b.y; });
        var labels = [];
        for (var r2 = 0; r2 < rowsLabels.length; r2++) {
          var lp = labels.length > 0 ? labels[labels.length - 1] : null;
          if (lp && Math.abs(lp.y - rowsLabels[r2].y) < 10) continue;
          labels.push(rowsLabels[r2]);
        }
        if (labels.length < 3) return null;

        var rowBounds = [];
        for (var r3 = 0; r3 < labels.length; r3++) {
          var top = r3 === 0 ? baseY : (labels[r3 - 1].y + labels[r3].y) / 2;
          var bottom = r3 === labels.length - 1 ? labels[r3].y + Math.max(60, labels[r3].h * 6) : (labels[r3].y + labels[r3 + 1].y) / 2;
          rowBounds.push({ top: top, bottom: bottom, label: normSectionLabel(labels[r3].t) });
        }

        // 落格：先按列收集文本块，再按纵向间隔把同一列的文字聚成"一张卡片"，
        // 最后用卡片"顶部"的 y 判它属于哪个大节——这样课程名/教师/教室不会被行边界拆开。
        var cardCols = [];
        for (var cc = 0; cc < cols.length; cc++) cardCols.push([]);
        for (var bi = 0; bi < blocks.length; bi++) {
          var blk = blocks[bi];
          if (blk.y < baseY + 8) continue;                              // 表头带（含星期文字本身）不要
          if (blk.x + blk.w <= firstColX + 6) continue;                 // 行标签列
          var bcx = blk.x + blk.w / 2;
          if (bcx < colBounds[0] || bcx > colBounds[colBounds.length - 1]) continue;
          var colIdx = -1;
          for (var ci = 1; ci < colBounds.length; ci++) {
            if (bcx >= colBounds[ci - 1] && bcx < colBounds[ci]) { colIdx = ci - 1; break; }
          }
          if (colIdx < 0) continue;
          cardCols[colIdx].push(blk);
        }

        var cellsCards = [];
        for (var rr = 0; rr < rowBounds.length; rr++) {
          var rowArr = [];
          for (var cc2 = 0; cc2 < cols.length; cc2++) rowArr.push([]);
          cellsCards.push(rowArr);
        }
        for (var ci2 = 0; ci2 < cardCols.length; ci2++) {
          var colList = cardCols[ci2];
          colList.sort(function (a, b) { return a.y - b.y; });
          var cluster = [];
          var lastBottom = -10000;
          var flush = function () {
            if (cluster.length === 0) return;
            var topY = cluster[0].y;
            var texts = [];
            for (var q = 0; q < cluster.length; q++) texts.push(cluster[q].t);
            var rowIdx = -1;
            for (var ri = 0; ri < rowBounds.length; ri++) {
              if (topY >= rowBounds[ri].top && topY < rowBounds[ri].bottom) { rowIdx = ri; break; }
            }
            // 卡片顶部紧贴在行边界下方时，仍归到上一行（真实课表里卡片不会跨节）
            if (rowIdx < 0 && topY < rowBounds[0].top) rowIdx = 0;
            if (rowIdx >= 0) cellsCards[rowIdx][ci2].push(texts.join('\n'));
            cluster = [];
          };
          for (var gi = 0; gi < colList.length; gi++) {
            var item = colList[gi];
            if (cluster.length > 0 && (item.y - lastBottom) > 12) flush();
            cluster.push(item);
            lastBottom = item.y + (item.h || 14);
          }
          flush();
        }

        // 表头行（星期）+ 每行（节次标签 + 各天文本）
        var rows = [];
        var headRow = { cells: [] };
        headRow.cells.push({ rs: 1, cs: 1, text: '', blocks: [] });
        for (var hc = 0; hc < cols.length; hc++) {
          headRow.cells.push({ rs: 1, cs: 1, text: cols[hc].t, blocks: [cols[hc].t] });
        }
        rows.push(headRow);

        for (var rIdx = 0; rIdx < rowBounds.length; rIdx++) {
          var cellsRow = { cells: [] };
          cellsRow.cells.push({ rs: 1, cs: 1, text: rowBounds[rIdx].label, blocks: [rowBounds[rIdx].label] });
          for (var cIdx = 0; cIdx < cols.length; cIdx++) {
            var cards = cellsCards[rIdx][cIdx];
            cellsRow.cells.push({ rs: 1, cs: 1, text: cards.join('\n\n'), blocks: cards });
          }
          rows.push(cellsRow);
        }

        return { id: 'dom-grid', cls: 'classgrid-synthetic', frame: 0, rows: rows };
      } catch (e) {
        return null;
      }
    }

    // ------------------------------------------------------------ 文本摘要 / 回传

    function collectBodyText() {
      var docs = [];
      collectDocs(document, docs, 0);
      var parts = [];
      var total = 0;
      for (var i = 0; i < docs.length; i++) {
        var t = '';
        try { t = normText(docs[i].body ? (docs[i].body.innerText || docs[i].body.textContent || '') : ''); } catch (e) { t = ''; }
        if (t.length === 0) continue;
        parts.push(t);
        total += t.length;
        if (total > 8000) break;
      }
      var joined = parts.join('\n');
      return joined.length > 8000 ? joined.substring(0, 8000) : joined;
    }

    // 页面自身的 JS 报错也收集起来：抓不到时最常见的原因就是页面脚本异常 / 我们的采集函数被新 DOM 结构搞崩，
    // 之前这类错误在手机上看不到任何痕迹（用户反馈「不知道日志在哪」）。
    var ERRS = [];
    function pushErr(msg) {
      try {
        if (ERRS.length < 20) ERRS.push(String(msg).slice(0, 300));
      } catch (e) {}
    }
    try {
      window.addEventListener('error', function (ev) {
        pushErr(ev && ev.message ? ev.message : 'script error');
      });
      window.addEventListener('unhandledrejection', function (ev) {
        var r = ev && ev.reason;
        pushErr('promise: ' + (r && r.message ? r.message : r));
      });
    } catch (e) {}

    function send(manual) {
      var payload = null;
      try {
        hookFrames();   // iframe 里的请求也可能带课表 JSON，扫描前补挂一次钩子

        var tables = [];
        try { tables = collectTables(); } catch (e) { pushErr('collectTables: ' + (e && e.message)); tables = []; }

        var blocks = [];
        try { blocks = collectBlocks(); } catch (e) { pushErr('collectBlocks: ' + (e && e.message)); blocks = []; }

        // div 网格：合成一张表塞进 tables，Kotlin 侧复用表格解析器
        try {
          var gridTable = buildGridTable(blocks);
          if (gridTable) tables.push(gridTable);
        } catch (e) { pushErr('buildGridTable: ' + (e && e.message)); }

        payload = {
          url: String(location.href),
          title: document.title ? String(document.title) : '',
          tables: tables,
          blocks: blocks,
          net: NET.slice(0, NET_MAX_DOCS),
          bodyText: collectBodyText(),
          hasKbTable: hasKbTable(),
          manual: !!manual,
          hasScript: true,
          errors: ERRS.slice(0, 10)
        };
      } catch (e) {
        // 采集整体失败也必须回传一次，否则界面上只会看到「没找到课表」，完全无法定位
        pushErr('scan: ' + (e && e.message));
        payload = {
          url: String(location.href),
          title: '',
          tables: [],
          blocks: [],
          net: NET.slice(0, NET_MAX_DOCS),
          bodyText: '',
          hasKbTable: false,
          manual: !!manual,
          hasScript: true,
          errors: ERRS.slice(0, 10)
        };
      }
      try {
        window[BRIDGE].onScan(JSON.stringify(payload));
      } catch (e) {}
    }

    window.ClassGrid.__scan = send;
    window.ClassGrid.__net = NET;
    window.ClassGrid.__blocks = collectBlocks;

    // 安装网络钩子（这一步不产生任何回传 / 弹窗）
    hookFetch(window);
    hookXhr(window);
    hookFrames();

    // 不再安装 MutationObserver / 定时器：用户明确要求「点抓取之前不主动抓」。
    // 页面渲染完 / SPA 路由切换时，重新注入本脚本只会设置 __installed 并补挂 iframe 钩子。
  } catch (e) {}
})();
""".trimIndent()
}
