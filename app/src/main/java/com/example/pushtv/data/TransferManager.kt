package com.example.pushtv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import android.os.Handler
import android.os.Looper

data class TransferProgress(
    val id: String,
    val fileName: String,
    val progress: Int, // 0-100
    val isComplete: Boolean = false,
    val errorMessage: String? = null
)

object TransferManager {
    private const val COMPLETED_DISPLAY_MILLIS = 1500L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val registry = TransferRegistry()
    val activeTransfers: StateFlow<List<TransferProgress>> = registry.transfers

    fun startTransfer(id: String, fileName: String) {
        registry.start(id, fileName)
    }

    fun updateProgress(id: String, fileName: String, progress: Int) {
        registry.updateProgress(id, fileName, progress)
    }

    fun markComplete(id: String) {
        finishTransfer(id) { registry.complete(id) }
    }

    fun markFailed(id: String, message: String) {
        finishTransfer(id) { registry.fail(id, message) }
    }

    private fun finishTransfer(id: String, finish: () -> Unit) {
        finish()
        mainHandler.postDelayed({
            registry.remove(id)
        }, COMPLETED_DISPLAY_MILLIS)
    }
}

internal class TransferRegistry {
    private val mutableTransfers = MutableStateFlow<List<TransferProgress>>(emptyList())
    val transfers: StateFlow<List<TransferProgress>> = mutableTransfers.asStateFlow()

    fun start(id: String, fileName: String) {
        mutableTransfers.update { transfers ->
            transfers.filterNot { it.id == id } + TransferProgress(id, fileName, 0)
        }
    }

    fun updateProgress(id: String, fileName: String, progress: Int) {
        mutableTransfers.update { transfers ->
            val current = transfers.find { it.id == id }
            val updated = (current ?: TransferProgress(id, fileName, 0)).copy(
                fileName = fileName,
                progress = progress.coerceIn(0, 100),
                isComplete = false,
                errorMessage = null
            )
            if (current == null) {
                transfers + updated
            } else {
                transfers.map { if (it.id == id) updated else it }
            }
        }
    }

    fun complete(id: String) {
        update(id) { it.copy(progress = 100, isComplete = true, errorMessage = null) }
    }

    fun fail(id: String, message: String) {
        update(id) { it.copy(isComplete = false, errorMessage = message) }
    }

    fun remove(id: String) {
        mutableTransfers.update { transfers -> transfers.filterNot { it.id == id } }
    }

    private fun update(id: String, transform: (TransferProgress) -> TransferProgress) {
        mutableTransfers.update { transfers ->
            transfers.map { if (it.id == id) transform(it) else it }
        }
    }
}
