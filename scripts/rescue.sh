#!/usr/bin/env bash
# A3: undo deploy.sh. Normal mode makes the previous home (from scripts/.deploy-state)
# the HOME again. Stock mode re-enables Google's launcher and makes it HOME. Both
# modes restore the screensaver, clear field_launcher_previous_home and press HOME.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
# shellcheck source-path=SCRIPTDIR source=lib.sh
source "$SCRIPT_DIR/lib.sh"

DEPLOY_STATE_FILE="${DEPLOY_STATE_FILE:-$SCRIPT_DIR/.deploy-state}"
STOCK=0
EXIT_CODE=0

usage() {
  cat <<'EOF'
Usage: scripts/rescue.sh [--stock] [--dry-run] [--serial SERIAL] [-h|--help]

Gives the TV back its previous home screen (BUILD_PLAN A3).

Normal mode (default): sets the previous home recorded by deploy.sh as HOME, restores
the screensaver, clears field_launcher_previous_home and presses HOME.

Stock mode (--stock, or automatic when the state file is missing, records no previous
home, or that home's package is not installed): enables Google's launcher
(com.google.android.apps.tv.launcherx) and setupwraith when installed, makes launcherx
the HOME, then restores the screensaver and presses HOME. If no launcherx HOME entry
exists, our HOME activity is disabled only when another HOME activity exists.

  --stock          use stock mode even when the previous home is available
  --dry-run        print the adb commands; run nothing (no adb or device needed)
  --serial SERIAL  talk to this adb device (adb -s SERIAL)
  -h, --help       show this help

Environment: ADB=/path/to/adb overrides the adb binary;
DEPLOY_STATE_FILE=/path overrides scripts/.deploy-state.
scripts/.deploy-state is kept, so deploy.sh can run again.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --stock)
      STOCK=1
      shift
      ;;
    --dry-run)
      DRY_RUN=1
      shift
      ;;
    --serial)
      [[ $# -ge 2 ]] || die "--serial needs a value"
      set_serial "$2"
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

require_adb

# The state file is read with a whitelist; it is never sourced.
have_state=0
if load_state "$DEPLOY_STATE_FILE"; then
  have_state=1
fi

mode="normal"
reason=""
if [[ $STOCK -eq 1 ]]; then
  mode="stock"
  reason="--stock was given"
elif [[ $have_state -eq 0 ]]; then
  mode="stock"
  reason="$(rel_path "$DEPLOY_STATE_FILE") not found"
elif [[ -z $S_PREV_HOME ]]; then
  mode="stock"
  reason="$(rel_path "$DEPLOY_STATE_FILE") records no previous home"
elif [[ $(pkg_of "$S_PREV_HOME") == "$HOME_PKG" ]]; then
  mode="stock"
  reason="the recorded previous home is our own launcher"
elif [[ -z $(pkg_path "$(pkg_of "$S_PREV_HOME")") ]]; then
  mode="stock"
  reason="previous home package $(pkg_of "$S_PREV_HOME") is not installed"
fi

if [[ $mode == "normal" ]]; then
  printf 'mode: normal (HOME back to %s)\n' "$S_PREV_HOME"
  run shell cmd package set-home-activity "$S_PREV_HOME" \
    || die "set-home-activity $S_PREV_HOME failed"
else
  if [[ $STOCK -eq 1 ]]; then
    printf 'mode: stock (--stock given)\n'
  else
    printf 'mode: stock, switched automatically because %s\n' "$reason"
  fi
  for pkg in "$LAUNCHERX_PKG" "$SETUPWRAITH_PKG"; do
    if [[ -n $(pkg_path "$pkg") ]]; then
      run shell pm enable "$pkg" || die "pm enable $pkg failed"
    else
      printf '%s is not installed; not enabled\n' "$pkg"
    fi
  done

  listing="$(query "<home-activities>" shell cmd package query-activities --brief \
    -a "$ACTION_MAIN" -c "$CATEGORY_HOME" || true)"
  target=""
  if [[ $DRY_RUN -eq 1 ]]; then
    target="<launcherx-activity>"
  else
    components="$(grep -oE '[A-Za-z0-9_.]+/[.A-Za-z0-9_$]+' <<<"$listing" || true)"
    while IFS= read -r candidate; do
      if [[ -n $candidate && $(pkg_of "$candidate") == "$LAUNCHERX_PKG" ]]; then
        target="$candidate"
        break
      fi
    done <<<"$components"
  fi

  if [[ -n $target ]]; then
    run shell cmd package set-home-activity "$target" \
      || die "set-home-activity $target failed"
  else
    other=""
    if [[ $DRY_RUN -eq 1 ]]; then
      other="<other-home>"
    else
      while IFS= read -r candidate; do
        if [[ -n $candidate && $(pkg_of "$candidate") != "$HOME_PKG" ]]; then
          other="$candidate"
          break
        fi
      done <<<"$components"
    fi
    if [[ -n $other ]]; then
      printf 'no launcherx HOME entry; disabling our HOME so the system picks %s\n' "$other"
      run shell pm disable-user --user 0 "$OUR_HOME" \
        || die "pm disable-user $OUR_HOME failed"
    else
      warn "no launcherx or other HOME activity was found; $OUR_HOME stays the HOME so the TV is never left without one"
      EXIT_CODE=1
    fi
  fi
fi

# Both modes: screensaver, the on-TV previous-home key, then HOME.
if [[ $have_state -eq 1 ]]; then
  if [[ -z $S_PREV_SCREENSAVER || $S_PREV_SCREENSAVER == "null" ]]; then
    run shell settings delete secure screensaver_components
    printf 'screensaver restored: unset (was null)\n'
  else
    run shell settings put secure screensaver_components "$S_PREV_SCREENSAVER"
    printf 'screensaver restored: %s\n' "$S_PREV_SCREENSAVER"
  fi
else
  warn "no state file, so the screensaver was left as it is"
fi

run shell settings delete secure field_launcher_previous_home \
  || warn "could not clear field_launcher_previous_home"
run shell input keyevent KEYCODE_HOME || die "could not send KEYCODE_HOME"

if [[ $have_state -eq 1 ]]; then
  printf 'kept %s so deploy.sh can run again\n' "$(rel_path "$DEPLOY_STATE_FILE")"
fi
printf 'rescue finished (%s mode)\n' "$mode"
exit "$EXIT_CODE"
