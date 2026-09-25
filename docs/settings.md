# Adding a setting

The settings screen lives in `ui/settings/`; its controls in `ui/components/SettingsControls.kt`.

1. **Pick its page.** The groups are This song, Lyrics, Sync, Sources and Advanced (`SettingsPage` in `ui/settings/SettingsScreen.kt`). Only make a new group when none fits. A new group needs a `SettingsPage` entry, a row on the first page (`HomeGroups`) with a live one-line summary, a case in `PageContent`, and a placeholder in `PageSkeleton`.
2. **Store it where it's used.**
   - How the lyrics look (e.g. word motion): the `ui` SharedPreferences in `MainActivity`, passed in through `LyricsPreferences`.
   - Lookup, sources or playback: a field in `PlayerUiState` plus a view-model function that saves it.
   - Keep the logic free of Android code where possible, for the Kotlin Multiplatform move after v1.
3. **Build it from the shared controls**, never Material ones:
   - `ToggleRow` for on/off;
   - `SettingRow` with an `SlButton` or `SlTextField` for actions and text;
   - `SettingRow(stacked = true)` with `SlBipolarSlider` for amounts.

   Wording follows Spicy Lyrics: a short label, and a one-sentence description that ends with a full stop.
4. **Search matches rows by their label and description automatically.** Anything else (a card, a note) needs wrapping in `Searchable(...)`, or search won't find it. A page's first rows go without a section title, because the page header already names them. Later groups on the same page go in `SettingsSection("Title")`.
5. **Keep the placeholder honest.** If a page gains or loses several rows, update its row count in `PageSkeleton`. If the first page's summary for that group should mention the new setting, update it too.
6. **Don't repeat what the lyrics screen already has.** For example, romanize is a button there.
