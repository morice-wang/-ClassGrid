package com.classgrid.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognizer
import java.util.concurrent.TimeUnit

/**
 * 整周课表截图 → 单元格网格。
 *
 * 为什么需要它：整周课表截图里，一格往往只有 8~10 像素高的字（一门课 4 行信息挤在 60px 高的格子里）。
 * 对整张图做一次 OCR，小字会糊成「1-163」「第08节」这类错字，表头「一 11/2」也常认错，
 * 于是表头识别不到、整张课表被判为「没识别出课程」。
 *
 * 这里换一种做法：先在位图上找出表格线，切出每个单元格，把单元格放大到字高约 40px 再单独 OCR。
 * 每格只认几个字，准确率高得多；单元格坐标天然就是「第几列 = 周几、第几行 = 第几节」，
 * 也不需要再从文字位置猜行列。
 *
 * 认不出表格线（不是网格截图）时返回 null，调用方回退到原来的 [GridParser] 流程。
 */
object TableGridOcr {

    private const val MAX_SIDE = 1400          // 找线用的缩放上限（像素）
    private const val DARK_THRESHOLD = 200     // 灰度低于它的算「有内容/线」（浅灰表格线也能抓到）
    private const val V_LINE_RATIO = 0.45f     // 一列里暗像素占比 ≥ 它 → 竖线
    private const val H_LINE_RATIO = 0.45f     // 一行里暗像素占比 ≥ 它 → 横线
    private const val MIN_CELL = 18            // 小于它的格子忽略（表格线粗、噪点）
    private const val CELL_UPSCALE = 40f       // 单元格内文字目标高度
    private const val MAX_CELLS = 120          // 一张截图最多识别多少格，防卡死
    private const val CELL_TIMEOUT_MS = 4000L

    /** 上一次识别的失败原因（写进识别日志，方便定位是哪一步没成） */
    @Volatile
    var lastNote: String = ""
        private set

    /** 识别结果：行优先的单元格文本；不是网格时返回 null */
    fun read(recognizer: TextRecognizer, source: Bitmap): List<List<String>>? {
        val bitmap = source
        if (bitmap.width < 120 || bitmap.height < 120) {
            lastNote = "图片太小（${bitmap.width}x${bitmap.height}）"
            return null
        }
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(bitmap.width, bitmap.height))
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, w, h, true) else bitmap
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)

        // 表格线有深有浅（浅灰 #DDD 的线很常见），按多个阈值各找一次，取线最多的一组
        var bestLines: Pair<List<Int>, List<Int>>? = null
        var bestThreshold = 0
        for (threshold in intArrayOf(200, 226, 242)) {
            val ys0 = detectLines(pixels, w, h, vertical = false, band = null, threshold = threshold)
            val xs0 = if (ys0.size >= 4) {
                detectLines(pixels, w, h, vertical = true, band = ys0.first()..ys0.last(), threshold = threshold)
            } else {
                detectLines(pixels, w, h, vertical = true, band = null, threshold = threshold)
            }
            val cur = xs0 to ys0
            val best = bestLines
            if (best == null || (cur.first.size >= 3 && cur.second.size >= 4 && cur.first.size + cur.second.size >
                    best.first.size + best.second.size)
            ) {
                bestLines = cur
                bestThreshold = threshold
            }
            if (cur.first.size >= 3 && cur.second.size >= 4) break
        }
        val xs = bestLines?.first ?: emptyList()
        val ys = bestLines?.second ?: emptyList()
        // 一张课表至少 3 列（节次列 + 2 天）与 4 行（表头 + 3 节）
        if (xs.size < 3 || ys.size < 4) {
            lastNote = "没找到表格线：竖线 ${xs.size} 条、横线 ${ys.size} 条（阈值 $bestThreshold）"
            if (small !== bitmap) small.recycle()
            return null
        }
        lastNote = "表格线：竖 ${xs.size}、横 ${ys.size}（阈值 $bestThreshold）"

        val rowCount = ys.size - 1
        val colCount = xs.size - 1
        val rows = MutableList(rowCount) { MutableList(colCount) { "" } }
        var used = 0
        for (r in 0 until rowCount) {
            for (c in 0 until colCount) {
                if (used >= MAX_CELLS) break
                val left = (xs[c] / scale).toInt()
                val top = (ys[r] / scale).toInt()
                val right = (xs[c + 1] / scale).toInt()
                val bottom = (ys[r + 1] / scale).toInt()
                val cw = right - left
                val ch = bottom - top
                if (cw < MIN_CELL || ch < MIN_CELL) continue
                val text = readCell(recognizer, bitmap, left, top, right, bottom)
                used++
                rows[r][c] = text
            }
        }
        if (small !== bitmap) small.recycle()
        val filled = rows.sumOf { row -> row.count { it.isNotBlank() } }
        lastNote = "$lastNote；识别了 $used 格，其中 $filled 格有文字"
        return rows
    }

    /** 单独识别一个单元格：裁出来 → 放大到字高约 40px → OCR */
    private fun readCell(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): String {
        val pad = 2
        val l = (left + pad).coerceIn(0, bitmap.width - 1)
        val t = (top + pad).coerceIn(0, bitmap.height - 1)
        val r = (right - pad).coerceIn(l + 1, bitmap.width)
        val b = (bottom - pad).coerceIn(t + 1, bitmap.height)
        val cell = try {
            Bitmap.createBitmap(bitmap, l, t, r - l, b - t)
        } catch (e: Throwable) {
            return ""
        }
        // 放大：一门课在格子里通常是 4 行小字，按行数估算放大倍数，让每行约 40px
        val targetScale = (CELL_UPSCALE * 3f / cell.height).coerceIn(1f, 4f)
        val scaled = if (targetScale > 1.05f) {
            Bitmap.createScaledBitmap(
                cell,
                (cell.width * targetScale).toInt().coerceAtLeast(1),
                (cell.height * targetScale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            cell
        }
        if (scaled !== cell) cell.recycle()
        return try {
            val image = InputImage.fromBitmap(scaled, 0)
            val task = recognizer.process(image)
            val result = Tasks.await(task, CELL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // 单元格里的多行文本保留换行，交给 TimetableGrid 按行拆字段
            result.textBlocks.flatMap { block -> block.lines }.joinToString("\n") { it.text.trim() }
                .trim()
        } catch (e: Throwable) {
            ""
        } finally {
            scaled.recycle()
        }
    }

    /**
     * 投影法找表格线：返回线的坐标（缩放后像素），已合并相邻像素。
     * band 不为空时只在该区间内统计（竖线用：截图上方页眉、下方白边不该拉低占比）。
     */
    private fun detectLines(
        pixels: IntArray,
        w: Int,
        h: Int,
        vertical: Boolean,
        band: IntRange?,
        threshold: Int = DARK_THRESHOLD
    ): List<Int> {
        val len = if (vertical) w else h
        val start = band?.first?.coerceIn(0, (if (vertical) h else w) - 1) ?: 0
        val end = band?.last?.coerceIn(start, (if (vertical) h else w) - 1) ?: ((if (vertical) h else w) - 1)
        val span = end - start + 1
        val score = FloatArray(len)
        for (i in 0 until len) {
            var dark = 0
            for (j in start..end) {
                val p = if (vertical) pixels[j * w + i] else pixels[i * w + j]
                val gray = (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
                if (gray < threshold) dark++
            }
            score[i] = dark.toFloat() / span
        }
        val ratio = if (vertical) V_LINE_RATIO else H_LINE_RATIO
        val lines = mutableListOf<Int>()
        var i = 0
        while (i < len) {
            if (score[i] >= ratio) {
                var sum = 0
                var n = 0
                var j = i
                while (j < len && score[j] >= ratio) {
                    sum += j
                    n++
                    j++
                }
                lines.add(sum / n)
                i = j
            } else {
                i++
            }
        }
        // 去掉贴边的误检（截图边框/滚动条）
        return lines.filter { it > 2 && it < len - 3 }
            // 缩放/锐化会把一条线糊成两三条，先合并 6px 内的相邻线
            .let { raw ->
                val merged = mutableListOf<Int>()
                for (l in raw) {
                    if (merged.isNotEmpty() && l - merged.last() <= 6) {
                        merged[merged.size - 1] = (merged.last() + l) / 2
                    } else {
                        merged.add(l)
                    }
                }
                merged
            }
            // 两条线太近（行高 < 25px）说明是文字行或噪点，丢弃后者，避免切出碎格
            .let { raw ->
                val spaced = mutableListOf<Int>()
                for (l in raw) {
                    if (spaced.isEmpty() || l - spaced.last() >= 25) spaced.add(l)
                }
                spaced
            }
    }
}
