# Sleep timer: plan

Status: **S1, S2, S3, S4 and S5 are built and pass in the cloud; S0 (the TV spike) has not been run, and S6 to S8
(Home tile, picker, Settings section, UI tests, README) are not built.** There is no way to start the timer from the UI
yet. Nothing has run on a TV. This extends `docs/BUILD_PLAN.md`; the rules in `CLAUDE.md` (design, ownership,
verification) apply unchanged.

## What it does

Pick a length (15, 30, 45, 60, 90, 120 min). When it runs out, with the TV still on whatever app you were in:

1. Pause the media that is playing.
2. Play a short closing sound (a descending glide; `FieldSound.windDown()`).
3. Fade the picture to a near-black Oxyfield dot field and hold a still frame with a faint clock (to the minute) on it. Any key wakes it; media stays paused.

The TV does not power off from here (no permission for that). An LCD backlight also stays on. Real power-off stays
with the TV's own "turn off after" setting; the README should say so.

## The one real risk

The timer fires while another app (YouTube, Netflix) is in front. Android blocks an app from starting a
full-screen activity from the background. Pausing is not affected (a media key event needs no permission); showing
the dim screen may be. S0 answers this on the TV before anything else is built. Same pattern as spike E2.

## S0: spike (`spike/`, run once on the TV)

A "fire in 20 s" screen added to the Field Spike app. Start a video in another app, press the button, go back to
the video, and watch what happens.

| Question | Methods tried, in order | Success looks like |
|---|---|---|
| Does the playing app pause? | `AudioManager.dispatchMediaKeyEvent` with `KEYCODE_MEDIA_PAUSE`; a second try; taking audio focus | `isMusicActive()` false 1.5 s later, and the picture stops. Tested on YouTube, Netflix, Plex, Prime |
| Can we show a dim screen from the background? | (a) start the screensaver the way the launcher does today; (b) start our own full-screen activity; (c) the same activity after granting "draw over other apps" with adb; (d) none | The screen appears over the other app |

The result decides the order of methods in the app, and whether `deploy.sh` must also grant the overlay permission.

## Build (after S0)

| # | Work | Owner area | Acceptance |
|---|---|---|---|
| S1 | Contracts: `SleepTimerController` (state, start, cancel, extend), `FieldSound.windDown()` (+ no-op), `FieldIcons.Moon`, a default length in settings | integrator | Old stored settings still load; compiles |
| S2 | Pure logic: presets, countdown text ("42 min", "0:42:10"), deadline math, ignore a deadline that expired while the TV was off, cap on extending, order of pause attempts and the firing sequence | `sleep/` | Plain-JVM tests under purecheck |
| S3 | Android side: scheduled with an alarm (survives the launcher process being killed), pause ladder, closing sound, start the dim screen by the method S0 proved | `sleep/` | Robolectric: alarm scheduled and cancelled; firing calls the steps in order |
| S4 | Dim mode for `OxyfieldView`: fade to near-black over about 45 s, then a still frame; the clock fades to a faint floor and stays, repainted each minute; reduced motion snaps to it | `ambient/` | Frame-by-frame screenshot check; brightness steps unit-tested |
| S5 | Closing sound plus the matching `sfx.windDown` in the mockup so it can be auditioned; render it in `tools/sound` | `sound/`, mockup | No NaN, bounded level, plays only when sounds are on |
| S6 | UI: "Sleep timer" tile in a new **Tools** row under "Inputs & system" (see `docs/NOTEBOOK_FEATURES_PLAN.md`; caption "Sleep · 42 min" while running), inline picker, Settings section | `home/`, `settings/` | D-pad behaviour tests; accent only on focus |
| S7 | UI behaviour tests and screenshots (tile idle and running, picker, dim frames) | tests | Green under AGP |
| S8 | Mockup tile, picker and dim demo; republish; README, BUILD_PLAN, CLAUDE.md | docs | |

S2, S4 and S5 run in parallel once S1 is in. S3 and S6 also wait for S0.

## Behaviour decisions

- The countdown is not cancelled by key presses. The tile can extend it by 15 min or cancel it.
- The deadline is stored (small SharedPreferences store). After a reboot, a deadline in the past is dropped.
- If nothing is playing when it fires, skip the pause and still play the sound and dim.
- The dim screen stays until a key is pressed, or for 30 minutes (`SleepConfig.DIM_AUTO_FINISH_MILLIS`), whichever is
  first; the paused app is then back on top and the TV's own timeout takes over. It never resumes media. When the
  screensaver route is used instead of our own activity, it is a normal dream and ends on the first key press.
- If the TV is already off when the alarm fires (the user switched it off first), the whole sequence is skipped: no
  sound, no dim, no wake-up.
- The dim activity does not hold the screen on by default (`SleepConfig.DIM_KEEPS_SCREEN_ON = false`), so the TV's own
  screen-off timeout can still run; that may cut the 45 s fade short. Flip it after the TV test if that happens.
- The alarm survives the system killing the launcher process. A reboot or a force-stop cancels it; the launcher
  re-arms a stored deadline that is still in the future the next time it starts (it is Home, so that is at boot) and
  drops a stale one. A deadline up to two minutes in the past is still fired after a cold start (the alarm lands at or
  just after the deadline); a TV switched off in that window is caught by the "already off" rule above.
- The sleep screen keeps a faint clock to the minute (no seconds), at 15% of full ink (about 35/255), reached 60% of
  the way through the fade. On an LCD that does not burn in; if the panel turns out to be OLED, a still clock needs
  pixel shifting or a slow drift, which is not built. The normal screensaver clock shows seconds, also under reduced
  motion (Home's header clock ticks every second the same way).
- No volume fade in v1: HDMI/ARC setups often have fixed volume, and a crash could leave it low.
- Closing sound obeys the interface-sounds switch and the system sound-effects setting.

## Verification

Cloud: purecheck, `:app:assembleDebug testDebugUnitTest lintDebug`, screenshots of every new state.
TV: the S0 matrix; the real timer end to end in YouTube and one other app; whether the TV's own idle timeout then
turns the screen off (and whether `DIM_KEEPS_SCREEN_ON` should be flipped); closing-sound loudness, including when the
launcher process had been killed and the alarm starts it cold; whether the faint clock is legible and not too bright
in a dark room; whether the dim screen can start from the background without `adb shell appops set
com.andrewevmiller.tvlauncher SYSTEM_ALERT_WINDOW allow`. After the spike, set `SleepConfig.PROVEN_PAUSE_RUNG` and
`PROVEN_DIM_STRATEGY`. What the last real run did is in `adb logcat -s FieldSleep`, or on a debug build
`adb shell run-as com.andrewevmiller.tvlauncher cat shared_prefs/field_sleep.xml`.
