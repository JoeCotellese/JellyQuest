package com.quest.jellyquest

data class TheaterExperience(
    val name: String,
    val description: String,
    val screenWidthM: Float,
    val screenHeightM: Float,
    val screenBottomM: Float = STAGE_HEIGHT,
    val ceilingHeightM: Float,
    val seats: List<SeatPosition>,
    val environmentAsset: String? = null,
) {
    val hasGlbEnvironment: Boolean get() = environmentAsset != null
}

data class SeatPosition(
    val label: String,
    val distanceM: Float,
    val riserHeightM: Float = 0f,
)

// Riser heights calculated from 12° stadium rake (tan(12°) ≈ 0.213m rise per meter).
// Front row is the baseline (0m rise); other seats rise relative to front row distance.
val THEATER_EXPERIENCES = listOf(
    TheaterExperience(
        name = "Screening Room",
        description = "Intimate indie cinema — 7m screen",
        screenWidthM = 7.0f,
        screenHeightM = 2.93f,
        ceilingHeightM = 6.5f,
        seats = listOf(
            SeatPosition("Front", 6.5f, riserHeightM = 0.0f),
            SeatPosition("Middle", 12.0f, riserHeightM = 1.17f),
            SeatPosition("Back", 18.0f, riserHeightM = 2.45f),
        ),
    ),
    TheaterExperience(
        name = "Multiplex",
        description = "Standard moviegoing — 12m screen",
        screenWidthM = 12.0f,
        screenHeightM = 5.0f,
        ceilingHeightM = 10.0f,
        seats = listOf(
            SeatPosition("Front", 11.42f, riserHeightM = 1.55f),
            SeatPosition("Middle", 14.63f, riserHeightM = 2.03f),
            SeatPosition("Back", 18.91f, riserHeightM = 2.66f),
        ),
        environmentAsset = "cinema_multiplex.glb",
    ),
    TheaterExperience(
        name = "Premium Large Format",
        description = "Dolby Cinema / premium — 16m screen",
        screenWidthM = 16.0f,
        screenHeightM = 6.69f,
        ceilingHeightM = 12.0f,
        seats = listOf(
            SeatPosition("Front", 14.0f, riserHeightM = 0.0f),
            SeatPosition("Middle", 22.0f, riserHeightM = 1.70f),
            SeatPosition("Back", 32.0f, riserHeightM = 3.83f),
        ),
    ),
    TheaterExperience(
        name = "IMAX",
        description = "Wall-to-wall immersion — 22m screen",
        screenWidthM = 22.0f,
        screenHeightM = 15.4f,
        screenBottomM = 0.9f,
        ceilingHeightM = 18.0f,
        seats = listOf(
            SeatPosition("Front", 19.5f, riserHeightM = 0.0f),
            SeatPosition("Middle", 28.0f, riserHeightM = 1.81f),
            SeatPosition("Back", 38.0f, riserHeightM = 3.94f),
        ),
    ),
)
