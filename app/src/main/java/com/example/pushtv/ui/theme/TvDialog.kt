package com.example.pushtv.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*

internal val TvDialogFieldShape = RoundedCornerShape(12.dp)

@Composable
internal fun tvDialogFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = PushTVColors.TextPrimary.copy(alpha = 0.05f),
    unfocusedContainerColor = Color.Transparent,
    focusedTextColor = PushTVColors.TextPrimary,
    unfocusedTextColor = PushTVColors.TextPrimary,
    focusedLabelColor = PushTVColors.Primary,
    unfocusedLabelColor = PushTVColors.TextSecondary,
    focusedIndicatorColor = PushTVColors.Primary,
    unfocusedIndicatorColor = PushTVColors.TextPrimary.copy(alpha = 0.25f)
)

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun TvDialog(
    onDismiss: () -> Unit,
    width: Dp = 440.dp,
    modifier: Modifier = Modifier,
    dismissOnBackPress: Boolean = true,
    scrollContent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(Modifier.padding(horizontal = 24.dp, vertical = 20.dp), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                colors = NonInteractiveSurfaceDefaults.colors(containerColor = PushTVColors.Panel),
                modifier = modifier.width(width)
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup()
            ) {
                Column(
                    modifier = Modifier
                        .then(if (scrollContent) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = content
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvDialogTitle(text: String) {
    Text(
        text, modifier = Modifier.fillMaxWidth().semantics { heading() },
        style = MaterialTheme.typography.headlineSmall,
        color = PushTVColors.TextPrimary, textAlign = TextAlign.Center
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvDialogMessage(text: String, color: Color = PushTVColors.TextSecondary) {
    Text(
        text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium,
        color = color, textAlign = TextAlign.Center
    )
}

@Composable
internal fun TvDialogActions(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically, content = content
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun TvDialogButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    accent: Color = PushTVColors.Primary
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        enabled = enabled,
        scale = ButtonDefaults.scale(focusedScale = 1f),
        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ButtonDefaults.colors(
            containerColor = PushTVColors.Surface,
            focusedContainerColor = accent.copy(alpha = 0.42f).compositeOver(PushTVColors.Surface),
            contentColor = PushTVColors.TextPrimary,
            focusedContentColor = PushTVColors.TextPrimary,
            pressedContentColor = PushTVColors.TextPrimary,
            disabledContentColor = PushTVColors.TextSecondary
        ),
        border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, PushTVColors.TextPrimary)))
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, textAlign = TextAlign.Center)
        }
    }
}
