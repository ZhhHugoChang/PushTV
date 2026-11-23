@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.example.pushtv.ui.home

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
import com.example.pushtv.ui.theme.TvDialog
import com.example.pushtv.ui.theme.TvDialogActions
import com.example.pushtv.ui.theme.TvDialogButton
import com.example.pushtv.ui.theme.TvDialogMessage
import com.example.pushtv.ui.theme.TvDialogTitle
import kotlinx.coroutines.delay
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ConfirmDialog(
    title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit, confirmLabel: String = "确定"
) {
    val confirmRequester = remember { FocusRequester() }
    val cancelRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        // CRASH-01: Dialog 入场动画可能多帧才完成，用重试保护
        repeat(5) {
            withFrameNanos { }
            if (runCatching { cancelRequester.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
    }

    BackHandler(onBack = onDismiss)

    TvDialog(onDismiss = onDismiss, width = 440.dp) {
        TvDialogTitle(title)
        Spacer(Modifier.height(8.dp))
        TvDialogMessage(message)
        Spacer(Modifier.height(20.dp))
        TvDialogActions {
            TvDialogButton(
                label = confirmLabel,
                onClick = onConfirm,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(confirmRequester)
                    .focusProperties {
                        left = FocusRequester.Cancel
                        right = cancelRequester
                        up = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                    },
                accent = PushTVColors.Error
            )
            TvDialogButton(
                label = "取消",
                onClick = onDismiss,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(cancelRequester)
                    .focusProperties {
                        left = confirmRequester
                        right = FocusRequester.Cancel
                        up = FocusRequester.Cancel
                        down = FocusRequester.Cancel
                    },
                accent = PushTVColors.TextSecondary
            )
        }
    }
}
