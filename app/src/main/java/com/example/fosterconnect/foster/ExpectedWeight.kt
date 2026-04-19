package com.example.fosterconnect.foster

import kotlin.math.min

/**
 * Kitten expected weight table by week of age.
 * Provides interpolated min/avg/max lookups by age in weeks (fractional).
 */
object ExpectedWeight {

    private data class Row(val week: Int, val minG: Float, val avgG: Float, val maxG: Float)

    private val table = listOf(
        Row(0, 85f, 100f, 120f), Row(1, 170f, 200f, 250f),
        Row(2, 230f, 290f, 350f), Row(3, 310f, 380f, 450f),
        Row(4, 370f, 480f, 590f), Row(5, 470f, 570f, 690f),
        Row(6, 540f, 680f, 810f), Row(7, 640f, 780f, 920f),
        Row(8, 750f, 870f, 1050f), Row(9, 840f, 950f, 1150f),
        Row(10, 900f, 1050f, 1250f), Row(11, 970f, 1150f, 1380f),
        Row(12, 1030f, 1250f, 1500f), Row(13, 1090f, 1350f, 1620f),
        Row(14, 1160f, 1450f, 1730f), Row(15, 1250f, 1550f, 1830f),
        Row(16, 1330f, 1650f, 1950f), Row(17, 1420f, 1750f, 2050f),
        Row(18, 1510f, 1820f, 2150f), Row(19, 1590f, 1870f, 2200f),
        Row(20, 1650f, 1900f, 2250f),
    )

    /** Returns interpolated (min, max) for a given age in fractional weeks, or null if out of range. */
    fun rangeAt(ageWeeks: Float): Pair<Float, Float>? {
        if (ageWeeks < 0f || ageWeeks > 20f) return null
        val lower = min(ageWeeks.toInt(), table.size - 2)
        val frac = ageWeeks - lower
        val lo = table[lower]
        val hi = table[lower + 1]
        return (lo.minG + (hi.minG - lo.minG) * frac) to (lo.maxG + (hi.maxG - lo.maxG) * frac)
    }

    /** Returns interpolated min weight for a given age in whole weeks. */
    fun minAt(ageWeeks: Int): Float? {
        return rangeAt(ageWeeks.toFloat())?.first
    }

    /** Returns interpolated avg weight for a given age in fractional weeks. */
    fun avgAt(ageWeeks: Float): Float? {
        if (ageWeeks < 0f || ageWeeks > 20f) return null
        val lower = min(ageWeeks.toInt(), table.size - 2)
        val frac = ageWeeks - lower
        return table[lower].avgG + (table[lower + 1].avgG - table[lower].avgG) * frac
    }
}
