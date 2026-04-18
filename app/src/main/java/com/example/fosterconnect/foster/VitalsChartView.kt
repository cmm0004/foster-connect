package com.example.fosterconnect.foster

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.fosterconnect.R
import com.example.fosterconnect.history.WeightEntry
import kotlin.math.max
import kotlin.math.min

/**
 * Custom view that renders a multi-series vitals chart matching the clinical dashboard design.
 * Shows weight trend line with expected band, stool score line (placeholder),
 * and event markers (placeholder).
 */
class VitalsChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class WeightPoint(val day: Int, val weight: Float, val expLow: Float, val expHigh: Float)
    data class StoolPoint(val day: Int, val score: Float)
    data class EventMarker(val day: Int, val glyph: String, val kind: String)

    private var weightData: List<WeightPoint> = emptyList()
    private var stoolData: List<StoolPoint> = emptyList()
    private var events: List<EventMarker> = emptyList()
    private var totalDays = 14

    // Colors from clinical palette
    private val colorWeight = Color.parseColor("#B83A3A")
    private val colorExpected = Color.parseColor("#52735A")
    private val colorStool = Color.parseColor("#C08A20")
    private val colorEvent = Color.parseColor("#B83A3A")
    private val colorInkMuted = Color.parseColor("#6E7A73")
    private val colorLine = Color.parseColor("#E4E8E2")
    private val colorZoneOk = Color.parseColor("#1452735A")
    private val colorZoneWarn = Color.parseColor("#1FC08A20")
    private val colorZoneBad = Color.parseColor("#1AB83A3A")

    private val padLeft = 34f
    private val padRight = 28f
    private val padTop = 16f
    private val padBottom = 26f

    private val paintWeight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorWeight; strokeWidth = 4f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val paintExpBand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorExpected; alpha = 20; style = Paint.Style.FILL
    }
    private val paintExpLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorExpected; alpha = 115; strokeWidth = 2f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4f, 6f), 0f)
    }
    private val paintStool = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorStool; strokeWidth = 3f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; alpha = 217
    }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorLine; strokeWidth = 1f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(2f, 4f), 0f)
    }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintZone = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInkMuted; textSize = 20f; typeface = Typeface.MONOSPACE
    }
    private val paintEventLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorEvent; strokeWidth = 2f; style = Paint.Style.STROKE; alpha = 77
        pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
    }
    private val paintEventGlyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 16f; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    fun setWeightEntries(entries: List<WeightEntry>) {
        if (entries.size < 2) {
            weightData = emptyList()
            invalidate()
            return
        }
        // Generate expected band based on a simple growth model
        val sorted = entries.sortedBy { it.dateMillis }
        val firstWeight = sorted.first().weightGrams
        val points = sorted.mapIndexed { i, e ->
            val expectedGrowth = firstWeight + (i * 15f) // ~15g/day expected
            WeightPoint(
                day = i,
                weight = e.weightGrams,
                expLow = expectedGrowth * 0.9f,
                expHigh = expectedGrowth * 1.1f
            )
        }
        weightData = points
        totalDays = max(points.size, 2)
        invalidate()
    }

    fun setPlaceholderData() {
        // Generate placeholder data matching the design's 14-day example
        weightData = listOf(
            WeightPoint(0, 280f, 275f, 310f), WeightPoint(1, 295f, 285f, 320f),
            WeightPoint(2, 305f, 295f, 330f), WeightPoint(3, 310f, 305f, 340f),
            WeightPoint(4, 302f, 315f, 350f), WeightPoint(5, 308f, 325f, 360f),
            WeightPoint(6, 325f, 335f, 370f), WeightPoint(7, 340f, 345f, 380f),
            WeightPoint(8, 355f, 355f, 390f), WeightPoint(9, 365f, 365f, 400f),
            WeightPoint(10, 378f, 375f, 410f), WeightPoint(11, 385f, 385f, 420f),
            WeightPoint(12, 395f, 395f, 430f), WeightPoint(13, 400f, 405f, 440f),
        )
        stoolData = listOf(
            StoolPoint(0, 4f), StoolPoint(1, 4f), StoolPoint(2, 5f), StoolPoint(3, 6f),
            StoolPoint(4, 7f), StoolPoint(5, 6f), StoolPoint(6, 5f), StoolPoint(7, 5f),
            StoolPoint(8, 4f), StoolPoint(9, 4f), StoolPoint(10, 3f), StoolPoint(11, 4f),
            StoolPoint(12, 4f), StoolPoint(13, 5f),
        )
        events = listOf(
            EventMarker(3, "V", "vomit"), EventMarker(4, "V", "vomit"),
            EventMarker(7, "Rx", "dewormer"), EventMarker(12, "B", "bath"),
        )
        totalDays = 14
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val dp = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        val pl = padLeft * dp
        val pr = padRight * dp
        val pt = padTop * dp
        val pb = padBottom * dp
        val iw = w - pl - pr
        val ih = h - pt - pb

        if (weightData.isEmpty() && stoolData.isEmpty()) {
            paintText.textSize = 12f * dp
            paintText.textAlign = Paint.Align.CENTER
            canvas.drawText("No data yet", w / 2, h / 2, paintText)
            paintText.textAlign = Paint.Align.LEFT
            return
        }

        fun xAt(day: Int): Float = pl + (day.toFloat() / (totalDays - 1)) * iw

        // Stool zone backgrounds
        fun yStool(score: Float): Float = pt + ih - ((score - 1f) / 6f) * ih
        paintZone.color = colorZoneBad
        canvas.drawRect(pl, yStool(7f), pl + iw, yStool(6f), paintZone)
        paintZone.color = colorZoneOk
        canvas.drawRect(pl, yStool(5f), pl + iw, yStool(3f), paintZone)
        paintZone.color = colorZoneWarn
        canvas.drawRect(pl, yStool(2f), pl + iw, yStool(1f), paintZone)

        // Weight Y scale
        if (weightData.isNotEmpty()) {
            val allW = weightData.flatMap { listOf(it.weight, it.expLow, it.expHigh) }
            val wMin = allW.min() * 0.88f
            val wMax = allW.max() * 1.08f
            fun yW(v: Float): Float = pt + ih - ((v - wMin) / (wMax - wMin)) * ih

            // Grid lines
            val ticks = 4
            val step = (wMax - wMin) / ticks
            paintText.textSize = 8f * dp
            paintText.textAlign = Paint.Align.RIGHT
            for (i in 0..ticks) {
                val v = wMin + step * i
                val y = yW(v)
                canvas.drawLine(pl, y, pl + iw, y, paintGrid)
                canvas.drawText("${v.toInt()}", pl - 4 * dp, y + 3 * dp, paintText)
            }

            // Expected band
            val bandPath = Path()
            weightData.forEachIndexed { i, p ->
                val x = xAt(p.day)
                if (i == 0) bandPath.moveTo(x, yW(p.expHigh)) else bandPath.lineTo(x, yW(p.expHigh))
            }
            weightData.reversed().forEach { p ->
                bandPath.lineTo(xAt(p.day), yW(p.expLow))
            }
            bandPath.close()
            canvas.drawPath(bandPath, paintExpBand)

            // Expected lines (dashed)
            val expHighPath = Path()
            val expLowPath = Path()
            weightData.forEachIndexed { i, p ->
                val x = xAt(p.day)
                if (i == 0) { expHighPath.moveTo(x, yW(p.expHigh)); expLowPath.moveTo(x, yW(p.expLow)) }
                else { expHighPath.lineTo(x, yW(p.expHigh)); expLowPath.lineTo(x, yW(p.expLow)) }
            }
            canvas.drawPath(expHighPath, paintExpLine)
            canvas.drawPath(expLowPath, paintExpLine)

            // Weight line
            val weightPath = Path()
            weightData.forEachIndexed { i, p ->
                val x = xAt(p.day)
                if (i == 0) weightPath.moveTo(x, yW(p.weight)) else weightPath.lineTo(x, yW(p.weight))
            }
            canvas.drawPath(weightPath, paintWeight)

            // Weight dots
            weightData.forEachIndexed { i, p ->
                val x = xAt(p.day)
                val y = yW(p.weight)
                val isLast = i == weightData.lastIndex
                if (isLast) {
                    paintDot.color = colorWeight; paintDot.alpha = 46
                    canvas.drawCircle(x, y, 7f * dp, paintDot)
                }
                paintDot.color = colorWeight; paintDot.alpha = 255
                canvas.drawCircle(x, y, if (isLast) 3.5f * dp else 2.25f * dp, paintDot)
            }
        }

        // Stool line
        if (stoolData.isNotEmpty()) {
            val stoolPath = Path()
            stoolData.forEachIndexed { i, p ->
                val x = xAt(p.day)
                val y = yStool(p.score)
                if (i == 0) stoolPath.moveTo(x, y) else stoolPath.lineTo(x, y)
            }
            canvas.drawPath(stoolPath, paintStool)
            stoolData.forEach { p ->
                paintDot.color = colorStool; paintDot.alpha = 217
                canvas.drawCircle(xAt(p.day), yStool(p.score), 2f * dp, paintDot)
            }
        }

        // Stool axis labels (right)
        paintText.textSize = 8f * dp
        paintText.textAlign = Paint.Align.LEFT
        for (s in listOf(1, 3, 5, 7)) {
            canvas.drawText("$s", pl + iw + 4 * dp, yStool(s.toFloat()) + 3 * dp, paintText)
        }

        // Event markers
        events.forEach { e ->
            val x = xAt(e.day)
            val y = pt + 6 * dp
            // Vertical guideline
            canvas.drawLine(x, pt, x, pt + ih, paintEventLine)
            // Halo
            paintDot.color = colorEvent; paintDot.alpha = 38
            canvas.drawCircle(x, y, 7f * dp, paintDot)
            // Dot
            paintDot.color = colorEvent; paintDot.alpha = 255
            canvas.drawCircle(x, y, 3.5f * dp, paintDot)
            // Glyph
            paintEventGlyph.textSize = 6.5f * dp
            canvas.drawText(e.glyph, x, y + 2f * dp, paintEventGlyph)
        }

        // X axis labels
        paintText.textAlign = Paint.Align.CENTER
        paintText.textSize = 8f * dp
        val labelInterval = max(1, totalDays / 7)
        for (d in 0 until totalDays) {
            if (d % labelInterval != 0 && d != totalDays - 1) continue
            canvas.drawText("d${d + 1}", xAt(d), h - 6 * dp, paintText)
        }
    }
}
