package com.quest.jellyquest

import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.core.net.toUri
import com.meta.spatial.castinputforward.CastInputForwardFeature
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Color4
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
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.VRFeature
import com.quest.jellyquest.streaming.AuthState
import com.quest.jellyquest.streaming.ExoPlayerSource
import com.quest.jellyquest.streaming.JellyfinClient
import kotlinx.coroutines.launch

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

class JellyQuestActivity : AppSystemActivity() {

  companion object {
    private const val TAG = "VirtualMonitor"
  }

  private val activityScope = CoroutineScope(Dispatchers.Main)

  val currentSizeIndex = mutableIntStateOf(7)      // Large Theater
  val currentDistanceIndex = mutableIntStateOf(4)  // Mid Theater
  // Screen center Y: bottom edge at STAGE_HEIGHT + half the screen's physical height
  var currentScreenHeightM = STAGE_HEIGHT + (SCREEN_SIZES[7].heightM / 2f)

  private var panelEntity: Entity? = null
  private var browsePanelEntity: Entity? = null
  val browsePanelVisible = mutableStateOf(false)

  // Jellyfin + ExoPlayer
  lateinit var exoPlayerSource: ExoPlayerSource
  lateinit var jellyfinClient: JellyfinClient

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
    exoPlayerSource = ExoPlayerSource(this)
    jellyfinClient = JellyfinClient(this)
    Log.i(TAG, "ExoPlayer and Jellyfin client initialized")

    // Pre-fetch library content if already authenticated from saved credentials
    if (jellyfinClient.authState.value == AuthState.AUTHENTICATED) {
      activityScope.launch { jellyfinClient.prefetchLibraryContent() }
    }
  }

  override fun onDestroy() {
    exoPlayerSource.disconnect()
    exoPlayerSource.release()
    super.onDestroy()
  }

  override fun onSceneReady() {
    super.onSceneReady()
    Log.i(TAG, "onSceneReady called")

    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)

    scene.setLightingEnvironment(
        ambientColor = Vector3(0.3f),
        sunColor = Vector3(2.0f, 2.0f, 2.0f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = 0.1f,
    )

    scene.setViewOrigin(0.0f, 0.0f, 0.0f, 0.0f)

    spawnEnvironment()

    // Try to capture the anchor now; if head tracking isn't ready yet,
    // the AnchorCaptureSystem will keep trying each frame until it succeeds.
    if (!captureAnchor()) {
      systemManager.registerSystem(AnchorCaptureSystem(this))
    } else {
      spawnPanel()
      // Auto-open browse panel if library cache is available
      if (jellyfinClient.cachedLibraries.value != null) {
        browsePanelVisible.value = true
        spawnBrowsePanel()
      }
    }

    systemManager.registerSystem(
        ControllerInputSystem(
            onBrowseToggle = {
              Log.i(TAG, "onBrowseToggle: visible=${browsePanelVisible.value}")
              if (!browsePanelVisible.value) {
                browsePanelVisible.value = true
                spawnBrowsePanel()
              } else {
                dismissBrowsePanel()
              }
            },
            onPlayPauseToggle = {
              exoPlayerSource.togglePlayPause()
            },
            onStop = {
              exoPlayerSource.stop()
              // Auto-show browse panel for next selection
              if (!browsePanelVisible.value) {
                browsePanelVisible.value = true
                spawnBrowsePanel()
              }
            },
            onSeekForward = { exoPlayerSource.seekForward() },
            onSeekBackward = { exoPlayerSource.seekBackward() },
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
    // Auto-open browse panel if library cache is available
    if (jellyfinClient.cachedLibraries.value != null) {
      browsePanelVisible.value = true
      spawnBrowsePanel()
    }
  }

  private fun spawnPanel() {
    val distance = DISTANCES[currentDistanceIndex.intValue]

    // Place the panel along the anchored forward direction at the selected distance
    val position = anchorPosition + anchorForward * distance.distanceM
    position.y = currentScreenHeightM

    Log.i(TAG, "Spawning panel at pos=$position rot=$anchorRotation dist=${distance.label} height=${currentScreenHeightM}m")
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
    Log.i(TAG, "spawnBrowsePanel: creating entity")
    browsePanelEntity?.destroy()

    // Place browse panel within arms reach, offset to the left.
    // Meta recommends ~70cm for controller interaction, slightly below line of sight.
    val leftDir = Vector3(-anchorForward.z, 0f, anchorForward.x).normalize()
    val position = anchorPosition + anchorForward * 0.6f + leftDir * 0.4f
    position.y = 0.9f  // Slightly below seated eye level, like a tablet in your lap

    // Compute yaw angle (degrees) to face panel content toward the user.
    // atan2 gives the angle of the awayFromUser vector; panel content is on -Z face,
    // so we use the direction from user to panel for the yaw.
    val dx = position.x - anchorPosition.x
    val dz = position.z - anchorPosition.z
    val yawDeg = Math.toDegrees(Math.atan2(dx.toDouble(), dz.toDouble())).toFloat()
    // Quaternion(pitch, yaw, roll) in degrees: tilt top away 15°
    val panelRotation = Quaternion(15f, yawDeg, 0f)

    browsePanelEntity =
        Entity.createPanelEntity(
            R.id.browse_panel,
            Transform(Pose(position, panelRotation)),
        )
  }

  private fun dismissBrowsePanel() {
    Log.i(TAG, "dismissBrowsePanel: visible=${browsePanelVisible.value}, entity=${browsePanelEntity?.id}")
    browsePanelVisible.value = false
    browsePanelEntity?.destroy()
    browsePanelEntity = null
  }

  private fun spawnEnvironment() {
    // Skybox: near-black sphere to replace the void
    Entity.create(listOf(
        Mesh("mesh://skybox".toUri(), hittable = MeshCollision.NoCollision),
        Material().apply {
          baseColor = Color4(0.05f, 0.05f, 0.07f, 1f)
          unlit = true
        },
        Transform(Pose(Vector3(0f, 0f, 0f))),
    ))

    // Floor: dark charcoal ground plane (30m x 30m, 1cm thick) at y=0 (LOCAL_FLOOR)
    Entity.create(listOf(
        Box(Vector3(-15f, -0.005f, -15f), Vector3(15f, 0.005f, 15f)),
        Mesh("mesh://box".toUri(), hittable = MeshCollision.NoCollision),
        Material().apply {
          baseColor = Color4(0.08f, 0.08f, 0.08f, 1f)
          unlit = true
        },
        Transform(Pose(Vector3(0f, 0f, 0f))),
    ))
  }

  private fun applyTheaterPreset(sizeIndex: Int, distanceIndex: Int, screenHeightM: Float) {
    currentSizeIndex.intValue = sizeIndex
    currentDistanceIndex.intValue = distanceIndex
    // Position screen so its bottom edge sits at stage height (1.2m above floor)
    val screenPhysicalHeight = SCREEN_SIZES[sizeIndex].heightM
    currentScreenHeightM = STAGE_HEIGHT + (screenPhysicalHeight / 2f)
    logScreenPosition()
    respawnPanel()
  }

  private fun logScreenPosition() {
    val headPose =
        Query.where { has(AvatarAttachment.id) }
            .eval()
            .filter { it.isLocal() && it.getComponent<AvatarAttachment>().type == "head" }
            .firstOrNull()
            ?.getComponent<Transform>()
            ?.transform

    val size = SCREEN_SIZES[currentSizeIndex.intValue]
    val distance = DISTANCES[currentDistanceIndex.intValue]

    val screenPos = anchorPosition + anchorForward * distance.distanceM
    screenPos.y = currentScreenHeightM

    val headPos = headPose?.t ?: Vector3(0f, 0f, 0f)
    val relativePos = Vector3(
        screenPos.x - headPos.x,
        screenPos.y - headPos.y,
        screenPos.z - headPos.z,
    )
    val distToScreen = Math.sqrt(
        (relativePos.x * relativePos.x + relativePos.y * relativePos.y + relativePos.z * relativePos.z).toDouble()
    ).toFloat()

    Log.i(TAG, "Screen position: ${size.label} at ${distance.label}")
    Log.i(TAG, "  Screen world pos: $screenPos")
    Log.i(TAG, "  Head world pos: $headPos")
    Log.i(TAG, "  Relative to head: $relativePos")
    Log.i(TAG, "  Distance from head: ${String.format("%.2f", distToScreen)}m")
    Log.i(TAG, "  Screen size: ${size.widthM}m x ${size.heightM}m, Screen center: ${currentScreenHeightM}m")
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
                      streamSource = exoPlayerSource,
                      sizeIndex = currentSizeIndex,
                      distanceIndex = currentDistanceIndex,
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
                  input = PanelInputOptions(
                      ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR
                  ),
              )
            },
        ),
        // Jellyfin browse panel (shown/hidden via A button)
        ComposeViewPanelRegistration(
            R.id.browse_panel,
            composeViewCreator = { _, ctx ->
              ComposeView(ctx).apply {
                setContent {
                  BrowsePanel(
                      jellyfinClient = jellyfinClient,
                      onMediaSelected = { itemId ->
                        val url = jellyfinClient.getStreamUrl(itemId)
                        exoPlayerSource.connect(url)
                        // Hide browse panel after selecting media
                        browsePanelVisible.value = false
                        browsePanelEntity?.destroy()
                        browsePanelEntity = null
                      },
                      currentSizeIndex = currentSizeIndex.intValue,
                      currentDistanceIndex = currentDistanceIndex.intValue,
                      onTheaterSelected = { sizeIdx, distIdx, screenHeightM ->
                        applyTheaterPreset(sizeIdx, distIdx, screenHeightM)
                      },
                  )
                }
              }
            },
            settingsCreator = {
              UIPanelSettings(
                  shape = QuadShapeOptions(width = 0.5f, height = 0.65f),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
                  display = DpPerMeterDisplayOptions(dpPerMeter = 800f),
                  input = PanelInputOptions(
                      ButtonBits.ButtonTriggerL or ButtonBits.ButtonTriggerR
                  ),
              )
            },
        ),
    )
  }
}
