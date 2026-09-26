# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android (Kotlin + Jetpack Compose) lyrics app for music playing in *another* app. It has no library and no playback service of its own. It reads the other app's MediaSession through a notification-listener grant, fetches lyrics from many online sources, and draws them with a port of Spicy Lyrics' (the Spotify/Spicetify extension's) word-synced renderer and backgrounds. Application ID: `com.tx24.spicyplayer.next`. The single module is `:app` with namespace `com.tx24.spicyplayer`.

Much of the code ports Spicy Lyrics' web client (and `@kawarp/core` for the background). When behaviour is in question, the reference sources are the ground truth, and code comments often name the upstream function being matched (e.g. `GetScrollLine`). The reference checkouts live at `~/Projects/refs/spicy-lyrics` and `~/Projects/refs/mild-lyrics`. Follow base Spicy Lyrics (`origin/main`) by default, settings included. iPixelGalaxy's fork (fetched into the spicy-lyrics checkout as `pixel/*`, e.g. `pixel/dev`) is used only where the user asks for it; the current settings screen layout is one such place. Do not use the original Spicy Player app as a reference.

## Commands

Windows / PowerShell (use `./gradlew` from Git Bash):

```
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug      # full check (from README)
.\gradlew.bat testDebugUnitTest --tests "com.tx24.spicyplayer.lyrics.spicy.parser.TtmlLyricsParserTest"
.\gradlew.bat testDebugUnitTest --tests "*ScrollManagerTest.some test name*"
.\gradlew.bat installDebug
```

- Unit tests are plain JVM JUnit4. `unitTests.isReturnDefaultValues = true` turns Android logging and clock calls into no-ops, so provider and parser code runs unchanged. XML parsing in tests uses kxml2.
- The live-network tests skip themselves unless you opt in: `RUN_SPICY_LYRICS_PROVIDER_TEST=1` (also needs `SPICY_LYRICS_CLIENT_KEY` in the environment) and `RUN_AMLL_NETWORK_TEST=1`. `RUN_ALL_SOURCES_TEST=1` asks every source for a few well-known songs and prints a table (run with `--info`); use it to check which APIs still work.
- `SPICY_LYRICS_CLIENT_KEY` is read from a git-ignored root `.env` into `BuildConfig`. It must be a publishable `sl_pk_` key, never a secret `sl_sk_` key. Users can override it in Settings → Sources.

## More docs

Read these only when the task calls for them:

- `docs/architecture.md`: before working inside a layer (session tracking, backend, sources, normalisation, rendering, UI).
- `docs/settings.md`: before adding or changing a setting.
- `docs/releases.md`: when asked to make a release. Tagged builds produce signed APK GitHub prereleases, and they double as the changelog.

## Architecture

The data flows through five layers. `docs/architecture.md` has the detail for each; read it before working on one.

1. **Session tracking**: `playback/`, reading the other app's MediaSession and keeping the lyric clock.
2. **Lyrics backend**: `lyrics/NextLyricsBackend.kt`, which holds the providers, the caches and the source preferences.
3. **Source selection**: `network/data/`, covering source ranking, Spotify matching and blends.
4. **Normalisation**: TTML, LRC or plain text become renderer `Line`/`Word` models.
5. **Rendering**: `lyrics/spicy/canvas/`, the SL renderer. Backgrounds are in `kawarp/` and `ui/background/`.

The UI is split across these places:
- `MainActivity.kt` is the lyrics screen.
- `ui/nowplaying/` holds the header, and `ui/controls/` the controls.
- `ui/settings/` holds settings.

SL's design tokens and glass live in `ui/theme/SpicyTokens.kt` and `ui/components/`. New chrome uses those, not Material defaults.

Rules that apply even without the detail doc:
- Bump `CACHE_VERSION` whenever matching, conversion or TTML layout changes. Otherwise stale cached lyrics keep being served.
- The Spotify matching heuristics are settled, so don't re-tune them without cause.
- Keep `BlendParityTest` at 0 differences when touching the blender. Never commit its fixtures, which are full lyrics.
- TTML is first-class: word timing never gets flattened to LRC.

## Renderer conventions learned the hard way

- Effect sizes (blur, glow) scale by lyric text size relative to SL's 56px desktop lyrics, **not** by screen density.
- Compose keeps a `TextLayoutResult`'s last shadow. Always pass `Shadow.None` and draw glow as a separate pass.
- For visual bugs, capture the device screen (e.g. `adb shell screenrecord`) and measure pixels before theorising. Do not drive the phone with `adb input` or launch intents on it.
