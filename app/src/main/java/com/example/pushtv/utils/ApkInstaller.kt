package com.example.pushtv.utils

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import com.example.pushtv.logging.AppLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val installScope = CoroutineScope(Dispatchers.Main)
private const val INSTALLER_TAG = "Installer"

internal fun installApk(context: Context, apkFile: File) {
    val appContext = context.applicationContext
    installScope.launch {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                // Android 6's installer does not accept APK content URIs.
                withContext(Dispatchers.IO) { commitLegacyInstall(appContext, apkFile) }
            } else {
                val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apkFile)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                appContext.startActivity(intent)
                AppLog.info(INSTALLER_TAG, "Installer opened for ${apkFile.name}")
            }
        } catch (error: Exception) {
            AppLog.error(INSTALLER_TAG, "Could not open installer for ${apkFile.name}", error)
            Toast.makeText(appContext, "无法启动安装，请手动安装 APK", Toast.LENGTH_LONG).show()
        }
    }
}

@SuppressLint("UnspecifiedImmutableFlag") // Android 6 requires a mutable callback and has no FLAG_MUTABLE.
private fun commitLegacyInstall(context: Context, apkFile: File) {
    val installer = context.packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
    params.setSize(apkFile.length())
    val sessionId = installer.createSession(params)
    val result = PendingIntent.getBroadcast(context, sessionId, Intent(context, ApkInstallReceiver::class.java), 0)
    try {
        installer.openSession(sessionId).use { session ->
            apkFile.inputStream().use { input ->
                session.openWrite("base.apk", 0, apkFile.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            session.commit(result.intentSender)
        }
        AppLog.info(INSTALLER_TAG, "Install session $sessionId submitted for ${apkFile.name}")
    } catch (error: Exception) {
        result.cancel()
        runCatching { installer.abandonSession(sessionId) }
        throw error
    }
}

class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                try {
                    val confirmation = checkNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java))
                    context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (error: Exception) {
                    val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                    runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                    AppLog.error(INSTALLER_TAG, "Could not open install confirmation for session $sessionId", error)
                    Toast.makeText(context, "无法启动安装，请手动安装 APK", Toast.LENGTH_LONG).show()
                }
            }
            PackageInstaller.STATUS_SUCCESS -> AppLog.info(INSTALLER_TAG, "APK installation completed")
            PackageInstaller.STATUS_FAILURE_ABORTED -> AppLog.info(INSTALLER_TAG, "APK installation cancelled")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                AppLog.warn(INSTALLER_TAG, "APK installation failed ($status): $message")
                Toast.makeText(context, "APK 安装失败，请查看诊断日志", Toast.LENGTH_LONG).show()
            }
        }
    }
}
