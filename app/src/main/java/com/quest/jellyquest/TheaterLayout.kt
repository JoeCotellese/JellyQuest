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

    /** Screen wall — behind the screen, spanning the full front width. Y=0 (floor level). */
    fun screenWallPose(anchor: Anchor, screen: ScreenConfig, room: RoomGeometry): Pose {
        val xz = anchor.position + anchor.forward * (screen.distanceM + TheaterEnvironment.WALL_THICKNESS / 2f)
        val position = Vector3(xz.x, 0f, xz.z)
        return Pose(position, anchor.rotation)
    }

    /** Back wall — behind the viewer at backDistance. Y=0 (floor level). */
    fun backWallPose(anchor: Anchor, room: RoomGeometry): Pose {
        val xz = anchor.position - anchor.forward * room.backDistance
        val position = Vector3(xz.x, 0f, xz.z)
        return Pose(position, anchor.rotation)
    }

    /** Left side wall — runs from back wall to screen wall. Y=0 (floor level). */
    fun leftWallPose(anchor: Anchor, screen: ScreenConfig, room: RoomGeometry): Pose {
        val centerForward = (screen.distanceM - room.backDistance) / 2f
        val avgHalfWidth = (room.widthFront + room.widthBack) / 4f
        val xz = anchor.position +
            anchor.forward * centerForward +
            anchor.left * avgHalfWidth
        val position = Vector3(xz.x, 0f, xz.z)
        return Pose(position, anchor.rotation)
    }

    /** Right side wall — mirror of left. Y=0 (floor level). */
    fun rightWallPose(anchor: Anchor, screen: ScreenConfig, room: RoomGeometry): Pose {
        val centerForward = (screen.distanceM - room.backDistance) / 2f
        val avgHalfWidth = (room.widthFront + room.widthBack) / 4f
        val xz = anchor.position +
            anchor.forward * centerForward -
            anchor.left * avgHalfWidth
        val position = Vector3(xz.x, 0f, xz.z)
        return Pose(position, anchor.rotation)
    }

    /** Ceiling — centered over the room. */
    fun ceilingPose(anchor: Anchor, screen: ScreenConfig, room: RoomGeometry): Pose {
        val centerForward = (screen.distanceM - room.backDistance) / 2f
        val xz = anchor.position + anchor.forward * centerForward
        val position = Vector3(xz.x, room.ceilingHeight, xz.z)
        return Pose(position, anchor.rotation)
    }

    /** Wall length from screen wall to back wall. */
    fun wallLength(screen: ScreenConfig, room: RoomGeometry): Float {
        return screen.distanceM + room.backDistance
    }
}
