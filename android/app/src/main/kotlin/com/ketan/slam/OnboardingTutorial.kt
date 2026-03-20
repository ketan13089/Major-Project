package com.ketan.slam

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Voice-guided onboarding tutorial for visually impaired users.
 *
 * Walks the user through each step via TTS with pauses between steps.
 * Tracks completion in SharedPreferences so it only auto-plays on first launch.
 * Can be replayed via voice command ("tutorial", "help me use this app").
 *
 * Steps:
 * 1. Welcome + app purpose
 * 2. How to hold the phone
 * 3. Scanning instructions
 * 4. Voice command examples
 * 5. Obstacle warnings explanation
 * 6. Emergency help info
 * 7. Completion
 */
class OnboardingTutorial(
    private val context: Context,
    private val announcer: NavigationGuide
) {
    companion object {
        private const val TAG = "Onboarding"
        private const val PREFS_NAME = "slam_onboarding"
        private const val KEY_COMPLETED = "tutorial_completed"
        private const val STEP_DELAY_MS = 1500L  // pause between steps
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val steps = listOf(
        "Welcome to Indoor Navigator. " +
        "This app helps you navigate indoor spaces safely. " +
        "I will guide you through how to use it.",

        "Hold your phone upright in front of you, with the camera facing forward. " +
        "The app uses the camera to see walls, doors, and obstacles around you.",

        "To build a map of your surroundings, walk slowly and steadily. " +
        "Pan the phone left and right as you walk so I can see more of the space. " +
        "The map builds automatically as you move.",

        "You can give me voice commands. Tap the microphone button, then say things like: " +
        "Take me to the nearest door. " +
        "Find the washroom. " +
        "Go to room 203. " +
        "Guide me back to the start.",

        "I will warn you about obstacles. " +
        "You will hear a tone that gets faster and higher as you get closer to walls or objects. " +
        "If something is very close, I will vibrate your phone and say obstacle ahead. " +
        "I will also warn you about stairs and drop-offs.",

        "In an emergency, say help me or emergency. " +
        "I will describe your location and help you share it with someone. " +
        "You can also use the emergency button on screen.",

        "You are all set. The app is now scanning your surroundings. " +
        "Walk around slowly to build the map, then use voice commands to navigate. " +
        "Say tutorial anytime to hear these instructions again."
    )

    @Volatile private var isPlaying = false
    @Volatile private var currentStep = 0
    private var tutorialThread: Thread? = null

    /** True if the tutorial has been completed at least once. */
    val isCompleted: Boolean get() = prefs.getBoolean(KEY_COMPLETED, false)

    /** True if tutorial should auto-play (first launch). */
    val shouldAutoPlay: Boolean get() = !isCompleted

    /**
     * Start playing the tutorial. Non-blocking — runs on a background thread.
     * Each step is spoken via TTS with pauses between.
     */
    fun play() {
        if (isPlaying) return
        isPlaying = true
        currentStep = 0
        Log.d(TAG, "Starting tutorial")

        tutorialThread = Thread({
            try {
                for ((i, step) in steps.withIndex()) {
                    if (!isPlaying) break
                    currentStep = i
                    Log.d(TAG, "Step ${i + 1}/${steps.size}")
                    announcer.speak(step)
                    // Wait for TTS to finish + pause
                    // Estimate: ~100ms per word + STEP_DELAY_MS
                    val wordCount = step.split(" ").size
                    val estimatedMs = wordCount * 100L + STEP_DELAY_MS
                    Thread.sleep(estimatedMs)
                }
                if (isPlaying) {
                    markCompleted()
                    Log.d(TAG, "Tutorial completed")
                }
            } catch (_: InterruptedException) {
                Log.d(TAG, "Tutorial interrupted")
            } finally {
                isPlaying = false
            }
        }, "OnboardingTutorial").apply {
            isDaemon = true
            start()
        }
    }

    /** Stop the tutorial if it's playing. */
    fun stop() {
        isPlaying = false
        tutorialThread?.interrupt()
        tutorialThread = null
    }

    /** Skip to the end and mark as completed. */
    fun skip() {
        stop()
        markCompleted()
        announcer.speak("Tutorial skipped. Say tutorial anytime to hear it again.")
    }

    /** Reset so tutorial plays again on next launch. */
    fun reset() {
        prefs.edit().remove(KEY_COMPLETED).apply()
    }

    private fun markCompleted() {
        prefs.edit().putBoolean(KEY_COMPLETED, true).apply()
    }
}
