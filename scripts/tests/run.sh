#!/usr/bin/env bash
# Acceptance tests for scripts/probe.sh, deploy.sh and rescue.sh. Everything runs against
# scripts/tests/fake-adb: no device, no real adb. Temp state lives in a mktemp dir, so the
# real scripts/.deploy-state is never touched.
set -euo pipefail

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="$(cd "$TESTS_DIR/.." && pwd)"
FAKE_ADB="$TESTS_DIR/fake-adb"
GOLDEN="$TESTS_DIR/golden"
FIXTURES="$TESTS_DIR/fixtures"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

OUR_HOME="com.andrewevmiller.tvlauncher/.HomeAlias"
PASSED=0
FAILED=0

pass() {
  PASSED=$((PASSED + 1))
  printf 'PASS  %s\n' "$1"
}

fail() {
  FAILED=$((FAILED + 1))
  printf 'FAIL  %s\n' "$1"
  if [[ -n ${2:-} ]]; then
    printf '%s\n' "$2" | sed 's/^/      /'
  fi
}

# expect_file_eq NAME EXPECTED ACTUAL
expect_file_eq() {
  local diff_out
  if diff_out="$(diff -u "$2" "$3" 2>&1)"; then
    pass "$1"
  else
    fail "$1" "$diff_out"
  fi
}

# expect_match NAME PATTERN FILE   (extended regex, must match at least one line)
expect_match() {
  if grep -Eq -- "$2" "$3"; then
    pass "$1"
  else
    fail "$1" "no line matches: $2"
  fi
}

# expect_no_match NAME PATTERN FILE
expect_no_match() {
  if grep -Eq -- "$2" "$3"; then
    fail "$1" "unexpected line matches $2: $(grep -E -- "$2" "$3" | head -n 1)"
  else
    pass "$1"
  fi
}

# normalise_state FILE: blank out the timestamp so two state files can be compared.
normalise_state() {
  sed "s/^RECORDED_AT='.*'\$/RECORDED_AT='<time>'/" "$1"
}

# (a) probe summary names Google TV, the Hisense model and SDK 31.
test_probe() {
  local out="$WORK/probe.out" summary="$WORK/probe.summary"
  if ! ADB="$FAKE_ADB" "$SCRIPTS_DIR/probe.sh" >"$out" 2>"$WORK/probe.err"; then
    fail "(a) probe.sh runs against the fake" "exit non-zero: $(head -n 1 "$WORK/probe.err")"
    return
  fi
  sed -n '1,/== Raw output ==/p' "$out" >"$summary"
  expect_match "(a) probe summary names Google TV" '^TV type: +Google TV' "$summary"
  expect_match "(a) probe summary names the Hisense model" '^Model: +Hisense$' "$summary"
  expect_match "(a) probe summary names SDK 31" '^Android: +12 \(SDK 31\)$' "$summary"
  expect_match "(a) probe summary names the default HOME" '^Default HOME: +com\.gathin\.tvhome/\.WiiMenuActivity$' "$summary"
  expect_match "(a) probe summary names the screensaver" '^Screensaver: +com\.google\.android\.apps\.tv\.dreamx/\.service\.Backdrop$' "$summary"
  expect_match "(a) probe summary reports launcherx disabled" '^launcherx: +installed, disabled$' "$summary"
  expect_match "(a) probe summary reports setupwraith disabled" '^setupwraith: +installed, disabled$' "$summary"
}

# (b) deploy --dry-run matches the golden file and never calls adb.
test_deploy_dry_run() {
  local out="$WORK/dry.out" log="$WORK/dry.log"
  if ADB=/nonexistent/adb FAKE_ADB_LOG="$log" "$SCRIPTS_DIR/deploy.sh" --dry-run >"$out" 2>&1; then
    expect_file_eq "(b) deploy --dry-run output equals golden" "$GOLDEN/deploy-dry-run.txt" "$out"
    expect_match "(b) deploy --dry-run grants usage access next to WRITE_SECURE_SETTINGS" \
      'adb shell appops set com\.andrewevmiller\.tvlauncher GET_USAGE_STATS allow$' "$out"
  else
    fail "(b) deploy --dry-run exits 0 with no adb binary" "$(tail -n 3 "$out")"
  fi
  if [[ -e $log ]]; then
    fail "(b) deploy --dry-run makes no adb calls" "log exists: $log"
  else
    pass "(b) deploy --dry-run makes no adb calls"
  fi
}

# (c) real deploy against the fake: state file, exact adb sequence, Google packages untouched.
test_deploy_real() {
  local d="$WORK/c"
  mkdir -p "$d"
  if ! ADB="$FAKE_ADB" FAKE_ADB_LOG="$d/log" FAKE_ADB_STATE_DIR="$d/tvstate" \
    DEPLOY_STATE_FILE="$d/deploy-state" "$SCRIPTS_DIR/deploy.sh" --no-build >"$d/out" 2>&1; then
    fail "(c) real deploy --no-build exits 0" "$(tail -n 3 "$d/out")"
    return
  fi
  pass "(c) real deploy --no-build exits 0"
  expect_match "(c) deploy reports OK" '^OK: HOME is com\.andrewevmiller\.tvlauncher/\.HomeAlias$' "$d/out"

  printf "PREV_HOME='com.gathin.tvhome/.WiiMenuActivity'\nLAUNCHERX_STATE='disabled'\nSETUPWRAITH_STATE='disabled'\nPREV_SCREENSAVER='com.google.android.apps.tv.dreamx/.service.Backdrop'\nRECORDED_AT='<time>'\nDEVICE_SERIAL='FAKESERIAL01'\n" >"$d/expected-state"
  expect_match "(c) RECORDED_AT is UTC ISO-8601" "^RECORDED_AT='[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z'\$" "$d/deploy-state"
  normalise_state "$d/deploy-state" >"$d/normalised-state"
  expect_file_eq "(c) deploy writes the expected state file" "$d/expected-state" "$d/normalised-state"
  expect_file_eq "(c) deploy issues the exact adb sequence" "$GOLDEN/deploy-real-calls.txt" "$d/log"
  expect_no_match "(c) deploy never touches launcherx or setupwraith" 'launcherx|setupwraith|tungsten' "$d/log"
}

# (d) a second deploy while our launcher is already HOME leaves the state file byte-identical.
test_deploy_second_run() {
  local d="$WORK/d"
  mkdir -p "$d"
  cp "$WORK/c/deploy-state" "$d/deploy-state"
  cp "$WORK/c/deploy-state" "$d/before"
  if ! ADB="$FAKE_ADB" FAKE_ADB_LOG="$d/log" FAKE_ADB_STATE_DIR="$d/tvstate" \
    FAKE_HOME_ACTIVITY="$OUR_HOME" DEPLOY_STATE_FILE="$d/deploy-state" \
    "$SCRIPTS_DIR/deploy.sh" --no-build >"$d/out" 2>&1; then
    fail "(d) second deploy exits 0" "$(tail -n 3 "$d/out")"
    return
  fi
  pass "(d) second deploy exits 0"
  if cmp -s "$d/before" "$d/deploy-state"; then
    pass "(d) second deploy leaves the state file byte-identical"
  else
    fail "(d) second deploy leaves the state file byte-identical" "$(diff -u "$d/before" "$d/deploy-state")"
  fi
  expect_no_match "(d) second deploy does not re-record the Google launcher states" 'list packages' "$d/log"
}

# (h) our launcher is already HOME but there is no state file: warn, and write nothing.
test_deploy_home_ours_no_state() {
  local d="$WORK/h"
  mkdir -p "$d"
  if ! ADB="$FAKE_ADB" FAKE_ADB_LOG="$d/log" FAKE_ADB_STATE_DIR="$d/tvstate" \
    FAKE_HOME_ACTIVITY="$OUR_HOME" DEPLOY_STATE_FILE="$d/deploy-state" \
    "$SCRIPTS_DIR/deploy.sh" --no-build >"$d/out" 2>&1; then
    fail "(h) deploy with home ours and no state exits 0" "$(tail -n 3 "$d/out")"
    return
  fi
  expect_match "(h) warns that rescue.sh will need --stock" 'rescue\.sh will have to use --stock' "$d/out"
  if [[ -e $d/deploy-state ]]; then
    fail "(h) writes no state file when home is ours" "found $d/deploy-state"
  else
    pass "(h) writes no state file when home is ours"
  fi
}

# (e) rescue normal mode: previous home back, screensaver restored, state kept.
test_rescue_normal() {
  local d="$WORK/e"
  mkdir -p "$d"
  cp "$FIXTURES/deploy-state" "$d/deploy-state"
  cp "$FIXTURES/deploy-state" "$d/before"
  if ! ADB="$FAKE_ADB" FAKE_ADB_LOG="$d/log" DEPLOY_STATE_FILE="$d/deploy-state" \
    "$SCRIPTS_DIR/rescue.sh" >"$d/out" 2>&1; then
    fail "(e) rescue.sh exits 0" "$(tail -n 3 "$d/out")"
    return
  fi
  pass "(e) rescue.sh exits 0"
  expect_match "(e) rescue reports normal mode" '^mode: normal \(HOME back to com\.gathin\.tvhome/\.WiiMenuActivity\)$' "$d/out"
  expect_file_eq "(e) rescue issues the normal-mode adb sequence" "$GOLDEN/rescue-normal-calls.txt" "$d/log"
  expect_no_match "(e) rescue normal mode leaves the Google launcher alone" 'pm enable' "$d/log"
  if cmp -s "$d/before" "$d/deploy-state"; then
    pass "(e) rescue keeps the state file unchanged"
  else
    fail "(e) rescue keeps the state file unchanged" "$(diff -u "$d/before" "$d/deploy-state")"
  fi
}

# (f) previous home uninstalled: switches to stock automatically.
test_rescue_previous_uninstalled() {
  local d="$WORK/f"
  mkdir -p "$d"
  cp "$FIXTURES/deploy-state" "$d/deploy-state"
  if ! ADB="$FAKE_ADB" FAKE_ADB_LOG="$d/log" FAKE_PM_PATH_MISSING=com.gathin.tvhome \
    DEPLOY_STATE_FILE="$d/deploy-state" "$SCRIPTS_DIR/rescue.sh" >"$d/out" 2>&1; then
    fail "(f) rescue with previous home uninstalled exits 0" "$(tail -n 3 "$d/out")"
    return
  fi
  pass "(f) rescue with previous home uninstalled exits 0"
  expect_match "(f) rescue says it switched to stock and why" 'mode: stock, switched automatically because previous home package com\.gathin\.tvhome is not installed' "$d/out"
  expect_file_eq "(f) rescue issues the stock adb sequence" "$GOLDEN/rescue-uninstalled-calls.txt" "$d/log"
  expect_no_match "(f) rescue never sets the uninstalled home" 'set-home-activity com\.gathin' "$d/log"
}

# (g) rescue --stock enables both Google packages and makes launcherx HOME.
test_rescue_stock() {
  local d="$WORK/g"
  mkdir -p "$d"
  cp "$FIXTURES/deploy-state" "$d/deploy-state"
  if ! ADB="$FAKE_ADB" FAKE_ADB_LOG="$d/log" DEPLOY_STATE_FILE="$d/deploy-state" \
    "$SCRIPTS_DIR/rescue.sh" --stock >"$d/out" 2>&1; then
    fail "(g) rescue --stock exits 0" "$(tail -n 3 "$d/out")"
    return
  fi
  pass "(g) rescue --stock exits 0"
  expect_match "(g) rescue --stock enables launcherx" '^shell pm enable com\.google\.android\.apps\.tv\.launcherx$' "$d/log"
  expect_match "(g) rescue --stock enables setupwraith" '^shell pm enable com\.google\.android\.tungsten\.setupwraith$' "$d/log"
  expect_file_eq "(g) rescue --stock issues the exact adb sequence" "$GOLDEN/rescue-stock-calls.txt" "$d/log"
}

# (i) no state file at all: stock mode, and the screensaver is left alone.
test_rescue_no_state() {
  local d="$WORK/i"
  mkdir -p "$d"
  if ! ADB="$FAKE_ADB" FAKE_ADB_LOG="$d/log" DEPLOY_STATE_FILE="$d/missing" \
    "$SCRIPTS_DIR/rescue.sh" >"$d/out" 2>&1; then
    fail "(i) rescue with no state file exits 0" "$(tail -n 3 "$d/out")"
    return
  fi
  expect_match "(i) rescue with no state file switches to stock" 'mode: stock, switched automatically because .* not found' "$d/out"
  expect_no_match "(i) rescue with no state file leaves the screensaver alone" 'screensaver_components' "$d/log"
}

# (j) rescue --dry-run needs no adb.
test_rescue_dry_run() {
  local out="$WORK/rescue-dry.out" log="$WORK/rescue-dry.log"
  if ADB=/nonexistent/adb FAKE_ADB_LOG="$log" DEPLOY_STATE_FILE="$FIXTURES/deploy-state" \
    "$SCRIPTS_DIR/rescue.sh" --dry-run >"$out" 2>&1 && [[ ! -e $log ]]; then
    pass "(j) rescue --dry-run works with no adb and makes no calls"
  else
    fail "(j) rescue --dry-run works with no adb and makes no calls" "$(tail -n 3 "$out")"
  fi
}

test_probe
test_deploy_dry_run
test_deploy_real
test_deploy_second_run
test_deploy_home_ours_no_state
test_rescue_normal
test_rescue_previous_uninstalled
test_rescue_stock
test_rescue_no_state
test_rescue_dry_run

printf '\n%d passed, %d failed\n' "$PASSED" "$FAILED"
if [[ $FAILED -ne 0 ]]; then
  exit 1
fi
