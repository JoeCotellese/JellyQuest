package com.quest.jellyquest

import android.util.Log
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Controller

/**
 * Maps controller buttons to app actions.
 *
 * Left controller:
 *   X — toggle browse panel
 *   Thumbstick left/right — seek backward/forward 10s
 *
 * Right controller:
 *   A — play/pause
 *   B — stop + return to browse
 */
class ControllerInputSystem(
    private val onBrowseToggle: () -> Unit = {},
    private val onPlayPauseToggle: () -> Unit = {},
    private val onStop: () -> Unit = {},
    private val onSeekForward: () -> Unit = {},
    private val onSeekBackward: () -> Unit = {},
) : SystemBase() {

  companion object {
    private const val TAG = "VirtualMonitor"
    private const val SEEK_COOLDOWN_MS = 400L
  }

  // Debounce flags: prevent double-fire if changedButtons persists across frames
  private var aButtonHandled = false
  private var bButtonHandled = false
  private var xButtonHandled = false

  // Seek cooldown: thumbstick is continuous, so we throttle seek events
  private var lastSeekTime = 0L

  override fun execute() {
    val localPlayerAvatar =
        Query.where { has(AvatarBody.id) }
            .eval()
            .firstOrNull { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }
            ?: return

    val avatarBody = localPlayerAvatar.getComponent<AvatarBody>()
    val rightController = avatarBody.rightHand.tryGetComponent<Controller>() ?: return
    val leftController = avatarBody.leftHand.tryGetComponent<Controller>()

    // A button (right controller) → play/pause
    val aDown = (rightController.buttonState and ButtonBits.ButtonA) != 0
    val aChanged = (rightController.changedButtons and ButtonBits.ButtonA) != 0
    if (aDown && aChanged && !aButtonHandled) {
      aButtonHandled = true
      Log.d(TAG, "A button pressed → play/pause")
      onPlayPauseToggle()
    } else if (!aDown && aChanged) {
      aButtonHandled = false
    }

    // B button (right controller) → stop + return to browse
    val bDown = (rightController.buttonState and ButtonBits.ButtonB) != 0
    val bChanged = (rightController.changedButtons and ButtonBits.ButtonB) != 0
    if (bDown && bChanged && !bButtonHandled) {
      bButtonHandled = true
      Log.d(TAG, "B button pressed → stop")
      onStop()
    } else if (!bDown && bChanged) {
      bButtonHandled = false
    }

    // X button (left controller) → toggle browse panel
    leftController?.let { controller ->
      val xDown = (controller.buttonState and ButtonBits.ButtonX) != 0
      val xChanged = (controller.changedButtons and ButtonBits.ButtonX) != 0
      if (xDown && xChanged && !xButtonHandled) {
        xButtonHandled = true
        Log.d(TAG, "X button pressed → toggle browse")
        onBrowseToggle()
      } else if (!xDown && xChanged) {
        xButtonHandled = false
      }

      // Left thumbstick left/right → seek backward/forward (continuous with cooldown)
      val now = System.currentTimeMillis()
      if (now - lastSeekTime >= SEEK_COOLDOWN_MS) {
        val thumbLeft = (controller.buttonState and ButtonBits.ButtonThumbLL) != 0
        val thumbRight = (controller.buttonState and ButtonBits.ButtonThumbLR) != 0
        if (thumbLeft) {
          Log.d(TAG, "Left thumbstick left → seek backward")
          onSeekBackward()
          lastSeekTime = now
        } else if (thumbRight) {
          Log.d(TAG, "Left thumbstick right → seek forward")
          onSeekForward()
          lastSeekTime = now
        }
      }
    }
  }
}
