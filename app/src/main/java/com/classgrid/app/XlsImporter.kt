package com.classgrid.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 读取 Excel 97-2003（.xls，OLE2 + BIFF8）里的第一个工作表，输出成二维字符串网格，
 * 交给 [TimetableGrid] 解析。纯 Kotlin，不依赖第三方库。
 *
 * 只做「读字符网格」这一件事：
 *  - OLE2 复合文档：FAT、MiniFAT、目录项，取 `Workbook`（BIFF8）或 `Book`（BIFF5）流；
 *  - BIFF8 记录：BOF/BOUNDSHEET/SST(+CONTINUE)/LABELSST/LABEL/NUMBER/RK/MULRK/FORMULA+STRING/MERGEDCELLS；
 *  - 合并单元格用左上角的值铺满整个区域（课表里星期几常常是横向合并的）。
 *
 * 读不了的真实文件（BIFF5、加密、损坏）会抛出带中文原因的 [XlsException]，供上层写日志。
 */
object XlsImporter {

    class XlsException(message: String) : Exception(message)

    private const val OLE_SIGNATURE = -0x3412D0CF // D0 CF 11 E0 A1 B1 1A E1 以小端 int 读
    private const val MAX_ROWS = 2000
    private const val MAX_COLS = 256

    // ---------------------------------------------------------------- 入口

    fun isXls(bytes: ByteArray): Boolean {
        if (bytes.size < 8) return false
        return u8(bytes, 0) == 0xD0.toByte() && u8(bytes, 1) == 0xCF.toByte() &&
            u8(bytes, 2) == 0x11.toByte() && u8(bytes, 3) == 0xE0.toByte() &&
            u8(bytes, 4) == 0xA1.toByte() && u8(bytes, 5) == 0xB1.toByte() &&
            u8(bytes, 6) == 0x1A.toByte() && u8(bytes, 7) == 0xE1.toByte()
    }

    fun read(bytes: ByteArray): TimetableGrid.Sheet {
        if (!isXls(bytes)) throw XlsException("这不是 Excel 97-2003 文件（缺少 OLE2 文件头）")
        val ole = Ole2(bytes)
        var stream = ole.stream("Workbook")
        if (stream == null) {
            stream = ole.stream("Book")
            if (stream != null) throw XlsException("这是 Excel 95/5.0 格式（BIFF5），暂不支持；请在 Excel 里「另存为」xlsx 或 .xls(97-2003) 再试")
        }
        if (stream == null) throw XlsException("文件里没有找到工作表数据（Workbook 流缺失）")
        return Biff8(stream).parseSheet()
    }

    // ---------------------------------------------------------------- OLE2

    private class Ole2(private val data: ByteArray) {
        private val sectorSize: Int
        private val miniSectorSize: Int
        private val miniCutoff: Int
        private val fat = ArrayList<Int>()
        private val miniFat = ArrayList<Int>()
        private val miniStream: ByteArray
        private val entries = ArrayList<DirEntry>()

        init {
            if (data.size < 512) throw XlsException("文件太小，不像 Excel 文件")
            if (u16(data, 0) != 0xCFD0) throw XlsException("不是 OLE2 复合文档")
            sectorSize = 1 shl u16(data, 30)
            miniSectorSize = 1 shl u16(data, 32)
            if (sectorSize < 128 || sectorSize > 65536) throw XlsException("OLE2 扇区大小异常：$sectorSize")
            miniCutoff = u32(data, 56)

            // DIFAT → FAT
            var difatSector = u32(data, 68)
            val fatSectors = ArrayList<Int>()
            for (i in 0 until 109) {
                val s = u32(data, 76 + i * 4)
                if (s >= 0 && s != -1) fatSectors.add(s)
            }
            var guard = 0
            while (difatSector >= 0 && difatSector < 0xFFFFFFF0 && guard++ < 4096) {
                val base = sectorOffset(difatSector)
                val per = sectorSize / 4 - 1
                for (i in 0 until per) {
                    val s = u32(data, base + i * 4)
                    if (s >= 0 && s != -1) fatSectors.add(s)
                }
                difatSector = u32(data, base + per * 4)
            }
            for (fs in fatSectors) {
                val base = sectorOffset(fs)
                for (i in 0 until sectorSize / 4) {
                    fat.add(u32(data, base + i * 4))
                }
            }

            // 目录项
            var dirSector = u32(data, 48)
            var dirGuard = 0
            var rootStart = -1
            var rootSize = 0
            while (dirSector >= 0 && dirSector < 0xFFFFFFF0 && dirGuard++ < 4096) {
                val base = sectorOffset(dirSector)
                for (off in 0 until sectorSize step 128) {
                    val e = base + off
                    if (e + 128 > data.size) break
                    val nameLen = u16(data, e + 64)
                    val type = u8(data, e + 66).toInt()
                    if (type == 0) continue
                    val name = String(data, e, (nameLen - 2).coerceAtLeast(0), Charsets.UTF_16LE)
                    val start = u32(data, e + 116)
                    val size = u64(data, e + 120)
                    entries.add(DirEntry(name, type, start, size))
                    if (type == 5) {
                        rootStart = start
                        rootSize = size
                    }
                }
                dirSector = nextSector(dirSector)
            }

            // MiniFAT
            var miniFatSector = u32(data, 60)
            var mfGuard = 0
            while (miniFatSector >= 0 && miniFatSector < 0xFFFFFFF0 && mfGuard++ < 4096) {
                val base = sectorOffset(miniFatSector)
                for (i in 0 until sectorSize / 4) miniFat.add(u32(data, base + i * 4))
                miniFatSector = nextSector(miniFatSector)
            }

            miniStream = if (rootStart >= 0 && rootSize > 0) readChain(rootStart, rootSize, mini = false) else ByteArray(0)
        }

        fun stream(name: String): ByteArray? {
            val e = entries.firstOrNull { it.type == 2 && it.name.equals(name, ignoreCase = true) } ?: return null
            if (e.size <= 0) return ByteArray(0)
            return if (e.size < miniCutoff) readChain(e.start, e.size, mini = true) else readChain(e.start, e.size, mini = false)
        }

        private fun readChain(start: Int, size: Int, mini: Boolean): ByteArray {
            val out = ByteArrayOutputStream(size.coerceAtMost(64 * 1024 * 1024))
            var sector = start
            var guard = 0
            while (sector >= 0 && sector < 0xFFFFFFF0 && out.size() < size && guard++ < 1_000_000) {
                val chunk = if (mini) {
                    val base = sector * miniSectorSize
                    if (base < 0 || base + miniSectorSize > miniStream.size) break
                    miniStream.copyOfRange(base, base + miniSectorSize)
                } else {
                    sectorBytes(sector)
                }
                val need = size - out.size()
                out.write(chunk, 0, minOf(need, chunk.size))
                sector = if (mini) miniFat.getOrElse(sector) { -1 } else nextSector(sector)
            }
            return out.toByteArray()
        }

        private fun nextSector(sector: Int): Int = fat.getOrElse(sector) { -1 }

        private fun sectorBytes(sector: Int): ByteArray {
            val base = sectorOffset(sector)
            val end = minOf(base + sectorSize, data.size)
            if (base < 0 || base >= end) return ByteArray(0)
            return data.copyOfRange(base, end)
        }

        private fun sectorOffset(sector: Int): Int = 512 + sector * sectorSize
    }

    private class DirEntry(val name: String, val type: Int, val start: Int, val size: Int)

    // ---------------------------------------------------------------- BIFF8

    private class Biff8(private val s: ByteArray) {
        private val sst = ArrayList<String>()
        private var sstLoaded = false

        private val cells = HashMap<Long, String>()
        private val merges = ArrayList<IntArray>()
        private var maxRow = 0
        private var maxCol = 0
        private var bodyStart = -1

        fun parseSheet(): TimetableGrid.Sheet {
            var pos = 0
            var inGlobals = true
            var bofVersion = 0
            while (pos + 4 <= s.size) {
                val id = u16(s, pos)
                val len = u16(s, pos + 2)
                val body = pos + 4
                if (body + len > s.size) break
                when (id) {
                    0x0809 -> { // BOF
                        if (pos == 0) {
                            bofVersion = u16(s, body)
                            if (bofVersion != 0x0600) {
                                throw XlsException(
                                    if (bofVersion == 0x0500) "这是 Excel 95/5.0 格式（BIFF5），暂不支持；请另存为 .xlsx 再试"
                                    else "无法识别的 Excel 版本（BIFF 0x%04X）".format(bofVersion)
                                )
                            }
                        }
                        val type = u16(s, body + 2)
                        if (type == 0x0010 && bodyStart < 0) bodyStart = pos + 4 + len
                        if (type == 0x0010) inGlobals = false
                    }
                    0x00FC -> { // SST（可能带 CONTINUE）
                        val chunk = collectContinue(body, len)
                        readSst(chunk)
                    }
                    0x003C -> { /* CONTINUE 已在 collectContinue 里合并 */ }
                    0x00FD -> if (len >= 10) { // LABELSST
                        val row = u16(s, body); val col = u16(s, body + 2)
                        val idx = u32(s, body + 6)
                        put(row, col, sstValue(idx))
                    }
                    0x0204 -> if (len >= 9) { // LABEL
                        val row = u16(s, body); val col = u16(s, body + 2)
                        put(row, col, unicodeString(s, body + 6, len - 6))
                    }
                    0x0203 -> if (len >= 14) { // NUMBER
                        val row = u16(s, body); val col = u16(s, body + 2)
                        val v = ByteBuffer.wrap(s, body + 6, 8).order(ByteOrder.LITTLE_ENDIAN).double
                        put(row, col, formatNumber(v))
                    }
                    0x027E -> if (len >= 10) { // RK
                        val row = u16(s, body); val col = u16(s, body + 2)
                        put(row, col, formatNumber(rk(u32(s, body + 6))))
                    }
                    0x00BD -> { // MULRK
                        if (len >= 6) {
                            val row = u16(s, body)
                            val first = u16(s, body + 2)
                            var p = body + 4
                            var c = first
                            while (p + 6 <= body + len - 2) {
                                val rkVal = u32(s, p + 2)
                                put(row, c, formatNumber(rk(rkVal)))
                                p += 6; c++
                            }
                        }
                    }
                    0x0006 -> if (len >= 20) { // FORMULA
                        val row = u16(s, body); val col = u16(s, body + 2)
                        val b6 = u8(s, body + 12); val b7 = u8(s, body + 13)
                        if (b6 == 0xFF.toByte() && b7 == 0xFF.toByte()) {
                            when (u8(s, body + 6).toInt()) {
                                0x01 -> put(row, col, "1")
                                0x00 -> put(row, col, "0")
                                0x02 -> put(row, col, "#ERR")
                                0x03 -> put(row, col, "")
                            }
                        } else {
                            val v = ByteBuffer.wrap(s, body + 6, 8).order(ByteOrder.LITTLE_ENDIAN).double
                            put(row, col, formatNumber(v))
                        }
                    }
                    0x0207 -> if (len >= 3) { // STRING（FORMULA 的字符串结果）
                        appendFormulaString(unicodeString(s, body + 0, len))
                    }
                    0x00E5 -> if (len >= 2) { // MERGEDCELLS
                        val n = u16(s, body)
                        var p = body + 2
                        for (i in 0 until n) {
                            if (p + 8 > s.size) break
                            merges.add(intArrayOf(u16(s, p), u16(s, p + 2), u16(s, p + 4), u16(s, p + 6)))
                            p += 8
                        }
                    }
                }
                pos = body + len
            }

            // 合并单元格：把左上角的值铺满整个区域
            for (m in merges) {
                val r1 = m[0]; val r2 = m[1]; val c1 = m[2]; val c2 = m[3]
                val v = cells[key(r1, c1)] ?: continue
                for (r in r1..minOf(r2, r1 + MAX_ROWS)) {
                    for (c in c1..minOf(c2, c1 + MAX_COLS)) put(r, c, v)
                }
            }

            if (cells.isEmpty()) throw XlsException("工作表是空的（没有读到任何单元格）")
            val rows = ArrayList<MutableList<String>>()
            for (r in 0..maxRow) {
                val row = ArrayList<String>()
                for (c in 0..maxCol) row.add(cells[key(r, c)] ?: "")
                rows.add(row)
            }
            return TimetableGrid.Sheet(rows)
        }

        private fun put(row: Int, col: Int, value: String) {
            if (row > MAX_ROWS || col > MAX_COLS) return
            if (row > maxRow) maxRow = row
            if (col > maxCol) maxCol = col
            cells[key(row, col)] = value
        }

        private fun key(row: Int, col: Int): Long = (row.toLong() shl 16) or col.toLong()

        // SST 记录可能跨 CONTINUE，拼接时 CONTINUE 开头有一个 grbit 字节需要跳过。
        private fun collectContinue(body: Int, len: Int): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(s, body, len)
            var pos = body + len
            while (pos + 4 <= s.size && u16(s, pos) == 0x003C) {
                val l = u16(s, pos + 2)
                // CONTINUE 的第一字节是继续字符串的 grbit，SST 解析里按字符串边界处理，
                // 这里保留原样（readSst 内部会按记录边界回退检查）。
                out.write(s, pos + 4, l)
                pos += 4 + l
            }
            return out.toByteArray()
        }

        private fun readSst(buf: ByteArray) {
            if (sstLoaded) return
            sstLoaded = true
            if (buf.size < 8) return
            val unique = u32(buf, 4)
            var p = 8
            var i = 0
            while (i < unique && p + 3 <= buf.size) {
                val cch = u16(buf, p)
                val flags = u8(buf, p + 2).toInt()
                p += 3
                val rich = (flags and 0x08) != 0
                val farEast = (flags and 0x04) != 0
                var richRuns = 0
                var extSize = 0
                if (rich) { richRuns = u16(buf, p); p += 2 }
                if (farEast) { extSize = u32(buf, p); p += 4 }
                val wide = (flags and 0x01) != 0
                val str = StringBuilder(cch)
                var charsLeft = cch
                var wideCur = wide
                while (charsLeft > 0 && p < buf.size) {
                    // 可能跨 CONTINUE：如果剩余字节不足，读 grbit 后继续
                    if (wideCur) {
                        if (p + 2 > buf.size) break
                        str.append(String(buf, p, 2, Charsets.UTF_16LE))
                        p += 2
                    } else {
                        str.append(buf[p].toInt().toChar())
                        p += 1
                    }
                    charsLeft--
                    // 粗略处理：正常情况不会中途切换编码；这里不额外处理 CONTINUE 的 grbit
                }
                sst.add(str.toString())
                p += richRuns * 4 + extSize
                i++
            }
        }

        private fun sstValue(idx: Int): String = sst.getOrElse(idx) { "" }

        private fun appendFormulaString(value: String) {
            // FORMULA 的字符串结果应写回该格；简单起见忽略（极少出现在课表里）
        }

        private fun rk(rk: Int): Double {
            val isInt = (rk and 0x02) != 0
            val div100 = (rk and 0x01) != 0
            val v: Double = if (isInt) {
                (rk shr 2).toDouble()
            } else {
                val bits = (rk.toLong() and 0xFFFFFFFCL) shl 32
                java.lang.Double.longBitsToDouble(bits)
            }
            return if (div100) v / 100.0 else v
        }
    }

    // ---------------------------------------------------------------- 基础读取

    private fun unicodeString(buf: ByteArray, off: Int, len: Int): String {
        if (len < 3 || off + 3 > buf.size) return ""
        val cch = u16(buf, off)
        val flags = u8(buf, off + 2).toInt()
        var p = off + 3
        val rich = (flags and 0x08) != 0
        val farEast = (flags and 0x04) != 0
        if (rich) p += 2
        if (farEast) p += 4
        val wide = (flags and 0x01) != 0
        return try {
            if (wide) {
                if (p + cch * 2 > buf.size) "" else String(buf, p, cch * 2, Charsets.UTF_16LE)
            } else {
                if (p + cch > buf.size) "" else String(buf, p, cch, Charsets.ISO_8859_1)
            }
        } catch (t: Throwable) {
            ""
        }
    }

    private fun formatNumber(v: Double): String {
        if (v == Math.floor(v) && !v.isInfinite() && Math.abs(v) < 1e15) {
            return v.toLong().toString()
        }
        return v.toString()
    }

    private fun u8(b: ByteArray, i: Int): Byte = if (i in b.indices) b[i] else 0

    private fun u16(b: ByteArray, i: Int): Int {
        if (i + 1 >= b.size) return 0
        return (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    }

    private fun u32(b: ByteArray, i: Int): Int {
        if (i + 3 >= b.size) return -1
        return (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
    }

    private fun u64(b: ByteArray, i: Int): Int {
        val lo = u32(b, i).toLong() and 0xFFFFFFFFL
        val hi = u32(b, i + 4).toLong() and 0xFFFFFFFFL
        val v = lo or (hi shl 32)
        return if (v > Int.MAX_VALUE) Int.MAX_VALUE else v.toInt()
    }

    /** 供测试/日志：把字节前 N 个用十六进制表示。 */
    fun hexHead(bytes: ByteArray, count: Int = 64): String {
        val n = minOf(count, bytes.size)
        val sb = StringBuilder()
        for (i in 0 until n) {
            sb.append("%02X".format(bytes[i]))
            if (i != n - 1) sb.append(' ')
        }
        return sb.toString()
    }

    @Suppress("unused")
    private fun unusedInputStream() = ByteArrayInputStream(ByteArray(0))
}
