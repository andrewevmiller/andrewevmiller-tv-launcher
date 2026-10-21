# Field Spike: E1 (Watch Next) and E2 (screensaver on demand)

One small standalone app that answers two questions on the owner's Hisense Google TV
(Android 12, API 31, MediaTek). It is a separate Gradle build. It is **not** included from the
root `settings.gradle.kts` and does not touch `:app`.

| ID | Question | Why it matters |
|---|---|---|
| E1 | Can a third-party app read the Watch Next / preview-channel rows other apps publish? | Decides the source of the launcher's Recent row ("continue watching" vs. our own launch history) |
| E2 | Can we start the active screensaver on demand? | Decides how the Photos tile behaves (start the slideshow vs. only switch the screensaver and show a hint) |

The app has no dependencies beyond the Android framework (plain `Activity` + Views, Kotlin stdlib).
Application id `com.andrewevmiller.spike`, minSdk 31, shows up in the TV app list as **Field Spike**.

Everything on screen is also written to logcat: `adb logcat -s FieldSpike`.

## 1. Build and install

From the repo root, with the TV connected over adb (`adb connect <tv-ip>:5555`, then `adb devices`):

```bash
./gradlew -p spike installDebug
```

Notes:

- `-p spike` makes Gradle use `spike/settings.gradle.kts`, so the root build is not involved.
- Gradle only reads `local.properties` from the build root. If your Android SDK path lives in the
  repo-root `local.properties`, either `export ANDROID_HOME=~/Android/Sdk` (any path to your SDK) or
  copy that file to `spike/local.properties` (it is git-ignored). Needs SDK platform 34 and JDK 17.
- Build only, install by hand:
  `./gradlew -p spike assembleDebug && adb install -r spike/app/build/outputs/apk/debug/app-debug.apk`
- Start it from the TV's app list ("Field Spike"), or:
  `adb shell am start -n com.andrewevmiller.spike/.MainActivity`
- Remove it afterwards: `adb uninstall com.andrewevmiller.spike`

## 2. Using the screen

- Left column: buttons. **Up/Down** moves, **OK** presses. The focused button has the purple outline.
- The purple line at the top of the left column shows the current E1 permission state and whether
  `WRITE_SECURE_SETTINGS` is granted. It refreshes when you come back to the app.
- Right column: results. Press **Right** to focus it (outline turns purple), then **Up/Down** scrolls,
  **Left** returns to the buttons. If Right does not work on your TV, use the "Results page up/down" buttons
  at the bottom of the left column. New lines auto-scroll to the bottom.
- Large text is for sitting on the couch; the logcat copy is for pasting.

## 3. E1: Watch Next / preview channels

The app queries `TvContract.WatchNextPrograms.CONTENT_URI`, `TvContract.PreviewPrograms.CONTENT_URI`
and `TvContract.Channels.CONTENT_URI` (no selection, projection `_id, package_name, title`;
channels use `display_name` and also `type`), counts rows, tallies rows per `package_name`, logs the first few
`package | title` pairs, and classifies each query as exactly one of. Note that the channels table also holds the
TV inputs' ordinary broadcast channels (for example the MediaTek DTV input), so rows from `com.mediatek...` there are
**not** Watch Next evidence; the channels line also reports how many rows are `type=TYPE_PREVIEW`:

| Outcome | Meaning |
|---|---|
| `SECURITY_EXCEPTION` | The provider refuses us. Message is shown. |
| `ZERO_ROWS` | Query allowed but empty: either nobody has published, or other apps' rows are filtered out for us. |
| `OWN_ROWS_ONLY` | Rows exist but all belong to this app (the spike never writes, so you should not see this). |
| `ROWS_FROM_OTHER_APPS` | Real rows from other packages. The launcher can read them. |
| `NULL_CURSOR` / `OTHER_ERROR` | Provider not reachable or something unexpected; the message is shown. |

There is one install and **three permission states**, driven by adb grants. The manifest declares both
permissions; to the provider, "declared but not granted" is the same as "not declared".

| State | What is granted | How to get there |
|---|---|---|
| 1 | Neither permission | `adb shell pm revoke com.andrewevmiller.spike com.android.providers.tv.permission.READ_EPG_DATA` (an error that it is not granted is fine) |
| 2 | `READ_EPG_DATA` | `adb shell pm grant com.andrewevmiller.spike com.android.providers.tv.permission.READ_EPG_DATA` |
| 3 | `ACCESS_ALL_EPG_DATA` | `adb shell pm grant com.andrewevmiller.spike com.android.providers.tv.permission.ACCESS_ALL_EPG_DATA` (**expected to be refused**: signature|privileged. Paste the error text) |

### Before you start: give Watch Next something to show

A "continue watching" entry only exists after you have **partly watched** something in an app that
publishes to Watch Next (YouTube, Netflix, Plex, Disney+, ...). Start a video in two or three apps,
watch a minute, back out. Note which apps in the template at the bottom.

### Steps

1. Open Field Spike. Confirm the top line says `STATE 1` (if it says STATE 2, run the State 1 revoke above, then
   `adb shell am force-stop com.andrewevmiller.spike` and relaunch).
2. Press **E1.0 Permission status**. It prints whether each permission is granted and how the system defines it
   (`dangerous`, `signature`, `privileged`). Paste those lines.
3. Press **E1.1 Run the 3 queries**. The result for STATE 1 is saved inside the app.
4. In a terminal: grant `READ_EPG_DATA` (State 2 command above). Paste the command's output. Back in the app the top
   line should now say `STATE 2` (relaunch the app if it does not update). Press **E1.1** again.
   - If `pm grant` refuses (E1.0 shows the real protection level; `dangerous` means grantable), press
     **E1.2 Ask for READ_EPG_DATA** on the TV instead and accept the dialog if one appears. Paste the refusal text.
5. Try State 3: run the `ACCESS_ALL_EPG_DATA` grant. Paste the output. If it was *not* refused, the top line says
   `STATE 3`: press **E1.1** again. If it was refused, there is nothing more to run for State 3; record the refusal.
6. Press **E1.3 Summary, all states** and paste the whole block.

Optional ground truth (the adb shell user has broad rights and may see what an app cannot). If these print rows
while the app saw `ZERO_ROWS`, the provider is hiding other apps' rows from us:

```bash
adb shell content query --uri content://android.media.tv/watch_next_program --projection _id:package_name:title
adb shell content query --uri content://android.media.tv/preview_program    --projection _id:package_name:title
adb shell content query --uri content://android.media.tv/channel            --projection _id:package_name:display_name
```

(The URIs are also printed on screen above each query; use what the app prints if they differ.)

## 4. E2: start the screensaver on demand

All strategies use public API only (activity intents, `PackageManager`, `Settings.Secure`); no reflection.
`android.service.dreams.Sandman` is a hidden class, so it is reached the way a normal app can reach it: through the
SystemUI `Somnambulator` activity (which calls `Sandman` internally) and the public dock category / dream settings
action.

Buttons:

| Button | What it does |
|---|---|
| E2.0 | Prints `screensaver_*` settings, whether `WRITE_SECURE_SETTINGS` is granted, whether `Somnambulator` exists and is exported, details of the active dream service, and everything in `com.google.android.apps.tv.dreamx` (activities and services with exported/permission flags). |
| E2.1 | Read-only sweep: lists every installed `DreamService` and what resolves for the dock and dream-settings intents. |
| E2.a | `ACTION_MAIN` to `com.android.systemui/.Somnambulator`, explicit component, `NEW_TASK`. |
| E2.b1 | Same plus `CATEGORY_DESK_DOCK` (Somnambulator's "start if docked" path). |
| E2.b2 | Implicit `ACTION_MAIN` + `CATEGORY_DESK_DOCK`, only if something resolves it. |
| E2.b3 | `Settings.ACTION_DREAM_SETTINGS` (the Daydream settings page; look for a "Start now" entry). |
| E2.c0 / E2.c `.Name` | One button per exported, enabled activity of `com.google.android.apps.tv.dreamx`, each launched explicitly. Press E2.c0 to rebuild the list. |
| E2.d1 | Reads and saves the current `screensaver_components`, then sets it to `com.google.android.apps.tv.dreamx/.service.Backdrop`. Needs `WRITE_SECURE_SETTINGS`. |
| E2.d2 | Restores the saved value. |
| Mark last attempt | After an attempt, record whether a screensaver visibly appeared. |
| E2 summary | Prints a table of every attempt in this session. |

Each attempt prints the intent, the `screensaver_components` value at that moment, the call result
(`OK (no exception)`, `SecurityException: ...`, `ActivityNotFoundException: ...`, `NOT RESOLVABLE`), and 2.5 s later
whether our window lost focus (`focusLost=yes` means something took the screen). `OK` only means the system accepted
the call; **what you see on the TV is the real answer**.

### Steps

1. One-time grant (needed only for E2.d), and note the baseline:
   ```bash
   adb shell pm grant com.andrewevmiller.spike android.permission.WRITE_SECURE_SETTINGS
   adb shell settings get secure screensaver_components      # expected: com.google.android.apps.tv.dreamx/.service.Backdrop
   adb shell settings get secure screensaver_enabled
   ```
2. Control experiment from the shell (tells "the platform path is broken on this TV" apart from "our app is refused"):
   ```bash
   adb shell am start -n com.android.systemui/.Somnambulator
   ```
   Did a screensaver appear? Press any remote key to wake it.
3. In the app: **E2.0**, then **E2.1**. Paste both outputs.
4. Press **E2.a**. Look at the TV for about 3 seconds. If a screensaver appeared, press any key to wake and come back to
   the app. Press **Mark last attempt: SHOWED** or **NOTHING**. Repeat for **E2.b1**, **E2.b2**, **E2.b3**, then every **E2.c** button.
   (E2.b3 and some E2.c activities open a normal screen; press Back to return and mark NOTHING unless a screensaver ran.)
5. Strategy d, point the screensaver at Backdrop and try again:
   - Press **E2.d1**. It prints `OK (...)` or the failure (for example `WRITE_SECURE_SETTINGS not granted`).
   - Press **E2.a**, **E2.b1**, **E2.b2** and the **E2.c** buttons again and mark each. The summary shows the
     `screensaver_components` value next to every attempt, so before/after is visible.
   - Press **E2.d2** to restore the previous value. If it ever fails, restore by hand:
     `adb shell settings put secure screensaver_components <value from the baseline above>`.
   - The TV already uses Backdrop (probe, 2026-10-21), so d1 may change nothing. For a meaningful test, first point the
     screensaver somewhere else (pick one from the E2.1 list), run **E2.a**, and check it shows *that* screensaver; then
     press **E2.d1** and run **E2.a** again. That proves both halves of the Photos hand-off: the app can switch
     the screensaver, and Somnambulator follows the setting.
     `adb shell settings put secure screensaver_components <package>/<class>`
6. Press **E2 summary** and paste the whole block.

Optional objective evidence while a screensaver is up:

```bash
adb shell dumpsys dreams
adb logcat -d | grep -iE "DreamController|DreamManagerService|Somnambulator|Sandman|FieldSpike"
```

If everything fails, also check `adb shell settings get secure screensaver_enabled`; if it is `0`, set it to `1`
(`adb shell settings put secure screensaver_enabled 1`) and repeat E2.a once. Note that in the results.

## 5. Decision table

### E1: Recent row source

Read the three tables of the E1 summary. "Real rows" means `ROWS_FROM_OTHER_APPS` in `WatchNext` (or `Preview`).

| Pattern in the summary | Meaning | Recent row source |
|---|---|---|
| State 1 (no permission) has real rows | Provider serves other apps' rows to anyone | **Watch Next**, no permission. Merge with our own launch history. |
| State 1 `SECURITY_EXCEPTION`; State 2 has real rows | Needs `READ_EPG_DATA`; `pm grant` works | **Watch Next**; launcher declares `READ_EPG_DATA`; `deploy.sh` grants it with `pm grant` (request this from the integrator). |
| State 1 and 2 `ZERO_ROWS` or `OWN_ROWS_ONLY`, but the shell `content query` shows rows | Provider filters rows to the caller's own package | Watch Next is **not** readable. State 3 decides below. |
| Only State 3 has real rows and `pm grant ACCESS_ALL_EPG_DATA` **succeeded** | Needs a privileged permission that adb can still grant | **Watch Next**, with `deploy.sh` granting `ACCESS_ALL_EPG_DATA` (ask the integrator; brittle across TV updates). |
| State 3 grant refused ("not a changeable permission type") and States 1 and 2 give nothing | Signature|privileged permission, unreachable for a sideloaded app | **No.** Recent row = our own launch history (B adds a log). |
| Everything `NULL_CURSOR` / `OTHER_ERROR` | TV provider missing or not visible on this firmware | **No.** Own launch history. |
| Every state `ZERO_ROWS` and the shell query is also empty | Inconclusive: nobody has published anything | Re-run after partly watching something in YouTube/Netflix/Plex (see "Before you start"). If still empty, own launch history. |
| `Preview` (or `Channels` rows with `type=TYPE_PREVIEW`) come from other apps, `WatchNext` does not | Channels (app-curated rows) are readable, "continue watching" is not | Prefer own launch history for Recent; optionally mirror one preview channel later. |

Short version: **yes** = any state shows `ROWS_FROM_OTHER_APPS` in `WatchNext` with a permission we can grant;
**no** = everything else, so Recent = our own launch history.

### E2: how the Photos tile starts the slideshow

An attempt **works** when its row reads `call=OK`, `focusLost=yes` and `visible=SHOWED`.

| Result | Meaning | Action for `ScreensaverController.startNow()` (D2/D3) |
|---|---|---|
| E2.a works | Somnambulator is exported and starts the active dream | Put **Somnambulator explicit intent first** in `StartStrategy` ordering. |
| E2.a `SecurityException` or `ActivityNotFoundException`, but the shell control (step 2) worked | SystemUI blocks third-party starts here | Try the next working row; otherwise "none works" below. |
| E2.a and the shell control both fail | Somnambulator absent or disabled on this firmware | Look at E2.0 ("Somnambulator: NOT FOUND" vs. disabled) and fall through. |
| E2.b1 or b2 works, a does not | Only the dock path is reachable | Put it **second**, after a. |
| An E2.c activity works | The dream package has a launchable entry point | Put **that activity** after the Somnambulator strategies (record the exact class name from the button). |
| Nothing works until d1, then a/b/c works | Dream must be the selected screensaver first | `startNow()` = `apply(GOOGLE_PHOTOS)` (write the setting) then the working strategy. |
| Works for the current screensaver, d1 also switches it, a follows the new setting | Full Photos hand-off is possible | `apply(choice)` writes `screensaver_components`, then `startNow()` uses the working method. |
| E2.b3 opens the Daydream page but no strategy starts a dream | Only user-driven start | Photos tile only switches the screensaver and shows a hint ("Press Home, then wait" / "Settings > Screensaver > Start now"). |
| **Nothing** works (all `SecurityException`/`NOT RESOLVABLE`/`NOTHING`) | Platform does not allow on-demand start | `startNow()` returns false: Photos tile **only switches the screensaver and shows a hint**. |
| E2.d1 fails with `WRITE_SECURE_SETTINGS not granted` | Grant missing, not a real failure | Run the grant in step 1 and repeat d. |

`ScreensaverController.startNow(): Boolean` should return `true` only for a strategy that showed
`SHOWED` here.

## 6. Paste-back template

Copy this, fill it in, and paste it to the integrator. (The two blocks in `[...]` are the on-screen summary text.)

```text
## Field Spike results
Date:                 
TV / build:           (E1.3 and E2 summary print Manufacturer, Model, Android, API)
Built with:           ./gradlew -p spike installDebug   -> OK / error: 

### E1
Apps I partly watched before testing (Watch Next candidates): 
pm grant READ_EPG_DATA output:        
pm grant ACCESS_ALL_EPG_DATA output:  
E1.0 permission lines (paste):
  [ ... ]
E1.3 summary (paste the whole block):
  [ ... ]
Shell ground truth (content query ...): rows? (yes/no/refused), packages: 
Verdict (from the decision table): readable / not readable ; Recent row source = 

### E2
Baseline settings get secure screensaver_components: 
Baseline screensaver_enabled: 
Control `am start -n com.android.systemui/.Somnambulator` showed a screensaver: yes / no / error: 
E2.0 output (paste):
  [ ... ]
E2.1 output (paste):
  [ ... ]
E2 summary (paste the whole block):
  [ ... ]
E2.d1 / d2 results (paste the `result:` lines): 
Strategy order that works (first = best): 
Verdict: startNow works via ______ / does not work -> hint only

### Anything odd (crashes, dialogs, TV rebooted, wrong screen): 
```

## 7. Safety and cleanup

- The spike only reads TV provider data and starts activities/screensaver. The one write is E2.d1/d2
  (`Settings.Secure screensaver_components`); press **E2.d2** (or use the `adb shell settings put` line) afterwards.
- The saved "previous value" lives in the spike's own preferences, so it survives the app being closed.
- Uninstalling the spike does not revert the setting. If you uninstall while d1 is active, restore it by hand.
- `deploy.sh` / `rescue.sh` record and restore the screensaver on their own; this app is independent of them.
