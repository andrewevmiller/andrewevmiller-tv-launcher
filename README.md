# Field Launcher

A personal Android TV launcher in the andrewevmiller.com design language: warm charcoal, a
dot-field paper texture, IBM Plex Serif / Geist / IBM Plex Mono, and one soft-purple accent.

## Build & install
Requires the Android SDK (API 34) and adb connected to the TV.

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

Then on the TV: press Home and pick **Field Launcher**. Google TV devices need the deploy
script (coming in `scripts/`), which disables the stock launcher, and `scripts/rescue.sh`
restores it.

## Fonts
IBM Plex Serif, IBM Plex Mono and Geist are bundled under the SIL Open Font License; see `licenses/`.
