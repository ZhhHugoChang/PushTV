@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.pushtv.ui.home

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.pushtv.data.TransferProgress
import com.example.pushtv.ui.software.FilterMode
import com.example.pushtv.ui.software.InstalledApp
import com.example.pushtv.ui.software.SoftwareViewModel
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

data class ActionItem(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val onClick: () -> Unit
)

private data class ConfirmationRequest(
    val title: String,
    val message: String,
    val onConfirm: () -> Unit
)

internal object TvFocusKeys {
    const val HOME_SOFTWARE = "home:software"
    const val HOME_CLEAR = "home:clear"
    const val SOFTWARE_ALL = "software:filter:all"
    const val SOFTWARE_FAVORITES = "software:filter:favorites"
    const val SOFTWARE_HIDE_SYSTEM = "software:hide_system"
    const val SOFTWARE_CHECK = "software:check"

    fun file(path: String) = "home:file:$path"
    fun app(packageName: String) = "software:app:$packageName"

    val fixed = setOf(
        HOME_SOFTWARE,
        HOME_CLEAR,
        SOFTWARE_ALL,
        SOFTWARE_FAVORITES,
        SOFTWARE_HIDE_SYSTEM,
        SOFTWARE_CHECK
    )
}

private enum class FocusLayer {
    CONTENT,
    DRAWER,
    CONFIRM_DIALOG,
    URL_DIALOG
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel(), softwareViewModel: SoftwareViewModel = viewModel()) {
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val isMobile = configuration.screenWidthDp < 600
    val fileList by viewModel.fileList.collectAsState()
    val ipAddress by viewModel.ipAddress.collectAsState()
    val qrBitmap by viewModel.qrBitmap.collectAsState()
    val storageInfo by viewModel.storageInfo.collectAsState()
    val activeTransfers by viewModel.activeTransfers.collectAsState()
    val installedApps by softwareViewModel.installedApps.collectAsState()
    val softwareFilterMode by softwareViewModel.filterMode.collectAsState()

    SideEffect {
        view.keepScreenOn = activeTransfers.isNotEmpty()
    }
    DisposableEffect(view) {
        onDispose {
            view.keepScreenOn = false
        }
    }
    
    var confirmationRequest by remember { mutableStateOf<ConfirmationRequest?>(null) }
    var editingUrlApp by remember { mutableStateOf<InstalledApp?>(null) }
    
    var currentScreen by remember { mutableIntStateOf(0) }
    var drawerActions by remember { mutableStateOf<List<ActionItem>?>(null) }
    var drawerTitle by remember { mutableStateOf("") }
    var drawerPackageName by remember { mutableStateOf<String?>(null) }

    val focusRequesterMap = remember { mutableStateMapOf<String, FocusRequester>() }
    var lastHomeFocusKey by remember { mutableStateOf<String?>(null) }
    var lastSoftwareFocusKey by remember { mutableStateOf<String?>(null) }
    var drawerOriginKey by remember { mutableStateOf<String?>(null) }
    val drawerFocusRequester = remember { FocusRequester() }
    var focusLayer by remember { mutableStateOf(FocusLayer.CONTENT) }
    var restoreFocusGeneration by remember { mutableIntStateOf(0) }

    var lastBackTime by remember { mutableLongStateOf(0L) }
    
    val isDrawerOpen = focusLayer == FocusLayer.DRAWER && drawerActions != null

    LaunchedEffect(fileList, installedApps) {
        val validKeys = TvFocusKeys.fixed +
            fileList.map { TvFocusKeys.file(it.file.absolutePath) } +
            installedApps.map { TvFocusKeys.app(it.packageName) }
        focusRequesterMap.keys.toList().filterNot(validKeys::contains).forEach(focusRequesterMap::remove)
    }

    LaunchedEffect(focusLayer, restoreFocusGeneration) {
        suspend fun requestWhenAttached(requester: FocusRequester?): Boolean {
            repeat(2) {
                withFrameNanos { }
                if (requester != null && runCatching { requester.requestFocus(); true }.getOrDefault(false)) {
                    return true
                }
            }
            return false
        }

        when (focusLayer) {
            FocusLayer.DRAWER -> requestWhenAttached(drawerFocusRequester)
            FocusLayer.CONTENT -> if (restoreFocusGeneration > 0) {
                val rememberedKey = drawerOriginKey ?: if (currentScreen == 0) lastHomeFocusKey else lastSoftwareFocusKey
                val fallbackKey = if (currentScreen == 0) {
                    TvFocusKeys.HOME_SOFTWARE
                } else if (softwareFilterMode == FilterMode.FAVORITES) {
                    TvFocusKeys.SOFTWARE_FAVORITES
                } else {
                    TvFocusKeys.SOFTWARE_ALL
                }
                if (!requestWhenAttached(rememberedKey?.let(focusRequesterMap::get))) {
                    requestWhenAttached(focusRequesterMap[fallbackKey])
                }
                drawerOriginKey = null
            }
            FocusLayer.CONFIRM_DIALOG, FocusLayer.URL_DIALOG -> Unit
        }
    }

    val closeDrawerAndRestore = {
        drawerActions = null
        drawerPackageName = null
        focusLayer = FocusLayer.CONTENT
        restoreFocusGeneration++
    }

    BackHandler(enabled = focusLayer == FocusLayer.CONTENT || focusLayer == FocusLayer.DRAWER) {
        if (focusLayer == FocusLayer.DRAWER) {
            closeDrawerAndRestore()
        } else if (currentScreen != 0) {
            currentScreen = 0
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackTime < 2000) {
                (context as? android.app.Activity)?.finish()
            } else {
                lastBackTime = currentTime
                android.widget.Toast.makeText(context, "再按一次退出程序", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    var targetBgColor by remember { mutableStateOf(Color(0xFF0F172A)) }
    val animatedBgColor by animateColorAsState(targetBgColor, animationSpec = tween(250))

    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(colors = listOf(animatedBgColor, Color(0xFF020617))))) {
        
        // --- 主内容区域焦点隔离：抽屉打开时阻断从外部 DPAD 进入主内容，但允许 requestFocus() 将焦点移走 ---
        Box(modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { focusLayer != FocusLayer.CONTENT }
            .focusProperties {
                canFocus = focusLayer == FocusLayer.CONTENT
                enter = { if (focusLayer == FocusLayer.CONTENT) FocusRequester.Default else FocusRequester.Cancel }
            }
        ) {
            if (currentScreen == 0) {
                HomeContent(
                    isMobile, ipAddress, qrBitmap, storageInfo, fileList, activeTransfers,
                    isFocusActive = focusLayer == FocusLayer.CONTENT,
                    preferredFocusKey = lastHomeFocusKey,
                    onFocusKeyChanged = { lastHomeFocusKey = it },
                    onNavigateToSoftware = { currentScreen = 1 },
                    onItemClick = { apk ->
                        drawerOriginKey = TvFocusKeys.file(apk.file.absolutePath)
                        drawerTitle = apk.name
                        drawerPackageName = apk.packageName
                        drawerActions = listOf(
                            ActionItem("安装", Icons.Default.Download, Color(0xFF10B981)) { installApk(context, apk.file); closeDrawerAndRestore() },
                            ActionItem("删除", Icons.Default.Delete, Color(0xFFEF4444)) {
                                confirmationRequest = ConfirmationRequest(
                                    title = "确认删除",
                                    message = "确定要删除 ${apk.name} 吗？",
                                    onConfirm = { viewModel.deleteFile(apk.file) }
                                )
                                drawerActions = null
                                drawerPackageName = null
                                focusLayer = FocusLayer.CONFIRM_DIALOG
                            }
                        ).let { baseActions ->
                            if (apk.isInstalled) {
                                baseActions + listOf(
                                    ActionItem("打开", Icons.Default.PlayArrow, Color(0xFF38BDF8)) { viewModel.openApp(apk.packageName); closeDrawerAndRestore() },
                                    ActionItem("卸载", Icons.Default.DeleteForever, Color(0xFFEF4444)) {
                                        confirmationRequest = ConfirmationRequest(
                                            title = "确认卸载",
                                            message = "确定要卸载 ${apk.name} 吗？",
                                            onConfirm = { viewModel.uninstallApp(apk.packageName) }
                                        )
                                        drawerActions = null
                                        drawerPackageName = null
                                        focusLayer = FocusLayer.CONFIRM_DIALOG
                                    }
                                )
                            } else baseActions
                        }
                        focusLayer = FocusLayer.DRAWER
                    },
                    onClearAll = {
                        drawerOriginKey = TvFocusKeys.HOME_CLEAR
                        drawerTitle = "文件清理"
                        drawerActions = listOf(
                            ActionItem("清空历史", Icons.Default.History, Color(0xFF38BDF8)) {
                                confirmationRequest = ConfirmationRequest(
                                    title = "确认清空历史",
                                    message = "确定要清空全部已接收文件记录吗？",
                                    onConfirm = viewModel::clearRecordsOnly
                                )
                                drawerActions = null
                                drawerPackageName = null
                                focusLayer = FocusLayer.CONFIRM_DIALOG
                            },
                            ActionItem("清除安装包", Icons.Default.DeleteSweep, Color(0xFFEF4444)) {
                                confirmationRequest = ConfirmationRequest(
                                    title = "确认清除安装包",
                                    message = "确定要删除全部已接收的安装包吗？此操作不可撤销。",
                                    onConfirm = viewModel::clearAllFiles
                                )
                                drawerActions = null
                                drawerPackageName = null
                                focusLayer = FocusLayer.CONFIRM_DIALOG
                            }
                        )
                        focusLayer = FocusLayer.DRAWER
                    },
                    onItemFocused = { targetBgColor = if (it.isInstalled) Color(0xFF064E3B) else Color(0xFF0F172A) },
                    focusRequesterMap = focusRequesterMap
                )
            } else {
                SoftwareListScreen(
                    isFocusActive = focusLayer == FocusLayer.CONTENT,
                    preferredFocusKey = lastSoftwareFocusKey,
                    onFocusKeyChanged = { lastSoftwareFocusKey = it },
                    onItemClick = { app ->
                        drawerOriginKey = TvFocusKeys.app(app.packageName)
                        softwareViewModel.checkUpdateStatus(app)
                        drawerTitle = app.name
                        drawerPackageName = app.packageName
                        drawerActions = mutableListOf<ActionItem>().apply {
                            add(ActionItem(if (app.isFavorite) "取消收藏" else "收藏软件", if (app.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, Color(0xFFF472B6)) { 
                                softwareViewModel.toggleFavorite(app); closeDrawerAndRestore()
                            })
                            add(ActionItem("设置地址", Icons.Default.Link, Color(0xFF38BDF8)) {
                                editingUrlApp = app
                                drawerActions = null
                                drawerPackageName = null
                                focusLayer = FocusLayer.URL_DIALOG
                            })
                            add(ActionItem("打开", Icons.Default.PlayArrow, Color(0xFF38BDF8)) { viewModel.openApp(app.packageName); closeDrawerAndRestore() })
                            if (!app.isSystemApp) {
                                add(ActionItem("卸载", Icons.Default.DeleteForever, Color(0xFFEF4444)) {
                                    confirmationRequest = ConfirmationRequest(
                                        title = "确认卸载",
                                        message = "确定要卸载 ${app.name} 吗？",
                                        onConfirm = { viewModel.uninstallApp(app.packageName) }
                                    )
                                    drawerActions = null
                                    drawerPackageName = null
                                    focusLayer = FocusLayer.CONFIRM_DIALOG
                                })
                            }
                        }.toList()
                        focusLayer = FocusLayer.DRAWER
                    },
                    focusRequesterMap = focusRequesterMap
                )
            }
        }

        // 抽屉覆盖层：不再用 focusable() 做焦点拦截，由主内容的 focusProperties 负责隔离
        AnimatedVisibility(
            visible = isDrawerOpen,
            enter = slideInHorizontally(initialOffsetX = { it }),
            exit = slideOutHorizontally(targetOffsetX = { it }),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            ActionDrawer(
                title = drawerTitle,
                actions = drawerActions ?: emptyList(),
                packageName = drawerPackageName,
                softwareViewModel = softwareViewModel,
                drawerFocusRequester = drawerFocusRequester
            )
        }
    }

    confirmationRequest?.let { request ->
        ConfirmDialog(request.title, request.message,
            onConfirm = {
                confirmationRequest = null
                request.onConfirm()
                focusLayer = FocusLayer.CONTENT
                restoreFocusGeneration++
            },
            onDismiss = {
                confirmationRequest = null
                focusLayer = FocusLayer.CONTENT
                restoreFocusGeneration++
            })
    }

    editingUrlApp?.let { app ->
        UrlEditDialog(
            app = app,
            onDismiss = {
                editingUrlApp = null
                focusLayer = FocusLayer.CONTENT
                restoreFocusGeneration++
            },
            onSave = { url ->
                softwareViewModel.saveUrl(app.packageName, url)
                editingUrlApp = null
                focusLayer = FocusLayer.CONTENT
                restoreFocusGeneration++
            }
        )
    }
}


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
            containerColor = if (isSelected) Color(0xFF38BDF8) else Color.Transparent,
            focusedContainerColor = if (isSelected) Color(0xFF38BDF8) else Color.White.copy(alpha = 0.1f)
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
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f)
            )
            if (count > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .background(if (isSelected) Color.White.copy(alpha = 0.2f) else Color(0xFFEF4444), RoundedCornerShape(10.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                ) {
                    Text(count.toString(), fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

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
    
    val recommendedAsset = currentApp?.assets?.find { it.isRecommended }
    val primaryAsset = recommendedAsset ?: currentApp?.assets?.firstOrNull()
    val otherAssets = currentApp?.assets?.filter { it != primaryAsset } ?: emptyList()
    val hasChangelog = currentApp?.changelog?.isNotBlank() == true
    val actionRequesters = remember(actions.size, drawerFocusRequester) {
        List(actions.size) { index -> if (index == 0) drawerFocusRequester else FocusRequester() }
    }
    val downloadRequester = remember { FocusRequester() }
    val otherAssetRequesters = remember(otherAssets.map { it.downloadUrl }) {
        List(otherAssets.size) { FocusRequester() }
    }
    val leftToChangelog = if (hasChangelog) changelogFocusRequester else FocusRequester.Cancel
    var isChangelogFocused by remember { mutableStateOf(false) }

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
                colors = NonInteractiveSurfaceDefaults.colors(containerColor = Color(0xFF0F172A)),
                modifier = Modifier
                    .fillMaxHeight()
                    .width(420.dp)
                    .padding(end = 1.dp)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Description, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("更新日志", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isChangelogFocused) Color.White.copy(alpha = 0.05f)
                                else Color.White.copy(alpha = 0.03f)
                            )
                            .then(
                                if (isChangelogFocused) {
                                    Modifier.border(2.dp, Color(0xFF38BDF8), RoundedCornerShape(12.dp))
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
                                color = Color(0xFFE2E8F0),
                                lineHeight = 18.sp
                            )
                        }
                    }
                }
            }
        }

        // Right Side: Original Actions
        Surface(
            colors = NonInteractiveSurfaceDefaults.colors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier
                .fillMaxHeight()
                .width(340.dp)
        ) {
            Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(Color.White.copy(alpha = 0.1f)))
            
            Column(modifier = Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White, maxLines = 1)
                
                currentApp?.let { app ->
                    if (app.updateUrl.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.05f)).padding(12.dp)) {
                            Column {
                                Text("当前版本: ${app.versionName}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8))
                                val hasUpdate = softwareViewModel?.isVersionDifferent(app.versionName, app.remoteVersion) == true
                                if (hasUpdate) {
                                    Text("最新版本: ${app.remoteVersion}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF10B981))
                                    Text("发现新版本！", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = Color(0xFF10B981), modifier = Modifier.padding(top = 4.dp))
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
                                    ) Color(0xFFEF4444) else Color(0xFF94A3B8)
                                    Text(status, style = MaterialTheme.typography.bodySmall, color = color)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (primaryAsset != null) {
                        Text(
                            if (primaryAsset.isRecommended) "推荐升级:" else "可用更新:",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF38BDF8)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            onClick = { softwareViewModel?.downloadAndInstall(currentApp!!, primaryAsset.downloadUrl) },
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (primaryAsset.isRecommended) Color(0xFF10B981).copy(alpha = 0.15f) else Color.White.copy(alpha = 0.08f),
                                focusedContainerColor = Color(0xFF10B981)
                            ),
                            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            border = ClickableSurfaceDefaults.border(
                                focusedBorder = Border(BorderStroke(2.dp, Color.White), inset = (-1).dp)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(downloadRequester)
                                .focusProperties {
                                    left = leftToChangelog
                                    up = FocusRequester.Cancel
                                    down = actionRequesters.firstOrNull() ?: otherAssetRequesters.firstOrNull() ?: FocusRequester.Cancel
                                }
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = Color.White)
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("下载并安装新版本", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(primaryAsset.name, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.6f), maxLines = 2)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text("常规操作:", style = MaterialTheme.typography.labelMedium, color = Color(0xFF94A3B8))

                    actions.forEachIndexed { index, action ->
                        val requester = actionRequesters[index]
                        val previousRequester = actionRequesters.getOrNull(index - 1) ?: if (primaryAsset != null) downloadRequester else null
                        val nextRequester = actionRequesters.getOrNull(index + 1) ?: otherAssetRequesters.firstOrNull()
                        Surface(
                            onClick = action.onClick,
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.05f),
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
                                Icon(action.icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(action.label, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                            }
                        }
                    }

                    if (otherAssets.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("其他可用版本:", style = MaterialTheme.typography.labelMedium, color = Color(0xFF94A3B8))
                        otherAssets.forEachIndexed { index, asset ->
                            val requester = otherAssetRequesters[index]
                            val previousRequester = otherAssetRequesters.getOrNull(index - 1) ?: actionRequesters.lastOrNull() ?: if (primaryAsset != null) downloadRequester else null
                            val nextRequester = otherAssetRequesters.getOrNull(index + 1)
                            Surface(
                                onClick = { softwareViewModel?.downloadAndInstall(currentApp!!, asset.downloadUrl) },
                                colors = ClickableSurfaceDefaults.colors(focusedContainerColor = Color.White.copy(alpha = 0.15f)),
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
                                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF94A3B8))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(asset.name, style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8), maxLines = 1)
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
fun HomeContent(
    isMobile: Boolean,
    ipAddress: String,
    qrBitmap: androidx.compose.ui.graphics.ImageBitmap?,
    storageInfo: String,
    fileList: List<ApkInfo>,
    activeTransfers: List<TransferProgress>,
    isFocusActive: Boolean,
    preferredFocusKey: String?,
    onFocusKeyChanged: (String) -> Unit,
    onNavigateToSoftware: () -> Unit,
    onItemClick: (ApkInfo) -> Unit,
    onClearAll: () -> Unit,
    onItemFocused: (ApkInfo) -> Unit,
    focusRequesterMap: SnapshotStateMap<String, FocusRequester>
) {
    val softwareRequester = focusRequesterMap.getOrPut(TvFocusKeys.HOME_SOFTWARE) { FocusRequester() }
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
            Text("PushTV", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold), color = Color(0xFF38BDF8))
            Spacer(modifier = Modifier.height(16.dp))
            
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.05f)).padding(16.dp)) {
                Column {
                    Text("浏览器访问地址:", style = MaterialTheme.typography.labelMedium, color = Color(0xFF94A3B8))
                    Text(ipAddress, style = MaterialTheme.typography.headlineSmall, color = Color(0xFF10B981))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(storageInfo, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
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
                            right = if (fileList.isNotEmpty()) clearRequester else FocusRequester.Cancel
                            down = firstFileRequester ?: FocusRequester.Cancel
                        }
                        .onFocusChanged { if (it.isFocused) onFocusKeyChanged(TvFocusKeys.HOME_SOFTWARE) },
                    shape = RoundedCornerShape(12.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF38BDF8))
                ) {
                    Icon(Icons.Default.Apps, contentDescription = null, tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("软件列表", color = Color.White)
                }
                
                if (fileList.isNotEmpty()) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = onClearAll,
                        modifier = Modifier
                            .weight(0.5f)
                            .focusRequester(clearRequester)
                            .focusProperties {
                                left = softwareRequester
                                down = firstFileRequester ?: FocusRequester.Cancel
                            }
                            .onFocusChanged { if (it.isFocused) onFocusKeyChanged(TvFocusKeys.HOME_CLEAR) },
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFEF4444))
                    ) {
                        Text("清空", color = Color(0xFFEF4444))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("已接收文件 (${fileList.size})", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(modifier = Modifier.height(12.dp))
            
            activeTransfers.forEach { TransferringItem(it) }
             
            if (fileList.isEmpty() && activeTransfers.isEmpty()) {
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
                                    onItemFocused(apkInfo)
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
                activeTransfers = activeTransfers,
                isFocusActive = isFocusActive,
                preferredFocusKey = preferredFocusKey,
                onItemClick = onItemClick,
                onClearAll = onClearAll,
                onItemFocused = onItemFocused,
                onNavigateToSoftware = onNavigateToSoftware,
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
    Box(modifier = modifier.clip(RoundedCornerShape(24.dp)).background(Color.White.copy(alpha = 0.03f)).border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(24.dp)).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PushTV", style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 32.sp), color = Color(0xFF38BDF8))
            Spacer(modifier = Modifier.height(8.dp))
            Text("扫码或浏览器访问，上传 APK", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF94A3B8), textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(24.dp))
            Box(modifier = Modifier.size(160.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.05f)).padding(12.dp), contentAlignment = Alignment.Center) {
                if (qrBitmap != null) Image(bitmap = qrBitmap, contentDescription = "QR Code", modifier = Modifier.fillMaxSize())
                else Text("请连接 Wi-Fi", color = Color(0xFF94A3B8))
            }
            Spacer(modifier = Modifier.height(24.dp))
            Surface(colors = NonInteractiveSurfaceDefaults.colors(containerColor = Color(0xFF1E293B)), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(0.9f)) {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (ipAddress == "未知IP") "未连接到局域网" else ipAddress, 
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), 
                        color = Color(0xFF10B981), 
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = storageInfo, style = MaterialTheme.typography.bodySmall, color = Color(0xFF64748B))
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FileListSection(
    fileList: List<ApkInfo>,
    activeTransfers: List<TransferProgress>,
    isFocusActive: Boolean,
    preferredFocusKey: String?,
    onItemClick: (ApkInfo) -> Unit,
    onClearAll: () -> Unit,
    onItemFocused: (ApkInfo) -> Unit,
    onNavigateToSoftware: () -> Unit,
    onFocusKeyChanged: (String) -> Unit,
    focusRequesterMap: SnapshotStateMap<String, FocusRequester>,
    modifier: Modifier
) {
    val softwareRequester = focusRequesterMap.getOrPut(TvFocusKeys.HOME_SOFTWARE) { FocusRequester() }
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
                runCatching { listState.scrollToItem(activeTransfers.size) }
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
            val preferredRequester = preferredFocusKey?.takeIf { it in fileKeys || it == TvFocusKeys.HOME_SOFTWARE || it == TvFocusKeys.HOME_CLEAR }
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
            val firstFileFocused = firstFileRequester?.let {
                runCatching { it.requestFocus(); true }.getOrDefault(false)
            } == true
            if (!firstFileFocused) softwareRequester.requestFocus()
        }
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween, 
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("已接收文件 (${fileList.size})", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold), color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 软件列表按钮
                Button(
                    onClick = onNavigateToSoftware,
                    scale = ButtonDefaults.scale(focusedScale = 1f),
                    modifier = Modifier
                        .focusRequester(softwareRequester)
                        .then(enterFilesOnDown)
                        .focusProperties {
                            right = if (fileList.isNotEmpty()) clearRequester else FocusRequester.Cancel
                        }
                        .onFocusChanged { if (it.isFocused) onFocusKeyChanged(TvFocusKeys.HOME_SOFTWARE) },
                    colors = ButtonDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.05f), 
                        focusedContainerColor = Color(0xFF38BDF8),
                        contentColor = Color.White,
                        focusedContentColor = Color.White
                    ),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(BorderStroke(2.dp, Color.White), inset = (-1).dp)
                    ),
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Apps, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("软件列表", color = Color.White)
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
                                left = softwareRequester
                            }
                            .onFocusChanged { if (it.isFocused) onFocusKeyChanged(TvFocusKeys.HOME_CLEAR) },
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.05f), 
                            focusedContainerColor = Color(0xFFEF4444),
                            contentColor = Color.White,
                            focusedContentColor = Color.White
                        ),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, Color.White), inset = (-1).dp)
                        ),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("清空", color = Color.White)
                        }
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
            items(activeTransfers, key = { "transfer:${it.id}" }) { transfer -> TransferringItem(transfer) }
            if (fileList.isEmpty() && activeTransfers.isEmpty()) item { EmptyStatePlaceholder() }
            else items(fileList, key = { it.file.absolutePath + it.file.lastModified() }) { apkInfo ->
                val index = fileList.indexOfFirst { it.file.absolutePath == apkInfo.file.absolutePath }
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
                                onItemFocused(apkInfo)
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
fun TransferringItem(transfer: TransferProgress) {
    Surface(colors = NonInteractiveSurfaceDefaults.colors(containerColor = Color(0xFF38BDF8).copy(alpha = 0.1f)), shape = RoundedCornerShape(16.dp), border = Border(BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.5f))), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                val label = when {
                    transfer.errorMessage != null -> "传输失败: ${transfer.fileName}"
                    transfer.isComplete -> "传输完成: ${transfer.fileName}"
                    else -> "正在传输: ${transfer.fileName}"
                }
                Text(label, style = MaterialTheme.typography.bodyLarge, color = Color.White, maxLines = 1)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    transfer.errorMessage ?: "${transfer.progress}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (transfer.errorMessage == null) Color(0xFF38BDF8) else Color(0xFFEF4444),
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(2.dp))) {
                Box(modifier = Modifier.fillMaxWidth(transfer.progress / 100f).fillMaxHeight().background(Color(0xFF38BDF8), RoundedCornerShape(2.dp)))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun EmptyStatePlaceholder() {
    Box(modifier = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(24.dp)).border(2.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Inbox, contentDescription = null, modifier = Modifier.size(48.dp), tint = Color(0xFF1E293B))
            Spacer(modifier = Modifier.height(12.dp))
            Text("等待接收中...", color = Color(0xFF64748B), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FileItem(apkInfo: ApkInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick, 
        colors = ClickableSurfaceDefaults.colors(containerColor = Color(0xFF1E293B), focusedContainerColor = Color(0xFF2A3A50)),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)), 
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), inset = (-1).dp)
        ), 
        modifier = modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = apkInfo.icon,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(apkInfo.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1)
                        if (apkInfo.isInstalled) {
                            Spacer(modifier = Modifier.width(8.dp))
                            StatusTag(if (apkInfo.canUpdate) "有更新" else "已安装", if (apkInfo.canUpdate) Color(0xFFF59E0B) else Color(0xFF10B981))
                        }
                    }
                    Text("${apkInfo.versionName} • ${apkInfo.sizeMb}", style = MaterialTheme.typography.bodySmall, color = if (isFocused) Color.White.copy(alpha = 0.8f) else Color(0xFF94A3B8))
                }
                if (!isFocused) Text("管理", style = MaterialTheme.typography.bodySmall, color = Color(0xFF94A3B8).copy(alpha = 0.6f))
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ConfirmDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val confirmRequester = remember { FocusRequester() }
    val cancelRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        cancelRequester.requestFocus()
    }

    BackHandler(onBack = onDismiss)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            colors = NonInteractiveSurfaceDefaults.colors(containerColor = Color(0xFF1E293B)),
            modifier = Modifier
                .width(360.dp)
                .focusGroup()
                .focusProperties { exit = { FocusRequester.Cancel } }
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(modifier = Modifier.height(12.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF94A3B8), textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onConfirm,
                        scale = ButtonDefaults.scale(focusedScale = 1f),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(confirmRequester)
                            .focusProperties {
                                left = FocusRequester.Cancel
                                right = cancelRequester
                                up = FocusRequester.Cancel
                                down = FocusRequester.Cancel
                            },
                        colors = ButtonDefaults.colors(containerColor = Color(0xFFEF4444))
                    ) { Text("确定") }
                    Button(
                        onClick = onDismiss,
                        scale = ButtonDefaults.scale(focusedScale = 1f),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(cancelRequester)
                            .focusProperties {
                                left = confirmRequester
                                right = FocusRequester.Cancel
                                up = FocusRequester.Cancel
                                down = FocusRequester.Cancel
                            }
                    ) { Text("取消") }
                }
            }
        }
    }
}

fun installApk(context: Context, apkFile: File) {
    try {
        val intent = Intent(Intent.ACTION_VIEW)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apkFile)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    } catch (e: Exception) { e.printStackTrace() }
}
