package com.evercare.app

import android.app.Application
import android.content.Context
import android.os.Build
import com.evercare.app.data.AssistantEndpoint
import com.evercare.app.data.AssistantSettings
import com.evercare.app.data.ChatClient
import com.evercare.app.data.EverCareClient
import com.evercare.app.data.SettingsRepository
import com.evercare.app.data.installEverCareDefaults
import com.evercare.app.domain.AiAssistant
import com.evercare.app.domain.LocaleProvider
import com.evercare.app.domain.ScreenTextReader
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EverCareApplication : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    companion object {
        fun graphOf(context: Context): AppGraph =
            (context.applicationContext as EverCareApplication).graph
    }
}

/**
 * Hand-rolled dependency graph.
 *
 * A DI framework would earn its keep in a larger app; here there are six objects
 * and two of them are Android services that the framework constructs itself, so
 * this stays a plain class.
 */
class AppGraph(private val context: Context) {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settingsRepository: SettingsRepository = AndroidSettingsRepository(context)

    /**
     * The language override, mirrored into memory.
     *
     * [LocaleProvider.currentLanguage] is called from composition -- on every
     * recomposition of the floating panel -- so it must never touch disk. Reading
     * it straight from DataStore with `runBlocking` produced an ANR on a cold
     * start, which is what this field exists to prevent.
     */
    @Volatile
    private var languageOverride: String = ""

    init {
        applicationScope.launch {
            settingsRepository.settings.collect { languageOverride = it.languageOverride }
        }
    }

    val httpClient: HttpClient = HttpClient(OkHttp) {
        installEverCareDefaults()
    }

    val everCareClient = EverCareClient(httpClient)

    val screenTextReader: ScreenTextReader = AndroidScreenTextReader(context)

    val localeProvider: LocaleProvider = AndroidLocaleProvider(
        context = context,
        // Reads the cached value, so a language change still applies without an
        // app restart but costs nothing on the main thread.
        overrideProvider = { languageOverride },
    )

    val assistant: AiAssistant = ChatClient(
        httpClient = httpClient,
        endpointProvider = { resolveEndpoint() },
    )

    fun newSpeaker() = AndroidTextSpeaker(context)

    fun newSpeechInput() = AndroidSpeechInput(context)

    /**
     * Resolves where to send chat requests, registering this handset with the
     * EverCare server the first time if it has no token yet.
     *
     * Registration happens lazily rather than at startup so that a user who only
     * ever configures their own provider never touches our servers at all.
     */
    private suspend fun resolveEndpoint(): AssistantEndpoint? {
        val settings = settingsRepository.current()

        if (settings.mode == com.evercare.app.data.AiMode.EverCare &&
            settings.deviceToken.isBlank() &&
            settings.everCareServerUrl.isNotBlank()
        ) {
            val registration = runCatching {
                everCareClient.registerDevice(
                    serverUrl = settings.everCareServerUrl,
                    label = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    platform = "android",
                )
            }.getOrNull() ?: return settings.resolveEndpoint()

            return settingsRepository
                .update { it.copy(deviceToken = registration.token) }
                .resolveEndpoint()
        }

        return settings.resolveEndpoint()
    }

    suspend fun currentSettings(): AssistantSettings = settingsRepository.current()
}
