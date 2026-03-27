package com.quest.helloworld

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.castinputforward.CastInputForwardFeature
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Query
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AvatarAttachment
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
import com.quest.helloworld.streaming.DlnaDiscovery
import com.quest.helloworld.streaming.DlnaSource
import org.videolan.libvlc.LibVLC

data class ScreenPreset(val label: String, val widthM: Float, val heightM: Float, val minDistanceIndex: Int = 0)
data class DistancePreset(val label: String, val distanceM: Float)
data class HeightPreset(val label: String, val heightM: Float)

val SCREEN_SIZES = listOf(
    // Home displays (16:9)
    ScreenPreset("32\" Monitor", 0.71f, 0.40f),
    ScreenPreset("55\" TV", 1.22f, 0.68f),
    ScreenPreset("65\" TV", 1.44f, 0.81f),
    ScreenPreset("75\" TV", 1.66f, 0.93f),
    ScreenPreset("100\" Projector", 2.21f, 1.25f),
    // Movie theater screens (2.39:1 scope)
    ScreenPreset("Small Theater", 7.0f, 2.93f, minDistanceIndex = 2),   // Home Theater 4m
    ScreenPreset("Mid Theater", 10.0f, 4.18f, minDistanceIndex = 2),    // Home Theater 4m
    ScreenPreset("Large Theater", 14.0f, 5.86f, minDistanceIndex = 3),  // Front Row 5m
    ScreenPreset("PLF / Dolby", 18.0f, 7.53f, minDistanceIndex = 4),    // Mid Theater 12m
    ScreenPreset("IMAX", 22.0f, 12.0f, minDistanceIndex = 4),           // Mid Theater 12m
)

val DISTANCES = listOf(
    DistancePreset("Desk", 0.7f),
    DistancePreset("Living Room", 2.5f),
    DistancePreset("Home Theater", 4.0f),
    DistancePreset("Front Row", 5.0f),
    DistancePreset("Mid Theater", 12.0f),
    DistancePreset("Back Row", 20.0f),
    DistancePreset("Balcony", 30.0f),
)

val HEIGHTS = listOf(
    HeightPreset("Floor", 0.5f),
    HeightPreset("Low", 1.0f),
    HeightPreset("Eye Level", 1.5f),
    HeightPreset("High", 2.0f),
    HeightPreset("Ceiling", 2.5f),
)

class HelloWorldActivity : AppSystemActivity() {

  companion object {
    private const val TAG = "VirtualMonitor"
  }

  val currentSizeIndex = mutableIntStateOf(2)      // 65" TV
  val currentDistanceIndex = mutableIntStateOf(1)  // Living Room
  val currentHeightIndex = mutableIntStateOf(2)    // Eye Level

  private var panelEntity: Entity? = null
  private var browsePanelEntity: Entity? = null
  val browsePanelVisible = mutableStateOf(false)

  // VLC / DLNA
  lateinit var libVLC: LibVLC
  lateinit var dlnaSource: DlnaSource
  lateinit var dlnaDiscovery: DlnaDiscovery
  private var multicastLock: WifiManager.MulticastLock? = null

  // Anchor: the user's position and facing direction, captured once at startup.
  // All panel placement is relative to this fixed point.
  // SDK coordinate system: +Z = forward, +X = right, +Y = up
  private var anchorPosition = Vector3(0f, 0f, 0f)
  private var anchorForward = Vector3(0f, 0f, 1f)
  private var anchorRotation = Quaternion(0f, 0f, 0f)
  private var anchorCaptured = false

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
    // Acquire multicast lock so UPnP/SSDP discovery packets aren't dropped
    try {
      val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
      multicastLock = wifiManager.createMulticastLock("dlna_discovery").apply {
        setReferenceCounted(true)
        acquire()
      }
      Log.i(TAG, "MulticastLock acquired: ${multicastLock?.isHeld}")
    } catch (e: Exception) {
      Log.e(TAG, "Failed to acquire MulticastLock", e)
    }

    libVLC = LibVLC(this, arrayListOf("--no-drop-late-frames", "--no-skip-frames"))
    dlnaSource = DlnaSource(libVLC)
    dlnaDiscovery = DlnaDiscovery(libVLC)
    Log.i(TAG, "LibVLC and DLNA initialized")
  }

  override fun onDestroy() {
    dlnaSource.disconnect()
    dlnaDiscovery.stop()
    libVLC.release()
    multicastLock?.let { if (it.isHeld) it.release() }
    super.onDestroy()
  }

  override fun onSceneReady() {
    super.onSceneReady()
    Log.i(TAG, "onSceneReady called")

    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)

    scene.setLightingEnvironment(
        ambientColor = Vector3(1.0f),
        sunColor = Vector3(7.0f, 7.0f, 7.0f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = 0.3f,
    )

    scene.setViewOrigin(0.0f, 0.0f, 0.0f, 0.0f)

    // Try to capture the anchor now; if head tracking isn't ready yet,
    // the AnchorCaptureSystem will keep trying each frame until it succeeds.
    if (!captureAnchor()) {
      systemManager.registerSystem(AnchorCaptureSystem(this))
    } else {
      spawnPanel()
    }

    systemManager.registerSystem(
        ScreenSizeControlSystem(
            onSizeChange = { delta ->
              val newIndex = (currentSizeIndex.intValue + delta).coerceIn(0, SCREEN_SIZES.size - 1)
              if (newIndex != currentSizeIndex.intValue) {
                currentSizeIndex.intValue = newIndex
                // Enforce minimum viewing distance for this screen size
                val minDist = SCREEN_SIZES[newIndex].minDistanceIndex
                if (currentDistanceIndex.intValue < minDist) {
                  currentDistanceIndex.intValue = minDist
                }
                respawnPanel()
              }
            },
            onDistanceChange = { delta ->
              val minDist = SCREEN_SIZES[currentSizeIndex.intValue].minDistanceIndex
              val newIndex =
                  (currentDistanceIndex.intValue + delta).coerceIn(minDist, DISTANCES.size - 1)
              if (newIndex != currentDistanceIndex.intValue) {
                currentDistanceIndex.intValue = newIndex
                respawnPanel()
              }
            },
            onHeightChange = { delta ->
              val newIndex =
                  (currentHeightIndex.intValue + delta).coerceIn(0, HEIGHTS.size - 1)
              if (newIndex != currentHeightIndex.intValue) {
                currentHeightIndex.intValue = newIndex
                respawnPanel()
              }
            },
            onBrowseToggle = {
              browsePanelVisible.value = !browsePanelVisible.value
              if (browsePanelVisible.value) {
                dlnaDiscovery.start()
                spawnBrowsePanel()
              } else {
                browsePanelEntity?.destroy()
                browsePanelEntity = null
              }
            },
            onPlayPauseToggle = {
              dlnaSource.togglePlayPause()
            },
        )
    )
  }

  fun captureAnchor(): Boolean {
    val headPose =
        Query.where { has(AvatarAttachment.id) }
            .eval()
            .filter { it.isLocal() && it.getComponent<AvatarAttachment>().type == "head" }
            .firstOrNull()
            ?.getComponent<Transform>()
            ?.transform

    if (headPose == null || headPose == Pose()) {
      Log.d(TAG, "captureAnchor: headPose=${headPose?.t} (null=${headPose == null}, default=${headPose == Pose()})")
      return false
    }

    anchorPosition = Vector3(headPose.t.x, 0f, headPose.t.z)
    // SDK: +Z is forward. Pose.forward() = headPose.q * Vector3(0,0,1)
    val forward = headPose.forward()
    forward.y = 0f
    anchorForward = forward.normalize()
    anchorRotation = Quaternion.lookRotationAroundY(anchorForward)
    anchorCaptured = true
    Log.i(TAG, "Anchor captured: pos=$anchorPosition fwd=$anchorForward rot=$anchorRotation headPose=${headPose.t}")
    return true
  }

  fun spawnPanelFromSystem() {
    spawnPanel()
  }

  private fun spawnPanel() {
    val distance = DISTANCES[currentDistanceIndex.intValue]
    val height = HEIGHTS[currentHeightIndex.intValue]

    // Place the panel along the anchored forward direction at the selected distance
    val position = anchorPosition + anchorForward * distance.distanceM
    position.y = height.heightM

    Log.i(TAG, "Spawning panel at pos=$position rot=$anchorRotation dist=${distance.label} height=${height.label}")
    panelEntity =
        Entity.createPanelEntity(
            R.id.hello_panel,
            Transform(Pose(position, anchorRotation)),
        )
  }

  private fun respawnPanel() {
    panelEntity?.destroy()
    panelEntity = null
    spawnPanel()
  }

  private fun spawnBrowsePanel() {
    browsePanelEntity?.destroy()
    val distance = DISTANCES[currentDistanceIndex.intValue]
    val height = HEIGHTS[currentHeightIndex.intValue]

    // Place browse panel to the right of the main panel
    val rightDir = Vector3(-anchorForward.z, 0f, anchorForward.x).normalize()
    val position = anchorPosition + anchorForward * (distance.distanceM * 0.7f) + rightDir * 1.2f
    position.y = height.heightM

    browsePanelEntity =
        Entity.createPanelEntity(
            R.id.browse_panel,
            Transform(Pose(position, anchorRotation)),
        )
  }

  override fun registerPanels(): List<PanelRegistration> {
    return listOf(
        // Main monitor panel
        ComposeViewPanelRegistration(
            R.id.hello_panel,
            composeViewCreator = { _, ctx ->
              ComposeView(ctx).apply {
                setContent {
                  MonitorPanel(
                      streamSource = dlnaSource,
                      sizeIndex = currentSizeIndex,
                      distanceIndex = currentDistanceIndex,
                      heightIndex = currentHeightIndex,
                  )
                }
              }
            },
            settingsCreator = {
              val size = SCREEN_SIZES[currentSizeIndex.intValue]
              // Scale density inversely with panel width to keep texture allocation reasonable.
              // Home displays (~1-2m) use default density; theater screens get progressively lower.
              val baseDpPerMeter = 600f
              val referencePanelWidth = 1.44f // 65" TV as baseline
              val dpPerMeter = (baseDpPerMeter * referencePanelWidth / size.widthM).coerceIn(40f, baseDpPerMeter)
              UIPanelSettings(
                  shape = QuadShapeOptions(width = size.widthM, height = size.heightM),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  display = DpPerMeterDisplayOptions(dpPerMeter),
              )
            },
        ),
        // DLNA browse panel (shown/hidden via A button)
        ComposeViewPanelRegistration(
            R.id.browse_panel,
            composeViewCreator = { _, ctx ->
              ComposeView(ctx).apply {
                setContent {
                  BrowsePanel(
                      discovery = dlnaDiscovery,
                      onMediaSelected = { uri ->
                        dlnaSource.connect(uri)
                        // Hide browse panel after selecting media
                        browsePanelVisible.value = false
                        browsePanelEntity?.destroy()
                        browsePanelEntity = null
                      },
                  )
                }
              }
            },
            settingsCreator = {
              UIPanelSettings(
                  shape = QuadShapeOptions(width = 0.8f, height = 1.0f),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  display = DpPerMeterDisplayOptions(),
              )
            },
        ),
    )
  }
}
