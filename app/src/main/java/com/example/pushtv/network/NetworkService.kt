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
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.request.*
import io.ktor.http.ContentType
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
import java.util.UUID

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
                        call.respondText(htmlContent, io.ktor.http.ContentType.Text.Html)
                    }
                    get("/api/apps") {
                        val pm = applicationContext.packageManager
                        val apps = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA)
                        val savedSettings = SettingsRepo.getAllSettings(applicationContext)
                        val appList = apps.map { appInfo ->
                            val packageInfo = pm.getPackageInfo(appInfo.packageName, 0)
                            JSONObject().apply {
                                put("name", pm.getApplicationLabel(appInfo).toString())
                                put("packageName", appInfo.packageName)
                                put("version", packageInfo.versionName.orEmpty())
                                put("url", savedSettings[appInfo.packageName]?.updateUrl.orEmpty())
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
                    post("/api/upload") {
                        var currentTransferId: String? = null
                        try {
                            val multipart = call.receiveMultipart()
                            val uploadDir = File(applicationContext.cacheDir, "incoming")
                            if (!uploadDir.exists()) uploadDir.mkdirs()
                            
                            var savedFile: File? = null
                            var part = multipart.readPart()
                            while (part != null) {
                                if (part is PartData.FileItem) {
                                    val originalFileName = part.originalFileName ?: "upload.apk"
                                    val transferId = "upload:${UUID.randomUUID()}"
                                    currentTransferId = transferId
                                    TransferManager.startTransfer(transferId, originalFileName)
                                    savedFile = File(uploadDir, "temp_${System.currentTimeMillis()}_$originalFileName")
                                    
                                    val contentLength = call.request.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLong() ?: -1L
                                    var bytesWritten = 0L
                                    
                                    part.streamProvider().use { input ->
                                        savedFile.outputStream().buffered().use { output ->
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
                            currentTransferId?.let { TransferManager.markFailed(it, e.message ?: "上传失败") }
                            call.respondText(
                                JSONObject().put("success", false).put("message", e.message ?: "上传失败").toString(),
                                ContentType.Application.Json,
                                HttpStatusCode.InternalServerError
                            )
                        }
                    }
                }
            }.start(wait = false)
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
