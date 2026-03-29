package com.quest.jellyquest

import com.meta.spatial.core.Color4
import com.meta.spatial.core.Vector3

/**
 * Room geometry and visual constants for the theater environment.
 * Pure computation — no side effects, no entity creation.
 */
object TheaterEnvironment {

    // Room sizing relative to theater preset
    const val SIDE_MARGIN = 2.0f          // meters beyond screen width, per side
    const val BACK_MARGIN = 5.0f          // meters behind furthest seat
    const val WALL_THICKNESS = 0.15f      // 15cm thick walls
    const val WALL_ANGLE_TAN = 0.07f      // tan(~4°), per side — back is wider than front

    // Screen frame (masking border)
    const val FRAME_BORDER = 0.08f        // 8cm wide border
    const val FRAME_DEPTH = 0.03f         // 3cm proud of screen wall

    // Active color scheme — swap to DEBUG_COLORS for wall orientation debugging
    var colors: RoomColors = THEATER_COLORS

    /**
     * Compute room geometry from a theater experience preset.
     * The room is a trapezoidal shape — wider at the back, narrower at the screen.
     */
    fun computeRoom(experience: TheaterExperience): RoomGeometry {
        val furthestSeat = experience.seats.maxOf { it.distanceM }

        val widthFront = experience.screenWidthM + SIDE_MARGIN * 2
        val depth = furthestSeat + BACK_MARGIN
        val widthBack = widthFront + depth * WALL_ANGLE_TAN * 2
        val ceilingHeight = experience.ceilingHeightM

        // frontDistance: screen wall is 1m past the screen position (screen sits slightly proud)
        // backDistance: how far behind the anchor the back wall sits
        val closestSeat = experience.seats.minOf { it.distanceM }
        val screenWallDist = closestSeat + experience.screenWidthM / experience.screenHeightM
        val backWallDist = furthestSeat + BACK_MARGIN

        return RoomGeometry(
            widthFront = widthFront,
            widthBack = widthBack,
            depth = depth,
            ceilingHeight = ceilingHeight,
            frontDistance = screenWallDist.coerceAtLeast(furthestSeat),
            backDistance = backWallDist,
        )
    }

    /**
     * Compute 4 border boxes for the screen frame (masking), centered at origin.
     * Caller positions them at the screen's world-space pose.
     *
     * Returns [top, bottom, left, right] boxes.
     */
    fun screenFrameBoxes(screenW: Float, screenH: Float): List<BoxDef> {
        val halfW = screenW / 2f
        val halfH = screenH / 2f
        val b = FRAME_BORDER
        val d = FRAME_DEPTH
        val frameColor = colors.screenFrame

        return listOf(
            BoxDef(min = Vector3(-halfW - b, halfH, 0f), max = Vector3(halfW + b, halfH + b, d), color = frameColor),
            BoxDef(min = Vector3(-halfW - b, -halfH - b, 0f), max = Vector3(halfW + b, -halfH, d), color = frameColor),
            BoxDef(min = Vector3(-halfW - b, -halfH, 0f), max = Vector3(-halfW, halfH, d), color = frameColor),
            BoxDef(min = Vector3(halfW, -halfH, 0f), max = Vector3(halfW + b, halfH, d), color = frameColor),
        )
    }
}

/** Color scheme for room geometry surfaces. */
data class RoomColors(
    val screenWall: Color4,
    val backWall: Color4,
    val sideWall: Color4,
    val ceiling: Color4,
    val screenFrame: Color4,
    val armrest: Color4,
)

/** Production colors — subtle dark grays, distinguishable from each other. */
val THEATER_COLORS = RoomColors(
    screenWall = Color4(0.04f, 0.04f, 0.045f, 1f),
    backWall = Color4(0.07f, 0.07f, 0.07f, 1f),
    sideWall = Color4(0.06f, 0.06f, 0.065f, 1f),
    ceiling = Color4(0.05f, 0.05f, 0.055f, 1f),
    screenFrame = Color4(0.02f, 0.02f, 0.02f, 1f),
    armrest = Color4(0.10f, 0.10f, 0.10f, 1f),
)

/** Debug colors — bright, distinct per surface for orientation verification. */
val DEBUG_COLORS = RoomColors(
    screenWall = Color4(0.8f, 0.1f, 0.1f, 1f),   // Red
    backWall = Color4(0.1f, 0.1f, 0.8f, 1f),      // Blue
    sideWall = Color4(0.1f, 0.8f, 0.1f, 1f),      // Green
    ceiling = Color4(0.8f, 0.8f, 0.8f, 1f),       // White
    screenFrame = Color4(0.8f, 0.0f, 0.8f, 1f),   // Magenta
    armrest = Color4(0.8f, 0.5f, 0.0f, 1f),        // Orange
)

/**
 * Room dimensions derived from a theater preset. All values in meters.
 */
data class RoomGeometry(
    val widthFront: Float,
    val widthBack: Float,
    val depth: Float,
    val ceilingHeight: Float,
    val frontDistance: Float,
    val backDistance: Float,
)

/**
 * A box definition with color, centered at origin. Used for screen frame pieces.
 */
data class BoxDef(
    val min: Vector3,
    val max: Vector3,
    val color: Color4,
)
