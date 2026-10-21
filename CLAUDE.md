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
  wavefolder -> tanh saturation (optional amplitude bitcrush) -> resonant low-pass sweep, with a
  band-passed noise click on the attack. Master high-pass ~70Hz and a compressor. The reference
  recipe is the `sfx` block in `docs/mockup/field-launcher-mockup.html`; match it by ear.

## Product decisions
- App tiles are 192x108dp (`FieldDimens.bannerWidth/Height`). Apps without a 16:9 banner show
  their icon large (~44dp), not a small glyph in the corner.
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
Hisense TV over adb; exact model/OS pending `scripts/probe.sh` output. minSdk 28 until then.
If the TV is Google TV, `deploy.sh` must disable `com.google.android.apps.tv.launcherx`,
and `rescue.sh` must re-enable it.
