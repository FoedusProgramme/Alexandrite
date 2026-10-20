#!/bin/sh
# Runs the tests that need Linux and bubblewrap in a privileged Debian container, on a copy of this checkout, and
# leaves their reports in build/linux-test/:
#   sh libraries/sandboxes/bubblewrap/linux-test.sh [Gradle arguments]   or   ./gradlew :libraries:sandboxes:bubblewrap:linuxTest
# While it runs it lifts the AppArmor limit on unprivileged user namespaces of the Docker VM's kernel (Ubuntu 24.04).
set -eu

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../.." && pwd)
image=alexandrite-linux-test
out=$repo/build/linux-test
if [ "$#" -eq 0 ]; then
    set -- :libraries:sandboxes:bubblewrap:test :app:test --tests '*Bubblewrap*' --tests '*McpSandbox*'
fi

docker build --quiet --tag "$image" - >/dev/null <<'DOCKERFILE'
FROM debian:trixie-slim
RUN apt-get update \
    && apt-get install --yes --no-install-recommends bubblewrap socat curl ca-certificates procps util-linux \
        openjdk-21-jdk-headless \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 1000 tester
DOCKERFILE

rm -rf "$out"
mkdir -p "$out"
status=0
docker run --rm --privileged \
    --volume "$repo":/src:ro \
    --volume alexandrite-linux-test-gradle:/home/tester/.gradle \
    --volume "$out":/out \
    "$image" sh -c '
        limit=/proc/sys/kernel/apparmor_restrict_unprivileged_userns
        old=$(cat "$limit" 2>/dev/null || true)
        restore() { [ -z "$old" ] || echo "$old" > "$limit"; }
        trap restore EXIT
        [ -z "$old" ] || echo 0 > "$limit"
        mkdir /work
        tar -C /src --exclude=./.git --exclude=./.gradle --exclude=./.kotlin --exclude=./.claude --exclude="*/build" \
            --exclude=./build -cf - . | tar -C /work -xf -
        chown -R tester /work /home/tester/.gradle
        status=0
        arguments=
        for argument in "$@"; do arguments="$arguments '"'"'$argument'"'"'"; done
        su tester -c "cd /work && ./gradlew --no-daemon --quiet --continue --max-workers=2 -Dorg.gradle.jvmargs=-Xmx1g -Pkotlin.daemon.jvmargs=-Xmx1g $arguments" || status=$?
        find /work -path "*/build/test-results/test/*.xml" -exec cp {} /out/ \;
        exit $status
    ' linux-test "$@" || status=$?
for report in "$out"/*.xml; do
    [ -e "$report" ] || continue
    sed -n 's/.*<testsuite name="\([^"]*\)" tests="\([0-9]*\)" skipped="\([0-9]*\)" failures="\([0-9]*\)" errors="\([0-9]*\)".*/\1: \2 tests, \3 skipped, \4 failed, \5 errors/p' "$report"
done
exit $status
