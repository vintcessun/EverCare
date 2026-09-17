package com.evercare.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.evercare.app.data.AiMode
import com.evercare.app.data.AssistantSettings
import com.evercare.app.data.SubscriptionStatus
import com.evercare.app.i18n.Strings
import com.evercare.app.ui.EverCareDimens
import com.evercare.app.ui.EverCareTheme

/**
 * One step of first-run setup, e.g. "allow the helper button".
 *
 * The list is supplied per platform because the permissions differ: Android has
 * three, iOS has one.
 */
data class SetupStep(
    val title: String,
    val body: String,
    val isDone: Boolean,
    val onGrant: () -> Unit,
)

/**
 * The app's only full screen.
 *
 * It is not where the product lives -- the floating panel is -- and an elderly
 * user is not expected to come back here after setup. It exists for the person
 * who sets the phone up, which is usually a family member.
 */
@Composable
fun App(
    strings: Strings,
    steps: List<SetupStep>,
    settings: AssistantSettings,
    subscription: SubscriptionStatus?,
    isHelperRunning: Boolean,
    onSettingsChange: (AssistantSettings) -> Unit,
    onToggleHelper: () -> Unit,
) {
    EverCareTheme {
        Scaffold { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(EverCareDimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(EverCareDimens.ItemSpacing),
            ) {
                Text(strings.appName, style = MaterialTheme.typography.displaySmall)
                Text(
                    strings.tagline,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (steps.any { !it.isDone }) {
                    Text(
                        strings.setupTitle,
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = EverCareDimens.ItemSpacing),
                    )
                    steps.forEach { step -> SetupStepCard(step = step, strings = strings) }
                }

                // Starting the helper is the whole point of this screen, so it is
                // the largest control and sits above the settings.
                Button(
                    onClick = onToggleHelper,
                    enabled = steps.all { it.isDone },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = EverCareDimens.ActionButton),
                    shape = RoundedCornerShape(EverCareDimens.CornerRadius),
                ) {
                    Text(
                        if (isHelperRunning) strings.stopHelper else strings.startHelper,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                AssistantSettingsSection(
                    strings = strings,
                    settings = settings,
                    subscription = subscription,
                    onSettingsChange = onSettingsChange,
                )
            }
        }
    }
}

@Composable
private fun SetupStepCard(step: SetupStep, strings: Strings) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(EverCareDimens.CornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = if (step.isDone) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(EverCareDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(step.title, style = MaterialTheme.typography.titleMedium)
            Text(step.body, style = MaterialTheme.typography.bodyMedium)

            Button(
                onClick = step.onGrant,
                enabled = !step.isDone,
                modifier = Modifier.heightIn(min = EverCareDimens.TouchTarget),
                shape = RoundedCornerShape(EverCareDimens.CornerRadius),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    if (step.isDone) strings.granted else strings.grant,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun AssistantSettingsSection(
    strings: Strings,
    settings: AssistantSettings,
    subscription: SubscriptionStatus?,
    onSettingsChange: (AssistantSettings) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(EverCareDimens.CornerRadius),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(EverCareDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(EverCareDimens.ItemSpacing),
        ) {
            Text(strings.aiServiceTitle, style = MaterialTheme.typography.titleLarge)

            Column(Modifier.selectableGroup()) {
                ModeOption(
                    title = strings.useEverCareService,
                    body = strings.useEverCareServiceBody,
                    selected = settings.mode == AiMode.EverCare,
                    onSelect = { onSettingsChange(settings.copy(mode = AiMode.EverCare)) },
                )
                ModeOption(
                    title = strings.useOwnProvider,
                    body = strings.useOwnProviderBody,
                    selected = settings.mode == AiMode.Custom,
                    onSelect = { onSettingsChange(settings.copy(mode = AiMode.Custom)) },
                )
            }

            when (settings.mode) {
                AiMode.EverCare -> {
                    OutlinedTextField(
                        value = settings.everCareServerUrl,
                        onValueChange = { onSettingsChange(settings.copy(everCareServerUrl = it)) },
                        label = { Text(strings.serverUrl) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (subscription != null) {
                        val line = when {
                            !subscription.active -> strings.subscriptionInactive
                            subscription.plan == "trial" ->
                                "${strings.trialDaysLeft} ${subscription.daysRemaining}"

                            else -> "${strings.subscriptionActiveUntil} ${subscription.expiresAt.take(10)}"
                        }
                        Text(line, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                AiMode.Custom -> {
                    OutlinedTextField(
                        value = settings.customBaseUrl,
                        onValueChange = { onSettingsChange(settings.copy(customBaseUrl = it)) },
                        label = { Text(strings.baseUrl) },
                        singleLine = true,
                        placeholder = { Text("https://api.deepseek.com") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = settings.customApiKey,
                        onValueChange = { onSettingsChange(settings.copy(customApiKey = it)) },
                        label = { Text(strings.apiKey) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = settings.customModel,
                        onValueChange = { onSettingsChange(settings.copy(customModel = it)) },
                        label = { Text(strings.model) },
                        singleLine = true,
                        placeholder = { Text("deepseek-chat") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeOption(
    title: String,
    body: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = EverCareDimens.TouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
