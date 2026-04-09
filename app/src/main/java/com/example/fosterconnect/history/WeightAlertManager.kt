package com.example.fosterconnect.history

import com.example.fosterconnect.foster.Kitten

enum class WeightTrend {
    NORMAL,
    DECLINING,
    DECLINING_AFTER_WARNING
}

object WeightAlertManager {

    private const val CONSECUTIVE_DECLINES_THRESHOLD = 3

    fun evaluate(kitten: Kitten): WeightTrend {
        val entries = kitten.weightEntries
        if (entries.size < CONSECUTIVE_DECLINES_THRESHOLD) return WeightTrend.NORMAL

        val recent = entries.takeLast(CONSECUTIVE_DECLINES_THRESHOLD)
        val allDecliningOrFlat = recent.zipWithNext().all { (older, newer) ->
            newer.weightGrams <= older.weightGrams
        }

        if (!allDecliningOrFlat) return WeightTrend.NORMAL

        return if (kitten.weightDeclineWarned) {
            WeightTrend.DECLINING_AFTER_WARNING
        } else {
            WeightTrend.DECLINING
        }
    }
}
