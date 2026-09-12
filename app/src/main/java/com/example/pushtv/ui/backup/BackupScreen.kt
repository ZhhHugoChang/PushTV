@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.example.pushtv.ui.backup

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.example.pushtv.data.AppBackupStatus
import com.example.pushtv.data.BackupAppItem
import com.example.pushtv.data.BackupProgress
import com.example.pushtv.data.BackupTaskType
import com.example.pushtv.data.CloudApkItem
import com.example.pushtv.data.CloudAppInstallStatus
import com.example.pushtv.data.WebDavStatus
import com.example.pushtv.ui.home.ActionItem
import com.example.pushtv.ui.home.ConfirmDialog
import com.example.pushtv.ui.home.installApk
import com.example.pushtv.ui.theme.PushTVColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun BackupScreen(
    onNavigateBack: () -> Unit,
    backupViewModel: BackupViewModel = viewModel()
) {
    val context = LocalContext.current
    val apps by backupViewModel.apps.collectAsState()
    val cloudApks by backupViewModel.cloudApks.collectAsState()
    val isLoading by backupViewModel.isLoading.collectAsState()
    val webDavConfig by backupViewModel.webDavConfig.collectAsState()
    val webDavStatus by backupViewModel.webDavStatus.collectAsState()
    val activeProgress by backupViewModel.activeProgress.collectAsState()

    // 选项卡状态：0 = 本机应用, 1 = 云端网盘
    var selectedTab by remember { mutableIntStateOf(0) }

    // 模式与抽屉状态
    var isSelectionMode by remember { mutableStateOf(false) }
    var drawerApp by remember { mutableStateOf<BackupAppItem?>(null) }
    var drawerCloudApk by remember { mutableStateOf<CloudApkItem?>(null) }
    var isMultiSelectDrawerOpen by remember { mutableStateOf(false) }
    val isDrawerOpen = drawerApp != null || drawerCloudApk != null || isMultiSelectDrawerOpen
    val drawerFocusRequester = remember { FocusRequester() }

    // 焦点记忆与恢复
    var drawerOriginKey by remember { mutableStateOf<String?>(null) }
    var lastFocusedKey by remember { mutableStateOf<String?>(null) }
    var restoreFocusGeneration by remember { mutableIntStateOf(0) }

    // 弹窗状态
    var showConfigDialog by remember { mutableStateOf(false) }
    var showDuplicateDialog by remember { mutableStateOf(false) }
    var duplicateAppToBackup by remember { mutableStateOf<BackupAppItem?>(null) }
    var backedUpCountInSelection by remember { mutableIntStateOf(0) }
    var showInstallPromptDialog by remember { mutableStateOf(false) }
    var downloadedFilesForInstall by remember { mutableStateOf<List<File>>(emptyList()) }
    var cloudApkToDelete by remember { mutableStateOf<CloudApkItem?>(null) }
    var storageWarning by remember { mutableStateOf<StorageCheckResult?>(null) }

    // 顶部按钮及任务取消按钮焦点 Requester
    val localTabRequester = remember { FocusRequester() }
    val cloudTabRequester = remember { FocusRequester() }
    val refreshRequester = remember { FocusRequester() }
    val settingsRequester = remember { FocusRequester() }
    val selectModeRequester = remember { FocusRequester() }
    val selectAllRequester = remember { FocusRequester() }
    val completeSelectionRequester = remember { FocusRequester() }
    val uploadRequester = remember { FocusRequester() }
    val downloadRequester = remember { FocusRequester() }
    val cancelTaskRequester = remember { FocusRequester() }

    val focusRequesterMap = remember { mutableStateMapOf<String, FocusRequester>() }
    focusRequesterMap["backup:tab:local"] = localTabRequester
    focusRequesterMap["backup:tab:cloud"] = cloudTabRequester
    focusRequesterMap["backup:refresh"] = refreshRequester
    focusRequesterMap["backup:settings"] = settingsRequester
    focusRequesterMap["backup:select_mode"] = selectModeRequester
    focusRequesterMap["backup:select_all"] = selectAllRequester
    focusRequesterMap["backup:complete"] = completeSelectionRequester

    val appKeys = apps.map { "backup:app:${it.packageName}" }
    appKeys.forEach { key -> focusRequesterMap.getOrPut(key) { FocusRequester() } }
    val cloudKeys = cloudApks.map { "backup:cloud:${it.fileName}" }
    cloudKeys.forEach { key -> focusRequesterMap.getOrPut(key) { FocusRequester() } }

    val gridState = rememberLazyGridState()
    val cloudGridState = rememberLazyGridState()
    val focusScope = rememberCoroutineScope()
    var initialFocusPlaced by remember { mutableStateOf(false) }
    var lastFocusedIndex by remember { mutableIntStateOf(0) }

    // 安全调度焦点：优先请求 targetKey；若失败则 100% 兜底至当前列表首项或设置按钮
    val requestFocusSafely: suspend (String?) -> Unit = { targetKey ->
        val primaryRequester = targetKey?.let { focusRequesterMap[it] }
        val firstKey = if (selectedTab == 0) {
            apps.firstOrNull()?.let { "backup:app:${it.packageName}" }
        } else {
            cloudApks.firstOrNull()?.let { "backup:cloud:${it.fileName}" }
        }
        val fallbackRequester = firstKey?.let { focusRequesterMap[it] } ?: settingsRequester

        var focused = false
        repeat(5) {
            withFrameNanos { }
            if (primaryRequester != null && runCatching { primaryRequester.requestFocus(); true }.getOrDefault(false)) {
                focused = true
                return@repeat
            }
        }
        if (!focused) {
            repeat(5) {
                withFrameNanos { }
                if (runCatching { fallbackRequester.requestFocus(); true }.getOrDefault(false)) {
                    focused = true
                    return@repeat
                }
            }
        }
    }

    val enterCurrentAppList: () -> Boolean = {
        val (firstKey, currentGridState) = if (selectedTab == 0) {
            (apps.firstOrNull()?.let { "backup:app:${it.packageName}" }) to gridState
        } else {
            (cloudApks.firstOrNull()?.let { "backup:cloud:${it.fileName}" }) to cloudGridState
        }
        val requester = firstKey?.let { focusRequesterMap.getOrPut(it) { FocusRequester() } }
        if (requester == null) {
            false
        } else {
            focusScope.launch {
                runCatching { currentGridState.scrollToItem(0) }
                repeat(3) {
                    withFrameNanos { }
                    if (runCatching { requester.requestFocus(); true }.getOrDefault(false)) return@launch
                }
            }
            true
        }
    }

    // 顶部操作栏向下按键：优先跳转到“取消任务”按钮（若存在正在执行的任务），否则进入列表
    val enterDownFromTopBar = Modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
            if (activeProgress != null) {
                cancelTaskRequester.requestFocus()
                true
            } else {
                enterCurrentAppList()
            }
        } else false
    }

    val selectedCount = if (selectedTab == 0) apps.count { it.isSelected } else cloudApks.count { it.isSelected }

    // 监听单项下载完成事件，触发安装弹窗
    LaunchedEffect(Unit) {
        backupViewModel.singleInstallPromptEvent.collect { files ->
            downloadedFilesForInstall = files
            showInstallPromptDialog = true
        }
    }

    // 遥控器返回键处理
    BackHandler {
        if (isDrawerOpen) {
            drawerApp = null
            drawerCloudApk = null
            isMultiSelectDrawerOpen = false
            restoreFocusGeneration++
        } else if (isSelectionMode) {
            isSelectionMode = false
            if (selectedTab == 0) {
                backupViewModel.clearSelection()
            } else {
                backupViewModel.clearCloudSelection()
            }
            lastFocusedKey = "backup:select_mode"
            restoreFocusGeneration++
            focusScope.launch {
                repeat(5) {
                    withFrameNanos { }
                    if (runCatching { selectModeRequester.requestFocus(); true }.getOrDefault(false)) {
                        return@launch
                    }
                }
            }
        } else {
            onNavigateBack()
        }
    }

    // 双阶段焦点恢复：阶段1立即尝试；阶段2等待抽屉退出动画（320ms）彻底完成后再次兜底锚定
    LaunchedEffect(isDrawerOpen, restoreFocusGeneration) {
        if (isDrawerOpen) {
            repeat(5) {
                withFrameNanos { }
                if (runCatching { drawerFocusRequester.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
            }
        } else if (restoreFocusGeneration > 0) {
            val targetKey = drawerOriginKey ?: lastFocusedKey
            // 阶段 1：抽屉刚关闭，立即尝试回位
            requestFocusSafely(targetKey)
            // 阶段 2：等待 AnimatedVisibility 滑出动画结束后，兜底检查并强行锚定
            delay(320)
            requestFocusSafely(targetKey)
            drawerOriginKey = null
        }
    }

    // 初始化焦点定位：优先定在列表第一项
    LaunchedEffect(apps.isNotEmpty(), cloudApks.isNotEmpty()) {
        if (!initialFocusPlaced) {
            val firstKey = if (selectedTab == 0) {
                apps.firstOrNull()?.let { "backup:app:${it.packageName}" }
            } else {
                cloudApks.firstOrNull()?.let { "backup:cloud:${it.fileName}" }
            }
            if (firstKey != null) {
                withFrameNanos { }
                val requester = focusRequesterMap.getOrPut(firstKey) { FocusRequester() }
                val focused = runCatching { requester.requestFocus(); true }.getOrDefault(false)
                if (focused) initialFocusPlaced = true
            }
        }
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (!initialFocusPlaced) {
            val firstKey = if (selectedTab == 0) {
                apps.firstOrNull()?.let { "backup:app:${it.packageName}" }
            } else {
                cloudApks.firstOrNull()?.let { "backup:cloud:${it.fileName}" }
            }
            val requester = firstKey?.let { focusRequesterMap[it] } ?: localTabRequester
            requester.requestFocus()
        }
    }

    // 单应用抽屉操作项（本机应用）
    // 单应用抽屉操作项（本机应用）
    val singleAppActions = remember(drawerApp, webDavStatus) {
        val app = drawerApp ?: return@remember emptyList<ActionItem>()
        val list = mutableListOf<ActionItem>()
        list.add(
            ActionItem(
                label = "上传云端备份",
                icon = Icons.Default.CloudUpload,
                color = PushTVColors.Primary
            ) {
                val currentApp = drawerApp
                drawerApp = null
                restoreFocusGeneration++
                if (currentApp != null) {
                    if (currentApp.backupStatus == AppBackupStatus.BACKED_UP) {
                        duplicateAppToBackup = currentApp
                        showDuplicateDialog = true
                    } else {
                        backupViewModel.startBackupApp(currentApp, skipBackedUp = false)
                    }
                }
            }
        )
        list
    }

    // 单应用抽屉操作项（云端网盘 APK）
    val singleCloudActions = remember(drawerCloudApk, webDavStatus) {
        val item = drawerCloudApk ?: return@remember emptyList<ActionItem>()
        val list = mutableListOf<ActionItem>()
        if (item.localApkFile != null) {
            list.add(
                ActionItem(
                    label = "立即安装",
                    icon = Icons.Default.CheckCircle,
                    color = PushTVColors.Success
                ) {
                    val currentItem = drawerCloudApk
                    drawerCloudApk = null
                    restoreFocusGeneration++
                    if (currentItem?.localApkFile != null) {
                        installApk(context, currentItem.localApkFile)
                    }
                }
            )
            list.add(
                ActionItem(
                    label = "重新下载",
                    icon = Icons.Default.CloudDownload,
                    color = PushTVColors.Primary
                ) {
                    val currentItem = drawerCloudApk
                    drawerCloudApk = null
                    restoreFocusGeneration++
                    if (currentItem != null) {
                        val check = backupViewModel.checkStorageForDownload(listOf(currentItem))
                        if (!check.isEnough) {
                            storageWarning = check
                        } else {
                            backupViewModel.startDownloadCloudApk(currentItem)
                        }
                    }
                }
            )
        } else {
            list.add(
                ActionItem(
                    label = "下载到电视",
                    icon = Icons.Default.CloudDownload,
                    color = PushTVColors.Primary
                ) {
                    val currentItem = drawerCloudApk
                    drawerCloudApk = null
                    restoreFocusGeneration++
                    if (currentItem != null) {
                        val check = backupViewModel.checkStorageForDownload(listOf(currentItem))
                        if (!check.isEnough) {
                            storageWarning = check
                        } else {
                            backupViewModel.startDownloadCloudApk(currentItem)
                        }
                    }
                }
            )
        }
        list.add(
            ActionItem(
                label = "删除云端文件",
                icon = Icons.Default.Delete,
                color = PushTVColors.Error
            ) {
                val currentItem = drawerCloudApk
                drawerCloudApk = null
                restoreFocusGeneration++
                if (currentItem != null) {
                    cloudApkToDelete = currentItem
                }
            }
        )
        list
    }

    // 本机多选菜单抽屉操作项（遥控器菜单键调出：仅保留上传）
    val multiSelectActions = remember(isMultiSelectDrawerOpen, selectedCount, webDavStatus) {
        listOf(
            ActionItem(
                label = "上传所选应用 ($selectedCount)",
                icon = Icons.Default.CloudUpload,
                color = PushTVColors.Primary
            ) {
                isMultiSelectDrawerOpen = false
                restoreFocusGeneration++
                val selectedApps = apps.filter { it.isSelected }
                val dupCount = selectedApps.count { it.backupStatus == AppBackupStatus.BACKED_UP }
                if (dupCount > 0) {
                    duplicateAppToBackup = null
                    backedUpCountInSelection = dupCount
                    showDuplicateDialog = true
                } else {
                    backupViewModel.startBackupSelected(skipBackedUp = false)
                }
            }
        )
    }

    // 网盘多选菜单抽屉操作项（遥控器菜单键调出）
    val cloudMultiSelectActions = remember(isMultiSelectDrawerOpen, selectedCount, webDavStatus) {
        listOf(
            ActionItem(
                label = "下载所选应用 ($selectedCount)",
                icon = Icons.Default.CloudDownload,
                color = PushTVColors.Primary
            ) {
                isMultiSelectDrawerOpen = false
                restoreFocusGeneration++
                val selected = cloudApks.filter { it.isSelected }
                if (selected.isNotEmpty()) {
                    val check = backupViewModel.checkStorageForDownload(selected)
                    if (!check.isEnough) {
                        storageWarning = check
                    } else {
                        backupViewModel.startDownloadCloudApks(selected, isSingleAppRestore = false)
                    }
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PushTVColors.Background)
            .onPreviewKeyEvent { event ->
                // 监听遥控器菜单键：多选模式下打开批量操作抽屉
                if (event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Menu || event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_MENU)
                ) {
                    if (isSelectionMode && selectedCount > 0 && !isDrawerOpen) {
                        drawerOriginKey = if (selectedTab == 0) {
                            apps.getOrNull(lastFocusedIndex)?.let { "backup:app:${it.packageName}" }
                        } else {
                            cloudApks.getOrNull(lastFocusedIndex)?.let { "backup:cloud:${it.fileName}" }
                        }
                        isMultiSelectDrawerOpen = true
                        true
                    } else false
                } else false
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 48.dp, vertical = 32.dp)
                .focusProperties {
                    canFocus = !isDrawerOpen
                }
        ) {
            // 顶部操作栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                if (!isSelectionMode) {
                    // 常规模式：双 Tab 切换器 + WebDAV 状态
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 本机应用 Tab
                        Button(
                            onClick = {
                                selectedTab = 0
                                enterCurrentAppList()
                            },
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(
                                containerColor = if (selectedTab == 0) PushTVColors.Primary.copy(alpha = 0.2f) else PushTVColors.TextPrimary.copy(alpha = 0.05f),
                                focusedContainerColor = PushTVColors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                            border = ButtonDefaults.border(
                                border = if (selectedTab == 0) Border(BorderStroke(1.5.dp, PushTVColors.Primary)) else Border.None,
                                focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                            ),
                            modifier = Modifier
                                .focusRequester(localTabRequester)
                                .then(enterDownFromTopBar)
                                .onFocusChanged { if (it.isFocused) lastFocusedKey = "backup:tab:local" }
                                .focusProperties {
                                    left = FocusRequester.Cancel
                                    right = cloudTabRequester
                                }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Apps, contentDescription = null, tint = PushTVColors.TextPrimary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("本机 (${apps.size})", color = PushTVColors.TextPrimary, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal)
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        // 云端网盘 Tab
                        Button(
                            onClick = {
                                selectedTab = 1
                                enterCurrentAppList()
                            },
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(
                                containerColor = if (selectedTab == 1) PushTVColors.Primary.copy(alpha = 0.2f) else PushTVColors.TextPrimary.copy(alpha = 0.05f),
                                focusedContainerColor = PushTVColors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                            border = ButtonDefaults.border(
                                border = if (selectedTab == 1) Border(BorderStroke(1.5.dp, PushTVColors.Primary)) else Border.None,
                                focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                            ),
                            modifier = Modifier
                                .focusRequester(cloudTabRequester)
                                .then(enterDownFromTopBar)
                                .onFocusChanged { if (it.isFocused) lastFocusedKey = "backup:tab:cloud" }
                                .focusProperties {
                                    left = localTabRequester
                                    right = refreshRequester
                                }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CloudQueue, contentDescription = null, tint = PushTVColors.TextPrimary, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("云端 (${cloudApks.size})", color = PushTVColors.TextPrimary, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal)
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        WebDavStatusBadge(webDavStatus, webDavConfig.serverUrl)
                    }

                    // 右侧操作按钮：刷新 + 设置 + 选择
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 刷新按钮（纯图标）
                        Button(
                            onClick = { backupViewModel.loadData() },
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(
                                containerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                                focusedContainerColor = PushTVColors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                            border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)),
                            modifier = Modifier
                                .focusRequester(refreshRequester)
                                .then(enterDownFromTopBar)
                                .onFocusChanged { if (it.isFocused) lastFocusedKey = "backup:refresh" }
                                .focusProperties {
                                    left = cloudTabRequester
                                    right = settingsRequester
                                }
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = PushTVColors.TextPrimary)
                        }

                        // 设置按钮（纯图标）
                        Button(
                            onClick = { showConfigDialog = true },
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(
                                containerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                                focusedContainerColor = PushTVColors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                            border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)),
                            modifier = Modifier
                                .focusRequester(settingsRequester)
                                .then(enterDownFromTopBar)
                                .onFocusChanged { if (it.isFocused) lastFocusedKey = "backup:settings" }
                                .focusProperties {
                                    left = refreshRequester
                                    right = selectModeRequester
                                }
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = "设置", tint = PushTVColors.TextPrimary)
                        }

                        // 选择按钮
                        Button(
                            onClick = {
                                isSelectionMode = true
                                enterCurrentAppList()
                            },
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(
                                containerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                                focusedContainerColor = PushTVColors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                            border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)),
                            modifier = Modifier
                                .focusRequester(selectModeRequester)
                                .then(enterDownFromTopBar)
                                .onFocusChanged { if (it.isFocused) lastFocusedKey = "backup:select_mode" }
                                .focusProperties {
                                    left = settingsRequester
                                    right = FocusRequester.Cancel
                                }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Checklist, contentDescription = null, tint = PushTVColors.TextPrimary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("选择", color = PushTVColors.TextPrimary)
                            }
                        }
                    }
                } else {
                    // 多选模式：左侧显示已选数量，右侧全选 + 完成 + 批量动作
                    val canUploadOrDownload = selectedCount > 0 && webDavStatus == WebDavStatus.ONLINE
                    val currentTabTotal = if (selectedTab == 0) apps.size else cloudApks.size
                    val isAllSelected = selectedCount == currentTabTotal && currentTabTotal > 0

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "已选择 ${selectedCount} 项",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = PushTVColors.TextPrimary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        StatusTag(if (selectedTab == 0) "本机" else "云端", PushTVColors.Primary)
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 全选/取消全选
                        Button(
                            onClick = {
                                if (selectedTab == 0) {
                                    if (isAllSelected) backupViewModel.clearSelection() else backupViewModel.selectAll()
                                } else {
                                    if (isAllSelected) backupViewModel.clearCloudSelection() else backupViewModel.selectAllCloud()
                                }
                            },
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(
                                containerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                                focusedContainerColor = PushTVColors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                            border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)),
                            modifier = Modifier
                                .focusRequester(selectAllRequester)
                                .then(enterDownFromTopBar)
                                .onFocusChanged { if (it.isFocused) lastFocusedKey = "backup:select_all" }
                                .focusProperties {
                                    left = FocusRequester.Cancel
                                    right = completeSelectionRequester
                                }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.SelectAll, contentDescription = null, tint = PushTVColors.TextPrimary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isAllSelected) "取消全选" else "全选", color = PushTVColors.TextPrimary)
                            }
                        }

                        // 完成选择按钮
                        Button(
                            onClick = {
                                isSelectionMode = false
                                if (selectedTab == 0) {
                                    backupViewModel.clearSelection()
                                } else {
                                    backupViewModel.clearCloudSelection()
                                }
                                lastFocusedKey = "backup:select_mode"
                                restoreFocusGeneration++
                                focusScope.launch {
                                    repeat(5) {
                                        withFrameNanos { }
                                        if (runCatching { selectModeRequester.requestFocus(); true }.getOrDefault(false)) {
                                            return@launch
                                        }
                                    }
                                }
                            },
                            scale = ButtonDefaults.scale(focusedScale = 1f),
                            colors = ButtonDefaults.colors(
                                containerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                                focusedContainerColor = PushTVColors.Primary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                            border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)),
                            modifier = Modifier
                                .focusRequester(completeSelectionRequester)
                                .then(enterDownFromTopBar)
                                .onFocusChanged { if (it.isFocused) lastFocusedKey = "backup:complete" }
                                .focusProperties {
                                    left = selectAllRequester
                                    right = if (selectedTab == 0) {
                                        if (canUploadOrDownload) uploadRequester else FocusRequester.Cancel
                                    } else {
                                        if (canUploadOrDownload) downloadRequester else FocusRequester.Cancel
                                    }
                                }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Done, contentDescription = null, tint = PushTVColors.TextPrimary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("完成", color = PushTVColors.TextPrimary)
                            }
                        }

                        if (selectedTab == 0) {
                            // 本机：上传所选备份（带数量）
                            Button(
                                onClick = {
                                    val selectedApps = apps.filter { it.isSelected }
                                    val dupCount = selectedApps.count { it.backupStatus == AppBackupStatus.BACKED_UP }
                                    if (dupCount > 0) {
                                        duplicateAppToBackup = null
                                        backedUpCountInSelection = dupCount
                                        showDuplicateDialog = true
                                    } else {
                                        backupViewModel.startBackupSelected(skipBackedUp = false)
                                    }
                                },
                                enabled = canUploadOrDownload,
                                scale = ButtonDefaults.scale(focusedScale = 1f),
                                colors = ButtonDefaults.colors(
                                    containerColor = PushTVColors.Primary.copy(alpha = 0.2f),
                                    focusedContainerColor = PushTVColors.Primary,
                                    contentColor = PushTVColors.Primary,
                                    focusedContentColor = PushTVColors.TextPrimary
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                                border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)),
                                modifier = Modifier
                                    .focusRequester(uploadRequester)
                                    .then(enterDownFromTopBar)
                                    .focusProperties {
                                        left = completeSelectionRequester
                                        right = FocusRequester.Cancel
                                    }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CloudUpload, contentDescription = "上传备份")
                                    if (selectedCount > 0) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("$selectedCount", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        } else {
                            // 网盘：下载所选应用（带数量）
                            Button(
                                onClick = {
                                    val selected = cloudApks.filter { it.isSelected }
                                    if (selected.isNotEmpty()) {
                                        val check = backupViewModel.checkStorageForDownload(selected)
                                        if (!check.isEnough) {
                                            storageWarning = check
                                        } else {
                                            backupViewModel.startDownloadCloudApks(selected, isSingleAppRestore = false)
                                        }
                                    }
                                },
                                enabled = canUploadOrDownload,
                                scale = ButtonDefaults.scale(focusedScale = 1f),
                                colors = ButtonDefaults.colors(
                                    containerColor = PushTVColors.Success.copy(alpha = 0.2f),
                                    focusedContainerColor = PushTVColors.Success,
                                    contentColor = PushTVColors.Success,
                                    focusedContentColor = PushTVColors.TextPrimary
                                ),
                                shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                                border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)),
                                modifier = Modifier
                                    .focusRequester(downloadRequester)
                                    .then(enterDownFromTopBar)
                                    .focusProperties {
                                        left = completeSelectionRequester
                                        right = FocusRequester.Cancel
                                    }
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CloudDownload, contentDescription = "下载所选")
                                    if (selectedCount > 0) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("$selectedCount", style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 正在传输进度条提示卡片（若有后台任务）
            activeProgress?.let { progress ->
                ActiveProgressBanner(
                    progress = progress,
                    cancelRequester = cancelTaskRequester,
                    upRequester = if (!isSelectionMode) (if (selectedTab == 0) localTabRequester else cloudTabRequester) else selectAllRequester,
                    downAction = { enterCurrentAppList() },
                    onCancel = {
                        backupViewModel.cancelTask()
                        lastFocusedKey = "backup:settings"
                        restoreFocusGeneration++
                        focusScope.launch {
                            repeat(5) {
                                withFrameNanos { }
                                if (runCatching { settingsRequester.requestFocus(); true }.getOrDefault(false)) {
                                    return@launch
                                }
                            }
                        }
                    }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 列表显示区域（根据 selectedTab 区分：0 = 本机应用，1 = 云端网盘）
            if (selectedTab == 0) {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("正在加载已安装应用与云端备份状态...", color = PushTVColors.TextSecondary)
                    }
                } else if (apps.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("未发现可备份的第三方应用", color = PushTVColors.TextSecondary)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = gridState,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 32.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .focusGroup()
                    ) {
                        itemsIndexed(apps, key = { _, appItem -> appItem.packageName }) { index, appItem ->
                            val key = "backup:app:${appItem.packageName}"
                            val fr = focusRequesterMap.getOrPut(key) { FocusRequester() }
                            val leftRequester = if (index % 2 == 1) appKeys.getOrNull(index - 1)?.let(focusRequesterMap::get) else null
                            val rightRequester = if (index % 2 == 0) appKeys.getOrNull(index + 1)?.let(focusRequesterMap::get) else null

                            val topTargetRequester = if (activeProgress != null) {
                                cancelTaskRequester
                            } else {
                                if (!isSelectionMode) localTabRequester else selectAllRequester
                            }

                            BackupAppItemCard(
                                item = appItem,
                                isSelectionMode = isSelectionMode,
                                onClick = {
                                    if (isSelectionMode) {
                                        backupViewModel.toggleSelect(appItem.packageName)
                                    } else {
                                        drawerOriginKey = key
                                        lastFocusedKey = key
                                        drawerApp = appItem
                                    }
                                },
                                modifier = Modifier
                                    .focusRequester(fr)
                                    .focusProperties {
                                        left = leftRequester ?: FocusRequester.Cancel
                                        right = rightRequester ?: FocusRequester.Cancel
                                        if (index < 2) up = topTargetRequester
                                    }
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            lastFocusedIndex = index
                                            lastFocusedKey = key
                                        }
                                    }
                            )
                        }
                    }
                }
            } else {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("正在读取云端备份的安装包...", color = PushTVColors.TextSecondary)
                    }
                } else if (cloudApks.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("云端暂无备份安装包", color = PushTVColors.TextSecondary)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        state = cloudGridState,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 32.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .focusGroup()
                    ) {
                        itemsIndexed(cloudApks, key = { _, cloudItem -> cloudItem.fileName }) { index, cloudItem ->
                            val key = "backup:cloud:${cloudItem.fileName}"
                            val fr = focusRequesterMap.getOrPut(key) { FocusRequester() }
                            val leftRequester = if (index % 2 == 1) cloudKeys.getOrNull(index - 1)?.let(focusRequesterMap::get) else null
                            val rightRequester = if (index % 2 == 0) cloudKeys.getOrNull(index + 1)?.let(focusRequesterMap::get) else null

                            val topTargetRequester = if (activeProgress != null) {
                                cancelTaskRequester
                            } else {
                                if (!isSelectionMode) cloudTabRequester else selectAllRequester
                            }

                            CloudApkItemCard(
                                item = cloudItem,
                                isSelectionMode = isSelectionMode,
                                onClick = {
                                    if (isSelectionMode) {
                                        backupViewModel.toggleCloudSelect(cloudItem.fileName)
                                    } else {
                                        drawerOriginKey = key
                                        lastFocusedKey = key
                                        drawerCloudApk = cloudItem
                                    }
                                },
                                modifier = Modifier
                                    .focusRequester(fr)
                                    .focusProperties {
                                        left = leftRequester ?: FocusRequester.Cancel
                                        right = rightRequester ?: FocusRequester.Cancel
                                        if (index < 2) up = topTargetRequester
                                    }
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            lastFocusedIndex = index
                                            lastFocusedKey = key
                                        }
                                    }
                            )
                        }
                    }
                }
            }
        }

        // 右侧操作抽屉（样式对齐软件列表抽屉头部与操作）
        AnimatedVisibility(
            visible = isDrawerOpen,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Back || event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK)
                    ) {
                        drawerApp = null
                        drawerCloudApk = null
                        isMultiSelectDrawerOpen = false
                        restoreFocusGeneration++
                        true
                    } else false
                }
        ) {
            val actions = when {
                drawerApp != null -> singleAppActions
                drawerCloudApk != null -> singleCloudActions
                selectedTab == 0 -> multiSelectActions
                else -> cloudMultiSelectActions
            }
            BackupActionDrawer(
                drawerApp = drawerApp,
                drawerCloudApk = drawerCloudApk,
                isMultiSelect = isMultiSelectDrawerOpen,
                selectedCount = selectedCount,
                actions = actions,
                drawerFocusRequester = drawerFocusRequester
            )
        }
    }

    // 设置弹窗
    if (showConfigDialog) {
        WebDavConfigDialog(
            initialConfig = webDavConfig,
            onDismiss = {
                showConfigDialog = false
                restoreFocusGeneration++
                focusScope.launch {
                    requestFocusSafely("backup:settings")
                }
            },
            onSave = { newConfig ->
                backupViewModel.saveConfig(newConfig)
                showConfigDialog = false
                restoreFocusGeneration++
                focusScope.launch {
                    requestFocusSafely("backup:settings")
                }
            },
            onTestConnection = { testConfig, onDone ->
                backupViewModel.testConnection(testConfig, onDone)
            }
        )
    }

    // 重复备份确认弹窗（全部覆盖 / 跳过已备份 / 取消）
    if (showDuplicateDialog) {
        val singleApp = duplicateAppToBackup
        val total = if (singleApp != null) 1 else selectedCount
        val backedCount = if (singleApp != null) 1 else backedUpCountInSelection

        DuplicateBackupDialog(
            totalSelected = total,
            backedUpCount = backedCount,
            onSkipBackedUp = {
                if (singleApp != null) {
                    duplicateAppToBackup = null
                } else {
                    backupViewModel.startBackupSelected(skipBackedUp = true)
                }
                showDuplicateDialog = false
                restoreFocusGeneration++
                focusScope.launch {
                    requestFocusSafely(lastFocusedKey)
                }
            },
            onOverwriteAll = {
                if (singleApp != null) {
                    backupViewModel.startBackupApp(singleApp, skipBackedUp = false)
                    duplicateAppToBackup = null
                } else {
                    backupViewModel.startBackupSelected(skipBackedUp = false)
                }
                showDuplicateDialog = false
                restoreFocusGeneration++
                focusScope.launch {
                    requestFocusSafely(lastFocusedKey)
                }
            },
            onDismiss = {
                duplicateAppToBackup = null
                showDuplicateDialog = false
                restoreFocusGeneration++
                focusScope.launch {
                    requestFocusSafely(lastFocusedKey)
                }
            }
        )
    }

    // 下载完成安装确认弹窗（单软件下载恢复全部完成后提示）
    if (showInstallPromptDialog) {
        InstallPromptDialog(
            downloadedFiles = downloadedFilesForInstall,
            onInstall = { apkFile ->
                installApk(context, apkFile)
                showInstallPromptDialog = false
                restoreFocusGeneration++
                focusScope.launch {
                    requestFocusSafely(lastFocusedKey)
                }
            },
            onDismiss = {
                showInstallPromptDialog = false
                restoreFocusGeneration++
                focusScope.launch {
                    requestFocusSafely(lastFocusedKey)
                }
            }
        )
    }

    // 删除云端备份文件确认弹窗
    cloudApkToDelete?.let { item ->
        DeleteCloudConfirmDialog(
            item = item,
            onConfirm = {
                cloudApkToDelete = null
                backupViewModel.deleteCloudApk(item.fileName)
                restoreFocusGeneration++
                focusScope.launch {
                    requestFocusSafely(lastFocusedKey)
                }
            },
            onDismiss = {
                cloudApkToDelete = null
                restoreFocusGeneration++
                focusScope.launch {
                    requestFocusSafely(lastFocusedKey)
                }
            }
        )
    }

    // 存储空间不足预警弹窗
    storageWarning?.let { warning ->
        InsufficientStorageDialog(
            warning = warning,
            onCleanAndRetry = {
                backupViewModel.cleanIncomingCache()
                val recheck = backupViewModel.checkStorageForDownload(warning.pendingDownloadItems)
                if (!recheck.isEnough) {
                    storageWarning = recheck
                } else {
                    val items = warning.pendingDownloadItems
                    storageWarning = null
                    backupViewModel.startDownloadCloudApks(items, isSingleAppRestore = (items.size == 1))
                }
            },
            onDismiss = {
                storageWarning = null
                restoreFocusGeneration++
                focusScope.launch {
                    requestFocusSafely(lastFocusedKey)
                }
            }
        )
    }
}

@Composable
private fun WebDavStatusBadge(status: WebDavStatus, serverUrl: String) {
    val (dotColor, text) = when (status) {
        WebDavStatus.ONLINE -> PushTVColors.Success to "在线"
        WebDavStatus.OFFLINE -> PushTVColors.Error to "离线"
        WebDavStatus.UNCONFIGURED -> PushTVColors.TextMuted to "未配置"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(PushTVColors.TextPrimary.copy(alpha = 0.06f))
            .border(1.dp, PushTVColors.TextPrimary.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "WebDAV: $text",
            style = MaterialTheme.typography.labelSmall,
            color = PushTVColors.TextSecondary
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActiveProgressBanner(
    progress: BackupProgress,
    cancelRequester: FocusRequester,
    upRequester: FocusRequester,
    downAction: () -> Boolean,
    onCancel: () -> Unit
) {
    val (tagText, tagColor) = if (progress.type == BackupTaskType.UPLOAD) {
        "上传" to PushTVColors.Primary
    } else {
        "下载" to PushTVColors.Success
    }

    Surface(
        colors = NonInteractiveSurfaceDefaults.colors(containerColor = PushTVColors.Panel),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 左侧类型标签：[上传] 或 [下载]
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(tagColor.copy(alpha = 0.2f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = tagText,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = tagColor
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = progress.statusMessage,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = PushTVColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Text(
                        text = "${(progress.currentFileProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = PushTVColors.Primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.currentFileProgress },
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = tagColor,
                    trackColor = PushTVColors.TextPrimary.copy(alpha = 0.1f),
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Button(
                onClick = onCancel,
                scale = ButtonDefaults.scale(focusedScale = 1.04f),
                colors = ButtonDefaults.colors(
                    containerColor = PushTVColors.TextPrimary.copy(alpha = 0.08f),
                    focusedContainerColor = PushTVColors.Error
                ),
                shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
                border = ButtonDefaults.border(
                    focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                ),
                modifier = Modifier
                    .focusRequester(cancelRequester)
                    .focusProperties {
                        up = upRequester
                    }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            downAction()
                        } else false
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("取消任务", color = PushTVColors.TextPrimary)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BackupActionDrawer(
    drawerApp: BackupAppItem?,
    drawerCloudApk: CloudApkItem? = null,
    isMultiSelect: Boolean,
    selectedCount: Int,
    actions: List<ActionItem>,
    drawerFocusRequester: FocusRequester
) {
    val actionRequesters = remember(actions.size) {
        List(actions.size) { index -> if (index == 0) drawerFocusRequester else FocusRequester() }
    }

    Surface(
        colors = NonInteractiveSurfaceDefaults.colors(containerColor = PushTVColors.Panel),
        modifier = Modifier
            .fillMaxHeight()
            .width(320.dp)
            .focusGroup()
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(PushTVColors.TextPrimary.copy(alpha = 0.1f))
        )

        Column(modifier = Modifier.padding(24.dp)) {
            if (drawerApp != null) {
                // 本机单应用头部详情（完全对齐软件抽屉结构）
                Text(
                    text = drawerApp.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = PushTVColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                val sizeMb = String.format("%.1f MB", drawerApp.apkFileLength / (1024f * 1024f))
                Text(
                    text = "当前版本: v${drawerApp.versionName} • $sizeMb",
                    style = MaterialTheme.typography.bodySmall,
                    color = PushTVColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (drawerApp.backupStatus) {
                        AppBackupStatus.BACKED_UP -> {
                            StatusTag("云端已备份", PushTVColors.Success)
                        }
                        AppBackupStatus.VERSION_MISMATCH -> {
                            StatusTag("云端有旧版 (${drawerApp.remoteVersion})", PushTVColors.Warning)
                        }
                        AppBackupStatus.NOT_BACKED_UP -> {
                            StatusTag("云端暂无备份", PushTVColors.TextMuted)
                        }
                    }
                }
            } else if (drawerCloudApk != null) {
                // 云端网盘单应用头部详情
                Text(
                    text = drawerCloudApk.appName,
                    style = MaterialTheme.typography.titleLarge,
                    color = PushTVColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                val sizeMb = String.format("%.1f MB", drawerCloudApk.size / (1024f * 1024f))
                val versionStr = if (drawerCloudApk.versionName != null) "v${drawerCloudApk.versionName}" else "未知版本"
                Text(
                    text = "网盘版本: $versionStr • $sizeMb",
                    style = MaterialTheme.typography.bodySmall,
                    color = PushTVColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (drawerCloudApk.installStatus) {
                        CloudAppInstallStatus.INSTALLED_SAME -> {
                            StatusTag("电视已安装相同版本", PushTVColors.Success)
                        }
                        CloudAppInstallStatus.INSTALLED_DIFFERENT -> {
                            StatusTag("电视已安装(v${drawerCloudApk.installedVersion})", PushTVColors.Warning)
                        }
                        CloudAppInstallStatus.NOT_INSTALLED -> {
                            StatusTag("电视未安装", PushTVColors.TextMuted)
                        }
                    }
                    if (drawerCloudApk.localApkFile != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        StatusTag("本地已下载", PushTVColors.Primary)
                    }
                }
            } else if (isMultiSelect) {
                // 批量多选头部
                Text(
                    text = "批量操作",
                    style = MaterialTheme.typography.titleLarge,
                    color = PushTVColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "已选择 $selectedCount 项应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = PushTVColors.TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 动作按钮列表
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                actions.forEachIndexed { index, action ->
                    val requester = actionRequesters[index]
                    val previousRequester = actionRequesters.getOrNull(index - 1)
                    val nextRequester = actionRequesters.getOrNull(index + 1)

                    Surface(
                        onClick = action.onClick,
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                            focusedContainerColor = action.color
                        ),
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(requester)
                            .focusProperties {
                                left = FocusRequester.Cancel
                                right = FocusRequester.Cancel
                                up = previousRequester ?: FocusRequester.Cancel
                                down = nextRequester ?: FocusRequester.Cancel
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(14.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(action.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = PushTVColors.TextPrimary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(action.label, style = MaterialTheme.typography.bodyMedium, color = PushTVColors.TextPrimary)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BackupAppItemCard(
    item: BackupAppItem,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelectionMode && item.isSelected) {
                PushTVColors.Primary.copy(alpha = 0.15f)
            } else {
                PushTVColors.TextPrimary.copy(alpha = 0.03f)
            },
            focusedContainerColor = if (isSelectionMode && item.isSelected) {
                PushTVColors.Primary.copy(alpha = 0.35f)
            } else {
                PushTVColors.SurfaceFocused
            }
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.Primary), inset = (-1).dp)
        ),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 多选框 (仅在选择模式下显示)
            if (isSelectionMode) {
                Icon(
                    imageVector = if (item.isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (item.isSelected) PushTVColors.Primary else PushTVColors.TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            // 应用图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PushTVColors.TextPrimary.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = item.icon,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 名称与版本大小（不显示包名）
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = PushTVColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    when (item.backupStatus) {
                        AppBackupStatus.BACKED_UP -> {
                            Spacer(modifier = Modifier.width(8.dp))
                            StatusTag("云端已备份", PushTVColors.Success)
                        }
                        AppBackupStatus.VERSION_MISMATCH -> {
                            Spacer(modifier = Modifier.width(8.dp))
                            StatusTag("有新版本待备", PushTVColors.Warning)
                        }
                        AppBackupStatus.NOT_BACKED_UP -> {}
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                val sizeMb = String.format("%.1f MB", item.apkFileLength / (1024f * 1024f))
                Text(
                    text = "版本: ${item.versionName} • $sizeMb",
                    style = MaterialTheme.typography.bodySmall,
                    color = PushTVColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 未聚焦且非多选模式时显示“管理”提示
            if (!isFocused && !isSelectionMode) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "管理",
                    style = MaterialTheme.typography.bodySmall,
                    color = PushTVColors.TextSecondary.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CloudApkItemCard(
    item: CloudApkItem,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelectionMode && item.isSelected) {
                PushTVColors.Primary.copy(alpha = 0.15f)
            } else {
                PushTVColors.TextPrimary.copy(alpha = 0.03f)
            },
            focusedContainerColor = if (isSelectionMode && item.isSelected) {
                PushTVColors.Primary.copy(alpha = 0.35f)
            } else {
                PushTVColors.SurfaceFocused
            }
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.Primary), inset = (-1).dp)
        ),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 多选框 (仅在选择模式下显示)
            if (isSelectionMode) {
                Icon(
                    imageVector = if (item.isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (item.isSelected) PushTVColors.Primary else PushTVColors.TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            // 应用图标
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PushTVColors.TextPrimary.copy(alpha = 0.05f)),
                contentAlignment = Alignment.Center
            ) {
                if (item.icon != null) {
                    AsyncImage(
                        model = item.icon,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudQueue,
                        contentDescription = null,
                        tint = PushTVColors.Primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 名称与状态
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.appName,
                        style = MaterialTheme.typography.titleMedium,
                        color = PushTVColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // 状态徽章
                    if (item.localApkFile != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusTag("本地已下载", PushTVColors.Primary)
                    }

                    when (item.installStatus) {
                        CloudAppInstallStatus.INSTALLED_SAME -> {
                            Spacer(modifier = Modifier.width(8.dp))
                            StatusTag("已安装", PushTVColors.Success)
                        }
                        CloudAppInstallStatus.INSTALLED_DIFFERENT -> {
                            Spacer(modifier = Modifier.width(8.dp))
                            StatusTag("本机v${item.installedVersion}", PushTVColors.Warning)
                        }
                        CloudAppInstallStatus.NOT_INSTALLED -> {
                            Spacer(modifier = Modifier.width(8.dp))
                            StatusTag("未安装", PushTVColors.TextMuted)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                val sizeMb = String.format("%.1f MB", item.size / (1024f * 1024f))
                val versionInfo = if (item.versionName != null) "版本: v${item.versionName}" else item.fileName
                Text(
                    text = "$versionInfo • $sizeMb",
                    style = MaterialTheme.typography.bodySmall,
                    color = PushTVColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 未聚焦且非多选模式时显示“操作”提示
            if (!isFocused && !isSelectionMode) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "操作",
                    style = MaterialTheme.typography.bodySmall,
                    color = PushTVColors.TextSecondary.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun StatusTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = color
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DuplicateBackupDialog(
    totalSelected: Int,
    backedUpCount: Int,
    onSkipBackedUp: () -> Unit,
    onOverwriteAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val skipRequester = remember { FocusRequester() }
    val overwriteRequester = remember { FocusRequester() }
    val cancelRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        skipRequester.requestFocus()
    }

    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            colors = NonInteractiveSurfaceDefaults.colors(containerColor = PushTVColors.Panel),
            modifier = Modifier
                .width(460.dp)
                .padding(16.dp)
                .focusGroup()
                .focusProperties { exit = { FocusRequester.Cancel } }
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "重复备份提示",
                    style = MaterialTheme.typography.headlineSmall,
                    color = PushTVColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                val message = if (totalSelected == 1) {
                    "该应用在云端已有相同版本的备份。\n请选择操作策略："
                } else {
                    "已选 ${totalSelected} 个应用，其中 ${backedUpCount} 个在云端已有相同版本备份。\n请选择备份策略："
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PushTVColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 跳过已备份按钮（默认灰底，选中高亮品牌色）
                    Button(
                        onClick = onSkipBackedUp,
                        scale = ButtonDefaults.scale(focusedScale = 1.04f),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.08f),
                            focusedContainerColor = PushTVColors.Primary
                        ),
                        modifier = Modifier
                            .weight(1.1f)
                            .height(44.dp)
                            .focusRequester(skipRequester)
                            .focusProperties {
                                right = overwriteRequester
                            }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (totalSelected == 1) "取消操作" else "跳过已备份",
                                textAlign = TextAlign.Center,
                                color = PushTVColors.TextPrimary
                            )
                        }
                    }

                    // 全部覆盖按钮（默认灰底，选中高亮警示色）
                    Button(
                        onClick = onOverwriteAll,
                        scale = ButtonDefaults.scale(focusedScale = 1.04f),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.08f),
                            focusedContainerColor = PushTVColors.Warning
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .focusRequester(overwriteRequester)
                            .focusProperties {
                                left = skipRequester
                                right = cancelRequester
                            }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (totalSelected == 1) "覆盖备份" else "全部覆盖",
                                textAlign = TextAlign.Center,
                                color = PushTVColors.TextPrimary
                            )
                        }
                    }

                    // 取消按钮（默认灰底，选中高亮浅灰）
                    Button(
                        onClick = onDismiss,
                        scale = ButtonDefaults.scale(focusedScale = 1.04f),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.08f),
                            focusedContainerColor = PushTVColors.TextPrimary.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier
                            .weight(0.8f)
                            .height(44.dp)
                            .focusRequester(cancelRequester)
                            .focusProperties {
                                left = overwriteRequester
                            }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "取消",
                                textAlign = TextAlign.Center,
                                color = PushTVColors.TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InstallPromptDialog(
    downloadedFiles: List<File>,
    onInstall: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val installRequester = remember { FocusRequester() }
    val cancelRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        installRequester.requestFocus()
    }

    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            colors = NonInteractiveSurfaceDefaults.colors(containerColor = PushTVColors.Panel),
            modifier = Modifier
                .width(420.dp)
                .padding(16.dp)
                .focusGroup()
                .focusProperties { exit = { FocusRequester.Cancel } }
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "下载完成",
                    style = MaterialTheme.typography.headlineSmall,
                    color = PushTVColors.Success
                )
                Spacer(modifier = Modifier.height(12.dp))
                val desc = if (downloadedFiles.size == 1) {
                    "安装包已下载至“已接收文件”目录，是否立即安装？"
                } else {
                    "已下载 ${downloadedFiles.size} 个应用安装包至“已接收文件”目录，是否立即安装？"
                }
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PushTVColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 立即安装按钮（默认灰底，选中高亮品牌色）
                    Button(
                        onClick = {
                            downloadedFiles.firstOrNull()?.let(onInstall)
                            onDismiss()
                        },
                        scale = ButtonDefaults.scale(focusedScale = 1.04f),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.08f),
                            focusedContainerColor = PushTVColors.Primary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .focusRequester(installRequester)
                            .focusProperties { right = cancelRequester }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "立即安装",
                                textAlign = TextAlign.Center,
                                color = PushTVColors.TextPrimary
                            )
                        }
                    }

                    // 稍后按钮（默认灰底，选中高亮浅灰）
                    Button(
                        onClick = onDismiss,
                        scale = ButtonDefaults.scale(focusedScale = 1.04f),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.08f),
                            focusedContainerColor = PushTVColors.TextPrimary.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .focusRequester(cancelRequester)
                            .focusProperties { left = installRequester }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "稍后",
                                textAlign = TextAlign.Center,
                                color = PushTVColors.TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DeleteCloudConfirmDialog(
    item: CloudApkItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val deleteRequester = remember { FocusRequester() }
    val cancelRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        cancelRequester.requestFocus()
    }

    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            colors = NonInteractiveSurfaceDefaults.colors(containerColor = PushTVColors.Panel),
            modifier = Modifier
                .width(420.dp)
                .padding(16.dp)
                .focusGroup()
                .focusProperties { exit = { FocusRequester.Cancel } }
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "删除云端备份",
                    style = MaterialTheme.typography.headlineSmall,
                    color = PushTVColors.Error
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "确定要从云端网盘删除「${item.appName}」吗？\n文件：${item.fileName}\n此操作不可撤销。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PushTVColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // 删除按钮（默认灰底，选中高亮危险色）
                    Button(
                        onClick = onConfirm,
                        scale = ButtonDefaults.scale(focusedScale = 1.04f),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.08f),
                            focusedContainerColor = PushTVColors.Error
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .focusRequester(deleteRequester)
                            .focusProperties { right = cancelRequester }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "确认删除",
                                textAlign = TextAlign.Center,
                                color = PushTVColors.TextPrimary
                            )
                        }
                    }

                    // 取消按钮（默认灰底，选中高亮浅灰）
                    Button(
                        onClick = onDismiss,
                        scale = ButtonDefaults.scale(focusedScale = 1.04f),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.08f),
                            focusedContainerColor = PushTVColors.TextPrimary.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .focusRequester(cancelRequester)
                            .focusProperties { left = deleteRequester }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "取消",
                                textAlign = TextAlign.Center,
                                color = PushTVColors.TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InsufficientStorageDialog(
    warning: StorageCheckResult,
    onCleanAndRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val cleanRequester = remember { FocusRequester() }
    val dismissRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        if (warning.cacheBytes > 0L) {
            cleanRequester.requestFocus()
        } else {
            dismissRequester.requestFocus()
        }
    }

    BackHandler(onBack = onDismiss)

    Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            colors = NonInteractiveSurfaceDefaults.colors(containerColor = PushTVColors.Panel),
            modifier = Modifier
                .width(480.dp)
                .border(1.dp, PushTVColors.TextPrimary.copy(alpha = 0.1f), RoundedCornerShape(24.dp))
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = PushTVColors.Warning,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "电视存储空间不足",
                    style = MaterialTheme.typography.headlineSmall,
                    color = PushTVColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(14.dp))

                // 空间详情面板
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    colors = NonInteractiveSurfaceDefaults.colors(containerColor = PushTVColors.TextPrimary.copy(alpha = 0.04f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "下载所需空间",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PushTVColors.TextSecondary
                            )
                            Text(
                                formatFileSize(warning.requiredBytes),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = PushTVColors.Warning
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "当前可用空间",
                                style = MaterialTheme.typography.bodyMedium,
                                color = PushTVColors.TextSecondary
                            )
                            Text(
                                formatFileSize(warning.usableBytes),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                color = PushTVColors.TextPrimary
                            )
                        }
                        if (warning.cacheBytes > 0L) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "历史下载缓存",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PushTVColors.TextSecondary
                                )
                                Text(
                                    formatFileSize(warning.cacheBytes),
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = PushTVColors.Primary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                val message = if (warning.cacheBytes > 0L) {
                    "PushTV 检测到历史下载安装包占用了 ${formatFileSize(warning.cacheBytes)} 存储空间。建议先清理旧缓存再重试。"
                } else {
                    "当前电视剩余空间不足以完成本次下载，请先在电视系统设置中清理空间后再试。"
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = PushTVColors.TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (warning.cacheBytes > 0L) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // 清理并重试按钮（默认灰底，选中高亮品牌色）
                        Button(
                            onClick = onCleanAndRetry,
                            scale = ButtonDefaults.scale(focusedScale = 1.04f),
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                            border = ButtonDefaults.border(
                                focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                            ),
                            colors = ButtonDefaults.colors(
                                containerColor = PushTVColors.TextPrimary.copy(alpha = 0.08f),
                                focusedContainerColor = PushTVColors.Primary
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .focusRequester(cleanRequester)
                                .focusProperties { right = dismissRequester }
                        ) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "清理旧缓存并重试",
                                    textAlign = TextAlign.Center,
                                    color = PushTVColors.TextPrimary
                                )
                            }
                        }

                        // 知道了/取消按钮（默认灰底，选中高亮浅灰）
                        Button(
                            onClick = onDismiss,
                            scale = ButtonDefaults.scale(focusedScale = 1.04f),
                            shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                            border = ButtonDefaults.border(
                                focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                            ),
                            colors = ButtonDefaults.colors(
                                containerColor = PushTVColors.TextPrimary.copy(alpha = 0.08f),
                                focusedContainerColor = PushTVColors.TextPrimary.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .focusRequester(dismissRequester)
                                .focusProperties { left = cleanRequester }
                        ) {
                            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = "知道了",
                                    textAlign = TextAlign.Center,
                                    color = PushTVColors.TextPrimary
                                )
                            }
                        }
                    }
                } else {
                    // 仅知道了按钮
                    Button(
                        onClick = onDismiss,
                        scale = ButtonDefaults.scale(focusedScale = 1.04f),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.08f),
                            focusedContainerColor = PushTVColors.TextPrimary.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .focusRequester(dismissRequester)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "知道了",
                                textAlign = TextAlign.Center,
                                color = PushTVColors.TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    val gb = mb / 1024.0
    return when {
        gb >= 1.0 -> String.format("%.2f GB", gb)
        mb >= 1.0 -> String.format("%.1f MB", mb)
        kb >= 1.0 -> String.format("%.1f KB", kb)
        else -> "$bytes B"
    }
}

