package com.quest.helloworld

import com.meta.spatial.core.Query
import com.meta.spatial.core.SystemBase
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.AvatarBody
import com.meta.spatial.toolkit.Controller

class ScreenSizeControlSystem(
    private val onSizeChange: (Int) -> Unit,
    private val onDistanceChange: (Int) -> Unit,
    private val onHeightChange: (Int) -> Unit,
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

    // Right thumbstick left/right → cycle screen size
    if (rightController.getPressed(ButtonBits.ButtonThumbRL)) {
      onSizeChange(-1)
    }
    if (rightController.getPressed(ButtonBits.ButtonThumbRR)) {
      onSizeChange(1)
    }

    // Right thumbstick up/down → cycle distance
    if (rightController.getPressed(ButtonBits.ButtonThumbRU)) {
      onDistanceChange(1)
    }
    if (rightController.getPressed(ButtonBits.ButtonThumbRD)) {
      onDistanceChange(-1)
    }

    // Left thumbstick up/down → adjust height
    leftController?.let { controller ->
      if (controller.getPressed(ButtonBits.ButtonThumbLU)) {
        onHeightChange(1)
      }
      if (controller.getPressed(ButtonBits.ButtonThumbLD)) {
        onHeightChange(-1)
      }
    }
  }
}

fun Controller.getPressed(button: Int): Boolean =
    (buttonState and changedButtons and button) != 0
