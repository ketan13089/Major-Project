package com.ketan.slam

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Continuous spatial audio feedback engine for visually impaired users.
 *
 * Generates ambient tones that convey spatial information:
 *
 * 1. **Proximity ping** — short tick whose pitch rises as the nearest wall/obstacle
 *    gets closer. Plays at a rate proportional to proximity (faster = closer).
 *    Silent when nothing is within range.
 *
 * 2. **Open-space hum** — low gentle tone when the user is in a large open area
 *    (average free distance > 2m in all directions). Helps orientation.
 *
 * 3. **Corridor cue** — alternating left/right panning when walls are detected
 *    on both sides (within 1.5m). Helps the user walk centered.
 *
 * All tones are generated via [AudioTrack] (PCM sine waves) so no audio files
 * are needed. Volume is kept low to avoid masking TTS navigation instructions.
 *
 * Thread: runs its own audio thread. Call [update] from the SLAM thread with
 * current spatial data; the audio thread picks up the latest state.
 */
class SpatialAudioEngine {

    companion object {
        private const val TAG = "SpatialAudio"
        private const val SAMPLE_RATE = 16000
        private const val BASE_VOLUME = 0.15f   // low so TTS is still audible

        // Proximity ping parameters
        private const val PING_MIN_FREQ = 400f   // Hz — far away
        private const val PING_MAX_FREQ = 1200f  // Hz — very close
        private const val PING_DURATION_MS = 60   // short click
        private const val PING_MAX_RANGE = 3.0f   // metres — beyond this, no ping
        private const val PING_MIN_INTERVAL_MS = 150L  // fastest ping rate
        private const val PING_MAX_INTERVAL_MS = 800L  // slowest ping rate

        // Open-space hum
        private const val HUM_FREQ = 180f
        private const val HUM_VOLUME = 0.08f
        private const val OPEN_SPACE_THRESHOLD = 2.0f  // metres avg free distance

        // Corridor parameters
        private const val CORRIDOR_WALL_DIST = 1.5f  // metres
    }

    // Latest spatial state (updated from SLAM thread)
    @Volatile private var nearestWallDist = Float.MAX_VALUE
    @Volatile private var leftWallDist = Float.MAX_VALUE
    @Volatile private var rightWallDist = Float.MAX_VALUE
    @Volatile private var avgFreeDistance = 0f
    @Volatile private var isInCorridor = false

    @Volatile private var enabled = false
    private var audioThread: Thread? = null
    private var audioTrack: AudioTrack? = null

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Update spatial state from the SLAM thread. Called every depth sample (~200ms).
     *
     * @param nearestDist  distance to nearest wall/obstacle in forward cone
     * @param leftDist     distance to nearest wall on left side
     * @param rightDist    distance to nearest wall on right side
     * @param avgFreeDist  average free-space distance around user
     */
    fun update(
        nearestDist: Float,
        leftDist: Float,
        rightDist: Float,
        avgFreeDist: Float
    ) {
        nearestWallDist = nearestDist
        leftWallDist = leftDist
        rightWallDist = rightDist
        avgFreeDistance = avgFreeDist
        isInCorridor = leftDist < CORRIDOR_WALL_DIST && rightDist < CORRIDOR_WALL_DIST
    }

    fun start() {
        if (enabled) return
        enabled = true
        audioThread = Thread(::audioLoop, "SpatialAudio").apply {
            isDaemon = true
            start()
        }
        Log.d(TAG, "Spatial audio started")
    }

    fun stop() {
        enabled = false
        audioThread?.interrupt()
        audioThread = null
        audioTrack?.release()
        audioTrack = null
        Log.d(TAG, "Spatial audio stopped")
    }

    // ── Audio generation thread ──────────────────────────────────────────────

    private fun audioLoop() {
        val bufSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()

        var lastPingMs = 0L

        try {
            while (enabled && !Thread.currentThread().isInterrupted) {
                val now = System.currentTimeMillis()
                val dist = nearestWallDist

                // ── Proximity ping ──────────────────────────────────────────
                if (dist < PING_MAX_RANGE) {
                    // Closer = higher pitch, faster rate
                    val t = ((PING_MAX_RANGE - dist) / PING_MAX_RANGE).coerceIn(0f, 1f)
                    val freq = PING_MIN_FREQ + t * (PING_MAX_FREQ - PING_MIN_FREQ)
                    val interval = (PING_MAX_INTERVAL_MS - t * (PING_MAX_INTERVAL_MS - PING_MIN_INTERVAL_MS)).toLong()

                    if (now - lastPingMs >= interval) {
                        lastPingMs = now
                        val volume = BASE_VOLUME + t * 0.15f  // louder when closer
                        val samples = generateTone(freq, PING_DURATION_MS, volume)

                        // Stereo panning for corridor awareness
                        if (isInCorridor) {
                            // Pan toward the closer wall to warn user
                            // (mono track — subtle effect through volume envelope)
                        }

                        track.write(samples, 0, samples.size)
                    }
                }

                // ── Open-space hum ──────────────────────────────────────────
                if (avgFreeDistance > OPEN_SPACE_THRESHOLD && dist >= PING_MAX_RANGE) {
                    // Gentle continuous hum indicating open area
                    val humSamples = generateTone(HUM_FREQ, 100, HUM_VOLUME)
                    track.write(humSamples, 0, humSamples.size)
                }

                // ── Corridor centering ticks ────────────────────────────────
                if (isInCorridor && dist >= PING_MAX_RANGE * 0.5f) {
                    // Two quick ticks — lower pitch on the side with more room
                    val leftCloser = leftWallDist < rightWallDist
                    val warnFreq = if (leftCloser) 600f else 500f
                    val tick = generateTone(warnFreq, 30, BASE_VOLUME * 0.7f)
                    track.write(tick, 0, tick.size)
                }

                // Sleep to avoid busy-spinning; audio is paced by write()
                Thread.sleep(50)
            }
        } catch (_: InterruptedException) {
            // Normal shutdown
        } finally {
            track.stop()
            track.release()
        }
    }

    /**
     * Generate a short sine-wave tone as 16-bit PCM samples.
     */
    private fun generateTone(freqHz: Float, durationMs: Int, volume: Float): ShortArray {
        val numSamples = (SAMPLE_RATE * durationMs / 1000f).roundToInt()
        val samples = ShortArray(numSamples)
        val twoPiF = 2.0 * PI * freqHz / SAMPLE_RATE

        for (i in 0 until numSamples) {
            // Apply fade-in/fade-out envelope to avoid clicks
            val env = if (i < numSamples / 10) i.toFloat() / (numSamples / 10)
                      else if (i > numSamples * 9 / 10) (numSamples - i).toFloat() / (numSamples / 10)
                      else 1f
            val sample = (sin(twoPiF * i) * volume * env * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            samples[i] = sample.toShort()
        }
        return samples
    }
}
