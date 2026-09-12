@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.pushtv.ui.home

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.example.pushtv.data.TransferManager
import com.example.pushtv.data.TransferProgress
import com.example.pushtv.data.TransferState
import com.example.pushtv.ui.software.FilterMode
import com.example.pushtv.ui.software.InstalledApp
import com.example.pushtv.ui.software.SoftwareViewModel
import com.example.pushtv.ui.theme.PushTVColors
import kotlinx.coroutines.delay
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun ActionDrawer(
    title: String,
    actions: List<ActionItem>,
    packageName: String? = null,
    softwareViewModel: SoftwareViewModel? = null,
    drawerFocusRequester: FocusRequester = remember { FocusRequester() }
) {
    // drawerFocusRequester 由父组件传入并统一管理，绑定到抽屉内第一个可聚焦的按钮上
    val changelogFocusRequester = remember { FocusRequester() }

    val apps by (softwareViewModel?.installedApps ?: remember { MutableStateFlow<List<InstalledApp>>(emptyList()) }).collectAsState()
    val currentApp = if (packageName != null) apps.find { it.packageName == packageName } else null
    val transfers by TransferManager.transfers.collectAsState()
    val currentDownload = packageName?.let { currentPackage ->
        transfers.find { it.id == "download:$currentPackage" }
    }

    val recommendedAsset = currentApp?.assets?.find { it.isRecommended }
    val primaryAsset = recommendedAsset ?: currentApp?.assets?.firstOrNull()
    val otherAssets = currentApp?.assets?.filter { it != primaryAsset } ?: emptyList()
    val hasChangelog = currentApp?.changelog?.isNotBlank() == true
    val actionRequesters = remember(actions.size, drawerFocusRequester) {
        List(actions.size) { index -> if (index == 0) drawerFocusRequester else FocusRequester() }
    }
    val downloadRequester = remember { FocusRequester() }
    val progressActionRequester = remember { FocusRequester() }
    val otherAssetRequesters = remember(otherAssets.map { it.downloadUrl }) {
        List(otherAssets.size) { FocusRequester() }
    }
    val leftToChangelog = if (hasChangelog) changelogFocusRequester else FocusRequester.Cancel
    val actionsScrollState = rememberScrollState()
    val drawerScope = rememberCoroutineScope()
    val revealDownloadTop = {
        drawerScope.launch {
            actionsScrollState.scrollTo(0)
        }
    }
    val startDownloadAndFocusTop: (String) -> Unit = { downloadUrl ->
        currentApp?.let { app ->
            softwareViewModel?.downloadAndInstall(app, downloadUrl)
            revealDownloadTop()
        }
    }
    var isChangelogFocused by remember { mutableStateOf(false) }
    var isProgressActionFocused by remember { mutableStateOf(false) }

    val downloadFocusMode = when (currentDownload?.state) {
        null -> 0
        TransferState.COMPLETED -> 1
        else -> 2
    }
    LaunchedEffect(currentDownload?.id, downloadFocusMode) {
        val transfer = currentDownload ?: return@LaunchedEffect
        actionsScrollState.scrollTo(0)
        val requester = if (transfer.state == TransferState.COMPLETED) {
            downloadRequester
        } else {
            progressActionRequester
        }
        repeat(12) {
            if (requester === progressActionRequester && isProgressActionFocused) {
                return@LaunchedEffect
            }
            withFrameNanos { }
            runCatching { requester.requestFocus() }
            if (requester !== progressActionRequester) {
                return@LaunchedEffect
            }
        }
    }

    // --- 核心修复：抽屉焦点锁定环 ---
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .wrapContentWidth()
            .focusGroup()
            .focusProperties { exit = { FocusRequester.Cancel } },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Side: Changelog
        if (hasChangelog) {
            val scrollState = rememberScrollState()
            val scope = rememberCoroutineScope()

            Surface(
                colors = NonInteractiveSurfaceDefaults.colors(containerColor = PushTVColors.Surface),
                modifier = Modifier
                    .fillMaxHeight()
                    .width(420.dp)
                    .padding(end = 1.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, contentDescription = null, tint = PushTVColors.Primary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("更新日志", style = MaterialTheme.typography.headlineSmall, color = PushTVColors.TextPrimary)
                    }
                    Spacer(modifier = Modifier.height(20.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isChangelogFocused) PushTVColors.TextPrimary.copy(alpha = 0.05f)
                                else PushTVColors.TextPrimary.copy(alpha = 0.03f)
                            )
                            .then(
                                if (isChangelogFocused) {
                                    Modifier.border(2.dp, PushTVColors.Primary, RoundedCornerShape(12.dp))
                                } else {
                                    Modifier
                                }
                            )
                            .focusRequester(changelogFocusRequester)
                            .onFocusChanged { isChangelogFocused = it.isFocused }
                            .focusProperties {
                                right = drawerFocusRequester
                            }
                            .focusable()
                            .onPreviewKeyEvent {
                                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (it.nativeKeyEvent.keyCode) {
                                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                        scope.launch { scrollState.scrollBy(150f) }
                                        true
                                    }
                                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                        scope.launch { scrollState.scrollBy(-150f) }
                                        true
                                    }
                                    else -> false
                                }
                            }
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .verticalScroll(scrollState)
                        ) {
                            Text(
                                text = currentApp.changelog.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = PushTVColors.TextSoft,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }

        // Right Side: Original Actions
        Surface(
            colors = NonInteractiveSurfaceDefaults.colors(containerColor = PushTVColors.Panel),
            modifier = Modifier
                .fillMaxHeight()
                .width(320.dp)
        ) {
            Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(PushTVColors.TextPrimary.copy(alpha = 0.1f)))

            Column(modifier = Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = PushTVColors.TextPrimary, maxLines = 1)

                currentApp?.let { app ->
                    if (app.updateUrl.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("当前 ${app.versionName}", style = MaterialTheme.typography.bodySmall, color = PushTVColors.TextSecondary)
                            val hasUpdate = softwareViewModel?.isVersionDifferent(app.versionName, app.remoteVersion) == true
                            if (hasUpdate) {
                                Text("最新 ${app.remoteVersion}", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = PushTVColors.Success)
                            } else {
                                val currentStatus = app.remoteStatus
                                val status = when {
                                    app.isChecking -> "正在检查..."
                                    currentStatus != "连接成功" -> currentStatus ?: "尚未检测"
                                    app.remoteVersion.isNullOrBlank() -> "未获取到远端版本"
                                    softwareViewModel?.canCompareVersions(app.versionName, app.remoteVersion) != true ->
                                        "版本格式无法比较 (${app.remoteVersion})"
                                    else -> "已是最新 (${app.remoteVersion})"
                                }
                                val color = if (
                                    status.contains("失败") ||
                                    status.contains("错误") ||
                                    status.contains("限制") ||
                                    status.contains("未找到") ||
                                    status.contains("解析失败")
                                ) PushTVColors.Error else PushTVColors.TextSecondary
                                Text(status, style = MaterialTheme.typography.bodySmall, color = color)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .focusGroup()
                        .verticalScroll(actionsScrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    currentDownload?.let { transfer ->
                        DownloadProgress(
                            transfer = transfer,
                            actionFocusRequester = progressActionRequester,
                            downFocusRequester = downloadRequester,
                            onActionFocusChanged = { isFocused ->
                                isProgressActionFocused = isFocused
                                if (isFocused) revealDownloadTop()
                            },
                            onCancel = { packageName?.let { softwareViewModel?.cancelDownload(it) } },
                            onRetry = {
                                packageName?.let { softwareViewModel?.retryDownload(it) }
                                revealDownloadTop()
                            }
                        )
                    }

                    if (primaryAsset != null) {
                        Text(
                            if (primaryAsset.isRecommended) "推荐升级" else "可用更新",
                            style = MaterialTheme.typography.labelMedium,
                            color = PushTVColors.Primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            onClick = { startDownloadAndFocusTop(primaryAsset.downloadUrl) },
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (primaryAsset.isRecommended) PushTVColors.Success.copy(alpha = 0.15f) else PushTVColors.TextPrimary.copy(alpha = 0.08f),
                                focusedContainerColor = PushTVColors.Success
                            ),
                            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(downloadRequester)
                                .onPreviewKeyEvent { event ->
                                    val canEnterProgress = currentDownload?.state
                                        ?.let { it != TransferState.COMPLETED } == true
                                    if (
                                        canEnterProgress &&
                                        event.type == KeyEventType.KeyDown &&
                                        event.key == Key.DirectionUp
                                    ) {
                                        runCatching { progressActionRequester.requestFocus() }
                                        true
                                    } else {
                                        false
                                    }
                                }
                                .focusProperties {
                                    left = leftToChangelog
                                    up = if (currentDownload?.state in setOf(
                                            TransferState.QUEUED,
                                            TransferState.RESOLVING,
                                            TransferState.TRANSFERRING,
                                            TransferState.FAILED,
                                            TransferState.CANCELED
                                        )) progressActionRequester else FocusRequester.Cancel
                                    down = actionRequesters.firstOrNull() ?: otherAssetRequesters.firstOrNull() ?: FocusRequester.Cancel
                                }
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = PushTVColors.TextPrimary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("下载并安装新版本", style = MaterialTheme.typography.titleMedium, color = PushTVColors.TextPrimary)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(primaryAsset.name, style = MaterialTheme.typography.bodySmall, color = PushTVColors.TextPrimary.copy(alpha = 0.6f), maxLines = 2)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text("常规操作", style = MaterialTheme.typography.labelMedium, color = PushTVColors.TextSecondary)

                    actions.forEachIndexed { index, action ->
                        val requester = actionRequesters[index]
                        val previousRequester = actionRequesters.getOrNull(index - 1) ?: if (primaryAsset != null) downloadRequester else null
                        val nextRequester = actionRequesters.getOrNull(index + 1) ?: otherAssetRequesters.firstOrNull()
                        Surface(
                            onClick = action.onClick,
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                                focusedContainerColor = action.color
                            ),
                            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            modifier = Modifier
                                .focusRequester(requester)
                                .focusProperties {
                                    left = leftToChangelog
                                    right = FocusRequester.Cancel
                                    up = previousRequester ?: FocusRequester.Cancel
                                    down = nextRequester ?: FocusRequester.Cancel
                                }
                        ) {
                            Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(action.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = PushTVColors.TextPrimary)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(action.label, style = MaterialTheme.typography.bodyMedium, color = PushTVColors.TextPrimary)
                            }
                        }
                    }

                    if (otherAssets.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("其他可用版本", style = MaterialTheme.typography.labelMedium, color = PushTVColors.TextSecondary)
                        otherAssets.forEachIndexed { index, asset ->
                            val requester = otherAssetRequesters[index]
                            val previousRequester = otherAssetRequesters.getOrNull(index - 1) ?: actionRequesters.lastOrNull() ?: if (primaryAsset != null) downloadRequester else null
                            val nextRequester = otherAssetRequesters.getOrNull(index + 1)
                            Surface(
                                onClick = { startDownloadAndFocusTop(asset.downloadUrl) },
                                colors = ClickableSurfaceDefaults.colors(focusedContainerColor = PushTVColors.TextPrimary.copy(alpha = 0.15f)),
                                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                modifier = Modifier
                                    .focusRequester(requester)
                                    .focusProperties {
                                        left = leftToChangelog
                                        right = FocusRequester.Cancel
                                        up = previousRequester ?: FocusRequester.Cancel
                                        down = nextRequester ?: FocusRequester.Cancel
                                    }
                            ) {
                                Row(modifier = Modifier.padding(10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp), tint = PushTVColors.TextSecondary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(asset.name, style = MaterialTheme.typography.bodySmall, color = PushTVColors.TextSecondary, maxLines = 1)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DownloadProgress(
    transfer: TransferProgress,
    actionFocusRequester: FocusRequester,
    downFocusRequester: FocusRequester,
    onActionFocusChanged: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    val statusText = when (transfer.state) {
        TransferState.QUEUED -> "等待下载"
        TransferState.RESOLVING -> "正在获取下载地址"
        TransferState.TRANSFERRING -> "正在下载"
        TransferState.COMPLETED -> "下载完成"
        TransferState.FAILED -> "下载失败"
        TransferState.CANCELED -> "下载已取消"
    }
    val statusColor = when (transfer.state) {
        TransferState.COMPLETED -> PushTVColors.Success
        TransferState.FAILED -> PushTVColors.Error
        TransferState.CANCELED -> PushTVColors.Warning
        else -> PushTVColors.Primary
    }
    val isActive = transfer.state in setOf(
        TransferState.QUEUED,
        TransferState.RESOLVING,
        TransferState.TRANSFERRING
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PushTVColors.TextPrimary.copy(alpha = 0.05f))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(statusText, style = MaterialTheme.typography.labelLarge, color = statusColor)
                val detail = transfer.errorMessage ?: when {
                    transfer.state == TransferState.TRANSFERRING && transfer.totalBytes != null -> "${transfer.progress}%"
                    transfer.state == TransferState.TRANSFERRING -> "正在接收数据"
                    else -> transfer.fileName
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = PushTVColors.TextSecondary, maxLines = 1)
            }
            if (transfer.state != TransferState.COMPLETED) {
                Surface(
                    onClick = if (isActive) onCancel else onRetry,
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = PushTVColors.TextPrimary.copy(alpha = 0.06f),
                        focusedContainerColor = statusColor
                    ),
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            BorderStroke(2.dp, PushTVColors.TextPrimary),
                            inset = (-1).dp
                        )
                    ),
                    modifier = Modifier
                        .size(36.dp)
                        .focusRequester(actionFocusRequester)
                        .onFocusChanged { onActionFocusChanged(it.isFocused) }
                        .focusProperties { down = downFocusRequester }
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (isActive) Icons.Default.Close else Icons.Default.Refresh,
                            contentDescription = if (isActive) "取消下载" else "重新下载",
                            modifier = Modifier.size(18.dp),
                            tint = PushTVColors.TextPrimary
                        )
                    }
                }
            }
        }
        if (transfer.state == TransferState.TRANSFERRING && transfer.totalBytes != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(PushTVColors.TextPrimary.copy(alpha = 0.12f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(transfer.progress.coerceIn(0, 100) / 100f)
                        .fillMaxHeight()
                        .background(statusColor, RoundedCornerShape(3.dp))
                )
            }
        }
    }
}
