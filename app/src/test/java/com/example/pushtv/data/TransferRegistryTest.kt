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
        assertEquals(TransferState.COMPLETED, transfers.getValue("first").state)
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

        registry.updateProgress("first", "first.apk", 50)
        registry.complete("first")
        registry.remove("first")

        assertEquals(listOf("second"), registry.transfers.value.map { it.id })
    }

    @Test
    fun clampsProgressAndRecordsFailure() {
        val registry = TransferRegistry()
        registry.start("download", "app.apk")
        registry.updateProgress("download", "app.apk", 120)
        registry.fail("download", "network error")

        val transfer = registry.transfers.value.single()
        assertEquals(100, transfer.progress)
        assertEquals("network error", transfer.errorMessage)
        assertEquals(TransferState.FAILED, transfer.state)
    }

    @Test
    fun rejectsDuplicateActiveTransferAndAllowsRetryAfterFailure() {
        val registry = TransferRegistry()

        assertTrue(registry.start("download", "app.apk"))
        assertFalse(registry.start("download", "app.apk"))
        registry.fail("download", "network error")
        assertTrue(registry.start("download", "app.apk"))

        assertEquals(TransferState.QUEUED, registry.transfers.value.single().state)
        assertEquals(null, registry.transfers.value.single().errorMessage)
    }

    @Test
    fun terminalStateCannotBeOverwrittenByLateProgress() {
        val registry = TransferRegistry()
        registry.start("download", "app.apk")
        registry.updateProgress("download", "app.apk", 40)
        registry.cancel("download")

        registry.updateProgress("download", "app.apk", 90)
        registry.complete("download")

        val transfer = registry.transfers.value.single()
        assertEquals(TransferState.CANCELED, transfer.state)
        assertEquals(40, transfer.progress)
    }

    @Test
    fun delayedCompletedRemovalDoesNotRemoveRestartedTransfer() {
        val registry = TransferRegistry()
        registry.start("download", "app.apk")
        registry.updateProgress("download", "app.apk", 100)
        registry.complete("download")

        assertTrue(registry.start("download", "app.apk"))
        registry.removeIfState("download", TransferState.COMPLETED)

        val transfer = registry.transfers.value.single()
        assertEquals(TransferState.QUEUED, transfer.state)
    }
}
