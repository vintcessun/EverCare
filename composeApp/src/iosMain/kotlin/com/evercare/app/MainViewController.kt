package com.evercare.app

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import com.evercare.app.data.AssistantSettings
import com.evercare.app.data.ChatClient
import com.evercare.app.data.EverCareClient
import com.evercare.app.data.installEverCareDefaults
import com.evercare.app.i18n.stringsFor
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.UIKit.UIViewController

/**
 * Entry point called from Swift.
 *
 * iOS shows the settings screen only. The floating helper that makes up the
 * Android experience cannot exist here: iOS has no third-party overlay windows
 * and no cross-app text access, so the shared panel UI has nowhere to live. See
 * [IosScreenTextReader] for what a real iOS screen-reading path would require.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val graph = remember { IosGraph() }
    val settings by graph.settingsRepository.settings.collectAsState(AssistantSettings())
    var subscription by remember { mutableStateOf<com.evercare.app.data.SubscriptionStatus?>(null) }

    App(
        strings = stringsFor(graph.localeProvider.currentLanguage()),
        // No system permission gates here yet: with no overlay and no screen
        // reading, there is nothing to ask for until speech input lands.
        steps = emptyList(),
        settings = settings,
        subscription = subscription,
        isHelperRunning = false,
        onSettingsChange = { updated ->
            graph.scope.launch { graph.settingsRepository.update { updated } }
        },
        onToggleHelper = {},
    )
}

/** The iOS counterpart of the Android `AppGraph`. */
class IosGraph {
    val scope = CoroutineScope(SupervisorJob())

    val settingsRepository = IosSettingsRepository()

    val httpClient = HttpClient(Darwin) {
        installEverCareDefaults()
    }

    val everCareClient = EverCareClient(httpClient)

    val screenTextReader = IosScreenTextReader()

    val speechInput = IosSpeechInput()

    val localeProvider = IosLocaleProvider(
        overrideProvider = { settingsRepository.currentBlocking().languageOverride },
    )

    val assistant = ChatClient(
        httpClient = httpClient,
        endpointProvider = { settingsRepository.current().resolveEndpoint() },
    )

    fun newSpeaker() = IosTextSpeaker()
}

/**
 * Reads the cached settings without suspending.
 *
 * Safe because [IosSettingsRepository] keeps its state in memory; it exists so
 * the locale provider, which is called from non-suspending Compose code, can see
 * the language override.
 */
private fun IosSettingsRepository.currentBlocking(): AssistantSettings {
    var result = AssistantSettings()
    kotlinx.coroutines.runBlocking { result = current() }
    return result
}
