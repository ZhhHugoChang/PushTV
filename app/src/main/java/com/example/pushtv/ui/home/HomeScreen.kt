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
import com.example.pushtv.data.BackupManager
import com.example.pushtv.ui.backup.BackupScreen
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
    const val HOME_BACKUP = "home:backup"
    const val HOME_CLEAR = "home:clear"
    const val SOFTWARE_ALL = "software:filter:all"
    const val SOFTWARE_FAVORITES = "software:filter:favorites"
    const val SOFTWARE_HIDE_SYSTEM = "software:hide_system"
    const val SOFTWARE_CHECK = "software:check"

    fun file(path: String) = "home:file:$path"
    fun app(packageName: String) = "software:app:$packageName"

    val fixed = setOf(
        HOME_SOFTWARE,
        HOME_BACKUP,
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
    val transfers by TransferManager.transfers.collectAsState()
    val installedApps by softwareViewModel.installedApps.collectAsState()
    val softwareFilterMode by softwareViewModel.filterMode.collectAsState()

    SideEffect {
        view.keepScreenOn = transfers.any { it.isActive }
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

    LaunchedEffect(Unit) {
        BackupManager.checkWebDavStatus(context)
    }

    BackHandler(enabled = currentScreen != 2 && (focusLayer == FocusLayer.CONTENT || focusLayer == FocusLayer.DRAWER)) {
        if (focusLayer == FocusLayer.DRAWER) {
            closeDrawerAndRestore()
        } else if (currentScreen != 0) {
            currentScreen = 0
            restoreFocusGeneration++
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(PushTVColors.BackgroundTop, PushTVColors.Background)
                )
            )
    ) {
        
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
                    isMobile, ipAddress, qrBitmap, storageInfo, fileList,
                    isFocusActive = focusLayer == FocusLayer.CONTENT,
                    preferredFocusKey = lastHomeFocusKey,
                    onFocusKeyChanged = { lastHomeFocusKey = it },
                    onNavigateToSoftware = { currentScreen = 1 },
                    onNavigateToBackup = { currentScreen = 2 },
                    onItemClick = { apk ->
                        drawerOriginKey = TvFocusKeys.file(apk.file.absolutePath)
                        drawerTitle = apk.name
                        drawerPackageName = apk.packageName
                        drawerActions = listOf(
                            ActionItem("安装", Icons.Default.Download, PushTVColors.Success) { installApk(context, apk.file); closeDrawerAndRestore() },
                            ActionItem("删除", Icons.Default.Delete, PushTVColors.Error) {
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
                                    ActionItem("打开", Icons.Default.PlayArrow, PushTVColors.Primary) { viewModel.openApp(apk.packageName); closeDrawerAndRestore() },
                                    ActionItem("卸载", Icons.Default.DeleteForever, PushTVColors.Error) {
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
                            ActionItem("清空历史", Icons.Default.History, PushTVColors.Primary) {
                                confirmationRequest = ConfirmationRequest(
                                    title = "确认清空历史",
                                    message = "接收记录将从首页隐藏，安装包文件仍保留。确定继续吗？",
                                    onConfirm = viewModel::clearRecordsOnly
                                )
                                drawerActions = null
                                drawerPackageName = null
                                focusLayer = FocusLayer.CONFIRM_DIALOG
                            },
                            ActionItem("清除安装包和历史", Icons.Default.DeleteSweep, PushTVColors.Error) {
                                confirmationRequest = ConfirmationRequest(
                                    title = "确认全部清除",
                                    message = "确定要删除全部安装包和接收历史吗？此操作不可撤销。",
                                    onConfirm = {
                                        softwareViewModel.cancelAllDownloads()
                                        viewModel.clearAllFiles()
                                    }
                                )
                                drawerActions = null
                                drawerPackageName = null
                                focusLayer = FocusLayer.CONFIRM_DIALOG
                            }
                        )
                        focusLayer = FocusLayer.DRAWER
                    },
                    focusRequesterMap = focusRequesterMap
                )
            } else if (currentScreen == 1) {
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
                            add(ActionItem(if (app.isFavorite) "取消收藏" else "收藏软件", if (app.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, PushTVColors.Favorite) {
                                softwareViewModel.toggleFavorite(app); closeDrawerAndRestore()
                            })
                            add(ActionItem("设置地址", Icons.Default.Link, PushTVColors.Primary) {
                                editingUrlApp = app
                                drawerActions = null
                                drawerPackageName = null
                                focusLayer = FocusLayer.URL_DIALOG
                            })
                            add(ActionItem("打开", Icons.Default.PlayArrow, PushTVColors.Primary) { viewModel.openApp(app.packageName); closeDrawerAndRestore() })
                            if (!app.isSystemApp) {
                                add(ActionItem("卸载", Icons.Default.DeleteForever, PushTVColors.Error) {
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
            } else {
                BackupScreen(
                    onNavigateBack = {
                        currentScreen = 0
                        lastHomeFocusKey = TvFocusKeys.HOME_BACKUP
                        restoreFocusGeneration++
                    }
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
