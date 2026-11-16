package com.example.pushtv

import android.app.Application
import android.os.Process
import com.example.pushtv.logging.AppLog

class PushTVApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.initialize(this)

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            AppLog.recordFatalCrash(thread.name, throwable)
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
            }
        }
    }
}
