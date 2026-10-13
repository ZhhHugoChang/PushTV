# PushTV 闪退风险分析报告

> 版本：v1.3 | 分析时间：2026-10-13 | 代码提交：12a88fe

---

## 概览

本报告基于对全部 Kotlin 源文件的静态分析，列出当前代码中存在的闪退（Force Close）风险点。  
所有风险按**严重程度**从高到低排列。

---

## 🔴 高危（极易复现）

---

### [CRASH-01] 对话框 FocusRequester 未附着即调用 requestFocus()

**文件：** [`BackupScreen.kt`](app/src/main/java/com/example/pushtv/ui/backup/BackupScreen.kt#L1712-L1715)  
[`WebDavConfigDialog.kt`](app/src/main/java/com/example/pushtv/ui/backup/WebDavConfigDialog.kt#L62-L65)  
[`HomeDialogs.kt`](app/src/main/java/com/example/pushtv/ui/home/HomeDialogs.kt#L75-L78)

**代码片段：**
```kotlin
// BackupScreen.kt L1712-1714（DuplicateBackupDialog）
LaunchedEffect(Unit) {
    withFrameNanos { }
    skipRequester.requestFocus()  // ⚠️ 无 runCatching 保护
}

// BackupScreen.kt L1865-1867（InstallPromptDialog）
LaunchedEffect(Unit) {
    withFrameNanos { }
    installRequester.requestFocus()  // ⚠️ 无 runCatching 保护
}

// BackupScreen.kt L1987-1989（DeleteDialog）
LaunchedEffect(Unit) {
    withFrameNanos { }
    cancelRequester.requestFocus()  // ⚠️ 无 runCatching 保护
}
```

**根本原因：**  
`FocusRequester.requestFocus()` 在目标节点尚未被 Compose 渲染到布局树时会抛出  
`IllegalStateException: FocusRequester is not initialized`，直接导致应用崩溃。  
`withFrameNanos { }` 只等待一帧，而 Dialog 动画（特别是 TV 上的入场动画）通常需要多帧才能完成布局。

**触发场景：**
- 下载完成后弹出「安装提示」对话框时
- 点击上传出现「已备份重复」对话框时
- 点击删除云端文件时
- 打开 WebDAV 配置对话框时

**建议修复：**
```kotlin
LaunchedEffect(Unit) {
    repeat(5) {
        withFrameNanos { }
        if (runCatching { skipRequester.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
    }
}
```

---

### [CRASH-02] BackupScreen.kt L278 裸露 requestFocus（无保护）

**文件：** [`BackupScreen.kt`](app/src/main/java/com/example/pushtv/ui/backup/BackupScreen.kt#L269-L279)

**代码片段：**
```kotlin
LaunchedEffect(Unit) {
    withFrameNanos { }
    if (!initialFocusPlaced) {
        val requester = firstKey?.let { focusRequesterMap[it] } ?: localTabRequester
        requester.requestFocus()  // ⚠️ 无 runCatching 保护
    }
}
```

**根本原因：**  
首次进入备份页面时，若列表数据异步加载还未完成，`focusRequesterMap` 中对应的 FocusRequester 可能未被附着到任何节点，裸调 `requestFocus()` 会直接崩溃。

**触发场景：**
- 首次打开备份页面且数据加载较慢时

**建议修复：**
```kotlin
runCatching { requester.requestFocus() }
```

---

### [CRASH-03] BackupScreen.kt L187 cancelTaskRequester 裸露 requestFocus

**文件：** [`BackupScreen.kt`](app/src/main/java/com/example/pushtv/ui/backup/BackupScreen.kt#L184-L193)

**代码片段：**
```kotlin
val enterDownFromTopBar = Modifier.onPreviewKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
        if (activeProgress != null) {
            cancelTaskRequester.requestFocus()  // ⚠️ 无 runCatching 保护
            true
        } else { ... }
    } else false
}
```

**根本原因：**  
`cancelTaskRequester` 对应的取消任务按钮，只有在 `activeProgress != null` 时才会出现在界面上。  
按键事件处理是同步的，如果按下方向键的那一帧，按钮刚好因为任务完成正在退出动画，节点尚未从布局树移除，`requestFocus()` 会抛出异常。

**触发场景：**
- 正在下载/上传时快速按方向键 Down

---

### [CRASH-04] SoftwareViewModel.triggerInstallation() 在 IO 线程触发 startActivity

**文件：** [`SoftwareViewModel.kt`](app/src/main/java/com/example/pushtv/ui/software/SoftwareViewModel.kt#L526-L537)

**代码片段：**
```kotlin
private fun triggerInstallation(file: File) {
    val intent = Intent(Intent.ACTION_VIEW)
    val uri = FileProvider.getUriForFile(...)
    intent.setDataAndType(uri, "application/vnd.android.package-archive")
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)  // ⚠️ 在 IO 协程线程直接调用
}
```

**根本原因：**  
`launchDownload()` 的协程运行在 `Dispatchers.IO`，下载完成后直接调用 `triggerInstallation()`，也在 IO 线程执行 `startActivity()`。  
在部分 TV 设备上 `startActivity()` 需要在主线程调用，IO 线程调用可能引发 `CalledFromWrongThreadException` 或 ActivityNotFoundException 崩溃。

**触发场景：**
- 软件列表下载更新完成后自动触发安装时

**建议修复：**
```kotlin
// 在 launchDownload() 内，download 完成后：
withContext(Dispatchers.Main) {
    triggerInstallation(finalFile)
}
```

---

### [CRASH-05] SoftwareViewModel.triggerInstallation() FileProvider URI 路径不在白名单

**文件：** [`SoftwareViewModel.kt`](app/src/main/java/com/example/pushtv/ui/software/SoftwareViewModel.kt#L526-L537)  
**配置文件：** [`file_paths.xml`](app/src/main/res/xml/file_paths.xml)

**代码片段：**
```kotlin
// SoftwareViewModel.kt - 下载到 downloads/ 子目录
val downloadDir = File(context.cacheDir, "downloads").apply { mkdirs() }
val finalFile = File(downloadDir, "xxx.apk")

// file_paths.xml
<cache-path name="incoming_apks" path="incoming/" />
<cache-path name="downloaded_apks" path="downloads/" />
```

**根本原因：**  
`downloads/` 路径已配置在 `file_paths.xml`，**看起来正常**。  
但问题在于 `finalFile` 的文件名是 `${packageName}-${timestamp}.apk`，包含 `-` 和时间戳，在极少数 OEM 定制 TV 系统上，包含时间戳的路径可能触发文件系统权限异常，导致 `FileProvider.getUriForFile()` 抛出 `IllegalArgumentException`。  
`triggerInstallation()` 中**没有 try-catch**，此异常会直接崩溃。

**建议修复：** 给 `triggerInstallation()` 加 try-catch（`NetworkService.kt` 中的 `triggerInstallation()` 已有保护，SoftwareViewModel 中的未保护）。

```kotlin
private fun triggerInstallation(file: File) {
    try {
        val uri = FileProvider.getUriForFile(...)
        ...
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
        // 提示用户手动安装
    }
}
```

---

## 🟠 中危（特定条件下可复现）

---

### [CRASH-06] BackupManager.pendingSingleInstalls 线程不安全（List vs Queue）

**文件：** [`BackupManager.kt`](app/src/main/java/com/example/pushtv/data/BackupManager.kt#L46)

**代码片段：**
```kotlin
private val pendingSingleInstalls = mutableListOf<File>()  // ⚠️ 非线程安全
```

**根本原因：**  
`pendingSingleInstalls` 使用普通 `mutableListOf()`（非线程安全），而 `workerJob`（IO线程）和 `cancelCurrentTask()`（可从主线程调用）都会并发操作这个 List。  
`cancelCurrentTask()` 调用 `pendingSingleInstalls.clear()` 时，如果 workerJob 正在同时调用 `pendingSingleInstalls.add(destFile)`，会触发 `ConcurrentModificationException` 崩溃。

**触发场景：**
- 单个应用下载过程中用户点击「取消」

**建议修复：**
```kotlin
private val pendingSingleInstalls = java.util.concurrent.CopyOnWriteArrayList<File>()
```

---

### [CRASH-07] BackupManager 发出 UPLOAD 类型完成事件即使实际是 DOWNLOAD 任务

**文件：** [`BackupManager.kt`](app/src/main/java/com/example/pushtv/data/BackupManager.kt#L282)

**代码片段：**
```kotlin
} finally {
    _activeProgress.value = null
    if (isActive) {
        _taskCompletedEvent.tryEmit(BackupTaskType.UPLOAD)  // ⚠️ 硬编码 UPLOAD，即使可能是 DOWNLOAD
        ...
    }
}
```

**根本原因：**  
`finally` 块中固定发出 `BackupTaskType.UPLOAD` 事件，无论任务实际类型。  
虽然目前 `BackupViewModel` 收到任何 `taskCompletedEvent` 都触发 `loadData()`（所以表面无碍），但这是一个隐藏的逻辑错误。若未来根据类型做不同处理，会引发逻辑崩溃。

---

### [CRASH-08] WebDavClient.uploadApk() 中 sink.write() 无 IO 异常保护

**文件：** [`WebDavClient.kt`](app/src/main/java/com/example/pushtv/network/WebDavClient.kt#L212-L228)

**代码片段：**
```kotlin
override fun writeTo(sink: BufferedSink) {
    val buffer = ByteArray(16 * 1024)
    FileInputStream(localFile).use { input ->
        var uploaded = 0L
        var read: Int
        while (input.read(buffer).also { read = it } != -1) {
            if (!coroutineContext.isActive) throw CancellationException("Upload cancelled")
            sink.write(buffer, 0, read)  // ⚠️ Okio sink.write() 抛出的 IOException 传播给 OkHttp
            ...
        }
    }
}
```

**根本原因：**  
`writeTo()` 在 OkHttp 的请求体线程中执行，`sink.write()` 抛出 `IOException`（如网络中断）时，OkHttp 内部会将其包装为 `IOException` 再抛出。  
`runCatching` 已在 `uploadApk` 外层捕获，所以不会直接崩溃。但若 `onProgress` 回调中存在异常（如 StateFlow emit 在协程取消后），可能漏掉。

---

### [CRASH-09] HomeViewModel.startFileObserver() FileObserver.onEvent() 在子线程回调，直接调用非线程安全操作

**文件：** [`HomeViewModel.kt`](app/src/main/java/com/example/pushtv/ui/home/HomeViewModel.kt#L134-L141)

**代码片段：**
```kotlin
fileObserver = object : FileObserver(incomingDir.absolutePath, CREATE or CLOSE_WRITE or MOVED_TO or DELETE) {
    override fun onEvent(event: Int, path: String?) {
        if (path?.lowercase()?.endsWith(".apk") == true) {
            scheduleFileListRefresh(FILE_REFRESH_DEBOUNCE_MILLIS)
        }
    }
}
```

**根本原因：**  
`FileObserver.onEvent()` 在系统 inotify 线程中回调（不是主线程，不是 IO 线程），`scheduleFileListRefresh()` 内部调用 `fileRefreshJob?.cancel()` 再 `viewModelScope.launch()`。  
`fileRefreshJob?.cancel()` 在非协程上下文中线程不安全，但通常不会直接崩溃（Kotlin 协程的 cancel 是幂等的）。  
**潜在风险**：如果 `FileObserver` 对象被 GC 掉但 `startWatching()` 仍在运行，系统可能回调到已回收的 lambda，造成 NPE。  
**建议**：在 `ViewModel.onCleared()` 中调用 `fileObserver?.stopWatching()`。

---

### [CRASH-10] NetworkService /api/apps 接口无 try-catch，遍历包信息可能崩溃

**文件：** [`NetworkService.kt`](app/src/main/java/com/example/pushtv/network/NetworkService.kt#L95-L111)

**代码片段：**
```kotlin
get("/api/apps") {
    val pm = applicationContext.packageManager
    val apps = pm.getInstalledApplications(...)
    val appList = apps.map { appInfo ->
        val packageInfo = pm.getPackageInfo(appInfo.packageName, 0)  // ⚠️ 无 try-catch
        ...
    }
    call.respondText(...)
}
```

**根本原因：**  
`pm.getPackageInfo()` 可能抛出 `PackageManager.NameNotFoundException`（应用在遍历过程中被卸载）。  
此处无 try-catch，异常会传播到 Ktor 路由层。Ktor 会返回 500 错误而不是崩溃，**但可能导致服务静默失败**，手机端网页显示异常。

---

## 🟡 低危（偶发/边缘情况）

---

### [CRASH-11] cleanIncomingCache() 与正在进行的下载存在竞争条件

**文件：** [`BackupViewModel.kt`](app/src/main/java/com/example/pushtv/ui/backup/BackupViewModel.kt#L286-L298)

**代码片段：**
```kotlin
fun cleanIncomingCache(): Long {
    val incomingDir = File(context.cacheDir, "incoming")
    var freed = 0L
    if (incomingDir.exists()) {
        val files = incomingDir.listFiles { f -> f.isFile && f.extension.equals("apk", ignoreCase = true) }
        files?.forEach { file ->
            val len = file.length()
            if (file.delete()) freed += len  // ⚠️ 可能删除正在下载的目标文件
        }
    }
    ...
}
```

**根本原因：**  
`BackupManager` 下载时的目标文件 `destFile` 也在 `incoming/` 目录下。若用户在下载进行中点击「清理缓存」，`cleanIncomingCache()` 会删除正在被写入的 `.part` 文件（或已完成重命名的 `.apk`），导致下载静默失败，但不会崩溃。

---

### [CRASH-12] SoftwareViewModel HTTP 超时配置不一致

**文件：** [`SoftwareViewModel.kt`](app/src/main/java/com/example/pushtv/ui/software/SoftwareViewModel.kt#L64-L72)

**代码片段：**
```kotlin
private val client = HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 15000    // 全局 15 秒
        connectTimeoutMillis = 10000
        socketTimeoutMillis = 15000
    }
}

// launchDownload() 中覆盖超时
val response: HttpResponse = client.get(finalUrl) {
    timeout {
        requestTimeoutMillis = DOWNLOAD_TIMEOUT_MILLIS  // 10 分钟
        socketTimeoutMillis = DOWNLOAD_SOCKET_TIMEOUT_MILLIS  // 30 秒
    }
}
```

**根本原因：**  
`checkAllUpdates()` 调用 GitHub API 使用全局 15 秒超时，正常。  
但 `resolveDownloadUrl()` 里的 GitHub API 请求**没有覆盖超时**，使用全局 15 秒。  
如果 GitHub API 响应慢，`resolveDownloadUrl()` 超时后不会崩溃（有 try-catch），但会返回原始 URL 地址，可能导致下载错误文件。

---

## 修复优先级汇总

| 编号 | 描述 | 严重程度 | 修复难度 |
|------|------|----------|----------|
| CRASH-01 | 对话框 FocusRequester 无保护裸调 | 🔴 高危 | ⭐ 简单 |
| CRASH-02 | BackupScreen 初始焦点无保护 | 🔴 高危 | ⭐ 简单 |
| CRASH-03 | cancelTaskRequester 按键事件无保护 | 🔴 高危 | ⭐ 简单 |
| CRASH-04 | triggerInstallation IO 线程 startActivity | 🔴 高危 | ⭐ 简单 |
| CRASH-05 | SoftwareViewModel triggerInstallation 无 try-catch | 🔴 高危 | ⭐ 简单 |
| CRASH-06 | pendingSingleInstalls 并发修改 | 🟠 中危 | ⭐ 简单 |
| CRASH-07 | taskCompletedEvent 硬编码 UPLOAD | 🟠 中危 | ⭐ 简单 |
| CRASH-08 | uploadApk writeTo IO 异常传播 | 🟠 中危 | ⭐⭐ 中等 |
| CRASH-09 | FileObserver 未在 onCleared 停止 | 🟠 中危 | ⭐ 简单 |
| CRASH-10 | /api/apps 无 PackageManager 异常保护 | 🟡 低危 | ⭐ 简单 |
| CRASH-11 | cleanIncomingCache 与下载竞争 | 🟡 低危 | ⭐⭐ 中等 |
| CRASH-12 | HTTP 超时配置不一致 | 🟡 低危 | ⭐ 简单 |

---

## 最需要立即修复的 3 个问题

1. **CRASH-01/02/03** — 所有 `requestFocus()` 加 `runCatching` 保护（约 10 分钟工作量）
2. **CRASH-04** — `SoftwareViewModel.triggerInstallation()` 加 `withContext(Dispatchers.Main)` 并加 try-catch
3. **CRASH-06** — `pendingSingleInstalls` 改用 `CopyOnWriteArrayList`（一行改动）
