# Field Launcher: agent guide

A personal Android TV launcher for one Hisense TV, in the andrewevmiller.com
"field notebook" design language. Kotlin + Compose for TV. Dark mode is the default.

## Design rules (non-negotiable)
- Colors only from `theme/Tokens.kt` via `Field.colors`. Never hard-code a hex in feature code.
- Accent (`#B98FD6`) = the focused element plus at most one other emphasis per screen. Never a large fill.
- Type only from `Field.type`: Plex Serif for headings, Geist for body, Plex Mono for labels/dates/captions.
  Left-aligned, never justified or centered body text.
- Focusable things use `Modifier.fieldFocus()` / `Modifier.fieldTile()`. Don't invent another focus style.
- Hairlines (`FieldDimens.hairline`, `line` color) separate sections; no cards with shadows, no colored bars.
- Icons are line glyphs: 1.6–1.8 stroke at 24dp, round caps/joins, tinted `muted` (accent only when focused).
- Respect `Field.reducedMotion`: snap or show a static frame.
- Everything inside the 5% safe area (`FieldDimens.safeHorizontal/Vertical`).
- Sound follows the accent rule: navigation is near-silent; only launch/select get a voiced sound.
  Use `FieldSound` (agent F), never play audio directly. Respect the system sound-effects setting.
- Sound character: harsh wavefolded triangle. 1–2 slightly detuned triangle oscillators -> sine
  wavefolder -> tanh saturation (each sound's drive x1.6 global multiplier; optional amplitude bitcrush) -> resonant low-pass sweep, with a
  band-passed noise click on the attack. Master high-pass ~70Hz and a compressor. The reference
  recipe is the `sfx` block in `docs/mockup/field-launcher-mockup.html`; match it by ear.
- Waveform is a user setting (Settings → Sound → Waveform), default Saw. Options:
  Sine, Triangle, Saw, Square, Supersaw (7 detuned saws, ±22 cents), White noise, Pink noise.
  Noise voices are pitched by a narrow resonant band-pass (Q≈30) at each note's frequency with
  make-up gain (~24x white, ~36x pink) and +5dB output level. Changing the setting re-renders the cached sound set and all launch motifs, then
  plays a short preview (two ticks, select, a launch motif). The mockup's waveform picker is the reference.
- Per-app launch motifs: FNV-1a of the package name seeds mulberry32; 3–4 always-rising notes in
  A minor pentatonic from A3, 35–70ms apart, optional held last note, timbre by `AppCategory`, plus a
  sub two octaves under the first note. Per-launch humanizing: ±5 cents, ±10% level. Port the
  generator exactly (same hash, PRNG and draw order) so the TV plays the motifs auditioned in the mockup.

## Product decisions
- App tiles are 192x108dp (`FieldDimens.bannerWidth/Height`). Apps without a 16:9 banner show
  their icon large (~44dp), not a small glyph in the corner.
- Settings → Sound: interface sounds on/off, waveform picker (see Design rules), per-app motif
  re-roll / mute.
- The Recent row is collapsed by default: a single "Show recent" chip with a hidden count.
  Expanding is per-session; it collapses again on Home.

## Photos slideshow
Google Photos content isn't reachable through a public API for this use (Library API is limited to
app-created media since 2025). We reuse the system's own ambient mode instead: the launcher can make
it the active screensaver (`Settings.Secure.screensaver_components`, needs `WRITE_SECURE_SETTINGS`
granted once via `adb shell pm grant`) and start it on demand. Album choice stays in Google's settings.

## Ownership: who may edit what
Parallel agents each own a directory. **Do not edit files outside your area.** If you need a change
to a shared file, describe it under "Requested shared changes" in your final report and the
integrator will apply it.

| Area | Owner |
|---|---|
| `build.gradle.kts`, `app/build.gradle.kts`, `gradle/`, `AndroidManifest.xml`, `res/values/`, `theme/`, `home/HomeScreen.kt`, `home/data/AppsRepository.kt`, `MainActivity.kt` | Integrator (shared contracts) |
| `scripts/`, `app/.../safety/` | Agent A: scripts & safety |
| `app/.../home/apps/`, `app/.../home/data/` (except `AppsRepository.kt`) | Agent B: home core |
| `app/.../ui/components/`, `app/.../ui/icons/` | Agent C: components |
| `app/.../ambient/` | Agent D: ambient (Oxyfield screensaver + hand-off to Google's photo screensaver) |
| `spike/` (separate Gradle module, not included in `:app`) | Agent E: spikes (Watch Next access; starting Google's photo screensaver on demand) |
| `app/.../sound/`, `app/src/main/res/raw/`, `tools/sound/` | Agent F: interface sounds |

New dependencies: list them in your report; don't edit the version catalog.

## Verification
The cloud environment can't download the Android SDK, so builds are verified on the owner's
machine with `./gradlew installDebug` against the TV over adb. Keep code conservative:
stable Compose / tv-material APIs, no experimental opt-ins unless unavoidable.

## Target device
Hisense "SmartTV 4K", **Google TV on Android 12 (API 31)**, MediaTek SoC. Probe taken 2026-10-21.
- UI renders at 1920x1080 (override of a 3840x2160 panel), density 320 → a 960x540dp canvas,
  which is what `FieldDimens` assumes. 1dp = 2px.
- Current HOME default is a third-party launcher, `com.gathin.tvhome/.WiiMenuActivity`, so this TV
  already honors a non-Google default home. `deploy.sh` sets ours with
  `cmd package set-home-activity`; `rescue.sh` restores whatever deploy recorded as the previous
  default (today: the gathin launcher), not necessarily Google's.
- Stock packages present: `com.google.android.apps.tv.launcherx`,
  `com.google.android.tungsten.setupwraith`. Leave their enabled/disabled state as deploy finds it;
  record it so rescue can put it back.
- Screensaver today: `com.google.android.apps.tv.dreamx/.service.Backdrop` (Google TV ambient mode,
  the Google Photos slideshow). This is the `GOOGLE_PHOTOS` choice in settings.
- TV inputs go through the MediaTek TV Input Framework (`com.mediatek.dtv.tvinput.*`), so system
  input tiles use standard TIF passthrough intents first.
