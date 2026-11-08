package com.example.phoneperf.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.phoneperf.R
import kotlin.math.min

/**
 * 环形仪表盘：显示 0~100 的百分比，中间是大号数字。
 * 纯自绘，无需第三方图表库。
 */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var value: Float = 0f
        set(v) {
            val target = v.coerceIn(0f, 1f)
            ValueAnimator.ofFloat(field, target).apply {
                duration = 450
                addUpdateListener {
                    field = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

    var label: String = ""
        set(v) { field = v; invalidate() }

    var caption: String = ""
        set(v) { field = v; invalidate() }

    private var accent: Int = ContextCompat.getColor(context, R.color.accent)
    private var warnColor: Int = ContextCompat.getColor(context, R.color.warn)
    private var dangerColor: Int = ContextCompat.getColor(context, R.color.danger)
    private var trackColor: Int = ContextCompat.getColor(context, R.color.track)
    private var textColor: Int = ContextCompat.getColor(context, R.color.text_primary)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()
    private val startAngle = 135f
    private val sweepTotal = 270f

    init {
        trackPaint.strokeWidth = 14f * resources.displayMetrics.density
        arcPaint.strokeWidth = 14f * resources.displayMetrics.density
        trackPaint.color = trackColor
        unitPaint.color = textColor
        captionPaint.color = ContextCompat.getColor(context, R.color.text_secondary)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = min(measuredWidth, measuredHeight)
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = trackPaint.strokeWidth / 2 + 4f
        rect.set(pad, pad, width - pad, height - pad)
        canvas.drawArc(rect, startAngle, sweepTotal, false, trackPaint)

        val color = when {
            value >= 0.9f -> dangerColor
            value >= 0.75f -> warnColor
            else -> accent
        }
        arcPaint.color = color
        canvas.drawArc(rect, startAngle, sweepTotal * value, false, arcPaint)

        val cx = width / 2f
        val cy = height / 2f
        valuePaint.color = textColor
        valuePaint.textSize = width * 0.26f
        canvas.drawText("${(value * 100).toInt()}", cx, cy + valuePaint.textSize * 0.28f, valuePaint)

        if (label.isNotEmpty()) {
            unitPaint.textSize = width * 0.12f
            canvas.drawText(label, cx, cy + valuePaint.textSize * 0.28f + unitPaint.textSize * 1.5f, unitPaint)
        }
        if (caption.isNotEmpty()) {
            captionPaint.textSize = width * 0.09f
            canvas.drawText(caption, cx, cy - valuePaint.textSize * 0.55f, captionPaint)
        }
    }
}
