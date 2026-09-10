package com.sleepguard.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * 整夜鼾声/呼吸暂停强度曲线（蜗牛睡眠同款“声音曲线”的轻量实现）。
 * 数据点：(时间占比 0..1, 强度 0..1)。以填充面积图呈现，不引入任何图表库。
 */
class SnoreCurveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#F6D9A0")
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#EF9F27")
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#E6ECF3")
    }
    private var points: List<Pair<Float, Float>> = emptyList()

    fun setData(pts: List<Pair<Float, Float>>) {
        points = pts
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 10f
        val plotW = (w - pad * 2).coerceAtLeast(1f)
        val plotH = (h - pad * 2).coerceAtLeast(1f)

        for (i in 1..3) {
            val y = pad + plotH * i / 4f
            canvas.drawLine(pad, y, w - pad, y, gridPaint)
        }

        if (points.isEmpty()) return

        val xOf = { xf: Float -> pad + plotW * xf }
        val yOf = { yf: Float -> h - pad - plotH * yf.coerceIn(0f, 1f) }

        val area = Path()
        area.moveTo(pad, h - pad)
        for ((xf, yf) in points) area.lineTo(xOf(xf), yOf(yf))
        area.lineTo(xOf(points.last().first), h - pad)
        area.close()
        canvas.drawPath(area, fillPaint)

        val line = Path()
        points.forEachIndexed { i, (xf, yf) ->
            if (i == 0) line.moveTo(xOf(xf), yOf(yf)) else line.lineTo(xOf(xf), yOf(yf))
        }
        canvas.drawPath(line, linePaint)
    }
}
