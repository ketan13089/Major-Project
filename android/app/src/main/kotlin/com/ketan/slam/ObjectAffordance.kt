package com.ketan.slam

/**
 * Classifies how each object type affects the occupancy grid.
 * Used to fix the bug where all objects were stamped as obstacles,
 * blocking corridors for pass-through objects like doors.
 */
enum class ObjectAffordance {
    /** Doors, lift gates — do NOT stamp as obstacle. */
    PASS_THROUGH,
    /** Windows, signs on walls — stamp only the wall cell, not corridor space. */
    WALL_ATTACHED,
    /** Chairs, trash cans, purifiers — stamp full obstacle footprint. */
    FLOOR_OBSTACLE,
    /** Room labels, text signs — no collision footprint, map marker only. */
    LANDMARK_ONLY;

    companion object {
        fun forType(type: ObjectType): ObjectAffordance = when (type) {
            ObjectType.DOOR,
            ObjectType.LIFT_GATE          -> PASS_THROUGH

            ObjectType.WINDOW,
            ObjectType.NOTICE_BOARD,
            ObjectType.EXIT_SIGN,
            ObjectType.WASHROOM_SIGN,
            ObjectType.WARNING_SIGN,
            ObjectType.FACILITY_SIGN      -> WALL_ATTACHED

            ObjectType.CHAIR,
            ObjectType.TRASH_CAN,
            ObjectType.WATER_PURIFIER,
            ObjectType.FIRE_EXTINGUISHER  -> FLOOR_OBSTACLE

            ObjectType.ROOM_LABEL,
            ObjectType.TEXT_SIGN,
            ObjectType.STAIRS_SIGN        -> LANDMARK_ONLY

            ObjectType.UNKNOWN            -> FLOOR_OBSTACLE
        }
    }
}
