package com.quest.jellyquest

import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
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
import com.quest.jellyquest.streaming.PlaybackReporter
import kotlinx.coroutines.launch

/**
 * Orchestrator for the VR theater experience. Owns entity lifecycle, event wiring,
 * and SDK panel registration. Delegates ALL positioning math to layout classes:
 *
 *  - [TheaterLayout] — world-anchored objects (screen, environment). Placed once
 *    in world space, fixed unless the anchor resets on recenter.
 *  - [ViewerLayout] — viewer-relative objects (browse panel). Positioned relative
 *    to the user's seated location, follows seat elevation changes.
 *
 * [Anchor] captures the user's position and facing direction at startup/recenter.
 * [TheaterState] is the single source of truth for screen config + riser height.
 *
 * See issues/002-theater-positioning-architecture.md for the full design rationale.
 */
class JellyQuestActivity : AppSystemActivity() {

  companion object {
    private const val TAG = "VirtualMonitor"
  }

  private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

  val theaterState = mutableStateOf(TheaterState())
  // Derived state for Compose panels that only need screen config
  val currentScreen: State<ScreenConfig> get() = derivedStateOf { theaterState.value.screen }

  private var screenEntity: Entity? = null
  private var browsePanelEntity: Entity? = null
  val browsePanelVisible = mutableStateOf(false)
  private var skyboxEntity: Entity? = null
  private var floorEntity: Entity? = null

  // Jellyfin + ExoPlayer
  lateinit var exoPlayerSource: ExoPlayerSource
  lateinit var jellyfinClient: JellyfinClient
  private lateinit var playbackReporter: PlaybackReporter

  // Anchor: immutable snapshot of the user's position and facing direction.
  // Captured at startup and on recenter. All placement is relative to this point.
  private var anchor: Anchor? = null

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
    playbackReporter = PlaybackReporter(
        jellyfinClient = jellyfinClient,
        positionProvider = { exoPlayerSource.player.currentPosition },
        scope = activityScope,
    )
    Log.i(TAG, "ExoPlayer, Jellyfin client, and playback reporter initialized")

    // Pre-fetch library content if already authenticated from saved credentials
    if (jellyfinClient.authState.value == AuthState.AUTHENTICATED) {
      activityScope.launch { jellyfinClient.prefetchLibraryContent() }
    }
  }

  override fun onPause() {
    // Capture position and send stopped report so Jellyfin saves userData.
    // NonCancellable ensures the network call completes even if onDestroy cancels the scope.
    val positionMs = exoPlayerSource.player.currentPosition
    activityScope.launch {
      withContext(NonCancellable) { playbackReporter.stopReportingAtPosition(positionMs) }
    }
    super.onPause()
  }

  override fun onDestroy() {
    activityScope.cancel()
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

    // Try to capture the anchor now; if head tracking isn't ready yet,
    // the AnchorCaptureSystem will keep trying each frame until it succeeds.
    if (!captureAnchor()) {
      systemManager.registerSystem(AnchorCaptureSystem(this))
    } else {
      spawnEnvironment()
      spawnScreen()
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
              activityScope.launch { playbackReporter.reportCurrentPosition() }
            },
            onStop = {
              // Capture position before stopping player (stop resets position to 0)
              val positionMs = exoPlayerSource.player.currentPosition
              exoPlayerSource.stop()
              activityScope.launch {
                playbackReporter.stopReportingAtPosition(positionMs)
              }
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
    anchor = Anchor.capture() ?: return false
    return true
  }

  fun spawnScreenFromSystem() {
    spawnEnvironment()
    spawnScreen()
    // Auto-open browse panel if library cache is available
    if (jellyfinClient.cachedLibraries.value != null) {
      browsePanelVisible.value = true
      spawnBrowsePanel()
    }
  }

  private fun spawnScreen() {
    val a = anchor ?: return
    val screen = theaterState.value.screen
    val pose = TheaterLayout.screenPose(a, screen)

    Log.i(TAG, "Spawning screen at pos=${pose.t} rot=${pose.q} dist=${screen.distanceM}m height=${screen.screenCenterY}m")
    screenEntity =
        Entity.createPanelEntity(
            R.id.screen_panel,
            Transform(pose),
        )
  }

  private fun respawnScreen() {
    screenEntity?.destroy()
    screenEntity = null
    spawnScreen()
  }

  private fun spawnBrowsePanel() {
    val a = anchor ?: return
    Log.i(TAG, "spawnBrowsePanel: creating entity")
    browsePanelEntity?.destroy()

    val pose = ViewerLayout.browsePanelPose(a, theaterState.value.riserHeightM)
    browsePanelEntity =
        Entity.createPanelEntity(
            R.id.browse_panel,
            Transform(pose),
        )
  }

  private fun dismissBrowsePanel() {
    Log.i(TAG, "dismissBrowsePanel: visible=${browsePanelVisible.value}, entity=${browsePanelEntity?.id}")
    browsePanelVisible.value = false
    browsePanelEntity?.destroy()
    browsePanelEntity = null
  }

  private fun spawnEnvironment() {
    val a = anchor ?: return
    skyboxEntity?.destroy()
    floorEntity?.destroy()

    val envPos = TheaterLayout.environmentPosition(a)

    // Skybox: near-black sphere centered on the user
    skyboxEntity = Entity.create(listOf(
        Mesh("mesh://skybox".toUri(), hittable = MeshCollision.NoCollision),
        Material().apply {
          baseColor = Color4(0.05f, 0.05f, 0.07f, 1f)
          unlit = true
        },
        Transform(Pose(envPos)),
    ))

    // Floor: dark charcoal ground plane (30m x 30m, 1cm thick) centered on the user
    floorEntity = Entity.create(listOf(
        Box(Vector3(-15f, -0.005f, -15f), Vector3(15f, 0.005f, 15f)),
        Mesh("mesh://box".toUri(), hittable = MeshCollision.NoCollision),
        Material().apply {
          baseColor = Color4(0.08f, 0.08f, 0.08f, 1f)
          unlit = true
        },
        Transform(Pose(envPos)),
    ))
  }

  private fun applyTheaterPreset(theater: TheaterExperience, seat: SeatPosition) {
    theaterState.value = TheaterState(
        screen = ScreenConfig(
            label = theater.name,
            widthM = theater.screenWidthM,
            heightM = theater.screenHeightM,
            distanceM = seat.distanceM,
            screenBottomM = theater.screenBottomM,
        ),
        riserHeightM = seat.riserHeightM,
    )
    scene.setViewOrigin(0.0f, theaterState.value.riserHeightM, 0.0f)
    Log.i(TAG, "Seat riser height: ${theaterState.value.riserHeightM}m")
    logScreenPosition()
    repositionTheater()
  }

  /** Respawn all positioned entities using current anchor and theater state. */
  private fun repositionTheater() {
    spawnEnvironment()
    respawnScreen()
    if (browsePanelVisible.value) {
      spawnBrowsePanel()
    }
  }

  override fun onRecenter(isUserInitiated: Boolean) {
    super.onRecenter(isUserInitiated)
    Log.i(TAG, "onRecenter: userInitiated=$isUserInitiated")
    // Preserve current riser height — recenter reorients but keeps seat elevation
    scene.setViewOrigin(0.0f, theaterState.value.riserHeightM, 0.0f)
    if (!captureAnchor()) {
      Log.w(TAG, "onRecenter: failed to capture anchor, retaining previous")
    }
    repositionTheater()
  }

  private fun logScreenPosition() {
    val a = anchor ?: return
    val headPose =
        Query.where { has(AvatarAttachment.id) }
            .eval()
            .filter { it.isLocal() && it.getComponent<AvatarAttachment>().type == "head" }
            .firstOrNull()
            ?.getComponent<Transform>()
            ?.transform

    val screen = theaterState.value.screen
    val screenPose = TheaterLayout.screenPose(a, screen)
    val screenPos = screenPose.t

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
        // Main screen panel
        ComposeViewPanelRegistration(
            R.id.screen_panel,
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
              val screen = theaterState.value.screen
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
                      onMediaSelected = { item ->
                        activityScope.launch {
                          playbackReporter.stopReporting()

                          // Fetch fresh position from server (cached data may be stale)
                          val freshItem = jellyfinClient.getItemFresh(item.id) ?: item
                          val url = jellyfinClient.getStreamUrl(freshItem.id)
                          val resumeMs = PlaybackReporter.computeResumePositionMs(
                              freshItem.playbackPositionTicks,
                              freshItem.runTimeTicks,
                          )
                          val startPaused = resumeMs > 0
                          Log.i(TAG, "Media selected: '${freshItem.name}' resumeMs=$resumeMs startPaused=$startPaused")
                          exoPlayerSource.connect(url, resumeMs, startPaused)
                          playbackReporter.startReporting(freshItem.id)

                          // Hide browse panel after selecting media
                          browsePanelVisible.value = false
                          browsePanelEntity?.destroy()
                          browsePanelEntity = null
                        }
                      },
                      currentScreen = theaterState.value.screen,
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
