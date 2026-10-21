# Field Launcher

A personal Android TV launcher for one Hisense Google TV, in the andrewevmiller.com "field notebook"
design language: warm charcoal, a dot-field paper texture, IBM Plex Serif / Geist / IBM Plex Mono and one
soft-purple accent. D-pad first, with a small generative sound system and two screensavers.

- **Home:** a feature band (what you opened last), category chips, your apps as a 4-column grid ending in
  an "All apps" tile, Recent (your own launch history), screensaver tiles, and the TV's inputs.
- **Apps are a grid**, not a carousel. Hold OK on a tile to move it (arrows), drop it with OK, cancel with Back;
  Unpin and Hide appear under the grid while editing.
- **Sound:** near-silent navigation ticks; a voiced select/back; a short per-app launch motif generated from
  the app's package name. Seven waveforms, chosen in Settings.
- **Screensavers:** our Oxyfield dot field, or Google TV's own ambient mode (your Google Photos albums).
- **Safety:** one script installs it as Home, one restores your previous home screen, and two crashes in a
  minute hand Home back automatically.

Visual and interaction reference: [`docs/mockup/field-launcher-mockup.html`](docs/mockup/field-launcher-mockup.html)
(also published as an artifact). The plan and status: [`docs/BUILD_PLAN.md`](docs/BUILD_PLAN.md).
Agent rules and file ownership: [`CLAUDE.md`](CLAUDE.md).

## Install on the TV

You need the Android SDK (API 34), JDK 17 or newer, and `adb` connected to the TV
(Developer options → USB/network debugging, then `adb connect <tv-ip>`).

```bash
./scripts/probe.sh     # optional: prints what the TV reports (model, Android, current home, screensaver)
./scripts/deploy.sh    # build, install, grant the one permission, make Field Launcher the home screen
```

`deploy.sh` is safe to run again. Before it changes anything it records your current home screen, the state
of Google's launcher packages and your screensaver in `scripts/.deploy-state` (never committed), and it also
leaves a copy of the previous home in a TV setting so the app can fall back on its own. It never enables or
disables Google's launcher. `./scripts/deploy.sh --dry-run` prints every adb command without running any.

### The one-time permission

Settings can switch the system screensaver only if the app holds `WRITE_SECURE_SETTINGS`, which Android
doesn't let you grant in the UI. `deploy.sh` does it for you; by hand:

```bash
adb shell pm grant com.andrewevmiller.tvlauncher android.permission.WRITE_SECURE_SETTINGS
```

Without it everything else works; Settings shows "Run scripts/deploy.sh once to allow switching".

### Getting your old home screen back

```bash
./scripts/rescue.sh           # previous home (today: the gathin launcher) + your old screensaver
./scripts/rescue.sh --stock   # re-enable Google TV's own launcher and make it home
```

If the previous home has been uninstalled, `rescue.sh` switches to `--stock` by itself.

If the launcher crashes twice within a minute, it hands Home back to the previous launcher and shows a notice
the next time you open it by hand. Run `./scripts/deploy.sh` to make it Home again. To test that:
`adb shell am start -n com.andrewevmiller.tvlauncher/.MainActivity --ez field.forceCrash true`, twice in a minute
(debug builds only).

## Using it

- **Home button** returns to Home from anywhere and resets it (filter cleared, Recent collapsed, focus on your apps).
- **Menu key** on the remote, or the SETTINGS chip top right, opens launcher Settings.
- **Settings:** Appearance (dark/light, reduced motion, dot-grid brightness), Sound (on/off, waveform, per-app
  motif re-roll and mute), Screensaver (Oxyfield / Google Photos, Start now), About. Every change applies
  immediately.
- **Sounds** follow the system "sound effects" setting as well as the launcher's own toggle.
- **Screensaver tiles** on Home start Google Photos' ambient mode or Oxyfield right now. If the TV refuses an
  on-demand start, you get a short message and the screensaver runs when the TV idles.

## Two on-device questions the spike answers

`spike/` is a separate small app that checks (1) whether the TV lets a third-party app read other apps'
"continue watching" rows, and (2) which method can start the screensaver on demand. Run it once; the steps and a
decision table are in [`spike/README.md`](spike/README.md). Until then Recent and the feature band use the
launcher's own launch history.

## Developing

```bash
./gradlew assembleDebug testDebugUnitTest    # APK + all unit tests (Robolectric UI tests included)
./gradlew installDebug                       # to a connected TV
./gradlew :app:lintDebug
```

Two helpers exist for the cloud sessions this was built in (shared machine, lock-serialised, warm daemon):
`tools/compile.sh [gradle tasks]` for the Android build, and `tools/purecheck.sh` for `tools/purecheck`, a plain-JVM
build that compiles and tests every Android-free source file (the DSP, motif generator, navigation rules,
codecs and so on). `tools/sound` renders every sound to WAV files for desktop listening
(`gradle -p tools/sound run --args="--out build/wavs"`, then open `index.html`).

Things worth tuning by eye and ear on the TV:

| Knob | Where |
|---|---|
| Interface sound loudness | `OUTPUT_GAIN_DB` in `sound/FieldSoundEngine.kt` (starts at +10) |
| First-tick latency after idle | `TAIL_MS` in `sound/FieldSoundEngine.kt` |
| Dot-grid visibility | Settings → Appearance → Dot-grid brightness |
| Banner sharpness | `BANNER_WIDTH_PX` / `BANNER_HEIGHT_PX` in `home/data/ArtworkLoader.kt` |

## Fonts

IBM Plex Serif, IBM Plex Mono and Geist are bundled under the SIL Open Font License; see `licenses/`.
