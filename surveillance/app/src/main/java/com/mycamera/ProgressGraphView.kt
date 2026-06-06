package com.mycamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class ProgressGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var fullData: List<Float> = emptyList()
    private var fullLabels: List<String> = emptyList()
    
    // Zoom levels: 5, 7, 15 days, and "Month (Weekly Summary)"
    private val zoomLevels = listOf(5, 7, 15, 31)
    private var currentZoomIndex = 0 // Default to 5 days
    
    private var scrollOffset = 0f 
    private val todayStr = SimpleDateFormat("dd/MM", Locale.getDefault()).format(Calendar.getInstance().time)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val todayDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22FFFFFF")
        strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAFFFFFF")
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    private val todayTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700")
        textSize = 26f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            // Disable scrolling in Week-wise month view (it fits on screen)
            if (zoomLevels[currentZoomIndex] == 31) return false
            
            scrollOffset -= distanceX
            constrainScroll()
            invalidate()
            return true
        }
        override fun onDown(e: MotionEvent): Boolean = true
    })

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            val factor = detector.scaleFactor
            val oldZoom = zoomLevels[currentZoomIndex]
            if (factor > 1.1f) {
                if (currentZoomIndex > 0) currentZoomIndex--
            } else if (factor < 0.9f) {
                if (currentZoomIndex < zoomLevels.size - 1) currentZoomIndex++
            }
            
            if (oldZoom != zoomLevels[currentZoomIndex]) {
                if (zoomLevels[currentZoomIndex] == 31) scrollOffset = 0f
                constrainScroll()
                invalidate()
            }
        }
    })

    fun setData(newData: List<Float>, labels: List<String>) {
        fullData = newData
        fullLabels = labels
        post {
            scrollOffset = -Float.MAX_VALUE
            constrainScroll()
            invalidate()
        }
    }

    private fun constrainScroll() {
        val daysToShow = zoomLevels[currentZoomIndex]
        if (daysToShow == 31) {
            scrollOffset = 0f
            return
        }
        
        val paddingHorizontal = 60f
        val w = width.toFloat() - 2 * paddingHorizontal
        val totalWidth = (fullData.size - 1) * (w / (daysToShow - 1))
        
        val minScroll = if (totalWidth > w) -(totalWidth - w) else 0f
        scrollOffset = max(minScroll, min(0f, scrollOffset))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGestureDetector.onTouchEvent(event)
        if (!scaleGestureDetector.isInProgress) {
            gestureDetector.onTouchEvent(event)
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (fullData.isEmpty()) return

        val daysToShow = zoomLevels[currentZoomIndex]
        if (daysToShow == 31) {
            drawWeekWise(canvas)
        } else {
            drawDayWise(canvas, daysToShow)
        }
    }

    private fun drawDayWise(canvas: Canvas, daysToShow: Int) {
        val paddingHorizontal = 60f
        val paddingTop = 60f
        val paddingBottom = 100f
        val w = width.toFloat() - 2 * paddingHorizontal
        val h = height.toFloat() - paddingTop - paddingBottom
        val stepX = w / (daysToShow - 1)

        for (i in 0..2) {
            val y = paddingTop + h - (i * 0.5f * h)
            canvas.drawLine(paddingHorizontal, y, width.toFloat() - paddingHorizontal, y, gridPaint)
        }

        canvas.save()
        canvas.clipRect(paddingHorizontal - 20, 0f, width.toFloat() - paddingHorizontal + 20, paddingTop + h + 80f)
        canvas.translate(scrollOffset, 0f)

        val path = Path()
        val fillPath = Path()
        for (i in fullData.indices) {
            val x = paddingHorizontal + i * stepX
            val y = paddingTop + h - (fullData[i] * h)
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, paddingTop + h)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            if (i == fullData.size - 1) {
                fillPath.lineTo(x, paddingTop + h)
                fillPath.lineTo(paddingHorizontal, paddingTop + h)
                fillPath.close()
            }
        }

        fillPaint.shader = LinearGradient(0f, paddingTop, 0f, paddingTop + h, Color.parseColor("#44FFFFFF"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        for (i in fullData.indices) {
            val x = paddingHorizontal + i * stepX
            val y = paddingTop + h - (fullData[i] * h)
            val isToday = fullLabels.getOrNull(i) == todayStr
            val p = if (isToday) todayDotPaint else dotPaint
            val tp = if (isToday) todayTextPaint else textPaint
            canvas.drawCircle(x, y, if (isToday) 12f else 8f, p)
            canvas.drawText("${(fullData[i] * 100).toInt()}%", x, y - 30f, tp)
            if (fullLabels.isNotEmpty()) {
                canvas.drawText(if (isToday) "Today" else fullLabels[i], x, paddingTop + h + 45f, tp)
            }
        }
        canvas.restore()
        canvas.drawText("View: $daysToShow Days (Scroll allowed)", width / 2f, height - 15f, textPaint)
    }

    private fun drawWeekWise(canvas: Canvas) {
        val paddingHorizontal = 80f
        val paddingTop = 60f
        val paddingBottom = 100f
        val w = width.toFloat() - 2 * paddingHorizontal
        val h = height.toFloat() - paddingTop - paddingBottom

        // Aggregate data into weeks
        val weekData = mutableListOf<Float>()
        for (i in fullData.indices step 7) {
            val end = min(i + 7, fullData.size)
            val chunk = fullData.subList(i, end)
            weekData.add(chunk.average().toFloat())
        }

        if (weekData.isEmpty()) return
        val stepX = if (weekData.size > 1) w / (weekData.size - 1) else 0f

        for (i in 0..2) {
            val y = paddingTop + h - (i * 0.5f * h)
            canvas.drawLine(paddingHorizontal, y, width.toFloat() - paddingHorizontal, y, gridPaint)
        }

        val path = Path()
        val fillPath = Path()
        for (i in weekData.indices) {
            val x = paddingHorizontal + i * stepX
            val y = paddingTop + h - (weekData[i] * h)
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, paddingTop + h)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
            if (i == weekData.size - 1) {
                fillPath.lineTo(x, paddingTop + h)
                fillPath.lineTo(paddingHorizontal, paddingTop + h)
                fillPath.close()
            }
        }

        fillPaint.shader = LinearGradient(0f, paddingTop, 0f, paddingTop + h, Color.parseColor("#44FFFFFF"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        for (i in weekData.indices) {
            val x = paddingHorizontal + i * stepX
            val y = paddingTop + h - (weekData[i] * h)
            canvas.drawCircle(x, y, 10f, dotPaint)
            canvas.drawText("${(weekData[i] * 100).toInt()}%", x, y - 30f, textPaint)
            canvas.drawText("Week ${i + 1}", x, paddingTop + h + 45f, textPaint)
        }
        canvas.drawText("Monthly View: Weekly Average", width / 2f, height - 15f, textPaint)
    }
}
