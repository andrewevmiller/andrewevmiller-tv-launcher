#!/usr/bin/env bash
# Sets the margin note from a laptop: `adb shell settings put secure field_launcher_note "text"`.
# The launcher shows this when its own stored note is empty (see app/.../marginnote/MarginNoteRules.kt).
# Reading a Settings.Secure key needs no permission, so this is the fallback that always works.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR source=lib.sh
source "$SCRIPT_DIR/lib.sh"

NOTE_SETTING="field_launcher_note"
NOTE_MAX=80

usage() {
  cat <<'EOF'
Usage: scripts/note.sh [--dry-run] [--serial SERIAL] ["text"] [-h|--help]

Sets the margin note shown on Home (BUILD_PLAN N2): one line, at most 80 characters.
  scripts/note.sh "finish S2 of Severance"   sets the note
  scripts/note.sh ""                          clears it (so does running it with no text)

The text is sent as adb shell settings put secure field_launcher_note "text". Newlines and
other control characters are removed, the ends are trimmed, and anything past 80 characters
is cut (with a warning). The text is quoted for the TV's shell, so spaces, quotes, $ and
backticks arrive as typed. Use -- before a text that starts with a dash.

The launcher reads this only while its own note (set in Home) is empty; the stored note wins.

  --dry-run        print the adb commands; run nothing (no adb or device needed)
  --serial SERIAL  talk to this adb device (adb -s SERIAL)
  -h, --help       show this help

Environment: ADB=/path/to/adb overrides the adb binary.
EOF
}

# Single-quotes TEXT for the TV's shell: each ' becomes '\'' so nothing inside is interpreted.
quote_for_shell() {
  local input="$1" out="" ch i
  for ((i = 0; i < ${#input}; i++)); do
    ch="${input:i:1}"
    if [[ $ch == "'" ]]; then
      out+="'\\''"
    else
      out+="$ch"
    fi
  done
  printf "'%s'" "$out"
}

# Strips leading and trailing whitespace.
trim() {
  local s="$1"
  s="${s#"${s%%[![:space:]]*}"}"
  s="${s%"${s##*[![:space:]]}"}"
  printf '%s' "$s"
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
    -h | --help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      die "unknown argument: $1 (see --help)"
      ;;
    *)
      break
      ;;
  esac
done

[[ $# -le 1 ]] || die "give the note as one quoted argument (see --help)"
raw="${1:-}"

# Newlines, tabs and other control characters go; then the ends; then the length limit.
clean="$(printf '%s' "$raw" | tr -d '[:cntrl:]')"
clean="$(trim "$clean")"
if [[ ${#clean} -gt $NOTE_MAX ]]; then
  warn "note is ${#clean} characters; the launcher keeps $NOTE_MAX, so it is cut to $NOTE_MAX"
  clean="$(trim "${clean:0:NOTE_MAX}")"
fi

require_adb

if [[ $DRY_RUN -eq 1 ]]; then
  printf 'dry run: nothing is changed; each adb command is printed\n'
fi

if [[ -z $clean ]]; then
  run shell settings delete secure "$NOTE_SETTING" \
    || die "could not clear $NOTE_SETTING"
  if [[ $DRY_RUN -eq 0 ]]; then
    printf 'OK: margin note cleared (the launcher shows its own note, if any)\n'
  fi
else
  # One argument to adb shell: the TV's shell parses the quoting, so the value arrives intact.
  remote="settings put secure $NOTE_SETTING $(quote_for_shell "$clean")"
  run shell "$remote" || die "could not set $NOTE_SETTING"
  if [[ $DRY_RUN -eq 0 ]]; then
    printf 'OK: margin note set (%d characters)\n' "${#clean}"
  fi
fi
