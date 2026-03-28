package com.quest.jellyquest

/**
 * Compute the largest panel dimensions that fit within the screen bounds while
 * preserving the video's native aspect ratio — like motorized masking in a
 * premium theater that reshapes the screen for each film.
 *
 * @return Pair of (widthM, heightM) in world-space meters.
 */
fun fitVideoToScreen(
    screenWidthM: Float,
    screenHeightM: Float,
    videoWidth: Int,
    videoHeight: Int,
): Pair<Float, Float> {
    if (videoWidth <= 0 || videoHeight <= 0) {
        return screenWidthM to screenHeightM
    }

    val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
    val screenAspect = screenWidthM / screenHeightM

    return if (videoAspect >= screenAspect) {
        // Video is wider than screen — width-limited
        val w = screenWidthM
        val h = screenWidthM / videoAspect
        w to h
    } else {
        // Video is narrower than screen — height-limited
        val w = screenHeightM * videoAspect
        val h = screenHeightM
        w to h
    }
}
