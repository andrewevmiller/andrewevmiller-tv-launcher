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
- **Sleep timer:** pick a length; when it runs out the launcher pauses what is playing, plays a short closing
  sound and fades the picture to a dark dot field with a faint clock. It does not turn the TV off.
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

The **Viewing log** needs usage access, the `GET_USAGE_STATS` appop, which Android also doesn't offer in the UI.
`deploy.sh` grants it with the same kind of one-time command:

```bash
adb shell appops set com.andrewevmiller.tvlauncher GET_USAGE_STATS allow
```

Without it the log shows an empty page with that command, and records nothing new. Nothing before the grant is recorded.

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
- **Menu key** on the remote, or the LAUNCHER SETTINGS chip top right, opens launcher Settings.
- **Settings:** Appearance (dark/light, reduced motion, dot-grid brightness), Sound (on/off, waveform, per-app
  motif re-roll and mute), Screensaver (Oxyfield / Google Photos, Start now), Sleep (default length), About.
  Every change applies immediately.
- **Sounds** follow the system "sound effects" setting as well as the launcher's own toggle.
- **Screensaver tiles** on Home start Google Photos' ambient mode or Oxyfield right now. If the TV refuses an
  on-demand start, you get a short message and the screensaver runs when the TV idles.

### Sleep timer

The **Sleep timer** tile is in the **Tools** row at the bottom of Home. Its caption reads "Off", or
"Sleep · 42 min" counting down.

- **OK on the tile** opens a row of lengths under it: Off, 15, 30, 45, 60, 90, 120 minutes. Focus starts on your
  default length (Settings → Sleep → Default length; 45 until you change it). Pick one to start the timer.
- **While it runs** the same row also offers **+15 min** (never more than 240 min from now) and **Cancel**. Back
  closes the row.
- **Key presses do not cancel the timer.** Use the tile.
- **When it runs out,** with whatever app you were in still in front:
  1. the launcher pauses the media (a pause key, then a retry, then audio focus; it checks that the audio stopped);
  2. it plays a short descending closing sound (it follows the interface-sounds switch and the system
     sound-effects setting);
  3. it fades the picture to a near-black Oxyfield dot field over 45 s and holds one still frame, with a faint clock
     to the minute (no seconds). Any key wakes it; the media stays paused.
  If the TV is already off when the time comes, nothing happens.
- **What it does not do:** it does not power the TV off (the launcher has no permission for that), and an LCD
  backlight stays on behind the dark picture. Real power-off stays with the TV's own "turn off after" setting.
  Our own dim screen also lets go by itself after 30 minutes.
- **It survives the launcher being killed** while another app is in front (it is an Android alarm). A reboot or a
  force-stop cancels the alarm; the launcher re-arms a timer that is still in the future the next time it starts,
  which is at boot because it is Home. A reinstall (`deploy.sh`) may cancel one too, so install while no timer is running.
- **Pausing depends on the app.** Some apps ignore a pause key from outside. Which method works best on this TV, and
  whether the dim screen can start over another app without an extra permission, is what the spike answers (below).
- **If it did not behave,** the log of the last run: `adb logcat -s FieldSleep`, or on a debug build
  `adb shell run-as com.andrewevmiller.tvlauncher cat shared_prefs/field_sleep.xml`.

The normal Oxyfield screensaver clock also shows seconds now.

### Viewing log

The **Viewing log** tile sits next to the sleep timer in the **Tools** row. It shows what you watched: a summary
(`THIS WEEK  14 h 20 m · 11 sessions`), one bar per day, the top apps with their time, then the sessions by date.
The chips switch between This week, Last week and Month. Back returns to Home with focus on the tile.

- **What a session is:** the time an app was in front, from when it came up to when it left, with the screen on.
  Sessions of one app less than 2 minutes apart are joined, anything under 1 minute is dropped, and one session
  is capped at 8 hours. The launcher, system UI and the screensavers are not recorded.
- **The honest limits:** this is time an app was in front, not time a video was playing. Music that plays in the
  background shows only the time its app was on screen. Anything before the usage-access grant is not recorded.
- **Where it lives:** one file in the launcher's app storage on the TV, kept 90 days. Nothing leaves the TV.
  The log is on by default once usage access is granted. Settings → Viewing log turns it off (nothing is read or
  written while it is off), and Clear log (press OK twice) empties it.

### Margin note

A **margin note** is one short line of your own text, shown on Home between the header and the feature band:
`NOTE  finish S2 of Severance`. It is in the mono style, muted, with a hairline under it.

- **OK on the note line** opens the editor. Type with the TV's on-screen keyboard. **Done** saves; **Back**
  discards. Saving an empty note removes the line, unless a laptop note is set (see below), which then shows again.
- With no note, the line shows only while it is focused (`NOTE  add a note`), so an unused note takes no space.
- The note is at most 80 characters, on one line. Newlines and control characters are dropped, and the ends are trimmed.
- **From a laptop:** `scripts/note.sh "text"` sets it over adb (`scripts/note.sh ""`, or no text, clears it). It is
  the fallback when typing on the TV is awkward. The launcher shows it only while its own note is empty, so a note
  typed on the TV wins. To remove both, clear the laptop note too (`scripts/note.sh` with no text).
- **Where it lives:** on the TV only, in the launcher's settings. Nothing is sent anywhere.

## Three on-device questions the spike answers

`spike/` is a separate small app that checks (1) whether the TV lets a third-party app read other apps'
"continue watching" rows, (2) which method can start the screensaver on demand, and (3) for the sleep timer, which
method pauses another app's media and whether a dim screen can be shown from the background. Run it once; the steps
and decision tables are in [`spike/README.md`](spike/README.md) (the sleep timer is section 6). Until then Recent
and the feature band use the launcher's own launch history, and the sleep timer tries its default order.

After the sleep-timer run, set `SleepConfig.PROVEN_PAUSE_RUNG` and `PROVEN_DIM_STRATEGY` in
`sleep/SleepConfig.kt` to the winners and rebuild. If the spike says the dim screen cannot start from the
background without it, grant the overlay appop once (and add it to `deploy.sh`):

```bash
adb shell appops set com.andrewevmiller.tvlauncher SYSTEM_ALERT_WINDOW allow
```

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
| Sleep screen: faintest dots, fade length | `FLOOR` (0.05), `FADE_MILLIS` (45 s) in `ambient/SleepDimMath.kt` |
| Sleep screen: how faint the clock is | `CLOCK_FLOOR` (0.15, about 35/255) in `ambient/SleepDimMath.kt` |
| Closing sound loudness | `gain` in `FixedSounds.windDown()` (`sound/synth/FixedSounds.kt`) |
| Pause method and dim method that go first | `PROVEN_PAUSE_RUNG`, `PROVEN_DIM_STRATEGY` in `sleep/SleepConfig.kt` |
| Let the TV's own screen-off timeout run during the dim screen | `DIM_KEEPS_SCREEN_ON` (false) in `sleep/SleepConfig.kt` |

### Judging speed (release build)

```bash
./gradlew installRelease    # use this one to judge speed: R8-optimized, like the real thing
./gradlew installDebug      # for development; noticeably slower on the TV
```

Debug and release share an application id and signing key, so either installs over the other.

After every install, precompile the app on the TV so it runs ahead-of-time compiled instead of
warming up on every cold start. Reinstalling resets this, so repeat it each time:

```bash
adb shell cmd package compile -f -m speed com.andrewevmiller.tvlauncher
adb shell dumpsys package com.andrewevmiller.tvlauncher | grep -i "status="   # expect status=speed
```

Release crashes can be deobfuscated with `app/build/outputs/mapping/release/mapping.txt`.

### Keeping your pins
Pins and their order are kept in the app's saved data. Updating with `./gradlew installDebug` keeps them. An uninstall, or installing a build signed with a different debug key, deletes them. Around those, back them up:

```powershell
./tools/pins.ps1 save      # before uninstalling
./tools/pins.ps1 restore   # after installing again
```

The backup is written to `%USERPROFILE%\.field-launcher\pins.xml`. Add `-IncludeHistory` to keep Recent and the featured band too, `-Serial` to pick a device, `-File` to choose where the backup goes. It needs a debug build.

### Featured band
The band at the top of Home shows what is playing right now (title, artist or episode, a Play/Pause button, album art when the app provides it). When nothing is playing it turns through your four most recent apps every six seconds.

Reading what other apps are playing needs notification-listener access, which the TV has no screen for. Grant it once over adb (the launcher never reads a notification):

```powershell
./tools/now-playing.ps1 enable    # also: disable, status
```

Installing a new build switches it off again, and so does an uninstall, so run `enable` again after every install.

### Remote buttons
Some buttons are taken by the TV's system before any app sees them. On this TV the Settings, Tuner and User buttons go to a Google TV launcher that is disabled, so they do nothing. The launcher can take them over as an accessibility service, which Android keeps off until you switch it on:

```powershell
./tools/remote.ps1 enable    # also: disable, status
```

Out of the box the Settings button opens a notifications panel (with an Open Settings shortcut) and the Tuner button opens Stremio. The service reads nothing from the screen, only key presses, and only touches the buttons listed in `RemoteKeys` (`app/.../remote/RemoteKeyService.kt`). Back, Home, Power, the arrows, OK and volume can never be remapped. `disable` puts every button back. Installing a new build switches the service off again (as does an uninstall), so run `enable` again after every install.

## Fonts

IBM Plex Serif, IBM Plex Mono and Geist are bundled under the SIL Open Font License; see `licenses/`.
