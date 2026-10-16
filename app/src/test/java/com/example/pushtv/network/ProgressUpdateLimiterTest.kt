package com.example.pushtv.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressUpdateLimiterTest {
    @Test
    fun emitsFirstUpdateThenWaitsForTimeOrOnePercent() {
        val limiter = ProgressUpdateLimiter(minIntervalMillis = 100L, minPercentStep = 1)
        val total = 10_000L

        assertTrue(limiter.shouldEmit(currentBytes = 10L, totalBytes = total, nowMillis = 0L))
        assertFalse(limiter.shouldEmit(currentBytes = 50L, totalBytes = total, nowMillis = 50L))
        assertTrue(limiter.shouldEmit(currentBytes = 110L, totalBytes = total, nowMillis = 60L))
        assertFalse(limiter.shouldEmit(currentBytes = 150L, totalBytes = total, nowMillis = 120L))
        assertTrue(limiter.shouldEmit(currentBytes = 160L, totalBytes = total, nowMillis = 160L))
    }

    @Test
    fun alwaysEmitsTheFirstCompletedUpdateOnlyOnce() {
        val limiter = ProgressUpdateLimiter(minIntervalMillis = 100L, minPercentStep = 1)

        assertTrue(limiter.shouldEmit(currentBytes = 1_000L, totalBytes = 1_000L, nowMillis = 0L))
        assertFalse(limiter.shouldEmit(currentBytes = 1_000L, totalBytes = 1_000L, nowMillis = 1L))
    }

    @Test
    fun unknownLengthUsesTimeBasedThrottling() {
        val limiter = ProgressUpdateLimiter(minIntervalMillis = 100L, minPercentStep = 1)

        assertTrue(limiter.shouldEmit(currentBytes = 1L, totalBytes = -1L, nowMillis = 0L))
        assertFalse(limiter.shouldEmit(currentBytes = 2L, totalBytes = -1L, nowMillis = 99L))
        assertTrue(limiter.shouldEmit(currentBytes = 3L, totalBytes = -1L, nowMillis = 100L))
    }

    @Test
    fun reducesA512MbTransferFromThousandsOfBlockUpdatesToAboutOneHundred() {
        val limiter = ProgressUpdateLimiter(minIntervalMillis = 100L, minPercentStep = 1)
        val total = 512L * 1024L * 1024L
        val blockSize = 8L * 1024L
        var emitted = 0
        var transferred = blockSize

        while (transferred <= total) {
            if (limiter.shouldEmit(transferred, total, nowMillis = 0L)) emitted++
            transferred += blockSize
        }

        assertTrue("Expected at most 101 progress updates, got $emitted", emitted <= 101)
        assertTrue("Expected final progress update", emitted >= 100)
    }
}
