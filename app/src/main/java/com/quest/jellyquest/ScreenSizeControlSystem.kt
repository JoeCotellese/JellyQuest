package com.quest.jellyquest

import android.util.Log
import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Controller

class ScreenSizeControlSystem(
    private val onBrowseToggle: () -> Unit = {},
    private val onPlayPauseToggle: () -> Unit = {},
    private val onTheaterToggle: () -> Unit = {},
) : SystemBase() {

  companion object {
    private const val TAG = "VirtualMonitor"
  }

  // Debounce flags: prevent double-fire if changedButtons persists across frames
  private var aButtonHandled = false
  private var bButtonHandled = false
  private var xButtonHandled = false

  override fun execute() {
    val localPlayerAvatar =
        Query.where { has(AvatarBody.id) }
            .eval()
            .firstOrNull { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }
            ?: return

    val avatarBody = localPlayerAvatar.getComponent<AvatarBody>()
    val rightController = avatarBody.rightHand.tryGetComponent<Controller>() ?: return
    val leftController = avatarBody.leftHand.tryGetComponent<Controller>()

    // A button (right controller) → toggle theater picker (right panel)
    val aDown = (rightController.buttonState and ButtonBits.ButtonA) != 0
    val aChanged = (rightController.changedButtons and ButtonBits.ButtonA) != 0
    if (aDown && aChanged && !aButtonHandled) {
      aButtonHandled = true
      Log.d(TAG, "A button pressed")
      onTheaterToggle()
    } else if (!aDown && aChanged) {
      aButtonHandled = false
    }

    // B button → play/pause
    val bDown = (rightController.buttonState and ButtonBits.ButtonB) != 0
    val bChanged = (rightController.changedButtons and ButtonBits.ButtonB) != 0
    if (bDown && bChanged && !bButtonHandled) {
      bButtonHandled = true
      Log.d(TAG, "B button pressed")
      onPlayPauseToggle()
    } else if (!bDown && bChanged) {
      bButtonHandled = false
    }

    // X button (left controller) → toggle browse panel (left panel)
    leftController?.let { controller ->
      val xDown = (controller.buttonState and ButtonBits.ButtonX) != 0
      val xChanged = (controller.changedButtons and ButtonBits.ButtonX) != 0
      if (xDown && xChanged && !xButtonHandled) {
        xButtonHandled = true
        Log.d(TAG, "X button pressed")
        onBrowseToggle()
      } else if (!xDown && xChanged) {
        xButtonHandled = false
      }
    }
  }
}
