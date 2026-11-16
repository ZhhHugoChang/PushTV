package com.example.pushtv.ui.logs

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.example.pushtv.logging.AppLog
import com.example.pushtv.logging.AppLogEntry
import com.example.pushtv.logging.AppLogLevel
import com.example.pushtv.ui.theme.PushTVColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun LogsScreen(onNavigateBack: () -> Unit) {
    val logs by AppLog.entriesFlow.collectAsState()
    val remoteEnabled by AppLog.remoteAccessEnabled.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var clearArmed by remember { mutableStateOf(false) }

    BackHandler(onBack = onNavigateBack)

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "诊断日志",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    "仅记录 PushTV 自身事件 · ${logs.size}/${AppLog.MAX_ENTRIES}",
                    color = PushTVColors.TextSecondary,
                    fontSize = 14.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LogActionButton(
                    if (remoteEnabled) "关闭手机查看" else "开启手机查看",
                    Icons.Default.Download,
                    onClick = { AppLog.setRemoteAccessEnabled(!remoteEnabled) },
                    accent = if (remoteEnabled) PushTVColors.Success else PushTVColors.Primary
                )
                LogActionButton("最新", Icons.Default.VerticalAlignBottom, onClick = {
                    if (logs.isNotEmpty()) scope.launch { listState.animateScrollToItem(logs.lastIndex) }
                })
                LogActionButton(if (clearArmed) "确认清空" else "清空", Icons.Default.DeleteSweep, onClick = {
                    if (clearArmed) {
                        AppLog.clear()
                        clearArmed = false
                    } else {
                        clearArmed = true
                    }
                }, accent = PushTVColors.Error)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            if (remoteEnabled) {
                "手机端已可查看和下载；崩溃报告会自动保存到 Download/PushTV。"
            } else {
                "崩溃报告自动保存到 Download/PushTV；手机远程查看默认关闭。"
            },
            color = if (remoteEnabled) PushTVColors.Success else PushTVColors.TextMuted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(18.dp))

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().background(PushTVColors.Panel, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无日志", color = PushTVColors.TextSecondary)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(logs, key = AppLogEntry::id) { entry -> LogRow(entry) }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LogActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, accent: Color = PushTVColors.Primary) {
    Button(
        onClick = onClick,
        scale = ButtonDefaults.scale(focusedScale = 1f),
        colors = ButtonDefaults.colors(
            containerColor = PushTVColors.TextPrimary.copy(alpha = 0.06f),
            focusedContainerColor = accent
        ),
        border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, Color.White), inset = (-1).dp)),
        shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp))
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.padding(horizontal = 3.dp))
        Text(label)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LogRow(entry: AppLogEntry) {
    val levelColor = when (entry.level) {
        AppLogLevel.INFO -> PushTVColors.Primary
        AppLogLevel.WARN -> Color(0xFFF59E0B)
        AppLogLevel.ERROR -> PushTVColors.Error
    }
    val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestamp))
    Surface(
        onClick = {},
        modifier = Modifier.fillMaxWidth(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = PushTVColors.Panel,
            focusedContainerColor = PushTVColors.TextPrimary.copy(alpha = 0.12f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, PushTVColors.TextPrimary.copy(alpha = 0.08f))),
            focusedBorder = Border(BorderStroke(2.dp, PushTVColors.Primary))
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp))
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(time, fontFamily = FontFamily.Monospace, color = PushTVColors.TextMuted, fontSize = 13.sp)
            Spacer(Modifier.padding(horizontal = 6.dp))
            Text(entry.level.name, color = levelColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.padding(horizontal = 6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.tag, color = PushTVColors.TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(entry.message, color = PushTVColors.TextPrimary, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }
    }
}
