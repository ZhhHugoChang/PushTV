package com.example.pushtv.ui.software

import com.example.pushtv.logging.AppLog

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pushtv.data.SettingsRepo
import com.example.pushtv.data.GitHubProxyNode
import com.example.pushtv.data.GitHubProxySettings
import com.example.pushtv.data.TransferManager
import com.example.pushtv.network.GitHubProxyClient
import com.example.pushtv.network.ProgressUpdateLimiter
import com.example.pushtv.utils.Events
import com.example.pushtv.utils.CompatibleFileMover
import com.example.pushtv.utils.VersionComparator
import com.example.pushtv.utils.installApk
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.CancellationException as CoroutineCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import org.json.JSONObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val PUSH_TV_PACKAGE = "com.example.pushtv"

data class GitHubAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long = 0,
    val isRecommended: Boolean = false,
    val sha256: String? = null
)

data class InstalledApp(
    val name: String,
    val packageName: String,
    val versionName: String,
    val updateUrl: String = "",
    val remoteVersion: String? = null,
    val isChecking: Boolean = false,
    val remoteStatus: String? = null, // 新增：连接状态描述
    val assets: List<GitHubAsset> = emptyList(),
    val changelog: String? = null,
    val isFavorite: Boolean = false,
    val isSystemApp: Boolean = false
) {
    val releaseVersionOrder: VersionComparator.Result
        get() {
            if (isChecking || remoteStatus != "连接成功") return VersionComparator.Result.UNKNOWN
            return VersionComparator.compare(versionName, remoteVersion)
        }

    val hasNewRelease: Boolean
        get() = releaseVersionOrder == VersionComparator.Result.REMOTE_NEWER

    val recommendedUpdateAsset: GitHubAsset?
        get() = if (hasNewRelease) assets.firstOrNull { it.isRecommended } else null

    val manualReleaseAssets: List<GitHubAsset>
        get() {
            if (isChecking || remoteStatus != "连接成功" || remoteVersion.isNullOrBlank()) {
                return emptyList()
            }
            val recommended = recommendedUpdateAsset
            return assets.filter { it != recommended }
        }

}

enum class FilterMode {
    ALL, FAVORITES
}

class SoftwareViewModel(application: Application) : AndroidViewModel(application) {
    private data class DownloadRequest(
        val app: InstalledApp,
        val url: String,
        val isDirectAsset: Boolean
    )

    private data class ResolvedDownload(val url: String, val sha256: String? = null)
    private class RetryableDownload(message: String) : IOException(message)
    private class LocalWriteFailure(cause: IOException) : Exception("无法写入 APK 文件", cause)
    internal data class ProxyInstallConfirmation(val transferId: String, val appName: String, val route: String)

    private val context = application.applicationContext
    private val clientDelegate = lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 15000
            }
            // 自动跟随重定向
            followRedirects = true
        }
    }
    private val client by clientDelegate
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val downloadRequests = ConcurrentHashMap<String, DownloadRequest>()
    private val installDecisions = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val pendingInstalls = MutableStateFlow<List<ProxyInstallConfirmation>>(emptyList())
    internal val proxyInstallConfirmations = pendingInstalls.asStateFlow()
    val githubProxySettings = SettingsRepo.observeGitHubProxy(context)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GitHubProxySettings())
    private val appIconCache = ConcurrentHashMap<String, Any>()
    private var loadAppsJob: Job? = null
    private val githubTokenMutex = Mutex()
    @Volatile
    private var githubTokenLoaded = false
    private var githubTokenCache = ""
    @Volatile
    private var hasLoadedApps = false

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    private val _isLoadingApps = MutableStateFlow(false)
    val isLoadingApps: StateFlow<Boolean> = _isLoadingApps.asStateFlow()

    private val _hasGitHubToken = MutableStateFlow(false)
    val hasGitHubToken: StateFlow<Boolean> = _hasGitHubToken.asStateFlow()

    fun selectGitHubProxy(id: String) {
        viewModelScope.launch {
            try {
                SettingsRepo.selectGitHubProxy(context, id)
            } catch (e: CoroutineCancellationException) {
                throw e
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, e.message ?: "代理设置保存失败", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    internal fun confirmProxyInstall(transferId: String, proceed: Boolean) {
        installDecisions[transferId]?.complete(proceed)
    }

    private val _filterMode = MutableStateFlow(FilterMode.FAVORITES)
    val filterMode: StateFlow<FilterMode> = _filterMode

    private val _hideSystemApps = MutableStateFlow(true)
    val hideSystemApps: StateFlow<Boolean> = _hideSystemApps

    val filteredApps = combine(installedApps, _filterMode, _hideSystemApps) { apps, mode, hideSystem ->
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

    val favoriteUpdateCount = installedApps.map { apps ->
        apps.count { it.isFavorite && it.hasNewRelease }
    }.stateIn(viewModelScope, SharingStarted.Lazily, 0)

    init {
        viewModelScope.launch {
            Events.refreshAppsSignal.collect {
                if (hasLoadedApps) loadInstalledApps()
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            Events.githubTokenChangedSignal.collect { reloadGitHubToken() }
        }
    }

    fun ensureAppsLoaded() {
        if (!hasLoadedApps && loadAppsJob?.isActive != true) loadInstalledApps()
        viewModelScope.launch(Dispatchers.IO) { getGitHubToken() }
    }

    fun saveGitHubToken(token: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val normalized = token.trim()
            SettingsRepo.saveGitHubToken(context, normalized)
            githubTokenMutex.withLock {
                githubTokenCache = normalized
                githubTokenLoaded = true
            }
            _hasGitHubToken.value = normalized.isNotEmpty()
            withContext(Dispatchers.Main) {
                val message = if (normalized.isEmpty()) "GitHub Token 已清除" else "GitHub Token 已保存"
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun getGitHubToken(): String {
        if (!githubTokenLoaded) {
            reloadGitHubToken()
        }
        return githubTokenCache
    }

    private suspend fun reloadGitHubToken() {
        githubTokenMutex.withLock {
            githubTokenCache = SettingsRepo.getGitHubToken(context)
            githubTokenLoaded = true
            _hasGitHubToken.value = githubTokenCache.isNotEmpty()
        }
    }

    fun getCachedAppIcon(packageName: String): Any? = appIconCache[packageName]

    suspend fun loadAppIcon(packageName: String): Any? {
        appIconCache[packageName]?.let { return it }
        return withContext(Dispatchers.IO) {
            appIconCache[packageName] ?: runCatching {
                context.packageManager.getApplicationIcon(packageName)
            }.getOrNull()?.let { icon ->
                appIconCache.putIfAbsent(packageName, icon) ?: icon
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
            val isFavorite = !app.isFavorite
            SettingsRepo.setFavorite(context, app.packageName, isFavorite)
            _installedApps.update { apps ->
                apps.map { if (it.packageName == app.packageName) it.copy(isFavorite = isFavorite) else it }
            }
        }
    }

    fun loadInstalledApps() {
        loadAppsJob?.cancel()
        loadAppsJob = viewModelScope.launch(Dispatchers.IO) {
            _isLoadingApps.value = true
            try {
                val pm = context.packageManager
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val savedSettings = SettingsRepo.getAllSettings(context)
                val previousApps = _installedApps.value.associateBy { it.packageName }

                val appList = apps.distinctBy { it.packageName }.mapNotNull { appInfo ->
                    runCatching {
                        val packageInfo = pm.getPackageInfo(appInfo.packageName, 0)
                        val settings = savedSettings[appInfo.packageName]
                        val previous = previousApps[appInfo.packageName]
                        val updateUrl = settings?.updateUrl.orEmpty()
                        val keepsRemoteState = previous?.updateUrl == updateUrl

                        InstalledApp(
                            name = pm.getApplicationLabel(appInfo).toString(),
                            packageName = appInfo.packageName,
                            versionName = packageInfo.versionName ?: "unknown",
                            updateUrl = updateUrl,
                            remoteVersion = if (keepsRemoteState) previous.remoteVersion else null,
                            isChecking = keepsRemoteState && previous.isChecking,
                            remoteStatus = if (keepsRemoteState) previous.remoteStatus else null,
                            assets = if (keepsRemoteState) previous.assets else emptyList(),
                            changelog = if (keepsRemoteState) previous.changelog else null,
                            isFavorite = settings?.isFavorite == true,
                            isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        )
                    }.getOrNull()
                }.sortedBy { it.name }

                val installedPackages = appList.mapTo(HashSet()) { it.packageName }
                appIconCache.keys.removeAll { it !in installedPackages }
                _installedApps.value = appList
                hasLoadedApps = true
            } finally {
                if (isActive) _isLoadingApps.value = false
            }
        }
    }

    fun saveUrl(packageName: String, url: String) {
        viewModelScope.launch {
            val normalizedUrl = url.trim()
            SettingsRepo.saveUrl(context, packageName, normalizedUrl)
            _installedApps.update { apps ->
                apps.map {
                    if (it.packageName == packageName) {
                        it.copy(
                            updateUrl = normalizedUrl,
                            remoteVersion = null,
                            remoteStatus = null,
                            assets = emptyList(),
                            changelog = null
                        )
                    } else it
                }
            }
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

        AppLog.info("UpdateCheck", "Batch check started: ${appsToCheck.size} applications")

        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                val typeText = if (mode == FilterMode.FAVORITES) "收藏" else "全部"
                android.widget.Toast.makeText(context, "开始检测 $typeText 列表中的 ${appsToCheck.size} 个应用...", android.widget.Toast.LENGTH_SHORT).show()
            }
            
            val requestLimit = Semaphore(4)
            val jobs = appsToCheck.map { app ->
                async(Dispatchers.IO) {
                    requestLimit.withPermit { performCheckUpdate(app) }
                }
            }
            jobs.awaitAll()
            
            val resultsByPackage = _installedApps.value.associateBy { it.packageName }
            val results = appsToCheck.mapNotNull { resultsByPackage[it.packageName] }
            val updatedAppsCount = results.count { it.hasNewRelease }
            val failedAppsCount = results.count { it.remoteStatus != "连接成功" }
            val incomparableAppsCount = results.count {
                it.remoteStatus == "连接成功" && it.releaseVersionOrder == VersionComparator.Result.UNKNOWN
            }
            AppLog.info(
                "UpdateCheck",
                "Batch check completed: total=${appsToCheck.size}, updates=$updatedAppsCount, failed=$failedAppsCount, incomparable=$incomparableAppsCount"
            )
            
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
                        android.widget.Toast.makeText(context, "检测完成：列表中未发现新版本", android.widget.Toast.LENGTH_SHORT).show()
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
        val startedAt = System.currentTimeMillis()
        AppLog.info("UpdateCheck", "Checking ${app.name} (${app.packageName})")
        updateAppStatus(app, isChecking = true, remoteStatus = "正在连接...")
        try {
            val info = parseGitHubUrl(app.updateUrl)
            if (info != null) {
                val (owner, repo) = info
                // 尝试获取最新正式版
                val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
                val githubToken = getGitHubToken()
                val response = client.get(apiUrl) {
                    // 使用标准的浏览器 Header
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    header("Accept", "application/vnd.github+json")
                    if (githubToken.isNotEmpty()) header("Authorization", "Bearer $githubToken")
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
                                    sha256 = GitHubProxySettings.sha256Digest(assetObj.optString("digest"))
                                ))
                            }
                        }
                    }

                    val deviceAssets = if (
                        app.packageName == PUSH_TV_PACKAGE &&
                        owner.equals("ZhhHugoChang", ignoreCase = true) &&
                        repo.equals("PushTV", ignoreCase = true)
                    ) {
                        pushTvApksForDevice(assets, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.asList(), latestVersion)
                    } else {
                        assets
                    }
                    updateAppStatus(app, remoteVersion = latestVersion, isChecking = false, assets = deviceAssets, changelog = changelog, remoteStatus = "连接成功")
                    AppLog.info(
                        "UpdateCheck",
                        "Checked ${app.packageName}: version=${latestVersion.orEmpty()}, apkAssets=${assets.size}, durationMs=${System.currentTimeMillis() - startedAt}"
                    )
                    return
                } else if (response.status.value == 401) {
                    updateAppStatus(app, isChecking = false, remoteStatus = "GitHub Token 无效")
                    AppLog.warn("UpdateCheck", "Check rejected for ${app.packageName}: GitHub Token invalid")
                    return
                } else if (response.status.value == 403 || response.status.value == 429) {
                    updateAppStatus(app, isChecking = false, remoteStatus = "被限制 (API Rate Limit)")
                    AppLog.warn("UpdateCheck", "Check rate limited for ${app.packageName}: HTTP ${response.status.value}")
                    return
                } else if (response.status.value == 404) {
                    updateAppStatus(app, remoteVersion = "未发布版本", isChecking = false, remoteStatus = "未找到版本")
                    AppLog.warn("UpdateCheck", "No release found for ${app.packageName}: HTTP 404")
                    return
                } else {
                    updateAppStatus(app, isChecking = false, remoteStatus = "服务器错误 (${response.status.value})")
                    AppLog.warn("UpdateCheck", "Check failed for ${app.packageName}: HTTP ${response.status.value}")
                    return
                }
            }
            updateAppStatus(app, isChecking = false, remoteStatus = "地址解析失败")
            AppLog.warn("UpdateCheck", "Update address could not be parsed for ${app.packageName}")
        } catch (e: Exception) {
            AppLog.error("UpdateCheck", "Update check failed for ${app.packageName}", e)
            // 提取简短的系统报错信息
            val errorMsg = e.message?.take(30) ?: e.javaClass.simpleName
            updateAppStatus(app, isChecking = false, remoteStatus = "失败: $errorMsg")
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

    private fun updateAppStatus(
        checkedApp: InstalledApp,
        remoteVersion: String? = null,
        isChecking: Boolean = false,
        assets: List<GitHubAsset>? = null,
        changelog: String? = null,
        remoteStatus: String? = null
    ) {
        _installedApps.update { list ->
            list.map {
                if (it.packageName == checkedApp.packageName && it.updateUrl == checkedApp.updateUrl) {
                    it.copy(
                        remoteVersion = if (assets != null) remoteVersion else remoteVersion ?: it.remoteVersion,
                        isChecking = isChecking,
                        assets = assets ?: it.assets,
                        changelog = changelog ?: it.changelog,
                        remoteStatus = remoteStatus ?: it.remoteStatus
                    )
                } else it
            }
        }
    }

    fun downloadAndInstall(app: InstalledApp, specificUrl: String? = null) {
        val urlToUse = specificUrl ?: app.updateUrl
        if (urlToUse.isBlank()) {
            android.widget.Toast.makeText(context, "没有可用的下载地址", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val transferId = downloadTransferId(app.packageName)
        val fileName = "${app.name}_update.apk"
        if (!TransferManager.startTransfer(transferId, fileName)) {
            android.widget.Toast.makeText(context, "${app.name} 正在下载", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        android.widget.Toast.makeText(context, "开始下载 ${app.name}", android.widget.Toast.LENGTH_SHORT).show()
        AppLog.info("Download", "Download started: ${app.name} (${app.packageName})")
        val request = DownloadRequest(app, urlToUse, specificUrl != null)
        downloadRequests[transferId] = request
        launchDownload(transferId, fileName, request)
    }

    fun cancelDownload(packageName: String) {
        AppLog.warn("Download", "Download cancellation requested: $packageName")
        downloadJobs[downloadTransferId(packageName)]?.cancel()
    }

    fun cancelAllDownloads() {
        if (downloadJobs.isNotEmpty()) {
            AppLog.warn("Download", "Cancel all downloads requested: count=${downloadJobs.size}")
        }
        downloadJobs.keys.toList().forEach { transferId ->
            downloadJobs[transferId]?.cancel()
            TransferManager.remove(transferId)
        }
        downloadRequests.clear()
    }

    fun retryDownload(packageName: String) {
        val transferId = downloadTransferId(packageName)
        val request = downloadRequests[transferId] ?: return
        val fileName = "${request.app.name}_update.apk"
        if (!TransferManager.startTransfer(transferId, fileName)) return
        AppLog.info("Download", "Download retry started: ${request.app.name} ($packageName)")
        launchDownload(transferId, fileName, request)
    }

    private fun launchDownload(
        transferId: String,
        displayFileName: String,
        request: DownloadRequest
    ) {
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val startedAt = System.currentTimeMillis()
            val downloadDir = File(context.cacheDir, "downloads").apply { mkdirs() }
            val partFile = File(downloadDir, "${request.app.packageName}.apk.part")
            val finalFile = File(downloadDir, "${request.app.packageName}-${System.currentTimeMillis()}.apk")
            partFile.delete()

            try {
                TransferManager.markResolving(transferId)
                val knownAsset = request.app.assets.firstOrNull { it.downloadUrl == request.url }
                val download = if (request.isDirectAsset) {
                    ResolvedDownload(request.url, knownAsset?.sha256)
                } else {
                    resolveDownload(request.url)
                }
                if (!download.url.startsWith("http")) throw Exception("无法解析下载地址")

                val isReleaseApk = GitHubProxySettings.isReleaseApk(download.url)
                val proxySettings = if (isReleaseApk) {
                    SettingsRepo.observeGitHubProxy(context).first()
                } else GitHubProxySettings()
                val accelerationEnabled = proxySettings.selection != GitHubProxySettings.DIRECT
                fun routeClient(node: GitHubProxyNode?) =
                    if (node == null) client else GitHubProxyClient.client
                val routes = GitHubProxyClient.selectRoutes(proxySettings) { node ->
                    GitHubProxyClient.probeDownload(routeClient(node), node?.downloadUrl(download.url) ?: download.url)
                }
                var proxyDigest = download.sha256 ?: knownAsset?.sha256
                var digestLookupDone = knownAsset != null || proxyDigest != null
                for ((index, node) in routes.withIndex()) {
                    val url = node?.downloadUrl(download.url) ?: download.url
                    val route = node?.name ?: "直连"
                    if (isReleaseApk) TransferManager.startRoute(transferId, route)
                    if (node != null && !digestLookupDone) {
                        proxyDigest = lookupReleaseDigest(download.url)
                        digestLookupDone = true
                    }
                    val digest = proxyDigest.takeIf { node != null }
                    val actualDigest = try {
                        fetchApk(
                            routeClient(node),
                            url, partFile, transferId, displayFileName,
                            fastConnect = accelerationEnabled, verifyDigest = digest != null,
                            retryProxyStatus = accelerationEnabled
                        )
                    } catch (e: IOException) {
                        if (index == routes.lastIndex) throw e
                        continue
                    }
                    if (digest != null && actualDigest != digest) {
                        throw Exception("APK 的 SHA-256 校验失败，已停止安装")
                    }
                    if (!isValidApk(partFile)) {
                        if (index < routes.lastIndex) continue
                        throw Exception("下载内容不是有效的 APK")
                    }
                    if (node != null && digest == null && !awaitProxyInstall(transferId, request.app.name, route)) {
                        throw CoroutineCancellationException("用户取消安装")
                    }
                    break
                }
                moveCompletedDownload(partFile, finalFile)
                installApk(context, finalFile)
                TransferManager.markComplete(transferId)
                AppLog.info(
                    "Download",
                    "Download completed: ${request.app.packageName}, file=${finalFile.name}, bytes=${finalFile.length()}, durationMs=${System.currentTimeMillis() - startedAt}"
                )
            } catch (e: CoroutineCancellationException) {
                partFile.delete()
                finalFile.delete()
                TransferManager.markCanceled(transferId)
                AppLog.warn(
                    "Download",
                    "Download canceled: ${request.app.packageName}, durationMs=${System.currentTimeMillis() - startedAt}"
                )
                throw e
            } catch (e: Exception) {
                AppLog.error(
                    "Download",
                    "Download failed: ${request.app.packageName}, durationMs=${System.currentTimeMillis() - startedAt}",
                    e
                )
                partFile.delete()
                finalFile.delete()
                TransferManager.markFailed(transferId, e.message ?: "下载失败")
            }
        }
        downloadJobs[transferId] = job
        job.invokeOnCompletion { downloadJobs.remove(transferId, job) }
        job.start()
    }

    private suspend fun fetchApk(
        httpClient: HttpClient, url: String, partFile: File, transferId: String,
        displayFileName: String, fastConnect: Boolean, verifyDigest: Boolean, retryProxyStatus: Boolean
    ): String? {
        val progressLimiter = ProgressUpdateLimiter()
        val digest = if (verifyDigest) MessageDigest.getInstance("SHA-256") else null
        return httpClient.prepareGet(url) {
            timeout {
                requestTimeoutMillis = DOWNLOAD_TIMEOUT_MILLIS
                socketTimeoutMillis = DOWNLOAD_SOCKET_TIMEOUT_MILLIS
                if (fastConnect) connectTimeoutMillis = 5_000
            }
            onDownload { received, length ->
                if (length > 0 && progressLimiter.shouldEmit(received, length)) {
                    TransferManager.updateProgress(transferId, displayFileName, (received * 100.0 / length).toInt(), length)
                }
            }
        }.execute { response ->
            val status = response.status.value
            if (status !in 200..299) {
                val message = "下载失败，HTTP $status"
                if (retryProxyStatus || status in 500..599 || status == 408 || status == 429) {
                    throw RetryableDownload(message)
                }
                throw Exception(message)
            }
            TransferManager.markTransferring(transferId)
            val channel = response.bodyAsChannel()
            val output = try {
                partFile.outputStream().buffered()
            } catch (e: IOException) {
                throw LocalWriteFailure(e)
            }
            output.use {
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                while (true) {
                    val count = channel.readAvailable(buffer, 0, buffer.size)
                    if (count == -1) break
                    if (count == 0) continue
                    try {
                        it.write(buffer, 0, count)
                    } catch (e: IOException) {
                        throw LocalWriteFailure(e)
                    }
                    digest?.update(buffer, 0, count)
                }
                try {
                    it.flush()
                } catch (e: IOException) {
                    throw LocalWriteFailure(e)
                }
            }
            digest?.digest()?.joinToString("") { "%02x".format(it) }
        }
    }

    private suspend fun awaitProxyInstall(transferId: String, appName: String, route: String): Boolean {
        val decision = CompletableDeferred<Boolean>()
        installDecisions[transferId] = decision
        pendingInstalls.update { it + ProxyInstallConfirmation(transferId, appName, route) }
        return try {
            decision.await()
        } finally {
            pendingInstalls.update { list -> list.filterNot { it.transferId == transferId } }
            installDecisions.remove(transferId, decision)
        }
    }

    private fun downloadTransferId(packageName: String) = "download:$packageName"

    private fun isValidApk(file: File): Boolean = runCatching {
        file.length() > 0 && ZipFile(file).use { it.getEntry("AndroidManifest.xml") != null }
    }.getOrDefault(false)

    private fun moveCompletedDownload(source: File, destination: File) {
        CompatibleFileMover.moveReplacing(source, destination)
    }

    private suspend fun resolveDownload(inputUrl: String): ResolvedDownload {
        val trimmedUrl = inputUrl.trim().removeSuffix("/")
        if (trimmedUrl.endsWith(".apk", ignoreCase = true)) return ResolvedDownload(trimmedUrl)
        val info = parseGitHubUrl(trimmedUrl) ?: return ResolvedDownload(trimmedUrl)
        val (owner, repo) = info
        return releaseDownloads("https://api.github.com/repos/$owner/$repo/releases/latest").firstOrNull()
            ?: ResolvedDownload(trimmedUrl)
    }

    private suspend fun lookupReleaseDigest(assetUrl: String): String? {
        val path = assetUrl.toHttpUrlOrNull()?.encodedPathSegments ?: return null
        val (owner, repo) = path
        val latestAlias = path[3] == "latest"
        val release = if (latestAlias) "latest" else "tags/${path[4]}"
        return releaseDownloads("https://api.github.com/repos/$owner/$repo/releases/$release", 5_000).firstOrNull {
            val candidate = it.url.toHttpUrlOrNull()?.encodedPathSegments ?: return@firstOrNull false
            if (latestAlias) candidate.lastOrNull() == path.last() else candidate == path
        }?.sha256
    }

    private suspend fun releaseDownloads(apiUrl: String, timeoutMillis: Long = 15_000): List<ResolvedDownload> {
        return try {
            val githubToken = getGitHubToken()
            val response = client.get(apiUrl) {
                header("User-Agent", "PushTV-App")
                header("Accept", "application/vnd.github+json")
                if (githubToken.isNotEmpty()) header("Authorization", "Bearer $githubToken")
                timeout {
                    requestTimeoutMillis = timeoutMillis
                    connectTimeoutMillis = minOf(10_000, timeoutMillis)
                    socketTimeoutMillis = timeoutMillis
                }
            }
            if (response.status.value !in 200..299) return emptyList()
            val assets = JSONObject(response.bodyAsText()).optJSONArray("assets") ?: return emptyList()
            (0 until assets.length()).mapNotNull { index ->
                val asset = assets.optJSONObject(index) ?: return@mapNotNull null
                val url = asset.optString("browser_download_url")
                if (!url.endsWith(".apk", ignoreCase = true)) return@mapNotNull null
                ResolvedDownload(url, GitHubProxySettings.sha256Digest(asset.optString("digest")))
            }
        } catch (e: CoroutineCancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.error("UpdateCheck", "Could not resolve GitHub release URL", e)
            emptyList()
        }
    }

    override fun onCleared() {
        downloadJobs.values.forEach { it.cancel() }
        if (clientDelegate.isInitialized()) client.close()
        super.onCleared()
    }

    companion object {
        private const val DOWNLOAD_TIMEOUT_MILLIS = 10 * 60 * 1000L
        private const val DOWNLOAD_SOCKET_TIMEOUT_MILLIS = 30_000L
        private const val DOWNLOAD_BUFFER_SIZE = 128 * 1024
    }
}
