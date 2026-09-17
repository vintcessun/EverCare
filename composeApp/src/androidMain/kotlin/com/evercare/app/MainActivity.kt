package com.evercare.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.evercare.app.data.AiMode
import com.evercare.app.data.AssistantSettings
import com.evercare.app.data.SubscriptionStatus
import com.evercare.app.i18n.stringsFor
import kotlinx.coroutines.launch

/**
 * The setup screen.
 *
 * Deliberately not the product: everything here is done once, usually by a family
 * member, and then the user lives in the floating helper instead.
 */
class MainActivity : ComponentActivity() {

    private val graph by lazy { EverCareApplication.graphOf(this) }

    private var permissionsVersion by mutableStateOf(0)

    private val requestMicrophone = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionsVersion++ }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permissionsVersion++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val settings by graph.settingsRepository.settings
                .collectAsStateWithLifecycle(initialValue = AssistantSettings())
            var subscription by remember { mutableStateOf<SubscriptionStatus?>(null) }
            var helperRunning by remember { mutableStateOf(false) }

            // Re-read on every recomposition triggered by returning from a system
            // settings screen; Android gives no callback for those grants.
            @Suppress("UNUSED_EXPRESSION")
            permissionsVersion

            val strings = stringsFor(graph.localeProvider.currentLanguage())

            LaunchedEffect(settings.mode, settings.deviceToken, settings.everCareServerUrl) {
                subscription = loadSubscription(settings)
            }

            App(
                strings = strings,
                steps = buildSetupSteps(),
                settings = settings,
                subscription = subscription,
                isHelperRunning = helperRunning,
                onSettingsChange = { updated ->
                    lifecycleScope.launch {
                        graph.settingsRepository.update { updated }
                    }
                },
                onToggleHelper = {
                    helperRunning = if (helperRunning) {
                        FloatingWindowService.stop(this)
                        false
                    } else {
                        FloatingWindowService.start(this)
                        true
                    }
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Overlay and accessibility are granted in system settings, so the only
        // moment to notice a change is when the user comes back to us.
        permissionsVersion++
    }

    private suspend fun loadSubscription(settings: AssistantSettings): SubscriptionStatus? {
        if (settings.mode != AiMode.EverCare) return null
        if (settings.deviceToken.isBlank() || settings.everCareServerUrl.isBlank()) return null

        return runCatching {
            graph.everCareClient.subscription(settings.everCareServerUrl, settings.deviceToken)
        }.getOrNull()
    }

    private fun buildSetupSteps(): List<SetupStep> {
        val strings = stringsFor(graph.localeProvider.currentLanguage())

        val steps = mutableListOf(
            SetupStep(
                title = strings.permissionOverlayTitle,
                body = strings.permissionOverlayBody,
                isDone = Settings.canDrawOverlays(this),
                onGrant = {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName"),
                        )
                    )
                },
            ),
            SetupStep(
                title = strings.permissionAccessibilityTitle,
                body = strings.permissionAccessibilityBody,
                isDone = EverCareAccessibilityService.isEnabledInSettings(this),
                onGrant = {
                    // There is no way to deep-link to one service's toggle, so
                    // this lands on the accessibility list.
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
            ),
            SetupStep(
                title = strings.permissionMicrophoneTitle,
                body = strings.permissionMicrophoneBody,
                isDone = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED,
                onGrant = { requestMicrophone.launch(Manifest.permission.RECORD_AUDIO) },
            ),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

            // The helper is a foreground service, so without this the user sees
            // no sign that it is running.
            if (!granted) {
                steps += SetupStep(
                    title = strings.permissionNotificationTitle,
                    body = strings.permissionNotificationBody,
                    isDone = false,
                    onGrant = {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                )
            }
        }

        return steps
    }
}
