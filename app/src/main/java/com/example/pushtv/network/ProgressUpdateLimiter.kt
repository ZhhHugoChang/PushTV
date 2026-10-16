package com.example.pushtv.network

internal class ProgressUpdateLimiter(
    private val minIntervalMillis: Long = 100L,
    private val minPercentStep: Int = 1
) {
    private var lastEmittedAtMillis: Long? = null
    private var lastEmittedPercent: Int? = null

    fun shouldEmit(
        currentBytes: Long,
        totalBytes: Long,
        nowMillis: Long = System.nanoTime() / 1_000_000L
    ): Boolean {
        val percent = if (totalBytes > 0L) {
            ((currentBytes.toDouble() / totalBytes.toDouble()) * 100.0)
                .toInt()
                .coerceIn(0, 100)
        } else {
            null
        }
        val previousTime = lastEmittedAtMillis
        val previousPercent = lastEmittedPercent
        val firstUpdate = previousTime == null
        val intervalReached = previousTime != null && nowMillis - previousTime >= minIntervalMillis
        val percentReached = percent != null &&
            (previousPercent == null || percent - previousPercent >= minPercentStep)
        val completedForFirstTime = percent == 100 && previousPercent != 100

        if (!firstUpdate && !intervalReached && !percentReached && !completedForFirstTime) {
            return false
        }

        lastEmittedAtMillis = nowMillis
        if (percent != null) lastEmittedPercent = percent
        return true
    }
}
