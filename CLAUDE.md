# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug              # Build debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease            # Build release APK (signing config is in build.gradle.kts)
./gradlew test                       # Run unit tests
./gradlew connectedAndroidTest       # Run instrumented tests (requires connected device/emulator)
```

- Target/Compile SDK: 36 (Android 15), Min SDK: 24
- Java 11, Kotlin 1.9.24, Compose BOM 2024.12.01

## What This App Is

Ncrust is a third-party NetEase Cloud Music (网易云音乐) Android client built around three design priorities:
1. **Metro Design** — right-angle cuts, no curves, no rounded corners, information-first
2. **GPU zero-recomposition** — animations driven by a single `progress: Float` through `graphicsLayer`, not state-driven recomposition
3. **Three-layer graphics architecture** — main page / player card / navigation bar are independent composable layers, enabling gesture transitions without interference

## Architecture

### Entry Point & Structure

`MainActivity.kt` is now a lean entry point (~50 LOC for the activity class itself) that calls `MainScreen()`. All app-level orchestration (navigation, player state, queue management, bottom tab bar) lives in the `MainScreen()` composable in the same file.

Package layout under `com.takahashirinta.ncrust/`:

| Package | Purpose |
|---|---|
| `network/` | Retrofit interface, eapi encryption, response models |
| `player/` | ExoPlayer service, playback state persistence, URL fetching |
| `ui/navigation/` | `NavRoutes` route constants and `MainNavGraph` composable |
| `ui/player/` | Full-screen player card split across `PlayerCardOverlay`, `PlayerCard`, `FullPlayerControls`, `LyricsView`, `QueueView`, `SlimProgressBar` |
| `ui/screen/` | One file per screen (Home, Search, Library, Album/Artist/Playlist detail, etc.) |
| `ui/viewmodel/` | `PlayerViewModel`, `SearchViewModel`, `SongViewModel` |
| `ui/components/` | Reusable composables (`SongCard`, `DetailScaffold`, `PlayAllCircleButton`) |
| `ui/theme/` | Theme color system, `MarkdownText` composable |
| `auth/` | Cookie singleton (MUSIC_U extraction, SharedPreferences storage) |
| `library/` | Saved songs singleton, album derivation by `albumId` |
| `lyric/` | LRC parser: `[MM:SS.mm]` → `LrcLine.timeMs` |

### Player Card Component Tree

The player is split into focused files under `ui/player/`:

- **`PlayerCardOverlay`** — Positions the card on screen via `graphicsLayer { translationY }` based on `progress`. Thin wrapper, no animation logic.
- **`PlayerCard`** — Gesture handling (vertical drag, snap threshold at 25%), cover art animation, mini bar overlay, lyrics/queue toggle. All animation values read in `graphicsLayer` (draw phase only).
- **`FullPlayerControls`** — Play/pause/skip buttons, progress bar, quality badge, lyrics/queue/library toggles. No animation logic.
- **`LyricsView`** — Auto-scrolling with golden-section positioning (current line at 36% from top), 5-second manual-scroll pause, and tap-to-seek on any lyric line.
- **`QueueView`** — Lazy queue list with gradient fade edges.
- **`SlimProgressBar`** — Seekable thin progress bar.

`progress: Animatable<Float>` (0 = mini bar, 1 = full-screen) is owned by `MainScreen`, passed down through `PlayerCardOverlay` → `PlayerCard`.

Three `Animatable` instances live in `PlayerCard` for content-area transitions:
- `lyricAnimProgress` (0 = large cover, 1 = small cover+lyrics) — drives cover scale and fade
- `queueSlideProgress` (0 = lyrics visible, 1 = queue visible) — drives horizontal slide via `translationX`
- Per-line `smoothCurrentIndex: Animatable<Float>` in `LyricsView` — drives per-lyric-line alpha/scale (active line full size, lines 1.8+ away shrink to 82%) with 180 ms tween, zero recomposition

### Network Layer

Two API styles coexist:
- **REST via Retrofit** (`NcmApi.kt`): search, lyrics, song detail
- **eapi via custom POST** (`PlaylistApi.kt` + `RetrofitClient.eapiPost/get`): protected endpoints (playlists, recommendations, daily songs)

eapi encryption (`crypto/EapiCrypto.kt`): URL path → AES-128-ECB with MD5 signing over `url_path + SEPARATOR + json_payload`. A cookie interceptor in `RetrofitClient` injects session cookies automatically.

### Playback

`PlaybackService` is a `MediaSessionService` running ExoPlayer. `PlayerViewModel` holds all playback state as `MutableStateFlow`. `PlaybackStateManager` serializes the queue and current song to SharedPreferences via Gson so playback survives process kill.

Song URL resolution (`SongUrlFetcher`) falls back through quality levels (super-master → hi-res → lossless → standard) based on a SharedPreferences setting.

`PlayerViewModel` tracks two independent quality preferences — `wifiQuality` and `mobileQuality` — as indices into a 5-level ladder:

| Index | Display | API level |
|---|---|---|
| 0 | 压缩 | standard |
| 1 | 较好 | higher |
| 2 | 更好 | exhigh |
| 3 | 无损 | lossless |
| 4 | 高解析 | hires |

Defaults: Wi-Fi = 3 (lossless), Mobile = 1 (higher). The selected quality label is shown as a badge in `FullPlayerControls`.

### Queue Management

All queue operations live in `MainScreen` (not in `PlayerViewModel`). The five helpers are:

- `replaceQueueAndPlay(songs, index)` — clears queue, sets new list, jumps to index
- `addToQueue(song)` — appends with dedup check
- `insertNext(song)` — inserts immediately after current index with dedup check
- `appendToQueue(song)` — tail-appends without dedup
- `insertAllNext(songs)` — batch-inserts after current index with dedup

When play mode 2 (shuffle) is active, any queue mutation that adds songs also regenerates the shuffled-index list.

### Animation Pattern

The player card uses a single `progress: Float` (0 = mini, 1 = full-screen) driven by `Animatable`. All visual properties (card size, position, opacity) are computed inside `graphicsLayer { }` blocks — **never via `animateFloatAsState`**. This is the core GPU zero-recomposition pattern.

The cover art uses a second `Animatable` called `lyricAnimProgress` (0 = large cover, 1 = small cover) for the lyrics/cover toggle. Both are read only in `graphicsLayer`, never in composition scope. Easing uses `tween + CubicBezierEasing` (non-linear, no spring/bounce — Metro Design requires controlled deceleration, not physics).

Cover art always fills the full screen width (no rounded corners, no clipping). The cover transitions use center-based `TransformOrigin(0.5f, 0.5f)` with computed `translationX/Y` to move the cover's center point between its mini, small, and large positions.

### State Management

No dependency injection framework. Singletons are used as service locators:
- `RetrofitClient` — HTTP client
- `CookieManager` — session cookies
- `LibraryManager` — saved songs
- `PlaybackStateManager` — persisted playback state
- `ThemeManager` — theme color preference

No Room database anywhere — all persistence is SharedPreferences + Gson.

## Key Constraints

- **Metro Design**: No rounded corners anywhere in the player. No spring/bounce animations. Cover always fills the full screen width (`fillMaxWidth().aspectRatio(1f)` with scale 1.0 in large mode).
- **Responsive layout**: `ResponsiveContent.kt` wraps all screens with a 360dp max-width center container. Wide-screen and fold support is handled there; don't hardcode widths elsewhere.
- **Search debounce**: 500ms in `SearchViewModel` — don't remove it.
- **No coroutines library import needed**: Coroutines ship with the Kotlin stdlib in this project's configuration.
- **Lyrics auto-scroll**: Golden-section positioning with a 5-second manual-scroll pause. Logic lives in `LyricsView.kt`.
- **System bar height compensation**: `collapsedOffsetY` (the Y position of the mini bar) factors in `systemNavBarHeightPx` so the card lands correctly under both gesture-nav and 3-button-nav. `fullCardExtraOffsetPx` adds `statusBarHeightDp + 24dp` to compensate for M3 `NavigationBar`'s actual 80dp height vs. the 56dp design constant. Touch any of these values carefully.
