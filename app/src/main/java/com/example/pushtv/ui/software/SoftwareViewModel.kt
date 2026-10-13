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
import kotlinx.coroutines.CancellationException as CoroutineCancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

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
    val remoteStatus: String? = null, // 鏂板锛氳繛鎺ョ姸鎬佹弿杩?
    val assets: List<GitHubAsset> = emptyList(),
    val changelog: String? = null,
    val isFavorite: Boolean = false,
    val isSystemApp: Boolean = false
)

enum class FilterMode {
    ALL, FAVORITES
}

class SoftwareViewModel(application: Application) : AndroidViewModel(application) {
    private data class DownloadRequest(
        val app: InstalledApp,
        val url: String,
        val isDirectAsset: Boolean
    )

    private val context = application.applicationContext
    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15000
            connectTimeoutMillis = 10000
            socketTimeoutMillis = 15000
        }
        // 鑷姩璺熼殢閲嶅畾鍚?
        followRedirects = true
    }
    private val downloadJobs = ConcurrentHashMap<String, Job>()
    private val downloadRequests = ConcurrentHashMap<String, DownloadRequest>()
    private var loadAppsJob: Job? = null

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
                        icon = previous?.icon ?: pm.getApplicationIcon(appInfo),
                        versionName = packageInfo.versionName ?: "unknown",
                        updateUrl = updateUrl,
                        remoteVersion = if (keepsRemoteState) previous?.remoteVersion else null,
                        isChecking = keepsRemoteState && previous?.isChecking == true,
                        remoteStatus = if (keepsRemoteState) previous?.remoteStatus else null,
                        assets = if (keepsRemoteState) previous?.assets.orEmpty() else emptyList(),
                        changelog = if (keepsRemoteState) previous?.changelog else null,
                        isFavorite = settings?.isFavorite == true,
                        isSystemApp = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }.getOrNull()
            }.sortedBy { it.name }
            
            _installedApps.value = appList
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
                android.widget.Toast.makeText(context, "鏀惰棌鍒楄〃涓病鏈夐厤缃洿鏂板湴鍧€鐨勫簲鐢?, android.widget.Toast.LENGTH_SHORT).show()
            }
            return
        }

        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                val typeText = if (mode == FilterMode.FAVORITES) "鏀惰棌" else "鍏ㄩ儴"
                android.widget.Toast.makeText(context, "寮€濮嬫娴?$typeText 鍒楄〃涓殑 ${appsToCheck.size} 涓簲鐢?..", android.widget.Toast.LENGTH_SHORT).show()
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
            val updatedAppsCount = results.count { isVersionDifferent(it.versionName, it.remoteVersion) }
            val failedAppsCount = results.count { it.remoteStatus != "杩炴帴鎴愬姛" }
            val incomparableAppsCount = results.count {
                it.remoteStatus == "杩炴帴鎴愬姛" && !canCompareVersions(it.versionName, it.remoteVersion)
            }
            
            withContext(Dispatchers.Main) {
                when {
                    updatedAppsCount > 0 -> {
                        android.widget.Toast.makeText(context, "妫€娴嬪畬鎴愶細鍙戠幇 $updatedAppsCount 涓簲鐢ㄦ湁鏂扮増鏈?, android.widget.Toast.LENGTH_LONG).show()
                    }
                    failedAppsCount > 0 -> {
                        android.widget.Toast.makeText(context, "妫€娴嬪畬鎴愶細$failedAppsCount 涓簲鐢ㄨ繛鎺ュけ璐ワ紝璇锋鏌ョ綉缁?, android.widget.Toast.LENGTH_LONG).show()
                    }
                    incomparableAppsCount > 0 -> {
                        android.widget.Toast.makeText(context, "妫€娴嬪畬鎴愶細$incomparableAppsCount 涓簲鐢ㄧ殑鐗堟湰鏍煎紡鏃犳硶姣旇緝", android.widget.Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        android.widget.Toast.makeText(context, "妫€娴嬪畬鎴愶細鍒楄〃涓殑搴旂敤鍧囦负鏈€鏂?, android.widget.Toast.LENGTH_SHORT).show()
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
        updateAppStatus(app.packageName, isChecking = true, remoteStatus = "姝ｅ湪杩炴帴...")
        try {
            val info = parseGitHubUrl(app.updateUrl)
            if (info != null) {
                val (owner, repo) = info
                // 灏濊瘯鑾峰彇鏈€鏂版寮忕増
                val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
                val response = client.get(apiUrl) {
                    // 浣跨敤鏍囧噯鐨勬祻瑙堝櫒 Header
                    header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    header("Accept", "application/vnd.github+json")
                }
                
                if (response.status.value in 200..299) {
                    // ... (淇濇寔鐜版湁鐨勮В鏋愰€昏緫)
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

                    updateAppStatus(app.packageName, remoteVersion = latestVersion, isChecking = false, assets = assets, changelog = changelog, remoteStatus = "杩炴帴鎴愬姛")
                    return
                } else if (response.status.value == 403) {
                    updateAppStatus(app.packageName, isChecking = false, remoteStatus = "琚檺鍒?(API Rate Limit)")
                    return
                } else if (response.status.value == 404) {
                    updateAppStatus(app.packageName, remoteVersion = "鏈彂甯冪増鏈?, isChecking = false, remoteStatus = "鏈壘鍒扮増鏈?)
                    return
                } else {
                    updateAppStatus(app.packageName, isChecking = false, remoteStatus = "鏈嶅姟鍣ㄩ敊璇?(${response.status.value})")
                    return
                }
            }
            updateAppStatus(app.packageName, isChecking = false, remoteStatus = "鍦板潃瑙ｆ瀽澶辫触")
        } catch (e: Exception) {
            e.printStackTrace()
            // 鎻愬彇绠€鐭殑绯荤粺鎶ラ敊淇℃伅
            val errorMsg = e.message?.take(30) ?: e.javaClass.simpleName
            updateAppStatus(app.packageName, isChecking = false, remoteStatus = "澶辫触: $errorMsg")
        }
    }

    private fun isArchitectureMatch(fileName: String): Boolean {
        val abis = android.os.Build.SUPPORTED_ABIS
        val name = fileName.lowercase()
        
        // 1. 浼樺厛瀹屽叏鍖归厤
        if (abis.any { name.contains(it.lowercase()) }) return true
        
        // 2. 甯歌鍒悕鍖归厤
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
            android.widget.Toast.makeText(context, "娌℃湁鍙敤鐨勪笅杞藉湴鍧€", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        val transferId = downloadTransferId(app.packageName)
        val fileName = "${app.name}_update.apk"
        if (!TransferManager.startTransfer(transferId, fileName)) {
            android.widget.Toast.makeText(context, "${app.name} 姝ｅ湪涓嬭浇", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        android.widget.Toast.makeText(context, "寮€濮嬩笅杞?${app.name}", android.widget.Toast.LENGTH_SHORT).show()
        val request = DownloadRequest(app, urlToUse, specificUrl != null)
        downloadRequests[transferId] = request
        launchDownload(transferId, fileName, request)
    }

    fun cancelDownload(packageName: String) {
        downloadJobs[downloadTransferId(packageName)]?.cancel()
    }

    fun cancelAllDownloads() {
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
        launchDownload(transferId, fileName, request)
    }

    private fun launchDownload(
        transferId: String,
        displayFileName: String,
        request: DownloadRequest
    ) {
        val job = viewModelScope.launch(Dispatchers.IO) {
            val downloadDir = File(context.cacheDir, "downloads").apply { mkdirs() }
            val partFile = File(downloadDir, "${request.app.packageName}.apk.part")
            val finalFile = File(downloadDir, "${request.app.packageName}-${System.currentTimeMillis()}.apk")
            partFile.delete()

            try {
                TransferManager.markResolving(transferId)
                val finalUrl = if (request.isDirectAsset) request.url else resolveDownloadUrl(request.url)
                if (finalUrl.isBlank() || !finalUrl.startsWith("http")) {
                    throw Exception("鏃犳硶瑙ｆ瀽涓嬭浇鍦板潃: $finalUrl")
                }

                val response: HttpResponse = client.get(finalUrl) {
                    timeout {
                        requestTimeoutMillis = DOWNLOAD_TIMEOUT_MILLIS
                        socketTimeoutMillis = DOWNLOAD_SOCKET_TIMEOUT_MILLIS
                    }
                    onDownload { bytesSentTotal, contentLength ->
                        if (contentLength > 0) {
                            val progress = ((bytesSentTotal.toDouble() / contentLength) * 100).toInt()
                            TransferManager.updateProgress(
                                transferId,
                                displayFileName,
                                progress,
                                contentLength
                            )
                        }
                    }
                }
                if (response.status.value !in 200..299) {
                    throw Exception("涓嬭浇澶辫触锛孒TTP ${response.status.value}")
                }

                TransferManager.markTransferring(transferId)
                val channel: ByteReadChannel = response.bodyAsChannel()
                partFile.outputStream().buffered().use { output ->
                    while (!channel.isClosedForRead) {
                        val packet = channel.readRemaining(8192)
                        while (!packet.isEmpty) {
                            output.write(packet.readBytes())
                        }
                    }
                }

                if (!isValidApk(partFile)) throw Exception("涓嬭浇鍐呭涓嶆槸鏈夋晥鐨?APK")
                moveCompletedDownload(partFile, finalFile)
                triggerInstallation(finalFile)
                TransferManager.markComplete(transferId)
            } catch (e: CoroutineCancellationException) {
                partFile.delete()
                finalFile.delete()
                TransferManager.markCanceled(transferId)
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                partFile.delete()
                finalFile.delete()
                TransferManager.markFailed(transferId, e.message ?: "涓嬭浇澶辫触")
            }
        }
        downloadJobs[transferId] = job
        job.invokeOnCompletion { downloadJobs.remove(transferId, job) }
    }

    private fun downloadTransferId(packageName: String) = "download:$packageName"

    private fun isValidApk(file: File): Boolean = runCatching {
        file.length() > 0 && ZipFile(file).use { it.getEntry("AndroidManifest.xml") != null }
    }.getOrDefault(false)

    private fun moveCompletedDownload(source: File, destination: File) {
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

    private suspend fun resolveDownloadUrl(inputUrl: String): String {
        val trimmedUrl = inputUrl.trim().removeSuffix("/")
        
        // 濡傛灉宸茬粡鏄?APK 鐩撮摼锛岀洿鎺ヨ繑鍥?
        if (trimmedUrl.endsWith(".apk", ignoreCase = true)) return trimmedUrl
        
        val info = parseGitHubUrl(trimmedUrl) ?: return trimmedUrl
        val (owner, repo) = info
        
        val apiUrl = "https://api.github.com/repos/$owner/$repo/releases/latest"
        
        return try {
            val response = client.get(apiUrl) {
                header("User-Agent", "PushTV-App")
                header("Accept", "application/vnd.github+json")
                timeout { // CRASH-12: add explicit timeout matching checkUpdate
                    requestTimeoutMillis = 15000
                    socketTimeoutMillis = 15000
                }
            }
            if (response.status.value in 200..299) {
                val body = response.bodyAsText()
                val regex = Regex("""\\"browser_download_url\\":\\s*\\"([^\\"]+?\\.apk)\\"$""")
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

    private suspend fun triggerInstallation(file: File) {
        withContext(Dispatchers.Main) { // CRASH-04: must call startActivity on main thread
            try { // CRASH-05: catch FileProvider / ActivityNotFoundException
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
            } catch (e: Exception) {
                e.printStackTrace()
                android.widget.Toast.makeText(context, "无法启动安装，请手动安装 APK", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCleared() {
        downloadJobs.values.forEach { it.cancel() }
        client.close()
        super.onCleared()
    }

    companion object {
        private const val DOWNLOAD_TIMEOUT_MILLIS = 10 * 60 * 1000L
        private const val DOWNLOAD_SOCKET_TIMEOUT_MILLIS = 30_000L
    }
}
