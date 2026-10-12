package io.github.lectorsys.symus.media

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the microphone is doing, in the terms the screen needs to say it.
 *
 * Separate states for listening and working because they feel completely
 * different to the person holding the phone: one is "say it now", the other is
 * "I have stopped listening, wait". A single "busy" spinner for both is how
 * voice input ends up being talked over.
 */
sealed interface VoiceState {
    data object Idle : VoiceState

    /** The microphone is open. [rms] is the live input level, 0..1. */
    data class Listening(val rms: Float = 0f, val partial: String = "") : VoiceState

    /** Microphone closed, recogniser still thinking. */
    data object Working : VoiceState

    /** A final transcription. Handed to the field to be edited, never searched blind. */
    data class Heard(val text: String) : VoiceState

    /**
     * Something went wrong, in words rather than an error code.
     *
     * [retryable] separates "say that again" from "this will not work here",
     * which decides whether the screen offers the microphone again.
     */
    data class Failed(val message: String, val retryable: Boolean) : VoiceState
}

/**
 * Speech to text, on whatever recogniser the device actually has.
 *
 * Deliberately honest about its limits rather than pretending:
 *
 * - [isAvailable] is false when no recognition service is installed, and the
 *   caller is expected to hide the microphone rather than show a button that
 *   cannot work.
 * - [onDevice] reports whether this is running locally or sending audio to a
 *   server, because that is a real difference and the user is entitled to know
 *   which one they are using.
 * - Network errors say so. A recogniser that needs a server and has no
 *   connection produces "No connection — voice search needs one on this
 *   device", not a generic failure.
 *
 * Nothing here transcribes anything itself: it drives the platform recogniser,
 * which is the only thing on the device licensed to do so.
 */
@Singleton
class VoiceSearch @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var recognizer: SpeechRecognizer? = null

    /**
     * Set while the current attempt is using the local recogniser.
     *
     * A device can advertise on-device recognition and still have no language
     * model downloaded — which is exactly what a fresh device does, and what
     * the emulator does. The first attempt prefers local; if it comes back
     * "no model for your language", [start] is retried once against the
     * network recogniser rather than declaring the feature broken.
     */
    private var usingLocal = false

    /** True once the local attempt has failed for lack of a model. */
    private var localUnavailable = false

    /** Whether this device can turn speech into text at all. */
    val isAvailable: Boolean
        get() = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }
            .getOrDefault(false)

    /**
     * Whether a local, offline recogniser is available.
     *
     * Android 12 added on-device recognition as a separate service that a
     * device may or may not ship. When it is there, no audio leaves the phone.
     */
    val onDevice: Boolean
        get() = !localUnavailable &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                .getOrDefault(false)

    /**
     * Opens the microphone.
     *
     * Must be called on the main thread — SpeechRecognizer requires it, the
     * same way MediaController does.
     */
    fun start() {
        if (!isAvailable) {
            _state.value = VoiceState.Failed(
                "No speech recognition on this device. You can still type.",
                retryable = false
            )
            return
        }
        cancel()

        usingLocal = onDevice && !localUnavailable
        val engine = runCatching {
            if (usingLocal) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } else {
                SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.getOrNull()

        if (engine == null) {
            _state.value = VoiceState.Failed("Could not open the microphone.", retryable = true)
            return
        }

        recognizer = engine
        engine.setRecognitionListener(Listener())
        _state.value = VoiceState.Listening()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // Partials are what make the sheet feel like it is listening rather
            // than frozen, and they are discarded the moment a final result
            // arrives.
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (usingLocal) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        runCatching { engine.startListening(intent) }.onFailure {
            _state.value = VoiceState.Failed("Could not start listening.", retryable = true)
        }
    }

    /** Stops listening and keeps whatever has been heard so far. */
    fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    /** Abandons the attempt entirely, releasing the microphone. */
    fun cancel() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        _state.value = VoiceState.Idle
    }

    /** Clears a result or an error once the screen has acted on it. */
    fun consume() {
        _state.value = VoiceState.Idle
    }

    /**
     * The microphone permission was refused.
     *
     * Not retryable from inside the app: once the system dialog has been
     * answered it will not be shown again, so the only honest next step is
     * Settings, and the message says so rather than offering a button that
     * does nothing.
     */
    fun denied() {
        _state.value = VoiceState.Failed(
            "Microphone access is off. Turn it on in Android Settings to use voice search.",
            retryable = false
        )
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = VoiceState.Listening()
        }

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) {
            val current = _state.value
            if (current is VoiceState.Listening) {
                // The API reports roughly -2..10 dB. Normalised here so the
                // screen can draw a level without knowing that.
                _state.value = current.copy(rms = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            if (_state.value is VoiceState.Listening) _state.value = VoiceState.Working
        }

        override fun onError(error: Int) {
            runCatching { recognizer?.destroy() }
            recognizer = null

            // "No model for your language" from the local recogniser is not
            // the end of the road if a network one is installed. Falls back
            // once, silently, because the user asked to search by voice and
            // does not care which engine answers.
            val noLocalModel = usingLocal &&
                (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            if (noLocalModel) {
                localUnavailable = true
                if (runCatching { SpeechRecognizer.isRecognitionAvailable(context) }
                        .getOrDefault(false)
                ) {
                    start()
                    return
                }
            }
            _state.value = VoiceState.Failed(messageFor(error), retryable = retryable(error))
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()

            _state.value = if (text.isEmpty()) {
                VoiceState.Failed("Did not catch that.", retryable = true)
            } else {
                VoiceState.Heard(text)
            }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }

        override fun onPartialResults(partial: Bundle?) {
            val text = partial
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            val current = _state.value
            if (current is VoiceState.Listening && text.isNotBlank()) {
                _state.value = current.copy(partial = text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /**
     * Error codes as sentences.
     *
     * The network cases are called out specifically. A recogniser that needs a
     * server is a fact about the device, and "voice search needs a connection
     * on this phone" is actionable where "error 2" is not.
     */
    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            if (onDevice) {
                "The recogniser could not be reached."
            } else {
                "No connection — voice search needs one on this device."
            }
        SpeechRecognizer.ERROR_NO_MATCH -> "Did not catch that."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Heard nothing. Try again."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be read."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is off for SYmuS."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recogniser is busy. Try again."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "This device has no speech model for your language."
        else -> "Voice search did not work."
    }

    private fun retryable(error: Int): Boolean = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> false
        else -> true
    }
}
