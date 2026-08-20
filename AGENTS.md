# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug              # Build debug APK → app/build/outputs/apk/debug/
./gradlew assembleRelease            # Build release APK (signing config is in build.gradle.kts)
./gradlew test                       # Run unit tests
./gradlew connectedAndroidTest       # Run instrumented tests (requires connected device/emulator)
```

- Target/Compile SDK: 36 (Android 15), Min SDK: 24
- Java 11, Kotlin 1.9.24, Compose BOM 2024.12.01

## Development Log & Commit Convention

**No separate log file.** All development history lives in `git log`. Do not create or maintain a parallel `*_log.md` file — a previous `Ncrust_log.md` was removed in favour of git history.

Commit messages follow **Conventional Commits** with a lowercase type prefix:

| Prefix | When to use |
|---|---|
| `feat:` | User-visible new capability (features, UI additions, new APIs) |
| `fix:` | Bug fix; no new behaviour beyond restoring correctness |
| `chore:` | Housekeeping (delete unused files, rename directories, gitignore updates) |
| `docs:` | Docs-only changes (README, AGENTS.md, in-code comments) |
| `build:` | Build system / dependencies / version bumps |
| `refactor:` | Code shape change without behavioural change |
| `perf:` | Performance-only optimisation |
| `style:` | Formatting, whitespace, comment tweaks |

Subject line: prefix + one-sentence Chinese summary. Body (blank line, then paragraphs) explains *why* — Ncrust commits are meant to be readable a year later without opening a PR. Reference issues with `Fixes #N` or `#N` when relevant.

Multi-purpose commits are allowed but pick the dominant type by user impact (a mix of fixes + a new feature → `feat:`).

## Versioning

Single source of truth: `app/build.gradle.kts` → `defaultConfig.versionName` (and `versionCode`).

The About page (`ui/screen/AboutScreen.kt`) reads the version dynamically from `BuildConfig.VERSION_NAME` — **never hardcode a version constant here**. This requires `buildFeatures.buildConfig = true` in `app/build.gradle.kts`.

Release flow: bump `versionCode` + `versionName` → commit as `build: 升级至 vX.Y.Z ...` → `./gradlew assembleRelease` → `gh release create vX.Y.Z --draft <apk>` → user manually publishes after smoke test.

## What This App Is

Ncrust is a third-party NetEase Cloud Music (网易云音乐) Android client built around three design priorities:
1. **Kanesumi Design** — right-angle cuts, no curves, no rounded corners, information-first
2. **GPU zero-recomposition** — animations driven by a single `progress: Float` through `graphicsLayer`, not state-driven recomposition
3. **Three-layer graphics architecture** — main page / player card / navigation bar are independent composable layers, enabling gesture transitions without interference

## Terminology: Kanesumi Design

The design language is officially **Kanesumi Design** (canon: Ether monorepo root `KANESUMI_DESIGN.md`), replacing the historical name "Metro Design". Notes for agents:

- `Metro*` identifiers (`MetroText`, `MetroTheme`, `MetroIndication`, easing constants `MetroDefault`/`MetroCubic`…) are code/component names — **keep, do not rename**.
- New docs/comments: write 「Kanesumi Design / Kanesumi 风格」, not 「Metro Design / Metro 风格」. The easing family is 「UWP 缓动」 (`UwpEasing`), from the Metro era.
- Full mapping: `KANESUMI_DESIGN.md` §Ⅴ.

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
| `ui/i18n/` | Runtime i18n system: `Strings` data class, per-language files, `LanguageManager` |
| `ui/anim/sokuou/` | Sokuou animation system: UWP easing family + Apple-style spring presets + `sokuouSpring(response, damping)` bridge |
| `auth/` | Cookie singleton (MUSIC_U extraction, SharedPreferences storage) |
| `library/` | Cloud-synced favorites: 「收藏单曲」= NetEase like API, 「收藏专辑」= 云端专辑收藏; local SharedPreferences cache + background refresh |
| `lyric/` | LRC parser: `[MM:SS.mm]` → `LrcLine.timeMs` |
| `cache/` | `ContentCache` — in-memory network response snapshot (Home + Album/Playlist/Artist by ID + userProfile). Not persisted; resets on process kill. |

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

Song URL resolution (`SongUrlFetcher`) starts at the user's chosen quality and falls back down the ladder (e.g. `dolby → hires → lossless → exhigh → higher → standard`, or `lossless → exhigh → higher → standard`) based on a SharedPreferences setting. The payload carries `encodeType` (`mp4` for Dolby, `flac` otherwise) and an official-client `header` (os/appver/osver/deviceId + random requestId). A song that yields no playable URL at any tier is skipped, never given a broken fallback link.

`PlayerViewModel` tracks two independent quality preferences — `wifiQuality` and `mobileQuality` — as indices into a 7-level ladder:

| Index | Display | API level |
|---|---|---|
| 0 | 压缩 | standard |
| 1 | 较好 | higher |
| 2 | 更好 | exhigh |
| 3 | 无损 | lossless |
| 4 | 高解析 | hires |
| 5 | 高清环绕声 | jyeffect |
| 6 | 杜比全景声 | dolby |

Defaults: Wi-Fi = 3 (lossless), Mobile = 1 (higher). The selected quality label is shown as a badge in `FullPlayerControls`.

#### Playback reporting (webLog)

Ncrust reports a completed play back to NetEase so local listening feeds the recommendation/指数 system — the official web player's `webLog` mechanism (`player/PlayReporter.kt`). On natural song end **or** progress ≥ 80%, it POSTs a form `logs=JSON([{action:"play", json:{...}}])` to `clientlogusf.music.163.com/api/feedback/weblog?csrf_token=` carrying the session cookie. This path does **not** use eapi/weapi encryption (verified against the live web player JS — no `encSecKey`/`clientSign`). Reporting is fire-and-forget, deduped per song, and never blocks playback.

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

The cover art uses a second `Animatable` called `lyricAnimProgress` (0 = large cover, 1 = small cover) for the lyrics/cover toggle. Both are read only in `graphicsLayer`, never in composition scope. Easing uses `tween + CubicBezierEasing` (non-linear, no spring/bounce — Kanesumi Design requires controlled deceleration, not physics).

Cover art always fills the full screen width (no rounded corners, no clipping). The cover transitions use center-based `TransformOrigin(0.5f, 0.5f)` with computed `translationX/Y` to move the cover's center point between its mini, small, and large positions.

### Sokuou Animation System (`ui/anim/sokuou/`)

Sokuou is the shared animation vocabulary — ported from the PezMax-One Rust project — for anything new that isn't the player card. It sits on top of Compose's existing `Animatable` / `SpringSpec` / `TweenSpec`; it does not replace them.

- **`UwpEasing.kt`** — full UWP easing family (Quadratic/Cubic/Quartic/Quintic/Sine/Circle/Power/Exponential/Back/Bounce/Elastic × EaseIn/Out/InOut) exposed as Compose `Easing` values. Named constants `MetroDefault`, `MetroCubic`, `MetroBackOut`, `MetroBounceOut`, `MetroElasticOut`, `MetroSine`. These are net-new capabilities that Compose does not ship.
- **`Sokuou.kt`** — `sokuouSpring(response, dampingRatio)` bridges Apple's `response / dampingRatio` parameterisation to Compose `SpringSpec` (`stiffness = (2π / response)²`). `SokuouPresets` and `SokuouTweens` hold named specs (`StandardInteraction`, `QuickInteraction`, `SheetAppear`, `SheetDismiss`, `CoverFade`, `ToggleFlip`, …) so new call sites stop repeating `tween(300, CubicBezierEasing(...))`. `mapRange` and `mapRangeClamped` are the standard progress→value mapping helpers.

Prefer Sokuou presets in new code. Do not batch-refactor existing player-card animations to Sokuou — they were tuned by hand and are load-bearing.

### Content Cache & Load Transitions (`cache/`)

`ContentCache` is an in-memory snapshot of network-loaded content, alive for the process lifetime. It is not a persistence layer — that is `LibraryManager` (SharedPreferences). Its purpose is UX: eliminate the "empty screen → spinner → jump-to-content" flicker when a user returns to a screen.

The load pattern in every screen that reads from network:

1. On entry, read `ContentCache.getX(id)` as initial state.
2. If present, render immediately; do not show a loader.
3. Regardless of cache hit, kick off a background refresh; write the response back to `ContentCache` on success.
4. `LazyColumn` diffs items by key and smoothly updates.
5. Wrap loading↔content transitions in `Crossfade(animationSpec = SokuouTweens.CoverFade)` — never use a hard `if (isLoading) return Loader()` branch.

`DetailScaffold` takes a `hasCachedContent: Boolean` parameter. When `true`, the loader state is suppressed even if `isLoading` is true. Detail screens (`AlbumDetailScreen`, `PlaylistDetailScreen`, …) pass `hasCachedContent = (data != null)` after checking `ContentCache`.

### State Management

No dependency injection framework. Singletons are used as service locators:
- `RetrofitClient` — HTTP client
- `CookieManager` — session cookies
- `LibraryManager` — cloud-backed favorites (liked songs + subscribed albums; persistent SharedPreferences cache, `refreshFromCloud()` pulls from NetEase)
- `PlaybackStateManager` — persisted playback state
- `ContentCache` — in-memory network snapshot for UX (not persisted)
- `ThemeManager` — theme color preference
- `LanguageManager` (via `LocalStrings` CompositionLocal) — runtime locale

No Room database anywhere — all persistence is SharedPreferences + Gson.

### i18n System

All UI strings live in `ui/i18n/`. The system is runtime-based (not Android resource strings):

- **`Strings.kt`** — single data class with every UI string as a property; lambdas for formatted strings (e.g. `trackCount: (Int) -> String`)
- **One file per locale** (e.g. `zh_CN.kt`, `en_US.kt`) — each defines a `val zhCN: Strings = Strings(...)` top-level value
- **`LanguageManager.kt`** — `languagePresets` list, `LocalStrings` CompositionLocal (default `zhCN`), SharedPreferences helpers `getSavedLanguageCode`/`saveLanguageCode`/`stringsForCode`

Supported locales: 简体中文, 繁體中文, English (US/UK), 日本語, 조선어, Deutsch, Русский, Советский русский, Ελληνικά, Lingua Latina, Ænglisc (Old English), Middle English.

To **add a new locale**: create a new `xx_XX.kt` file with a `Strings(...)` instance, add a `LanguagePreset` entry to `languagePresets` in `LanguageManager.kt`.

To **add a new string**: add the property to `Strings` in `Strings.kt`, then add it to every locale file.

Composables read strings via `val s = LocalStrings.current` — never hardcode UI strings.

## Key Constraints

- **Kanesumi Design**: No rounded corners anywhere in the player. No spring/bounce animations. Cover always fills the full screen width (`fillMaxWidth().aspectRatio(1f)` with scale 1.0 in large mode).
- **Responsive layout**: `ResponsiveContent.kt` wraps all screens with a 360dp max-width center container. Wide-screen and fold support is handled there; don't hardcode widths elsewhere.
- **Search debounce**: 500ms in `SearchViewModel` — don't remove it.
- **No coroutines library import needed**: Coroutines ship with the Kotlin stdlib in this project's configuration.
- **Lyrics auto-scroll**: Golden-section positioning with a 5-second manual-scroll pause. Logic lives in `LyricsView.kt`.
- **System bar height compensation**: `collapsedOffsetY` (the Y position of the mini bar) factors in `systemNavBarHeightPx` so the card lands correctly under both gesture-nav and 3-button-nav. `fullCardExtraOffsetPx` adds `statusBarHeightDp + 24dp` to compensate for M3 `NavigationBar`'s actual 80dp height vs. the 56dp design constant. Touch any of these values carefully.
