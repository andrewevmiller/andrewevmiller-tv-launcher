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

An update keeps the grant; an uninstall removes it, so run `enable` again after reinstalling.

### Remote buttons
Some buttons are taken by the TV's system before any app sees them. On this TV the Settings, Tuner and User buttons go to a Google TV launcher that is disabled, so they do nothing. The launcher can take them over as an accessibility service, which Android keeps off until you switch it on:

```powershell
./tools/remote.ps1 enable    # also: disable, status
```

Out of the box the Settings button opens a notifications panel (with an Open Settings shortcut) and the Tuner button opens Stremio. The service reads nothing from the screen, only key presses, and only touches the buttons listed in `RemoteKeys` (`app/.../remote/RemoteKeyService.kt`). Back, Home, Power, the arrows, OK and volume can never be remapped. `disable` puts every button back. An update keeps the setting; an uninstall removes it.

## Fonts
IBM Plex Serif, IBM Plex Mono and Geist are bundled under the SIL Open Font License; see `licenses/`.
