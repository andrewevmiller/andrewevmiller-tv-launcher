# Sleep timer: plan

Status: **S0 (spike) in progress.** Nothing else is built. This extends `docs/BUILD_PLAN.md`; the rules in
`CLAUDE.md` (design, ownership, verification) apply unchanged.

## What it does

Pick a length (15, 30, 45, 60, 90, 120 min). When it runs out, with the TV still on whatever app you were in:

1. Pause the media that is playing.
2. Play a short closing sound (a descending glide; `FieldSound.windDown()`).
3. Fade the picture to a near-black Oxyfield dot field and hold a still frame. Any key wakes it; media stays paused.

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
| S4 | Dim mode for `OxyfieldView`: fade to near-black over about 45 s, then a still frame; reduced motion snaps to it | `ambient/` | Frame-by-frame screenshot check; brightness steps unit-tested |
| S5 | Closing sound plus the matching `sfx.windDown` in the mockup so it can be auditioned; render it in `tools/sound` | `sound/`, mockup | No NaN, bounded level, plays only when sounds are on |
| S6 | UI: "Sleep timer" tile in the Inputs and system row (caption "Sleep · 42 min" while running), inline picker, Settings section | `home/`, `settings/` | D-pad behaviour tests; accent only on focus |
| S7 | UI behaviour tests and screenshots (tile idle and running, picker, dim frames) | tests | Green under AGP |
| S8 | Mockup tile, picker and dim demo; republish; README, BUILD_PLAN, CLAUDE.md | docs | |

S2, S4 and S5 run in parallel once S1 is in. S3 and S6 also wait for S0.

## Behaviour decisions

- The countdown is not cancelled by key presses. The tile can extend it by 15 min or cancel it.
- The deadline is stored (small SharedPreferences store). After a reboot, a deadline in the past is dropped.
- If nothing is playing when it fires, skip the pause and still play the sound and dim.
- The dim screen stays until a key is pressed. It never resumes media.
- No volume fade in v1: HDMI/ARC setups often have fixed volume, and a crash could leave it low.
- Closing sound obeys the interface-sounds switch and the system sound-effects setting.

## Verification

Cloud: purecheck, `:app:assembleDebug testDebugUnitTest lintDebug`, screenshots of every new state.
TV: the S0 matrix; the real timer end to end in YouTube and one other app; whether the TV's own idle timeout then
turns the screen off; closing-sound loudness.
