@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.example.pushtv.ui.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.example.pushtv.data.WebDavConfig
import com.example.pushtv.ui.theme.PushTVColors
import com.example.pushtv.ui.theme.TvDialog
import com.example.pushtv.ui.theme.TvDialogActions
import com.example.pushtv.ui.theme.TvDialogButton
import com.example.pushtv.ui.theme.TvDialogFieldShape
import com.example.pushtv.ui.theme.TvDialogMessage
import com.example.pushtv.ui.theme.TvDialogTitle
import com.example.pushtv.ui.theme.tvDialogFieldColors

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun WebDavConfigDialog(
    initialConfig: WebDavConfig,
    onDismiss: () -> Unit,
    onSave: (WebDavConfig) -> Unit,
    onTestConnection: (WebDavConfig, (Boolean, String) -> Unit) -> Unit
) {
    var url by remember { mutableStateOf(initialConfig.serverUrl) }
    var username by remember { mutableStateOf(initialConfig.username) }
    var password by remember { mutableStateOf(initialConfig.password) }
    var remoteDir by remember { mutableStateOf(initialConfig.remoteDir) }

    var testStatusText by remember { mutableStateOf<String?>(null) }
    var isTesting by remember { mutableStateOf(false) }
    var testSucceeded by remember { mutableStateOf(false) }

    val urlRequester = remember { FocusRequester() }
    val userRequester = remember { FocusRequester() }
    val passRequester = remember { FocusRequester() }
    val dirRequester = remember { FocusRequester() }
    val testBtnRequester = remember { FocusRequester() }
    val saveBtnRequester = remember { FocusRequester() }
    val cancelBtnRequester = remember { FocusRequester() }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        // CRASH-01: Dialog 入场动画可能多帧才完成，用重试保护
        repeat(5) {
            withFrameNanos { }
            if (runCatching { saveBtnRequester.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    BackHandler {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        onDismiss()
    }

    TvDialog(onDismiss = onDismiss, width = 560.dp) {
        TvDialogTitle("WebDAV 备份配置")
        Spacer(Modifier.height(8.dp))
        TvDialogMessage("配置 WebDAV 服务器以同步备份与恢复应用 APK")
        Spacer(modifier = Modifier.height(20.dp))

        // 服务器地址
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("服务器地址 (如 https://dav.jianguoyun.com/dav/)") },
            singleLine = true,
            shape = TvDialogFieldShape,
            colors = tvDialogFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(urlRequester)
                .focusProperties {
                    up = FocusRequester.Cancel
                    down = userRequester
                }
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 用户名
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("用户名 / 账号") },
            singleLine = true,
            shape = TvDialogFieldShape,
            colors = tvDialogFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(userRequester)
                .focusProperties {
                    up = urlRequester
                    down = passRequester
                }
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 密码
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码 / 应用授权码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = TvDialogFieldShape,
            colors = tvDialogFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(passRequester)
                .focusProperties {
                    up = userRequester
                    down = dirRequester
                }
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 远端目录
        OutlinedTextField(
            value = remoteDir,
            onValueChange = { remoteDir = it },
            label = { Text("远端备份目录") },
            singleLine = true,
            shape = TvDialogFieldShape,
            colors = tvDialogFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(dirRequester)
                .focusProperties {
                    up = passRequester
                    down = testBtnRequester
                }
        )

        if (testStatusText != null) {
            Spacer(modifier = Modifier.height(10.dp))
            TvDialogMessage(
                text = testStatusText.orEmpty(),
                color = when {
                    isTesting -> PushTVColors.TextSecondary
                    testSucceeded -> PushTVColors.ProbeFast
                    else -> PushTVColors.ProbeUnavailable
                }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 操作按钮栏
        TvDialogActions {
            // 测试连接按钮
            TvDialogButton(
                label = if (isTesting) "测试中..." else "测试连接",
                onClick = {
                    isTesting = true
                    testStatusText = "正在测试连接..."
                    val currentConfig = WebDavConfig(
                        serverUrl = url.trim(),
                        username = username.trim(),
                        password = password.trim(),
                        remoteDir = remoteDir.trim().ifBlank { "/PushTV/Backups/" }
                    )
                    onTestConnection(currentConfig) { success, msg ->
                        isTesting = false
                        testSucceeded = success
                        testStatusText = msg
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(testBtnRequester)
                    .focusProperties {
                        up = dirRequester
                        right = saveBtnRequester
                        down = FocusRequester.Cancel
                    },
                enabled = !isTesting,
                accent = PushTVColors.TextSecondary
            )

            // 保存按钮
            TvDialogButton(
                label = "保存",
                onClick = {
                    val finalConfig = WebDavConfig(
                        serverUrl = url.trim(),
                        username = username.trim(),
                        password = password.trim(),
                        remoteDir = remoteDir.trim().ifBlank { "/PushTV/Backups/" }
                    )
                    onSave(finalConfig)
                },
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(saveBtnRequester)
                    .focusProperties {
                        up = dirRequester
                        left = testBtnRequester
                        right = cancelBtnRequester
                        down = FocusRequester.Cancel
                    }
            )

            // 取消按钮
            TvDialogButton(
                label = "取消",
                onClick = onDismiss,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(cancelBtnRequester)
                    .focusProperties {
                        up = dirRequester
                        left = saveBtnRequester
                        down = FocusRequester.Cancel
                    },
                accent = PushTVColors.TextSecondary
            )
        }
    }
}
