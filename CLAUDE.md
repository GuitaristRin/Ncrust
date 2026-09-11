# CLAUDE.md

This file provides guidance to Claude Code when working in this repository. It is a mirror of **`AGENTS.md`** — that file is the canonical agent guide and the single source of truth. **When the two disagree, `AGENTS.md` wins** (or just re-copy it). Reflects the code as of **v1.3.0** (`versionCode = 5`). When in doubt, the source wins — update `AGENTS.md` first, then sync this file.

## Build & Test Commands

```bash
./gradlew assembleDebug            # debug APK  -> app/build/outputs/apk/debug/
./gradlew assembleRelease          # release APK (R8 minified), needs keystore.properties
./gradlew test                     # JVM unit tests
./gradlew connectedAndroidTest     # instrumented tests (device/emulator required)

# Macrobenchmark (performance; drives a signed release build, not connectedCheck)
benchmark/run_benchmark.sh [startup|scroll|expand|all]     # default: all

# Standalone Go backend — NOT used by the app (see ncrust-api/ below)
cd ncrust-api && go build -o ncrust-api .
```

Toolchain:

| | |
|---|---|
| compileSdk / targetSdk | 36 (Android 15) |
| minSdk | 24 |
| Java / jvmTarget | 11 |
| Kotlin | 1.9.24 |
| Compose BOM | 2024.12.01 (compiler ext 1.5.14) |
| Android Gradle Plugin | 8.5.0 |
| Gradle | wrapper 8.x |

**Composite build:** `settings.gradle.kts` does `includeBuild("../Kanesumi-sec-a")` with explicit `dependencySubstitution`, so the app builds against the sibling `Kanesumi-sec-a` checkout (not a published artifact). Cloning Ncrust alone is not enough — `Kanesumi-sec-a` must sit next to it. Delete the `includeBuild` block once Kanesumi is on Maven Central; the coordinates in `app/build.gradle.kts` then work unchanged.

## Repository Layout

```
Ncrust/
├── app/                  # the Android application (Kotlin + Jetpack Compose)
├── benchmark/            # macrobenchmark module (3 benchmarks + run_benchmark.sh)
├── ncrust-api/           # standalone Go reverse proxy — orphaned, not wired into the app
├── build.gradle.kts      # top-level plugin declarations
├── settings.gradle.kts   # :app, :benchmark + Kanesumi composite build
├── gradle.properties     # jvmargs, AndroidX, suppressUnsupportedCompileSdk=36
├── keystore.properties   # gitignored, release signing (storeFile/storePassword/keyAlias/keyPassword)
└── local.properties      # gitignored, sdk.dir
```

- **`app/`** — everything documented below.
- **`benchmark/`** — `StartupBenchmark` (cold start, 8 iters), `HomeScrollBenchmark` (frame timing, 6 iters), `ExpandPlayerBenchmark` (player expand/collapse, 6 iters, HOT start). Results land in `benchmark/output/<device>/` (gitignored) as `*-benchmarkData.json` + Perfetto traces. `benchmark/cookie.secret` (gitignored) injects a login cookie for realistic runs; `cookie.secret.example` documents it.
- **`ncrust-api/`** — a self-contained Go 1.21 service (stdlib only) that reverse-proxies NetEase with eapi encryption and TTL caching. It includes a checked-in 9.75 MB binary. **The Android app never calls it** — the app talks directly to `music.163.com` / `interface*.music.163.com`. Treat it as an unused side artifact / future scaffold; do not assume it is part of the runtime.

## Versioning & Release

Single source of truth: `app/build.gradle.kts` → `defaultConfig.versionName` / `versionCode`.

- `AboutScreen.kt` reads `BuildConfig.VERSION_NAME` — **never hardcode a version constant**. This needs `buildFeatures.buildConfig = true`.
- Release flow: bump `versionCode` + `versionName` → commit `build: 升级至 vX.Y.Z ...` → `./gradlew assembleRelease` → `gh release create vX.Y.Z --draft <apk>` → user smoke-tests and publishes manually.
- Current: `versionName = "1.3.0"`, `versionCode = 5`. Latest release: `v1.3.0` (2026-09-10).

## Commit Convention

All development history lives in `git log`. **Do not create a parallel `*_log.md` file.**

Conventional Commits, lowercase type prefix, Chinese subject:

| Prefix | When to use |
|---|---|
| `feat:` | User-visible new capability (features, UI additions, new APIs) |
| `fix:` | Bug fix; no new behaviour beyond restoring correctness |
| `chore:` | Housekeeping (delete unused files, rename dirs, gitignore) |
| `docs:` | Docs-only changes (README, AGENTS.md, in-code comments) |
| `build:` | Build system / dependencies / version bumps |
| `refactor:` | Code-shape change without behavioural change |
| `perf:` | Performance-only optimisation |
| `style:` | Formatting, whitespace, comment tweaks |

- Subject: prefix + one-sentence Chinese summary. Body (blank line, then paragraphs) explains **why** — commits should be readable a year later without opening a PR. Reference issues with `Fixes #N` / `#N`.
- **One logical section = one commit, committed proactively without waiting to be asked.** Don't batch unrelated changes (e.g. "extract txt + fix color + update docs") into one commit. Split by logical unit, not by file count: changes that must compile together are one section; independently revertable changes are separate.
- Multiple related fixes may share a commit only when they are the same logical change; pick the dominant type by user impact.

## What This App Is

Ncrust is a third-party NetEase Cloud Music (网易云音乐) Android client built around three design priorities:

1. **Kanesumi Design** — right-angle cuts, no curves, no rounded corners, information-first.
2. **GPU zero-recomposition** — animations driven by a single `progress: Float` through `graphicsLayer`, not state-driven recomposition.
3. **Three-layer graphics architecture** — main page / player card / navigation bar are independent composable layers, enabling gesture transitions without interference.

Feature surface at v1.3.0: home discovery (daily songs / recommended playlists / new songs / private FM), three-type search with 500 ms debounce + history, cloud-synced library (liked songs / subscribed albums / user playlists), full-screen player with gapless playback and a 7-level quality ladder, 5 playback modes, bilingual lyrics, system media controls + Android Auto / Automotive, WebView + QR login, runtime theming (6 colors × 3 modes), and 8 runtime languages.

## Terminology: Kanesumi Design

The design language is **Kanesumi Design** (canon: Ether monorepo root `KANESUMI_DESIGN.md`), historically "Metro Design". Notes for agents:

- `Metro*` identifiers (`MetroText`, `MetroTheme`, `MetroIndication`, easing constants `MetroDefault`/`MetroCubic`…) are code/component names — **keep, do not rename**.
- New docs/comments: write 「Kanesumi Design / Kanesumi 风格」, not 「Metro Design / Metro 风格」. The easing family is 「UWP 缓动」 (`UwpEasing`), from the Metro era.
- The `Metro*` UI components now live in the external **Kanesumi** library (see below), not in this repo.

## Architecture

### Entry Point & Orchestration

`MainActivity.kt` is a lean entry point (the activity class is ~130 LOC) plus the `MainScreen()` composable in the same file. `MainActivity`:

- applies the orientation policy (phones `< 600dp` locked portrait; large screens unlocked) via `smallestScreenWidthDp`, re-run in `onConfigurationChanged`;
- requests `POST_NOTIFICATIONS` on API 33+;
- calls `RetrofitClient.init(this)` and `AppWarmup.start(this)`;
- builds the theme stack: `LocalStrings` + `metroViewConfiguration` → `NcrustTheme` → `MetroTheme` → `MainScreen` + `SplashScreen` + first-run battery prompt.

`MainScreen()` owns all app-level orchestration: bottom navigation / wide-screen sidebar, the `progress: Animatable<Float>` that drives the player card, the playback queue, the 5 playback modes, the global song menu sheet, clipboard link handling, and the About / WebView-login overlays.

Also at file scope: `formatDuration(ms)`, and `object QueueModes { CYCLE=0, SINGLE=1, SHUFFLE=2, LINE=3, INFINITY=4 }`.

### Package Layout

Under `com.takahashirinta.ncrust/`:

| Package | Purpose |
|---|---|
| `network/` | Retrofit interface (`NcmApi`), eapi/weapi endpoints (`PlaylistApi`), `RetrofitClient` gateway, `CoverUrls`, response models |
| `network/crypto/` | `EapiCrypto` (AES-128-ECB + MD5 sign), `WeapiCrypto` (double AES-CBC + RSA) |
| `player/` | `PlaybackService` (MediaLibraryService + ExoPlayer), `PlaybackStateManager`, `SongUrlFetcher`, `PlayReporter` |
| `auth/` | `CookieManager` (SharedPreferences), `QrPair` / `QrPairClient` / `QrPairServer` (LAN QR handoff) |
| `library/` | `LibraryManager` (cloud-synced liked songs + subscribed albums), `SearchHistoryManager` |
| `cache/` | `ContentCache` — in-memory network snapshot (home + LRU detail caches + user profile) |
| `lyric/` | `LrcParser` (`[MM:SS.mm]` → `LrcLine.timeMs`), `LyricsCache` (200-entry persistent cache) |
| `warmup/` | `AppWarmup` — cold-start preload singleton |
| `power/` | `BackgroundActivity` — battery-optimisation whitelist intents |
| `ui/navigation/` | `NavRoutes` route constants and `MainNavGraph` |
| `ui/player/` | `PlayerCardOverlay`, `PlayerCard`, `FullPlayerControls`, `LyricsView`, `QueueView`, `SlimProgressBar` |
| `ui/screen/` | One file per screen (Home, Search, Library, User, Album/Artist/Playlist/Song detail, About, Splash) |
| `ui/viewmodel/` | `PlayerViewModel`, `SearchViewModel`, `SongViewModel` |
| `ui/components/` | Reusable composables (`SongCard`, `DetailScaffold`, `SongMenuSheet`, `PlayAllDialog`, `QrLoginDialog`, `QrScannerScreen`, `ArtistSearchItem`, `BackgroundActivityDialog`) |
| `ui/theme/` | `NcrustColors`, `NcrustTypography`, `ThemeManager`, `ThemeColorSelector`, `MarkdownText` |
| `ui/i18n/` | Runtime i18n: `Strings` data class, per-language files, `LanguageManager` |
| `ui/ResponsiveContent.kt` | 360dp (phone) / configurable (wide) max-width centre container |
| `ui/BottomOverlayInset.kt` | `BottomOverlayInsetDp` constant (144dp narrow / 64dp wide) |

### Kanesumi Library (external)

The app's shared UI components and animation vocabulary have **migrated out of this repo** into `Kanesumi-sec-a` (Apache-2.0), consumed via composite build:

- `kanesumi-core` — `MetroTheme`/`MetroColors`/`MetroTypography`, `MetroText`/`MetroIcon`, `MetroInsets`, `MetroIndication`.
- `kanesumi-anim` — `sokuou` package: `UwpEasing`, `SokuouPresets`/`SokuouTweens`, `sokuouSpring`, `MetroFling`/`MetroScroll`, `metroViewConfiguration`.
- `kanesumi-controls` — `MetroSurface`, `MetroButton`, `MetroTabRow`, `MetroSwitch`, `MetroIconButton`, `MetroDropdownMenu`, `MetroBottomSheet`, `MetroLyricsPanel`, `MetroDialog`, …
- `kanesumi-structure` — `MetroShell`, `MetroAppBar`, `MetroDetailScaffold`, `MetroTopScrim`, `MetroBottomNav`, `MetroSidebar`.

**There is no `androidx.compose.material3` dependency in the app** and no `material3` import anywhere. The only Material artifact left is `androidx.compose.material:material-icons-extended` (icons only). The former in-repo `ui/anim/sokuou/` directory no longer exists.

When editing UI, prefer Kanesumi components/presets. **Do not batch-refactor existing hand-tuned player-card animations** — they are load-bearing.

### Player Card Component Tree

- **`PlayerCardOverlay`** — positions the card via `graphicsLayer { translationY = collapsedOffsetY + (0 - collapsedOffsetY) * progress.value }`. Thin wrapper, no animation logic.
- **`PlayerCard`** — gesture handling (vertical drag, 25% snap threshold), cover-art animation, mini bar, lyrics/queue toggle, wide-screen two-column layout. Owns `lyricAnimProgress` (0 = large cover → 1 = small cover), `queueSlideProgress` (0 = lyrics → 1 = queue), and `wideSplit`. `StableCover` holds the previous cover across song changes (`COVER_HOLD_MS = 400L`). All animation values are read inside `graphicsLayer`.
- **`FullPlayerControls`** — transport buttons, seekable progress bar, position/duration/quality leaf composables (each collects its own StateFlow), lyrics/queue/library toggles, `compact`/`landscape` variants.
- **`LyricsView`** — wraps Kanesumi `MetroLyricsPanel`. Golden-section positioning, 5 s manual-scroll pause, tap-to-seek, bilingual `tlyric` merge.
- **`QueueView`** — three-zone queue (past / current / upcoming), drag-reorder limited to the upcoming zone, INFINITY placeholder, auto-centre on open.
- **`SlimProgressBar`** — Canvas-drawn 40dp-touch progress bar with a lazily-started buffering pulse.

`progress: Animatable<Float>` (0 = mini, 1 = full) is owned by `MainScreen` and passed through `PlayerCardOverlay` → `PlayerCard`. Card expand/collapse: 400 ms `CubicBezierEasing(0.2, 0, 0, 1)` / 260 ms `FastOutSlowInEasing`.

### Animation Pattern

The player card uses a single `progress: Float` driven by `Animatable`. All visual properties (card size, position, opacity) are computed inside `graphicsLayer { }` — **never `animateFloatAsState`**. This is the core GPU zero-recomposition pattern. Easing is `tween + CubicBezierEasing` (controlled deceleration, no spring/bounce). Cover art always fills the full screen width with no clipping; transitions use centre-based `TransformOrigin(0.5f, 0.5f)` with computed translations.

For anything that is *not* the player card, use the Kanesumi `sokuou` presets (`SokuouTweens.CoverFade`, `SokuouPresets.*`, `mapRange`/`mapRangeClamped`) instead of ad-hoc `tween(...)`.

### Network Layer

Two API styles coexist:

- **REST via Retrofit** (`NcmApi`, base `https://music.163.com/`): search (`api/cloudsearch/pc`, types 1/10/100), song detail (`api/v3/song/detail`), lyrics (`api/song/lyric`), album detail (`api/v1/album/{id}`), artist albums (`api/artist/albums/{id}`).
- **eapi via manual OkHttp POST** (`PlaylistApi` + `RetrofitClient.eapiPost`): playlists, recommendations, daily songs, personal FM, similar songs, song URL, album sub/unsub, like. **weapi** (`RetrofitClient.weapiPost`) is used for QR login (`/api/login/qrcode/unikey`, `/api/login/qrcode/client/login`) and the similar-song fallback.

`RetrofitClient` hosts:

| Constant | Value |
|---|---|
| `BASE_URL` | `https://music.163.com` |
| `API_URL` | `https://interface.music.163.com` (default eapi host) |
| `INTERFACE_URL` | `https://interface3.music.163.com` (eapi when `useInterface = true`, i.e. song URL fetch) |

- `init(context)` loads the cookie from `CookieManager`; `updateCookie(...)` replaces it (login/logout).
- `eapiPost(path, payload, useInterface)` encrypts via `EapiCrypto`, sends form `params=<hex>`, PC UA + Referer + Cookie.
- `eapiPostOfficial(path, payload)` mimics the official iPhone client (header with `os=iphone`, `appver=8.9.60`, deviceId/osver from cookie, random `requestId`); used for `likeSong`. Encrypted write responses are decrypted with `EapiCrypto.decryptResponse`.
- `weapiPost(path, payloadJson, extraCookies)` produces `params` + `encSecKey` via `WeapiCrypto`.
- `get(path)` plain GET; `postWeblog(url, logsJson)` form POST for playback reporting.
- A cookie interceptor injects `Cookie`/`User-Agent`/`Referer`; `HttpLoggingInterceptor` is `BuildConfig.DEBUG`-only.

`EapiCrypto`: AES key `e82ckenh8dichen8`, `/eapi/` → `/api/` path rewrite, signature `MD5("nobody" + path + "use" + json + "md5forencrypt")`, payload `path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest`, AES-128-ECB/PKCS5 → hex.

`WeapiCrypto`: random 16-char secret, double AES-128-CBC/PKCS5 with preset key `0CoJUm6Qyw8W8jud` then the secret, and `encSecKey` = raw RSA of the reversed secret (256 hex, no PKCS#1 padding, not base64).

### Playback

`PlaybackService` is a **`MediaLibraryService`** (media3), not a plain `MediaSessionService`. It owns:

- an `ExoPlayer` with audio focus, `handleAudioBecomingNoisy`, `WAKE_MODE_NETWORK`, and `DefaultLoadControl(30_000, 60_000, 2_500, 15_000)`; **no on-disk media cache**;
- a media3 `MediaLibrarySession` (Android Auto / Automotive browse tree: root → 每日推荐 / 私人 FM / 我喜欢的音乐, resolving `song:<id>` through `SongUrlFetcher`) and a legacy `MediaSessionCompat` (notification / lock-screen);
- a foreground MediaStyle notification (channel `ncrust_playback`, dominant-colour colorization, artwork bitmap);
- a **2 Hz** progress ticker (`delay(500)`);
- error reporting: both `onPlayerError` and `onAudioSinkError` invoke the static `onPlaybackError(songId)`.

`PlayerViewModel` holds all playback state as `MutableStateFlow` (isPlaying, position, duration, progress, buffering, current song metadata, lyrics, `currentQualityIndex`, `needsPreload`). `PlaybackStateManager` persists the last song + queue to SharedPreferences via Gson so playback survives process kill.

**Song URL resolution** (`SongUrlFetcher`) starts at the selected quality and falls back down the ladder. Exact ladders:

```
dolby    -> dolby, hires, lossless, exhigh, higher, standard
jyeffect -> jyeffect, lossless, exhigh, higher, standard
hires    -> hires, lossless, exhigh, higher, standard
lossless -> lossless, exhigh, higher, standard
exhigh   -> exhigh, higher, standard
higher   -> higher, standard
standard -> standard
```

- **Device FLAC gate**: skips `lossless`/`hires`/`jyeffect` when no MediaCodec `audio/flac` decoder exists (API < 27 or stripped ROM), so ExoPlayer never gets a FLAC stream it can only render as silence.
- **Auto quality downgrade**: `PlayerViewModel.handlePlaybackError` retries the same song one rung lower on `qualityRetryLadder` (reverse order, deduped 3 s per `songId@level`), skipping to the next song only when even `standard` fails. `AudioSink` failures are covered too.
- **Level-aware preload cache**: `PreloadCacheEntry` records `requestedLevel`; a downgrade retry never replays an already-failed higher-tier URL. TTL = 5 min. Gapless preload window = **60 s** (`PRELOAD_THRESHOLD_MS`), despite some older comments saying 20 s.
- A song that yields no playable URL at any tier is skipped — **never** fall back to `.../song/media/outer/url?id=X.mp3` (it 302→404s HTML and buffers forever).

Quality preferences (indices into the 7-level ladder) live in `ncrust_settings`:

| Index | Display (zh) | API level |
|---|---|---|
| 0 | 压缩 | `standard` |
| 1 | 较好 | `higher` |
| 2 | 更好 | `exhigh` |
| 3 | 无损 | `lossless` |
| 4 | 高解析 | `hires` |
| 5 | 高清环绕声 | `jyeffect` |
| 6 | 杜比全景声 | `dolby` |

Defaults: Wi-Fi = 3 (lossless), Mobile = 1 (higher). The selected label shows as a badge in `FullPlayerControls`.

#### Playback reporting (webLog)

`PlayReporter` replays the official web player's `webLog` so local listening feeds NetEase recommendations. On natural end **or** progress ≥ 80%, it POSTs a form `logs=JSON([{action:"play", json:{type:"song", wifi:0|1, download:0, id, time, end:"playend", mainsite:"1", mainsiteWeb:"1"}}])` to `https://clientlogusf.music.163.com/api/feedback/weblog?csrf_token=<csrf>`. **No eapi/weapi encryption.** Fire-and-forget on a `ncrust-weblog` thread, deduped per song, never blocks playback.

### Queue Management

All queue operations live in `MainScreen` (not `PlayerViewModel`): `replaceQueueAndPlay`, `playSongItem`, `insertNext`, `appendToQueue`, `insertAllNext`, `appendAllToQueue`, `removeFromQueue`, `moveInQueue`, `playFromQueue`, `playNext`, `playPrevious`, `generateShuffledIndices`, `launchInfinity`, `startFm`.

**Critical invariant:** `playbackQueue[currentQueueIndex]` must always equal the currently playing song. When deduping a queue mutation, record the current song's id first, filter, then re-locate the index — a naive `.filter` can drop an earlier duplicate and point `currentQueueIndex` at the wrong item. Every mutation calls `PlaybackStateManager.saveQueue(...)`, and any mutation in SHUFFLE mode regenerates the shuffled-index list.

5 playback modes (`QueueModes`): `CYCLE` (0), `SINGLE` (1), `SHUFFLE` (2), `LINE` (3, stops at tail), `INFINITY` (4, FM radio / similar-song continuation). `launchInfinity()` appends at the tail from `getPersonalFm` (FM mode) or `getSimilarSongs(seed)`, falling back to `getDailyRecommendSongs`, deduped and in-flight guarded.

### Content Cache & Load Transitions

`ContentCache` is an in-memory snapshot (not persistence — that is `LibraryManager`). It holds the three home lists + `homeWarmedUpAt` (15 s freshness window), LRU-32 caches for album / playlist-songs / artist-albums, and the user profile. It is cleared on `onTrimMemory` / `onLowMemory` / manual cache clear.

Load pattern for any network screen:

1. Read `ContentCache.getX(id)` as initial state.
2. If present, render immediately; do not show a loader.
3. Regardless, kick off a background refresh and write back on success.
4. Let `LazyColumn` diff by key.
5. Wrap loading↔content in `Crossfade(animationSpec = SokuouTweens.CoverFade)` — never a hard `if (isLoading) return Loader()`.

`DetailScaffold` takes `hasCachedContent: Boolean`; when `true` it suppresses the loader even if `isLoading`. Detail screens pass `hasCachedContent = (data != null)`.

### State Management

No DI framework — singletons as service locators: `RetrofitClient`, `CookieManager`, `LibraryManager`, `PlaybackStateManager`, `ContentCache`, `ThemeManager`, `LanguageManager` (via `LocalStrings`). No Room; all persistence is SharedPreferences + Gson.

SharedPreferences files:

| File | Owner(s) | Contents |
|---|---|---|
| `ncrust_prefs` | `CookieManager` | `user_cookie` |
| `ncrust_settings` | `ThemeManager`, `LanguageManager`, `PlayerViewModel`, `UserScreen`, `MainActivity` | theme index/mode, language, quality, gapless, lyrics translation, `battery_prompt_done` |
| `ncrust_library` | `LibraryManager` | `saved_songs`, `saved_albums`, `liked_ids` |
| `ncrust_playback_state` | `PlaybackStateManager` | last song + `queue` / `queue_index` |
| `ncrust_lyrics_cache` | `LyricsCache` | `entries` (≤ 200) |
| `search_history` | `SearchHistoryManager` | `songs`, `albums`, `artists` (≤ 10 each, 14-day TTL) |

### Auth & Login

`CookieManager` stores the raw cookie in `ncrust_prefs`. Login paths:

1. **WebView login** (phone default, and "通用登录" on tablet) — `MainActivity` shows a WebView at `https://music.163.com/#/login`, reads `MUSIC_U=` on `onPageFinished`, saves + `RetrofitClient.updateCookie`, then triggers `LibraryManager.refreshFromCloud`.
2. **QR login** (wide screens `>= 600dp`) — `QrLoginDialog` requests a `unikey` via weapi, renders the QR with ZXing, starts a `QrPairServer`, and polls `client/login` every 2 s (max 150 ticks): 800 expired / 802 scanned / 803 success.
3. **Phone-scanning-tablet handoff** — `QrAuthorizeScreen` (CameraX + ZXing) scans the tablet's QR, extracts the unikey, and sends the phone's cookie over the LAN. Protocol in `QrPair`: UDP broadcast on port `47821` (prefix `NCRUSTPAIR1?` / `NCRUSTPAIR1!`) to discover the tablet, then a TCP connection carrying a length-prefixed AES-128-CBC payload (key = `SHA-256(unikey)[0:16]`, random IV prepended). `cleartextTraffic` is enabled for this.

**Manual cookie paste no longer exists** — it was removed in `a16e381`. Do not reintroduce it in docs.

### i18n System

Runtime-based (not Android resource strings), under `ui/i18n/`:

- `Strings.kt` — one data class with every UI string (≈165 properties; some are lambdas such as `trackCount: (Int) -> String`).
- One file per locale defining a top-level `Strings(...)` value.
- `LanguageManager.kt` — `languagePresets`, `LocalStrings` (default `zhCN`), SharedPreferences helpers.

**8 locales:** `zh-CN` 简体中文, `zh-TW` 繁體中文, `en-US` English, `ja-JP` 日本語, `ja-MY` 万葉仮名, `ko-KP` 조선어, `de-DE` Deutsch, `ru-RU` Русский. (The legacy `en-UK` code maps to `en`.)

To add a locale: create `xx_XX.kt` with a `Strings(...)` and add a `LanguagePreset` to `languagePresets`. To add a string: add the property to `Strings.kt` and to **every** locale file. Composables read `val s = LocalStrings.current` — never hardcode UI strings.

### Theming & Responsive Layout

- **6 theme colors** (`themeColorPresets`): 云杉 `#1DB954`, 钴蓝 `#3B82F6`, 绯红 `#EF4444`, 琥珀 `#F59E0B`, 堇紫 `#8B5CF6`, 素白 `#FFFFFF`.
- **3 theme modes** (`ThemeMode`): `SYSTEM`, `DARK`, `LIGHT`. Dark base is OLED black `#000000`; light base is warm off-white `#F6F2E9`.
- `NcrustTheme(primaryColor, isDark)` derives `NcrustColors`; `toMetroColors(isDark)` bridges them into Kanesumi `MetroColors` so `Ncrust*` and `Metro*` components stay visually consistent.
- `ResponsiveContent`: `< 600dp` → centred `widthIn(max = 360.dp)`; `>= 600dp` with `maxWidth` → centred at that width; without → fills with 24dp horizontal padding.
- Wide screens (`windowWidthDp >= 600`) replace the bottom nav with a 200dp `MetroSidebar`; the content column gets `padding(start = 200.dp)`.
- `BottomOverlayInsetDp` = **144dp** narrow (80 nav + 56 mini + 8 buffer) / **64dp** wide (56 mini + 8). All scrollable content must use it as `contentPadding`; **never** append a manual end-of-list `Spacer`.

## Key Constraints & Pitfalls

- **Kanesumi Design**: no rounded corners in the player; no spring/bounce; cover always fills the full screen width (`fillMaxWidth().aspectRatio(1f)`, scale 1.0 in large mode).
- **GPU zero-recomposition**: read animation values only inside `graphicsLayer`; never `animateFloatAsState` for the player card. High-frequency StateFlows are subscribed in leaf composables / `draw` scope, not in the parent.
- **PlayerCard collapse gating**: below `progress = 0.05`, heavy children (LyricsView / QueueView / FullPlayerControls) are disposed.
- **Borderless list style**: `SongCard` LIST/COMPACT rows have `0dp` left padding (72dp cover flush to the edge); only the right keeps 16dp. `DetailScaffold` has no `TopAppBar` Surface — a floating back arrow (`MetroTopScrim`) overlays the content. Grid tiles use `spacedBy(2.dp)`. Home/Library use a 34sp page header instead of an app bar.
- **Bottom overlay inset**: the mini bar (56dp) sits above the M3-era 80dp nav bar, drawn as a sibling overlay, so `Scaffold.innerPadding.bottom` does **not** reserve space for them. Use `BottomOverlayInsetDp` as `contentPadding`.
- **System-bar compensation**: `collapsedOffsetY = contentHeightPx - sysNavPx - navBarHeightPx(56/0) - miniBarHeightPx(56) - sysStatusPx`. `sysStatusPx` cancels the `.statusBarsPadding()` applied inside `PlayerCard` to the mini-bar overlay. **Automotive (AAOS) caveat**: CarSystemUI does not deliver WindowInsets, so on `UI_MODE_TYPE_CAR` the content height comes from the measured root height (`rootHeightPx`), not `screenHeightDp`; phone/tablet keep `screenHeightDp` so car changes don't leak. Touch any of these values carefully.
- **Search debounce**: 500 ms in `SearchViewModel` — do not remove.
- **No explicit coroutines dependency**: coroutines ship with the Kotlin stdlib configuration here.
- **`ContentCache` is not persisted**; `LibraryManager` is the persistence layer.
- **Stale comments**: some comments say "last 20 s" for the gapless preload window (actual 60 s) and "4 Hz" for progress ticks (actual 2 Hz). Trust the code.
- **`SongDetailScreen` / `NavRoutes.song(...)` are registered but unreachable** — clipboard song links load into the player instead.
- **`ncrust-api/` is not part of the app** — see Repository Layout.
