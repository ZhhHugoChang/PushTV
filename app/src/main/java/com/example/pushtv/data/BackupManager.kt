package com.example.pushtv.data

import android.content.Context
import com.example.pushtv.network.WebDavClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

enum class BackupTaskType {
    UPLOAD,
    DOWNLOAD
}

data class BackupProgress(
    val type: BackupTaskType,
    val currentAppName: String,
    val currentIndex: Int,
    val totalCount: Int,
    val currentFileProgress: Float = 0f,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val statusMessage: String = ""
)

data class QueueTask(
    val type: BackupTaskType,
    val appName: String,
    val packageName: String,
    val remoteFileName: String,
    val localSourceDir: String? = null,
    val skipBackedUp: Boolean = false,
    val isSingleAppRestore: Boolean = false
)

object BackupManager {

    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var workerJob: Job? = null
    private val taskQueue = ConcurrentLinkedQueue<QueueTask>()
    private val pendingSingleInstalls = mutableListOf<File>()

    private val _webDavStatus = MutableStateFlow(WebDavStatus.UNCONFIGURED)
    val webDavStatus: StateFlow<WebDavStatus> = _webDavStatus.asStateFlow()

    private val _activeProgress = MutableStateFlow<BackupProgress?>(null)
    val activeProgress: StateFlow<BackupProgress?> = _activeProgress.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _taskCompletedEvent = MutableSharedFlow<BackupTaskType>(extraBufferCapacity = 1)
    val taskCompletedEvent: SharedFlow<BackupTaskType> = _taskCompletedEvent.asSharedFlow()

    private val _singleInstallPromptEvent = MutableSharedFlow<List<File>>(extraBufferCapacity = 1)
    val singleInstallPromptEvent: SharedFlow<List<File>> = _singleInstallPromptEvent.asSharedFlow()

    fun checkWebDavStatus(context: Context) {
        managerScope.launch {
            val config = SettingsRepo.getWebDavConfig(context)
            if (!config.isConfigured) {
                _webDavStatus.value = WebDavStatus.UNCONFIGURED
                return@launch
            }
            val result = WebDavClient.testConnection(config)
            _webDavStatus.value = if (result.isSuccess) WebDavStatus.ONLINE else WebDavStatus.OFFLINE
        }
    }

    fun enqueueBackup(context: Context, apps: List<BackupAppItem>, skipBackedUp: Boolean = false) {
        val targetApps = if (skipBackedUp) {
            apps.filter { it.backupStatus != AppBackupStatus.BACKED_UP }
        } else {
            apps
        }
        if (targetApps.isEmpty()) return

        for (app in targetApps) {
            val alreadyQueued = taskQueue.any { it.type == BackupTaskType.UPLOAD && it.packageName == app.packageName }
            if (!alreadyQueued) {
                val cleanName = sanitizeFileName(app.name)
                val cleanVersion = sanitizeFileName(app.versionName)
                val remoteFileName = "${cleanName}_v${cleanVersion}.apk"
                taskQueue.add(
                    QueueTask(
                        type = BackupTaskType.UPLOAD,
                        appName = app.name,
                        packageName = app.packageName,
                        remoteFileName = remoteFileName,
                        localSourceDir = app.sourceDir,
                        skipBackedUp = skipBackedUp
                    )
                )
            }
        }
        startWorkerIfNeeded(context.applicationContext)
    }

    fun enqueueRestore(context: Context, apps: List<BackupAppItem>, isSingleAppRestore: Boolean = false) {
        if (apps.isEmpty()) return

        for (app in apps) {
            val remoteFileName = app.remoteFileName ?: run {
                val cleanName = sanitizeFileName(app.name)
                val cleanVersion = sanitizeFileName(app.versionName)
                "${cleanName}_v${cleanVersion}.apk"
            }
            val alreadyQueued = taskQueue.any { it.type == BackupTaskType.DOWNLOAD && it.remoteFileName == remoteFileName }
            if (!alreadyQueued) {
                taskQueue.add(
                    QueueTask(
                        type = BackupTaskType.DOWNLOAD,
                        appName = app.name,
                        packageName = app.packageName,
                        remoteFileName = remoteFileName,
                        isSingleAppRestore = isSingleAppRestore
                    )
                )
            }
        }
        startWorkerIfNeeded(context.applicationContext)
    }

    fun enqueueRestoreCloud(context: Context, items: List<CloudApkItem>, isSingleAppRestore: Boolean = false) {
        if (items.isEmpty()) return

        for (item in items) {
            val alreadyQueued = taskQueue.any { it.type == BackupTaskType.DOWNLOAD && it.remoteFileName == item.fileName }
            if (!alreadyQueued) {
                taskQueue.add(
                    QueueTask(
                        type = BackupTaskType.DOWNLOAD,
                        appName = item.appName,
                        packageName = item.installedPackageName ?: item.fileName,
                        remoteFileName = item.fileName,
                        isSingleAppRestore = isSingleAppRestore
                    )
                )
            }
        }
        startWorkerIfNeeded(context.applicationContext)
    }

    fun startBackupQueue(context: Context, apps: List<BackupAppItem>, skipBackedUp: Boolean = false) {
        enqueueBackup(context, apps, skipBackedUp)
    }

    fun startRestoreQueue(
        context: Context,
        apps: List<BackupAppItem>,
        onFinished: (List<File>) -> Unit = {}
    ) {
        enqueueRestore(context, apps, isSingleAppRestore = false)
    }

    private fun startWorkerIfNeeded(context: Context) {
        synchronized(this) {
            if (workerJob?.isActive == true) {
                // 如果当前已经在执行，更新一下 statusMessage 中的剩余项数
                val current = _activeProgress.value
                if (current != null) {
                    val remaining = taskQueue.size
                    val queueText = if (remaining > 0) " (队列剩余 $remaining 项)" else ""
                    val actionText = if (current.type == BackupTaskType.UPLOAD) "上传" else "下载"
                    _activeProgress.value = current.copy(
                        statusMessage = "正在$actionText ${current.currentAppName}$queueText"
                    )
                }
                return
            }

            workerJob = managerScope.launch {
                try {
                    val config = SettingsRepo.getWebDavConfig(context)
                    if (!config.isConfigured) {
                        _lastError.value = "WebDAV 尚未配置"
                        taskQueue.clear()
                        return@launch
                    }

                    _webDavStatus.value = WebDavStatus.ONLINE

                    while (isActive && !taskQueue.isEmpty()) {
                        val task = taskQueue.poll() ?: break
                        val remainingCount = taskQueue.size
                        val queueText = if (remainingCount > 0) " (队列剩余 $remainingCount 项)" else ""

                        when (task.type) {
                            BackupTaskType.UPLOAD -> {
                                val sourceDir = task.localSourceDir ?: continue
                                val localFile = File(sourceDir)
                                if (!localFile.exists()) continue

                                _activeProgress.value = BackupProgress(
                                    type = BackupTaskType.UPLOAD,
                                    currentAppName = task.appName,
                                    currentIndex = 1,
                                    totalCount = 1,
                                    currentFileProgress = 0f,
                                    bytesTransferred = 0L,
                                    totalBytes = localFile.length(),
                                    statusMessage = "正在上传 ${task.appName}$queueText"
                                )

                                val uploadResult = WebDavClient.uploadApk(
                                    config = config,
                                    localFile = localFile,
                                    remoteFileName = task.remoteFileName
                                ) { sent, fileTotal ->
                                    val ratio = if (fileTotal > 0) sent.toFloat() / fileTotal else 0f
                                    _activeProgress.value = _activeProgress.value?.copy(
                                        currentFileProgress = ratio,
                                        bytesTransferred = sent,
                                        totalBytes = fileTotal
                                    )
                                }

                                if (uploadResult.isFailure) {
                                    _lastError.value = "上传 ${task.appName} 失败: ${uploadResult.exceptionOrNull()?.message}"
                                }
                            }
                            BackupTaskType.DOWNLOAD -> {
                                val incomingDir = File(context.cacheDir, "incoming")
                                if (!incomingDir.exists()) incomingDir.mkdirs()

                                val destFile = File(incomingDir, task.remoteFileName)

                                _activeProgress.value = BackupProgress(
                                    type = BackupTaskType.DOWNLOAD,
                                    currentAppName = task.appName,
                                    currentIndex = 1,
                                    totalCount = 1,
                                    currentFileProgress = 0f,
                                    bytesTransferred = 0L,
                                    totalBytes = 0L,
                                    statusMessage = "正在下载 ${task.appName}$queueText"
                                )

                                val downloadResult = WebDavClient.downloadApk(
                                    config = config,
                                    remoteFileName = task.remoteFileName,
                                    destFile = destFile
                                ) { recv, fileTotal ->
                                    val ratio = if (fileTotal > 0) recv.toFloat() / fileTotal else 0f
                                    _activeProgress.value = _activeProgress.value?.copy(
                                        currentFileProgress = ratio,
                                        bytesTransferred = recv,
                                        totalBytes = fileTotal
                                    )
                                }

                                if (downloadResult.isSuccess) {
                                    if (task.isSingleAppRestore) {
                                        pendingSingleInstalls.add(destFile)
                                    }
                                } else {
                                    _lastError.value = "下载 ${task.appName} 失败: ${downloadResult.exceptionOrNull()?.message}"
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    taskQueue.clear()
                    pendingSingleInstalls.clear()
                } catch (e: Exception) {
                    val isCanceled = e is java.io.IOException && e.message?.contains("Canceled", ignoreCase = true) == true
                    if (isCanceled) {
                        taskQueue.clear()
                        pendingSingleInstalls.clear()
                    } else {
                        _lastError.value = "任务异常: ${e.message}"
                        _webDavStatus.value = WebDavStatus.OFFLINE
                    }
                } finally {
                    _activeProgress.value = null
                    if (isActive) {
                        _taskCompletedEvent.tryEmit(BackupTaskType.UPLOAD)
                        if (pendingSingleInstalls.isNotEmpty()) {
                            val files = pendingSingleInstalls.toList()
                            pendingSingleInstalls.clear()
                            _singleInstallPromptEvent.tryEmit(files)
                        }
                    } else {
                        pendingSingleInstalls.clear()
                    }
                }
            }
        }
    }

    fun cancelCurrentTask() {
        taskQueue.clear()
        pendingSingleInstalls.clear()
        _activeProgress.value = null
        WebDavClient.cancelActiveCall()
        workerJob?.cancel()
        workerJob = null
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|\\s]"), "_")
    }
}
