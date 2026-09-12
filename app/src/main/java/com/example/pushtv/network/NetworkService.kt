package com.example.pushtv.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.pushtv.data.SettingsRepo
import com.example.pushtv.data.TransferManager
import com.example.pushtv.data.BackupManager
import com.example.pushtv.data.BackupAppItem
import com.example.pushtv.data.WebDavConfig
import com.example.pushtv.network.WebDavClient
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipFile

class NetworkService : Service() {
    private var server: NettyApplicationEngine? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val CHANNEL_ID = "PushTV_Service"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
        
        startServer()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "PushTV Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PushTV 正在运行")
            .setContentText("局域网服务已启动，等待文件上传...")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startServer() {
        serviceScope.launch {
            server = embeddedServer(Netty, port = 8899, host = "0.0.0.0") {
                routing {
                    get("/") {
                        val htmlContent = loadAssetFile("web/index.html")
                        call.response.header(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate, max-age=0")
                        call.response.header(HttpHeaders.Pragma, "no-cache")
                        call.response.header(HttpHeaders.Expires, "0")
                        call.respondText(htmlContent, io.ktor.http.ContentType.Text.Html)
                    }
                    get("/api/apps") {
                        val pm = applicationContext.packageManager
                        val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                        val savedSettings = SettingsRepo.getAllSettings(applicationContext)
                        val appList = apps.map { appInfo ->
                            val packageInfo = pm.getPackageInfo(appInfo.packageName, 0)
                            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                            JSONObject().apply {
                                put("name", pm.getApplicationLabel(appInfo).toString())
                                put("packageName", appInfo.packageName)
                                put("version", packageInfo.versionName.orEmpty())
                                put("url", savedSettings[appInfo.packageName]?.updateUrl.orEmpty())
                                put("isSystem", isSystem)
                            }
                        }
                        call.respondText(JSONArray(appList).toString(), ContentType.Application.Json)
                    }
                    post("/api/save_url") {
                        val params = call.receiveParameters()
                        val pkg = params["packageName"] ?: ""
                        val url = params["url"] ?: ""
                        if (pkg.isNotEmpty()) {
                            SettingsRepo.saveUrl(applicationContext, pkg, url)
                            com.example.pushtv.utils.Events.triggerRefreshApps()
                            
                            // 在电视上弹出提示
                            launch(Dispatchers.Main) {
                                android.widget.Toast.makeText(applicationContext, "已从网页同步更新地址", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            
                            call.respondText("""{"success":true}""", ContentType.Application.Json, HttpStatusCode.OK)
                        } else {
                            call.respondText(
                                """{"success":false,"message":"缺少应用包名"}""",
                                ContentType.Application.Json,
                                HttpStatusCode.BadRequest
                            )
                        }
                    }
                    get("/api/webdav/config") {
                        val config = SettingsRepo.getWebDavConfig(applicationContext)
                        val status = BackupManager.webDavStatus.value.name
                        val json = JSONObject().apply {
                            put("serverUrl", config.serverUrl)
                            put("username", config.username)
                            put("password", if (config.password.isNotBlank()) "******" else "")
                            put("remoteDir", config.remoteDir)
                            put("status", status)
                        }
                        call.respondText(json.toString(), ContentType.Application.Json)
                    }
                    post("/api/webdav/config") {
                        val params = call.receiveParameters()
                        val url = params["serverUrl"]?.trim().orEmpty()
                        val user = params["username"]?.trim().orEmpty()
                        val pass = params["password"]?.trim().orEmpty()
                        val dir = params["remoteDir"]?.trim()?.ifBlank { "/PushTV/Backups/" } ?: "/PushTV/Backups/"

                        val oldConfig = SettingsRepo.getWebDavConfig(applicationContext)
                        val finalPassword = if (pass == "******" || pass.isBlank()) oldConfig.password else pass

                        val newConfig = WebDavConfig(
                            serverUrl = url,
                            username = user,
                            password = finalPassword,
                            remoteDir = dir
                        )
                        SettingsRepo.saveWebDavConfig(applicationContext, newConfig)
                        BackupManager.checkWebDavStatus(applicationContext)

                        launch(Dispatchers.Main) {
                            android.widget.Toast.makeText(applicationContext, "WebDAV 配置已通过网页更新", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        call.respondText("""{"success":true}""", ContentType.Application.Json)
                    }
                    post("/api/webdav/test") {
                        val params = call.receiveParameters()
                        val url = params["serverUrl"]?.trim().orEmpty()
                        val user = params["username"]?.trim().orEmpty()
                        val pass = params["password"]?.trim().orEmpty()
                        val dir = params["remoteDir"]?.trim()?.ifBlank { "/PushTV/Backups/" } ?: "/PushTV/Backups/"

                        val oldConfig = SettingsRepo.getWebDavConfig(applicationContext)
                        val finalPassword = if (pass == "******" || pass.isBlank()) oldConfig.password else pass

                        val testConfig = WebDavConfig(url, user, finalPassword, dir)
                        val testResult = WebDavClient.testConnection(testConfig)
                        val json = JSONObject().apply {
                            put("success", testResult.isSuccess)
                            put("message", if (testResult.isSuccess) "连接成功！目录正常" else testResult.exceptionOrNull()?.message ?: "连接失败")
                        }
                        call.respondText(json.toString(), ContentType.Application.Json)
                    }
                    post("/api/webdav/backup") {
                        val params = call.receiveParameters()
                        val packagesStr = params["packages"].orEmpty()
                        val packages = packagesStr.split(",").map { it.trim() }.filter { it.isNotBlank() }

                        if (packages.isEmpty()) {
                            call.respondText("""{"success":false,"message":"未选择应用"}""", ContentType.Application.Json)
                            return@post
                        }

                        val pm = applicationContext.packageManager
                        val allApps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                        val selectedApps = allApps.filter { it.packageName in packages }
                            .mapNotNull { appInfo ->
                                try {
                                    val pkgInfo = pm.getPackageInfo(appInfo.packageName, 0)
                                    val sourceDir = appInfo.sourceDir
                                    BackupAppItem(
                                        name = pm.getApplicationLabel(appInfo).toString(),
                                        packageName = appInfo.packageName,
                                        icon = null,
                                        versionName = pkgInfo.versionName.orEmpty(),
                                        apkFileLength = File(sourceDir).length(),
                                        sourceDir = sourceDir,
                                        isSelected = true
                                    )
                                } catch (e: Exception) {
                                    null
                                }
                            }

                        BackupManager.startBackupQueue(applicationContext, selectedApps)
                        call.respondText("""{"success":true,"count":${selectedApps.size}}""", ContentType.Application.Json)
                    }
                    get("/api/webdav/progress") {
                        val progress = BackupManager.activeProgress.value
                        val json = JSONObject().apply {
                            put("active", progress != null)
                            if (progress != null) {
                                put("type", progress.type.name)
                                put("appName", progress.currentAppName)
                                put("index", progress.currentIndex)
                                put("total", progress.totalCount)
                                put("progress", progress.currentFileProgress)
                                put("statusMessage", progress.statusMessage)
                            }
                        }
                        call.respondText(json.toString(), ContentType.Application.Json)
                    }
                    post("/api/upload") {
                        var currentTransferId: String? = null
                        var pendingFile: File? = null
                        try {
                            val multipart = call.receiveMultipart()
                            val uploadDir = File(applicationContext.cacheDir, "incoming")
                            if (!uploadDir.exists()) uploadDir.mkdirs()
                            
                            var savedFile: File? = null
                            var part = multipart.readPart()
                            while (part != null) {
                                if (part is PartData.FileItem) {
                                    val originalFileName = File(part.originalFileName ?: "upload.apk").name
                                    if (!originalFileName.endsWith(".apk", ignoreCase = true)) {
                                        part.dispose()
                                        call.respondText(
                                            """{"success":false,"message":"仅支持 APK 文件"}""",
                                            ContentType.Application.Json,
                                            HttpStatusCode.UnsupportedMediaType
                                        )
                                        return@post
                                    }
                                    val transferId = "upload:${UUID.randomUUID()}"
                                    currentTransferId = transferId
                                    TransferManager.startTransfer(transferId, originalFileName)
                                    val timestamp = System.currentTimeMillis()
                                    val partFile = File(uploadDir, ".$timestamp-$originalFileName.part")
                                    val completedFile = File(uploadDir, "$timestamp-$originalFileName")
                                    pendingFile = partFile
                                    
                                    val contentLength = call.request.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLong() ?: -1L
                                    var bytesWritten = 0L
                                    TransferManager.markTransferring(transferId)
                                    part.streamProvider().use { input ->
                                        partFile.outputStream().buffered().use { output ->
                                            val buffer = ByteArray(8192)
                                            var bytes = input.read(buffer)
                                            while (bytes >= 0) {
                                                output.write(buffer, 0, bytes)
                                                bytesWritten += bytes
                                                if (contentLength > 0) {
                                                    val progress = ((bytesWritten.toDouble() / contentLength) * 100).toInt()
                                                    TransferManager.updateProgress(transferId, originalFileName, progress)
                                                }
                                                bytes = input.read(buffer)
                                            }
                                        }
                                    }
                                    if (!isValidApk(partFile)) {
                                        throw IllegalArgumentException("文件不是有效的 APK")
                                    }
                                    moveCompletedFile(partFile, completedFile)
                                    pendingFile = null
                                    savedFile = completedFile
                                    TransferManager.markComplete(transferId)
                                    currentTransferId = null
                                }
                                part.dispose()
                                part = multipart.readPart()
                            }
                            
                            if (savedFile != null) {
                                call.respondText(
                                    """{"success":true,"message":"文件已上传"}""",
                                    ContentType.Application.Json,
                                    HttpStatusCode.Created
                                )
                                triggerInstallation(savedFile)
                            } else {
                                call.respondText(
                                    """{"success":false,"message":"未找到文件"}""",
                                    ContentType.Application.Json,
                                    HttpStatusCode.BadRequest
                                )
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            pendingFile?.delete()
                            currentTransferId?.let {
                                TransferManager.markFailed(it, e.message ?: "上传失败")
                                TransferManager.remove(it)
                            }
                            val status = if (e is IllegalArgumentException) {
                                HttpStatusCode.BadRequest
                            } else {
                                HttpStatusCode.InternalServerError
                            }
                            call.respondText(
                                JSONObject().put("success", false).put("message", e.message ?: "上传失败").toString(),
                                ContentType.Application.Json,
                                status
                            )
                        }
                    }
                }
            }.start(wait = false)
        }
    }

    private fun isValidApk(file: File): Boolean = runCatching {
        file.length() > 0 && ZipFile(file).use { it.getEntry("AndroidManifest.xml") != null }
    }.getOrDefault(false)

    private fun moveCompletedFile(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun triggerInstallation(apkFile: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                applicationContext, 
                applicationContext.packageName + ".fileprovider", 
                apkFile
            )
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadAssetFile(path: String): String {
        return try {
            val inputStream: InputStream = applicationContext.assets.open(path)
            inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            "Error loading $path"
        }
    }

    override fun onDestroy() {
        server?.stop(1000, 2000)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
