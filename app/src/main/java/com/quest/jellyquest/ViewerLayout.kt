package com.quest.jellyquest

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3

/**
 * Pure positioning logic for viewer-relative objects (control panels).
 * These objects follow the user as they move or change elevation.
 *
 * Uses anchor for XZ direction (panel is always left of screen, not left of gaze)
 * but riser height for Y (follows seat elevation).
 * When free movement is added, XZ will use viewer position instead.
 */
object ViewerLayout {

    const val SEATED_EYE_HEIGHT = 1.1f

    // Browse panel placement relative to viewer
    const val BROWSE_FORWARD = 0.6f
    const val BROWSE_LEFT = 0.4f
    const val BROWSE_BELOW_EYE = 0.2f
    const val BROWSE_TILT_DEG = 15f

    /**
     * Position the browse panel to the left of the screen direction,
     * at the viewer's current seated eye height (including riser).
     */
    fun browsePanelPose(anchor: Anchor, riserHeightM: Float): Pose {
        val xz = anchor.position +
            anchor.forward * BROWSE_FORWARD +
            anchor.left * BROWSE_LEFT
        val position = Vector3(xz.x, SEATED_EYE_HEIGHT + riserHeightM - BROWSE_BELOW_EYE, xz.z)

        // Face toward anchor position (not current gaze) with tablet tilt
        val dx = position.x - anchor.position.x
        val dz = position.z - anchor.position.z
        val yawDeg = Math.toDegrees(Math.atan2(dx.toDouble(), dz.toDouble())).toFloat()
        return Pose(position, Quaternion(BROWSE_TILT_DEG, yawDeg, 0f))
    }
}
