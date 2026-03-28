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
    const val CEILING_CLEARANCE = 2.0f    // meters above screen top
    const val WALL_THICKNESS = 0.15f      // 15cm thick walls
    const val WALL_ANGLE_TAN = 0.07f      // tan(~4°), per side — back is wider than front

    // Screen frame (masking border)
    const val FRAME_BORDER = 0.08f        // 8cm wide border
    const val FRAME_DEPTH = 0.03f         // 3cm proud of screen wall

    // Colors — subtle dark grays, distinguishable from each other
    val SIDE_WALL_COLOR = Color4(0.06f, 0.06f, 0.065f, 1f)
    val BACK_WALL_COLOR = Color4(0.07f, 0.07f, 0.07f, 1f)
    val SCREEN_WALL_COLOR = Color4(0.04f, 0.04f, 0.045f, 1f)
    val CEILING_COLOR = Color4(0.05f, 0.05f, 0.055f, 1f)
    val SCREEN_FRAME_COLOR = Color4(0.02f, 0.02f, 0.02f, 1f)
    val ARMREST_COLOR = Color4(0.10f, 0.10f, 0.10f, 1f)

    /**
     * Compute room geometry from a theater experience preset.
     * The room is a trapezoidal shape — wider at the back, narrower at the screen.
     */
    fun computeRoom(experience: TheaterExperience): RoomGeometry {
        val furthestSeat = experience.seats.maxOf { it.distanceM }
        val screenTop = experience.screenBottomM + experience.screenHeightM

        val widthFront = experience.screenWidthM + SIDE_MARGIN * 2
        val depth = furthestSeat + BACK_MARGIN
        val widthBack = widthFront + depth * WALL_ANGLE_TAN * 2
        val ceilingHeight = screenTop + CEILING_CLEARANCE

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

        return listOf(
            // Top bar
            BoxDef(
                min = Vector3(-halfW - b, halfH, 0f),
                max = Vector3(halfW + b, halfH + b, d),
                color = SCREEN_FRAME_COLOR,
            ),
            // Bottom bar
            BoxDef(
                min = Vector3(-halfW - b, -halfH - b, 0f),
                max = Vector3(halfW + b, -halfH, d),
                color = SCREEN_FRAME_COLOR,
            ),
            // Left bar
            BoxDef(
                min = Vector3(-halfW - b, -halfH, 0f),
                max = Vector3(-halfW, halfH, d),
                color = SCREEN_FRAME_COLOR,
            ),
            // Right bar
            BoxDef(
                min = Vector3(halfW, -halfH, 0f),
                max = Vector3(halfW + b, halfH, d),
                color = SCREEN_FRAME_COLOR,
            ),
        )
    }
}

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
