package com.classgrid.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.io.File
import java.io.FileOutputStream

/**
 * cover 缩放比例：图片铺满取景框（超出部分裁掉）。
 *
 * 预览 [BgCropActivity.BgCropView.onDraw] 与保存 [BgCropActivity.saveCropped] 共用同一比例，
 * 修复原实现「保存用 minOf(fit)、预览用 maxOf(cover)」导致的取景错位
 * （AUDIT P1 BgCropActivity.kt:152）：横图/长截图作为背景时，保存结果与预览不一致、无法通过拖动修正。
 */
private fun coverScale(frameW: Float, frameH: Float, bmpW: Int, bmpH: Int): Float =
    maxOf(frameW / bmpW.toFloat(), frameH / bmpH.toFloat())

/**
 * 内置背景裁剪页：图片等比显示后固定一个 9:20 竖屏裁剪框（与课表区域比例一致），
 * 单指拖动图片调整显示区域，确认后把框内部分保存到 App 私有目录，
 * 不依赖系统裁剪组件、不依赖相册临时权限，杜绝"设置了却没显示"。
 */
class BgCropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
        private const val MAX_DIM = 1600
        private const val FRAME_W = 9
        private const val FRAME_H = 20
    }

    private lateinit var cropView: BgCropView
    private var bitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val uriStr = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uriStr.isNullOrEmpty()) {
            finish()
            return
        }
        bitmap = decodeDownsampled(android.net.Uri.parse(uriStr))
        if (bitmap == null) {
            Toast.makeText(this, "图片读取失败，请重试", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        cropView = BgCropView(this).apply { setImage(bitmap!!) }

        val title = TextView(this).apply {
            text = "调整背景区域（拖动图片，框内为课表背景）"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(16, 12, 16, 12)
        }
        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE6222936.toInt())
            addView(title)
        }

        val btnCancel = TextView(this).apply {
            text = "取消"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 18, 0, 18)
            setBackgroundColor(0xE6222936.toInt())
        }
        val btnOk = TextView(this).apply {
            text = "使用此区域"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 18, 0, 18)
            setBackgroundColor(AppTheme.primary(this@BgCropActivity, ScheduleColor.isDarkMode(this@BgCropActivity)))
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnCancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btnOk, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F131C.toInt())
            addView(titleBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(cropView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
            addView(btnRow, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        setContentView(root)

        // 状态栏沉浸：给标题栏加顶部 inset 内边距
        ViewCompat.setOnApplyWindowInsetsListener(titleBar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            insets
        }

        btnCancel.setOnClickListener { finish() }
        btnOk.setOnClickListener {
            if (saveCropped()) {
                Toast.makeText(this, "背景已设置", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /** 解码并降采样到最长边 ≤ MAX_DIM，控制内存 */
    private fun decodeDownsampled(uri: android.net.Uri): Bitmap? {
        return try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            var dim = maxOf(bounds.outWidth, bounds.outHeight)
            while (dim / (sample * 2) >= MAX_DIM) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        }
    }

    /** 按裁剪框截取并保存到私有目录 files/bg/background.jpg，返回是否成功 */
    private fun saveCropped(): Boolean {
        val src = bitmap ?: return false
        val frame = cropView.frame()
        val fw = frame.width().toFloat()
        val fh = frame.height().toFloat()
        // 与预览 onDraw 共用 cover 比例（原为 minOf/fit，导致保存取景与预览不一致）
        val scale = coverScale(fw, fh, src.width, src.height)
        val sx = ((-cropView.offsetX) / scale).toInt().coerceIn(0, src.width - 1)
        val sy = ((-cropView.offsetY) / scale).toInt().coerceIn(0, src.height - 1)
        val sw = (fw / scale).toInt().coerceIn(1, src.width - sx)
        val sh = (fh / scale).toInt().coerceIn(1, src.height - sy)
        return try {
            val cropped = Bitmap.createBitmap(src, sx, sy, sw, sh)
            val dir = File(filesDir, "bg")
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, "background.jpg")
            FileOutputStream(out).use { fos ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 90, fos)
            }
            if (cropped != src) cropped.recycle()
            Prefs.setBackgroundImage(this, out.absolutePath)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 裁剪预览视图：固定 9:20 竖屏裁剪框 + 蒙层 + 单指拖动图片 */
    class BgCropView(context: android.content.Context) : View(context) {

        private var bitmap: Bitmap? = null
        private var imgScale = 1f
        private var imgW = 0f
        private var imgH = 0f

        /** 图片相对裁剪框左上角的偏移（px，恒 ≤0，图片左上不越过框左上） */
        var offsetX = 0f
        var offsetY = 0f
        private var downX = 0f
        private var downY = 0f
        private var startX = 0f
        private var startY = 0f
        private var firstDraw = true

        private val maskPaint = Paint().apply {
            color = 0x99000000.toInt()
        }
        private val borderPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = 0xFFFFFFFF.toInt()
        }
        private val imgPaint = Paint().apply {
            isFilterBitmap = true
        }

        fun setImage(bmp: Bitmap) {
            bitmap = bmp
            requestLayout()
            invalidate()
        }

        /** 裁剪框（9:20，居中，占可用宽度的 100%，高度不超过视图高度 90%） */
        fun frame(): RectF {
            val w = width.toFloat()
            val h = height.toFloat()
            val ratio = FRAME_W.toFloat() / FRAME_H
            var fw = w
            var fh = fw / ratio
            if (fh > h * 0.92f) {
                fh = h * 0.92f
                fw = fh * ratio
            }
            return RectF((w - fw) / 2f, (h - fh) / 2f, (w + fw) / 2f, (h + fh) / 2f)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bmp = bitmap ?: return
            val frame = frame()
            // cover：图片铺满裁剪框（超出部分裁掉），横图/竖图都不会出现大片留白。
            // 与保存共用 coverScale，保证「预览所见 = 保存所得」
            imgScale = coverScale(frame.width(), frame.height(), bmp.width, bmp.height)
            imgW = bmp.width * imgScale
            imgH = bmp.height * imgScale
            // 限制图片偏移：图片须始终盖住裁剪框
            val minX = frame.width() - imgW
            val minY = frame.height() - imgH
            if (offsetX < minX) offsetX = minX
            if (offsetX > 0f) offsetX = 0f
            if (offsetY < minY) offsetY = minY
            if (offsetY > 0f) offsetY = 0f
            // 首次绘制：图片居中（默认显示中间区域），随后由用户拖动选择
            if (firstDraw) {
                offsetX = minX / 2f
                offsetY = minY / 2f
                firstDraw = false
            }

            val dst = RectF(
                frame.left + offsetX,
                frame.top + offsetY,
                frame.left + offsetX + imgW,
                frame.top + offsetY + imgH
            )
            canvas.drawBitmap(bmp, null, dst, imgPaint)

            // 蒙层（框外压暗）
            canvas.drawRect(0f, 0f, width.toFloat(), frame.top, maskPaint)
            canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), maskPaint)
            canvas.drawRect(0f, frame.top, frame.left, frame.bottom, maskPaint)
            canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, maskPaint)

            // 裁剪框边框 + 四角
            canvas.drawRect(frame, borderPaint)
            val len = frame.width() * 0.06f
            val corner = Paint(borderPaint)
            corner.strokeWidth = 6f
            val l = frame.left
            val t = frame.top
            val r = frame.right
            val b = frame.bottom
            // 左上
            canvas.drawLine(l, t, l + len, t, corner)
            canvas.drawLine(l, t, l, t + len, corner)
            // 右上
            canvas.drawLine(r, t, r - len, t, corner)
            canvas.drawLine(r, t, r, t + len, corner)
            // 左下
            canvas.drawLine(l, b, l + len, b, corner)
            canvas.drawLine(l, b, l, b - len, corner)
            // 右下
            canvas.drawLine(r, b, r - len, b, corner)
            canvas.drawLine(r, b, r, b - len, corner)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    startX = offsetX
                    startY = offsetY
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    offsetX = startX + (event.x - downX)
                    offsetY = startY + (event.y - downY)
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
