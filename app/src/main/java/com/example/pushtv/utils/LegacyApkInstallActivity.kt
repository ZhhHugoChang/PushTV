package com.example.pushtv.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.pushtv.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Stages the APK where the Android 6 system installer can read its file URI. */
internal class LegacyApkInstallActivity : ComponentActivity() {
    private var waitingForStoragePermission = false
    private var stagedApk: File? = null

    private val requestStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        waitingForStoragePermission = false
        if (granted) {
            prepareInstall()
        } else {
            AppLog.warn(INSTALLER_TAG, "Storage permission denied for Android 6 installation")
            Toast.makeText(this, "请允许存储权限后重试安装", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val launchInstaller = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            AppLog.info(INSTALLER_TAG, "Android 6 installation completed")
        } else {
            AppLog.info(INSTALLER_TAG, "Android 6 installer finished with result ${result.resultCode}")
        }
        stagedApk?.delete()
        stagedApk = null
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT != Build.VERSION_CODES.M) {
            finish()
            return
        }

        waitingForStoragePermission = savedInstanceState?.getBoolean(WAITING_FOR_PERMISSION) == true
        stagedApk = savedInstanceState?.getString(STAGED_APK)?.let(::File)
        if (waitingForStoragePermission || stagedApk != null) return

        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            prepareInstall()
        } else {
            waitingForStoragePermission = true
            requestStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(WAITING_FOR_PERMISSION, waitingForStoragePermission)
        outState.putString(STAGED_APK, stagedApk?.absolutePath)
        super.onSaveInstanceState(outState)
    }

    @Suppress("DEPRECATION") // Android 6 TV installers can require file URIs.
    private fun prepareInstall() {
        lifecycleScope.launch {
            val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destination = File(downloads, "PushTV-install-${UUID.randomUUID()}.apk")
            try {
                val source = File(checkNotNull(intent.getStringExtra(SOURCE_APK)))
                withContext(Dispatchers.IO) {
                    check(source.isFile && source.length() > 0) { "Source APK is missing or empty" }
                    check(downloads.isDirectory || downloads.mkdirs() || downloads.isDirectory) {
                        "Downloads directory is unavailable"
                    }
                    source.copyTo(destination)
                }
                val target = Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(Uri.fromFile(destination), APK_MIME_TYPE)
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                launchInstaller.launch(target)
                stagedApk = destination
                AppLog.info(INSTALLER_TAG, "Android 6 installer opened for ${source.name}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                AppLog.error(INSTALLER_TAG, "Could not open Android 6 installer", error)
                Toast.makeText(this@LegacyApkInstallActivity, "无法启动安装，请查看诊断日志", Toast.LENGTH_LONG).show()
                finish()
            } finally {
                if (stagedApk != destination) destination.delete()
            }
        }
    }

    companion object {
        private const val SOURCE_APK = "source_apk"
        private const val STAGED_APK = "staged_apk"
        private const val WAITING_FOR_PERMISSION = "waiting_for_permission"

        fun open(context: Context, apk: File) {
            val target = Intent(context, LegacyApkInstallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SOURCE_APK, apk.absolutePath)
            context.startActivity(target)
        }
    }
}
