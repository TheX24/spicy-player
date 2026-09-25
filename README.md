# Spicy Player Next

An external-player lyrics app built from the working Spicy Player MediaSession prototype. It follows music played in another app; it does not include a local music library or playback service.

The current app discovers active media sessions, prefers a playing local player over a remote mirror, and watches all active sessions so it can switch when another app starts playing. It uses the prototype's monotonic lyric clock, control acknowledgement timing, word-timed renderer, dynamic background, and output-specific lyric delay. Lyrics come through the original player's multi-source provider boundary, with Spotify track matching where a source needs it.

## Build

Run `.\gradlew.bat assembleDebug testDebugUnitTest lintDebug` on Windows. The application ID is `com.tx24.spicyplayer.next`, separate from the original app and latency prototype.

Both builds read `SPICY_LYRICS_CLIENT_KEY` from a local `.env` file (ignored by Git) and ship it. It must be a publishable `sl_pk_` key: those are made for clients and Spicy Lyrics rate-limits them per viewer IP, so one user cannot use up everyone's quota. Never ship a secret `sl_sk_` key. Users can override it with their own key in Options; that is saved on the device.

## Current boundaries

- Options has temporary source enable/order controls and the manual Spotify-ID override. Debug lists the outcome of each source in the latest lookup. These controls are functional placeholders for the later UI design.
- The external-player app does not include the original player's Room library or persistent lyrics cache. Source preferences and manual Spotify-ID overrides are persisted locally.
- Spotify-ID matching and manual override are available through Options. A missing or unsafe match does not silently substitute a different recording.
- Play, pause, seek, and skip depend on the selected app's MediaSession capabilities and account restrictions. The acknowledgement number measures session callbacks, not audible response time.
