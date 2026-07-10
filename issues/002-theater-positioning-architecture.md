# Theater Positioning Architecture

## Problem

`JellyQuestActivity` is a 430-line god class that mixes entity lifecycle, event handling, positioning math, and UI wiring. Positioning logic is scattered across `spawnPanel()`, `spawnBrowsePanel()`, `spawnEnvironment()`, `applyTheaterPreset()`, `captureAnchor()`, and `onRecenter()` — each computing positions with inline math and magic numbers. This makes the system fragile: bugs like the view rotation issue (`setViewOrigin` yaw reset) and the browse panel height timing bug arose from not having a clear mental model of what's positioned relative to what.

## Architectural Insight

There are two fundamentally different positioning strategies:

| Strategy | Objects | Behavior |
|----------|---------|----------|
| **World-anchored** | Screen, floor, skybox, theater geometry | Placed once in world space. Fixed unless anchor resets (recenter). |
| **Viewer-relative** | Browse/settings panel, future transport HUD | Positioned relative to the viewer's current location. Moves with the user. |

Today these are mixed together. Separating them enables future free movement (locomotion) where the user walks around the theater while the screen stays fixed but the control panel follows them.

## Design

### Component Diagram

```
┌──────────────────────────────────────────────────────────┐
│                    JellyQuestActivity                     │
│  - Entity lifecycle (create/destroy)                      │
│  - Event wiring (buttons, recenter, seat change)          │
│  - Panel registration (SDK boilerplate)                   │
│  - Delegates ALL positioning to layout classes             │
├──────────────────────────────────────────────────────────┤
│                                                           │
│  ┌─────────────┐  ┌──────────────┐  ┌──────────────────┐ │
│  │   Anchor     │  │ TheaterState │  │  TheaterLayout   │ │
│  │  (value obj) │  │ (state)      │  │  (world-anchored)│ │
│  │             │  │              │  │                  │ │
│  │  position   │  │  screen      │  │  screenPose()    │ │
│  │  forward    │  │  riserHeight │  │  environmentPos()│ │
│  │  rotation   │  │              │  │                  │ │
│  └──────┬──────┘  └──────┬───────┘  └────────┬─────────┘ │
│         │               │                    │           │
│         └───────────────┼────────────────────┘           │
│                         │                                │
│  ┌──────────────────────┴───────────────────────────────┐│
│  │                   ViewerLayout                        ││
│  │                  (viewer-relative)                     ││
│  │                                                       ││
│  │  browsePanelPose(anchor, riserHeight)                 ││
│  │  // future: transportHudPose(controllerPose)          ││
│  │  // future: wristPanelPose(handPose)                  ││
│  └───────────────────────────────────────────────────────┘│
└──────────────────────────────────────────────────────────┘
```

### Data Types

```kotlin
/**
 * Immutable snapshot of the user's position and facing direction.
 * Captured at startup and on recenter. Y is always 0 (floor level).
 * The anchor defines the theater's origin — where the user "sits."
 */
data class Anchor(
    val position: Vector3,    // XZ of user at floor level
    val forward: Vector3,     // horizontal forward direction (toward screen)
    val rotation: Quaternion, // lookRotationAroundY(forward)
) {
    val left: Vector3 get() = Vector3(-forward.z, 0f, forward.x).normalize()
    val right: Vector3 get() = Vector3(forward.z, 0f, -forward.x).normalize()

    companion object {
        /** Attempt to capture from current head tracking. Returns null if not ready. */
        fun capture(scene: Scene): Anchor? { ... }
    }
}

/**
 * Current theater configuration. Single source of truth.
 * Updated when user selects a theater + seat.
 */
data class TheaterState(
    val screen: ScreenConfig,
    val riserHeightM: Float = 0f,
)

/**
 * Screen dimensions and position. Self-contained — no index references.
 */
data class ScreenConfig(
    val label: String,
    val widthM: Float,
    val heightM: Float,
    val distanceM: Float,
    val screenBottomM: Float = STAGE_HEIGHT,
) {
    val screenCenterY: Float get() = screenBottomM + (heightM / 2f)
}
```

### Layout Classes

```kotlin
/**
 * Pure positioning logic for world-anchored objects (theater geometry).
 * All functions are pure — take inputs, return Pose/Vector3, no side effects.
 */
object TheaterLayout {

    fun screenPose(anchor: Anchor, screen: ScreenConfig): Pose {
        val position = anchor.position + anchor.forward * screen.distanceM
        position.y = screen.screenCenterY
        return Pose(position, anchor.rotation)
    }

    fun environmentPosition(anchor: Anchor): Vector3 {
        return Vector3(anchor.position.x, 0f, anchor.position.z)
    }
}

/**
 * Pure positioning logic for viewer-relative objects (control panels).
 * These objects follow the user as they move or change elevation.
 */
object ViewerLayout {

    const val SEATED_EYE_HEIGHT = 1.1f

    // Browse panel placement relative to viewer
    const val BROWSE_FORWARD = 0.6f
    const val BROWSE_LEFT = 0.4f
    const val BROWSE_BELOW_EYE = 0.2f
    const val BROWSE_TILT_DEG = 15f

    /**
     * Position the browse panel to the left of the screen direction,
     * at the viewer's current seated eye height (including riser).
     *
     * Uses anchor for XZ direction (panel is always left of screen,
     * not left of gaze) but riser height for Y (follows seat elevation).
     * When free movement is added, XZ will use viewer position instead.
     */
    fun browsePanelPose(anchor: Anchor, riserHeightM: Float): Pose {
        val position = anchor.position +
            anchor.forward * BROWSE_FORWARD +
            anchor.left * BROWSE_LEFT
        position.y = SEATED_EYE_HEIGHT + riserHeightM - BROWSE_BELOW_EYE

        val dx = position.x - anchor.position.x
        val dz = position.z - anchor.position.z
        val yawDeg = Math.toDegrees(Math.atan2(dx.toDouble(), dz.toDouble())).toFloat()
        return Pose(position, Quaternion(BROWSE_TILT_DEG, yawDeg, 0f))
    }
}
```

### Activity (Orchestrator Only)

The activity becomes thin — it owns entities and wires events, but positioning logic is delegated:

```kotlin
class JellyQuestActivity : AppSystemActivity() {

    private var anchor: Anchor? = null
    val theaterState = mutableStateOf(TheaterState(DEFAULT_SCREEN))

    // Entity references (lifecycle only)
    private var screenEntity: Entity? = null
    private var browsePanelEntity: Entity? = null
    private var skyboxEntity: Entity? = null
    private var floorEntity: Entity? = null

    private fun spawnScreen() {
        val a = anchor ?: return
        screenEntity?.destroy()
        val pose = TheaterLayout.screenPose(a, theaterState.value.screen)
        screenEntity = Entity.createPanelEntity(R.id.screen_panel, Transform(pose))
    }

    private fun spawnBrowsePanel() {
        val a = anchor ?: return
        browsePanelEntity?.destroy()
        val pose = ViewerLayout.browsePanelPose(a, theaterState.value.riserHeightM)
        browsePanelEntity = Entity.createPanelEntity(R.id.browse_panel, Transform(pose))
    }

    private fun spawnEnvironment() {
        val a = anchor ?: return
        val pos = TheaterLayout.environmentPosition(a)
        // ... create skybox + floor at pos
    }

    private fun applyTheaterPreset(theater: TheaterExperience, seat: SeatPosition) {
        theaterState.value = TheaterState(
            screen = ScreenConfig(theater.name, theater.screenWidthM, ...),
            riserHeightM = seat.riserHeightM,
        )
        scene.setViewOrigin(0f, seat.riserHeightM, 0f)
        respawnScreen()
        if (browsePanelVisible.value) spawnBrowsePanel()
    }

    override fun onRecenter(isUserInitiated: Boolean) {
        super.onRecenter(isUserInitiated)
        scene.setViewOrigin(0f, theaterState.value.riserHeightM, 0f)
        anchor = Anchor.capture(scene)
        spawnEnvironment()
        respawnScreen()
        if (browsePanelVisible.value) spawnBrowsePanel()
    }
}
```

### What changes from today

| Current | Proposed | Why |
|---------|----------|-----|
| `anchorPosition`, `anchorForward`, `anchorRotation` as 3 separate fields | `Anchor` data class with `.left` / `.right` helpers | Single concept, immutable, self-documenting |
| `currentScreen` + `currentRiserHeightM` as separate fields | `TheaterState` with both | Always change together — single source of truth |
| Positioning math inline in `spawnPanel()`, `spawnBrowsePanel()`, etc | `TheaterLayout.screenPose()`, `ViewerLayout.browsePanelPose()` | Testable, readable, constants named |
| `spawnPanel` / `panelEntity` | `spawnScreen` / `screenEntity` | Naming reflects what it is |
| Magic numbers `0.6f`, `0.4f`, `1.1f`, `15f` | Named constants on `ViewerLayout` | Self-documenting |
| `captureAnchor()` mutates fields on activity | `Anchor.capture()` returns immutable value or null | Pure function, no side effects |

### Future: Free Movement

When locomotion is added, only `ViewerLayout` changes:

```kotlin
// Today (no locomotion): XZ from anchor, Y from riser
fun browsePanelPose(anchor: Anchor, riserHeightM: Float): Pose

// Future (with locomotion): XZ and Y from viewer's current world position
fun browsePanelPose(viewerPosition: Vector3, viewerForward: Vector3): Pose
```

`TheaterLayout` doesn't change at all — the screen is bolted to the theater wall regardless of where the user walks.

### File Structure

```
app/src/main/java/com/quest/jellyquest/
├── JellyQuestActivity.kt      -- Orchestrator: entities, events, SDK wiring
├── Anchor.kt                   -- Immutable user origin snapshot
├── TheaterState.kt             -- Current screen + riser configuration
├── TheaterLayout.kt            -- World-anchored positioning (screen, environment)
├── ViewerLayout.kt             -- Viewer-relative positioning (browse panel, HUD)
├── TheaterExperiences.kt       -- Preset data (unchanged)
├── ControllerInputSystem.kt    -- Button/thumbstick mapping (unchanged)
├── AnchorCaptureSystem.kt      -- Startup anchor polling (uses Anchor.capture)
├── BrowsePanel.kt              -- Compose UI (unchanged)
├── TheaterPickerPanel.kt       -- Compose UI (unchanged)
├── MonitorPanel.kt             -- Compose UI (unchanged)
├── HelloPanel.kt               -- Idle screen UI (unchanged)
├── Theme.kt                    -- Dracula color scheme (unchanged)
└── streaming/
    ├── StreamSource.kt
    ├── ExoPlayerSource.kt
    └── JellyfinClient.kt
```

### Implementation Order

Each step is a pure refactor — no behavior changes. Deploy and verify on Quest 3 between each step.

1. **`Anchor` data class** — Extract `anchorPosition`, `anchorForward`, `anchorRotation` from activity fields into `Anchor.kt`. Add `Anchor.capture()` factory. Wire into activity.
   - **Test**: App launches, screen positions correctly, recenter works.

2. **`TheaterState`** — Merge `currentScreen` + `currentRiserHeightM` into single `TheaterState` in `TheaterState.kt`. Move `ScreenConfig` there too.
   - **Test**: Seat switching works, riser height preserved across recenter.

3. **`TheaterLayout`** — Extract `screenPose()` and `environmentPosition()` into `TheaterLayout.kt`. Replace inline math in `spawnPanel()` and `spawnEnvironment()`.
   - **Test**: Screen and floor position correctly at all seat/theater combos, recenter repositions everything.

4. **`ViewerLayout`** — Extract `browsePanelPose()` with named constants into `ViewerLayout.kt`. Replace inline math in `spawnBrowsePanel()`.
   - **Test**: Browse panel appears correctly, height adjusts with riser, stays left of screen direction.

5. **Rename** — `panelEntity` → `screenEntity`, `spawnPanel` → `spawnScreen`, `hello_panel` → `screen_panel`, `R.id.hello_panel` → `R.id.screen_panel`.
   - **Test**: Everything still works, no regressions.

### Verification

- All existing behavior preserved (screen positioning, browse panel, seat changes, recenter)
- No visual changes — purely internal refactor
- `TheaterLayout` and `ViewerLayout` are testable with unit tests (pure functions)
- Deploy to Quest 3 and verify: seat switching, browse panel positioning, recenter
