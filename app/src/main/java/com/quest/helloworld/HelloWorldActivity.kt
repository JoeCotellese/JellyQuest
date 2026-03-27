package com.quest.helloworld

import android.os.Bundle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.castinputforward.CastInputForwardFeature
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.datamodelinspector.DataModelInspectorFeature
import com.meta.spatial.debugtools.HotReloadFeature
import com.meta.spatial.ovrmetrics.OVRMetricsDataModel
import com.meta.spatial.ovrmetrics.OVRMetricsFeature
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.VRFeature

data class ScreenPreset(val label: String, val widthM: Float, val heightM: Float)
data class DistancePreset(val label: String, val distanceM: Float)

val SCREEN_SIZES = listOf(
    ScreenPreset("32\" Monitor", 0.71f, 0.40f),
    ScreenPreset("55\" TV", 1.22f, 0.68f),
    ScreenPreset("65\" TV", 1.44f, 0.81f),
    ScreenPreset("75\" TV", 1.66f, 0.93f),
    ScreenPreset("100\" Projector", 2.21f, 1.25f),
)

val DISTANCES = listOf(
    DistancePreset("Desk", 0.7f),
    DistancePreset("Living Room", 2.5f),
    DistancePreset("Theater", 5.0f),
    DistancePreset("Cinema", 8.0f),
)

class HelloWorldActivity : AppSystemActivity() {

  val currentSizeIndex = mutableIntStateOf(2)    // 65" TV
  val currentDistanceIndex = mutableIntStateOf(1) // Living Room

  private var panelEntity: Entity? = null

  override fun registerFeatures(): List<SpatialFeature> {
    val features =
        mutableListOf<SpatialFeature>(
            VRFeature(this),
            ComposeFeature(),
        )
    if (BuildConfig.DEBUG) {
      features.add(CastInputForwardFeature(this))
      features.add(HotReloadFeature(this))
      features.add(OVRMetricsFeature(this, OVRMetricsDataModel() { numberOfMeshes() }))
      features.add(DataModelInspectorFeature(spatial, this.componentManager))
    }
    return features
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
  }

  override fun onSceneReady() {
    super.onSceneReady()

    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)

    scene.setLightingEnvironment(
        ambientColor = Vector3(1.0f),
        sunColor = Vector3(7.0f, 7.0f, 7.0f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = 0.3f,
    )

    scene.setViewOrigin(0.0f, 0.0f, 2.0f, 180.0f)

    spawnPanel()

    systemManager.registerSystem(
        ScreenSizeControlSystem(
            onSizeChange = { delta ->
              val newIndex = (currentSizeIndex.intValue + delta).coerceIn(0, SCREEN_SIZES.size - 1)
              if (newIndex != currentSizeIndex.intValue) {
                currentSizeIndex.intValue = newIndex
                respawnPanel()
              }
            },
            onDistanceChange = { delta ->
              val newIndex =
                  (currentDistanceIndex.intValue + delta).coerceIn(0, DISTANCES.size - 1)
              if (newIndex != currentDistanceIndex.intValue) {
                currentDistanceIndex.intValue = newIndex
                respawnPanel()
              }
            },
        )
    )
  }

  private fun spawnPanel() {
    val distance = DISTANCES[currentDistanceIndex.intValue]
    // View origin is at (0, 0, 2) facing 180° (toward -Z)
    // Place panel in front of the user along -Z from the view origin
    panelEntity =
        Entity.createPanelEntity(
            R.id.hello_panel,
            Transform(
                Pose(
                    Vector3(0f, 1.5f, 2.0f - distance.distanceM),
                    Quaternion(0f, 180f, 0f),
                )
            ),
        )
  }

  private fun respawnPanel() {
    panelEntity?.destroy()
    panelEntity = null
    spawnPanel()
  }

  override fun registerPanels(): List<PanelRegistration> {
    return listOf(
        ComposeViewPanelRegistration(
            R.id.hello_panel,
            composeViewCreator = { _, ctx ->
              ComposeView(ctx).apply {
                setContent {
                  HelloPanel(
                      sizeIndex = currentSizeIndex,
                      distanceIndex = currentDistanceIndex,
                  )
                }
              }
            },
            settingsCreator = {
              val size = SCREEN_SIZES[currentSizeIndex.intValue]
              UIPanelSettings(
                  shape = QuadShapeOptions(width = size.widthM, height = size.heightM),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  display = DpPerMeterDisplayOptions(),
              )
            },
        )
    )
  }
}
