#!/usr/bin/env bash
# Compile/test the Android app in the cloud, one build at a time across all agents and worktrees
# (a full AGP + Compose build is heavy; the lock keeps a 4-CPU box from thrashing).
#
#   bash /home/user/andrewevmiller-tv-launcher/tools/compile.sh   # from your worktree root: :app:compileDebugKotlin
#   tools/compile.sh :app:testDebugUnitTest           # any Gradle tasks/args
#
# Needs the Android SDK at $ANDROID_HOME (default ~/android-sdk) and dl.google.com reachable.
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"   # the checkout you run it from (works from any agent worktree)
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.internal.repository.max.tentatives=10 -Dorg.gradle.internal.repository.initial.backoff=3000"
[ -d "$ANDROID_HOME/platforms/android-34" ] || { echo "Android SDK platform 34 not found in $ANDROID_HOME" >&2; exit 2; }

exec 9>/tmp/field-compile.lock
echo "tools/compile.sh: waiting for the compile lock..." >&2
flock 9
echo "tools/compile.sh: lock acquired, building $ROOT" >&2
cd "$ROOT"
if [ "$#" -eq 0 ]; then set -- :app:compileDebugKotlin; fi
exec ./gradlew --console=plain "$@"
