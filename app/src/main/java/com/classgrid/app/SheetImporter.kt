package com.classgrid.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * Excel / CSV 表格读取（v1.42.0）。
 *
 * 为什么不用第三方库：包体积只有 25MB，为导入一个表格引入 Apache POI 会让 APK 翻倍；
 * xlsx 本质是 zip + 若干 XML，我们用 JDK 自带的 ZipInputStream + javax.xml DOM 就能读，
 * 单测里也能直接构造 xlsx 字节流验证。
 *
 * 支持：
 *  - .xlsx（第一张工作表；共享字符串 / inlineStr / 数字 / 合并单元格填充）
 *  - .csv / .tsv（自动识别逗号、制表符、分号；处理引号转义；UTF-8 失败时回退 GB18030）
 *
 * 解析出的二维网格交给 [TimetableGrid.parse] 做课表语义解析。
 */
object SheetImporter {

    private const val MAX_ROWS = 600
    private const val MAX_COLS = 80
    private const val MAX_ENTRY_BYTES = 12 * 1024 * 1024

    /** 按文件名/内容判断格式并读取 */
    fun read(fileName: String, bytes: ByteArray): TimetableGrid.Sheet {
        val lower = fileName.lowercase()
        val looksZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        return when {
            // OLE2 复合文档：Excel 97-2003 .xls（BIFF8）
            XlsImporter.isXls(bytes) -> XlsImporter.read(bytes)
            lower.endsWith(".xlsx") || lower.endsWith(".xlsm") || looksZip -> readXlsx(bytes)
            // 明确是 .xls 但不是 OLE2（有些系统把 HTML/CSV 存成 .xls）
            lower.endsWith(".xls") -> readText(bytes)
            else -> readText(bytes)
        }
    }

    /** 给导入失败时的日志用：文件头十六进制 + 大小，方便判断到底是什么格式 */
    fun describe(fileName: String, bytes: ByteArray): String {
        val name = fileName.ifBlank { "（未命名文件）" }
        return buildString {
            append("文件：").append(name).append('\n')
            append("大小：").append(bytes.size).append(" 字节\n")
            append("文件头：").append(XlsImporter.hexHead(bytes, 32))
        }
    }

    // ------------------------------------------------------------------ CSV / TSV

    fun readText(bytes: ByteArray): TimetableGrid.Sheet {
        val text = decode(bytes)
        return TimetableGrid.Sheet(parseCsv(text))
    }

    fun decode(bytes: ByteArray): String {
        // 先按严格 UTF-8 解码：出现替换符说明不是 UTF-8（中文 Windows 导出的 CSV 多为 GB18030）
        val utf8 = try {
            val dec = Charsets.UTF_8.newDecoder()
            dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            ""
        }
        if (utf8.isNotEmpty() && utf8.count { it == '\uFFFD' } == 0) return utf8
        val gb = try {
            Charset.forName("GB18030").decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            ""
        }
        if (gb.isNotEmpty()) return gb
        return String(bytes, Charsets.UTF_8)
    }

    /** 解析 CSV/TSV 文本：自动识别分隔符，支持引号内的分隔符与换行 */
    fun parseCsv(text: String): List<List<String>> {
        val content = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val delimiter = detectDelimiter(content)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < content.length) {
            val ch = content[i]
            when {
                ch == '"' -> {
                    if (inQuotes && i + 1 < content.length && content[i + 1] == '"') {
                        cell.append('"')
                        i += 2
                        continue
                    }
                    inQuotes = !inQuotes
                }
                ch == delimiter && !inQuotes -> {
                    row.add(cell.toString().trim())
                    cell.setLength(0)
                }
                ch == '\n' && !inQuotes -> {
                    row.add(cell.toString().trim())
                    cell.setLength(0)
                    rows.add(row)
                    row = mutableListOf()
                    if (rows.size >= MAX_ROWS) break
                }
                else -> cell.append(ch)
            }
            i++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row.add(cell.toString().trim())
            rows.add(row)
        }
        return rows.filter { r -> r.any { it.isNotEmpty() } }
    }

    private fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().take(12).joinToString("\n")
        val tabs = sample.count { it == '\t' }
        val commas = sample.count { it == ',' }
        val semis = sample.count { it == ';' }
        return when {
            tabs >= commas && tabs >= semis && tabs > 0 -> '\t'
            semis > commas -> ';'
            else -> ','
        }
    }

    // ------------------------------------------------------------------ XLSX

    fun readXlsx(bytes: ByteArray): TimetableGrid.Sheet {
        val entries = readZipEntries(bytes)
        val shared = parseSharedStrings(entries["xl/sharedStrings.xml"])
        val sheetName = chooseSheet(entries)
            ?: throw IllegalArgumentException("这个 xlsx 里没有找到工作表")
        val sheetXml = entries[sheetName]
            ?: throw IllegalArgumentException("这个 xlsx 里没有找到工作表数据")
        return TimetableGrid.Sheet(parseSheetXml(sheetXml, shared))
    }

    private fun readZipEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && (
                        name == "xl/sharedStrings.xml" ||
                            name.startsWith("xl/worksheets/") ||
                            name == "xl/workbook.xml"
                        )
                ) {
                    val buf = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        val n = zip.read(chunk)
                        if (n <= 0) break
                        buf.write(chunk, 0, n)
                        if (buf.size() > MAX_ENTRY_BYTES) break
                    }
                    out[name] = buf.toByteArray()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return out
    }

    private fun chooseSheet(entries: Map<String, ByteArray>): String? {
        val sheets = entries.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.sorted()
        if (sheets.isEmpty()) return null
        // 优先 sheet1.xml（Excel 默认第一张表），否则按文件名排序取第一张
        return sheets.firstOrNull { it == "xl/worksheets/sheet1.xml" } ?: sheets.first()
    }

    private fun parseSharedStrings(xml: ByteArray?): List<String> {
        if (xml == null) return emptyList()
        val doc = parseXml(xml) ?: return emptyList()
        val out = mutableListOf<String>()
        val sis = doc.getElementsByTagName("si")
        for (i in 0 until sis.length) {
            val el = sis.item(i) as? Element ?: continue
            out.add(textOf(el))
        }
        return out
    }

    /** 拼接节点下所有 <t> 的文本（兼容富文本 <r><t>） */
    private fun textOf(el: Element): String {
        val sb = StringBuilder()
        val ts = el.getElementsByTagName("t")
        for (i in 0 until ts.length) {
            val n = ts.item(i)
            sb.append(n.textContent.orEmpty())
        }
        if (sb.isEmpty()) sb.append(el.textContent.orEmpty())
        return sb.toString()
    }

    private fun parseSheetXml(xml: ByteArray, shared: List<String>): List<List<String>> {
        val doc = parseXml(xml) ?: throw IllegalArgumentException("工作表 XML 解析失败")
        val rowsEl = doc.getElementsByTagName("row")
        val grid = mutableListOf<MutableList<String>>()
        var maxCol = 0
        for (r in 0 until rowsEl.length) {
            if (r >= MAX_ROWS) break
            val rowEl = rowsEl.item(r) as? Element ?: continue
            val cellsEl = rowEl.getElementsByTagName("c")
            val row = mutableListOf<String>()
            for (c in 0 until cellsEl.length) {
                val cEl = cellsEl.item(c) as? Element ?: continue
                val ref = cEl.getAttribute("r")
                val col = columnIndex(ref)
                while (row.size < col) row.add("")
                val type = cEl.getAttribute("t")
                var value = ""
                val vEl = cEl.getElementsByTagName("v").item(0)
                val isEl = cEl.getElementsByTagName("is").item(0) as? Element
                when {
                    type == "s" -> {
                        val idx = vEl?.textContent?.trim()?.toIntOrNull() ?: -1
                        value = shared.getOrElse(idx) { "" }
                    }
                    type == "inlineStr" -> value = isEl?.let { textOf(it) } ?: ""
                    type == "str" -> value = vEl?.textContent.orEmpty()
                    type == "b" -> value = if (vEl?.textContent?.trim() == "1") "TRUE" else "FALSE"
                    else -> value = vEl?.textContent.orEmpty()
                }
                value = value.trim()
                while (row.size <= col) row.add("")
                row[col] = value
                if (col + 1 > maxCol) maxCol = col + 1
            }
            if (row.any { it.isNotEmpty() }) grid.add(row)
        }
        // 合并单元格：只把「纵向合并」的值往下填（常见于跨 2 节的一门课）；
        // 横向合并（多半是标题/表头横跨几列）不再复制，否则整行标题会重复出现在预览里。
        val merges = doc.getElementsByTagName("mergeCell")
        for (i in 0 until merges.length) {
            val el = merges.item(i) as? Element ?: continue
            val ref = el.getAttribute("ref")
            val parts = ref.split(":")
            if (parts.size != 2) continue
            val (r1, c1) = cellCoord(parts[0]) ?: continue
            val (r2, c2) = cellCoord(parts[1]) ?: continue
            val topLeft = grid.getOrNull(r1)?.getOrNull(c1) ?: continue
            if (topLeft.isEmpty()) continue
            if (r2 <= r1 || c2 != c1) continue
            for (rr in (r1 + 1)..r2) {
                while (grid.size <= rr) grid.add(mutableListOf())
                val row = grid[rr]
                while (row.size <= c1) row.add("")
                if (row[c1].isEmpty()) row[c1] = topLeft
            }
        }
        return grid.map { it.toMutableList() }
    }

    private fun parseXml(bytes: ByteArray): org.w3c.dom.Document? = try {
        val factory = DocumentBuilderFactory.newInstance()
        // 注意：Android 自带的 DOM 解析器不支持 Xerces 专有 feature，
        // 直接 setFeature 会抛 ParserConfigurationException 导致整份 xlsx 解析失败（JVM 单测发现不了）。
        factory.isNamespaceAware = false
        runCatching {
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        runCatching { factory.isExpandEntityReferences = true }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    } catch (e: Exception) {
        null
    }

    /** "B12" → (row=11, col=1) */
    private fun cellCoord(ref: String): Pair<Int, Int>? {
        val m = Regex("""^([A-Za-z]+)(\d+)$""").find(ref.trim()) ?: return null
        val col = columnIndex(ref)
        val row = (m.groupValues[2].toIntOrNull() ?: return null) - 1
        if (row < 0 || col < 0) return null
        return row to col
    }

    /** "AB3" → 27 */
    private fun columnIndex(ref: String): Int {
        val letters = ref.takeWhile { it.isLetter() }
        if (letters.isEmpty()) return 0
        var n = 0
        for (ch in letters.uppercase()) {
            n = n * 26 + (ch - 'A' + 1)
        }
        return (n - 1).coerceAtMost(MAX_COLS)
    }

}
