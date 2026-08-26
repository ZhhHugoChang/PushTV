package com.example.pushtv.ui.software

import android.app.Application
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pushtv.data.SettingsRepo
import com.example.pushtv.data.TransferManager
import com.example.pushtv.utils.Events
import com.example.pushtv.utils.VersionComparator
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

data class GitHubAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long = 0,
    val isRecommended: Boolean = false
)

data class InstalledApp(
    val name: String,
    val packageName: String,
    val icon: Any?,
    val versionName: String,
    val updateUrl: String = "",
    val remoteVersion: String? = null,
    val isChecking: Boolean = false,
    val remoteStatus: String? = null, // 新增：连接状态描述
    val assets: List<GitHubAsset> = emptyList(),
    val changelog: String? = null,
    val isFavorite: Boolean = false,
    val isSystemApp: Boolean = false
)

enum class FilterMode {
    ALL, FAVORITES
}

class SoftwareViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 15000
        }
        // 自动跟随重定向
        followRedirects = true
    }

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps

    private val _filterMode = MutableStateFlow(FilterMode.FAVORITES)
    val filterMode: StateFlow<FilterMode> = _filterMode

    private val _hideSystemApps = MutableStateFlow(true)
    val hideSystemApps: StateFlow<Boolean> = _hideSystemApps

    val filteredApps = combine(_installedApps, _filterMode, _hideSystemApps) { apps, mode, hideSystem ->
        val baseList = when (mode) {
            FilterMode.ALL -> apps
            FilterMode.FAVORITES -> apps.filter { it.isFavorite }
        }
        if (hideSystem) {
            baseList.filter { !it.isSystemApp }
        } else {
            baseList
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val favoriteUpdateCount = _installedApps.map { apps ->
        apps.count { it.isFavorite && isVersionDifferent(it.versionName, it.remoteVersion) }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    init {
        loadInstalledApps()
        viewModelScope.launch {
            Events.refreshAppsSignal.collect {
                loadInstalledApps()
            }
        }
    }

    fun setFilterMode(mode: FilterMode) {
        _filterMode.value = mode
    }

    fun toggleHideSystemApps() {
        _hideSystemApps.value = !_hideSystemApps.value
    }

    fun toggleFavorite(app: InstalledApp) {
        viewModelScope.launch {
            SettingsRepo.setFavorite(context, app.packageName, !app.isFavorite)
            loadInstalledApps()
        }
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val savedSettings = SettingsRepo.getAllSettings(context)
            
            val appList = apps.distinctBy { it.packageName }.map { appInfo ->
                val packageInfo = pm.getPackageInfo(appInfo.packageName, 0)
                val settings = savedSettings[appInfo.packageName]
                val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                InstalledApp(
                    name = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    icon = pm.getApplicationIcon(appInfo), // Pass Drawable directly to Coil
                    versionName = packageInfo.versionName ?: "unknown",
                    updateUrl = settings?.updateUrl.orEmpty(),
                    isFavorite = settings?.isFavorite == true,
                    isSystemApp = isSystem
                )
            }.sortedBy { it.name }
            
            _installedApps.value = appList
        }
    }

    fun saveUrl(packageName: String, url: String) {
        viewModelScope.launch {
            SettingsRepo.saveUrl(context, packageName, url)
            loadInstalledApps()
        }
    }

    fun checkAllUpdates() {
        val mode = _filterMode.value
        val appsToCheck = when (mode) {
            FilterMode.FAVORITES -> _installedApps.value.filter { it.isFavorite && it.updateUrl.isNotBlank() }
            FilterMode.ALL -> _installedApps.value.filter { it.updateUrl.isNotBlank() }
        }
        
        if (appsToCheck.isEmpty()) {
            if (mode == FilterMode.FAVORITES) {
                android.widget.Toast.makeText(context, "收藏列表中没有配置更新地址的应用", android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                val typeText = if (mode == FilterMode.FAVORITES) "收藏" else "全部"
                android.widget.Toast.makeText(context, "开始检测 $typeText 列表中的 ${appsToCheck.size} 个应用...", android.widget.Toast.LENGTH_SHORT).show()
            }
            
            val jobs = appsToCheck.map { app ->
                async(Dispatchers.IO) {
                    performCheckUpdate(app)
                }
            }
            jobs.awaitAll()
            
            // 计算当前检测结果
            val results = appsToCheck.mapNotNull { it.packageName.let { pkg -> _installedApps.value.find { a -> a.packageName == pkg } } }
            val updatedAppsCount = results.count { isVersionDifferent(it.versionName, it.remoteVersion) }
            val failedAppsCount = results.count { it.remoteStatus != "连接成功" }
            val incomparableAppsCount = results.count {
                it.remoteStatus == "连接成功" && !canCompareVersions(it.versionName, it.remoteVersion)
            }
            
            withContext(Dispatchers.Main) {
                when {
                    updatedAppsCount > 0 -> {
                        android.widget.Toast.makeText(context, "检测完成：发现 $updatedAppsCount 个应用有新版本", android.widget.Toast.LENGTH_LONG).show()
                    }
                    failedAppsCount > 0 -> {
                        android.widget.Toast.makeText(context, "检测完成：$failedAppsCount 个应用连接失败，请检查网络", android.widget.Toast.LENGTH_LONG).show()
                    }
                    incomparableAppsCount > 0 -> {
                        android.widget.Toast.makeText(context, "检测完成：$incomparableAppsCount 个应用的版本格式无法比较", android.widget.Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        android.widget.Toast.makeText(context, "检测完成：列表中的应用均为最新", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun checkUpdateStatus(app: InstalledApp) {
        if (app.updateUrl.isBlank() || app.isChecking) return
        viewModelScope.launch(Dispatchers.IO) {
            performCheckUpdate(app)
        }
    }

    private suspend fun performCheckUpdate(app: InstalledApp) {
        updateAppStatus(app.packageName, isChecking = true, remoteStatus = "正在连接...")
        try {
            val info = parseGitHubUrl(app.updateUrl)
            if (info != null) {
                val (owner, repo) = info
                // 尝试获取最新正式版
                val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
                val response = client.get(apiUrl) {
                    // 使用标准的浏览器 Header
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    header("Accept", "application/vnd.github+json")
                }
                
                if (response.status.value in 200..299) {
                    // ... (保持现有的解析逻辑)
                    val bodyStr = response.bodyAsText()
                    val releaseObj = org.json.JSONObject(bodyStr)
                    val latestVersion = releaseObj.optString("tag_name").takeIf { it.isNotBlank() }
                    val rawChangelog = releaseObj.optString("body").takeIf { it.isNotBlank() }
                    val changelog = rawChangelog?.replace("\\r\\n", "\n")?.replace("\\n", "\n")

                    val assets = mutableListOf<GitHubAsset>()
                    val assetsArray = releaseObj.optJSONArray("assets")
                    if (assetsArray != null) {
                        for (i in 0 until assetsArray.length()) {
                            val assetObj = assetsArray.getJSONObject(i)
                            val name = assetObj.optString("name", "")
                            val downloadUrl = assetObj.optString("browser_download_url", "")
                            if (name.endsWith(".apk", ignoreCase = true) && downloadUrl.isNotEmpty()) {
                                assets.add(GitHubAsset(
                                    name = name,
                                    downloadUrl = downloadUrl,
                                    isRecommended = isArchitectureMatch(name)
                                ))
                            }
                        }
                    }

                    updateAppStatus(app.packageName, remoteVersion = latestVersion, isChecking = false, assets = assets, changelog = changelog, remoteStatus = "连接成功")
                    return
                } else if (response.status.value == 403) {
                    updateAppStatus(app.packageName, isChecking = false, remoteStatus = "被限制 (API Rate Limit)")
                    return
                } else if (response.status.value == 404) {
                    updateAppStatus(app.packageName, remoteVersion = "未发布版本", isChecking = false, remoteStatus = "未找到版本")
                    return
                } else {
                    updateAppStatus(app.packageName, isChecking = false, remoteStatus = "服务器错误 (${response.status.value})")
                    return
                }
            }
            updateAppStatus(app.packageName, isChecking = false, remoteStatus = "地址解析失败")
        } catch (e: Exception) {
            e.printStackTrace()
            // 提取简短的系统报错信息
            val errorMsg = e.message?.take(30) ?: e.javaClass.simpleName
            updateAppStatus(app.packageName, isChecking = false, remoteStatus = "失败: $errorMsg")
        }
    }

    private fun isArchitectureMatch(fileName: String): Boolean {
        val abis = android.os.Build.SUPPORTED_ABIS
        val name = fileName.lowercase()
        
        // 1. 优先完全匹配
        if (abis.any { name.contains(it.lowercase()) }) return true
        
        // 2. 常见别名匹配
        return when {
            abis.contains("arm64-v8a") && (name.contains("arm64") || name.contains("aarch64") || name.contains("v8a")) -> true
            abis.contains("armeabi-v7a") && (name.contains("v7a") || name.contains("armv7") || name.contains("armeabi")) -> true
            (abis.contains("x86") || abis.contains("x86_64")) && (name.contains("x86") || name.contains("amd64")) -> true
            else -> false
        }
    }

    private fun parseGitHubUrl(inputUrl: String): Pair<String, String>? {
        val trimmed = inputUrl.trim().removeSuffix("/")
        // Case 1: Full URL or domain-starting URL
        val fullRegex = Regex("(?:https?://)?(?:www\\.)?github\\.com/([^/]+)/([^/\\s#?]+)")
        val fullMatch = fullRegex.find(trimmed)
        if (fullMatch != null) {
            return Pair(fullMatch.groupValues[1], fullMatch.groupValues[2])
        }
        // Case 2: owner/repo format
        val shortRegex = Regex("^([^/]+)/([^/\\s#?]+)$")
        val shortMatch = shortRegex.find(trimmed)
        if (shortMatch != null) {
            return Pair(shortMatch.groupValues[1], shortMatch.groupValues[2])
        }
        return null
    }

    private fun updateAppStatus(packageName: String, remoteVersion: String? = null, isChecking: Boolean = false, assets: List<GitHubAsset>? = null, changelog: String? = null, remoteStatus: String? = null) {
        _installedApps.update { list ->
            list.map {
                if (it.packageName == packageName) {
                    it.copy(
                        remoteVersion = remoteVersion ?: it.remoteVersion,
                        isChecking = isChecking,
                        assets = assets ?: it.assets,
                        changelog = changelog ?: it.changelog,
                        remoteStatus = remoteStatus ?: it.remoteStatus
                    )
                } else it
            }
        }
    }

    fun isVersionDifferent(local: String, remote: String?): Boolean {
        return VersionComparator.isRemoteNewer(local, remote)
    }

    fun canCompareVersions(local: String, remote: String?): Boolean {
        return VersionComparator.canCompare(local, remote)
    }

    fun downloadAndInstall(app: InstalledApp, specificUrl: String? = null) {
        val urlToUse = specificUrl ?: app.updateUrl
        if (urlToUse.isBlank()) {
            android.widget.Toast.makeText(context, "没有可用的下载地址", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        android.widget.Toast.makeText(context, "开始下载 ${app.name}", android.widget.Toast.LENGTH_SHORT).show()
        
        viewModelScope.launch(Dispatchers.IO) {
            val transferId = "download:${app.packageName}:${System.nanoTime()}"
            val fileName = "${app.name}_update.apk"
            try {
                TransferManager.startTransfer(transferId, fileName)
                
                val finalUrl = if (specificUrl != null) specificUrl else resolveDownloadUrl(urlToUse)
                if (finalUrl.isBlank() || !finalUrl.startsWith("http")) {
                    throw Exception("无法解析下载地址: $finalUrl")
                }

                val destFile = File(context.cacheDir, "incoming/$fileName")
                if (destFile.parentFile?.exists() == false) destFile.parentFile?.mkdirs()

                val response: HttpResponse = client.get(finalUrl) {
                    onDownload { bytesSentTotal, contentLength ->
                        if (contentLength > 0) {
                            val progress = ((bytesSentTotal.toDouble() / contentLength) * 100).toInt()
                            TransferManager.updateProgress(transferId, fileName, progress)
                        }
                    }
                }
                if (response.status.value !in 200..299) {
                    throw Exception("下载失败，HTTP ${response.status.value}")
                }

                val channel: ByteReadChannel = response.bodyAsChannel()
                destFile.outputStream().use { output ->
                    while (!channel.isClosedForRead) {
                        val packet = channel.readRemaining(8192)
                        while (!packet.isEmpty) {
                            output.write(packet.readBytes())
                        }
                    }
                }

                TransferManager.markComplete(transferId)
                triggerInstallation(destFile)
            } catch (e: Exception) {
                e.printStackTrace()
                TransferManager.markFailed(transferId, e.message ?: "下载失败")
            }
        }
    }

    private suspend fun resolveDownloadUrl(inputUrl: String): String {
        val trimmedUrl = inputUrl.trim().removeSuffix("/")
        
        // 如果已经是 APK 直链，直接返回
        if (trimmedUrl.endsWith(".apk", ignoreCase = true)) return trimmedUrl
        
        val info = parseGitHubUrl(trimmedUrl) ?: return trimmedUrl
        val (owner, repo) = info
        
        val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
        
        return try {
            val response = client.get(apiUrl) {
                header("User-Agent", "PushTV-App")
                header("Accept", "application/vnd.github+json")
            }
            if (response.status.value in 200..299) {
                val body = response.bodyAsText()
                // 使用正则查找第一个以 .apk 结尾的下载链接
                val regex = Regex("\"browser_download_url\":\\s*\"([^\"]+?\\.apk)\"")
                val match = regex.find(body)
                match?.groupValues?.get(1) ?: trimmedUrl
            } else {
                trimmedUrl
            }
        } catch (e: Exception) {
            e.printStackTrace()
            trimmedUrl
        }
    }

    private fun triggerInstallation(file: File) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    }

    override fun onCleared() {
        client.close()
        super.onCleared()
    }
}
