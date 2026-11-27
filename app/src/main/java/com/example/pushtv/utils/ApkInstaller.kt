package com.example.pushtv.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.pushtv.logging.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

private val installScope = CoroutineScope(Dispatchers.Main)
internal const val APK_MIME_TYPE = "application/vnd.android.package-archive"
internal const val INSTALLER_TAG = "Installer"

internal fun installApk(context: Context, apkFile: File) {
    val appContext = context.applicationContext
    installScope.launch {
        try {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.M) {
                LegacyApkInstallActivity.open(appContext, apkFile)
            } else {
                val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apkFile)
                val intent = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME_TYPE)
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
