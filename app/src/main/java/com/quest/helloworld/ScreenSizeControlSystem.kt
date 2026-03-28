package com.quest.helloworld

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

  override fun execute() {
    val localPlayerAvatar =
        Query.where { has(AvatarBody.id) }
            .eval()
            .firstOrNull { it.isLocal() && it.getComponent<AvatarBody>().isPlayerControlled }
            ?: return

    val avatarBody = localPlayerAvatar.getComponent<AvatarBody>()
    val rightController = avatarBody.rightHand.tryGetComponent<Controller>() ?: return
    val leftController = avatarBody.leftHand.tryGetComponent<Controller>()

    // A button → toggle browse panel
    if (rightController.getPressed(ButtonBits.ButtonA)) {
      onBrowseToggle()
    }

    // B button → play/pause
    if (rightController.getPressed(ButtonBits.ButtonB)) {
      onPlayPauseToggle()
    }

    // X button (left controller) → toggle theater picker
    leftController?.let { controller ->
      if (controller.getPressed(ButtonBits.ButtonX)) {
        onTheaterToggle()
      }
    }
  }
}

fun Controller.getPressed(button: Int): Boolean =
    (buttonState and changedButtons and button) != 0
