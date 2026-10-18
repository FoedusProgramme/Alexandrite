#!/bin/sh
# Runs install.sh offline against releases and a Java runtime served from file:// directories, each case in a
# temporary HOME: sh app/src/installTest/install-test.sh [alexandrite-<version>.tar.gz]
# The cases that need the real archive and a Java 21+ skip themselves without them.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
script=$here/../../../website/install.sh
real_archive=${1:-}
work=$(mktemp -d "${TMPDIR:-/tmp}/alexandrite-install-test.XXXXXX")
trap 'rm -rf "$work"' EXIT
original_path=$PATH
shell=${INSTALL_TEST_SHELL:-sh}
passed=0
failed=0
skipped=0

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d ' ' -f 1; else shasum -a 256 "$1" | cut -d ' ' -f 1; fi
}

java_major() {
    [ -x "$1" ] || return 1
    v=$("$1" -version 2>&1 | sed -n 's/.* version "\([^"]*\)".*/\1/p' | head -n 1)
    v=${v%%[!0-9]*}
    [ -n "$v" ] && printf '%s\n' "$v"
}

real_java=
for candidate in "${JAVA_HOME:-/nonexistent}/bin/java" "$(command -v java || true)"; do
    if major=$(java_major "$candidate") && [ "$major" -ge 21 ]; then
        real_java=$candidate
        break
    fi
done

fail() {
    printf '    %s\n' "$1" >&2
    if [ -f "$LOG" ]; then sed 's/^/    | /' "$LOG" >&2; fi
    exit 1
}

expect_eq() {
    [ "$1" = "$2" ] || fail "$3: expected '$2', got '$1'"
}

expect_contains() {
    case $1 in *"$2"*) ;; *) fail "$3: '$2' missing from '$1'" ;; esac
}

expect_lacks() {
    case $1 in *"$2"*) fail "$3: '$2' found in '$1'" ;; esac
}

# Sets up an empty HOME, release site, Adoptium site and fake service managers for one case.
fresh() {
    case_dir=$work/$1
    HOME="$case_dir/home dir"
    RELEASES=$case_dir/releases
    ADOPTIUM=$case_dir/adoptium
    CALLS=$case_dir/calls
    LOG=$case_dir/log
    mkdir -p "$HOME" "$RELEASES" "$ADOPTIUM" "$case_dir/fakebin" "$case_dir/stage"
    : >"$CALLS"
    for tool in systemctl launchctl; do
        cat >"$case_dir/fakebin/$tool" <<'EOF'
#!/bin/sh
echo "${0##*/} $*" >>"$CALLS"
case $* in
    *is-active* | print*) [ -f "$HOME/service-on" ] ;;
esac
EOF
        chmod 755 "$case_dir/fakebin/$tool"
    done
    unset LC_ALL LC_MESSAGES ALEXANDRITE_INSTALL_DIR ALEXANDRITE_BIN_DIR ALEXANDRITE_JAVA_HOME
    unset XDG_CONFIG_HOME XDG_DATA_HOME XDG_CACHE_HOME ALEXANDRITE_CONFIG ALEXANDRITE_DATA_DIR ALEXANDRITE_CACHE_DIR
    LANG=C
    PATH=$case_dir/fakebin:$original_path
    ALEXANDRITE_INSTALL_BASE=file://$RELEASES
    ALEXANDRITE_ADOPTIUM_BASE=file://$ADOPTIUM
    ALEXANDRITE_READY_TIMEOUT=3
    export HOME LANG PATH CALLS ALEXANDRITE_INSTALL_BASE ALEXANDRITE_ADOPTIUM_BASE ALEXANDRITE_READY_TIMEOUT
    ROOT=$HOME/.local/lib/alexandrite
    serve_jre "$(sha256_of_jre)"
}

service_on() {
    mkdir -p "$HOME/Library/LaunchAgents" "$HOME/.config/systemd/user"
    : >"$HOME/Library/LaunchAgents/org.unitedsoftware.alexandrite.plist"
    : >"$HOME/.config/systemd/user/alexandrite.service"
    : >"$HOME/service-on"
}

# Builds the fake Java runtime: `-version` answers 21, anything else runs the real Java.
sha256_of_jre() {
    jre=$case_dir/stage/jdk-21.0.5+11-jre
    mkdir -p "$jre/bin"
    {
        cat <<'EOF'
#!/bin/sh
echo "fake-jre $*" >>"$CALLS"
if [ "$1" = -version ]; then echo 'openjdk version "21.0.5" 2024-10-15' >&2 && exit 0; fi
EOF
        printf 'exec "%s" "$@"\n' "${real_java:-/nonexistent/java}"
    } >"$jre/bin/java"
    chmod 755 "$jre/bin/java"
    tar -czf "$case_dir/stage/jre.tar.gz" -C "$case_dir/stage" jdk-21.0.5+11-jre
    sha256 "$case_dir/stage/jre.tar.gz"
}

serve_jre() {
    for os in linux alpine-linux mac; do
        for arch in x64 aarch64; do
            directory=$ADOPTIUM/v3/binary/latest/21/ga/$os/$arch/jre/hotspot/normal
            mkdir -p "$directory"
            cp "$case_dir/stage/jre.tar.gz" "$directory/eclipse"
            printf '%s  OpenJDK21U-jre.tar.gz\n' "$1" >"$directory/eclipse.sha256.txt"
        done
    done
}

# Writes the SHA256SUMS of release $1 and makes it the latest.
seal() {
    release=$RELEASES/download/v$1
    cp "$script" "$release/install.sh"
    (cd "$release" && for file in alexandrite-*.tar.gz install.sh; do printf '%s  %s\n' "$(sha256 "$file")" "$file"; done >SHA256SUMS)
    mkdir -p "$RELEASES/latest/download"
    cp "$release/SHA256SUMS" "$RELEASES/latest/download/SHA256SUMS"
}

# Publishes a fake release $1 whose commands behave as $2 says: ok, badcheck, nocheck, notready, old.
publish() {
    version=$1
    distribution=$case_dir/stage/alexandrite-$version
    mkdir -p "$distribution/bin" "$RELEASES/download/v$version"
    cat >"$distribution/bin/alexandrite" <<EOF
#!/bin/sh
kind=$2
echo "app $version \$*" >>"\$CALLS"
case "\$1" in
    --version) echo "alexandrite $version" ;;
    config)
        case \$kind in
            badcheck) echo "config: the endpoint is unknown" >&2 && exit 78 ;;
            nocheck | old) echo "alexandrite: Unknown command config." >&2 && exit 64 ;;
        esac
        ;;
    status)
        case \$kind in
            notready) exit 69 ;;
            old) echo "alexandrite: Unknown command status." >&2 && exit 64 ;;
        esac
        ;;
    pairings) [ "\$kind" != notready ] || exit 69 ;;
esac
EOF
    chmod 755 "$distribution/bin/alexandrite"
    tar -czf "$RELEASES/download/v$version/alexandrite-$version.tar.gz" -C "$case_dir/stage" "alexandrite-$version"
    seal "$version"
}

installs() {
    $shell "$script" "$@" >"$LOG" 2>&1 || fail "install.sh $* failed"
}

refuses() {
    if $shell "$script" "$@" >"$LOG" 2>&1; then fail "install.sh $* succeeded"; fi
}

current() {
    readlink "$ROOT/current" || true
}

previous() {
    readlink "$ROOT/previous" || true
}

versions() {
    (cd "$ROOT/versions" && printf '%s ' *)
}

calls() {
    cat "$CALLS"
}

restarts() {
    grep -c -E 'systemctl --user restart|launchctl bootstrap' "$CALLS" || true
}

skip() {
    printf '    skipped: %s\n' "$1"
    exit 3
}

real_release() {
    [ -n "$real_archive" ] && [ -f "$real_archive" ] || skip "no distribution archive given"
    [ -n "$real_java" ] || skip "no Java 21+"
    name=${real_archive##*/}
    version=${name#alexandrite-}
    version=${version%.tar.gz}
    mkdir -p "$RELEASES/download/v$version"
    cp "$real_archive" "$RELEASES/download/v$version/"
    seal "$version"
}

case_installs_the_real_distribution() {
    fresh real
    real_release
    installs --no-init
    expect_eq "$("$HOME/.local/bin/alexandrite" --version)" "alexandrite $version" "the linked command"
    expect_eq "$(current)" "versions/$version" "current"
    [ -x "$ROOT/install.sh" ] || fail "install.sh is not kept"
    [ -x "$(cat "$ROOT/java-home")/bin/java" ] || fail "java-home names no Java"
    expect_contains "$(cat "$LOG")" "is not on your PATH" "the PATH hint"
}

case_runs_on_a_downloaded_runtime() {
    fresh jre
    real_release
    installs --no-init --download-jre
    expect_eq "$(cat "$ROOT/java-home")" "$ROOT/jre" "java-home"
    : >"$CALLS"
    expect_eq "$(JAVA_HOME=/nonexistent "$HOME/.local/bin/alexandrite" --version)" "alexandrite $version" "the command"
    expect_contains "$(calls)" "MainKt --version" "the runtime that ran it"
}

case_refuses_a_runtime_with_a_wrong_checksum() {
    fresh jre-checksum
    serve_jre 0000000000000000000000000000000000000000000000000000000000000000
    publish 1.0.0 ok
    refuses --no-init --download-jre
    expect_contains "$(cat "$LOG")" "checksum of the Java runtime does not match" "the refusal"
    [ ! -e "$ROOT/jre" ] || fail "the runtime was installed"
    expect_eq "$(current)" "" "current"
}

case_upgrades_keeps_two_and_rolls_back() {
    fresh upgrades
    publish 1.0.0 ok
    installs --no-init
    expect_eq "$(current)" "versions/1.0.0" "first install"
    expect_eq "$(previous)" "" "previous after the first install"
    expect_lacks "$(calls)" "init" "--no-init"

    service_on
    publish 1.1.0 ok
    installs
    expect_eq "$(current)" "versions/1.1.0" "upgrade by running again"
    expect_eq "$(previous)" "versions/1.0.0" "previous after the upgrade"
    expect_eq "$(restarts)" 1 "service restarts"
    expect_lacks "$(calls)" "init" "init on an upgrade"

    publish 1.2.0 ok
    $shell "$ROOT/install.sh" --update >"$LOG" 2>&1 || fail "the kept install.sh --update failed"
    expect_eq "$(current)" "versions/1.2.0" "update"
    expect_eq "$(versions)" "1.1.0 1.2.0 " "two versions kept"

    installs --update
    expect_contains "$(cat "$LOG")" "installed already" "an update to the same version"

    publish 1.3.0 ok
    printf 'tampered' >>"$RELEASES/download/v1.3.0/alexandrite-1.3.0.tar.gz"
    refuses --update
    expect_contains "$(cat "$LOG")" "checksum of alexandrite-1.3.0.tar.gz does not match" "the checksum refusal"
    expect_eq "$(current)" "versions/1.2.0" "current after a checksum mismatch"
    expect_eq "$(versions)" "1.1.0 1.2.0 " "versions after a checksum mismatch"

    publish 1.4.0 badcheck
    refuses --update
    expect_contains "$(cat "$LOG")" "the endpoint is unknown" "the config check's output"
    expect_eq "$(current)" "versions/1.2.0" "current after a failed config check"
    expect_eq "$(versions)" "1.1.0 1.2.0 " "versions after a failed config check"
    expect_eq "$(restarts)" 2 "service restarts after a failed config check"

    publish 1.5.0 notready
    refuses --update
    expect_contains "$(cat "$LOG")" "did not answer" "the readiness failure"
    expect_eq "$(current)" "versions/1.2.0" "current after a rollback"
    expect_eq "$(previous)" "versions/1.1.0" "previous after a rollback"
    expect_eq "$(versions)" "1.1.0 1.2.0 " "versions after a rollback"
    expect_eq "$(restarts)" 4 "service restarts after a rollback"

    publish 1.6.0 nocheck
    refuses --update
    expect_contains "$(cat "$LOG")" "has no 'config check'" "a release without config check"
    expect_eq "$(current)" "versions/1.2.0" "current after a release without config check"

    installs --update --version 1.1.0
    expect_eq "$(current)" "versions/1.1.0" "a pinned older version"
    expect_eq "$(previous)" "versions/1.2.0" "previous after a pinned older version"
}

case_updates_versions_before_config_check() {
    fresh old
    publish 0.0.1 old
    installs --no-init
    service_on
    publish 0.0.2 old
    installs --update
    expect_eq "$(current)" "versions/0.0.2" "update"
    expect_contains "$(cat "$LOG")" "has no 'config check'; the config is checked when it starts" "the note"
    expect_contains "$(calls)" "app 0.0.2 pairings" "the readiness probe"
}

case_goes_on_when_both_versions_fail_the_config_check() {
    fresh both
    publish 2.0.0 badcheck
    installs --no-init
    publish 2.1.0 badcheck
    installs --update
    expect_eq "$(current)" "versions/2.1.0" "update"
    expect_contains "$(cat "$LOG")" "of the installed 2.0.0 alike" "the warning"
}

case_update_needs_an_installation() {
    fresh none
    publish 1.0.0 ok
    refuses --update
    expect_contains "$(cat "$LOG")" "is not installed" "the refusal"
}

case_speaks_chinese() {
    fresh chinese
    LANG=zh_CN.UTF-8
    publish 1.0.0 ok
    installs --no-init
    publish 1.1.0 ok
    installs --update
    expect_contains "$(cat "$LOG")" "已安装 Alexandrite 1.1.0（原为 1.0.0）。" "the Chinese message"
}

for test_case in \
    case_installs_the_real_distribution \
    case_runs_on_a_downloaded_runtime \
    case_refuses_a_runtime_with_a_wrong_checksum \
    case_upgrades_keeps_two_and_rolls_back \
    case_updates_versions_before_config_check \
    case_goes_on_when_both_versions_fail_the_config_check \
    case_update_needs_an_installation \
    case_speaks_chinese; do
    printf '%s\n' "$test_case"
    set +e
    (
        set -e
        "$test_case"
    )
    status=$?
    set -e
    case $status in
        0) passed=$((passed + 1)) ;;
        3) skipped=$((skipped + 1)) ;;
        *) failed=$((failed + 1)) ;;
    esac
done
printf '%s passed, %s failed, %s skipped\n' "$passed" "$failed" "$skipped"
[ "$failed" = 0 ]
