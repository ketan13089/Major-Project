package com.ketan.slam

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Push-to-talk speech input for the LLM assistant. Unlike
 * [VoiceCommandProcessor] (which maps speech directly to a NavigationIntent),
 * this class returns the raw transcript for the LLM to interpret.
 *
 *   start(onTranscript = { text -> ... }, onError = { ... }, onListening = { ... })
 *
 * Safe to call start repeatedly — the previous recognizer is destroyed first.
 */
class LlmVoiceInput(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun start(
        onTranscript: (String) -> Unit,
        onError: (String) -> Unit,
        onListening: () -> Unit
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition not available"); return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) = onListening()
                override fun onResults(bundle: Bundle?) {
                    val text = bundle
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (text.isNullOrBlank()) onError("No speech detected")
                    else onTranscript(text)
                }
                override fun onError(code: Int) = onError(errorMessage(code))
                override fun onBeginningOfSpeech() {}
                override fun onBufferReceived(p: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(p: Int, p1: Bundle?) {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onRmsChanged(p: Float) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(intent)
    }

    fun stop() { try { recognizer?.stopListening() } catch (_: Exception) {} }

    fun destroy() { try { recognizer?.destroy() } catch (_: Exception) {}; recognizer = null }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO              -> "Audio error"
        SpeechRecognizer.ERROR_CLIENT             -> "Client error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mic permission missing"
        SpeechRecognizer.ERROR_NETWORK            -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT    -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH           -> "No speech match"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY    -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER             -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT     -> "Speech timeout"
        else                                      -> "Speech error ($code)"
    }
}
