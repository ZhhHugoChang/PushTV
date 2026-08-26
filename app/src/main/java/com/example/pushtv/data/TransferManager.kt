package com.example.pushtv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.Handler
import android.os.Looper

enum class TransferState {
    QUEUED,
    RESOLVING,
    TRANSFERRING,
    COMPLETED,
    FAILED,
    CANCELED
}

data class TransferProgress(
    val id: String,
    val fileName: String,
    val progress: Int,
    val state: TransferState = TransferState.QUEUED,
    val totalBytes: Long? = null,
    val errorMessage: String? = null
) {
    val isComplete: Boolean get() = state == TransferState.COMPLETED
    val isActive: Boolean get() = state in ACTIVE_STATES

    companion object {
        private val ACTIVE_STATES = setOf(
            TransferState.QUEUED,
            TransferState.RESOLVING,
            TransferState.TRANSFERRING
        )
    }
}

object TransferManager {
    private const val COMPLETED_DISPLAY_MILLIS = 1500L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val registry = TransferRegistry()
    val transfers: StateFlow<List<TransferProgress>> = registry.transfers

    fun startTransfer(id: String, fileName: String): Boolean {
        return registry.start(id, fileName)
    }

    fun markResolving(id: String) {
        registry.changeState(id, TransferState.RESOLVING)
    }

    fun markTransferring(id: String) {
        registry.changeState(id, TransferState.TRANSFERRING)
    }

    fun updateProgress(id: String, fileName: String, progress: Int, totalBytes: Long? = null) {
        registry.updateProgress(id, fileName, progress, totalBytes)
    }

    fun markComplete(id: String) {
        if (registry.complete(id)) {
            mainHandler.postDelayed({ registry.removeIfState(id, TransferState.COMPLETED) }, COMPLETED_DISPLAY_MILLIS)
        }
    }

    fun markFailed(id: String, message: String) {
        registry.fail(id, message)
    }

    fun markCanceled(id: String) {
        registry.cancel(id)
    }

    fun remove(id: String) {
        registry.remove(id)
    }
}

internal class TransferRegistry {
    private val mutableTransfers = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transfers: StateFlow<List<TransferProgress>> = mutableTransfers.asStateFlow()

    @Synchronized
    fun start(id: String, fileName: String): Boolean {
        val transfers = mutableTransfers.value
        if (transfers.find { it.id == id }?.isActive == true) return false
        mutableTransfers.value = transfers.filterNot { it.id == id } + TransferProgress(id, fileName, 0)
        return true
    }

    fun changeState(id: String, state: TransferState) {
        update(id, state) { it.copy(state = state, errorMessage = null) }
    }

    @Synchronized
    fun updateProgress(id: String, fileName: String, progress: Int, totalBytes: Long? = null) {
        val transfers = mutableTransfers.value
        val current = transfers.find { it.id == id } ?: return
        if (!current.isActive) return
        val updated = current.copy(
            fileName = fileName,
            progress = progress.coerceIn(0, 100),
            state = TransferState.TRANSFERRING,
            totalBytes = totalBytes?.takeIf { it > 0 } ?: current.totalBytes,
            errorMessage = null
        )
        mutableTransfers.value = transfers.map { if (it.id == id) updated else it }
    }

    fun complete(id: String): Boolean {
        return update(id, TransferState.COMPLETED) {
            it.copy(progress = 100, state = TransferState.COMPLETED, errorMessage = null)
        }
    }

    fun fail(id: String, message: String) {
        update(id, TransferState.FAILED) {
            it.copy(state = TransferState.FAILED, errorMessage = message)
        }
    }

    fun cancel(id: String) {
        update(id, TransferState.CANCELED) {
            it.copy(state = TransferState.CANCELED, errorMessage = null)
        }
    }

    @Synchronized
    fun remove(id: String) {
        mutableTransfers.value = mutableTransfers.value.filterNot { it.id == id }
    }

    @Synchronized
    fun removeIfState(id: String, state: TransferState) {
        mutableTransfers.value = mutableTransfers.value.filterNot { it.id == id && it.state == state }
    }

    @Synchronized
    private fun update(
        id: String,
        targetState: TransferState,
        transform: (TransferProgress) -> TransferProgress
    ): Boolean {
        val transfers = mutableTransfers.value
        val current = transfers.find { it.id == id } ?: return false
        if (targetState != current.state && !canTransition(current.state, targetState)) return false
        mutableTransfers.value = transfers.map { if (it.id == id) transform(it) else it }
        return true
    }

    private fun canTransition(from: TransferState, to: TransferState): Boolean {
        return when (from) {
            TransferState.QUEUED -> to == TransferState.RESOLVING ||
                to == TransferState.TRANSFERRING ||
                to == TransferState.FAILED ||
                to == TransferState.CANCELED
            TransferState.RESOLVING -> to == TransferState.TRANSFERRING ||
                to == TransferState.FAILED ||
                to == TransferState.CANCELED
            TransferState.TRANSFERRING -> to == TransferState.COMPLETED ||
                to == TransferState.FAILED ||
                to == TransferState.CANCELED
            TransferState.COMPLETED,
            TransferState.FAILED,
            TransferState.CANCELED -> false
        }
    }
}
