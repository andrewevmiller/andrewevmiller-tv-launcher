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

## Fonts
IBM Plex Serif, IBM Plex Mono and Geist are bundled under the SIL Open Font License; see `licenses/`.
