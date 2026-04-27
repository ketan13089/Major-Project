package com.ketan.slam

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.plugin.common.MethodChannel
import java.util.Locale

/**
 * App-wide push-to-talk voice command service.
 *
 * Channel:  com.ketan.slam/global_voice
 *
 * Flutter -> Native:
 *   "isAvailable"  -> Boolean: does the device have a SpeechRecognizer?
 *   "startListen"  -> begin a one-shot listen. Result returns immediately;
 *                     transcript / state arrives on subsequent native -> Flutter
 *                     callbacks below.
 *   "stopListen"   -> abort the current listen.
 *
 * Native -> Flutter:
 *   "onState"      -> {state: "LISTENING" | "PROCESSING" | "IDLE"}
 *   "onTranscript" -> {text: String}    — final transcript, ready to dispatch
 *   "onError"      -> {message: String}
 *
 * The matching of transcripts to commands lives in Dart so per-screen
 * vocabularies stay co-located with the rest of the UI logic.
 *
 * Distinct from [VoiceCommandProcessor] (which is destination-only and lives
 * inside ArActivity) and [LlmVoiceInput] (which is for the LLM assistant). All
 * three may exist on the same Flutter engine; only one SpeechRecognizer is
 * actively listening at a time because the OS only allows one anyway, and
 * Dart guards entry by checking "isAvailable" before starting.
 */
class GlobalVoiceService(
    private val activity: Activity,
    private val channel: MethodChannel,
) {
    companion object {
        private const val TAG = "GlobalVoiceService"
        private const val PERMISSION_REQUEST_CODE = 4172
    }

    private var recognizer: SpeechRecognizer? = null
    @Volatile private var listening = false

    fun attach() {
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "isAvailable" -> {
                    result.success(SpeechRecognizer.isRecognitionAvailable(activity))
                }
                "startListen" -> {
                    if (!hasMicPermission()) {
                        // Trigger runtime grant; Dart side gets onError so the
                        // user hears a clear "microphone permission required"
                        // message via TTS instead of a silent failure.
                        requestMicPermission()
                        emitError("Microphone permission required. Please grant it and try again.")
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    if (listening) {
                        // Already listening — ignore re-entry; Dart should
                        // debounce but we guard anyway.
                        result.success(true)
                        return@setMethodCallHandler
                    }
                    startListening()
                    result.success(true)
                }
                "stopListen" -> {
                    stopListening()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    fun destroy() {
        stopListening()
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
        channel.setMethodCallHandler(null)
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            PERMISSION_REQUEST_CODE,
        )
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            emitError("Speech recognition not available on this device.")
            return
        }
        try {
            recognizer?.destroy()
        } catch (_: Exception) {}

        recognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {
                    listening = true
                    emitState("LISTENING")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(p: Float) {}
                override fun onBufferReceived(p: ByteArray?) {}
                override fun onEndOfSpeech() {
                    emitState("PROCESSING")
                }
                override fun onResults(bundle: Bundle?) {
                    listening = false
                    val text = bundle
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    emitState("IDLE")
                    if (text.isNullOrBlank()) {
                        emitError("No speech detected")
                    } else {
                        emitTranscript(text)
                    }
                }
                override fun onPartialResults(p: Bundle?) {}
                override fun onError(code: Int) {
                    listening = false
                    emitState("IDLE")
                    emitError(errorMessage(code))
                }
                override fun onEvent(p: Int, p1: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                1500L,
            )
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "startListening failed: ${e.message}")
            emitError("Could not start listening: ${e.message}")
        }
    }

    private fun stopListening() {
        listening = false
        try { recognizer?.stopListening() } catch (_: Exception) {}
        try { recognizer?.cancel() } catch (_: Exception) {}
    }

    private fun emitState(state: String) {
        activity.runOnUiThread {
            try { channel.invokeMethod("onState", mapOf("state" to state)) } catch (_: Exception) {}
        }
    }

    private fun emitTranscript(text: String) {
        activity.runOnUiThread {
            try { channel.invokeMethod("onTranscript", mapOf("text" to text)) } catch (_: Exception) {}
        }
    }

    private fun emitError(message: String) {
        activity.runOnUiThread {
            try { channel.invokeMethod("onError", mapOf("message" to message)) } catch (_: Exception) {}
        }
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO                    -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT                   -> "Recognition client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
        SpeechRecognizer.ERROR_NETWORK                  -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT          -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH                 -> "No speech matched. Please try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY          -> "Recognizer busy. Please try again."
        SpeechRecognizer.ERROR_SERVER                   -> "Speech server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT           -> "No speech detected"
        else                                            -> "Speech error ($code)"
    }
}
