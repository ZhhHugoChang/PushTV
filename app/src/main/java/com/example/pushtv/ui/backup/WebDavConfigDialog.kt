@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.example.pushtv.ui.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import com.example.pushtv.data.WebDavConfig
import com.example.pushtv.ui.theme.PushTVColors

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
        withFrameNanos { }
        saveBtnRequester.requestFocus()
    }

    BackHandler {
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        onDismiss()
    }

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
                .width(560.dp)
                .padding(16.dp)
                .focusGroup()
                .focusProperties { exit = { FocusRequester.Cancel } }
        ) {
            Column(
                modifier = Modifier
                    .padding(28.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "WebDAV 备份配置",
                    style = MaterialTheme.typography.headlineSmall,
                    color = PushTVColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "配置 WebDAV 服务器以同步备份与恢复应用 APK",
                    style = MaterialTheme.typography.bodySmall,
                    color = PushTVColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(20.dp))

                // 服务器地址
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("服务器地址 (如 https://dav.jianguoyun.com/dav/)", color = PushTVColors.TextSecondary) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = PushTVColors.TextPrimary,
                        unfocusedTextColor = PushTVColors.TextPrimary,
                        focusedLabelColor = PushTVColors.Primary,
                        unfocusedLabelColor = PushTVColors.TextSecondary,
                        focusedIndicatorColor = PushTVColors.Primary,
                        unfocusedIndicatorColor = PushTVColors.TextPrimary.copy(alpha = 0.2f)
                    ),
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
                    label = { Text("用户名 / 账号", color = PushTVColors.TextSecondary) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = PushTVColors.TextPrimary,
                        unfocusedTextColor = PushTVColors.TextPrimary,
                        focusedLabelColor = PushTVColors.Primary,
                        unfocusedLabelColor = PushTVColors.TextSecondary,
                        focusedIndicatorColor = PushTVColors.Primary,
                        unfocusedIndicatorColor = PushTVColors.TextPrimary.copy(alpha = 0.2f)
                    ),
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
                    label = { Text("密码 / 应用授权码", color = PushTVColors.TextSecondary) },
                    singleLine = true,
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
                    ),
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
                    label = { Text("远端备份目录", color = PushTVColors.TextSecondary) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
                        unfocusedContainerColor = Color.Transparent,
                        focusedTextColor = PushTVColors.TextPrimary,
                        unfocusedTextColor = PushTVColors.TextPrimary,
                        focusedLabelColor = PushTVColors.Primary,
                        unfocusedLabelColor = PushTVColors.TextSecondary,
                        focusedIndicatorColor = PushTVColors.Primary,
                        unfocusedIndicatorColor = PushTVColors.TextPrimary.copy(alpha = 0.2f)
                    ),
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
                    Text(
                        text = testStatusText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testStatusText?.startsWith("🟢") == true) PushTVColors.Success else PushTVColors.Error
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 操作按钮栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 测试连接按钮
                    Button(
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
                                testStatusText = if (success) "🟢 $msg" else "🔴 $msg"
                            }
                        },
                        scale = ButtonDefaults.scale(focusedScale = 1.04f),
                        enabled = !isTesting,
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.08f),
                            focusedContainerColor = PushTVColors.TextPrimary.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier
                            .weight(1.1f)
                            .height(44.dp)
                            .focusRequester(testBtnRequester)
                            .focusProperties {
                                up = dirRequester
                                right = saveBtnRequester
                                down = FocusRequester.Cancel
                            }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = if (isTesting) "测试中..." else "测试连接",
                                textAlign = TextAlign.Center,
                                color = PushTVColors.TextPrimary
                            )
                        }
                    }

                    // 保存按钮
                    Button(
                        onClick = {
                            val finalConfig = WebDavConfig(
                                serverUrl = url.trim(),
                                username = username.trim(),
                                password = password.trim(),
                                remoteDir = remoteDir.trim().ifBlank { "/PushTV/Backups/" }
                            )
                            onSave(finalConfig)
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
                            .focusRequester(saveBtnRequester)
                            .focusProperties {
                                up = dirRequester
                                left = testBtnRequester
                                right = cancelBtnRequester
                                down = FocusRequester.Cancel
                            }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "保存",
                                textAlign = TextAlign.Center,
                                color = PushTVColors.TextPrimary
                            )
                        }
                    }

                    // 取消按钮
                    Button(
                        onClick = onDismiss,
                        scale = ButtonDefaults.scale(focusedScale = 1.04f),
                        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        border = ButtonDefaults.border(
                            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary), inset = (-1).dp)
                        ),
                        colors = ButtonDefaults.colors(
                            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.08f),
                            focusedContainerColor = PushTVColors.TextPrimary.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .weight(0.9f)
                            .height(44.dp)
                            .focusRequester(cancelBtnRequester)
                            .focusProperties {
                                up = dirRequester
                                left = saveBtnRequester
                                down = FocusRequester.Cancel
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
