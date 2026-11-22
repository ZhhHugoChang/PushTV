@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.pushtv.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyOff
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import com.example.pushtv.data.GitHubProxySettings
import com.example.pushtv.network.GitHubProxyClient
import com.example.pushtv.network.GitHubProxyClient.ConnectionStatus
import com.example.pushtv.ui.software.SoftwareViewModel
import com.example.pushtv.ui.theme.PushTVColors
import com.example.pushtv.ui.theme.TvDialog
import com.example.pushtv.ui.theme.TvDialogActions
import com.example.pushtv.ui.theme.TvDialogButton
import com.example.pushtv.ui.theme.TvDialogFieldShape
import com.example.pushtv.ui.theme.TvDialogMessage
import com.example.pushtv.ui.theme.TvDialogTitle
import com.example.pushtv.ui.theme.tvDialogFieldColors
import kotlinx.coroutines.launch

private enum class SettingsDialog { TOKEN, PROXY }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun SoftwareSettingsScreen(
    viewModel: SoftwareViewModel,
    onNavigateBack: () -> Unit
) {
    val hasToken by viewModel.hasGitHubToken.collectAsStateWithLifecycle()
    val proxySettings by viewModel.githubProxySettings.collectAsStateWithLifecycle()
    val proxyStatus by remember(proxySettings) {
        GitHubProxyClient.observeConnectionStatus(proxySettings)
    }.collectAsStateWithLifecycle(initialValue = ConnectionStatus.DIRECT)
    val tokenRequester = remember { FocusRequester() }
    val proxyRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<SettingsDialog?>(null) }

    BackHandler(enabled = dialog == null, onBack = onNavigateBack)

    LaunchedEffect(Unit) {
        withFrameNanos { }
        tokenRequester.requestFocus()
    }

    fun closeDialog(requester: FocusRequester) {
        dialog = null
        scope.launch {
            withFrameNanos { }
            requester.requestFocus()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (dialog != null || event.key != Key.Back) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) onNavigateBack()
                true
            }
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "更新设置",
            modifier = Modifier.semantics { heading() }.padding(bottom = 4.dp),
            style = MaterialTheme.typography.headlineMedium,
            color = PushTVColors.TextPrimary
        )

        SoftwareSettingRow(
            title = "GitHub Token",
            icon = if (hasToken) Icons.Default.Key else Icons.Default.KeyOff,
            iconColor = if (hasToken) PushTVColors.ProbeFast else PushTVColors.TextSecondary,
            onClick = { dialog = SettingsDialog.TOKEN },
            modifier = Modifier.focusRequester(tokenRequester).focusProperties {
                up = FocusRequester.Cancel
                down = proxyRequester
            }
        ) {
            SettingsValue(if (hasToken) "已配置" else "未配置",
                if (hasToken) PushTVColors.ProbeFast else PushTVColors.TextSecondary)
        }

        val proxyName = when (proxySettings.selection) {
            GitHubProxySettings.DIRECT -> "直连"
            GitHubProxySettings.AUTO -> "自动"
            else -> proxySettings.nodes.firstOrNull { it.id == proxySettings.selection }?.name ?: "直连"
        }
        val proxyColor = when (proxyStatus) {
            ConnectionStatus.DIRECT, ConnectionStatus.UNTESTED -> PushTVColors.TextSecondary
            ConnectionStatus.AVAILABLE -> PushTVColors.ProbeFast
            ConnectionStatus.TIMEOUT -> PushTVColors.ProbeTimeout
            ConnectionStatus.UNAVAILABLE -> PushTVColors.ProbeUnavailable
        }
        SoftwareSettingRow(
            title = "下载加速代理",
            icon = if (proxyStatus == ConnectionStatus.DIRECT) Icons.Default.CloudOff else Icons.Default.CloudDownload,
            iconColor = proxyColor,
            onClick = { dialog = SettingsDialog.PROXY },
            modifier = Modifier.focusRequester(proxyRequester)
                .semantics { stateDescription = proxyStatus.description }
                .focusProperties {
                    up = tokenRequester
                    down = FocusRequester.Cancel
                }
        ) {
            SettingsValue(proxyName, proxyColor)
        }

    }

    when (dialog) {
        SettingsDialog.TOKEN -> GitHubTokenDialog(
            hasExistingToken = hasToken,
            onDismiss = { closeDialog(tokenRequester) },
            onSave = { token ->
                viewModel.saveGitHubToken(token)
                closeDialog(tokenRequester)
            }
        )
        SettingsDialog.PROXY -> GitHubProxyDialog(
            settings = proxySettings,
            onSelect = viewModel::selectGitHubProxy,
            onDismiss = { closeDialog(proxyRequester) }
        )
        null -> Unit
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SoftwareSettingRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconColor: Color = PushTVColors.TextSecondary,
    trailing: @Composable RowScope.() -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = PushTVColors.Surface,
            focusedContainerColor = PushTVColors.SurfaceFocused
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.Primary), inset = (-1).dp)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = iconColor)
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = PushTVColors.TextPrimary
            )
            trailing()
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SettingsValue(value: String, color: Color) {
    Text(value, modifier = Modifier.widthIn(max = 180.dp), style = MaterialTheme.typography.bodyMedium,
        color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = PushTVColors.TextSecondary)
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

    TvDialog(
        onDismiss = ::dismissDialog,
        width = 560.dp,
        dismissOnBackPress = false,
        modifier = Modifier.onPreviewKeyEvent { event ->
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
        TvDialogTitle("配置 GitHub Token")
        Spacer(Modifier.height(8.dp))
        TvDialogMessage("Token 用于提高 GitHub 更新检测的请求额度，仅加密保存在本机，不会随更新地址上传。建议使用无额外权限、设置有效期的 Token。")
        Spacer(modifier = Modifier.height(8.dp))
        TvDialogMessage(
            text = if (hasExistingToken) {
                "当前已配置。输入新 Token 可替换；留空保存将清除。"
            } else {
                "尚未配置。Token 通常以 ghp_ 或 github_pat_ 开头。"
            },
            color = if (hasExistingToken) PushTVColors.ProbeFast else PushTVColors.TextSecondary
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
            label = { androidx.compose.material3.Text("GitHub Token") },
            placeholder = { Text("ghp_... / github_pat_...", color = PushTVColors.TextMuted) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { releaseInputFocus() }),
            visualTransformation = PasswordVisualTransformation(),
            shape = TvDialogFieldShape,
            colors = tvDialogFieldColors()
        )
        Spacer(Modifier.height(20.dp))
        TvDialogActions {
            TvDialogButton(
                label = if (token.isBlank() && hasExistingToken) "清除" else "保存",
                onClick = {
                    releaseInputFocus()
                    onSave(token)
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(saveRequester)
                    .focusProperties {
                        left = FocusRequester.Cancel
                        right = cancelRequester
                        up = inputRequester
                        down = FocusRequester.Cancel
                    },
                accent = if (token.isBlank() && hasExistingToken) PushTVColors.Error else PushTVColors.Primary
            )

            TvDialogButton(
                label = "取消",
                onClick = ::dismissDialog,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(cancelRequester)
                    .focusProperties {
                        left = saveRequester
                        right = FocusRequester.Cancel
                        up = inputRequester
                        down = FocusRequester.Cancel
                    },
                accent = PushTVColors.TextSecondary
            )
        }
    }
}
