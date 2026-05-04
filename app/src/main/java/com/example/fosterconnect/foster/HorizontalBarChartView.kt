package com.example.fosterconnect.foster

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Lightweight horizontal bar chart. Each bar is one row with a label on the left,
 * the bar in the middle, and the value text at the end of the bar.
 */
class HorizontalBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Bar(val label: String, val value: Float, val valueLabel: String? = null)

    private var bars: List<Bar> = emptyList()
    private var maxValue: Float = 1f

    private val density = resources.displayMetrics.density
    private val rowHeight = 36f * density
    private val rowPadding = 6f * density
    private val labelWidth = 110f * density
    private val valuePadding = 6f * density

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6750A4.toInt()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 13f * density
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 12f * density
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE7E0EC.toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val rows = bars.size.coerceAtLeast(1)
        val height = (rows * (rowHeight + rowPadding) + rowPadding).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return

        val barAreaLeft = paddingLeft + labelWidth
        val barAreaRight = (width - paddingRight - 56f * density).coerceAtLeast(barAreaLeft + 1)
        val barAreaWidth = barAreaRight - barAreaLeft

        var top = paddingTop + rowPadding
        for (bar in bars) {
            val center = top + rowHeight / 2f
            // label
            val textY = center - (labelPaint.descent() + labelPaint.ascent()) / 2f
            val labelText = ellipsize(bar.label, labelWidth - valuePadding, labelPaint)
            canvas.drawText(labelText, paddingLeft.toFloat(), textY, labelPaint)

            // track
            val barTop = top + rowPadding
            val barBottom = top + rowHeight - rowPadding
            canvas.drawRoundRect(
                RectF(barAreaLeft, barTop, barAreaRight, barBottom),
                6f * density, 6f * density, trackPaint
            )

            // bar fill
            val ratio = (bar.value / maxValue).coerceIn(0f, 1f)
            val fillRight = barAreaLeft + barAreaWidth * ratio
            canvas.drawRoundRect(
                RectF(barAreaLeft, barTop, fillRight, barBottom),
                6f * density, 6f * density, barPaint
            )

            // value label
            val valueText = bar.valueLabel ?: formatValue(bar.value)
            canvas.drawText(valueText, fillRight + valuePadding, textY, valuePaint)

            top += rowHeight + rowPadding
        }
    }

    private fun ellipsize(text: String, maxWidth: Float, paint: Paint): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + ellipsis) > maxWidth) {
            end--
        }
        return text.substring(0, end) + ellipsis
    }

    private fun formatValue(v: Float): String =
        if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v)
}
