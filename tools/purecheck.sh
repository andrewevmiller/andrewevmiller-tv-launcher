#!/usr/bin/env bash
# Run tools/purecheck one run at a time across all agents/worktrees, on one shared warm Gradle daemon.
# (Cold JVM + Kotlin compiler start per run was the main source of CPU contention with many agents.)
#
#   bash /home/user/andrewevmiller-tv-launcher/tools/purecheck.sh            # test (default)
#   bash /home/user/andrewevmiller-tv-launcher/tools/purecheck.sh pureReport # any purecheck task
#
# Run it from your worktree root: it builds the checkout you are in.
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.internal.repository.max.tentatives=10 -Dorg.gradle.internal.repository.initial.backoff=3000"

exec 8>/tmp/field-purecheck.lock
flock 8
cd "$ROOT"
if [ "$#" -eq 0 ]; then set -- test; fi
# --daemon + a fixed heap so every caller reuses the same daemon (command line beats the project's daemon=false).
exec nice -n 5 gradle -p tools/purecheck --console=plain --daemon -Dorg.gradle.jvmargs=-Xmx1536m "$@"
