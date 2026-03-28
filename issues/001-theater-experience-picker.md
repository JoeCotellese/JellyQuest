# Feature: Theater Experience Picker

**Value: L** — Core cinema experience differentiator; this is what makes JellyQuest feel like a cinema, not a flat video player
**Effort: M** — 4 files touched, well-understood panel/controller patterns, ~half day

---

## Overview

Replace raw thumbstick cycling of screen size/distance/height with curated "theater experiences" — named presets (Screening Room, Multiplex, Premium Large Format, IMAX) each with front/middle/back seat positions. Accessed via X button on left controller, presented as a spatial panel to the left of the user.

## User Stories

### US-1: Browse Theater Experiences
**As a** JellyQuest user, **I want to** choose from named theater types **so that** I can quickly get the viewing experience I'm in the mood for.

**Acceptance Criteria:**
- Given the theater picker is open, when I see the options, then I see 4 named theaters with a one-line description
- Given I select a theater, then screen size, distance, and height are set to that theater's curated values
- Given I'm watching content, when I switch theaters, then playback continues uninterrupted

### US-2: Adjust Seat Position
**As a** JellyQuest user, **I want to** choose front, middle, or back seat within a theater **so that** I can fine-tune immersion without changing theater type.

**Acceptance Criteria:**
- Given I've selected a theater, when I adjust seat position, then only viewing distance changes
- Given a theater has minimum distance constraints, then front seat respects those minimums

### US-3: Access the Picker
**As a** JellyQuest user, **I want to** open the theater picker with X button **so that** it's always one press away but doesn't clutter my view.

**Acceptance Criteria:**
- Given I press X, then the picker spawns as a spatial panel to my left, below eye line
- Given the picker is open, when I press X again, then it dismisses
- Given the browse panel is open, when I press X, then the browse panel closes and the theater picker opens

## UX Design

### Layout
Vertical card stack (4 cards, no scrolling). Each card contains theater name, one-line description, and an inline front/middle/back segmented control.

### Card Anatomy
```
┌─────────────────────────────┐
│  SCREENING ROOM             │  ← headline2Strong, primary
│  Intimate indie cinema      │  ← body2, secondary
│                             │
│  ○ Front   ● Middle   ○ Back│  ← inline segmented control
└─────────────────────────────┘
```

### Panel Placement
- Left of user, below eye line (~1.0m distance)
- 0.6m wide x 0.8m tall
- Angled slightly toward user
- Does not block cinema screen

### Transitions
- **Theater switch**: Fade-through-black (300ms out, 300ms in) — VR comfort pattern
- **Seat change** (same theater): Smooth distance slide (~400ms) — less jarring

### Button Mapping
- **X button** (left controller) — groups environment controls on left hand
- Left = environment (X: theater, left stick: height)
- Right = content (A: browse, B: play/pause, right stick: size/distance)

### Dismissal
- X press toggles open/closed
- Mutual exclusion with browse panel
- Auto-dismiss after 10s of no interaction

### Visual (Dracula Theme)
- Panel background: same `LocalColorScheme.current.panel` brush
- Active theater: left border accent in `DraculaCyan`
- Selected seat: `DraculaGreen` fill pill, dark text
- Unselected seat: transparent, secondary text

## Architecture

### New Files
- **`TheaterExperiences.kt`** — Data model + preset definitions
- **`TheaterPickerPanel.kt`** — Compose UI (card stack with inline seat selectors)

### Modified Files
- **`ControllerInputSystem.kt`** — Add `onTheaterToggle` callback for X button (`ButtonX`)
- **`JellyQuestActivity.kt`** — Theater picker panel entity, registration, spawn/dismiss, mutual exclusion with browse panel, `applyTheaterPreset()` method
- **`res/values/ids.xml`** — Add `theater_picker_panel` ID

### Data Model
```kotlin
data class TheaterExperience(
    val name: String,
    val description: String,
    val screenSizeIndex: Int,
    val heightIndex: Int,
    val seats: List<SeatPosition>,
)

data class SeatPosition(
    val label: String,
    val distanceIndex: Int,
)
```

### Theater Presets
| Theater | Screen | Front | Middle | Back | Height |
|---------|--------|-------|--------|------|--------|
| Screening Room | Small Theater (7m) | Home Theater 4m | Front Row 5m | Back Row 20m | Eye Level |
| Multiplex | Mid Theater (10m) | Front Row 5m | Mid Theater 12m | Back Row 20m | Eye Level |
| Premium Large Format | Large Theater (14m) | Front Row 5m | Mid Theater 12m | Back Row 20m | Eye Level |
| IMAX | IMAX (22m) | Mid Theater 12m | Back Row 20m | Balcony 30m | Eye Level |

### Pattern Reuse
- Panel spawn follows `spawnBrowsePanel()` pattern (offset left instead of right)
- Panel registration follows `ComposeViewPanelRegistration` from browse panel
- Toggle mirrors browse panel toggle with mutual exclusion

## Acceptance Criteria (Consolidated)

- [ ] X button toggles theater picker panel
- [ ] 4 theater cards displayed vertically, each with name + description + seat selector
- [ ] Selecting a theater applies screen size, distance, and height
- [ ] Seat selector (front/middle/back) changes only distance within the theater
- [ ] Active theater has visual indicator (cyan border)
- [ ] Selected seat has green pill treatment
- [ ] Browse panel and theater picker are mutually exclusive
- [ ] Playback continues during theater/seat transitions
- [ ] Panel spawns to left of user, below eye line
- [ ] Thumbstick controls still work for manual adjustment

## Analytics Events

| Event | Properties |
|-------|-----------|
| `theater_picker_opened` | `current_theater`, `during_playback` |
| `theater_selected` | `theater_name`, `seat_position` |
| `seat_adjusted` | `theater_name`, `from_position`, `to_position` |

## Out of Scope (v1)
- Custom theater creation
- Environment visuals (walls, seats, ambient lighting)
- Saving favorite theater per content
- Fade-through-black transition animation (use instant respawn for v1, animate later)
- Per-session theater memory
