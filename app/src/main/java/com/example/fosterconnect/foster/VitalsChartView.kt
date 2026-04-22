package com.example.fosterconnect.foster

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.example.fosterconnect.history.EventEntry
import com.example.fosterconnect.history.EventType
import com.example.fosterconnect.history.StoolEntry
import com.example.fosterconnect.history.WeightEntry
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class VitalsChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class WeightPoint(val dayIndex: Int, val weight: Float, val dayOfMonth: Int, val dateMillis: Long)
    data class StoolPoint(val dayIndex: Int, val level: Int, val dayOfMonth: Int, val dateMillis: Long)
    data class EventPoint(val dayIndex: Int, val type: EventType)

    private var rawWeights: List<WeightEntry> = emptyList()
    private var rawStools: List<StoolEntry> = emptyList()
    private var rawEvents: List<EventEntry> = emptyList()

    private var weightData: List<WeightPoint> = emptyList()
    private var stoolData: List<StoolPoint> = emptyList()
    private var eventData: List<EventPoint> = emptyList()
    private var allDayLabels: List<Pair<Int, Int>> = emptyList() // (dayIndex, dayOfMonth)
    private var totalPoints = 14
    private var birthdayMillis: Long? = null

    private val colorWeight = Color.parseColor("#B83A3A")
    private val colorExpected = Color.parseColor("#52735A")
    private val colorInkMuted = Color.parseColor("#6E7A73")
    private val colorLine = Color.parseColor("#E4E8E2")
    private val colorStool = Color.parseColor("#D4851F")
    private val colorStoolBand = Color.parseColor("#52735A")

    private val padLeft = 40f
    private val padRight = 32f
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
    private val paintStoolLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorStool; strokeWidth = 3f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    }
    private val paintStoolSolid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorStool; strokeWidth = 3f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val paintStoolBand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorStoolBand; alpha = 15; style = Paint.Style.FILL
    }

    fun setBirthdayMillis(millis: Long?) {
        birthdayMillis = millis
        invalidate()
    }

    fun setWeightEntries(entries: List<WeightEntry>) {
        rawWeights = entries
        rebuildTimeline()
        invalidate()
    }

    fun setStoolEntries(entries: List<StoolEntry>) {
        rawStools = entries
        rebuildTimeline()
        invalidate()
    }

    fun setEventEntries(entries: List<EventEntry>) {
        rawEvents = entries
        rebuildTimeline()
        invalidate()
    }

    private fun dayKey(millis: Long, cal: Calendar): Int {
        cal.timeInMillis = millis
        return cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
    }

    private fun rebuildTimeline() {
        val cal = Calendar.getInstance()

        // Group weight entries by day, keep last per day
        val weightPerDay = if (rawWeights.isNotEmpty()) {
            rawWeights.sortedBy { it.dateMillis }.groupBy { dayKey(it.dateMillis, cal) }
                .mapValues { (_, v) -> v.last() }
        } else emptyMap()

        // Group stool entries by day, keep last per day
        val stoolPerDay = if (rawStools.isNotEmpty()) {
            rawStools.sortedBy { it.dateMillis }.groupBy { dayKey(it.dateMillis, cal) }
                .mapValues { (_, v) -> v.last() }
        } else emptyMap()

        // Group events by day, dedup by type (one per type per day)
        val eventPerDay = if (rawEvents.isNotEmpty()) {
            rawEvents.sortedBy { it.dateMillis }.groupBy { dayKey(it.dateMillis, cal) }
                .mapValues { (_, v) -> v.distinctBy { it.type } }
        } else emptyMap()

        // Build unified sorted day keys, keep last 14
        val allDayKeys = (weightPerDay.keys + stoolPerDay.keys + eventPerDay.keys).sorted()
        val recentDays = allDayKeys.takeLast(min(allDayKeys.size, 14))
        val dayIndexMap = recentDays.withIndex().associate { (i, key) -> key to i }

        totalPoints = max(recentDays.size, 2)

        // Build day labels for X axis
        allDayLabels = recentDays.mapIndexed { i, key ->
            val millis = weightPerDay[key]?.dateMillis
                ?: stoolPerDay[key]?.dateMillis
                ?: eventPerDay[key]!!.first().dateMillis
            cal.timeInMillis = millis
            i to cal.get(Calendar.DAY_OF_MONTH)
        }

        // Map weight data to unified indices
        weightData = recentDays.mapNotNull { key ->
            val entry = weightPerDay[key] ?: return@mapNotNull null
            cal.timeInMillis = entry.dateMillis
            WeightPoint(
                dayIndex = dayIndexMap[key]!!,
                weight = entry.weightGrams,
                dayOfMonth = cal.get(Calendar.DAY_OF_MONTH),
                dateMillis = entry.dateMillis
            )
        }

        // Map stool data to unified indices
        stoolData = recentDays.mapNotNull { key ->
            val entry = stoolPerDay[key] ?: return@mapNotNull null
            cal.timeInMillis = entry.dateMillis
            StoolPoint(
                dayIndex = dayIndexMap[key]!!,
                level = entry.level,
                dayOfMonth = cal.get(Calendar.DAY_OF_MONTH),
                dateMillis = entry.dateMillis
            )
        }

        // Map event data to unified indices (deduped by type per day)
        eventData = recentDays.flatMap { key ->
            val entries = eventPerDay[key] ?: return@flatMap emptyList()
            val idx = dayIndexMap[key]!!
            entries.map { EventPoint(dayIndex = idx, type = it.type) }
        }
    }

    fun setPlaceholderData() {
        val cal = Calendar.getInstance()
        val weights = listOf(280f, 295f, 305f, 310f, 302f, 308f, 325f, 340f, 355f, 365f, 378f, 385f, 395f, 400f)
        rawWeights = weights.mapIndexed { i, w ->
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -(weights.size - 1 - i))
            WeightEntry(cal.timeInMillis, w)
        }
        val stools = listOf(
            3,
            4,
            4,
            5,
            4
        )
        rawStools = listOf(1,3,4,5,7).mapIndexed { i, s ->
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -(s))
            StoolEntry(cal.timeInMillis, stools[i])
        }

        rawEvents = listOf(4, 10).map { daysAgo ->
            cal.timeInMillis = System.currentTimeMillis()
            cal.add(Calendar.DAY_OF_YEAR, -daysAgo)
            EventEntry(cal.timeInMillis, EventType.VOMITING)
        }

        birthdayMillis = rawWeights.first().dateMillis - 21L * 24 * 60 * 60 * 1000
        rebuildTimeline()
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
        val pr = if (stoolData.isNotEmpty()) padRight * dp else padRight / 2f * dp
        val pt = padTop * dp
        val pb = padBottom * dp
        val iw = w - pl - pr
        val ih = h - pt - pb

        if (weightData.isEmpty() && stoolData.isEmpty() && eventData.isEmpty()) {
            paintText.textSize = 12f * dp
            paintText.textAlign = Paint.Align.CENTER
            canvas.drawText("No data yet", w / 2, h / 2, paintText)
            paintText.textAlign = Paint.Align.LEFT
            return
        }

        val singlePoint = totalPoints <= 1
        fun xAt(index: Int): Float = if (singlePoint) pl + iw / 2f
            else pl + (index.toFloat() / (totalPoints - 1)) * iw

        // Compute expected band values for each weight data point
        val bday = birthdayMillis
        val msPerDay = 24L * 60 * 60 * 1000
        val bandData: List<Pair<Float, Float>?> = if (bday != null) {
            weightData.map { p ->
                val ageDays = (p.dateMillis - bday).toFloat() / msPerDay
                expectedRange(ageDays)
            }
        } else emptyList()

        // Y-axis scaling (left axis — weight)
        if (weightData.isNotEmpty()) {
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

            // Grid lines and left Y-axis labels
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
                    val (_, r) = validBand[0]
                    canvas.drawRect(pl, yW(r.second), pl + iw, yW(r.first), paintExpBand)
                    canvas.drawLine(pl, yW(r.second), pl + iw, yW(r.second), paintExpLine)
                    canvas.drawLine(pl, yW(r.first), pl + iw, yW(r.first), paintExpLine)
                } else if (validBand.size >= 2) {
                    val bandPath = Path()
                    validBand.forEachIndexed { j, (i, pair) ->
                        val x = xAt(weightData[i].dayIndex)
                        if (j == 0) bandPath.moveTo(x, yW(pair.second)) else bandPath.lineTo(x, yW(pair.second))
                    }
                    validBand.reversed().forEach { (i, pair) ->
                        bandPath.lineTo(xAt(weightData[i].dayIndex), yW(pair.first))
                    }
                    bandPath.close()
                    canvas.drawPath(bandPath, paintExpBand)

                    val upperPath = Path()
                    val lowerPath = Path()
                    validBand.forEachIndexed { j, (i, pair) ->
                        val x = xAt(weightData[i].dayIndex)
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
                val x = xAt(p.dayIndex)
                if (i == 0) weightPath.moveTo(x, yW(p.weight)) else weightPath.lineTo(x, yW(p.weight))
            }
            canvas.drawPath(weightPath, paintWeight)

            // Weight dots
            weightData.forEachIndexed { i, p ->
                val x = xAt(p.dayIndex)
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

        // Right Y-axis — stool (fixed 1–7 scale)
        if (stoolData.isNotEmpty()) {
            val stoolMin = 1f
            val stoolMax = 7f
            fun yS(v: Float): Float = pt + ih - ((v - stoolMin) / (stoolMax - stoolMin)) * ih

            // Right Y-axis labels
            paintText.textSize = 8f * dp
            paintText.textAlign = Paint.Align.LEFT
            for (level in 1..7) {
                val y = yS(level.toFloat())
                canvas.drawText("$level", pl + iw + 4 * dp, y + 3 * dp, paintText)
            }

            // Stool line — solid between consecutive day indices, dashed for gaps
            for (i in 0 until stoolData.size - 1) {
                val p0 = stoolData[i]
                val p1 = stoolData[i + 1]
                val x0 = xAt(p0.dayIndex)
                val y0 = yS(p0.level.toFloat())
                val x1 = xAt(p1.dayIndex)
                val y1 = yS(p1.level.toFloat())
                val consecutive = p1.dayIndex - p0.dayIndex == 1
                canvas.drawLine(x0, y0, x1, y1, if (consecutive) paintStoolSolid else paintStoolLine)
            }

            // Stool dots
            stoolData.forEachIndexed { i, p ->
                val x = xAt(p.dayIndex)
                val y = yS(p.level.toFloat())
                val isLast = i == stoolData.lastIndex
                if (isLast) {
                    paintDot.color = colorStool; paintDot.alpha = 46
                    canvas.drawCircle(x, y, 7f * dp, paintDot)
                }
                paintDot.color = colorStool; paintDot.alpha = 255
                canvas.drawCircle(x, y, if (isLast) 3.5f * dp else 2.25f * dp, paintDot)
            }
        }

        // Event dots at the bottom of the chart area
        if (eventData.isNotEmpty()) {
            val eventY = pt + ih - 4f * dp
            eventData.forEach { ep ->
                val x = xAt(ep.dayIndex)
                paintDot.color = Color.parseColor(ep.type.colorHex)
                paintDot.alpha = 255
                canvas.drawCircle(x, eventY, 3.5f * dp, paintDot)
            }
        }

        // X axis labels from unified timeline
        paintText.textAlign = Paint.Align.CENTER
        paintText.textSize = 8f * dp
        val labelInterval = max(1, totalPoints / 7)
        for (i in allDayLabels.indices) {
            if (i % labelInterval != 0 && i != allDayLabels.lastIndex) continue
            val (dayIndex, dayOfMonth) = allDayLabels[i]
            canvas.drawText("$dayOfMonth", xAt(dayIndex), h - 6 * dp, paintText)
        }
    }
}
