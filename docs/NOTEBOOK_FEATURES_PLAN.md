# Notebook features: plan

Status: **planned, nothing built.** Three small features that lean into the field-notebook look: an **instrument
readout**, a **margin note** and a **viewing log**. They extend `docs/BUILD_PLAN.md`; `CLAUDE.md` rules apply
unchanged (tokens only, accent = focus plus at most one other emphasis, hairlines not cards, mono for labels).
The sleep timer has its own plan, `docs/SLEEP_TIMER_PLAN.md`.

## Order

1. **Instrument readout** first: no unknowns, no permissions beyond two normal ones, and it doubles as the TV
   self-test the project still needs (see N1).
2. **Margin note**: small, with one risk (typing on a TV).
3. **Viewing log**: the largest, needs a one-time adb grant and a new data store.

None needs a separate spike. All three degrade gracefully if the TV disagrees with an assumption, and the readout
page itself shows which assumptions held. That saves a round trip to the TV, unlike the sleep timer, where a wrong
guess would leave the feature useless.

## Shared decisions

- **A "Tools" row on Home**, under "Inputs & system": Sleep timer, Viewing log, Readout. Keeps the system row about
  inputs and TV settings, and gives these three one home. (This replaces the sleep plan's "tile in the Inputs and
  system row".) Muted line icons, accent on focus only. Needs new glyphs: moon, log (ruled lines); the readout reuses
  `points`.
- **Routes:** `Route.Log` and `Route.Readout` in `FieldRoot`, each full screen, Back returns to Home with focus on the
  tile it came from.
- **The mockup gets each feature** (tile, screen, interaction) before or with the Android work, and is republished,
  as it was for the grid.
- **deploy.sh** grows one more one-time grant (usage access, N3). `rescue.sh` does not need to undo it.

## N1: Instrument readout

A mono page of label/value lines separated by hairlines, updating live. Two blocks:

| Block | Lines |
|---|---|
| Device | Model and build; Android version and **security patch**; uptime (`3d 04:12:09`); storage free of total; memory available of total; display (1920x1080, 320 dpi, refresh rate); launcher version |
| Network | Connection (Wi-Fi or Ethernet); **IP address** (handy for `adb connect`); link speed; internet reachable yes/no |
| Checks | `WRITE_SECURE_SETTINGS` granted; usage access granted; previous home recorded and still installed; current screensaver and whether it is ours; Watch Next readable yes/no/unknown |

The "Checks" block is the self-test: each line reads `ok`, `missing` or `unknown` in muted text (no coloured
status), so a single visit to this page confirms a deploy worked. SSID is left out on purpose: reading it needs the
location permission.

| # | Work | Notes |
|---|---|---|
| N1.1 | Pure `ReadoutFormat`: bytes to `12.4 GB`, uptime, patch date, link speed, check labels | Plain-JVM tests |
| N1.2 | `DeviceFacts` interface plus Android implementation (`Build`, `StatFs`, `ActivityManager.MemoryInfo`, `ConnectivityManager`, `Display`, `PackageManager`) and a fake | Manifest: `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` (both normal permissions) |
| N1.3 | `ReadoutScreen` (Compose): scrolls with the D-pad, refreshes uptime each second and network on change | Reduced motion has nothing to snap; no animation |
| N1.4 | Tools-row tile, route, Back handling; mockup page | |
| N1.5 | Tests: format unit tests; Robolectric screen test with a fake `DeviceFacts` (every line present, scrolls, Back); screenshot | |

## N2: Margin note

One short line of your own text, e.g. "finish S2 of Severance", shown on Home between the header and the feature
band as `NOTE  finish S2 of Severance` in mono, `muted`, with a hairline. It is a focus stop: OK opens an editor,
and saving an empty note removes the line.

- **Storage:** a `marginNote` field in `LauncherSettings` (DataStore), max 80 characters, newlines stripped. Old
  stores load with an empty note.
- **Editor:** a single-line text field using the TV's on-screen keyboard, with voice input when the remote has a
  microphone button. Done saves, Back discards. This is the one real risk: how the keyboard behaves inside a
  D-pad-driven Compose screen on Google TV can only be judged on the TV.
- **Fallback that always works:** `adb shell settings put secure field_launcher_note "text"`. The launcher reads it
  (reading `Settings.Secure` needs no permission, the same trick as the recorded previous home) and shows it, and a
  `scripts/note.sh "text"` helper wraps the command. If typing on the TV turns out to be unpleasant, this makes the
  feature usable from a laptop or phone.
- **Home focus:** the note adds a stop between the header chip and the feature band, so the existing Home focus
  tests (up from the feature band reaches the chip, and so on) need updating. When there is no note, show a quiet
  `NOTE  add a note` line only while it is focused, so an unused feature takes no space.

| # | Work | Notes |
|---|---|---|
| N2.1 | `marginNote` in `LauncherSettings` and `SettingsCodec` (clamp, strip, default) | Round-trip and bad-input tests |
| N2.2 | Secure-setting reader merged with the stored note (stored wins; setting is the fallback), pure rule plus Android glue | |
| N2.3 | `MarginNote` row composable, editor dialog | Accent on focus only |
| N2.4 | Wire into `HomeContent`, update Home focus and sound-row tests, mockup | |
| N2.5 | `scripts/note.sh`, README | Shell tests like the other scripts |

## N3: Viewing log

A page of what you watched: a weekly summary, then dated sessions. Not the Recent row: Recent keeps the latest
launch per app, capped at 12, and has no durations, so this needs its own store.

**How durations are known.** `UsageStatsManager.queryEvents` reports when each app came to the foreground and left
it, for every app, including ones started from the remote's app buttons. It needs usage access, which the system
shows as the `GET_USAGE_STATS` appop and which `adb shell appops set com.andrewevmiller.tvlauncher GET_USAGE_STATS allow`
grants once (added to `deploy.sh` next to the `WRITE_SECURE_SETTINGS` grant). The system only keeps these events for
about a week, so the launcher copies them into its own log each time Home opens.

**What counts as a session.** A pure `SessionBuilder` turns events into sessions: it ignores the launcher, system
UI and the screensavers; joins sessions of one app less than 2 minutes apart; drops sessions under 1 minute; closes
a session when the screen goes non-interactive; and caps one session at 8 hours so a TV left on a paused video
cannot run for a day. All thresholds are constants with tests.

**Honest limits.** The duration is time the app was in front, not time a video was actually playing. Music apps
playing in the background show only the time they were on screen. Anything before the grant is not recorded.

| # | Work | Notes |
|---|---|---|
| N3.1 | Pure `SessionBuilder` (events to sessions, the rules above) | Plain-JVM tests, including standby and overlap cases |
| N3.2 | Pure `ViewingStats` (week and month totals, per-day totals, top apps, share of category) and `ViewingLogCodec` (tab-separated lines like `LaunchLog`, forgiving decode, prune past 90 days) | Plain-JVM tests |
| N3.3 | Android: `UsageEventsSource` over `UsageStatsManager`, `ViewingLogStore` (file in app storage), `UsageSync` run when Home resumes, `hasUsageAccess()` | Missing grant gives an empty state that says how to grant it |
| N3.4 | `ViewingLogScreen`: header, `THIS WEEK  14 h 20 m · 11 sessions`, one text bar per day (block characters in `ink`/`muted`, no coloured bars), top apps with duration, then sessions grouped by day. Chips: This week, Last week, Month. Rows focusable so the D-pad scrolls | Accent on the focused row only |
| N3.5 | Settings: "Viewing log" on/off, "Clear log" (confirm by a second OK) | Log is on-device only; nothing leaves the TV |
| N3.6 | Tools-row tile, route, `deploy.sh` grant, README; mockup page with sample data | |
| N3.7 | Tests: Robolectric screen tests with a fake store; screenshot of filled and empty states | |

## Verification

Cloud: purecheck, `:app:assembleDebug testDebugUnitTest lintDebug`, screenshots of every new screen and state.
TV: the readout's own "Checks" block after a deploy; typing a note on the remote (and the `note.sh` fallback);
whether the usage-access grant works over adb on this TV and that real sessions appear after watching something.

## Open choices (recommendations in brackets)

- Log retention [90 days] and minimum session [1 minute].
- Whether the note line is a focus stop [yes] or only editable in Settings.
- Whether the log is on by default once usage access is granted [yes, with a visible Clear log].
