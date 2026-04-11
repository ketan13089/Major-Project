package com.ketan.slam

/**
 * Data models for the Gemma semantic correction pipeline.
 * Strict typed representations of the AI response contract.
 */

/** A single cell update from the AI. */
data class SemanticCellUpdate(
    val gridX: Int,
    val gridZ: Int,
    val cellClass: CellClass,
    val confidence: Float
)

enum class CellClass {
    FLOOR, WALL, OBSTACLE, UNKNOWN;
    companion object {
        fun fromString(s: String): CellClass? = try { valueOf(s.uppercase()) } catch (_: Exception) { null }
    }
}

/** A doorway suggestion from the AI. */
data class SemanticDoorway(
    val centerX: Int,
    val centerZ: Int,
    val orientationDeg: Float,
    val widthCells: Int,
    val confidence: Float
)

/** An object update action from the AI. */
data class SemanticObjectUpdate(
    val id: String?,
    val action: ObjectAction,
    val label: String,
    val confidence: Float,
    val gridX: Int,
    val gridZ: Int,
    val affordance: ObjectAffordance?
)

enum class ObjectAction {
    CONFIRM, RELABEL, SUPPRESS, ADD;
    companion object {
        fun fromString(s: String): ObjectAction? = try { valueOf(s.uppercase()) } catch (_: Exception) { null }
    }
}

/** Complete parsed AI correction response. */
data class SemanticCorrectionResponse(
    val cellUpdates: List<SemanticCellUpdate>,
    val doorways: List<SemanticDoorway>,
    val objectUpdates: List<SemanticObjectUpdate>,
    val globalConfidence: Float
) {
    companion object {
        val EMPTY = SemanticCorrectionResponse(emptyList(), emptyList(), emptyList(), 0f)
    }
}

/** Configuration for the semantic correction system. */
object SemanticCorrectionConfig {
    /** Master kill switch — default OFF. */
    @Volatile var AI_SEMANTIC_CORRECTOR_ENABLED = false

    /** Minimum interval between correction API calls. */
    const val AI_SEMANTIC_INTERVAL_MS = 8000L

    /** HTTP timeout for API calls. */
    const val AI_SEMANTIC_TIMEOUT_MS = 15_000

    /** OpenRouter endpoint. */
    const val AI_ENDPOINT_URL = "https://openrouter.ai/api/v1/chat/completions"

    /** Model identifier. */
    const val AI_MODEL = "qwen/qwen3-next-80b-a3b-instruct:free"

    // Retry policy
    const val MAX_RETRIES = 4
    const val RETRY_BASE_DELAY_MS = 5_000L
    const val RETRY_MAX_DELAY_MS = 60_000L

    // Circuit breaker — only trips after all retries exhausted on repeated calls
    const val CIRCUIT_BREAKER_THRESHOLD = 3
    const val CIRCUIT_BREAKER_BACKOFF_MS = 45_000L
    const val CIRCUIT_BREAKER_EXTENDED_BACKOFF_MS = 90_000L

    // Confidence thresholds
    const val MIN_GLOBAL_CONFIDENCE = 0.3f
    const val MIN_CELL_CONFIDENCE = 0.4f
    const val MIN_OBJECT_CONFIDENCE = 0.5f

    // Response limits
    const val MAX_CELL_UPDATES = 50
    const val MAX_DOORWAYS = 5
    const val MAX_OBJECT_UPDATES = 10

    // Fusion parameters (log-odds deltas)
    const val FLOOR_BASE_DELTA = -0.4f
    const val WALL_BASE_DELTA = 0.6f
    const val DOOR_BASE_DELTA = -0.8f
    const val DOOR_WALL_LO_THRESHOLD = 2.0f

    // Hysteresis
    const val HYSTERESIS_APPLY_THRESHOLD = 1
    const val HYSTERESIS_FULL_THRESHOLD = 3
    const val HYSTERESIS_DECAY_INTERVAL_MS = 10_000L

    // Deduplication: skip API call if grid crop similarity exceeds this
    const val DEDUP_SIMILARITY_THRESHOLD = 0.80f

    /**
     * API key — read from BuildConfig or system property at runtime.
     * NEVER hardcode here.
     */
    @Volatile var apiKey: String = ""
}
