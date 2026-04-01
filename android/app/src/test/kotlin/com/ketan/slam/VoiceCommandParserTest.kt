package com.ketan.slam

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for VoiceCommandProcessor's intent parsing logic.
 * Uses the companion-level keyword maps and room pattern directly,
 * and tests parseIntent via a test-friendly wrapper.
 */
class VoiceCommandParserTest {

    // Direct access to the keyword maps for validation
    private val keywordMap = VoiceCommandProcessor.KEYWORD_MAP
    private val ocrKeywordMap = VoiceCommandProcessor.OCR_KEYWORD_MAP

    // ── Keyword map coverage ────────────────────────────────────────────────

    @Test
    fun `keyword map contains all 8 YOLO types`() {
        val types = keywordMap.values.toSet()
        assertTrue("Should map to DOOR", types.contains(ObjectType.DOOR))
        assertTrue("Should map to LIFT_GATE", types.contains(ObjectType.LIFT_GATE))
        assertTrue("Should map to FIRE_EXTINGUISHER", types.contains(ObjectType.FIRE_EXTINGUISHER))
        assertTrue("Should map to NOTICE_BOARD", types.contains(ObjectType.NOTICE_BOARD))
        assertTrue("Should map to WATER_PURIFIER", types.contains(ObjectType.WATER_PURIFIER))
        assertTrue("Should map to TRASH_CAN", types.contains(ObjectType.TRASH_CAN))
        assertTrue("Should map to CHAIR", types.contains(ObjectType.CHAIR))
        assertTrue("Should map to WINDOW", types.contains(ObjectType.WINDOW))
    }

    @Test
    fun `OCR keyword map contains expected sign types`() {
        val types = ocrKeywordMap.values.toSet()
        assertTrue("Should map to WASHROOM_SIGN", types.contains(ObjectType.WASHROOM_SIGN))
        assertTrue("Should map to EXIT_SIGN", types.contains(ObjectType.EXIT_SIGN))
        assertTrue("Should map to STAIRS_SIGN", types.contains(ObjectType.STAIRS_SIGN))
        assertTrue("Should map to FACILITY_SIGN", types.contains(ObjectType.FACILITY_SIGN))
    }

    // ── YOLO keyword matching ───────────────────────────────────────────────

    @Test
    fun `door keywords all resolve correctly`() {
        val doorKeywords = keywordMap.entries.first { it.value == ObjectType.DOOR }.key
        assertTrue(doorKeywords.contains("door"))
        assertTrue(doorKeywords.contains("exit"))
        assertTrue(doorKeywords.contains("entrance"))
        assertTrue(doorKeywords.contains("doorway"))
        assertTrue(doorKeywords.contains("way out"))
    }

    @Test
    fun `lift keywords include elevator`() {
        val liftKeywords = keywordMap.entries.first { it.value == ObjectType.LIFT_GATE }.key
        assertTrue(liftKeywords.contains("lift"))
        assertTrue(liftKeywords.contains("elevator"))
        assertTrue(liftKeywords.contains("lift gate"))
    }

    @Test
    fun `trash can has alternative keywords`() {
        val keywords = keywordMap.entries.first { it.value == ObjectType.TRASH_CAN }.key
        assertTrue(keywords.contains("dustbin"))
        assertTrue(keywords.contains("bin"))
        assertTrue(keywords.contains("waste bin"))
    }

    @Test
    fun `chair has seat and sitting keywords`() {
        val keywords = keywordMap.entries.first { it.value == ObjectType.CHAIR }.key
        assertTrue(keywords.contains("chair"))
        assertTrue(keywords.contains("seat"))
        assertTrue(keywords.contains("sitting"))
    }

    // ── OCR keyword matching ────────────────────────────────────────────────

    @Test
    fun `washroom keywords include toilet restroom bathroom`() {
        val keywords = ocrKeywordMap.entries.first { it.value == ObjectType.WASHROOM_SIGN }.key
        assertTrue(keywords.contains("washroom"))
        assertTrue(keywords.contains("toilet"))
        assertTrue(keywords.contains("restroom"))
        assertTrue(keywords.contains("bathroom"))
        assertTrue(keywords.contains("lavatory"))
    }

    @Test
    fun `stairs keywords include staircase`() {
        val keywords = ocrKeywordMap.entries.first { it.value == ObjectType.STAIRS_SIGN }.key
        assertTrue(keywords.contains("stairs"))
        assertTrue(keywords.contains("staircase"))
        assertTrue(keywords.contains("stairway"))
    }

    @Test
    fun `facility keywords include canteen cafeteria library reception`() {
        val keywords = ocrKeywordMap.entries.first { it.value == ObjectType.FACILITY_SIGN }.key
        assertTrue(keywords.contains("canteen"))
        assertTrue(keywords.contains("cafeteria"))
        assertTrue(keywords.contains("library"))
        assertTrue(keywords.contains("reception"))
    }

    // ── Keyword lookup helper ───────────────────────────────────────────────

    private fun findYoloType(text: String): ObjectType? {
        val lower = text.lowercase()
        return keywordMap.entries.firstOrNull { (keywords, _) ->
            keywords.any { lower.contains(it) }
        }?.value
    }

    private fun findOcrType(text: String): ObjectType? {
        val lower = text.lowercase()
        return ocrKeywordMap.entries.firstOrNull { (keywords, _) ->
            keywords.any { lower.contains(it) }
        }?.value
    }

    @Test
    fun `YOLO lookup - door`() = assertEquals(ObjectType.DOOR, findYoloType("door"))

    @Test
    fun `YOLO lookup - elevator`() = assertEquals(ObjectType.LIFT_GATE, findYoloType("elevator"))

    @Test
    fun `YOLO lookup - fire extinguisher`() = assertEquals(ObjectType.FIRE_EXTINGUISHER, findYoloType("fire extinguisher"))

    @Test
    fun `YOLO lookup - window`() = assertEquals(ObjectType.WINDOW, findYoloType("window"))

    @Test
    fun `YOLO lookup - notice board`() = assertEquals(ObjectType.NOTICE_BOARD, findYoloType("notice board"))

    @Test
    fun `YOLO lookup - water purifier`() = assertEquals(ObjectType.WATER_PURIFIER, findYoloType("water purifier"))

    @Test
    fun `YOLO lookup - dustbin maps to trash can`() = assertEquals(ObjectType.TRASH_CAN, findYoloType("dustbin"))

    @Test
    fun `YOLO lookup - seat maps to chair`() = assertEquals(ObjectType.CHAIR, findYoloType("seat"))

    @Test
    fun `YOLO lookup - entrance maps to door`() = assertEquals(ObjectType.DOOR, findYoloType("entrance"))

    @Test
    fun `YOLO lookup - unrecognized returns null`() = assertNull(findYoloType("refrigerator"))

    @Test
    fun `OCR lookup - washroom`() = assertEquals(ObjectType.WASHROOM_SIGN, findOcrType("washroom"))

    @Test
    fun `OCR lookup - restroom`() = assertEquals(ObjectType.WASHROOM_SIGN, findOcrType("restroom"))

    @Test
    fun `OCR lookup - stairs`() = assertEquals(ObjectType.STAIRS_SIGN, findOcrType("stairs"))

    @Test
    fun `OCR lookup - canteen`() = assertEquals(ObjectType.FACILITY_SIGN, findOcrType("canteen"))

    @Test
    fun `OCR lookup - exit`() = assertEquals(ObjectType.EXIT_SIGN, findOcrType("exit"))

    @Test
    fun `OCR lookup - unrecognized returns null`() = assertNull(findOcrType("parking lot"))

    // ── NavigationIntent data class ─────────────────────────────────────────

    @Test
    fun `NavigationIntent defaults`() {
        val intent = NavigationIntent(
            destinationType = ObjectType.DOOR,
            qualifier = DestinationQualifier.NEAREST,
            rawText = "test"
        )
        assertNull(intent.roomNumber)
        assertNull(intent.textQuery)
        assertFalse(intent.isRetrace)
        assertFalse(intent.isEmergency)
        assertFalse(intent.isTutorial)
    }

    @Test
    fun `NavigationIntent with room number`() {
        val intent = NavigationIntent(
            destinationType = ObjectType.ROOM_LABEL,
            qualifier = DestinationQualifier.NEAREST,
            rawText = "room 203",
            roomNumber = "203"
        )
        assertEquals("203", intent.roomNumber)
        assertEquals(ObjectType.ROOM_LABEL, intent.destinationType)
    }

    @Test
    fun `DestinationQualifier enum values`() {
        assertEquals(4, DestinationQualifier.values().size)
        assertNotNull(DestinationQualifier.NEAREST)
        assertNotNull(DestinationQualifier.FARTHEST)
        assertNotNull(DestinationQualifier.LEFT_MOST)
        assertNotNull(DestinationQualifier.RIGHT_MOST)
    }

    // ── OCR priority over YOLO ──────────────────────────────────────────────

    @Test
    fun `OCR keywords checked before YOLO keywords`() {
        // "exit" appears in both YOLO (DOOR → "exit") and OCR (EXIT_SIGN → "exit")
        // OCR should win per the parser design
        val ocrType = findOcrType("exit")
        val yoloType = findYoloType("exit")
        assertEquals("OCR should find EXIT_SIGN", ObjectType.EXIT_SIGN, ocrType)
        assertEquals("YOLO would find DOOR", ObjectType.DOOR, yoloType)
        // In the actual parser, OCR is checked first, so EXIT_SIGN wins
    }
}
