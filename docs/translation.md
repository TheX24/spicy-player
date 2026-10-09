# Lyrics translation

Translation is optional and off by default. The floating Translate action applies to the current lyrics and stays enabled as songs change. It has keep priority 25, between PiP and resync, so crowded rows move it into More. Settings → Translation contains the target language (initially the device language), display mode, automatic translation, provider, DeepL key and languages to leave untranslated. Quick settings contains a saved per-song lyrics-language override.

The first use of each provider requires its disclosure. Unison receives original lyric lines and forwards them to Google; DeepL receives them directly with the user's key. Detection runs offline. No translation analytics or usage counters were added. Preferences and song-language overrides participate in settings backup/restore; credentials and provider consent stay on the device.

## Hard requirements

Paths below are relative to `app/src/main/java/com/tx24/spicyplayer/`.

| Requirement | Implementation | Behavior |
| --- | --- | --- |
| H1: exact identity, no stale results | `translation/TranslationModels.kt`: `TranslationDocument.hash`, `TranslationKey`, `TranslationSession`; `playback/ExternalPlaybackViewModel.kt`: `translateCurrent`, `clearTranslation`, `updateFromController` | A length-prefixed SHA-256 covers song, source, original texts and vocal addresses. Keys add target, provider and source override. A generation and current-document check guard every published result; changing song, source, target, provider, override or toggle cancels and invalidates old work. |
| H2: indexed alignment | `translation/TranslationEngine.kt`: `TranslationBatching.batches`, `align`, `TranslationEngine.translate`; `translation/TranslationPresentation.kt`: `forTimeline` | Request positions carry original indexes, including long-line pieces. Missing placeholders stay empty. Wrong response counts discard the response rather than guess which position is missing. Blank rows and music/interlude markers are not sent. Synthetic display interludes and copied timings never shift translations. |
| H3: original script | `translation/TranslationModels.kt`: `TranslationDocument.from`; `translation/TranslationEngine.kt`: `language` | Detection and HTTP requests read original `TimedWord.text`, preserving attached syllables. Romanization never enters the document, context or identity. |
| H4: background vocals | `translation/TranslationModels.kt`: `TranslationDocument.from`, `LyricAddress`; `translation/TranslationPresentation.kt`: `displayLines`; `lyrics/spicy/canvas/LyricsLayoutCalculator.kt`: `calculatePresentationLayouts` | Every background voice gets its own original index and parent/background address. Both display modes handle it as a separate row while preserving vocal role, group and times. |
| H5: whole-song detection and override | `translation/LocalSongLanguageDetector.kt`: `detect`; `translation/TranslationEngine.kt`: `language`; `playback/TranslationSettingsStore.kt`: `language`, `saveLanguage`; `ui/settings/TranslationSettings.kt`: `LyricsLanguageRow` | One offline detection uses all original content together. The override wins, is stored under the same song key as per-song delay, and is included in backups. Opening Quick settings can detect while translation remains off. |
| H6: skip decisions | `translation/TranslationModels.kt`: `skipTranslation`; `translation/TranslationEngine.kt`: `translate`, `TranslationBatching.align`; `playback/ExternalPlaybackViewModel.kt`: `translateCurrent` | Known target/excluded song languages make zero provider calls. Only a manual toggle emits the already-in-language message. Unison's `needsTranslation=false` leaves that row without an overlay; DeepL equivalent no-op rows also retain their originals. |
| H7: disk cache that renders | `translation/DiskTranslationCache.kt`: `read`, `write`; `translation/TranslationModels.kt`: `TRANSLATION_CACHE_VERSION`, `TranslationKey.fileName`; `translation/TranslationEngine.kt`: `translate`; `MainActivity.kt`: `LyricsPanelContent` | Version, key and full original row count must match. Fresh and cached results use the same state and presentation path. Corruption or count/version mismatch refetches. Cache is bounded to 100 entries and protected by release Gson rules. The lyrics `CACHE_VERSION` is unchanged. |
| H8: instant clean off | `playback/ExternalPlaybackViewModel.kt`: `toggleTranslation`, `clearTranslation`; `lyrics/spicy/canvas/LyricsView.kt`: `LyricsView` | Off clears state and invalidates work synchronously. Original layouts are measured first and retained alongside translated variants, so translated text and extra height disappear together. On consults disk cache. |
| H9: song context | `translation/TranslationEngine.kt`: `TranslationBatching.batches`, `TranslationEngine.translate`; `translation/HttpTranslators.kt`: `UnisonTranslator.translate`, `deepLRequest` | Unison receives all content in one request when it fits, otherwise whole verses per request. DeepL sends separate `text` fields plus the complete original song as `context` in every batch, `preserve_formatting=1`, `split_sentences=0`, and known `source_lang`. |

## Rendering

Under each line measures wrapping captions at 0.6 times that line's font size. Their actual measured height and a font-relative gap join the parent row's height, including background rows. With romanization enabled the order is original, romanization, translation. Captions follow the parent's opacity, blur, scale, scroll trail and folding presence without a word sweep.

Replace uses one untimed display word over the original line's time span, skips letter synthesis, and asks both animator styles for line-level state. The translated paragraph wraps as one text layout and lights uniformly. Missing translations continue using the original timed words. Original lyric models are never mutated.

The ordinary measurement/drawing path remains in use when translation is absent. Body passes explicitly reset to `Shadow.None`; caption blur uses a separate pass scaled by actual font size against 56 px.

## Limits and verification

- An individual Unison verse that needs more than 200 request entries fails safely and suggests DeepL. It is never cut mid-verse. Long original lines are split into at-most-500-character entries in the same contextual request and recombined at their original index.
- Verse boundaries come from blank/interlude markers or timed gaps of at least three seconds. A document with no usable boundary cannot be split safely. For a multi-request Unison song whose language is unknown, choose the per-song override first rather than let each fragment produce a different language guess.
- DeepL requests retain the full context and reject bodies over 128 KiB. Context is not truncated to make them fit.
- Detection is a dominant-language estimate; short and mixed-language lyrics can be ambiguous. The per-song override is available for corrections.
- JVM tests use fake translators, with no network calls. They cover malformed alignment, missing entries, markers, backgrounds, original-script detection, stale results, skips, disk hits/count/version errors, context/batching, host selection, replacement spans and display interludes.
- Verified with `gradlew.bat assembleDebug testDebugUnitTest lintDebug assembleRelease`: success, 401 JVM tests (393 passed, 8 skipped), including all 21 translation tests; lint reports no errors. The minified APK contains the bundled detector profiles and its cache models retain their serialized field names.
- Device visual behavior and live provider responses have not been verified. Wrapped captions, long background vocals, RTL/duets, folding and the off-path appearance still need visual checks on a phone.

Provider contracts checked against [Unison's Translate lyrics documentation](https://github.com/better-lyrics/unison#translate-lyrics) and the [DeepL translate API documentation](https://developers.deepl.com/api-reference/translate/request-translation). DeepL currently supports `context` and the formatting/sentence controls used here.

## Files changed

- Build and privacy: `app/build.gradle.kts`, `app/proguard-rules.pro`, `README.md`, `THIRD_PARTY_NOTICES.md`, `docs/translation.md`.
- Portable translation: `app/src/main/java/com/tx24/spicyplayer/translation/TranslationModels.kt`, `TranslationEngine.kt`, `DiskTranslationCache.kt`, `LocalSongLanguageDetector.kt`, `HttpTranslators.kt`, `TranslationPresentation.kt` (all in that directory).
- State and disclosures: `app/src/main/java/com/tx24/spicyplayer/playback/ExternalPlaybackViewModel.kt`, `playback/TranslationSettingsStore.kt`, `network/data/SourceDisclosures.kt`, `network/data/providers/UnisonLyricsProvider.kt` (relative to the same source root).
- Settings and backup: `app/src/main/java/com/tx24/spicyplayer/ui/settings/TranslationSettings.kt`, `ui/settings/AppSettings.kt`, `ui/settings/SettingsScreen.kt`, `ui/settings/QuickSettings.kt`, `backup/SettingsBackup.kt`, `backup/SettingsBackupStore.kt`.
- UI and renderer: `app/src/main/java/com/tx24/spicyplayer/MainActivity.kt`, `ui/controls/LyricsControls.kt`, `lyrics/spicy/models/Line.kt`, `lyrics/spicy/parser/LetterSynthesizer.kt`, `lyrics/spicy/animation/LyricsAnimator.kt`, `lyrics/spicy/canvas/LayoutModels.kt`, `lyrics/spicy/canvas/LyricsLayoutCalculator.kt`, `lyrics/spicy/canvas/LyricsRenderer.kt`, `lyrics/spicy/canvas/LyricsView.kt`, `lyrics/spicy/canvas/TranslationRenderer.kt`.
- Tests: `app/src/test/java/com/tx24/spicyplayer/translation/TranslationTest.kt`, `app/src/test/java/com/tx24/spicyplayer/backup/SettingsBackupTest.kt`.
