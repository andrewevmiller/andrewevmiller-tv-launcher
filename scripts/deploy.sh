#!/usr/bin/env bash
# A2: make Field Launcher the TV's home screen. Records the previous HOME, the
# Google launcher states and the screensaver first, so rescue.sh can undo it.
# Google's launcher packages are never enabled or disabled here.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source-path=SCRIPTDIR source=lib.sh
source "$SCRIPT_DIR/lib.sh"

DEPLOY_STATE_FILE="${DEPLOY_STATE_FILE:-$SCRIPT_DIR/.deploy-state}"
NO_BUILD=0
APK=""

usage() {
  cat <<'EOF'
Usage: scripts/deploy.sh [--dry-run] [--serial SERIAL] [--no-build] [--apk PATH] [-h|--help]

Makes Field Launcher the TV's home screen. Steps, in order:
  1. check the adb device is connected
  2. read the current HOME
  3. record the previous HOME, launcherx/setupwraith states and the screensaver
     in scripts/.deploy-state (not rewritten while our launcher is already HOME)
  4. ./gradlew installDebug, or adb install -r PATH with --apk
  5. grant WRITE_SECURE_SETTINGS and usage access, GET_USAGE_STATS (warn on failure)
  6. write the previous HOME to the TV (field_launcher_previous_home)
  7. re-enable our HOME activity and make it the default HOME
  8. press HOME, then verify the HOME that is now resolved

  --dry-run        print the adb commands; run nothing (no adb or device needed)
  --serial SERIAL  talk to this adb device (adb -s SERIAL)
  --no-build       skip the Gradle build and the install (install the APK yourself)
  --apk PATH       install this APK with adb install -r instead of building
  -h, --help       show this help

Environment: ADB=/path/to/adb overrides the adb binary;
DEPLOY_STATE_FILE=/path overrides scripts/.deploy-state.
Roll back with scripts/rescue.sh (or scripts/rescue.sh --stock).
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --serial)
      [[ $# -ge 2 ]] || die "--serial needs a value"
      set_serial "$2"
      shift 2
      ;;
    --no-build)
      NO_BUILD=1
      shift
      ;;
    --apk)
      [[ $# -ge 2 ]] || die "--apk needs a path"
      APK="$2"
      shift 2
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

if [[ -n $APK && $NO_BUILD -eq 1 ]]; then
  die "--apk and --no-build cannot be combined"
fi
if [[ -n $APK && ! -f $APK ]]; then
  die "APK not found: $APK"
fi

require_adb
if [[ $DRY_RUN -eq 1 ]]; then
  printf 'dry run: nothing is changed; each adb command is printed with a placeholder for its result\n'
fi

# 1. Device must be connected.
printf '== 1. device ==\n'
device_state="$(query "<device-state>" get-state)" \
  || die "adb get-state failed: no adb device (is the TV connected? try --serial SERIAL)"
if [[ $DRY_RUN -ne 1 && $device_state != "device" ]]; then
  die "adb device state is '$device_state', expected 'device' (check adb devices and the TV's USB debugging prompt)"
fi
serial="$(query "<serial>" get-serialno)" || die "adb get-serialno failed"

# 2. Current HOME (last output line containing a '/').
printf '== 2. current HOME ==\n'
current_raw="$(query "<previous-home>" shell cmd package resolve-activity --brief -c "$CATEGORY_HOME" -a "$ACTION_MAIN")" \
  || die "could not resolve the current HOME"
if [[ $DRY_RUN -eq 1 ]]; then
  current_home="<previous-home>"
else
  current_home="$(last_component <<<"$current_raw")"
  [[ -n $current_home ]] || die "could not read the current HOME: resolve-activity printed no component"
fi
printf 'current HOME: %s\n' "$current_home"

# 3. Record state before anything changes.
printf '== 3. record state ==\n'
load_state "$DEPLOY_STATE_FILE" || true
if [[ $(pkg_of "$current_home") == "$HOME_PKG" ]]; then
  if [[ -n $S_PREV_HOME ]]; then
    printf 'our launcher is already HOME: keeping %s (previous home %s)\n' \
      "$(rel_path "$DEPLOY_STATE_FILE")" "$S_PREV_HOME"
  else
    warn "our launcher is already HOME and there is no usable $(rel_path "$DEPLOY_STATE_FILE"): rescue.sh will have to use --stock"
  fi
else
  S_PREV_HOME="$current_home"
  S_LAUNCHERX_STATE="$(pkg_state "$LAUNCHERX_PKG" launcherx)"
  S_SETUPWRAITH_STATE="$(pkg_state "$SETUPWRAITH_PKG" setupwraith)"
  S_PREV_SCREENSAVER="$(query "<previous-screensaver>" shell settings get secure screensaver_components || true)"
  if [[ -z $S_PREV_SCREENSAVER ]]; then
    S_PREV_SCREENSAVER="null"
  fi
  if [[ $DRY_RUN -eq 1 ]]; then
    S_RECORDED_AT="<utc-now>"
  else
    S_RECORDED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  fi
  S_DEVICE_SERIAL="$serial"
  for value in "$S_PREV_HOME" "$S_PREV_SCREENSAVER"; do
    [[ $value != *"'"* ]] || die "refusing to record a value containing a single quote: $value"
  done
  if [[ $DRY_RUN -eq 1 ]]; then
    printf '+ write %s (PREV_HOME=%s LAUNCHERX_STATE=%s SETUPWRAITH_STATE=%s PREV_SCREENSAVER=%s RECORDED_AT=%s DEVICE_SERIAL=%s)\n' \
      "$(rel_path "$DEPLOY_STATE_FILE")" "$S_PREV_HOME" "$S_LAUNCHERX_STATE" "$S_SETUPWRAITH_STATE" \
      "$S_PREV_SCREENSAVER" "$S_RECORDED_AT" "$S_DEVICE_SERIAL"
  else
    write_state "$DEPLOY_STATE_FILE"
    printf 'recorded previous home %s in %s\n' "$S_PREV_HOME" "$(rel_path "$DEPLOY_STATE_FILE")"
  fi
fi

# 4. Build and install.
printf '== 4. build and install ==\n'
if [[ -n $APK ]]; then
  run install -r "$APK" || die "adb install of $APK failed"
elif [[ $NO_BUILD -eq 1 ]]; then
  printf 'skipping build and install (--no-build)\n'
else
  printf '+ ./gradlew installDebug (in the repo root)\n'
  if [[ $DRY_RUN -ne 1 ]]; then
    (cd "$REPO_ROOT" && ./gradlew installDebug) || die "./gradlew installDebug failed"
  fi
fi

# 5. Permission for the screensaver switch (non-fatal).
printf '== 5. permission ==\n'
run shell pm grant "$HOME_PKG" android.permission.WRITE_SECURE_SETTINGS \
  || warn "could not grant WRITE_SECURE_SETTINGS; the screensaver switch will not work until it is granted"
# Usage access for the viewing log (non-fatal): without it the log records nothing new.
run shell appops set "$HOME_PKG" GET_USAGE_STATS allow \
  || warn "could not grant usage access (GET_USAGE_STATS); the viewing log will not record until it is granted"

# 6. Previous HOME on the TV, for the on-device crash fallback.
printf '== 6. previous home on the TV ==\n'
if [[ -z $S_PREV_HOME ]]; then
  warn "no previous home is recorded; field_launcher_previous_home is left as it is"
elif [[ $(pkg_of "$S_PREV_HOME") == "$HOME_PKG" ]]; then
  printf 'previous home is our own launcher; not writing field_launcher_previous_home\n'
else
  run shell settings put secure field_launcher_previous_home "$S_PREV_HOME" \
    || die "could not write field_launcher_previous_home"
fi

# 7. Our HOME activity, enabled and set as the default.
printf '== 7. set HOME ==\n'
run shell pm enable "$OUR_HOME" || die "pm enable $OUR_HOME failed"
run shell cmd package set-home-activity "$OUR_HOME" || die "set-home-activity $OUR_HOME failed"

# 8. Press HOME, then verify.
printf '== 8. verify ==\n'
run shell input keyevent KEYCODE_HOME || die "could not send KEYCODE_HOME"
verify_raw="$(query "<current-home>" shell cmd package resolve-activity --brief -c "$CATEGORY_HOME" -a "$ACTION_MAIN")" || true

rollback_hint() {
  printf 'To roll back: scripts/rescue.sh (HOME back to %s, screensaver restored)\n' "${S_PREV_HOME:-the previous home}"
  printf '              scripts/rescue.sh --stock (re-enables Google'\''s launcher and makes it HOME)\n'
}

if [[ $DRY_RUN -eq 1 ]]; then
  printf 'dry run: HOME is not verified\n'
  rollback_hint
  exit 0
fi

after="$(last_component <<<"$verify_raw")"
if [[ $after == "$OUR_HOME" ]]; then
  printf 'OK: HOME is %s\n' "$OUR_HOME"
  rollback_hint
else
  rollback_hint
  warn "HOME is '${after:-unknown}' after deploy, expected $OUR_HOME"
  exit 1
fi
