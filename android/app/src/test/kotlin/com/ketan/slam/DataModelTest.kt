package com.ketan.slam

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for core data models — Point3D, GridCell, SemanticObject,
 * ObjectType, BoundingBox2D, NavWaypoint.
 */
class DataModelTest {

    // ── Point3D ─────────────────────────────────────────────────────────────

    @Test
    fun `Point3D arithmetic`() {
        val a = Point3D(1f, 2f, 3f)
        val b = Point3D(4f, 5f, 6f)
        val sum = a + b
        assertEquals(5f, sum.x, 0f)
        assertEquals(7f, sum.y, 0f)
        assertEquals(9f, sum.z, 0f)
    }

    @Test
    fun `Point3D subtraction`() {
        val a = Point3D(5f, 10f, 15f)
        val b = Point3D(1f, 2f, 3f)
        val diff = a - b
        assertEquals(4f, diff.x, 0f)
        assertEquals(8f, diff.y, 0f)
        assertEquals(12f, diff.z, 0f)
    }

    @Test
    fun `Point3D scalar multiplication`() {
        val p = Point3D(2f, 3f, 4f)
        val scaled = p * 2f
        assertEquals(4f, scaled.x, 0f)
        assertEquals(6f, scaled.y, 0f)
        assertEquals(8f, scaled.z, 0f)
    }

    @Test
    fun `Point3D length`() {
        val p = Point3D(3f, 4f, 0f)
        assertEquals(5f, p.length(), 0.001f)
    }

    @Test
    fun `Point3D distance`() {
        val a = Point3D(0f, 0f, 0f)
        val b = Point3D(3f, 4f, 0f)
        assertEquals(5f, a.distance(b), 0.001f)
        assertEquals(5f, a.distanceTo(b), 0.001f)  // alias
    }

    @Test
    fun `Point3D distance to self is zero`() {
        val p = Point3D(5f, 5f, 5f)
        assertEquals(0f, p.distance(p), 0f)
    }

    // ── BoundingBox2D ───────────────────────────────────────────────────────

    @Test
    fun `BoundingBox2D dimensions`() {
        val bb = BoundingBox2D(10f, 20f, 50f, 80f)
        assertEquals(40f, bb.width, 0f)
        assertEquals(60f, bb.height, 0f)
        assertEquals(30f, bb.centerX, 0f)
        assertEquals(50f, bb.centerY, 0f)
    }

    // ── GridCell ────────────────────────────────────────────────────────────

    @Test
    fun `GridCell equality and hashing`() {
        val a = GridCell(5, 10)
        val b = GridCell(5, 10)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `GridCell inequality`() {
        assertNotEquals(GridCell(5, 10), GridCell(5, 11))
        assertNotEquals(GridCell(5, 10), GridCell(6, 10))
    }

    @Test
    fun `GridCell works as HashMap key`() {
        val map = HashMap<GridCell, Int>()
        map[GridCell(3, 7)] = 42
        assertEquals(42, map[GridCell(3, 7)])
    }

    @Test
    fun `GridCell negative coordinates`() {
        val cell = GridCell(-5, -10)
        assertEquals(-5, cell.x)
        assertEquals(-10, cell.z)
    }

    // ── ObjectType ──────────────────────────────────────────────────────────

    @Test
    fun `ObjectType fromLabel all YOLO classes`() {
        assertEquals(ObjectType.CHAIR, ObjectType.fromLabel("chair"))
        assertEquals(ObjectType.DOOR, ObjectType.fromLabel("door"))
        assertEquals(ObjectType.FIRE_EXTINGUISHER, ObjectType.fromLabel("fire_extinguisher"))
        assertEquals(ObjectType.LIFT_GATE, ObjectType.fromLabel("lift_gate"))
        assertEquals(ObjectType.NOTICE_BOARD, ObjectType.fromLabel("notice_board"))
        assertEquals(ObjectType.TRASH_CAN, ObjectType.fromLabel("trash_can"))
        assertEquals(ObjectType.WATER_PURIFIER, ObjectType.fromLabel("water_purifier"))
        assertEquals(ObjectType.WINDOW, ObjectType.fromLabel("window"))
    }

    @Test
    fun `ObjectType fromLabel OCR types`() {
        assertEquals(ObjectType.EXIT_SIGN, ObjectType.fromLabel("exit_sign"))
        assertEquals(ObjectType.WASHROOM_SIGN, ObjectType.fromLabel("washroom_sign"))
        assertEquals(ObjectType.STAIRS_SIGN, ObjectType.fromLabel("stairs_sign"))
        assertEquals(ObjectType.ROOM_LABEL, ObjectType.fromLabel("room_label"))
        assertEquals(ObjectType.FACILITY_SIGN, ObjectType.fromLabel("facility_sign"))
        assertEquals(ObjectType.WARNING_SIGN, ObjectType.fromLabel("warning_sign"))
        assertEquals(ObjectType.TEXT_SIGN, ObjectType.fromLabel("text_sign"))
    }

    @Test
    fun `ObjectType fromLabel unknown returns UNKNOWN`() {
        assertEquals(ObjectType.UNKNOWN, ObjectType.fromLabel("something_random"))
        assertEquals(ObjectType.UNKNOWN, ObjectType.fromLabel(""))
    }

    @Test
    fun `ObjectType fromLabel case insensitive`() {
        assertEquals(ObjectType.DOOR, ObjectType.fromLabel("Door"))
        assertEquals(ObjectType.CHAIR, ObjectType.fromLabel("CHAIR"))
        assertEquals(ObjectType.WINDOW, ObjectType.fromLabel("  Window  "))
    }

    @Test
    fun `ObjectType enum has 16 values`() {
        assertEquals(16, ObjectType.values().size)
    }

    // ── SemanticObject ──────────────────────────────────────────────────────

    @Test
    fun `SemanticObject isSimilarTo same type close distance`() {
        val now = System.currentTimeMillis()
        val a = makeObj("door_1", ObjectType.DOOR, 1f, 0f, 1f, now)
        val b = makeObj("door_2", ObjectType.DOOR, 1.3f, 0f, 1.1f, now)
        assertTrue("Same type within 0.5m should be similar", a.isSimilarTo(b))
    }

    @Test
    fun `SemanticObject isSimilarTo different type`() {
        val now = System.currentTimeMillis()
        val a = makeObj("door_1", ObjectType.DOOR, 1f, 0f, 1f, now)
        val b = makeObj("window_1", ObjectType.WINDOW, 1f, 0f, 1f, now)
        assertFalse("Different types should not be similar", a.isSimilarTo(b))
    }

    @Test
    fun `SemanticObject isSimilarTo far distance`() {
        val now = System.currentTimeMillis()
        val a = makeObj("door_1", ObjectType.DOOR, 0f, 0f, 0f, now)
        val b = makeObj("door_2", ObjectType.DOOR, 5f, 0f, 5f, now)
        assertFalse("Same type but far apart should not be similar", a.isSimilarTo(b))
    }

    @Test
    fun `SemanticObject mergeWith averages position`() {
        val now = System.currentTimeMillis()
        val a = makeObj("door_1", ObjectType.DOOR, 2f, 0f, 4f, now)
        val b = makeObj("door_2", ObjectType.DOOR, 4f, 0f, 6f, now)
        val merged = a.mergeWith(b)
        assertEquals(3f, merged.position.x, 0.001f)
        assertEquals(5f, merged.position.z, 0.001f)
        assertEquals(a.observations + 1, merged.observations)
    }

    @Test
    fun `SemanticObject merge takes latest lastSeen`() {
        val a = makeObj("d1", ObjectType.DOOR, 0f, 0f, 0f, 100L)
        val b = makeObj("d2", ObjectType.DOOR, 0f, 0f, 0f, 200L)
        val merged = a.mergeWith(b)
        assertEquals(200L, merged.lastSeen)
    }

    // ── NavWaypoint ─────────────────────────────────────────────────────────

    @Test
    fun `NavWaypoint world coordinates`() {
        val wp = NavWaypoint(10, 25)
        assertEquals(2.0f, wp.worldX(0.20f), 0.001f)
        assertEquals(5.0f, wp.worldZ(0.20f), 0.001f)
    }

    @Test
    fun `NavWaypoint equality`() {
        assertEquals(NavWaypoint(5, 10), NavWaypoint(5, 10))
        assertNotEquals(NavWaypoint(5, 10), NavWaypoint(5, 11))
    }

    // ── Helper ──────────────────────────────────────────────────────────────

    private fun makeObj(
        id: String, type: ObjectType,
        x: Float, y: Float, z: Float, time: Long
    ) = SemanticObject(
        id = id, type = type, category = type.name.lowercase(),
        position = Point3D(x, y, z),
        boundingBox = BoundingBox2D(0f, 0f, 1f, 1f),
        confidence = 0.8f, firstSeen = time, lastSeen = time
    )
}
