package com.ketan.slam

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.KeyEvent
import io.flutter.plugin.common.MethodChannel
import java.util.*

/**
 * Handles accessibility features for blind users:
 * - Text-to-speech announcements
 * - Volume button navigation interception
 * - Haptic feedback
 * 
 * Volume Button Controls:
 * - Single Volume Up: Move to previous item
 * - Single Volume Down: Move to next item
 * - Hold Volume Up (0.5s): Activate/Enter selected item
 * - Hold Volume Down (0.5s): Go back
 * - Double-tap Volume Up: Read current position
 * - Double-tap Volume Down: Open help guide
 * - Hold Both (1s): Toggle accessibility mode
 */
class AccessibilityHandler(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsChannel: MethodChannel? = null
    private var volumeChannel: MethodChannel? = null

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    // Volume button state tracking
    private var volumeUpPressTime = 0L
    private var volumeDownPressTime = 0L
    private var volumeUpReleaseTime = 0L
    private var volumeDownReleaseTime = 0L
    private var isVolumeUpLongPress = false
    private var isVolumeDownLongPress = false
    private var volumeUpTapCount = 0
    private var volumeDownTapCount = 0
    
    // Timing constants
    private val LONG_PRESS_THRESHOLD = 500L      // Hold for action
    private val DOUBLE_TAP_WINDOW = 400L         // Window for double-tap
    private val BOTH_BUTTONS_WINDOW = 200L       // Window to detect both pressed

    // Queue for TTS when not ready
    private val pendingSpeech = mutableListOf<String>()

    fun initialize() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.1f) // Slightly faster for efficiency
                tts?.setPitch(1.0f)
                ttsReady = true

                // Speak any queued messages
                pendingSpeech.forEach { speak(it, true) }
                pendingSpeech.clear()

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {}
                    override fun onError(utteranceId: String?) {}
                })
            }
        }
    }

    fun setChannels(ttsChannel: MethodChannel, volumeChannel: MethodChannel) {
        this.ttsChannel = ttsChannel
        this.volumeChannel = volumeChannel

        ttsChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "speak" -> {
                    val text = call.argument<String>("text") ?: ""
                    val interrupt = call.argument<Boolean>("interrupt") ?: true
                    speak(text, interrupt)
                    result.success(null)
                }
                "stop" -> {
                    stop()
                    result.success(null)
                }
                "setRate" -> {
                    val rate = call.argument<Double>("rate")?.toFloat() ?: 1.0f
                    tts?.setSpeechRate(rate)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }

    fun speak(text: String, interrupt: Boolean = true) {
        if (!ttsReady) {
            if (interrupt) pendingSpeech.clear()
            pendingSpeech.add(text)
            return
        }

        val mode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, mode, null, "accessibility_${System.currentTimeMillis()}")
    }

    fun stop() {
        tts?.stop()
    }

    /**
     * Handle volume key events. Returns true if the event was consumed.
     * Call this from Activity.onKeyDown/onKeyUp
     */
    fun handleKeyEvent(keyCode: Int, event: KeyEvent): Boolean {
        val now = System.currentTimeMillis()

        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            volumeUpPressTime = now
                            isVolumeUpLongPress = false
                            
                            // Check if both buttons pressed together
                            if (now - volumeDownPressTime < BOTH_BUTTONS_WINDOW && !isVolumeDownLongPress) {
                                // Both buttons pressed - will handle on release
                            }
                        } else if (!isVolumeUpLongPress && 
                            now - volumeUpPressTime > LONG_PRESS_THRESHOLD) {
                            // Long press detected - ACTIVATE/ENTER
                            isVolumeUpLongPress = true
                            volumeChannel?.invokeMethod("volumeAction", mapOf(
                                "action" to "activate",
                                "type" to "longPress"
                            ))
                            hapticFeedback(HapticType.CONFIRM)
                        }
                        return true
                    }
                    KeyEvent.ACTION_UP -> {
                        val pressDuration = now - volumeUpPressTime
                        
                        if (!isVolumeUpLongPress && pressDuration < LONG_PRESS_THRESHOLD) {
                            // Short press - check for double tap
                            if (now - volumeUpReleaseTime < DOUBLE_TAP_WINDOW) {
                                volumeUpTapCount++
                                if (volumeUpTapCount >= 2) {
                                    // Double tap - READ CURRENT POSITION
                                    volumeChannel?.invokeMethod("volumeAction", mapOf(
                                        "action" to "readPosition",
                                        "type" to "doubleTap"
                                    ))
                                    hapticFeedback(HapticType.TICK)
                                    volumeUpTapCount = 0
                                }
                            } else {
                                volumeUpTapCount = 1
                                // Delay to check if it's a single tap or start of double tap
                                android.os.Handler(context.mainLooper).postDelayed({
                                    if (volumeUpTapCount == 1) {
                                        // Single tap - PREVIOUS
                                        volumeChannel?.invokeMethod("volumeAction", mapOf(
                                            "action" to "previous",
                                            "type" to "singleTap"
                                        ))
                                        hapticFeedback(HapticType.TICK)
                                    }
                                    volumeUpTapCount = 0
                                }, DOUBLE_TAP_WINDOW)
                            }
                        }
                        volumeUpReleaseTime = now
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> {
                        if (event.repeatCount == 0) {
                            volumeDownPressTime = now
                            isVolumeDownLongPress = false
                            
                            // Check if both buttons pressed together
                            if (now - volumeUpPressTime < BOTH_BUTTONS_WINDOW && !isVolumeUpLongPress) {
                                // Both buttons pressed - will handle on long press
                            }
                        } else if (!isVolumeDownLongPress && 
                            now - volumeDownPressTime > LONG_PRESS_THRESHOLD) {
                            // Long press detected - GO BACK
                            isVolumeDownLongPress = true
                            volumeChannel?.invokeMethod("volumeAction", mapOf(
                                "action" to "goBack",
                                "type" to "longPress"
                            ))
                            hapticFeedback(HapticType.CONFIRM)
                        }
                        return true
                    }
                    KeyEvent.ACTION_UP -> {
                        val pressDuration = now - volumeDownPressTime
                        
                        if (!isVolumeDownLongPress && pressDuration < LONG_PRESS_THRESHOLD) {
                            // Short press - check for double tap
                            if (now - volumeDownReleaseTime < DOUBLE_TAP_WINDOW) {
                                volumeDownTapCount++
                                if (volumeDownTapCount >= 2) {
                                    // Double tap - OPEN HELP
                                    volumeChannel?.invokeMethod("volumeAction", mapOf(
                                        "action" to "openHelp",
                                        "type" to "doubleTap"
                                    ))
                                    hapticFeedback(HapticType.LONG_PRESS)
                                    volumeDownTapCount = 0
                                }
                            } else {
                                volumeDownTapCount = 1
                                // Delay to check if it's a single tap or start of double tap
                                android.os.Handler(context.mainLooper).postDelayed({
                                    if (volumeDownTapCount == 1) {
                                        // Single tap - NEXT
                                        volumeChannel?.invokeMethod("volumeAction", mapOf(
                                            "action" to "next",
                                            "type" to "singleTap"
                                        ))
                                        hapticFeedback(HapticType.TICK)
                                    }
                                    volumeDownTapCount = 0
                                }, DOUBLE_TAP_WINDOW)
                            }
                        }
                        volumeDownReleaseTime = now
                        return true
                    }
                }
            }
        }
        return false
    }

    enum class HapticType {
        TICK, CONFIRM, ERROR, LONG_PRESS
    }

    fun hapticFeedback(type: HapticType) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = when (type) {
                HapticType.TICK -> VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
                HapticType.CONFIRM -> VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                HapticType.ERROR -> VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1)
                HapticType.LONG_PRESS -> VibrationEffect.createOneShot(100, 200)
            }
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            when (type) {
                HapticType.TICK -> vibrator.vibrate(10)
                HapticType.CONFIRM -> vibrator.vibrate(50)
                HapticType.ERROR -> vibrator.vibrate(longArrayOf(0, 100, 50, 100), -1)
                HapticType.LONG_PRESS -> vibrator.vibrate(100)
            }
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
