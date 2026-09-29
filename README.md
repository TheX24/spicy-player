![Spicy Player: word-synced lyrics for whatever is playing on your phone](.github/assets/banner.webp)

# Spicy Player

> [!NOTE]
> **Looking for the offline music player?** The earlier Spicy Player, which plays your own music files, now lives at [Spicy Player (old)](https://github.com/TheX24/spicy-player-old). It is no longer developed.

An Android lyrics screen for music playing in another app. It follows the active Android media session, fetches timed lyrics from online sources, and shows playback controls when the music app supports them. It has no local music library or playback service. It succeeds the [original Spicy Player](https://github.com/TheX24/spicy-player-old), an offline music player.

**Status:** early development. The interface and provider availability may change. Releases are prereleases; check their notes before installing.

## Try it

1. Install an APK from [Releases](https://github.com/TheX24/spicy-player/releases), or build one below. Later versions can be installed from inside the app (Settings → Advanced → Updates).
2. Open the app and grant notification access when prompted. Android uses this permission to let the app discover active media sessions. You can revoke it in system settings.
3. Start music in another app, then return to the lyrics app. It should follow the active player and look up lyrics.
4. Use Settings to adjust lyric sources, timing, or an optional Spicy Lyrics client key. Debug shows the result of recent source lookups.

Playback controls depend on what the music app exposes through Android MediaSession. Some apps or account tiers limit seek and skip; the app explains when Spotify Free refuses one. Lyrics depend on the source having a usable match; a missing or uncertain Spotify match is not silently replaced with a different recording.

## Build from source

You need JDK 17 or newer and the Android SDK for API 37. Android Studio can install both. `local.properties` may point to your SDK; it is ignored by Git.

On Windows, from the repository root:

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

On macOS or Linux, run `./gradlew assembleDebug testDebugUnitTest lintDebug`. The APK is `app/build/outputs/apk/debug/app-debug.apk`; install it with `adb install -r` or Android Studio. The application ID is `com.tx24.spicyplayer.next`; debug builds install next to it as `com.tx24.spicyplayer.next.debug` ("Spicy Player Debug").

The Spicy Lyrics provider needs a publishable `sl_pk_` client key. To include one in your own build, copy `.env.example` to `.env` and set `SPICY_LYRICS_CLIENT_KEY`. You may also enter your own key in the app’s Settings. The file is ignored by Git, but **the value is embedded in the APK**. Never use an `sl_sk_` secret key. Other sources can still be used when this key is blank.

## Releases and CI

Pull requests and pushes to `main` or `dev` build a debug APK, run unit tests and lint, and retain the APK as a short-lived Actions artifact. These builds use no repository secrets.

An intentional `v<versionName>` tag starts the prerelease workflow. It checks the Gradle version, builds a signed release APK with the repository's publishable Spicy Lyrics client key, verifies its signature, and uploads the APK plus SHA-256 digest to GitHub Releases. The tag must identify the commit being released. See [release instructions](docs/releases.md) for signing setup and the release checklist. Debug builds are a separate app (`.debug`), so they never replace an installed release.

## Current limits

- The settings screen still includes developer-oriented source and matching controls.
- Judge smoothness on a release build (`assembleRelease`): debug builds are unoptimised and noticeably slower.
- This app needs notification access to follow another player; it does not play local files itself.
- The controls report Android media-session acknowledgement, which is not a measurement of audible response time.
- Source preferences, manual Spotify-ID overrides, and cached lyrics are stored locally. Android backup is disabled.

## License and attribution

Spicy Player is licensed under the [GNU Affero General Public License, version 3](LICENSE). Its lyrics renderer is adapted from [Spicy Lyrics](https://github.com/Spikerko/spicy-lyrics), its source fetching and blends from [mild-lyrics](https://github.com/gcoolL/mild-lyrics), and more comes from other open-source projects. [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) lists them all with their licenses.
