package com.evercare.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.evercare.app.domain.LocaleProvider
import com.evercare.app.domain.SpeechInput
import com.evercare.app.domain.SpeechResult
import com.evercare.app.domain.TextSpeaker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Text-to-speech built on the platform engine.
 *
 * The voice follows the language tag passed in on every call rather than being
 * fixed at construction: a Chinese-speaking user reading an English screen
 * should still hear Chinese from the assistant.
 */
class AndroidTextSpeaker(context: Context) : TextSpeaker {

    private val speaking = MutableStateFlow(false)
    override val isSpeaking: Flow<Boolean> = speaking

    private val ready = CompletableDeferred<Boolean>()
    private val utteranceCounter = AtomicLong(0)
    private val pending = mutableMapOf<String, CompletableDeferred<Unit>>()

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                speaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                finish(utteranceId)
            }

            @Deprecated("Required by the platform interface")
            override fun onError(utteranceId: String?) {
                finish(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                finish(utteranceId)
            }

            private fun finish(utteranceId: String?) {
                speaking.value = false
                utteranceId?.let { id ->
                    synchronized(pending) { pending.remove(id) }?.complete(Unit)
                }
            }
        })
    }

    /**
     * Speaks [text] and suspends until it has finished.
     *
     * Suspending until done is what lets the caller feed sentences in one at a
     * time while the model is still streaming, without them overlapping.
     */
    override suspend fun speak(text: String, language: String) {
        if (text.isBlank()) return
        if (!ready.await()) return

        tts.language = language.toLocale()

        val utteranceId = "evercare-${utteranceCounter.incrementAndGet()}"
        val completion = CompletableDeferred<Unit>()
        synchronized(pending) { pending[utteranceId] = completion }

        // QUEUE_ADD, not QUEUE_FLUSH: consecutive sentences of one answer must
        // not cut each other off.
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            synchronized(pending) { pending.remove(utteranceId) }
            return
        }

        completion.await()
    }

    override fun stop() {
        tts.stop()
        speaking.value = false
        synchronized(pending) {
            pending.values.forEach { it.complete(Unit) }
            pending.clear()
        }
    }

    override fun release() {
        stop()
        tts.shutdown()
    }
}

/**
 * One-shot voice input built on the platform recogniser.
 *
 * Partial results are forwarded as they arrive: seeing their own words appear is
 * what tells an elderly user that the phone is actually listening, which is the
 * single most common point of confusion with voice input.
 */
class AndroidSpeechInput(private val context: Context) : SpeechInput {

    override val isAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    private var recognizer: SpeechRecognizer? = null

    override fun listen(language: String): Flow<SpeechResult> = callbackFlow {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            trySend(SpeechResult.Failed("recognition unavailable"))
            close()
            return@callbackFlow
        }

        val speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speechRecognizer

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                partialResults.firstTranscript()?.let { trySend(SpeechResult.Partial(it)) }
            }

            override fun onResults(results: Bundle?) {
                val transcript = results.firstTranscript()
                if (transcript.isNullOrBlank()) {
                    trySend(SpeechResult.Failed("no speech recognised"))
                } else {
                    trySend(SpeechResult.Final(transcript))
                }
                close()
            }

            override fun onError(error: Int) {
                trySend(SpeechResult.Failed(error.describeRecognitionError()))
                close()
            }
        }

        speechRecognizer.setRecognitionListener(listener)
        speechRecognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                // Older speakers pause mid-sentence; the default silence timeout
                // cuts them off before they have finished asking.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    2500L,
                )
            }
        )

        awaitClose {
            speechRecognizer.cancel()
            speechRecognizer.destroy()
            if (recognizer === speechRecognizer) recognizer = null
        }
    }

    override fun cancel() {
        recognizer?.cancel()
    }
}

/** Reports the phone's current language, honouring a user override. */
class AndroidLocaleProvider(
    private val context: Context,
    private val overrideProvider: () -> String = { "" },
) : LocaleProvider {

    override fun currentLanguage(): String {
        overrideProvider().takeIf { it.isNotBlank() }?.let { return it }

        val locales = context.resources.configuration.locales
        val locale = if (locales.isEmpty) Locale.getDefault() else locales[0]
        return locale.toLanguageTag()
    }
}

private fun Bundle?.firstTranscript(): String? =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

private fun String.toLocale(): Locale = Locale.forLanguageTag(this)

private fun Int.describeRecognitionError(): String = when (this) {
    SpeechRecognizer.ERROR_AUDIO -> "audio error"
    SpeechRecognizer.ERROR_CLIENT -> "client error"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission denied"
    SpeechRecognizer.ERROR_NETWORK -> "network error"
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
    SpeechRecognizer.ERROR_NO_MATCH -> "no speech recognised"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recogniser busy"
    SpeechRecognizer.ERROR_SERVER -> "server error"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
    else -> "speech error $this"
}
