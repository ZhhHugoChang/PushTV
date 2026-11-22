package com.example.pushtv.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.example.pushtv.data.GitHubProxySettings
import com.example.pushtv.network.GitHubProxyClient
import com.example.pushtv.ui.theme.PushTVColors
import com.example.pushtv.ui.theme.TvDialog
import com.example.pushtv.ui.theme.TvDialogActions
import com.example.pushtv.ui.theme.TvDialogButton
import com.example.pushtv.ui.theme.TvDialogMessage
import com.example.pushtv.ui.theme.TvDialogTitle
import kotlinx.coroutines.launch

@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun GitHubProxyDialog(
    settings: GitHubProxySettings, onSelect: (String) -> Unit, onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val speedTestFocus = remember { FocusRequester() }
    val closeFocus = remember { FocusRequester() }
    var probing by remember { mutableStateOf(false) }
    var probeRevision by remember { mutableIntStateOf(0) }
    val nodes = settings.nodes
    val rowFocus = remember(nodes.map { it.id }) { List(nodes.size + 2) { FocusRequester() } }
    val probes = remember(nodes, probeRevision) { nodes.associate { it.id to GitHubProxyClient.recentProbe(it) } }

    fun optionFocus(index: Int) = Modifier.focusRequester(rowFocus[index]).focusProperties {
        up = rowFocus.getOrNull(index - 1) ?: FocusRequester.Cancel
        down = rowFocus.getOrNull(index + 1) ?: if (probing) closeFocus else speedTestFocus
    }

    TvDialog(onDismiss = onDismiss, width = 560.dp, scrollContent = false) {
        TvDialogTitle("GitHub 下载代理")
        Spacer(Modifier.height(8.dp))
        TvDialogMessage("设置仅用于 GitHub APK 下载。自定义节点可在手机管理页编辑。")
        Spacer(Modifier.height(16.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            val rows = ((maxHeight + ProxyRowGap) / (ProxyRowHeight + ProxyRowGap))
                .toInt().coerceIn(1, minOf(5, nodes.size + 2))
            val listHeight = (ProxyRowHeight * rows + ProxyRowGap * (rows - 1)).coerceAtMost(maxHeight)
            Column(
                modifier = Modifier.height(listHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(ProxyRowGap)
            ) {
                ProxyOption("直连", GitHubProxySettings.DIRECT == settings.selection, "默认", optionFocus(0)) {
                    onSelect(GitHubProxySettings.DIRECT)
                }
                ProxyOption("自动", GitHubProxySettings.AUTO == settings.selection, "并发探测线路", optionFocus(1)) {
                    onSelect(GitHubProxySettings.AUTO)
                }
                nodes.forEachIndexed { index, node ->
                    val probe = probes[node.id]
                    ProxyOption(node.name, node.id == settings.selection, probe?.displayLabel ?: "未测速", optionFocus(index + 2),
                        detailColor = when (probe?.tone) {
                            "fast" -> PushTVColors.ProbeFast
                            "moderate" -> PushTVColors.ProbeModerate
                            "slow" -> PushTVColors.ProbeSlow
                            "timeout" -> PushTVColors.ProbeTimeout
                            "unavailable" -> PushTVColors.ProbeUnavailable
                            else -> PushTVColors.TextSecondary
                        }) {
                        onSelect(node.id)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        TvDialogActions {
            TvDialogButton(
                label = if (probing) "测速中…" else "测速",
                onClick = {
                    closeFocus.requestFocus()
                    probing = true
                    scope.launch {
                        try {
                            GitHubProxyClient.probeAll(nodes)
                            probeRevision++
                        } finally {
                            probing = false
                        }
                    }
                },
                enabled = !probing,
                modifier = Modifier.weight(1f).focusRequester(speedTestFocus).focusProperties {
                    up = rowFocus.last()
                    right = closeFocus
                    down = FocusRequester.Cancel
                }
            )
            TvDialogButton(
                "关闭", onDismiss,
                Modifier.weight(1f).focusRequester(closeFocus).focusProperties {
                    up = rowFocus.last()
                    left = if (probing) FocusRequester.Cancel else speedTestFocus
                    down = FocusRequester.Cancel
                },
                accent = PushTVColors.TextSecondary
            )
        }
    }
    LaunchedEffect(Unit) {
        repeat(5) {
            withFrameNanos { }
            if (runCatching { speedTestFocus.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
    }
}

private val ProxyRowHeight = 48.dp
private val ProxyRowGap = 8.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProxyOption(
    label: String, checked: Boolean, detail: String,
    modifier: Modifier = Modifier,
    detailColor: Color = PushTVColors.TextSoft, onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val selectedBackground = PushTVColors.Primary.copy(alpha = 0.20f).compositeOver(PushTVColors.Surface)
    val outline = when {
        focused -> PushTVColors.Primary
        checked -> PushTVColors.Primary.copy(alpha = 0.65f)
        else -> Color.Transparent
    }
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(ProxyRowHeight)
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, outline, shape)
            .semantics { selected = checked },
        contentPadding = PaddingValues(horizontal = 16.dp),
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(shape),
        colors = ButtonDefaults.colors(
            containerColor = if (checked) selectedBackground else PushTVColors.Surface,
            focusedContainerColor = if (checked) selectedBackground else PushTVColors.SurfaceFocused,
            contentColor = PushTVColors.TextPrimary,
            focusedContentColor = PushTVColors.TextPrimary
        )
    ) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val statusWidth = maxWidth * 0.27f
            Text(
                label,
                modifier = Modifier.width(maxWidth - statusWidth * 2 - 16.dp),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                detail,
                modifier = Modifier.align(Alignment.CenterEnd).width(statusWidth),
                style = MaterialTheme.typography.labelMedium,
                color = detailColor,
                textAlign = TextAlign.End,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
