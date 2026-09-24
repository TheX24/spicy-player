# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android (Kotlin + Jetpack Compose) lyrics app for music playing in *another* app. It has no library and no playback service of its own. It reads the other app's MediaSession through a notification-listener grant, fetches lyrics from many online sources, and draws them with a port of Spicy Lyrics' (the Spotify/Spicetify extension's) word-synced renderer and backgrounds. Application ID: `com.tx24.spicyplayer.next`. The single module is `:app` with namespace `com.tx24.spicyplayer`.

Much of the code ports Spicy Lyrics' web client (and `@kawarp/core` for the background). When behaviour is in question, the reference sources are the ground truth, and code comments often name the upstream function being matched (e.g. `GetScrollLine`). The reference checkouts live at `~/Projects/refs/spicy-lyrics` and `~/Projects/refs/mild-lyrics`. Do not use the original Spicy Player app as a reference.

## Commands

Windows / PowerShell (use `./gradlew` from Git Bash):

```
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug      # full check (from README)
.\gradlew.bat testDebugUnitTest --tests "com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParserTest"
.\gradlew.bat testDebugUnitTest --tests "*ScrollManagerTest.some test name*"
.\gradlew.bat installDebug
```

- Unit tests are plain JVM JUnit4. `unitTests.isReturnDefaultValues = true` turns Android logging and clock calls into no-ops, so provider and parser code runs unchanged. XML parsing in tests uses kxml2.
- The live-network tests skip themselves unless you opt in: `RUN_SPICY_LYRICS_PROVIDER_TEST=1` (also needs `SPICY_LYRICS_CLIENT_KEY` in the environment) and `RUN_AMLL_NETWORK_TEST=1`.
- `SPICY_LYRICS_CLIENT_KEY` is read from a git-ignored root `.env` into `BuildConfig`. It must be a publishable `sl_pk_` key, never a secret `sl_sk_` key. Users can override it in Options.

## Architecture

The data flows through these layers:

1. **Session tracking**: `latencytest/ExternalPlaybackViewModel.kt`. `SessionAccessService` is an empty `NotificationListenerService` that exists only to grant `MediaSessionManager` access. The VM watches all active sessions, prefers a playing local player over a remote mirror, and keeps a `TimelineAnchor` (position + `elapsedRealtime` + speed) as a monotonic lyric clock. `ClockCorrection` decides when drift reported by the session is big enough to re-anchor. `AudioOutputProfiles` stores a lyric delay per output route (e.g. Bluetooth vs. speaker). Lyrics are keyed on title + artist only, because players fill metadata in across several updates. The playback clock follows the full track identity.
2. **Lyrics backend**: `latencytest/NextLyricsBackend.kt` wires the OkHttp/Retrofit client, the ~16 `RemoteLyricsProvider`s in `network/data/providers/`, source preferences (SharedPreferences), the per-track memory cache, a 3-day disk cache in `cacheDir/lyrics`, and fetch-ahead for queued tracks. **Bump `CACHE_VERSION` whenever matching, conversion, or TTML layout changes**, or stale cached lyrics will keep being served.
3. **Source selection**: `network/data/RemoteLyricsSource.kt`. It asks the lead source first, then fans out to the rest in parallel. It reports the best result so far as results arrive (`onUpdate`), stops early once a good enough result is in, and applies `ProviderCooldownTracker` backoff. Quality ranking and diagnostics feed the Debug screen. Providers that need a Spotify track ID (Spicy Lyrics) get one from `network/data/spotify/SpotifyTrackResolver` + `SpotifyTrackMatcher`, which deliberately refuse unsafe matches rather than substitute a different recording. The matching heuristics are considered settled, so avoid re-tuning them without cause.
4. **Normalisation**: providers return a `RemoteLyricsPayload`. TTML is first-class, so word timing never gets flattened to LRC. `latencytest/RemoteLyricsAdapter.kt` turns TTML (via `lyrics/spicy/parser/TtmlLyricsParser`, a port of SL's client `ttml/parser.ts`), LRC, or plain text into renderer `Line`/`Word` models, credits footer included.
5. **Rendering**: `lyrics/spicy/canvas/SpicyLyricsView.kt` is a single Compose `Canvas` driven by one `withFrameNanos` loop that reads `currentTimeMs()` outside composition. `LyricsLayoutCalculator` + `LineWrapper` handle layout, `LyricsAnimator` (springs / cubic splines) handles animation, and `ScrollPolicyController` + `ScrollManager` handle scrolling, following SL's scroll-line logic. `LyricsRenderer` draws. `LetterSynthesizer` makes per-letter emphasis for held words. `romanization/` covers the per-script romanizers (kuromoji for Japanese, pinyin4j for Chinese). Backgrounds are `kawarp/` (the Kawarp port) or `DynamicBackgroundRenderer`, picked in `latencytest/SpicySessionBackground.kt`.
6. **UI**: `latencytest/MainActivity.kt` holds the lyrics screen plus temporary Options/Debug screens. The UI is a placeholder and will be redesigned later.

The `latencytest` package name comes from the prototype and is kept on purpose for now.

## Renderer conventions learned the hard way

- Effect sizes (blur, glow) scale by lyric text size relative to SL's 56px desktop lyrics, **not** by screen density.
- Compose keeps a `TextLayoutResult`'s last shadow. Always pass `Shadow.None` and draw glow as a separate pass.
- For visual bugs, capture the device screen (e.g. `adb shell screenrecord`) and measure pixels before theorising. Do not drive the phone with `adb input` or launch intents on it.
