package com.example.pushtv.network

import com.example.pushtv.data.WebDavConfig
import com.example.pushtv.data.WebDavRemoteFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.StringReader
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

object WebDavClient {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    private var activeCall: Call? = null

    fun cancelActiveCall() {
        activeCall?.cancel()
        activeCall = null
    }

    private fun buildAuthHeader(config: WebDavConfig): String? {
        if (config.username.isBlank() && config.password.isBlank()) return null
        return Credentials.basic(config.username, config.password, Charsets.UTF_8)
    }

    fun buildBaseDirUrl(config: WebDavConfig): String {
        val base = config.serverUrl.trim().trimEnd('/')
        val dir = config.remoteDir.trim().trim('/')
        return if (dir.isEmpty()) "$base/" else "$base/$dir/"
    }

    private fun buildFileUrl(config: WebDavConfig, fileName: String): String {
        val baseDir = buildBaseDirUrl(config)
        // 编码文件名部分以保证特殊字符正常支持
        val encodedFileName = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        return "$baseDir$encodedFileName"
    }

    suspend fun testConnection(config: WebDavConfig): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            if (!config.isConfigured) throw IllegalArgumentException("WebDAV 服务器地址未配置")
            ensureRemoteDirectory(config).getOrThrow()
            true
        }
    }

    suspend fun ensureRemoteDirectory(config: WebDavConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val base = config.serverUrl.trim().trimEnd('/')
            val cleanDir = config.remoteDir.trim().trim('/')
            val auth = buildAuthHeader(config)

            // 先检查根目录或目标目录是否存在
            val targetDirUrl = buildBaseDirUrl(config)
            val checkRequest = Request.Builder()
                .url(targetDirUrl)
                .method("PROPFIND", null)
                .header("Depth", "0")
                .apply { if (auth != null) header("Authorization", auth) }
                .build()

            val checkResp = okHttpClient.newCall(checkRequest).execute()
            val checkCode = checkResp.code
            checkResp.close()

            if (checkCode in 200..299 || checkCode == 207) {
                return@runCatching
            }

            if (checkCode == 401 || checkCode == 403) {
                throw IllegalStateException("WebDAV 认证失败，请检查用户名或密码")
            }

            // 逐级创建目录
            val parts = cleanDir.split("/").filter { it.isNotBlank() }
            var currentPath = base
            for (part in parts) {
                currentPath = "$currentPath/$part"
                val mkcolRequest = Request.Builder()
                    .url("$currentPath/")
                    .method("MKCOL", null)
                    .apply { if (auth != null) header("Authorization", auth) }
                    .build()
                okHttpClient.newCall(mkcolRequest).execute().use { resp ->
                    if (resp.code !in 200..299 && resp.code != 405 && resp.code != 207) {
                        // 405 Method Not Allowed 通常表示目录已存在，属于正常情况
                        if (resp.code == 401 || resp.code == 403) {
                            throw IllegalStateException("WebDAV 认证失败，无权限创建目录")
                        }
                    }
                }
            }
        }
    }

    suspend fun listRemoteBackups(config: WebDavConfig): Result<List<WebDavRemoteFile>> = withContext(Dispatchers.IO) {
        runCatching {
            if (!config.isConfigured) return@runCatching emptyList()
            val targetDirUrl = buildBaseDirUrl(config)
            val auth = buildAuthHeader(config)

            val request = Request.Builder()
                .url(targetDirUrl)
                .method("PROPFIND", null)
                .header("Depth", "1")
                .apply { if (auth != null) header("Authorization", auth) }
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful && response.code != 207) {
                val code = response.code
                response.close()
                if (code == 404) return@runCatching emptyList()
                throw IllegalStateException("无法拉取远端文件列表 (HTTP $code)")
            }

            val xml = response.body?.string().orEmpty()
            parsePropFindResponse(xml, targetDirUrl)
        }
    }

    private fun parsePropFindResponse(xml: String, baseDirUrl: String): List<WebDavRemoteFile> {
        if (xml.isBlank()) return emptyList()
        val files = mutableListOf<WebDavRemoteFile>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var eventType = parser.eventType
            var currentHref = ""
            var currentLength: Long = 0
            var isCollection = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (tagName.lowercase()) {
                            "response" -> {
                                currentHref = ""
                                currentLength = 0
                                isCollection = false
                            }
                            "href" -> {
                                currentHref = parser.nextText().orEmpty()
                            }
                            "getcontentlength" -> {
                                currentLength = parser.nextText().toLongOrNull() ?: 0L
                            }
                            "collection" -> {
                                isCollection = true
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName.equals("response", ignoreCase = true)) {
                            if (!isCollection && currentHref.isNotBlank()) {
                                val decodedHref = runCatching { URLDecoder.decode(currentHref, "UTF-8") }.getOrDefault(currentHref)
                                val name = decodedHref.trimEnd('/').substringAfterLast('/')
                                if (name.endsWith(".apk", ignoreCase = true)) {
                                    files.add(WebDavRemoteFile(name = name, size = currentLength))
                                }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return files
    }

    suspend fun uploadApk(
        config: WebDavConfig,
        localFile: File,
        remoteFileName: String,
        onProgress: (sent: Long, total: Long) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!localFile.exists()) throw IllegalArgumentException("本地 APK 文件不存在: ${localFile.absolutePath}")
            ensureRemoteDirectory(config).getOrThrow()

            val uploadUrl = buildFileUrl(config, remoteFileName)
            val auth = buildAuthHeader(config)
            val totalSize = localFile.length()

            val requestBody = object : RequestBody() {
                override fun contentType() = "application/vnd.android.package-archive".toMediaTypeOrNull()
                override fun contentLength() = totalSize

                override fun writeTo(sink: BufferedSink) {
                    val buffer = ByteArray(16 * 1024)
                    FileInputStream(localFile).use { input ->
                        var uploaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (!coroutineContext.isActive) {
                                throw CancellationException("Upload cancelled")
                            }
                            sink.write(buffer, 0, read)
                            uploaded += read
                            if (coroutineContext.isActive) {
                                onProgress(uploaded, totalSize)
                            }
                        }
                    }
                }
            }

            val request = Request.Builder()
                .url(uploadUrl)
                .put(requestBody)
                .apply { if (auth != null) header("Authorization", auth) }
                .build()

            val call = okHttpClient.newCall(request)
            activeCall = call
            try {
                call.execute().use { resp ->
                    if (resp.code !in 200..299 && resp.code != 201 && resp.code != 204) {
                        throw IllegalStateException("上传失败: HTTP ${resp.code} ${resp.message}")
                    }
                }
            } finally {
                if (activeCall === call) {
                    activeCall = null
                }
            }
        }
    }

    suspend fun downloadApk(
        config: WebDavConfig,
        remoteFileName: String,
        destFile: File,
        onProgress: (recv: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val downloadUrl = buildFileUrl(config, remoteFileName)
            val auth = buildAuthHeader(config)

            val request = Request.Builder()
                .url(downloadUrl)
                .get()
                .apply { if (auth != null) header("Authorization", auth) }
                .build()

            val call = okHttpClient.newCall(request)
            activeCall = call
            val response = call.execute()
            try {
                if (!response.isSuccessful) {
                    val code = response.code
                    response.close()
                    throw IllegalStateException("下载失败: HTTP $code")
                }

                val body = response.body ?: throw IllegalStateException("下载响应体为空")
                val totalBytes = body.contentLength()

                destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                val tempFile = File(destFile.parentFile, ".${destFile.name}.part")

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var received = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            if (!coroutineContext.isActive) {
                                throw CancellationException("Download cancelled")
                            }
                            output.write(buffer, 0, read)
                            received += read
                            if (coroutineContext.isActive) {
                                onProgress(received, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }

                if (destFile.exists()) destFile.delete()
                if (!tempFile.renameTo(destFile)) {
                    tempFile.copyTo(destFile, overwrite = true)
                    tempFile.delete()
                }

                destFile
            } finally {
                response.close()
                if (activeCall === call) {
                    activeCall = null
                }
            }
        }
    }

    suspend fun deleteRemoteFile(
        config: WebDavConfig,
        remoteFileName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val fileUrl = buildFileUrl(config, remoteFileName)
            val auth = buildAuthHeader(config)

            val request = Request.Builder()
                .url(fileUrl)
                .delete()
                .apply { if (auth != null) header("Authorization", auth) }
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 404) {
                    throw IllegalStateException("删除云端文件失败: HTTP ${resp.code}")
                }
            }
        }
    }
}
