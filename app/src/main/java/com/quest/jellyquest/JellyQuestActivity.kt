package com.quest.jellyquest

import android.os.Bundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
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
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
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

  private var screenEntity: Entity? = null
  private var browsePanelEntity: Entity? = null
  val browsePanelVisible = mutableStateOf(false)
  private var skyboxEntity: Entity? = null
  private var floorEntity: Entity? = null
  private var wallEntities: List<Entity> = emptyList()
  private var armrestEntities: List<Entity> = emptyList()

  // Jellyfin + ExoPlayer
  lateinit var exoPlayerSource: ExoPlayerSource
  lateinit var jellyfinClient: JellyfinClient
  private lateinit var playbackReporter: PlaybackReporter

  // Current video dimensions for aspect-ratio fitting. Updated when ExoPlayer
  // reports a new video size; triggers screen respawn so the panel reshapes.
  private var videoWidth: Int = 0
  private var videoHeight: Int = 0

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

    // Reshape screen panel when video dimensions change (aspect-ratio masking)
    activityScope.launch {
      exoPlayerSource.mediaInfo.collect { info ->
        val w = info?.width ?: 0
        val h = info?.height ?: 0
        if (w > 0 && h > 0 && (w != videoWidth || h != videoHeight)) {
          videoWidth = w
          videoHeight = h
          Log.i(TAG, "Video size changed to ${w}x${h}, reshaping screen panel")
          respawnScreen()
        }
      }
    }

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
        ambientColor = Vector3(0.05f),
        sunColor = Vector3(0.0f, 0.0f, 0.0f),
        sunDirection = -Vector3(1.0f, 3.0f, -2.0f),
        environmentIntensity = 0.0f,
    )

    scene.setViewOrigin(0.0f, 0.0f, 0.0f)

    // Try to capture the anchor now; if head tracking isn't ready yet,
    // the AnchorCaptureSystem will keep trying each frame until it succeeds.
    if (!captureAnchor()) {
      systemManager.registerSystem(AnchorCaptureSystem(this))
    } else {
      spawnEnvironment()
      spawnScreen()
      startBumpers()
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
    startBumpers()
    // Auto-open browse panel if library cache is available
    if (jellyfinClient.cachedLibraries.value != null) {
      browsePanelVisible.value = true
      spawnBrowsePanel()
    }
  }

  private fun startBumpers() {
    exoPlayerSource.playBumpers(this, listOf(
        R.raw.bumper_regal,
        R.raw.bumper_chilly_dilly,
        R.raw.bumper_snipe,
    ))
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
    // The screen wall cutout provides a natural masking border around the video.
    // Separate frame entities are not used because VideoSurfacePanelRegistration renders
    // as a compositor layer, which doesn't share depth testing with regular mesh entities.
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
    wallEntities.forEach { it.destroy() }
    wallEntities = emptyList()
    armrestEntities.forEach { it.destroy() }
    armrestEntities = emptyList()

    val envPos = TheaterLayout.environmentPosition(a)
    val screen = theaterState.value.screen
    val room = theaterState.value.room

    // Skybox: near-black sphere centered on the user
    skyboxEntity = Entity.create(listOf(
        Mesh("mesh://skybox".toUri(), hittable = MeshCollision.NoCollision),
        Material().apply {
          baseColor = Color4(0.05f, 0.05f, 0.07f, 1f)
          unlit = true
        },
        Transform(Pose(envPos)),
    ))

    // Floor: dark charcoal ground plane centered on the user
    val floorHalfW = room.widthBack / 2f
    val floorHalfD = room.depth / 2f
    floorEntity = Entity.create(listOf(
        Box(Vector3(-floorHalfW, -0.005f, -floorHalfD), Vector3(floorHalfW, 0.005f, floorHalfD)),
        Mesh("mesh://box".toUri(), hittable = MeshCollision.NoCollision),
        Material().apply {
          baseColor = Color4(0.08f, 0.08f, 0.08f, 1f)
          unlit = true
        },
        Transform(Pose(envPos)),
    ))

    // Walls and ceiling
    val wallLength = TheaterLayout.wallLength(screen, room)
    val halfH = room.ceilingHeight / 2f
    val t = TheaterEnvironment.WALL_THICKNESS

    // Screen wall splits around the screen opening — left flank, right flank, and top strip
    val screenHalfW = screen.widthM / 2f
    val screenTop = screen.screenBottomM + screen.heightM
    val screenWallPose = TheaterLayout.screenWallPose(a, screen, room)

    val c = TheaterEnvironment.colors

    wallEntities = listOf(
        // Screen wall — left flank (extends along local X, thin along Z)
        createBoxEntity(
            Box(Vector3(-room.widthFront / 2f, 0f, -t / 2f), Vector3(-screenHalfW, room.ceilingHeight, t / 2f)),
            c.screenWall,
            screenWallPose,
        ),
        // Screen wall — right flank
        createBoxEntity(
            Box(Vector3(screenHalfW, 0f, -t / 2f), Vector3(room.widthFront / 2f, room.ceilingHeight, t / 2f)),
            c.screenWall,
            screenWallPose,
        ),
        // Screen wall — strip above screen
        createBoxEntity(
            Box(Vector3(-screenHalfW, screenTop, -t / 2f), Vector3(screenHalfW, room.ceilingHeight, t / 2f)),
            c.screenWall,
            screenWallPose,
        ),
        // Screen wall — strip below screen
        createBoxEntity(
            Box(Vector3(-screenHalfW, 0f, -t / 2f), Vector3(screenHalfW, screen.screenBottomM, t / 2f)),
            c.screenWall,
            screenWallPose,
        ),
        // Back wall (extends along local X, thin along Z)
        createBoxEntity(
            Box(Vector3(-room.widthBack / 2f, 0f, -t / 2f), Vector3(room.widthBack / 2f, room.ceilingHeight, t / 2f)),
            c.backWall,
            TheaterLayout.backWallPose(a, room),
        ),
        // Left wall — extends along local Z (front-to-back), thin along local X
        createBoxEntity(
            Box(Vector3(-t / 2f, 0f, -wallLength / 2f), Vector3(t / 2f, room.ceilingHeight, wallLength / 2f)),
            c.sideWall,
            TheaterLayout.leftWallPose(a, screen, room),
        ),
        // Right wall — extends along local Z (front-to-back), thin along local X
        createBoxEntity(
            Box(Vector3(-t / 2f, 0f, -wallLength / 2f), Vector3(t / 2f, room.ceilingHeight, wallLength / 2f)),
            c.sideWall,
            TheaterLayout.rightWallPose(a, screen, room),
        ),
        // Ceiling (extends along local X and Z, thin along Y)
        createBoxEntity(
            Box(Vector3(-room.widthBack / 2f, -t / 2f, -wallLength / 2f), Vector3(room.widthBack / 2f, t / 2f, wallLength / 2f)),
            c.ceiling,
            TheaterLayout.ceilingPose(a, screen, room),
        ),
    )

    // Armrests
    val armrestBox = Box(
        Vector3(-ViewerLayout.ARMREST_WIDTH / 2f, -ViewerLayout.ARMREST_HEIGHT / 2f, -ViewerLayout.ARMREST_LENGTH / 2f),
        Vector3(ViewerLayout.ARMREST_WIDTH / 2f, ViewerLayout.ARMREST_HEIGHT / 2f, ViewerLayout.ARMREST_LENGTH / 2f),
    )
    armrestEntities = listOf(
        createBoxEntity(armrestBox, c.armrest, ViewerLayout.armrestPose(a, theaterState.value.riserHeightM, isLeft = true)),
        createBoxEntity(armrestBox, c.armrest, ViewerLayout.armrestPose(a, theaterState.value.riserHeightM, isLeft = false)),
    )
  }

  private fun createBoxEntity(box: Box, color: Color4, pose: Pose): Entity {
    return Entity.create(listOf(
        box,
        Mesh("mesh://box".toUri(), hittable = MeshCollision.NoCollision),
        Material().apply {
          baseColor = color
          unlit = true
        },
        Transform(Pose(pose.t, pose.q)),
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
        room = TheaterEnvironment.computeRoom(theater),
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
        // Main screen panel — direct-to-surface video rendering via compositor layer.
        // ExoPlayer renders directly to the surface provided by the SDK, bypassing the
        // Android View system for significantly sharper video output.
        VideoSurfacePanelRegistration(
            R.id.screen_panel,
            surfaceConsumer = { _, surface ->
              exoPlayerSource.attachSurface(surface)
            },
            settingsCreator = {
              val screen = theaterState.value.screen
              val (fitW, fitH) = fitVideoToScreen(screen.widthM, screen.heightM, videoWidth, videoHeight)
              val pixW = if (videoWidth > 0) videoWidth else 1920
              val pixH = if (videoHeight > 0) videoHeight else 1080
              MediaPanelSettings(
                  shape = QuadShapeOptions(width = fitW, height = fitH),
                  display = PixelDisplayOptions(width = pixW, height = pixH),
                  rendering = MediaPanelRenderOptions(isDRM = false, zIndex = 0),
                  style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeTransparent),
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
