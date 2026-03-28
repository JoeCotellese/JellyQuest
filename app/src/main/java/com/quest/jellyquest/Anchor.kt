package com.quest.jellyquest

import android.util.Log
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Query
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Transform

/**
 * Immutable snapshot of the user's position and facing direction.
 * Captured at startup and on recenter. Y is always 0 (floor level).
 * The anchor defines the theater's origin — where the user "sits."
 */
data class Anchor(
    val position: Vector3,    // XZ of user at floor level
    val forward: Vector3,     // horizontal forward direction (toward screen)
    val rotation: Quaternion, // lookRotationAroundY(forward)
) {
    /** Direction to the left of forward (in XZ plane). */
    val left: Vector3 get() = Vector3(-forward.z, 0f, forward.x).normalize()

    /** Direction to the right of forward (in XZ plane). */
    val right: Vector3 get() = Vector3(forward.z, 0f, -forward.x).normalize()

    companion object {
        private const val TAG = "VirtualMonitor"

        /**
         * Attempt to capture from current head tracking.
         * Returns null if head tracking isn't ready yet (pose is null or default).
         */
        fun capture(): Anchor? {
            val headPose =
                Query.where { has(AvatarAttachment.id) }
                    .eval()
                    .filter { it.isLocal() && it.getComponent<AvatarAttachment>().type == "head" }
                    .firstOrNull()
                    ?.getComponent<Transform>()
                    ?.transform

            if (headPose == null || headPose == Pose()) {
                Log.d(TAG, "Anchor.capture: headPose=${headPose?.t} (null=${headPose == null}, default=${headPose == Pose()})")
                return null
            }

            val position = Vector3(headPose.t.x, 0f, headPose.t.z)
            // SDK: +Z is forward. Pose.forward() = headPose.q * Vector3(0,0,1)
            val forward = headPose.forward()
            forward.y = 0f
            val normalizedForward = forward.normalize()
            val rotation = Quaternion.lookRotationAroundY(normalizedForward)

            Log.i(TAG, "Anchor captured: pos=$position fwd=$normalizedForward rot=$rotation headPose=${headPose.t}")
            return Anchor(position, normalizedForward, rotation)
        }
    }
}
