package com.example.phoneperf.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.phoneperf.R

/**
 * 折线图：多条同量纲(0~1 或 0~100)曲线，带渐变填充与网格。
 * 用于展示 CPU / 内存 / 温度 的实时走势。
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    class Series(
        val data: List<Float>,
        val color: Int,
        val fill: Boolean = false
    )

    var series: List<Series> = emptyList()
        set(value) { field = value; invalidate() }

    /** 纵轴最大值，数据超出时自动跟随。 */
    var maxValue: Float = 1f
        set(value) { field = value; invalidate() }

    var unitLabel: String = ""

    private val density = resources.displayMetrics.density
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = ContextCompat.getColor(context, R.color.track)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density
        color = ContextCompat.getColor(context, R.color.text_tertiary)
    }

    private val path = Path()
    private val fillPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val padLeft = 34f * density
        val padRight = 6f * density
        val padTop = 8f * density
        val padBottom = 16f * density
        val w = width - padLeft - padRight
        val h = height - padTop - padBottom
        if (w <= 0 || h <= 0) return

        // 横向网格 + 刻度
        val steps = 4
        for (i in 0..steps) {
            val y = padTop + h * i / steps
            canvas.drawLine(padLeft, y, width - padRight, y, gridPaint)
            val v = maxValue * (1f - i.toFloat() / steps)
            val label = if (maxValue <= 1.05f) "${(v * 100).toInt()}" else String.format("%.0f", v)
            canvas.drawText(label, 4f * density, y + 4f * density, labelPaint)
        }
        if (unitLabel.isNotEmpty()) {
            canvas.drawText(unitLabel, padLeft, height - 3f * density, labelPaint)
        }

        val maxPoints = series.maxOfOrNull { it.data.size } ?: 0
        if (maxPoints < 2) return

        series.forEach { s ->
            if (s.data.size < 2) return@forEach
            path.reset()
            fillPath.reset()
            val stepX = w / (maxPoints - 1)
            s.data.forEachIndexed { i, raw ->
                val v = (raw / maxValue).coerceIn(0f, 1f)
                val x = padLeft + stepX * i
                val y = padTop + h * (1f - v)
                if (i == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, padTop + h)
                    fillPath.lineTo(x, y)
                } else {
                    // 以相邻两点的中点做二次贝塞尔，得到平滑曲线
                    val prevV = (s.data[i - 1] / maxValue).coerceIn(0f, 1f)
                    val prevX = padLeft + stepX * (i - 1)
                    val prevY = padTop + h * (1f - prevV)
                    val midX = (prevX + x) / 2f
                    val midY = (prevY + y) / 2f
                    path.quadTo(prevX, prevY, midX, midY)
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
            }
            fillPath.lineTo(padLeft + stepX * (s.data.size - 1), padTop + h)
            fillPath.close()

            if (s.fill) {
                fillPaint.shader = LinearGradient(
                    0f, padTop, 0f, padTop + h,
                    (s.color and 0x00FFFFFF) or 0x55000000,
                    (s.color and 0x00FFFFFF),
                    Shader.TileMode.CLAMP
                )
                canvas.drawPath(fillPath, fillPaint)
                fillPaint.shader = null
            }
            linePaint.color = s.color
            canvas.drawPath(path, linePaint)
        }
    }
}
