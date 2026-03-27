package com.quest.helloworld

import com.meta.spatial.core.SystemBase

class AnchorCaptureSystem(private val activity: HelloWorldActivity) : SystemBase() {

  override fun execute() {
    if (activity.captureAnchor()) {
      // Anchor captured — spawn the panel and remove this system
      activity.spawnPanelFromSystem()
      systemManager.unregisterSystem<AnchorCaptureSystem>()
    }
  }
}
