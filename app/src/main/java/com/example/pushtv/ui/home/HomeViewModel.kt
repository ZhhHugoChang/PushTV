package com.example.pushtv.ui.home

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.FileObserver
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.example.pushtv.data.TransferManager
import com.example.pushtv.data.TransferProgress
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.NetworkInterface

data class ApkInfo(
    val file: File,
    val name: String,
    val packageName: String,
    val versionName: String,
    val icon: Any?, // Changed to Any? to support File, PackageName or Bitmap for Coil
    val sizeMb: String,
    val isInstalled: Boolean = false,
    val installedVersionName: String? = null,
    val canUpdate: Boolean = false
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext

    private val _fileList = MutableStateFlow<List<ApkInfo>>(emptyList())
    val fileList: StateFlow<List<ApkInfo>> = _fileList

    private val _ipAddress = MutableStateFlow("获取中...")
    val ipAddress: StateFlow<String> = _ipAddress

    private val _qrBitmap = MutableStateFlow<ImageBitmap?>(null)
    val qrBitmap: StateFlow<ImageBitmap?> = _qrBitmap

    val activeTransfers: StateFlow<List<TransferProgress>> = TransferManager.activeTransfers

    private val _storageInfo = MutableStateFlow("检测中...")
    val storageInfo: StateFlow<String> = _storageInfo

    private var fileObserver: FileObserver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        refreshIpAddress()
        startFileObserver()
        refreshFileList()
        refreshStorageInfo()
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Delay slightly to ensure IP is assigned
                viewModelScope.launch {
                    delay(1000)
                    refreshIpAddress()
                }
            }

            override fun onLost(network: Network) {
                refreshIpAddress()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    private fun refreshStorageInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val stat = android.os.StatFs(context.filesDir.path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val availableGb = availableBytes / (1024.0 * 1024.0 * 1024.0)
            val totalGb = totalBytes / (1024.0 * 1024.0 * 1024.0)
            _storageInfo.value = "剩余空间: %.1fG / %.1fG".format(availableGb, totalGb)
        }
    }

    private fun refreshIpAddress() {
        viewModelScope.launch(Dispatchers.IO) {
            val ip = getLocalIpAddress(context)
            val fullIp = if (ip == "未知IP") ip else "http://$ip:8899"
            _ipAddress.value = fullIp
            if (ip != "未知IP") {
                _qrBitmap.value = generateQRCode(fullIp)
            }
        }
    }

    private fun startFileObserver() {
        val incomingDir = File(context.cacheDir, "incoming")
        if (!incomingDir.exists()) incomingDir.mkdirs()

        fileObserver = object : FileObserver(incomingDir.absolutePath, CREATE or CLOSE_WRITE or MOVED_TO or DELETE) {
            override fun onEvent(event: Int, path: String?) {
                if (path?.lowercase()?.endsWith(".apk") == true) {
                    refreshFileList()
                }
            }
        }
        fileObserver?.startWatching()
    }

    fun refreshFileList() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(context.cacheDir, "incoming")
            val files = dir.listFiles { file -> file.extension.lowercase() == "apk" }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            val apkInfos = files.map { file ->
                parseApkInfo(context, file)
            }
            _fileList.value = apkInfos
        }
    }

    fun deleteFile(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            if (file.exists()) {
                file.delete()
                refreshFileList()
                refreshStorageInfo()
            }
        }
    }

    fun clearRecordsOnly() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(context.cacheDir, "incoming")
            dir.listFiles { file -> file.extension.lowercase() == "apk" }?.forEach { file ->
                val newFile = File(dir, file.name + ".bak")
                file.renameTo(newFile)
            }
            refreshFileList()
        }
    }

    fun clearAllFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = File(context.cacheDir, "incoming")
            dir.listFiles()?.forEach { it.delete() }
            refreshFileList()
            refreshStorageInfo()
        }
    }

    fun openApp(packageName: String) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        intent?.let {
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    }

    fun uninstallApp(packageName: String) {
        val intent = android.content.Intent(android.content.Intent.ACTION_DELETE)
        intent.data = android.net.Uri.parse("package:$packageName")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun parseApkInfo(context: Context, file: File): ApkInfo {
        val packageManager = context.packageManager
        val packageInfo = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        
        val label = if (packageInfo != null) {
            packageInfo.applicationInfo.sourceDir = file.absolutePath
            packageInfo.applicationInfo.publicSourceDir = file.absolutePath
            packageManager.getApplicationLabel(packageInfo.applicationInfo).toString()
        } else {
            file.name
        }

        val packageName = packageInfo?.packageName ?: ""
        val versionName = packageInfo?.versionName ?: ""
        
        val icon = if (packageInfo != null) {
            try {
                packageManager.getApplicationIcon(packageInfo.applicationInfo)
            } catch (e: Exception) { null }
        } else null

        val sizeMb = "%.2f MB".format(file.length() / (1024.0 * 1024.0))

        var isInstalled = false
        var installedVersionName: String? = null
        var canUpdate = false

        if (packageName.isNotEmpty()) {
            try {
                val installedAppInfo = packageManager.getPackageInfo(packageName, 0)
                isInstalled = true
                installedVersionName = installedAppInfo.versionName
                // 简单的版本字符串对比，实际可能需要更复杂的逻辑
                if (versionName != installedVersionName) {
                    canUpdate = true 
                }
            } catch (e: PackageManager.NameNotFoundException) {
                // 未安装
            }
        }

        return ApkInfo(file, label, packageName, versionName, icon, sizeMb, isInstalled, installedVersionName, canUpdate)
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun getLocalIpAddress(context: Context): String {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipAddress = wifiManager.connectionInfo.ipAddress
            if (ipAddress != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipAddress and 0xff,
                    ipAddress shr 8 and 0xff,
                    ipAddress shr 16 and 0xff,
                    ipAddress shr 24 and 0xff
                )
            }
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val enumIpAddr = en.nextElement().inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) {
                        return inetAddress.hostAddress ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "未知IP"
    }

    private fun generateQRCode(content: String, size: Int = 400): ImageBitmap? {
        if (content.isEmpty()) return null
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val dotColor = 0xFF0F172A.toInt() // Dark Blue-Grey
            val bgColor = 0xFFE2E8F0.toInt() // Light Blue-Grey
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) dotColor else bgColor)
                }
            }
            bmp.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        fileObserver?.stopWatching()
        networkCallback?.let {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(it)
        }
    }
}
