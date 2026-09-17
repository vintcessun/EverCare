package com.evercare.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.evercare.app.AssistantUiState
import com.evercare.app.i18n.Strings

/**
 * The collapsed helper: a single round button that floats over other apps.
 *
 * It is the only thing on screen most of the time, and one tap opens the panel.
 */
@Composable
fun FloatingBubble(
    strings: Strings,
    isBusy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .size(EverCareDimens.Bubble)
            .semantics { contentDescription = strings.appName },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
        shadowElevation = 8.dp,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isBusy) Icons.Filled.Hearing else Icons.Filled.RecordVoiceOver,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

/**
 * The expanded panel: at most three actions, each one tap, each labelled in
 * plain words. There is no menu, no settings gear and no scrolling list of
 * options -- everything an elderly user needs day to day is visible at once.
 */
@Composable
fun FloatingPanel(
    state: AssistantUiState,
    strings: Strings,
    onReadScreen: () -> Unit,
    onExplainScreen: () -> Unit,
    onAskQuestion: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBusy = state.phase != AssistantUiState.Phase.Idle

    Card(
        modifier = modifier.widthIn(max = 360.dp),
        shape = RoundedCornerShape(EverCareDimens.CornerRadius),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
    ) {
        Column(
            modifier = Modifier.padding(EverCareDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(EverCareDimens.ItemSpacing),
        ) {
            StatusArea(state = state, strings = strings)

            if (isBusy) {
                // While something is happening there is exactly one button, and
                // it is the one that makes it stop.
                BigActionButton(
                    label = strings.stopSpeaking,
                    icon = Icons.Filled.Stop,
                    onClick = onStop,
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                )
            } else {
                if (state.canReadScreen) {
                    BigActionButton(
                        label = strings.readScreen,
                        icon = Icons.Filled.RecordVoiceOver,
                        onClick = onReadScreen,
                    )
                    BigActionButton(
                        label = strings.explainScreen,
                        icon = Icons.Filled.HelpOutline,
                        onClick = onExplainScreen,
                        containerColor = MaterialTheme.colorScheme.secondary,
                        contentColor = MaterialTheme.colorScheme.onSecondary,
                    )
                }
                if (state.canListen) {
                    BigActionButton(
                        label = strings.askQuestion,
                        icon = Icons.Filled.Mic,
                        onClick = onAskQuestion,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }

            BigActionButton(
                label = strings.closePanel,
                icon = Icons.Filled.Close,
                onClick = onClose,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusArea(state: AssistantUiState, strings: Strings) {
    val heading = when (state.phase) {
        AssistantUiState.Phase.Idle -> null
        AssistantUiState.Phase.Listening -> strings.listening
        AssistantUiState.Phase.Thinking -> strings.thinking
        AssistantUiState.Phase.Speaking -> strings.reading
    }

    if (state.error != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.errorContainer,
                    RoundedCornerShape(EverCareDimens.CornerRadius),
                )
                .padding(EverCareDimens.ItemSpacing),
        ) {
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        return
    }

    if (heading == null && state.message.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (heading != null) {
            Text(
                text = heading,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (state.message.isNotEmpty()) {
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyLarge,
                // Capped so the panel can never grow taller than the screen; the
                // text is being spoken anyway, reading along is secondary.
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun BigActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onPrimary,
) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = EverCareDimens.ActionButton),
        shape = RoundedCornerShape(EverCareDimens.CornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(EverCareDimens.ItemSpacing),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(36.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
