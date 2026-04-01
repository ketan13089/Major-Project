package com.ketan.slam

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

data class NavigationIntent(
    val destinationType: ObjectType,
    val qualifier: DestinationQualifier,
    val rawText: String,
    /** Room number for room-based navigation (e.g., "203", "305A"). */
    val roomNumber: String? = null,
    /** Free-text search term for text-landmark matching. */
    val textQuery: String? = null,
    /** If true, retrace the walked path back to the starting point. */
    val isRetrace: Boolean = false,
    /** If true, trigger emergency SOS flow. */
    val isEmergency: Boolean = false,
    /** If true, replay the onboarding tutorial. */
    val isTutorial: Boolean = false
)

enum class DestinationQualifier { NEAREST, FARTHEST, LEFT_MOST, RIGHT_MOST }

/**
 * Wraps Android SpeechRecognizer and converts raw transcript to a [NavigationIntent]
 * using a compact rule-based NLP pipeline.
 *
 * Example intents:
 *   "take me to the nearest door"       → Intent(DOOR, NEAREST, ...)
 *   "find the lift"                     → Intent(LIFT_GATE, NEAREST, ...)
 *   "go to the farthest fire exit"      → Intent(FIRE_EXTINGUISHER, FARTHEST, ...)
 *   "where is the water purifier"       → Intent(WATER_PURIFIER, NEAREST, ...)
 *   "navigate to the left-most window"  → Intent(WINDOW, LEFT_MOST, ...)
 */
class VoiceCommandProcessor(
    private val context: Context,
    private val onIntent: (NavigationIntent) -> Unit,
    private val onError: (String) -> Unit,
    private val onListening: () -> Unit
) {
    private var recognizer: SpeechRecognizer? = null

    fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("Speech recognition not available on this device"); return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) = onListening()
                override fun onResults(bundle: Bundle?) {
                    val results = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = results?.firstOrNull()
                        ?: run { onError("No speech detected"); return }
                    parseIntent(text)?.let(onIntent)
                        ?: onError("Could not understand: \"$text\"")
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
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        recognizer?.startListening(intent)
    }

    fun stopListening() { recognizer?.stopListening() }

    fun destroy() { recognizer?.destroy(); recognizer = null }

    // ── Rule-based NLP ────────────────────────────────────────────────────────

    internal fun parseIntent(text: String): NavigationIntent? {
        val lower = text.lowercase().trim()

        // ── Tutorial replay ─────────────────────────────────────────────────
        if (TUTORIAL_TRIGGERS.any { lower.contains(it) }) {
            return NavigationIntent(
                destinationType = ObjectType.UNKNOWN,
                qualifier = DestinationQualifier.NEAREST,
                rawText = text,
                isTutorial = true
            )
        }

        // ── Emergency / SOS commands ───────────────────────────────────────
        if (EMERGENCY_TRIGGERS.any { lower.contains(it) }) {
            return NavigationIntent(
                destinationType = ObjectType.UNKNOWN,
                qualifier = DestinationQualifier.NEAREST,
                rawText = text,
                isEmergency = true
            )
        }

        // ── Retrace / go-back commands ─────────────────────────────────────
        if (RETRACE_TRIGGERS.any { lower.contains(it) }) {
            return NavigationIntent(
                destinationType = ObjectType.UNKNOWN,
                qualifier = DestinationQualifier.NEAREST,
                rawText = text,
                isRetrace = true
            )
        }

        // Must contain a navigation trigger keyword OR a qualifier like "nearest"
        val isNavCommand = NAV_TRIGGERS.any { lower.contains(it) }
                || QUALIFIERS_NEAREST.any { lower.contains(it) }
        if (!isNavCommand) return null

        val qualifier = when {
            QUALIFIERS_FARTHEST.any { lower.contains(it) } -> DestinationQualifier.FARTHEST
            lower.contains("left")                         -> DestinationQualifier.LEFT_MOST
            lower.contains("right")                        -> DestinationQualifier.RIGHT_MOST
            else                                           -> DestinationQualifier.NEAREST
        }

        // ── Room number detection (e.g., "take me to room 203") ─────────────
        val roomMatch = ROOM_PATTERN.find(lower)
        if (roomMatch != null) {
            val roomNum = roomMatch.groupValues[1].uppercase()
            return NavigationIntent(ObjectType.ROOM_LABEL, qualifier, text, roomNumber = roomNum)
        }

        // ── OCR sign keywords (washroom, exit, stairs, etc.) ────────────────
        val ocrType = OCR_KEYWORD_MAP.entries.firstOrNull { (keywords, _) ->
            keywords.any { lower.contains(it) }
        }?.value
        if (ocrType != null) {
            return NavigationIntent(ocrType, qualifier, text, textQuery = lower)
        }

        // ── Original YOLO-based object keywords ─────────────────────────────
        val destType = KEYWORD_MAP.entries.firstOrNull { (keywords, _) ->
            keywords.any { lower.contains(it) }
        }?.value ?: return null

        return NavigationIntent(destType, qualifier, text)
    }

    companion object {
        private val NAV_TRIGGERS = listOf(
            "take me to", "go to", "navigate to", "find the", "find a",
            "where is", "guide me to", "bring me to", "show me the", "show me a"
        )
        private val TUTORIAL_TRIGGERS = listOf(
            "tutorial", "help me use", "how to use", "instructions",
            "how does this work", "teach me", "what can you do"
        )
        private val EMERGENCY_TRIGGERS = listOf(
            "help me", "emergency", "sos", "i need help",
            "call for help", "send help", "i'm lost", "i am lost"
        )
        private val RETRACE_TRIGGERS = listOf(
            "take me back", "go back", "guide me back", "retrace",
            "return to start", "go to start", "back to start",
            "bring me back", "reverse path", "way back"
        )
        private val QUALIFIERS_NEAREST  = listOf("nearest", "closest", "nearby")
        private val QUALIFIERS_FARTHEST = listOf("farthest", "furthest", "far")

        /**
         * Maps keyword lists → ObjectType.
         * First match wins, so more-specific phrases must appear earlier.
         */
        val KEYWORD_MAP: Map<List<String>, ObjectType> = mapOf(
            listOf("door", "exit", "entrance", "doorway", "way out") to ObjectType.DOOR,
            listOf("lift gate", "liftgate", "lift", "elevator")      to ObjectType.LIFT_GATE,
            listOf("fire extinguisher", "extinguisher", "fire exit") to ObjectType.FIRE_EXTINGUISHER,
            listOf("notice board", "bulletin board", "notice")       to ObjectType.NOTICE_BOARD,
            listOf("water purifier", "purifier", "water")            to ObjectType.WATER_PURIFIER,
            listOf("trash can", "dustbin", "bin", "waste bin")       to ObjectType.TRASH_CAN,
            listOf("chair", "seat", "sitting")                       to ObjectType.CHAIR,
            listOf("window")                                         to ObjectType.WINDOW
        )

        /** Pattern matching room/lab numbers in voice commands. */
        private val ROOM_PATTERN = Regex(
            """(?:room|lab|class|hall|office|cabin)\s+(\d{1,4}[A-Za-z]?)""",
            RegexOption.IGNORE_CASE
        )

        /**
         * OCR text landmark keywords → ObjectType.
         * Checked BEFORE the YOLO keyword map to give OCR signs priority.
         */
        val OCR_KEYWORD_MAP: Map<List<String>, ObjectType> = mapOf(
            listOf("washroom", "toilet", "restroom", "bathroom", "lavatory") to ObjectType.WASHROOM_SIGN,
            listOf("exit sign", "exit")                                       to ObjectType.EXIT_SIGN,
            listOf("stairs", "staircase", "stairway")                        to ObjectType.STAIRS_SIGN,
            listOf("canteen", "cafeteria", "library", "reception")           to ObjectType.FACILITY_SIGN,
        )

        private fun errorMessage(code: Int) = when (code) {
            SpeechRecognizer.ERROR_AUDIO                    -> "Audio recording error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied"
            SpeechRecognizer.ERROR_NO_MATCH                 -> "No speech matched — try again"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT           -> "No speech detected"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY          -> "Recognizer busy — try again"
            else                                            -> "Recognition error ($code)"
        }
    }
}
