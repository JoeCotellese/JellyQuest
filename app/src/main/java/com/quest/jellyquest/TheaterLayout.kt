package com.quest.jellyquest

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3

/**
 * Pure positioning logic for world-anchored objects (theater geometry).
 * All functions are pure — take inputs, return Pose/Vector3, no side effects.
 * These objects are placed once in world space and stay fixed unless the anchor resets.
 */
object TheaterLayout {

    /** Compute the screen panel's world-space pose from anchor and screen config. */
    fun screenPose(anchor: Anchor, screen: ScreenConfig): Pose {
        val xz = anchor.position + anchor.forward * screen.distanceM
        val position = Vector3(xz.x, screen.screenCenterY, xz.z)
        return Pose(position, anchor.rotation)
    }

    /** Compute the environment origin (skybox, floor) from anchor XZ at floor level. */
    fun environmentPosition(anchor: Anchor): Vector3 {
        return Vector3(anchor.position.x, 0f, anchor.position.z)
    }
}
