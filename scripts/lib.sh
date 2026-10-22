# shellcheck shell=bash
# Shared helpers for scripts/probe.sh, scripts/deploy.sh and scripts/rescue.sh.
# Sourced, never executed. The sourcing script sets `set -euo pipefail`, then
# SCRIPT_DIR and REPO_ROOT, then sources this file.
# shellcheck disable=SC2034  # constants and S_* values are read by the sourcing scripts

HOME_PKG="com.andrewevmiller.tvlauncher"
OUR_HOME="com.andrewevmiller.tvlauncher/.HomeAlias"
LAUNCHERX_PKG="com.google.android.apps.tv.launcherx"
SETUPWRAITH_PKG="com.google.android.tungsten.setupwraith"
CATEGORY_HOME="android.intent.category.HOME"
ACTION_MAIN="android.intent.action.MAIN"
STATE_KEYS=(PREV_HOME LAUNCHERX_STATE SETUPWRAITH_STATE PREV_SCREENSAVER RECORDED_AT DEVICE_SERIAL)

ADB="${ADB:-adb}"
DRY_RUN=0
SERIAL=""
ADB_ARGS=()

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

warn() {
  printf 'WARN: %s\n' "$*"
}

strip_cr() {
  tr -d '\r'
}

set_serial() {
  SERIAL="$1"
  ADB_ARGS=(-s "$1")
}

# Human-readable form of an adb call, used for the "+ adb ..." lines.
show_cmd() {
  local out="adb" arg
  if [[ -n $SERIAL ]]; then
    out+=" -s $SERIAL"
  fi
  for arg in "$@"; do
    out+=" $arg"
  done
  printf '%s' "$out"
}

require_adb() {
  if [[ $DRY_RUN -eq 1 ]]; then
    return 0
  fi
  command -v "$ADB" >/dev/null 2>&1 || die "adb not found (install platform-tools or set ADB=/path/to/adb)"
}

# Raw adb call with the serial prefix. Output is CR-stripped. No logging.
adb_exec() {
  "$ADB" ${ADB_ARGS[@]+"${ADB_ARGS[@]}"} "$@" | strip_cr
}

# State-changing adb call: logged as "+ adb ..." on stdout, skipped in dry run.
run() {
  printf '+ %s\n' "$(show_cmd "$@")"
  if [[ $DRY_RUN -eq 1 ]]; then
    return 0
  fi
  adb_exec "$@"
}

# Read-only adb call: logged on stderr so $(...) capture stays clean.
# In dry run nothing executes; the placeholder is printed instead.
query() {
  local placeholder="$1"
  shift
  printf '+ %s\n' "$(show_cmd "$@")" >&2
  if [[ $DRY_RUN -eq 1 ]]; then
    printf '%s\n' "$placeholder"
    return 0
  fi
  adb_exec "$@"
}

# Last output line containing a '/' (a component such as pkg/.Activity), trimmed.
last_component() {
  local line=""
  line="$(grep '/' | tail -n 1 || true)"
  line="${line#"${line%%[![:space:]]*}"}"
  line="${line%"${line##*[![:space:]]}"}"
  printf '%s' "$line"
}

pkg_of() {
  printf '%s' "${1%%/*}"
}

rel_path() {
  printf '%s' "${1#"$REPO_ROOT"/}"
}

# Prints the `pm path` output for PKG (empty when the package is not installed).
pkg_path() {
  local out=""
  out="$(query "<pm-path>" shell pm path "$1" || true)"
  printf '%s' "$out"
}

# enabled | disabled | absent for PKG. LABEL is only used for the dry-run placeholder.
pkg_state() {
  local pkg="$1" label="$2" all="" disabled=""
  all="$(query "<all-packages>" shell pm list packages || true)"
  disabled="$(query "<disabled-packages>" shell pm list packages -d || true)"
  if [[ $DRY_RUN -eq 1 ]]; then
    printf '<%s-state>' "$label"
    return 0
  fi
  if ! grep -Fxq "package:$pkg" <<<"$all"; then
    printf 'absent'
  elif grep -Fxq "package:$pkg" <<<"$disabled"; then
    printf 'disabled'
  else
    printf 'enabled'
  fi
}

is_state_key() {
  local k
  for k in "${STATE_KEYS[@]}"; do
    if [[ $k == "$1" ]]; then
      return 0
    fi
  done
  return 1
}

# Whitelisted reader for the deploy state file. The file is never sourced.
# Sets S_<KEY> for every whitelisted key. Returns 1 when the file is missing.
load_state() {
  local file="$1" line key val k re="^([A-Z_]+)='([^']*)'\$"
  for k in "${STATE_KEYS[@]}"; do
    printf -v "S_$k" '%s' ""
  done
  if [[ ! -f $file ]]; then
    return 1
  fi
  while IFS= read -r line || [[ -n $line ]]; do
    if [[ $line =~ $re ]]; then
      key="${BASH_REMATCH[1]}"
      val="${BASH_REMATCH[2]}"
      if is_state_key "$key"; then
        printf -v "S_$key" '%s' "$val"
      fi
    fi
  done < "$file"
  return 0
}

# Atomic write of the S_* values to FILE: write a tmp file, then mv it into place.
write_state() {
  local file="$1" tmp
  tmp="$file.tmp.$$"
  mkdir -p "$(dirname "$file")"
  {
    printf "PREV_HOME='%s'\n" "$S_PREV_HOME"
    printf "LAUNCHERX_STATE='%s'\n" "$S_LAUNCHERX_STATE"
    printf "SETUPWRAITH_STATE='%s'\n" "$S_SETUPWRAITH_STATE"
    printf "PREV_SCREENSAVER='%s'\n" "$S_PREV_SCREENSAVER"
    printf "RECORDED_AT='%s'\n" "$S_RECORDED_AT"
    printf "DEVICE_SERIAL='%s'\n" "$S_DEVICE_SERIAL"
  } > "$tmp"
  mv -f "$tmp" "$file"
}
