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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.example.pushtv.data.BackupManager
import com.example.pushtv.data.WebDavStatus
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FilterTab(
    label: String,
    mode: FilterMode,
    isSelected: Boolean,
    count: Int = 0,
    modifier: Modifier = Modifier,
    onSelect: (FilterMode) -> Unit
) {
    Surface(
        onClick = { onSelect(mode) },
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) PushTVColors.Primary else Color.Transparent,
            focusedContainerColor = if (isSelected) PushTVColors.Primary else PushTVColors.TextPrimary.copy(alpha = 0.1f)
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) PushTVColors.TextPrimary else PushTVColors.TextPrimary.copy(alpha = 0.6f)
            )
            if (count > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .background(if (isSelected) PushTVColors.TextPrimary.copy(alpha = 0.2f) else PushTVColors.Error, RoundedCornerShape(10.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(count.toString(), fontSize = 10.sp, color = PushTVColors.TextPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeContent(
    isMobile: Boolean,
    ipAddress: String,
    qrBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    storageInfo: String,
    fileList: List<ApkInfo>,
    isFocusActive: Boolean,
    preferredFocusKey: String?,
    onFocusKeyChanged: (String) -> Unit,
    onNavigateToSoftware: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onItemClick: (ApkInfo) -> Unit,
    onClearAll: () -> Unit,
    focusRequesterMap: SnapshotStateMap<String, FocusRequester>
) {
    val softwareRequester = focusRequesterMap.getOrPut(TvFocusKeys.HOME_SOFTWARE) { FocusRequester() }
    val backupRequester = focusRequesterMap.getOrPut(TvFocusKeys.HOME_BACKUP) { FocusRequester() }
    val clearRequester = focusRequesterMap.getOrPut(TvFocusKeys.HOME_CLEAR) { FocusRequester() }
    val firstFileRequester = fileList.firstOrNull()?.let {
        focusRequesterMap.getOrPut(TvFocusKeys.file(it.file.absolutePath)) { FocusRequester() }
    }

    if (isMobile) {
        var initialFocusPlaced by remember { mutableStateOf(false) }
        LaunchedEffect(isFocusActive) {
            if (isFocusActive && !initialFocusPlaced) {
                withFrameNanos { }
                val preferredRequester = preferredFocusKey?.let(focusRequesterMap::get)
                val focused = preferredRequester?.let { runCatching { it.requestFocus(); true }.getOrDefault(false) } == true
                if (!focused) softwareRequester.requestFocus()
                initialFocusPlaced = true
            }
        }
    }

    if (isMobile) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("PushTV", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold), color = PushTVColors.Primary)
            Spacer(modifier = Modifier.height(16.dp))

            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PushTVColors.TextPrimary.copy(alpha = 0.05f)).padding(16.dp)) {
                Column {
                    Text("浏览器访问地址:", style = MaterialTheme.typography.labelMedium, color = PushTVColors.TextSecondary)
                    Text(ipAddress, style = MaterialTheme.typography.headlineSmall, color = PushTVColors.Success)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(storageInfo, style = MaterialTheme.typography.bodySmall, color = PushTVColors.TextMuted)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.Button(
                    onClick = onNavigateToSoftware,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(softwareRequester)
                        .focusProperties {
                            right = backupRequester
                            down = firstFileRequester ?: FocusRequester.Cancel
                        }
                        .onFocusChanged { if (it.isFocused) onFocusKeyChanged(TvFocusKeys.HOME_SOFTWARE) },
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = PushTVColors.Primary)
                ) {
                    Icon(Icons.Default.Apps, contentDescription = "软件列表", tint = PushTVColors.TextPrimary)
                }

                val mobileWebDavStatus by BackupManager.webDavStatus.collectAsState()
                val mobileDotColor = when (mobileWebDavStatus) {
                    WebDavStatus.ONLINE -> PushTVColors.Success
                    WebDavStatus.OFFLINE -> PushTVColors.Error
                    WebDavStatus.UNCONFIGURED -> PushTVColors.TextMuted
                }
                androidx.compose.material3.Button(
                    onClick = onNavigateToBackup,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(backupRequester)
                        .focusProperties {
                            left = softwareRequester
                            right = if (fileList.isNotEmpty()) clearRequester else FocusRequester.Cancel
                            down = firstFileRequester ?: FocusRequester.Cancel
                        }
                        .onFocusChanged { if (it.isFocused) onFocusKeyChanged(TvFocusKeys.HOME_BACKUP) },
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = PushTVColors.Primary)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(mobileDotColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.CloudSync, contentDescription = "备份", tint = PushTVColors.TextPrimary)
                    }
                }

                if (fileList.isNotEmpty()) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = onClearAll,
                        modifier = Modifier
                            .weight(0.6f)
                            .focusRequester(clearRequester)
                            .focusProperties {
                                left = backupRequester
                                down = firstFileRequester ?: FocusRequester.Cancel
                            }
                            .onFocusChanged { if (it.isFocused) onFocusKeyChanged(TvFocusKeys.HOME_CLEAR) },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, PushTVColors.Error)
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "清空", tint = PushTVColors.Error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("已接收 (${fileList.size})", style = MaterialTheme.typography.titleMedium, color = PushTVColors.TextPrimary)
            Spacer(modifier = Modifier.height(12.dp))

            if (fileList.isEmpty()) {
                EmptyStatePlaceholder()
            } else {
                fileList.forEachIndexed { index, apkInfo ->
                    val key = TvFocusKeys.file(apkInfo.file.absolutePath)
                    val fr = focusRequesterMap.getOrPut(key) { FocusRequester() }
                    val previous = fileList.getOrNull(index - 1)?.let { focusRequesterMap[TvFocusKeys.file(it.file.absolutePath)] }
                    val next = fileList.getOrNull(index + 1)?.let {
                        focusRequesterMap.getOrPut(TvFocusKeys.file(it.file.absolutePath)) { FocusRequester() }
                    }
                    FileItem(
                        apkInfo,
                        { onItemClick(apkInfo) },
                        Modifier
                            .focusRequester(fr)
                            .focusProperties {
                                up = previous ?: softwareRequester
                                down = next ?: FocusRequester.Cancel
                            }
                            .onFocusChanged {
                                if (it.isFocused) {
                                    onFocusKeyChanged(key)
                                }
                            }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    } else {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            ConnectionCard(ipAddress, qrBitmap, storageInfo, Modifier.weight(1f).fillMaxHeight())
            FileListSection(
                fileList = fileList,
                isFocusActive = isFocusActive,
                preferredFocusKey = preferredFocusKey,
                onItemClick = onItemClick,
                onClearAll = onClearAll,
                onNavigateToSoftware = onNavigateToSoftware,
                onNavigateToBackup = onNavigateToBackup,
                onFocusKeyChanged = onFocusKeyChanged,
                focusRequesterMap = focusRequesterMap,
                modifier = Modifier.weight(1.2f).fillMaxHeight()
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ConnectionCard(ipAddress: String, qrBitmap: androidx.compose.ui.graphics.ImageBitmap?, storageInfo: String, modifier: Modifier) {
    Box(modifier = modifier.clip(RoundedCornerShape(24.dp)).background(PushTVColors.TextPrimary.copy(alpha = 0.03f)).border(1.dp, PushTVColors.TextPrimary.copy(alpha = 0.1f), RoundedCornerShape(24.dp)).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PushTV", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 32.sp), color = PushTVColors.Primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("扫码或浏览器访问，上传 APK", style = MaterialTheme.typography.bodyMedium, color = PushTVColors.TextSecondary, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(24.dp))
            Box(modifier = Modifier.size(160.dp).clip(RoundedCornerShape(16.dp)).background(PushTVColors.TextPrimary.copy(alpha = 0.05f)).padding(12.dp), contentAlignment = Alignment.Center) {
                if (qrBitmap != null) Image(bitmap = qrBitmap, contentDescription = "QR Code", modifier = Modifier.fillMaxSize())
                else Text("请连接 Wi-Fi", color = PushTVColors.TextSecondary)
            }
            Spacer(modifier = Modifier.height(24.dp))
            Surface(colors = NonInteractiveSurfaceDefaults.colors(containerColor = PushTVColors.Panel), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(0.9f)) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (ipAddress == "未知IP") "未连接到局域网" else ipAddress,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = PushTVColors.Success,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = storageInfo, style = MaterialTheme.typography.bodySmall, color = PushTVColors.TextMuted)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FileListSection(
    fileList: List<ApkInfo>,
    isFocusActive: Boolean,
    preferredFocusKey: String?,
    onItemClick: (ApkInfo) -> Unit,
    onClearAll: () -> Unit,
    onNavigateToSoftware: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onFocusKeyChanged: (String) -> Unit,
    focusRequesterMap: SnapshotStateMap<String, FocusRequester>,
    modifier: Modifier
) {
    val softwareRequester = focusRequesterMap.getOrPut(TvFocusKeys.HOME_SOFTWARE) { FocusRequester() }
    val backupRequester = focusRequesterMap.getOrPut(TvFocusKeys.HOME_BACKUP) { FocusRequester() }
    val clearRequester = focusRequesterMap.getOrPut(TvFocusKeys.HOME_CLEAR) { FocusRequester() }
    val fileKeys = fileList.map { TvFocusKeys.file(it.file.absolutePath) }
    fileKeys.forEach { key -> focusRequesterMap.getOrPut(key) { FocusRequester() } }
    val firstFileRequester = fileKeys.firstOrNull()?.let(focusRequesterMap::get)
    val listState = rememberLazyListState()
    val focusScope = rememberCoroutineScope()
    var initialFocusPlaced by remember { mutableStateOf(false) }

    val enterFileList: () -> Boolean = {
        val requester = firstFileRequester
        if (requester == null) {
            false
        } else {
            focusScope.launch {
                runCatching { listState.scrollToItem(0) }
                repeat(2) {
                    withFrameNanos { }
                    if (runCatching { requester.requestFocus(); true }.getOrDefault(false)) return@launch
                }
            }
            true
        }
    }
    val enterFilesOnDown = Modifier.onPreviewKeyEvent { event ->
        event.type == KeyEventType.KeyDown &&
            event.key == Key.DirectionDown &&
            enterFileList()
    }

    LaunchedEffect(isFocusActive) {
        if (isFocusActive && !initialFocusPlaced) {
            withFrameNanos { }
            val preferredRequester = preferredFocusKey?.takeIf { it in fileKeys || it == TvFocusKeys.HOME_SOFTWARE || it == TvFocusKeys.HOME_BACKUP || it == TvFocusKeys.HOME_CLEAR }
                ?.let(focusRequesterMap::get)
            val focused = preferredRequester?.let { runCatching { it.requestFocus(); true }.getOrDefault(false) } == true
            if (!focused) {
                val firstFileFocused = firstFileRequester?.let {
                    runCatching { it.requestFocus(); true }.getOrDefault(false)
                } == true
                if (!firstFileFocused) softwareRequester.requestFocus()
            }
            initialFocusPlaced = true
        }
    }

    LaunchedEffect(fileKeys, isFocusActive) {
        if (isFocusActive && preferredFocusKey?.startsWith("home:file:") == true && preferredFocusKey !in fileKeys) {
            withFrameNanos { }
            val target = firstFileRequester ?: softwareRequester
            target.requestFocus()
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("已接收 (${fileList.size})", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = PushTVColors.TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 软件列表按钮
                Button(
                    onClick = onNavigateToSoftware,
                    scale = ButtonDefaults.scale(focusedScale = 1f),
                    modifier = Modifier
                        .focusRequester(softwareRequester)
                        .then(enterFilesOnDown)
                        .focusProperties {
                            right = backupRequester
                        }
                        .onFocusChanged { if (it.isFocused) onFocusKeyChanged(TvFocusKeys.HOME_SOFTWARE) },
                    colors = ButtonDefaults.colors(
                        containerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                        focusedContainerColor = PushTVColors.Primary,
                        contentColor = PushTVColors.TextPrimary,
                        focusedContentColor = PushTVColors.TextPrimary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                    ),
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp))
                ) {
                    Icon(Icons.Default.Apps, contentDescription = "软件列表", tint = PushTVColors.TextPrimary)
                }

                // 备份按钮
                val webDavStatus by BackupManager.webDavStatus.collectAsState()
                val dotColor = when (webDavStatus) {
                    WebDavStatus.ONLINE -> PushTVColors.Success
                    WebDavStatus.OFFLINE -> PushTVColors.Error
                    WebDavStatus.UNCONFIGURED -> PushTVColors.TextMuted
                }

                Button(
                    onClick = onNavigateToBackup,
                    scale = ButtonDefaults.scale(focusedScale = 1f),
                    modifier = Modifier
                        .focusRequester(backupRequester)
                        .then(enterFilesOnDown)
                        .focusProperties {
                            left = softwareRequester
                            right = if (fileList.isNotEmpty()) clearRequester else FocusRequester.Cancel
                        }
                        .onFocusChanged { if (it.isFocused) onFocusKeyChanged(TvFocusKeys.HOME_BACKUP) },
                    colors = ButtonDefaults.colors(
                        containerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                        focusedContainerColor = PushTVColors.Primary,
                        contentColor = PushTVColors.TextPrimary,
                        focusedContentColor = PushTVColors.TextPrimary
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                    ),
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.CloudSync, contentDescription = "备份", tint = PushTVColors.TextPrimary)
                    }
                }

                if (fileList.isNotEmpty()) {
                    Button(
                        onClick = onClearAll,
                        scale = ButtonDefaults.scale(focusedScale = 1f),
                        modifier = Modifier
                            .focusRequester(clearRequester)
                            .then(enterFilesOnDown)
                            .focusProperties {
                                left = backupRequester
                            }
                            .onFocusChanged { if (it.isFocused) onFocusKeyChanged(TvFocusKeys.HOME_CLEAR) },
                        colors = ButtonDefaults.colors(
                            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                            focusedContainerColor = PushTVColors.Error,
                            contentColor = PushTVColors.TextPrimary,
                            focusedContentColor = PushTVColors.TextPrimary
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                        ),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp))
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "清空", tint = PushTVColors.TextPrimary)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            if (fileList.isEmpty()) item { EmptyStatePlaceholder() }
            else itemsIndexed(fileList, key = { _, apk -> apk.file.absolutePath + apk.file.lastModified() }) { index, apkInfo ->
                val key = TvFocusKeys.file(apkInfo.file.absolutePath)
                val fr = focusRequesterMap.getValue(key)
                FileItem(
                    apkInfo,
                    { onItemClick(apkInfo) },
                    Modifier
                        .focusRequester(fr)
                        .focusProperties {
                            if (index == 0) up = softwareRequester
                        }
                        .onFocusChanged {
                            if (it.isFocused) {
                                onFocusKeyChanged(key)
                            }
                        }
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EmptyStatePlaceholder() {
    Box(modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(24.dp)).border(2.dp, PushTVColors.TextPrimary.copy(alpha = 0.05f), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Inbox, contentDescription = null, modifier = Modifier.size(48.dp), tint = PushTVColors.Panel)
            Spacer(modifier = Modifier.height(12.dp))
            Text("等待接收中...", color = PushTVColors.TextMuted, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FileItem(apkInfo: ApkInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.03f),
            focusedContainerColor = PushTVColors.SurfaceFocused
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.Primary), inset = (-1).dp)
        ),
        modifier = modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(PushTVColors.TextPrimary.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = apkInfo.icon,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(apkInfo.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = PushTVColors.TextPrimary, maxLines = 1)
                        if (apkInfo.isInstalled) {
                            Spacer(modifier = Modifier.width(8.dp))
                            StatusTag(if (apkInfo.canUpdate) "有更新" else "已安装", if (apkInfo.canUpdate) PushTVColors.Warning else PushTVColors.Success)
                        }
                    }
                    Text("${apkInfo.versionName} • ${apkInfo.sizeMb}", style = MaterialTheme.typography.bodySmall, color = if (isFocused) PushTVColors.TextPrimary.copy(alpha = 0.8f) else PushTVColors.TextSecondary)
                }
                if (!isFocused) Text("管理", style = MaterialTheme.typography.bodySmall, color = PushTVColors.TextSecondary.copy(alpha = 0.6f))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StatusTag(text: String, color: Color) {
    Box(modifier = Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(4.dp)).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) {
        Text(text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
