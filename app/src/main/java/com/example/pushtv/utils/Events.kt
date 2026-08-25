package com.example.pushtv.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object Events {
    private val _refreshAppsSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refreshAppsSignal = _refreshAppsSignal.asSharedFlow()

    fun triggerRefreshApps() {
        _refreshAppsSignal.tryEmit(Unit)
    }
}
