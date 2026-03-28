# JellyQuest

A VR cinema app for Meta Quest that streams your [Jellyfin](https://jellyfin.org) media library in a virtual theater environment.

Built with the [Meta Spatial SDK](https://developers.meta.com/horizon/documentation/spatial-sdk/) and Kotlin.

## Features

- **Jellyfin Integration** -- Connect to your Jellyfin server via Quick Connect (no typing in VR). Browse your library and stream movies, TV shows, and videos directly in the headset.
- **Theater Experiences** -- Choose from curated cinema presets: Screening Room, Multiplex, Premium Large Format, and IMAX. Each theater offers Front, Middle, and Back seat positions.
- **Virtual Environment** -- Dark room theater with floor and skybox. Screens are positioned at stage height like a real cinema.
- **Instant Library Access** -- Library data is pre-fetched and cached to disk. On subsequent launches, your content is available immediately with the browse panel auto-opened.
- **CylinderLayer Rendering** -- Video plays on a curved CylinderLayer panel via ExoPlayer for native-quality playback without compositor overhead.

## Controls

| Button | Action |
|--------|--------|
| **X** (left controller) | Toggle library browser |
| **A** (right controller) | Toggle theater picker |
| **B** (right controller) | Play / Pause |
| **Trigger** | Select items in panels |

## Screen Presets

The app includes screen sizes from desktop monitors to IMAX:

| Category | Sizes |
|----------|-------|
| Home displays | 32" Monitor, 55" TV, 65" TV, 75" TV, 100" Projector |
| Theater screens | Small Theater (7m), Mid Theater (10m), Large Theater (14m), PLF/Dolby (18m), IMAX (22m) |

Viewing distances range from 0.7m (desk) to 30m (balcony).

## Architecture

```
JellyQuestActivity          -- VR activity, panel lifecycle, anchor positioning
  |
  +-- JellyfinClient        -- Jellyfin SDK wrapper, Quick Connect auth, library cache
  +-- ExoPlayerSource       -- ExoPlayer streaming with CylinderLayer
  +-- ScreenSizeControlSystem -- Controller button input handling
  |
  +-- BrowsePanel           -- Jellyfin library browser (Compose)
  +-- TheaterPickerPanel    -- Theater experience selector (Compose + UISet)
  +-- MonitorPanel          -- Main video display panel (Compose)
  +-- TheaterExperiences    -- Theater preset data model
  +-- Theme                 -- Dracula color scheme for SpatialTheme
```

## Requirements

- Meta Quest 2, 3, 3S, or Pro
- Android Studio with Android SDK 34
- A Jellyfin server on your local network with [Quick Connect](https://jellyfin.org/docs/general/server/quick-connect/) enabled

## Building

```bash
# Clone the repository
git clone https://github.com/jcotellese/JellyQuest.git
cd JellyQuest

# Build debug APK
./gradlew assembleDebug

# Install to connected Quest device
./gradlew installDebug
```

**Note:** The Jellyfin server URL is currently hardcoded in `JellyfinClient.kt` (`DEFAULT_SERVER_URL`). Update this to your server's address before building.

## Configuration

### Jellyfin Server URL

Edit `app/src/main/java/com/quest/jellyquest/streaming/JellyfinClient.kt`:

```kotlin
const val DEFAULT_SERVER_URL = "http://YOUR_SERVER_IP:8096"
```

### Theater Presets

Theater experiences are defined in `TheaterExperiences.kt`. Each preset specifies a screen size, stage height, and available seat positions.

## Tech Stack

- **Meta Spatial SDK** -- VR rendering, panels, controller input, UISet components
- **Jellyfin SDK** -- Media server API, Quick Connect authentication
- **ExoPlayer (Media3)** -- Hardware-accelerated video playback
- **Jetpack Compose** -- UI panels with Meta's SpatialTheme
- **Kotlin Coroutines** -- Async networking and background pre-fetching

## License

[MIT](LICENSE)
