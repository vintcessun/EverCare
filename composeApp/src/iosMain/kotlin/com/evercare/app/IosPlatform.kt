package com.evercare.app

import com.evercare.app.data.AiMode
import com.evercare.app.data.AssistantSettings
import com.evercare.app.data.SettingsRepository
import com.evercare.app.domain.LocaleProvider
import com.evercare.app.domain.ScreenSnapshot
import com.evercare.app.domain.ScreenTextReader
import com.evercare.app.domain.SpeechInput
import com.evercare.app.domain.SpeechResult
import com.evercare.app.domain.TextSpeaker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVSpeechBoundaryImmediate
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.preferredLanguages
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * iOS has no cross-app screen reading.
 *
 * There is no equivalent of Android's AccessibilityService for third-party apps:
 * an app cannot read the contents of another app's window. Rather than pretend,
 * this reports [ScreenTextReader.Availability.Unsupported] and the UI hides the
 * screen-reading buttons entirely.
 *
 * The realistic iOS routes, neither of which is implemented here, are a Share
 * Extension (the user shares a page to EverCare) or a screenshot run through
 * Vision OCR. Both require the user to act first, so they cannot be wired to the
 * same one-tap flow.
 */
class IosScreenTextReader : ScreenTextReader {
    override val availability = ScreenTextReader.Availability.Unsupported

    override suspend fun capture(): ScreenSnapshot = ScreenSnapshot.Empty
}

/** Text-to-speech built on AVSpeechSynthesizer. */
class IosTextSpeaker : TextSpeaker {

    private val synthesizer = AVSpeechSynthesizer()
    private val speaking = MutableStateFlow(false)
    override val isSpeaking: Flow<Boolean> = speaking.asStateFlow()

    private var completion: ((Unit) -> Unit)? = null

    private val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didFinishSpeechUtterance: AVSpeechUtterance,
        ) {
            speaking.value = false
            completion?.invoke(Unit)
            completion = null
        }

        override fun speechSynthesizer(
            synthesizer: AVSpeechSynthesizer,
            didCancelSpeechUtterance: AVSpeechUtterance,
        ) {
            speaking.value = false
            completion?.invoke(Unit)
            completion = null
        }
    }

    init {
        synthesizer.delegate = delegate
    }

    override suspend fun speak(text: String, language: String) {
        if (text.isBlank()) return

        suspendCancellableCoroutine { continuation ->
            completion = { if (!continuation.isCompleted) continuation.resume(Unit) }

            val utterance = AVSpeechUtterance.speechUtteranceWithString(text).apply {
                voice = AVSpeechSynthesisVoice.voiceWithLanguage(language)
                    ?: AVSpeechSynthesisVoice.voiceWithLanguage("en-US")
                // Slower than the default: the standard rate is hard to follow
                // for the audience this app is built for.
                rate = 0.45f
            }

            speaking.value = true
            synthesizer.speakUtterance(utterance)

            continuation.invokeOnCancellation {
                synthesizer.stopSpeakingAtBoundary(AVSpeechBoundaryImmediate)
            }
        }
    }

    override fun stop() {
        synthesizer.stopSpeakingAtBoundary(AVSpeechBoundaryImmediate)
        speaking.value = false
        completion?.invoke(Unit)
        completion = null
    }

    override fun release() = stop()
}

/**
 * Voice input is not wired up yet.
 *
 * SFSpeechRecognizer needs `NSSpeechRecognitionUsageDescription`, a microphone
 * permission prompt and an Xcode project to declare them in -- none of which can
 * be produced or verified from this build host. Reporting `false` keeps the
 * button hidden rather than showing one that fails when tapped.
 */
class IosSpeechInput : SpeechInput {
    override val isAvailable: Boolean = false

    override fun listen(language: String): Flow<SpeechResult> = flow {
        emit(SpeechResult.Failed("speech input is not implemented on iOS yet"))
    }

    override fun cancel() = Unit
}

/** Reports the phone's language, honouring a user override. */
class IosLocaleProvider(
    private val overrideProvider: () -> String = { "" },
) : LocaleProvider {

    override fun currentLanguage(): String {
        overrideProvider().takeIf { it.isNotBlank() }?.let { return it }

        val preferred = NSLocale.preferredLanguages.firstOrNull() as? String
        return preferred ?: NSLocale.currentLocale.languageCode
    }
}

/**
 * Settings backed by NSUserDefaults.
 *
 * Mirrors the Android DataStore implementation field for field, so the shared
 * code cannot tell the two platforms apart.
 */
class IosSettingsRepository : SettingsRepository {

    private val defaults = NSUserDefaults.standardUserDefaults
    private val state = MutableStateFlow(read())

    override val settings: Flow<AssistantSettings> = state.asStateFlow()

    override suspend fun current(): AssistantSettings = state.value

    override suspend fun update(
        transform: (AssistantSettings) -> AssistantSettings,
    ): AssistantSettings {
        val next = transform(state.value)
        defaults.setObject(next.mode.name, KEY_MODE)
        defaults.setObject(next.everCareServerUrl, KEY_SERVER_URL)
        defaults.setObject(next.deviceToken, KEY_DEVICE_TOKEN)
        defaults.setObject(next.customBaseUrl, KEY_CUSTOM_BASE_URL)
        defaults.setObject(next.customApiKey, KEY_CUSTOM_API_KEY)
        defaults.setObject(next.customModel, KEY_CUSTOM_MODEL)
        defaults.setObject(next.languageOverride, KEY_LANGUAGE_OVERRIDE)
        state.value = next
        return next
    }

    private fun read() = AssistantSettings(
        mode = defaults.stringForKey(KEY_MODE)
            ?.let { name -> runCatching { AiMode.valueOf(name) }.getOrNull() }
            ?: AiMode.EverCare,
        everCareServerUrl = defaults.stringForKey(KEY_SERVER_URL)
            ?: AssistantSettings.DEFAULT_EVERCARE_SERVER,
        deviceToken = defaults.stringForKey(KEY_DEVICE_TOKEN).orEmpty(),
        customBaseUrl = defaults.stringForKey(KEY_CUSTOM_BASE_URL).orEmpty(),
        customApiKey = defaults.stringForKey(KEY_CUSTOM_API_KEY).orEmpty(),
        customModel = defaults.stringForKey(KEY_CUSTOM_MODEL).orEmpty(),
        languageOverride = defaults.stringForKey(KEY_LANGUAGE_OVERRIDE).orEmpty(),
    )

    private companion object {
        const val KEY_MODE = "evercare.mode"
        const val KEY_SERVER_URL = "evercare.server_url"
        const val KEY_DEVICE_TOKEN = "evercare.device_token"
        const val KEY_CUSTOM_BASE_URL = "evercare.custom_base_url"
        const val KEY_CUSTOM_API_KEY = "evercare.custom_api_key"
        const val KEY_CUSTOM_MODEL = "evercare.custom_model"
        const val KEY_LANGUAGE_OVERRIDE = "evercare.language_override"
    }
}
