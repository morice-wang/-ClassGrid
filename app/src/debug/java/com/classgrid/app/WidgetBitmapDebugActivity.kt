package com.classgrid.app

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 仅 debug 包：把组件渲染结果按大尺寸显示出来，方便截图核对 Canvas 效果。
 *
 * 可通过 intent extra 指定「星期 / 当前分钟」，用来检查「正在上 / 下一节 / 已上完」三种状态：
 *   adb shell am start -n com.classgrid.app/.WidgetBitmapDebugActivity --ei weekday 2 --ei minutes 530
 */
class WidgetBitmapDebugActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        // 尺寸可调：--ei widthDp 340 --ei heightDp 200，用来核对不同格位下的排版
        val widthDp = intent.getIntExtra("widthDp", 360).coerceIn(110, 640)
        val heightDp = intent.getIntExtra("heightDp", 160).coerceIn(90, 640)
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()

        val weekday = intent.getIntExtra("weekday", 0).takeIf { it in 1..7 }
            ?: TodayWidgetData.weekdayOf(java.util.Calendar.getInstance())
        val minutes = intent.getIntExtra("minutes", -1).takeIf { it >= 0 }
            ?: localMinutes()
        val week = Prefs.currentWeek(this)
        val sections = SectionTimes.load(this)
        val snapshot = TodayWidgetData.build(
            CourseStore.load(this), week, weekday, minutes, sections, TodayWidgetData.MAX_ROWS
        )

        // v1.48.6：卡片底 + 内容层，跟桌面上两张 ImageView 的叠法一致，合成一张方便看
        val dark = ScheduleColor.isDarkMode(this)
        val bitmap: Bitmap = run {
            val card = TodayWidgetRenderer.card(this, widthPx, heightPx, dark)
            val content = TodayWidgetRenderer.content(this, snapshot, widthPx, heightPx, dark)
            val composed = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(composed)
            canvas.drawBitmap(card, 0f, 0f, null)
            canvas.drawBitmap(content, 0f, 0f, null)
            composed
        }

        // v1.47.0 调试用：对渲染结果做一次 OCR，把每行文字和坐标打到 logcat，
        // 便于在没有视觉工具时确认「文字有没有叠在一起、有没有被裁掉」。
        runCatching {
            val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
                com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions.Builder().build()
            )
            val big = Bitmap.createScaledBitmap(bitmap, bitmap.width * 2, bitmap.height * 2, true)
            recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(big, 0))
                .addOnSuccessListener { result ->
                    android.util.Log.i("WidgetDebug", "=== widget ocr ===")
                    for (block in result.textBlocks) {
                        for (line in block.lines) {
                            val b = line.boundingBox
                            android.util.Log.i(
                                "WidgetDebug",
                                "${line.text} | " +
                                    "x=${b?.left}..${b?.right} y=${b?.top}..${b?.bottom}"
                            )
                        }
                    }
                    android.util.Log.i("WidgetDebug", "=== widget ocr end ===")
                }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF888888.toInt())
            setPadding(12, 40, 12, 12)
        }
        root.addView(
            TextView(this).apply {
                text = "render ${widthPx}x${heightPx}px · 周$weekday ${minutes / 60}:%02d".format(minutes % 60)
                textSize = 12f
                setTextColor(0xFF222222.toInt())
            }
        )
        root.addView(
            ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams(widthPx, heightPx).apply { topMargin = 16 }
        )
        setContentView(root)
    }

    private fun localMinutes(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }
}
