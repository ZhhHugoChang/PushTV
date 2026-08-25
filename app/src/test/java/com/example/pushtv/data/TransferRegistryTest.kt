package com.example.pushtv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferRegistryTest {
    @Test
    fun keepsConcurrentTransfersIndependent() {
        val registry = TransferRegistry()

        registry.start("first", "first.apk")
        registry.start("second", "second.apk")
        registry.updateProgress("first", "first.apk", 40)
        registry.complete("first")

        val transfers = registry.transfers.value.associateBy { it.id }
        assertEquals(2, transfers.size)
        assertTrue(transfers.getValue("first").isComplete)
        assertEquals(100, transfers.getValue("first").progress)
        assertFalse(transfers.getValue("second").isComplete)
        assertEquals(0, transfers.getValue("second").progress)
        assertEquals(listOf("first", "second"), registry.transfers.value.map { it.id })
    }

    @Test
    fun removingCompletedTransferDoesNotRemoveAnotherTransfer() {
        val registry = TransferRegistry()
        registry.start("first", "first.apk")
        registry.start("second", "second.apk")

        registry.complete("first")
        registry.remove("first")

        assertEquals(listOf("second"), registry.transfers.value.map { it.id })
    }

    @Test
    fun clampsProgressAndRecordsFailure() {
        val registry = TransferRegistry()
        registry.updateProgress("download", "app.apk", 120)
        registry.fail("download", "network error")

        val transfer = registry.transfers.value.single()
        assertEquals(100, transfer.progress)
        assertEquals("network error", transfer.errorMessage)
    }
}
