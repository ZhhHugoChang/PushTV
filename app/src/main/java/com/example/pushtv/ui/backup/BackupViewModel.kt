package com.example.pushtv.ui.backup

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.pushtv.data.*
import com.example.pushtv.network.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext

    private val _apps = MutableStateFlow<List<BackupAppItem>>(emptyList())
    val apps: StateFlow<List<BackupAppItem>> = _apps.asStateFlow()

    private val _cloudApks = MutableStateFlow<List<CloudApkItem>>(emptyList())
    val cloudApks: StateFlow<List<CloudApkItem>> = _cloudApks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _webDavConfig = MutableStateFlow(WebDavConfig())
    val webDavConfig: StateFlow<WebDavConfig> = _webDavConfig.asStateFlow()

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = _testResult.asStateFlow()

    val webDavStatus: StateFlow<WebDavStatus> = BackupManager.webDavStatus
    val activeProgress: StateFlow<BackupProgress?> = BackupManager.activeProgress

    init {
        loadData()
        viewModelScope.launch {
            BackupManager.taskCompletedEvent.collect {
                loadData()
            }
        }
    }

    fun loadData() {
        viewModelScope.launch {
            _isLoading.value = true
            val config = SettingsRepo.getWebDavConfig(context)
            _webDavConfig.value = config
            BackupManager.checkWebDavStatus(context)

            // 读取已安装第三方应用
            val installedApps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val allApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                allApps.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                    .mapNotNull { appInfo ->
                        try {
                            val pkgInfo = pm.getPackageInfo(appInfo.packageName, 0)
                            val sourceDir = appInfo.sourceDir
                            val file = File(sourceDir)
                            BackupAppItem(
                                name = pm.getApplicationLabel(appInfo).toString(),
                                packageName = appInfo.packageName,
                                icon = pm.getApplicationIcon(appInfo),
                                versionName = pkgInfo.versionName.orEmpty(),
                                apkFileLength = if (file.exists()) file.length() else 0L,
                                sourceDir = sourceDir,
                                isSelected = false
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }
                    .sortedBy { it.name }
            }

            // 查询云端备份
            val remoteFiles = withContext(Dispatchers.IO) {
                if (config.isConfigured) {
                    WebDavClient.listRemoteBackups(config).getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }

            // 查询本地 incoming 目录中已有的 APK 文件
            val localIncomingFiles = withContext(Dispatchers.IO) {
                val incomingDir = File(context.cacheDir, "incoming")
                if (incomingDir.exists()) {
                    incomingDir.listFiles { f -> f.isFile && f.extension.equals("apk", ignoreCase = true) }?.toList() ?: emptyList()
                } else {
                    emptyList()
                }
            }

            // 比对本机安装应用与云端状态
            val combined = installedApps.map { item ->
                val cleanName = item.name.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
                val exactFileName = "${cleanName}_v${item.versionName}.apk"
                val prefix = "${cleanName}_v"

                val matchedLocalApk = localIncomingFiles.find { file ->
                    file.name.equals(exactFileName, ignoreCase = true) ||
                    file.name.startsWith(prefix, ignoreCase = true)
                }

                val exactMatch = remoteFiles.find { it.name.equals(exactFileName, ignoreCase = true) }
                if (exactMatch != null) {
                    item.copy(
                        backupStatus = AppBackupStatus.BACKED_UP,
                        remoteFileName = exactMatch.name,
                        remoteVersion = item.versionName,
                        localApkFile = matchedLocalApk
                    )
                } else {
                    val anyMatch = remoteFiles.find { it.name.startsWith(prefix, ignoreCase = true) && it.name.endsWith(".apk", ignoreCase = true) }
                    if (anyMatch != null) {
                        val versionStr = anyMatch.name.removePrefix(prefix).removeSuffix(".apk")
                        item.copy(
                            backupStatus = AppBackupStatus.VERSION_MISMATCH,
                            remoteFileName = anyMatch.name,
                            remoteVersion = versionStr,
                            localApkFile = matchedLocalApk
                        )
                    } else {
                        item.copy(
                            backupStatus = AppBackupStatus.NOT_BACKED_UP,
                            localApkFile = matchedLocalApk
                        )
                    }
                }
            }

            // 构建云端网盘 APK 独立列表
            val cloudItems = remoteFiles.map { remoteFile ->
                val fileName = remoteFile.name
                val match = Regex("^(.*)_v([^_]+)\\.apk$", RegexOption.IGNORE_CASE).find(fileName)
                val (parsedName, parsedVersion) = if (match != null) {
                    val rawName = match.groupValues[1].replace("_", " ")
                    val version = match.groupValues[2]
                    rawName to version
                } else {
                    val rawName = fileName.removeSuffix(".apk").removeSuffix(".APK").replace("_", " ")
                    rawName to null
                }

                val matchedLocalApk = localIncomingFiles.find { file ->
                    file.name.equals(fileName, ignoreCase = true)
                }

                val matchedInstalledApp = installedApps.find { inst ->
                    val cleanInstName = inst.name.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
                    val cleanParsedName = parsedName.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
                    cleanInstName.equals(cleanParsedName, ignoreCase = true) ||
                    inst.name.equals(parsedName, ignoreCase = true)
                }

                val installStatus = when {
                    matchedInstalledApp == null -> CloudAppInstallStatus.NOT_INSTALLED
                    parsedVersion != null && matchedInstalledApp.versionName == parsedVersion -> CloudAppInstallStatus.INSTALLED_SAME
                    else -> CloudAppInstallStatus.INSTALLED_DIFFERENT
                }

                CloudApkItem(
                    fileName = fileName,
                    appName = matchedInstalledApp?.name ?: parsedName,
                    versionName = parsedVersion,
                    size = remoteFile.size,
                    installStatus = installStatus,
                    installedVersion = matchedInstalledApp?.versionName,
                    localApkFile = matchedLocalApk,
                    installedPackageName = matchedInstalledApp?.packageName,
                    icon = matchedInstalledApp?.icon,
                    isSelected = false
                )
            }.sortedBy { it.appName }

            _apps.value = combined
            _cloudApks.value = cloudItems
            _isLoading.value = false
        }
    }

    fun toggleSelect(packageName: String) {
        _apps.value = _apps.value.map {
            if (it.packageName == packageName) it.copy(isSelected = !it.isSelected) else it
        }
    }

    val singleInstallPromptEvent = BackupManager.singleInstallPromptEvent

    fun selectAll() {
        val hasUnselected = _apps.value.any { !it.isSelected }
        _apps.value = _apps.value.map { it.copy(isSelected = hasUnselected) }
    }

    fun clearSelection() {
        _apps.value = _apps.value.map { it.copy(isSelected = false) }
    }

    fun startBackupSelected(skipBackedUp: Boolean = false) {
        val selected = _apps.value.filter { it.isSelected }
        if (selected.isEmpty()) return
        BackupManager.enqueueBackup(context, selected, skipBackedUp)
    }

    fun startRestoreSelected() {
        val selected = _apps.value.filter { it.isSelected }
        if (selected.isEmpty()) return
        BackupManager.enqueueRestore(context, selected, isSingleAppRestore = false)
    }

    fun startBackupApp(item: BackupAppItem, skipBackedUp: Boolean = false) {
        BackupManager.enqueueBackup(context, listOf(item), skipBackedUp)
    }

    fun startRestoreApp(item: BackupAppItem) {
        BackupManager.enqueueRestore(context, listOf(item), isSingleAppRestore = true)
    }

    fun toggleCloudSelect(fileName: String) {
        _cloudApks.value = _cloudApks.value.map {
            if (it.fileName == fileName) it.copy(isSelected = !it.isSelected) else it
        }
    }

    fun selectAllCloud() {
        val hasUnselected = _cloudApks.value.any { !it.isSelected }
        _cloudApks.value = _cloudApks.value.map { it.copy(isSelected = hasUnselected) }
    }

    fun clearCloudSelection() {
        _cloudApks.value = _cloudApks.value.map { it.copy(isSelected = false) }
    }

    fun startDownloadCloudApk(item: CloudApkItem) {
        BackupManager.enqueueRestoreCloud(context, listOf(item), isSingleAppRestore = true)
    }

    fun startDownloadSelectedCloudApks() {
        val selected = _cloudApks.value.filter { it.isSelected }
        if (selected.isEmpty()) return
        BackupManager.enqueueRestoreCloud(context, selected, isSingleAppRestore = false)
    }

    fun startDownloadCloudApks(items: List<CloudApkItem>, isSingleAppRestore: Boolean = false) {
        if (items.isEmpty()) return
        BackupManager.enqueueRestoreCloud(context, items, isSingleAppRestore = isSingleAppRestore)
    }

    fun deleteCloudApk(fileName: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val config = _webDavConfig.value
            val result = WebDavClient.deleteRemoteFile(config, fileName)
            if (result.isSuccess) {
                loadData()
                onDone(true)
            } else {
                onDone(false)
            }
        }
    }

    fun checkStorageForDownload(items: List<CloudApkItem>): StorageCheckResult {
        val incomingDir = File(context.cacheDir, "incoming")
        val usableSpace = if (incomingDir.exists()) incomingDir.usableSpace else context.cacheDir.usableSpace
        val requiredSpace = items.sumOf { it.size }
        val cacheFiles = if (incomingDir.exists()) {
            incomingDir.listFiles { f -> f.isFile && f.extension.equals("apk", ignoreCase = true) }?.toList() ?: emptyList()
        } else emptyList()
        val cacheBytes = cacheFiles.sumOf { it.length() }
        return StorageCheckResult(
            isEnough = usableSpace >= requiredSpace,
            usableBytes = usableSpace,
            requiredBytes = requiredSpace,
            cacheBytes = cacheBytes,
            pendingDownloadItems = items
        )
    }

    fun cleanIncomingCache(): Long {
        val incomingDir = File(context.cacheDir, "incoming")
        var freed = 0L
        if (incomingDir.exists()) {
            val files = incomingDir.listFiles { f -> f.isFile && f.extension.equals("apk", ignoreCase = true) }
            files?.forEach { file ->
                val len = file.length()
                if (file.delete()) freed += len
            }
        }
        loadData()
        return freed
    }

    fun cancelTask() {
        BackupManager.cancelCurrentTask()
    }

    fun saveConfig(newConfig: WebDavConfig) {
        viewModelScope.launch {
            SettingsRepo.saveWebDavConfig(context, newConfig)
            _webDavConfig.value = newConfig
            loadData()
        }
    }

    fun testConnection(config: WebDavConfig, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val result = WebDavClient.testConnection(config)
            if (result.isSuccess) {
                _testResult.value = "连接成功"
                onDone(true, "连接成功！目录正常")
            } else {
                val error = result.exceptionOrNull()?.message ?: "未知错误"
                _testResult.value = error
                onDone(false, error)
            }
        }
    }
}

data class StorageCheckResult(
    val isEnough: Boolean,
    val usableBytes: Long,
    val requiredBytes: Long,
    val cacheBytes: Long,
    val pendingDownloadItems: List<CloudApkItem>
)

