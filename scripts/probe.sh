#!/usr/bin/env bash
# A1: run the BUILD_PLAN §4 probe against the connected TV and summarise it.
# Read-only: nothing on the TV is changed. Summary first, raw output after.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR source=lib.sh
source "$SCRIPT_DIR/lib.sh"

usage() {
  cat <<'EOF'
Usage: scripts/probe.sh [--serial SERIAL] [--dry-run] [-h|--help]

Runs the BUILD_PLAN §4 probe over adb and prints a summary (TV type, model,
Android release and SDK, display size and density, default HOME, screensaver,
launcherx and setupwraith state), followed by the raw output.

  --serial SERIAL  talk to this adb device (adb -s SERIAL)
  --dry-run        print the adb commands; do not run adb
  -h, --help       show this help

Environment: ADB=/path/to/adb overrides the adb binary.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      [[ $# -ge 2 ]] || die "--serial needs a value"
      set_serial "$2"
      shift 2
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      die "unknown argument: $1 (see --help)"
      ;;
  esac
done

require_adb

model="$(query "<model>" shell getprop ro.product.model)" \
  || die "adb shell failed (is the TV connected? try --serial SERIAL)"
release="$(query "<release>" shell getprop ro.build.version.release || true)"
sdk="$(query "<sdk>" shell getprop ro.build.version.sdk || true)"
all_pkgs="$(query "<all-packages>" shell pm list packages || true)"
filtered="$(grep -iE 'launcher|tungsten|hisense|vidaa|livetv|tvinput|dreamx|backdrop' <<<"$all_pkgs" || true)"
home_raw="$(query "<home-raw>" shell cmd package resolve-activity -c "$CATEGORY_HOME" -a "$ACTION_MAIN" || true)"
screensaver="$(query "<screensaver>" shell settings get secure screensaver_components || true)"
wm_size="$(query "<wm-size>" shell wm size || true)"
wm_density="$(query "<wm-density>" shell wm density || true)"
disabled_pkgs="$(query "<disabled-packages>" shell pm list packages -d || true)"

# Effective value: the override when one is set, otherwise the physical value.
effective() {
  local override physical
  override="$(grep -i '^Override' <<<"$1" | head -n 1 || true)"
  if [[ -n $override ]]; then
    printf '%s' "${override#*: }"
    return 0
  fi
  physical="$(grep -i '^Physical' <<<"$1" | head -n 1 || true)"
  printf '%s' "${physical#*: }"
}

# Component from the raw resolve output (pkg/.Activity), or nothing.
home_component() {
  grep -oE '[A-Za-z0-9_.]+/[.A-Za-z0-9_$]+' <<<"$1" | tail -n 1 || true
}

launcher_state() {
  if ! grep -Fxq "package:$1" <<<"$all_pkgs"; then
    printf 'not installed'
  elif grep -Fxq "package:$1" <<<"$disabled_pkgs"; then
    printf 'installed, disabled'
  else
    printf 'installed, enabled'
  fi
}

section() {
  printf '\n$ %s\n%s\n' "$1" "$2"
}

if [[ $DRY_RUN -eq 1 ]]; then
  printf 'dry run: the summary and raw output need a connected TV; only the commands are shown\n'
else
  google_tv=0
  vidaa=0
  tv_parts=()
  if grep -Eq 'launcherx|tungsten' <<<"$all_pkgs"; then
    google_tv=1
    tv_parts+=("Google TV (launcherx/tungsten present)")
  fi
  if grep -Eq '^package:(net\.vidaatv\.hisense\.android\.tv|com\.vt\.tv\.customerlauncher)$' <<<"$all_pkgs"; then
    vidaa=1
    tv_parts+=("Hisense VIDAA (vidaatv/customerlauncher present)")
  fi
  if [[ $google_tv -eq 0 && $vidaa -eq 0 ]]; then
    tv_parts+=("unknown")
  fi
  tv_type=""
  for part in "${tv_parts[@]}"; do
    tv_type="${tv_type:+$tv_type + }$part"
  done
  home="$(home_component "$home_raw")"
  size="$(effective "$wm_size")"
  density="$(effective "$wm_density")"

  printf '== Field Launcher probe summary ==\n'
  printf 'TV type:       %s\n' "$tv_type"
  printf 'Model:         %s\n' "$model"
  printf 'Android:       %s (SDK %s)\n' "$release" "$sdk"
  printf 'Display:       %s at density %s\n' "${size:-unknown}" "${density:-unknown}"
  printf 'Default HOME:  %s\n' "${home:-unknown}"
  printf 'Screensaver:   %s\n' "${screensaver:-null}"
  printf 'launcherx:     %s\n' "$(launcher_state "$LAUNCHERX_PKG")"
  printf 'setupwraith:   %s\n' "$(launcher_state "$SETUPWRAITH_PKG")"
  printf '\n== Raw output ==\n'
fi

if [[ $DRY_RUN -ne 1 ]]; then
  section "adb shell getprop ro.product.model" "$model"
  section "adb shell getprop ro.build.version.release" "$release"
  section "adb shell getprop ro.build.version.sdk" "$sdk"
  section "adb shell pm list packages | grep -iE 'launcher|tungsten|hisense|vidaa|livetv|tvinput|dreamx|backdrop'" "$filtered"
  section "adb shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN" "$home_raw"
  section "adb shell settings get secure screensaver_components" "$screensaver"
  section "adb shell wm size" "$wm_size"
  section "adb shell wm density" "$wm_density"
  section "adb shell pm list packages -d" "$disabled_pkgs"
else
  printf '\n(dry run: no adb calls made; the commands are listed above)\n'
fi
