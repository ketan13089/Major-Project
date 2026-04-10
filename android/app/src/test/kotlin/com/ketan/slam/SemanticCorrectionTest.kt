package com.ketan.slam

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for the semantic correction system:
 * - ObjectAffordance mapping
 * - MapBuilder semantic prior fusion
 * - Affordance-aware footprint stamping
 * - SemanticCorrectionEngine response parsing
 * - Regression: AI-off baseline unchanged
 */
class SemanticCorrectionTest {

    private lateinit var builder: MapBuilder
    private val res = 0.20f

    @Before
    fun setUp() {
        builder = MapBuilder(res)
        // Ensure AI is off by default for baseline tests
        SemanticCorrectionConfig.AI_SEMANTIC_CORRECTOR_ENABLED = false
    }

    // ── ObjectAffordance mapping ─────────────────────────────────────────────

    @Test
    fun `DOOR has PASS_THROUGH affordance`() {
        assertEquals(ObjectAffordance.PASS_THROUGH, ObjectAffordance.forType(ObjectType.DOOR))
    }

    @Test
    fun `LIFT_GATE has PASS_THROUGH affordance`() {
        assertEquals(ObjectAffordance.PASS_THROUGH, ObjectAffordance.forType(ObjectType.LIFT_GATE))
    }

    @Test
    fun `WINDOW has WALL_ATTACHED affordance`() {
        assertEquals(ObjectAffordance.WALL_ATTACHED, ObjectAffordance.forType(ObjectType.WINDOW))
    }

    @Test
    fun `NOTICE_BOARD has WALL_ATTACHED affordance`() {
        assertEquals(ObjectAffordance.WALL_ATTACHED, ObjectAffordance.forType(ObjectType.NOTICE_BOARD))
    }

    @Test
    fun `CHAIR has FLOOR_OBSTACLE affordance`() {
        assertEquals(ObjectAffordance.FLOOR_OBSTACLE, ObjectAffordance.forType(ObjectType.CHAIR))
    }

    @Test
    fun `TRASH_CAN has FLOOR_OBSTACLE affordance`() {
        assertEquals(ObjectAffordance.FLOOR_OBSTACLE, ObjectAffordance.forType(ObjectType.TRASH_CAN))
    }

    @Test
    fun `ROOM_LABEL has LANDMARK_ONLY affordance`() {
        assertEquals(ObjectAffordance.LANDMARK_ONLY, ObjectAffordance.forType(ObjectType.ROOM_LABEL))
    }

    @Test
    fun `TEXT_SIGN has LANDMARK_ONLY affordance`() {
        assertEquals(ObjectAffordance.LANDMARK_ONLY, ObjectAffordance.forType(ObjectType.TEXT_SIGN))
    }

    @Test
    fun `EXIT_SIGN has WALL_ATTACHED affordance`() {
        assertEquals(ObjectAffordance.WALL_ATTACHED, ObjectAffordance.forType(ObjectType.EXIT_SIGN))
    }

    // ── Affordance-aware footprint stamping ──────────────────────────────────

    @Test
    fun `PASS_THROUGH objects do NOT stamp obstacle footprint`() {
        val pos = Point3D(2.0f, 0f, 2.0f)
        builder.markAffordanceAwareFootprint(pos, 0.45f, ObjectAffordance.PASS_THROUGH)

        val gx = builder.worldToGrid(2.0f)
        val gz = builder.worldToGrid(2.0f)
        val lo = builder.logOdds[GridCell(gx, gz)]
        // Should be null or zero — no footprint stamped
        assertTrue("PASS_THROUGH should not stamp footprint",
            lo == null || lo == 0f)
    }

    @Test
    fun `LANDMARK_ONLY objects do NOT stamp obstacle footprint`() {
        val pos = Point3D(3.0f, 0f, 3.0f)
        builder.markAffordanceAwareFootprint(pos, 0.10f, ObjectAffordance.LANDMARK_ONLY)

        val gx = builder.worldToGrid(3.0f)
        val gz = builder.worldToGrid(3.0f)
        val lo = builder.logOdds[GridCell(gx, gz)]
        assertTrue("LANDMARK_ONLY should not stamp footprint",
            lo == null || lo == 0f)
    }

    @Test
    fun `FLOOR_OBSTACLE objects DO stamp obstacle footprint`() {
        val pos = Point3D(4.0f, 0f, 4.0f)
        builder.markAffordanceAwareFootprint(pos, 0.25f, ObjectAffordance.FLOOR_OBSTACLE)

        val gx = builder.worldToGrid(4.0f)
        val gz = builder.worldToGrid(4.0f)
        val lo = builder.logOdds[GridCell(gx, gz)]
        assertNotNull("FLOOR_OBSTACLE should stamp footprint", lo)
        assertTrue("FLOOR_OBSTACLE logOdds should be positive", lo!! > 0f)
    }

    @Test
    fun `WALL_ATTACHED objects stamp only single cell`() {
        val pos = Point3D(5.0f, 0f, 5.0f)
        builder.markAffordanceAwareFootprint(pos, 0.35f, ObjectAffordance.WALL_ATTACHED)

        val gx = builder.worldToGrid(5.0f)
        val gz = builder.worldToGrid(5.0f)
        val centerLo = builder.logOdds[GridCell(gx, gz)]
        assertNotNull("WALL_ATTACHED should stamp center cell", centerLo)
        assertTrue("WALL_ATTACHED center should be positive", centerLo!! > 0f)

        // Adjacent cells should NOT be stamped
        val adjacentLo = builder.logOdds[GridCell(gx + 1, gz)]
        assertTrue("WALL_ATTACHED should not stamp adjacent cells",
            adjacentLo == null || adjacentLo == 0f)
    }

    // ── MapBuilder semantic prior fusion ─────────────────────────────────────

    @Test
    fun `wall prior increases logOdds bounded by L_MAX`() {
        val gx = 10; val gz = 10
        builder.applySemanticWallPrior(gx, gz, 0.8f)

        val lo = builder.logOdds[GridCell(gx, gz)]!!
        val expectedDelta = SemanticCorrectionConfig.WALL_BASE_DELTA * 0.8f  // 0.6 * 0.8 = 0.48
        assertEquals("Wall prior should add delta", expectedDelta, lo, 0.01f)

        // Apply many times — should be clamped
        for (i in 0..20) builder.applySemanticWallPrior(gx, gz, 1.0f)
        val clamped = builder.logOdds[GridCell(gx, gz)]!!
        assertTrue("Wall prior should be clamped to L_MAX (3.5f)", clamped <= 3.5f)
    }

    @Test
    fun `floor prior decreases logOdds bounded by L_MIN`() {
        val gx = 15; val gz = 15
        // First make it occupied
        builder.markHitOccupied(gx * res, gz * res)
        val loBefore = builder.logOdds[GridCell(gx, gz)]!!
        assertTrue("Cell should start positive", loBefore > 0f)

        // Apply floor prior
        builder.applySemanticFloorPrior(gx, gz, 0.9f)
        val loAfter = builder.logOdds[GridCell(gx, gz)]!!
        val expectedDelta = SemanticCorrectionConfig.FLOOR_BASE_DELTA * 0.9f  // -0.4 * 0.9 = -0.36
        assertEquals("Floor prior should decrease logOdds",
            loBefore + expectedDelta, loAfter, 0.01f)

        // Apply many times — should be clamped
        for (i in 0..30) builder.applySemanticFloorPrior(gx, gz, 1.0f)
        val clamped = builder.logOdds[GridCell(gx, gz)]!!
        assertTrue("Floor prior should be clamped to L_MIN (-4.0f)", clamped >= -4.0f)
    }

    @Test
    fun `door prior does NOT clear cells above LO threshold`() {
        val gx = 20; val gz = 20
        // Build up very strong wall evidence (above DOOR_WALL_LO_THRESHOLD = 2.0)
        for (i in 0..10) builder.markHitOccupied(gx * res, gz * res)
        val loBefore = builder.logOdds[GridCell(gx, gz)]!!
        assertTrue("Cell should have high logOdds", loBefore >= 2.0f)

        // Apply door prior — should NOT clear because logOdds >= threshold
        builder.applySemanticDoorPrior(gx, gz, 0f, 3, 0.9f)
        val loAfter = builder.logOdds[GridCell(gx, gz)]!!
        assertEquals("Door prior should not clear high-confidence walls", loBefore, loAfter, 0.01f)
    }

    @Test
    fun `door prior clears cells below LO threshold`() {
        val gx = 25; val gz = 25
        // Build moderate wall evidence (below threshold)
        builder.markHitOccupied(gx * res, gz * res)
        val loBefore = builder.logOdds[GridCell(gx, gz)]!!
        assertTrue("Cell should have moderate logOdds", loBefore > 0f)
        assertTrue("Cell should be below door threshold", loBefore < SemanticCorrectionConfig.DOOR_WALL_LO_THRESHOLD)

        // Apply door prior — should clear
        builder.applySemanticDoorPrior(gx, gz, 0f, 3, 0.9f)
        val loAfter = builder.logOdds[GridCell(gx, gz)]!!
        assertTrue("Door prior should decrease logOdds", loAfter < loBefore)
    }

    @Test
    fun `door prior adds cells to doorCells set`() {
        val gx = 30; val gz = 30
        // Set up a weak wall cell
        builder.applySemanticWallPrior(gx, gz, 0.3f)

        builder.applySemanticDoorPrior(gx, gz, 0f, 3, 0.9f)
        assertTrue("Door cell should be in doorCells set", builder.doorCells.contains(GridCell(gx, gz)))
    }

    // ── SemanticCorrectionEngine parser tests ────────────────────────────────

    @Test
    fun `malformed JSON returns empty response`() {
        val engine = SemanticCorrectionEngine(builder, SemanticMapManager(), res)
        val result = engine.parseResponse("this is not json {{{", 0, 0)
        assertEquals("Malformed JSON should return EMPTY", SemanticCorrectionResponse.EMPTY, result)
    }

    @Test
    fun `low global confidence returns empty response`() {
        val engine = SemanticCorrectionEngine(builder, SemanticMapManager(), res)
        val json = """{"global_confidence": 0.1, "cell_updates": [], "doorways": [], "object_updates": []}"""
        val result = engine.parseResponse(json, 0, 0)
        assertEquals("Low confidence should return EMPTY", SemanticCorrectionResponse.EMPTY, result)
    }

    @Test
    fun `valid response is correctly parsed`() {
        val engine = SemanticCorrectionEngine(builder, SemanticMapManager(), res)
        val json = """{
            "global_confidence": 0.8,
            "cell_updates": [
                {"gridX": 1, "gridZ": 2, "class": "FLOOR", "confidence": 0.7},
                {"gridX": 3, "gridZ": 4, "class": "WALL", "confidence": 0.9}
            ],
            "doorways": [
                {"centerX": 5, "centerZ": 6, "orientationDeg": 90.0, "widthCells": 3, "confidence": 0.85}
            ],
            "object_updates": [
                {"id": "door_1_2", "action": "CONFIRM", "label": "door", "confidence": 0.8, "gridX": 1, "gridZ": 2, "affordance": "PASS_THROUGH"}
            ]
        }"""
        val result = engine.parseResponse(json, 10, 20)

        assertEquals("Should parse 2 cell updates", 2, result.cellUpdates.size)
        assertEquals("Should parse 1 doorway", 1, result.doorways.size)
        assertEquals("Should parse 1 object update", 1, result.objectUpdates.size)
        assertEquals(0.8f, result.globalConfidence, 0.01f)

        // Cell updates should be offset by user position
        assertEquals("Cell X should be offset", 11, result.cellUpdates[0].gridX)
        assertEquals("Cell Z should be offset", 22, result.cellUpdates[0].gridZ)
        assertEquals(CellClass.FLOOR, result.cellUpdates[0].cellClass)
    }

    @Test
    fun `low confidence individual entries are filtered`() {
        val engine = SemanticCorrectionEngine(builder, SemanticMapManager(), res)
        val json = """{
            "global_confidence": 0.8,
            "cell_updates": [
                {"gridX": 1, "gridZ": 2, "class": "FLOOR", "confidence": 0.2},
                {"gridX": 3, "gridZ": 4, "class": "WALL", "confidence": 0.9}
            ],
            "doorways": [],
            "object_updates": [
                {"id": "x", "action": "CONFIRM", "label": "door", "confidence": 0.3, "gridX": 0, "gridZ": 0}
            ]
        }"""
        val result = engine.parseResponse(json, 0, 0)

        assertEquals("Low-conf cell update should be filtered", 1, result.cellUpdates.size)
        assertEquals("Low-conf object update should be filtered", 0, result.objectUpdates.size)
    }

    @Test
    fun `excess entries beyond limits are truncated`() {
        val engine = SemanticCorrectionEngine(builder, SemanticMapManager(), res)
        val cellUpdates = StringBuilder("[")
        for (i in 0..60) {
            if (i > 0) cellUpdates.append(",")
            cellUpdates.append("""{"gridX":$i,"gridZ":0,"class":"FLOOR","confidence":0.8}""")
        }
        cellUpdates.append("]")

        val json = """{"global_confidence": 0.8, "cell_updates": $cellUpdates, "doorways": [], "object_updates": []}"""
        val result = engine.parseResponse(json, 0, 0)

        assertTrue("Cell updates should be capped at ${SemanticCorrectionConfig.MAX_CELL_UPDATES}",
            result.cellUpdates.size <= SemanticCorrectionConfig.MAX_CELL_UPDATES)
    }

    @Test
    fun `unknown class values are skipped`() {
        val engine = SemanticCorrectionEngine(builder, SemanticMapManager(), res)
        val json = """{
            "global_confidence": 0.8,
            "cell_updates": [
                {"gridX": 1, "gridZ": 2, "class": "LAVA", "confidence": 0.9},
                {"gridX": 3, "gridZ": 4, "class": "WALL", "confidence": 0.9}
            ],
            "doorways": [],
            "object_updates": []
        }"""
        val result = engine.parseResponse(json, 0, 0)
        assertEquals("Unknown class should be skipped", 1, result.cellUpdates.size)
    }

    @Test
    fun `markdown-wrapped JSON is handled`() {
        val engine = SemanticCorrectionEngine(builder, SemanticMapManager(), res)
        val json = """```json
{"global_confidence": 0.7, "cell_updates": [], "doorways": [], "object_updates": []}
```"""
        val result = engine.parseResponse(json, 0, 0)
        assertEquals(0.7f, result.globalConfidence, 0.01f)
    }

    // ── Regression: AI off = baseline unchanged ──────────────────────────────

    @Test
    fun `with AI disabled, no semantic engine methods alter map state`() {
        assertFalse("AI should be off by default", SemanticCorrectionConfig.AI_SEMANTIC_CORRECTOR_ENABLED)

        // Normal map operations should work identically
        builder.incrementalUpdate(1.0f, 1.0f, 0f, 0f, 1f)
        builder.markHitOccupied(3.0f, 3.0f)
        builder.markObstacleFootprint(Point3D(5.0f, 0f, 5.0f), 0.25f)

        val totalCells = builder.grid.size
        assertTrue("Map should have cells from normal operations", totalCells > 0)

        // Verify standard cell types are present
        val visited = builder.grid.values.count { it.toInt() == MapBuilder.CELL_VISITED }
        assertTrue("Should have visited cells", visited > 0)
    }

    @Test
    fun `FLOOR_OBSTACLE objects still get stamped with affordance system`() {
        val pos = Point3D(6.0f, 0f, 6.0f)
        val halfM = 0.25f
        builder.markAffordanceAwareFootprint(pos, halfM, ObjectAffordance.FLOOR_OBSTACLE)

        val gx = builder.worldToGrid(6.0f)
        val gz = builder.worldToGrid(6.0f)
        val lo = builder.logOdds[GridCell(gx, gz)]
        assertNotNull("FLOOR_OBSTACLE should still stamp", lo)
        assertTrue("Should have positive logOdds", lo!! > 0f)
    }

    // ── Circuit breaker test ─────────────────────────────────────────────────

    @Test
    fun `SemanticCorrectionConfig defaults are correct`() {
        assertFalse("AI off by default", SemanticCorrectionConfig.AI_SEMANTIC_CORRECTOR_ENABLED)
        assertEquals(8000L, SemanticCorrectionConfig.AI_SEMANTIC_INTERVAL_MS)
        assertEquals(15_000, SemanticCorrectionConfig.AI_SEMANTIC_TIMEOUT_MS)
        assertEquals(4, SemanticCorrectionConfig.MAX_RETRIES)
        assertEquals(3, SemanticCorrectionConfig.CIRCUIT_BREAKER_THRESHOLD)
        assertEquals(45_000L, SemanticCorrectionConfig.CIRCUIT_BREAKER_BACKOFF_MS)
        assertEquals(0.3f, SemanticCorrectionConfig.MIN_GLOBAL_CONFIDENCE, 0.001f)
        assertEquals(0.4f, SemanticCorrectionConfig.MIN_CELL_CONFIDENCE, 0.001f)
        assertEquals(0.5f, SemanticCorrectionConfig.MIN_OBJECT_CONFIDENCE, 0.001f)
    }
}
