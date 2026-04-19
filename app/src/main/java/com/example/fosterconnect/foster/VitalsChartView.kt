package com.example.fosterconnect.foster

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.fosterconnect.history.WeightEntry
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Custom view that renders a weight trend chart for foster animal tracking.
 * Shows the last 14 days of weight data with expected weight band based on age.
 */
class VitalsChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class WeightPoint(val index: Int, val weight: Float, val dayOfMonth: Int, val dateMillis: Long)

    private var weightData: List<WeightPoint> = emptyList()
    private var totalPoints = 14
    private var birthdayMillis: Long? = null

    private val colorWeight = Color.parseColor("#B83A3A")
    private val colorExpected = Color.parseColor("#52735A")
    private val colorInkMuted = Color.parseColor("#6E7A73")
    private val colorLine = Color.parseColor("#E4E8E2")

    private val padLeft = 40f
    private val padRight = 16f
    private val padTop = 16f
    private val padBottom = 26f

    private val paintWeight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorWeight; strokeWidth = 4f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val paintExpBand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorExpected; alpha = 25; style = Paint.Style.FILL
    }
    private val paintExpLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorExpected; alpha = 80; strokeWidth = 1.5f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4f, 6f), 0f)
    }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorLine; strokeWidth = 1f; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(2f, 4f), 0f)
    }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorInkMuted; textSize = 20f; typeface = Typeface.MONOSPACE
    }

    fun setBirthdayMillis(millis: Long?) {
        birthdayMillis = millis
        invalidate()
    }

    fun setWeightEntries(entries: List<WeightEntry>) {
        if (entries.isEmpty()) {
            weightData = emptyList()
            invalidate()
            return
        }
        val sorted = entries.sortedBy { it.dateMillis }
        val cal = Calendar.getInstance()
        // Group by calendar day, keep the most recent entry per day
        val perDay = sorted.groupBy { e ->
            cal.timeInMillis = e.dateMillis
            cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        }.map { (_, dayEntries) -> dayEntries.last() }
        // Keep only the last 14 days
        val recent = perDay.takeLast(min(perDay.size, 14))
        weightData = recent.mapIndexed { i, e ->
            cal.timeInMillis = e.dateMillis
            WeightPoint(index = i, weight = e.weightGrams, dayOfMonth = cal.get(Calendar.DAY_OF_MONTH), dateMillis = e.dateMillis)
        }
        totalPoints = max(weightData.size, 2)
        invalidate()
    }

    fun setPlaceholderData() {
        val cal = Calendar.getInstance()
        val weights = listOf(
            280f,
            295f,
            305f,
            310f,
            302f,
            308f,
            325f,
            340f,
            355f,
            365f,
            378f,
            385f,
            395f,
            400f
        )
        weightData = weights.mapIndexed { i, w ->
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -(weights.size - 1 - i))
            WeightPoint(index = i, weight = w, dayOfMonth = cal.get(Calendar.DAY_OF_MONTH), dateMillis = cal.timeInMillis)
        }
        totalPoints = weights.size
        // Set a placeholder birthday ~3 weeks before first data point
        birthdayMillis = weightData.first().dateMillis - 21L * 24 * 60 * 60 * 1000
        invalidate()
    }

    private fun expectedRange(ageDays: Float): Pair<Float, Float>? {
        return ExpectedWeight.rangeAt(ageDays / 7f)
    }

    private fun niceStep(raw: Float): Float {
        if (raw <= 0f) return 1f
        val mag = 10f.pow(floor(log10(raw)))
        val frac = raw / mag
        return mag * when {
            frac <= 1.5f -> 1f
            frac <= 3.5f -> 2.5f
            frac <= 7.5f -> 5f
            else -> 10f
        }
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

        if (weightData.isEmpty()) {
            paintText.textSize = 12f * dp
            paintText.textAlign = Paint.Align.CENTER
            canvas.drawText("No data yet", w / 2, h / 2, paintText)
            paintText.textAlign = Paint.Align.LEFT
            return
        }

        val singlePoint = weightData.size == 1
        fun xAt(index: Int): Float = if (singlePoint) pl + iw / 2f
            else pl + (index.toFloat() / (totalPoints - 1)) * iw

        // Compute expected band values for each data point
        val bday = birthdayMillis
        val msPerDay = 24L * 60 * 60 * 1000
        val bandData: List<Pair<Float, Float>?> = if (bday != null) {
            weightData.map { p ->
                val ageDays = (p.dateMillis - bday).toFloat() / msPerDay
                expectedRange(ageDays)
            }
        } else emptyList()

        // Y-axis scaling — include both actual weights and expected band in range
        val allValues = mutableListOf<Float>()
        weightData.forEach { allValues.add(it.weight) }
        bandData.filterNotNull().forEach { (lo, hi) -> allValues.add(lo); allValues.add(hi) }

        val dataMin = allValues.min()
        val dataMax = allValues.max()
        val range = if (dataMax - dataMin < 10f) 20f else dataMax - dataMin
        val padding = range * 0.08f
        val rawStep = (range + 2 * padding) / 4f
        val step = niceStep(rawStep)
        val yMin = floor((dataMin - padding) / step) * step
        val yMax = yMin + ceil((dataMax + padding - yMin) / step) * step

        fun yW(v: Float): Float = pt + ih - ((v - yMin) / (yMax - yMin)) * ih

        // Grid lines and Y-axis labels
        paintText.textSize = 8f * dp
        paintText.textAlign = Paint.Align.RIGHT
        var tick = yMin
        while (tick <= yMax + step * 0.01f) {
            val y = yW(tick)
            canvas.drawLine(pl, y, pl + iw, y, paintGrid)
            canvas.drawText("${tick.toInt()}", pl - 4 * dp, y + 3 * dp, paintText)
            tick += step
        }

        // Expected weight band
        if (bandData.size == weightData.size) {
            val validBand = bandData.mapIndexedNotNull { i, pair ->
                pair?.let { i to it }
            }
            if (validBand.size == 1) {
                // Single point — draw flat horizontal band across full width
                val (_, range) = validBand[0]
                canvas.drawRect(pl, yW(range.second), pl + iw, yW(range.first), paintExpBand)
                canvas.drawLine(pl, yW(range.second), pl + iw, yW(range.second), paintExpLine)
                canvas.drawLine(pl, yW(range.first), pl + iw, yW(range.first), paintExpLine)
            } else if (validBand.size >= 2) {
                // Filled band
                val bandPath = Path()
                validBand.forEachIndexed { j, (i, pair) ->
                    val x = xAt(i)
                    if (j == 0) bandPath.moveTo(x, yW(pair.second)) else bandPath.lineTo(x, yW(pair.second))
                }
                validBand.reversed().forEach { (i, pair) ->
                    bandPath.lineTo(xAt(i), yW(pair.first))
                }
                bandPath.close()
                canvas.drawPath(bandPath, paintExpBand)

                // Dashed upper and lower lines
                val upperPath = Path()
                val lowerPath = Path()
                validBand.forEachIndexed { j, (i, pair) ->
                    val x = xAt(i)
                    if (j == 0) {
                        upperPath.moveTo(x, yW(pair.second))
                        lowerPath.moveTo(x, yW(pair.first))
                    } else {
                        upperPath.lineTo(x, yW(pair.second))
                        lowerPath.lineTo(x, yW(pair.first))
                    }
                }
                canvas.drawPath(upperPath, paintExpLine)
                canvas.drawPath(lowerPath, paintExpLine)
            }
        }

        // Weight line
        val weightPath = Path()
        weightData.forEachIndexed { i, p ->
            val x = xAt(p.index)
            if (i == 0) weightPath.moveTo(x, yW(p.weight)) else weightPath.lineTo(x, yW(p.weight))
        }
        canvas.drawPath(weightPath, paintWeight)

        // Weight dots
        weightData.forEachIndexed { i, p ->
            val x = xAt(p.index)
            val y = yW(p.weight)
            val isLast = i == weightData.lastIndex
            if (isLast) {
                paintDot.color = colorWeight; paintDot.alpha = 46
                canvas.drawCircle(x, y, 7f * dp, paintDot)
            }
            paintDot.color = colorWeight; paintDot.alpha = 255
            canvas.drawCircle(x, y, if (isLast) 3.5f * dp else 2.25f * dp, paintDot)
        }

        // X axis labels — actual day-of-month numbers
        paintText.textAlign = Paint.Align.CENTER
        paintText.textSize = 8f * dp
        val labelInterval = max(1, totalPoints / 7)
        for (i in weightData.indices) {
            if (i % labelInterval != 0 && i != weightData.lastIndex) continue
            canvas.drawText("${weightData[i].dayOfMonth}", xAt(i), h - 6 * dp, paintText)
        }
    }
}
