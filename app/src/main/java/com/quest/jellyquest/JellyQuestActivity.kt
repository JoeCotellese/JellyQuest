package com.quest.jellyquest

import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import android.util.Log
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

/** Current screen configuration — updated when a theater preset is applied. */
data class ScreenConfig(
    val label: String,
    val widthM: Float,
    val heightM: Float,
    val distanceM: Float,
    val screenBottomM: Float = STAGE_HEIGHT,
) {
  val screenCenterY: Float get() = screenBottomM + (heightM / 2f)
}

// Default: PLF middle seat
val DEFAULT_SCREEN = ScreenConfig(
    label = "Premium Large Format",
    widthM = 14.0f,
    heightM = 5.86f,
    distanceM = 12.0f,
)

class JellyQuestActivity : AppSystemActivity() {

  companion object {
    private const val TAG = "VirtualMonitor"
  }

  private val activityScope = CoroutineScope(Dispatchers.Main)

  val currentScreen = mutableStateOf(DEFAULT_SCREEN)
  private var currentRiserHeightM = 0f

  private var panelEntity: Entity? = null
  private var browsePanelEntity: Entity? = null
  val browsePanelVisible = mutableStateOf(false)
  private var skyboxEntity: Entity? = null
  private var floorEntity: Entity? = null

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

    scene.setViewOrigin(0.0f, 0.0f, 0.0f)

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
    val screen = currentScreen.value

    // Place the panel along the anchored forward direction at the selected distance
    val position = anchorPosition + anchorForward * screen.distanceM
    position.y = screen.screenCenterY

    Log.i(TAG, "Spawning panel at pos=$position rot=$anchorRotation dist=${screen.distanceM}m height=${screen.screenCenterY}m")
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

    // XZ position uses anchor (always to the left of the screen direction).
    // Y uses seated eye height (~1.1m) plus riser elevation, not live head pose
    // (which may not reflect setViewOrigin changes immediately).
    val seatedEyeHeight = 1.1f
    val leftDir = Vector3(-anchorForward.z, 0f, anchorForward.x).normalize()
    val position = anchorPosition + anchorForward * 0.6f + leftDir * 0.4f
    position.y = seatedEyeHeight + currentRiserHeightM - 0.2f

    // Face toward anchor position (not current gaze) with tablet tilt
    val dx = position.x - anchorPosition.x
    val dz = position.z - anchorPosition.z
    val yawDeg = Math.toDegrees(Math.atan2(dx.toDouble(), dz.toDouble())).toFloat()
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
    skyboxEntity?.destroy()
    floorEntity?.destroy()

    // Skybox: near-black sphere centered on the user
    skyboxEntity = Entity.create(listOf(
        Mesh("mesh://skybox".toUri(), hittable = MeshCollision.NoCollision),
        Material().apply {
          baseColor = Color4(0.05f, 0.05f, 0.07f, 1f)
          unlit = true
        },
        Transform(Pose(Vector3(anchorPosition.x, 0f, anchorPosition.z))),
    ))

    // Floor: dark charcoal ground plane (30m x 30m, 1cm thick) centered on the user
    floorEntity = Entity.create(listOf(
        Box(Vector3(-15f, -0.005f, -15f), Vector3(15f, 0.005f, 15f)),
        Mesh("mesh://box".toUri(), hittable = MeshCollision.NoCollision),
        Material().apply {
          baseColor = Color4(0.08f, 0.08f, 0.08f, 1f)
          unlit = true
        },
        Transform(Pose(Vector3(anchorPosition.x, 0f, anchorPosition.z))),
    ))
  }

  private fun applyTheaterPreset(theater: TheaterExperience, seat: SeatPosition) {
    currentScreen.value = ScreenConfig(
        label = theater.name,
        widthM = theater.screenWidthM,
        heightM = theater.screenHeightM,
        distanceM = seat.distanceM,
        screenBottomM = theater.screenBottomM,
    )
    // Elevate the user's viewpoint to simulate stadium seating risers
    currentRiserHeightM = seat.riserHeightM
    scene.setViewOrigin(0.0f, currentRiserHeightM, 0.0f)
    Log.i(TAG, "Seat riser height: ${currentRiserHeightM}m")
    logScreenPosition()
    respawnPanel()
    // Reposition browse panel if visible
    if (browsePanelVisible.value) {
      spawnBrowsePanel()
    }
  }

  override fun onRecenter(isUserInitiated: Boolean) {
    super.onRecenter(isUserInitiated)
    Log.i(TAG, "onRecenter: userInitiated=$isUserInitiated")
    // Preserve current riser height — recenter reorients but keeps seat elevation
    scene.setViewOrigin(0.0f, currentRiserHeightM, 0.0f)
    captureAnchor()
    spawnEnvironment()
    respawnPanel()
    if (browsePanelVisible.value) {
      spawnBrowsePanel()
    }
  }

  private fun logScreenPosition() {
    val headPose =
        Query.where { has(AvatarAttachment.id) }
            .eval()
            .filter { it.isLocal() && it.getComponent<AvatarAttachment>().type == "head" }
            .firstOrNull()
            ?.getComponent<Transform>()
            ?.transform

    val screen = currentScreen.value
    val screenPos = anchorPosition + anchorForward * screen.distanceM
    screenPos.y = screen.screenCenterY

    val headPos = headPose?.t ?: Vector3(0f, 0f, 0f)
    val relativePos = Vector3(
        screenPos.x - headPos.x,
        screenPos.y - headPos.y,
        screenPos.z - headPos.z,
    )
    val distToScreen = Math.sqrt(
        (relativePos.x * relativePos.x + relativePos.y * relativePos.y + relativePos.z * relativePos.z).toDouble()
    ).toFloat()

    Log.i(TAG, "Screen: ${screen.label} at ${screen.distanceM}m")
    Log.i(TAG, "  Screen world pos: $screenPos")
    Log.i(TAG, "  Head world pos: $headPos")
    Log.i(TAG, "  Relative to head: $relativePos")
    Log.i(TAG, "  Distance from head: ${String.format("%.2f", distToScreen)}m")
    Log.i(TAG, "  Screen size: ${screen.widthM}m x ${screen.heightM}m, Screen center: ${screen.screenCenterY}m")
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
                      screenConfig = currentScreen,
                  )
                }
              }
            },
            settingsCreator = {
              val screen = currentScreen.value
              // Scale density inversely with panel width to keep texture allocation reasonable.
              val baseDpPerMeter = 600f
              val referencePanelWidth = 1.44f // 65" TV as baseline
              val dpPerMeter = (baseDpPerMeter * referencePanelWidth / screen.widthM).coerceIn(40f, baseDpPerMeter)
              UIPanelSettings(
                  shape = QuadShapeOptions(width = screen.widthM, height = screen.heightM),
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
                      currentScreen = currentScreen.value,
                      onTheaterSelected = { theater, seat ->
                        applyTheaterPreset(theater, seat)
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
