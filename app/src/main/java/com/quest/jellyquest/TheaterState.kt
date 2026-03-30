package com.quest.jellyquest

/** Height of the virtual stage where the screen bottom sits, in meters. */
const val STAGE_HEIGHT = 1.0f

/**
 * Screen dimensions and position. Self-contained — no index references.
 * screenBottomM defaults to STAGE_HEIGHT (real theater convention).
 */
data class ScreenConfig(
    val label: String,
    val widthM: Float,
    val heightM: Float,
    val distanceM: Float,
    val screenBottomM: Float = STAGE_HEIGHT,
) {
    val screenCenterY: Float get() = screenBottomM + (heightM / 2f)
}

/** Default theater preset and seat, used at startup before user selects. */
private val DEFAULT_EXPERIENCE = THEATER_EXPERIENCES.first { it.name == "Multiplex" }
private val DEFAULT_SEAT = DEFAULT_EXPERIENCE.seats.first { it.label == "Middle" }

val DEFAULT_SCREEN = ScreenConfig(
    label = DEFAULT_EXPERIENCE.name,
    widthM = DEFAULT_EXPERIENCE.screenWidthM,
    heightM = DEFAULT_EXPERIENCE.screenHeightM,
    distanceM = DEFAULT_SEAT.distanceM,
    screenBottomM = DEFAULT_EXPERIENCE.screenBottomM,
)

/**
 * Current theater configuration. Single source of truth for screen + seat + room state.
 * Updated when user selects a theater preset or changes seats.
 */
data class TheaterState(
    val screen: ScreenConfig = DEFAULT_SCREEN,
    val riserHeightM: Float = DEFAULT_SEAT.riserHeightM,
    val room: RoomGeometry = TheaterEnvironment.computeRoom(DEFAULT_EXPERIENCE),
)
