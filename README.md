# Field Launcher

A personal Android TV launcher in the andrewevmiller.com design language: warm charcoal, a
dot-field paper texture, IBM Plex Serif / Geist / IBM Plex Mono, and one soft-purple accent.

## Build & install
Requires the Android SDK (API 34) and adb connected to the TV.

```bash
./gradlew installDebug
```

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

## Fonts
IBM Plex Serif, IBM Plex Mono and Geist are bundled under the SIL Open Font License; see `licenses/`.
