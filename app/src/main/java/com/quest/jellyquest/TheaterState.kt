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

/** Default screen: PLF middle seat. */
val DEFAULT_SCREEN = ScreenConfig(
    label = "Premium Large Format",
    widthM = 16.0f,
    heightM = 6.69f,
    distanceM = 22.0f,
)

/**
 * Current theater configuration. Single source of truth for screen + seat + room state.
 * Updated when user selects a theater preset or changes seats.
 */
data class TheaterState(
    val screen: ScreenConfig = DEFAULT_SCREEN,
    val riserHeightM: Float = 0f,
    val room: RoomGeometry = TheaterEnvironment.computeRoom(THEATER_EXPERIENCES[2]),
)
