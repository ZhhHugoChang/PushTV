@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.pushtv.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.*
import coil.compose.AsyncImage
import com.example.pushtv.ui.software.FilterMode
import com.example.pushtv.ui.software.InstalledApp
import com.example.pushtv.ui.software.SoftwareViewModel
import com.example.pushtv.ui.theme.PushTVColors
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SoftwareListScreen(
    viewModel: SoftwareViewModel = viewModel(),
    isFocusActive: Boolean,
    preferredFocusKey: String?,
    onFocusKeyChanged: (String) -> Unit,
    onItemClick: (InstalledApp) -> Unit,
    focusRequesterMap: SnapshotStateMap<String, FocusRequester>
) {
    val apps by viewModel.filteredApps.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val filterMode by viewModel.filterMode.collectAsStateWithLifecycle()
    val hideSystemApps by viewModel.hideSystemApps.collectAsStateWithLifecycle()
    val favoriteUpdateCount by viewModel.favoriteUpdateCount.collectAsStateWithLifecycle()
    val isLoadingApps by viewModel.isLoadingApps.collectAsStateWithLifecycle()
    val hasGitHubToken by viewModel.hasGitHubToken.collectAsStateWithLifecycle()
    var showGitHubTokenDialog by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.ensureAppsLoaded()
    }

    val allCount = remember(installedApps, hideSystemApps) {
        if (hideSystemApps) installedApps.count { !it.isSystemApp } else installedApps.size
    }
    val favoritesCount = remember(installedApps, hideSystemApps) {
        if (hideSystemApps) installedApps.count { it.isFavorite && !it.isSystemApp } else installedApps.count { it.isFavorite }
    }
    val allFilterRequester = focusRequesterMap.getOrPut(TvFocusKeys.SOFTWARE_ALL) { FocusRequester() }
    val favoritesFilterRequester = focusRequesterMap.getOrPut(TvFocusKeys.SOFTWARE_FAVORITES) { FocusRequester() }
    val hideSystemRequester = focusRequesterMap.getOrPut(TvFocusKeys.SOFTWARE_HIDE_SYSTEM) { FocusRequester() }
    val githubTokenRequester = focusRequesterMap.getOrPut(TvFocusKeys.SOFTWARE_GITHUB_TOKEN) { FocusRequester() }
    val checkRequester = focusRequesterMap.getOrPut(TvFocusKeys.SOFTWARE_CHECK) { FocusRequester() }
    val appKeys = apps.map { TvFocusKeys.app(it.packageName) }
    appKeys.forEach { key -> focusRequesterMap.getOrPut(key) { FocusRequester() } }
    LaunchedEffect(appKeys) {
        focusRequesterMap.keys.toList()
            .filter { it.startsWith("software:app:") && it !in appKeys }
            .forEach(focusRequesterMap::remove)
    }
    val firstAppRequester = appKeys.firstOrNull()?.let(focusRequesterMap::get)
    val selectedFilterRequester = if (filterMode == FilterMode.FAVORITES) favoritesFilterRequester else allFilterRequester
    val gridState = rememberLazyGridState()
    val focusScope = rememberCoroutineScope()
    var lastFocusIndex by remember { mutableIntStateOf(-1) }
    var initialFocusPlaced by remember { mutableStateOf(false) }

    val enterCurrentAppList: () -> Boolean = {
        val requester = firstAppRequester
        if (requester == null) {
            false
        } else {
            focusScope.launch {
                runCatching { gridState.scrollToItem(0) }
                repeat(2) {
                    withFrameNanos { }
                    if (runCatching { requester.requestFocus(); true }.getOrDefault(false)) return@launch
                }
            }
            true
        }
    }
    val enterListOnDown = Modifier.onPreviewKeyEvent { event ->
        event.type == KeyEventType.KeyDown &&
            event.key == Key.DirectionDown &&
            enterCurrentAppList()
    }

    LaunchedEffect(isFocusActive) {
        if (isFocusActive && !initialFocusPlaced) {
            withFrameNanos { }
            val preferredRequester = preferredFocusKey?.let(focusRequesterMap::get)
            val focused = preferredRequester?.let { runCatching { it.requestFocus(); true }.getOrDefault(false) } == true
            if (!focused) {
                val firstAppFocused = firstAppRequester?.let {
                    runCatching { it.requestFocus(); true }.getOrDefault(false)
                } == true
                if (!firstAppFocused) selectedFilterRequester.requestFocus()
            }
            initialFocusPlaced = true
        }
    }

    LaunchedEffect(appKeys, isFocusActive) {
        if (isFocusActive && preferredFocusKey?.startsWith("software:app:") == true && preferredFocusKey !in appKeys) {
            // 说明之前选中的应用被卸载了
            withFrameNanos { }
            // 尝试聚焦相同索引位置的新应用，如果超出范围则选最后一个
            val targetIndex = if (lastFocusIndex >= appKeys.size) appKeys.size - 1 else lastFocusIndex
            val nextBestKey = appKeys.getOrNull(targetIndex)
            
            val focused = nextBestKey?.let { focusRequesterMap[it] }?.let {
                runCatching { it.requestFocus(); true }.getOrDefault(false)
            } == true
            
            if (!focused) {
                val firstAppFocused = firstAppRequester?.let {
                    runCatching { it.requestFocus(); true }.getOrDefault(false)
                } == true
                if (!firstAppFocused) selectedFilterRequester.requestFocus()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 32.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 左侧：全部 / 收藏 双 Tab（样式完全对齐备份页本机/云端）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 全部 Tab
                Button(
                    onClick = { viewModel.setFilterMode(FilterMode.ALL) },
                    scale = ButtonDefaults.scale(focusedScale = 1f),
                    colors = ButtonDefaults.colors(
                        containerColor = if (filterMode == FilterMode.ALL) PushTVColors.Primary.copy(alpha = 0.2f) else PushTVColors.TextPrimary.copy(alpha = 0.05f),
                        focusedContainerColor = PushTVColors.Primary
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                    border = ButtonDefaults.border(
                        border = if (filterMode == FilterMode.ALL) Border(BorderStroke(1.5.dp, PushTVColors.Primary)) else Border.None,
                        focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                    ),
                    modifier = Modifier
                        .focusRequester(allFilterRequester)
                        .then(enterListOnDown)
                        .onFocusChanged { if (it.isFocused) onFocusKeyChanged(TvFocusKeys.SOFTWARE_ALL) }
                        .focusProperties {
                            left = FocusRequester.Cancel
                            right = favoritesFilterRequester
                        }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Apps, contentDescription = null, tint = PushTVColors.TextPrimary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "全部 ($allCount)",
                            color = PushTVColors.TextPrimary,
                            fontWeight = if (filterMode == FilterMode.ALL) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                // 收藏 Tab
                Button(
                    onClick = { viewModel.setFilterMode(FilterMode.FAVORITES) },
                    scale = ButtonDefaults.scale(focusedScale = 1f),
                    colors = ButtonDefaults.colors(
                        containerColor = if (filterMode == FilterMode.FAVORITES) PushTVColors.Primary.copy(alpha = 0.2f) else PushTVColors.TextPrimary.copy(alpha = 0.05f),
                        focusedContainerColor = PushTVColors.Primary
                    ),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                    border = ButtonDefaults.border(
                        border = if (filterMode == FilterMode.FAVORITES) Border(BorderStroke(1.5.dp, PushTVColors.Primary)) else Border.None,
                        focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                    ),
                    modifier = Modifier
                        .focusRequester(favoritesFilterRequester)
                        .then(enterListOnDown)
                        .onFocusChanged { if (it.isFocused) onFocusKeyChanged(TvFocusKeys.SOFTWARE_FAVORITES) }
                        .focusProperties {
                            left = allFilterRequester
                            right = hideSystemRequester
                        }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Favorite, contentDescription = null, tint = PushTVColors.TextPrimary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "收藏 ($favoritesCount)",
                            color = PushTVColors.TextPrimary,
                            fontWeight = if (filterMode == FilterMode.FAVORITES) FontWeight.Bold else FontWeight.Normal
                        )
                        if (favoriteUpdateCount > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(PushTVColors.Success)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "$favoriteUpdateCount",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = PushTVColors.TextPrimary
                                )
                            }
                        }
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                // Hide/Show System Apps Toggle
                Button(
                    onClick = { viewModel.toggleHideSystemApps() },
                    scale = ButtonDefaults.scale(focusedScale = 1f),
                    modifier = Modifier
                        .focusRequester(hideSystemRequester)
                        .then(enterListOnDown)
                        .focusProperties {
                            left = favoritesFilterRequester
                            right = githubTokenRequester
                        }
                        .onFocusChanged { if (it.isFocused) onFocusKeyChanged(TvFocusKeys.SOFTWARE_HIDE_SYSTEM) },
                    colors = ButtonDefaults.colors(
                        containerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                        focusedContainerColor = PushTVColors.TextMuted,
                        contentColor = PushTVColors.TextPrimary,
                        focusedContentColor = PushTVColors.TextPrimary
                    ),
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                    )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (hideSystemApps) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = null,
                            tint = PushTVColors.TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (hideSystemApps) "隐藏系统应用" else "显示系统应用", style = MaterialTheme.typography.labelLarge)
                    }
                }

                Button(
                    onClick = { showGitHubTokenDialog = true },
                    scale = ButtonDefaults.scale(focusedScale = 1f),
                    modifier = Modifier
                        .focusRequester(githubTokenRequester)
                        .then(enterListOnDown)
                        .focusProperties {
                            left = hideSystemRequester
                            right = checkRequester
                        }
                        .onFocusChanged { if (it.isFocused) onFocusKeyChanged(TvFocusKeys.SOFTWARE_GITHUB_TOKEN) },
                    colors = ButtonDefaults.colors(
                        containerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                        focusedContainerColor = PushTVColors.Primary,
                        contentColor = PushTVColors.TextPrimary,
                        focusedContentColor = PushTVColors.TextPrimary
                    ),
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                    )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (hasGitHubToken) Icons.Default.Key else Icons.Default.KeyOff,
                            contentDescription = null,
                            tint = if (hasGitHubToken) PushTVColors.Success else PushTVColors.TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("GitHub Token", style = MaterialTheme.typography.labelLarge)
                    }
                }

                // Global Check Update Button
                Button(
                    onClick = { 
                        viewModel.checkAllUpdates()
                    },
                    scale = ButtonDefaults.scale(focusedScale = 1f),
                    modifier = Modifier
                        .focusRequester(checkRequester)
                        .then(enterListOnDown)
                        .focusProperties {
                            left = githubTokenRequester
                            right = FocusRequester.Cancel
                        }
                        .onFocusChanged { if (it.isFocused) onFocusKeyChanged(TvFocusKeys.SOFTWARE_CHECK) },
                    colors = ButtonDefaults.colors(
                        containerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                        focusedContainerColor = PushTVColors.Success,
                        contentColor = PushTVColors.TextPrimary,
                        focusedContentColor = PushTVColors.TextPrimary
                    ),
                    shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                    border = ButtonDefaults.border(
                        focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                    )
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = PushTVColors.TextPrimary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("检测更新", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(32.dp))

        if (isLoadingApps && installedApps.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("正在读取已安装应用...", color = PushTVColors.TextSecondary)
            }
        } else if (apps.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val emptyIcon = when(filterMode) {
                        FilterMode.FAVORITES -> Icons.Default.FavoriteBorder
                        FilterMode.ALL -> Icons.Default.Apps
                    }
                    val emptyText = when(filterMode) {
                        FilterMode.FAVORITES -> "暂无收藏软件"
                        FilterMode.ALL -> "未发现安装的应用"
                    }
                    Icon(emptyIcon, contentDescription = null, modifier = Modifier.size(64.dp), tint = PushTVColors.TextPrimary.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(emptyText, color = PushTVColors.TextPrimary.copy(alpha = 0.3f))
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
                    val key = TvFocusKeys.app(app.packageName)
                    val fr = focusRequesterMap.getValue(key)
                    val leftRequester = if (index % 2 == 1) appKeys.getOrNull(index - 1)?.let(focusRequesterMap::get) else null
                    val rightRequester = if (index % 2 == 0) appKeys.getOrNull(index + 1)?.let(focusRequesterMap::get) else null

                    SoftwareItem(
                        app = app,
                        onClick = { onItemClick(app) },
                        softwareViewModel = viewModel,
                        modifier = Modifier
                            .focusRequester(fr)
                            .focusProperties {
                                left = leftRequester ?: FocusRequester.Cancel
                                right = rightRequester ?: FocusRequester.Cancel
                                if (index < 2) up = selectedFilterRequester
                            }
                            .onFocusChanged { 
                                if (it.isFocused) {
                                    onFocusKeyChanged(key)
                                    lastFocusIndex = index
                                }
                            }
                    )
                }
            }
        }
    }

    if (showGitHubTokenDialog) {
        GitHubTokenDialog(
            hasExistingToken = hasGitHubToken,
            onDismiss = { showGitHubTokenDialog = false },
            onSave = { token ->
                viewModel.saveGitHubToken(token)
                showGitHubTokenDialog = false
            }
        )
    }

}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun GitHubTokenDialog(
    hasExistingToken: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var token by remember { mutableStateOf("") }
    var inputFocused by remember { mutableStateOf(false) }
    val inputRequester = remember { FocusRequester() }
    val saveRequester = remember { FocusRequester() }
    val cancelRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun releaseInputFocus() {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        runCatching { cancelRequester.requestFocus() }
    }

    fun dismissDialog() {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        onDismiss()
    }

    LaunchedEffect(Unit) {
        repeat(3) {
            withFrameNanos { }
            if (runCatching { cancelRequester.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    BackHandler {
        if (inputFocused) {
            releaseInputFocus()
        } else {
            dismissDialog()
        }
    }

    Dialog(
        onDismissRequest = ::dismissDialog,
        properties = DialogProperties(dismissOnBackPress = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            colors = NonInteractiveSurfaceDefaults.colors(containerColor = PushTVColors.Panel),
            modifier = Modifier
                .width(560.dp)
                .focusGroup()
                .focusProperties { exit = { FocusRequester.Cancel } }
                .onPreviewKeyEvent { event ->
                    val isBack = event.key == Key.Back ||
                        event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK
                    if (event.type == KeyEventType.KeyDown && isBack) {
                        if (inputFocused) releaseInputFocus() else dismissDialog()
                        true
                    } else {
                        false
                    }
                }
        ) {
            Column(modifier = Modifier.padding(32.dp)) {
                Text(
                    text = "配置 GitHub Token",
                    style = MaterialTheme.typography.headlineSmall,
                    color = PushTVColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Token 用于提高 GitHub 更新检测的请求额度，仅加密保存在本机，不会随更新地址上传。建议使用无额外权限、设置有效期的 Token。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PushTVColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (hasExistingToken) {
                        "当前已配置。输入新 Token 可替换；留空保存将清除。"
                    } else {
                        "尚未配置。Token 通常以 ghp_ 或 github_pat_ 开头。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasExistingToken) PushTVColors.Success else PushTVColors.TextMuted
                )
                Spacer(modifier = Modifier.height(22.dp))
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputRequester)
                        .focusProperties {
                            up = FocusRequester.Cancel
                            down = saveRequester
                        }
                        .onFocusChanged { inputFocused = it.isFocused },
                    label = { Text("GitHub Token", color = PushTVColors.TextSecondary) },
                    placeholder = { Text("ghp_... / github_pat_...", color = PushTVColors.TextMuted) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { releaseInputFocus() }),
                    visualTransformation = PasswordVisualTransformation(),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = PushTVColors.TextPrimary,
                        unfocusedTextColor = PushTVColors.TextPrimary,
                        focusedLabelColor = PushTVColors.Primary,
                        unfocusedLabelColor = PushTVColors.TextSecondary,
                        focusedIndicatorColor = PushTVColors.Primary,
                        unfocusedIndicatorColor = PushTVColors.TextPrimary.copy(alpha = 0.2f)
                    )
                )
                Spacer(modifier = Modifier.height(28.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            releaseInputFocus()
                            onSave(token)
                        },
                        scale = ButtonDefaults.scale(focusedScale = 1f),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(saveRequester)
                            .focusProperties {
                                left = FocusRequester.Cancel
                                right = cancelRequester
                                up = inputRequester
                                down = FocusRequester.Cancel
                            },
                        colors = ButtonDefaults.colors(
                            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.1f),
                            focusedContainerColor = PushTVColors.Primary
                        )
                    ) { Text(if (token.isBlank() && hasExistingToken) "清除" else "保存") }

                    Button(
                        onClick = ::dismissDialog,
                        scale = ButtonDefaults.scale(focusedScale = 1f),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(cancelRequester)
                            .focusProperties {
                                left = saveRequester
                                right = FocusRequester.Cancel
                                up = inputRequester
                                down = FocusRequester.Cancel
                            },
                        colors = ButtonDefaults.colors(
                            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.1f),
                            focusedContainerColor = PushTVColors.TextPrimary.copy(alpha = 0.2f)
                        )
                    ) { Text("取消") }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SoftwareItem(app: InstalledApp, onClick: () -> Unit, softwareViewModel: SoftwareViewModel, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }
    val hasUpdate = softwareViewModel.isVersionDifferent(app.versionName, app.remoteVersion)

    Surface(
        onClick = onClick,
        colors = ClickableSurfaceDefaults.colors(
            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.03f),
            focusedContainerColor = PushTVColors.SurfaceFocused
        ),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(16.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        border = ClickableSurfaceDefaults.border(
            border = if (hasUpdate) {
                Border(BorderStroke(2.dp, PushTVColors.Success.copy(alpha = 0.5f)), inset = (-1).dp)
            } else {
                Border.None
            },
            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.Primary), inset = (-1).dp)
        ),
        modifier = modifier.fillMaxWidth().onFocusChanged { isFocused = it.isFocused }
    ) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(PushTVColors.TextPrimary.copy(alpha = 0.05f)), contentAlignment = Alignment.Center) {
                SoftwareAppIcon(app.packageName, softwareViewModel)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.name, style = MaterialTheme.typography.titleMedium, color = PushTVColors.TextPrimary, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    val hasUpdate = softwareViewModel.isVersionDifferent(app.versionName, app.remoteVersion)
                    if (hasUpdate) {
                        Spacer(modifier = Modifier.width(8.dp))
                        StatusTag("有新版本", PushTVColors.Success)
                    }
                    if (app.isFavorite) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(Icons.Default.Favorite, contentDescription = null, tint = PushTVColors.Favorite, modifier = Modifier.size(14.dp))
                    }
                }
                val versionText = StringBuilder("版本: ${app.versionName}")
                app.remoteStatus?.let { status ->
                    if (status.contains("失败") || status.contains("连接中")) {
                        versionText.append(" • $status")
                    }
                }
                Text(versionText.toString(), style = MaterialTheme.typography.bodySmall, color = if (app.remoteStatus?.contains("失败") == true) PushTVColors.Error else PushTVColors.TextSecondary)
            }
            if (!isFocused) Text("管理", style = MaterialTheme.typography.bodySmall, color = PushTVColors.TextSecondary.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun SoftwareAppIcon(packageName: String, viewModel: SoftwareViewModel) {
    val icon by produceState<Any?>(
        initialValue = viewModel.getCachedAppIcon(packageName),
        packageName,
        viewModel
    ) {
        if (value == null) value = viewModel.loadAppIcon(packageName)
    }
    AsyncImage(
        model = icon,
        contentDescription = null,
        modifier = Modifier.size(32.dp)
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun UrlEditDialog(app: InstalledApp, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var url by remember { mutableStateOf(app.updateUrl) }
    var inputFocused by remember { mutableStateOf(false) }
    val inputRequester = remember { FocusRequester() }
    val saveRequester = remember { FocusRequester() }
    val cancelRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        withFrameNanos { }
        inputRequester.requestFocus()
    }

    BackHandler {
        if (inputFocused) {
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
            cancelRequester.requestFocus()
        } else {
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            colors = NonInteractiveSurfaceDefaults.colors(containerColor = PushTVColors.Panel),
            modifier = Modifier
                .width(500.dp)
                .focusGroup()
                .focusProperties { exit = { FocusRequester.Cancel } }
        ) {
            Column(modifier = Modifier.padding(32.dp)) {
                Text("设置更新地址", style = MaterialTheme.typography.headlineSmall, color = PushTVColors.TextPrimary)
                Spacer(modifier = Modifier.height(8.dp))
                Text("输入 ${app.name} 的 GitHub 项目地址", style = MaterialTheme.typography.bodyMedium, color = PushTVColors.TextSecondary)
                Spacer(modifier = Modifier.height(24.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputRequester)
                        .focusProperties {
                            up = FocusRequester.Cancel
                            down = saveRequester
                        }
                        .onFocusChanged { inputFocused = it.isFocused },
                    label = { Text("https://...", color = PushTVColors.TextPrimary) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = PushTVColors.TextPrimary,
                        unfocusedTextColor = PushTVColors.TextPrimary,
                        focusedLabelColor = PushTVColors.TextPrimary,
                        unfocusedLabelColor = PushTVColors.TextPrimary.copy(alpha = 0.7f)
                    )
                )
                Spacer(modifier = Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onSave(url) }, 
                        scale = ButtonDefaults.scale(focusedScale = 1f),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(saveRequester)
                            .focusProperties {
                                left = FocusRequester.Cancel
                                right = cancelRequester
                                up = inputRequester
                                down = FocusRequester.Cancel
                            },
                        colors = ButtonDefaults.colors(
                            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.1f),
                            focusedContainerColor = PushTVColors.Primary
                        )
                    ) { Text("保存") }
                    Button(
                        onClick = onDismiss, 
                        scale = ButtonDefaults.scale(focusedScale = 1f),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(cancelRequester)
                            .focusProperties {
                                left = saveRequester
                                right = FocusRequester.Cancel
                                up = inputRequester
                                down = FocusRequester.Cancel
                            },
                        colors = ButtonDefaults.colors(
                            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.1f),
                            focusedContainerColor = PushTVColors.Error
                        )
                    ) { Text("取消") }
                }
            }
        }
    }
}
