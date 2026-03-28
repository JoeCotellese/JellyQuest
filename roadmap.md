# JellyQuest VR Cinema - Implementation Roadmap

## Vision

An immersive Jellyfin cinema experience on Meta Quest. Browse your media library in VR, select a movie, and watch it on a giant screen with head-tracked spatial audio (Atmos).

## Why the Pivot

The DLNA/UPnP approach failed:
- Plex ignores SSDP multicast discovery entirely
- Jellyfin advertises unreachable Docker container IPs via SSDP
- SOAP ContentDirectory browsing is fragile and poorly standardized

The Jellyfin Android SDK provides a clean REST API with proper auth, library browsing, stream URLs, poster art, and scrobbling. ExoPlayer replaces VLC for video playback, enabling hardware Atmos decode and spatial audio integration that VLC couldn't provide.

## Tech Stack

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Media API | Jellyfin SDK (`jellyfin-core:1.5.0`) | Auth, library browsing, stream URLs, scrobbling |
| Video Playback | ExoPlayer (Media3 `1.4.1`) | HLS/DASH streaming, H.264/H.265/AV1 hardware decode |
| Spatial Audio | Meta `SpatialAudioFeature` | Head-tracked Atmos from screen position |
| VR Framework | Meta Spatial SDK (`0.11.1`) | Panels, transforms, controller input |
| UI | Jetpack Compose + Meta UISet | Browse panel, login, playback controls |

## Reference Implementation

The **PremiumMediaSample** in `../Meta-Spatial-SDK-Samples/PremiumMediaSample/` demonstrates the exact ExoPlayer + SpatialAudio + VideoSurfacePanel pattern we need.

---

## Phase 1: Dependencies + Cleanup

**Goal:** Remove VLC/DLNA, add Jellyfin SDK + ExoPlayer + SpatialAudio deps.

### Delete
- `streaming/DlnaSource.kt`
- `streaming/DlnaDiscovery.kt`
- `streaming/MediaServerScanner.kt`

### Add to `gradle/libs.versions.toml`
```toml
jellyfin = "1.5.0"
media3 = "1.4.1"

jellyfin-core = { group = "org.jellyfin.sdk", name = "jellyfin-core", version.ref = "jellyfin" }
media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
media3-exoplayer-dash = { group = "androidx.media3", name = "media3-exoplayer-dash", version.ref = "media3" }
media3-ui = { group = "androidx.media3", name = "media3-ui", version.ref = "media3" }
meta-spatial-sdk-spatialaudio = { group = "com.meta.spatial", name = "meta-spatial-sdk-spatialaudio", version.ref = "spatialsdk" }
```

### Remove from `gradle/libs.versions.toml`
```toml
libvlc = "3.6.2"
libvlc-all = { group = "org.videolan.android", name = "libvlc-all", version.ref = "libvlc" }
```

### Update `app/build.gradle.kts`
Replace `implementation(libs.libvlc.all)` with:
```kotlin
// Jellyfin SDK
implementation(libs.jellyfin.core)
// ExoPlayer (Media3)
implementation(libs.media3.exoplayer)
implementation(libs.media3.exoplayer.dash)
implementation(libs.media3.ui)
// Spatial Audio
implementation(libs.meta.spatial.sdk.spatialaudio)
```

### Verify
- [ ] Gradle sync succeeds

---

## Phase 2: ExoPlayerSource

**Goal:** Replace VLC playback with ExoPlayer behind the existing `StreamSource` interface.

### New file: `streaming/ExoPlayerSource.kt`

Implements `StreamSource` using Media3 ExoPlayer:

- Constructor takes `Context`, creates `ExoPlayer.Builder(context).build()` eagerly
- Exposes `val player: ExoPlayer` for audio session ID access
- `connect(uri)` -> `player.setMediaItem(MediaItem.fromUri(uri))` + prepare + play
- `attachSurface(surface)` -> `player.setVideoSurface(surface)`
- `Player.Listener` maps ExoPlayer states to `ConnectionState`:
  - `STATE_BUFFERING` -> `CONNECTING`
  - `isPlaying == true` -> `PLAYING`
  - `isPlaying == false && STATE_READY` -> `PAUSED`
  - `STATE_ENDED / STATE_IDLE` -> `DISCONNECTED`
  - `onPlayerError` -> `ERROR`
- `release()` method for cleanup (called from Activity.onDestroy)
- Thread-safe: all calls on main thread (SystemBase and Player.Listener both main-thread)

### Unchanged
- `streaming/StreamSource.kt` -- interface is backend-agnostic, no changes needed
- `MonitorPanel.kt` -- takes any `StreamSource?`, works as-is

### Verify
- [ ] Wire ExoPlayerSource into JellyQuestActivity with a hardcoded test URL
- [ ] Video plays on the VR monitor panel

---

## Phase 3: JellyfinClient

**Goal:** Wrap the Jellyfin SDK for auth + library browsing.

### New file: `streaming/JellyfinClient.kt`

```kotlin
// Initialization
val jellyfin = createJellyfin {
    clientInfo = ClientInfo("JellyQuest", "1.0")
    context = androidContext
}

// Authentication
val api = jellyfin.createApi(baseUrl = serverUrl)
val result by api.userApi.authenticateUserByName(username, password)
api.update(accessToken = result.accessToken)
```

Key methods:
| Method | Suspend? | SDK Call |
|--------|----------|---------|
| `connect(baseUrl, user, pass)` | Yes | `userApi.authenticateUserByName()` |
| `getLibraries()` | Yes | `userViewsApi.getUserViews()` |
| `getItems(parentId)` | Yes | `itemsApi.getItems(parentId = ...)` |
| `getStreamUrl(itemId)` | No | String construction: `$baseUrl/Videos/$itemId/stream?static=true&api_key=$token` |
| `getImageUrl(itemId)` | No | `imageApi.getItemImageUrl(itemId, PRIMARY)` |

State: `StateFlow<AuthState>` -- DISCONNECTED / CONNECTING / AUTHENTICATED / ERROR

Data model:
```kotlin
data class JellyfinItem(
    val id: UUID,
    val name: String,
    val type: BaseItemKind,  // MOVIE, SERIES, SEASON, EPISODE, FOLDER
    val isFolder: Boolean,
)
```

Credential persistence: `SharedPreferences` for v1 (baseUrl, username, accessToken).

### Verify
- [ ] Unit test: authenticate against local Jellyfin server
- [ ] Unit test: browse libraries and items

---

## Phase 4: BrowsePanel Rewrite

**Goal:** Replace DLNA server browser with Jellyfin login + library browser.

### Modify: `BrowsePanel.kt`

New signature:
```kotlin
fun BrowsePanel(jellyfinClient: JellyfinClient, onMediaSelected: (UUID) -> Unit)
```

Two screens based on `authState`:

**Login Screen** (DISCONNECTED / ERROR):
- TextField: server URL (e.g., `http://192.168.1.100:8096`)
- TextField: username
- TextField: password (visual transformation)
- "Connect" button
- Error message display
- Quest virtual keyboard already enabled in manifest

**Library Browser** (AUTHENTICATED):
- Reuse existing `BrowseListItem` composable (text list, folder/item distinction)
- Navigation: Libraries -> folders/items -> sub-items
- Breadcrumb + "< Back" button (same pattern as current DLNA browser)
- Folders navigate deeper; leaf items (MOVIE, EPISODE) trigger `onMediaSelected(itemId)`
- "Disconnect" button in header

Coroutines via `rememberCoroutineScope()` for suspend calls.

### Verify
- [ ] Login screen renders on Quest with virtual keyboard
- [ ] Library list populates after authentication
- [ ] Folder navigation works (drill into, back out)
- [ ] Selecting a movie triggers playback

---

## Phase 5: JellyQuestActivity Wiring

**Goal:** Connect all components in the main activity.

### Modify: `JellyQuestActivity.kt`

**Remove:**
- `libVLC: LibVLC`
- `dlnaSource: DlnaSource`
- `dlnaDiscovery: DlnaDiscovery`
- `multicastLock: WifiManager.MulticastLock`
- All `org.videolan.libvlc.*` imports

**Add:**
- `exoPlayerSource: ExoPlayerSource`
- `jellyfinClient: JellyfinClient`
- `spatialAudioFeature: SpatialAudioFeature`

**Update lifecycle:**
- `registerFeatures()` -> add `spatialAudioFeature`
- `onCreate()` -> create `ExoPlayerSource(this)` + `JellyfinClient(this)`
- `onDestroy()` -> `exoPlayerSource.release()`

**Update controller callbacks:**
- Browse toggle: remove `dlnaDiscovery.start()` (Jellyfin uses direct connection, not discovery)
- Play/pause: `exoPlayerSource.togglePlayPause()`

**Update panel registrations:**
- Monitor panel: `streamSource = exoPlayerSource`
- Browse panel: `BrowsePanel(jellyfinClient, onMediaSelected = { itemId -> ... })`

### Verify
- [ ] Full flow: open app -> browse panel -> login -> browse library -> select movie -> plays on screen
- [ ] B button pauses/resumes
- [ ] A button toggles browse panel

---

## Phase 6: Spatial Audio

**Goal:** Head-tracked audio emanating from the screen position.

### Pattern from PremiumMediaSample

```kotlin
// In registerFeatures():
spatialAudioFeature = SpatialAudioFeature()
features.add(spatialAudioFeature)

// When ExoPlayer reaches STATE_READY:
val audioFormat = player.audioFormat
val audioType = when (audioFormat?.channelCount ?: 2) {
    1 -> AudioType.MONO
    2 -> AudioType.STEREO
    else -> AudioType.SOUNDFIELD  // Atmos path
}
spatialAudioFeature.registerAudioSessionId(1, player.audioSessionId)
panelEntity?.setComponent(AudioSessionId(1, audioType))
```

This wires ExoPlayer's audio output through Meta's spatial audio renderer, positioning sound at the panel entity in 3D space. For Atmos content (E-AC3 JOC), ExoPlayer routes through hardware MediaCodec decoder and `SOUNDFIELD` mode enables full object-based audio.

### Verify
- [ ] Audio comes from screen direction, not head-locked
- [ ] Turning head changes perceived audio position
- [ ] Multichannel content detected as SOUNDFIELD

---

## Future Phases (Post-v1)

### Phase 7: Curved Cinema Screen
- Upgrade from `QuadShapeOptions` to `PanelShapeType.CYLINDER`
- `PanelQuadCylinderAnimation` for smooth transitions
- Configurable radius (tighter = more immersive)

### Phase 8: Poster Grid UI
- Replace text-only `BrowseListItem` with image grid
- Load poster art via `jellyfinClient.getImageUrl()`
- Coil or Glide for async image loading

### Phase 9: Playback Controls Overlay
- Seek bar, time display, volume
- Show on gaze-down or controller gesture
- Auto-hide after timeout

### Phase 10: Theater Environment
- Dark immersive environment (disable passthrough)
- Subtle ambient lighting
- Optional: modeled theater seats/walls

### Phase 11: Scrobbling + Resume
- `jellyfinClient.reportPlaybackProgress()` for position tracking
- Resume playback from last position
- Mark as watched

---

## Architecture Diagram

```
Controller Input
       |
ControllerInputSystem
       |
       v
JellyQuestActivity
  |         |          |
  v         v          v
ExoPlayer  Jellyfin   SpatialAudio
Source     Client     Feature
  |         |          |
  v         v          v
MonitorPanel  BrowsePanel  AudioSessionId
(SurfaceView) (Compose)   (on Panel Entity)
```

## Files Changed Summary

| File | Action |
|------|--------|
| `gradle/libs.versions.toml` | Modify (add/remove deps) |
| `app/build.gradle.kts` | Modify (add/remove deps) |
| `streaming/DlnaSource.kt` | Delete |
| `streaming/DlnaDiscovery.kt` | Delete |
| `streaming/MediaServerScanner.kt` | Delete |
| `streaming/ExoPlayerSource.kt` | Create |
| `streaming/JellyfinClient.kt` | Create |
| `BrowsePanel.kt` | Rewrite |
| `JellyQuestActivity.kt` | Modify |
| `streaming/StreamSource.kt` | Unchanged |
| `MonitorPanel.kt` | Unchanged |
| `HelloPanel.kt` | Unchanged |
| `ControllerInputSystem.kt` | Unchanged |
| `AnchorCaptureSystem.kt` | Unchanged |
| `Theme.kt` | Unchanged |
| `AndroidManifest.xml` | Unchanged |
