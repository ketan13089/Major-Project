package com.ketan.slam

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Emergency assistance for visually impaired users.
 *
 * Triggered by voice command ("help", "emergency", "SOS") or a UI button.
 * When activated:
 *
 * 1. Announces the user's current context aloud (nearby landmarks, room numbers)
 * 2. Auto-saves the current map
 * 3. Opens a share intent with a text description of the user's location
 *    (can be sent via SMS, WhatsApp, or any share target)
 */
class EmergencyManager(
    private val context: Context,
    private val announcer: NavigationGuide,
    private val mapPersistence: MapPersistence
) {

    companion object {
        private const val TAG = "Emergency"
    }

    /**
     * Trigger the emergency flow.
     *
     * @param userX        current user world X
     * @param userZ        current user world Z
     * @param semanticMap  current semantic map for context
     * @param mapBuilder   current map builder for auto-save
     * @param breadcrumbs  current breadcrumb trail for auto-save
     * @return description text that was shared
     */
    fun trigger(
        userX: Float,
        userZ: Float,
        semanticMap: SemanticMapManager,
        mapBuilder: MapBuilder,
        breadcrumbs: List<Point3D>
    ): String {
        Log.w(TAG, "Emergency triggered at (${"%.1f".format(userX)}, ${"%.1f".format(userZ)})")

        // 1. Build location description from nearby landmarks
        val description = buildLocationDescription(userX, userZ, semanticMap)

        // 2. Announce it via TTS
        announcer.speak("Emergency. $description")

        // 3. Auto-save the map
        val mapName = "emergency_${System.currentTimeMillis()}"
        val savedPath = mapPersistence.saveMap(mapName, mapBuilder, semanticMap, breadcrumbs)
        if (savedPath != null) {
            Log.d(TAG, "Emergency map saved: $savedPath")
        }

        // 4. Fire share intent
        val shareText = "EMERGENCY - Indoor Navigation Assistance Needed\n\n$description\n\n" +
                "Map reference: $mapName"
        fireShareIntent(shareText)

        return description
    }

    /**
     * Build a human-readable description of the user's surroundings.
     */
    private fun buildLocationDescription(
        userX: Float,
        userZ: Float,
        semanticMap: SemanticMapManager
    ): String {
        val nearby = semanticMap.getObjectsInRadius(
            Point3D(userX, 0f, userZ), 5.0f
        )

        val parts = mutableListOf<String>()

        // Check for room numbers
        val rooms = nearby.filter { it.roomNumber != null }
            .sortedBy { it.position.distance(Point3D(userX, 0f, userZ)) }
        if (rooms.isNotEmpty()) {
            val nearest = rooms.first()
            parts.add("Near room ${nearest.roomNumber}")
        }

        // Describe nearby landmarks
        val landmarks = nearby.filter { it.type != ObjectType.UNKNOWN && it.roomNumber == null }
            .sortedBy { it.position.distance(Point3D(userX, 0f, userZ)) }
            .take(3)

        if (landmarks.isNotEmpty()) {
            val landmarkDesc = landmarks.joinToString(", ") { obj ->
                val dist = obj.position.distance(Point3D(userX, 0f, userZ))
                "${obj.category.replace('_', ' ')} ${"%.0f".format(dist)} metres away"
            }
            parts.add("Nearby: $landmarkDesc")
        }

        // Check for signs
        val signs = nearby.filter { it.textContent != null }
            .sortedBy { it.position.distance(Point3D(userX, 0f, userZ)) }
        if (signs.isNotEmpty()) {
            parts.add("Signs: ${signs.joinToString(", ") { it.textContent ?: "" }}")
        }

        return if (parts.isNotEmpty()) {
            parts.joinToString(". ") + "."
        } else {
            "I need help. I am indoors but my exact location is unknown. I am using an indoor navigation app."
        }
    }

    private fun fireShareIntent(text: String) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                putExtra(Intent.EXTRA_SUBJECT, "Emergency - Location Assistance")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Send emergency location").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fire share intent: ${e.message}")
            announcer.speak("Could not open sharing. Please ask someone nearby for help.")
        }
    }
}
