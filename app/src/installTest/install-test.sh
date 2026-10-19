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
    unset NO_COLOR ZDOTDIR
    LANG=C
    SHELL=/bin/sh
    PATH=$case_dir/fakebin:$original_path
    ALEXANDRITE_INSTALL_BASE=file://$RELEASES
    ALEXANDRITE_ADOPTIUM_BASE=file://$ADOPTIUM
    ALEXANDRITE_READY_TIMEOUT=3
    ALEXANDRITE_INSTALL_TTY=$case_dir/no-terminal
    CONFIG=$HOME/.config/alexandrite/alexandrite.json
    export HOME LANG SHELL PATH CALLS ALEXANDRITE_INSTALL_BASE ALEXANDRITE_ADOPTIUM_BASE ALEXANDRITE_READY_TIMEOUT
    export ALEXANDRITE_INSTALL_TTY
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
            nocheck | old) echo "alexandrite: Unknown command config." >&2 && exit 64 ;;
        esac
        if [ "\$2" = path ]; then echo "\$HOME/.config/alexandrite/alexandrite.json" && exit 0; fi
        if [ "\$kind" = badcheck ]; then echo "config: the endpoint is unknown" >&2 && exit 78; fi
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

configured() {
    mkdir -p "${CONFIG%/*}"
    printf '{}\n' >"$CONFIG"
}

markers() {
    grep -c '^# >>> alexandrite >>>$' "$1" || true
}

inits() {
    grep -c ' init$' "$CALLS" || true
}

# The output in LOG without escape sequences.
drawn() {
    sed "s/$(printf '\033')\[[0-9;?]*[A-Za-z]//g" "$LOG"
}

# Runs install.sh under a pseudo-terminal, its output in LOG; skips without script(1).
installs_on_terminal() {
    command -v script >/dev/null 2>&1 || skip "no script(1)"
    if script --version 2>/dev/null | grep -q util-linux; then
        command="$shell '$script'"
        for argument in "$@"; do command="$command '$argument'"; done
        TERM=xterm script -qec "$command" /dev/null </dev/null >"$LOG" 2>&1 || fail "install.sh $* failed"
    else
        TERM=xterm script -q /dev/null "$shell" "$script" "$@" </dev/null >"$LOG" 2>&1 || fail "install.sh $* failed"
    fi
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
    expect_eq "$(markers "$HOME/.profile")" 1 "the PATH block"
    expect_contains "$("$HOME/.local/bin/alexandrite" config path)" "/alexandrite.json" "the config path init looks for"
    expect_lacks "$(calls)" " init" "--no-init"
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

    configured
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
    rm -rf "$ROOT"
    installs
    expect_contains "$(cat "$LOG")" "已跳过设置（没有终端）；安装后请运行 'alexandrite init'。" "the Chinese skip line"
    expect_lacks "$(cat "$LOG")" "Added" "the Chinese PATH note"
}

case_prints_plain_lines_without_a_terminal() {
    fresh plain
    publish 1.0.0 ok
    installs --no-init
    escape=$(printf '\033')
    for drawn in ┌ ◆ ✓ ✗ → · "[1/4]" "$escape"; do expect_lacks "$(cat "$LOG")" "$drawn" "the plain output"; done
    expect_contains "$(cat "$LOG")" "Alexandrite 1.0.0 is installed: $HOME/.local/bin/alexandrite" "the summary"
}

case_draws_on_a_terminal() {
    fresh terminal
    publish 1.0.0 ok
    installs_on_terminal --no-init
    for drawn in "┌──" "◆ Alexandrite" "Distributed Model Harness." "[1/4] Environment" "[3/4] Download and verify" \
        "[4/4] Install" "✓ Detected:" "Install directory:" "✓ Downloaded alexandrite-1.0.0.tar.gz" \
        "✓ Alexandrite 1.0.0 is installed." "Then run: alexandrite"; do
        expect_contains "$(drawn)" "$drawn" "the terminal output"
    done
    expect_contains "$(cat "$LOG")" "$(printf '\033[32m')" "the colours"

    fresh terminal-no-color
    NO_COLOR=1
    export NO_COLOR
    publish 1.0.0 ok
    installs_on_terminal --no-init
    expect_contains "$(drawn)" "✓ Detected:" "the glyphs with NO_COLOR"
    expect_lacks "$(cat "$LOG")" "$(printf '\033[32m')" "the colours with NO_COLOR"
}

case_speaks_chinese_on_a_terminal() {
    fresh terminal-chinese
    LANG=zh_CN.UTF-8
    publish 1.0.0 ok
    installs_on_terminal --no-init
    for drawn in "分布式模型运行框架。" "[1/4] 环境" "[3/4] 下载并校验" "[4/4] 安装" "已检测：" "安装目录：" "然后运行：alexandrite"; do
        expect_contains "$(drawn)" "$drawn" "the Chinese terminal output"
    done
    for english in Environment Download Detected Install Version Command Location Run Added Harness; do
        expect_lacks "$(cat "$LOG")" "$english" "the Chinese terminal output"
    done
}

case_shows_the_log_of_a_failed_download() {
    fresh failed-download
    publish 1.0.0 ok
    rm "$RELEASES/download/v1.0.0/alexandrite-1.0.0.tar.gz"
    refuses --no-init
    expect_contains "$(cat "$LOG")" "Downloading Alexandrite 1.0.0 ...
  | " "the log's end"
    expect_contains "$(cat "$LOG")" "Cannot download alexandrite-1.0.0.tar.gz." "the refusal"
    expect_eq "$(current)" "" "current"
}

case_puts_the_command_on_path() {
    fresh path-zsh
    SHELL=/bin/zsh
    publish 1.0.0 ok
    installs --no-init
    installs --no-init
    expect_contains "$(cat "$LOG")" "installed already" "the second run"
    expect_eq "$(markers "$HOME/.profile")" 1 "the block in .profile"
    expect_eq "$(markers "$HOME/.zshrc")" 1 "the block in .zshrc"
    expect_eq "$(env -i HOME="$HOME" PATH=/usr/bin:/bin sh -c '. "$HOME/.profile" && command -v alexandrite')" \
        "$HOME/.local/bin/alexandrite" "the command after .profile"
    if command -v zsh >/dev/null 2>&1; then
        expect_eq "$(env -i HOME="$HOME" PATH=/usr/bin:/bin zsh -f -c '. "$HOME/.zshrc" && command -v alexandrite')" \
            "$HOME/.local/bin/alexandrite" "the command after .zshrc"
    fi

    fresh path-bash
    SHELL=/bin/bash
    printf 'echo kept' >"$HOME/.bashrc"
    publish 1.0.0 ok
    installs --no-init
    expect_contains "$(cat "$LOG")" "Added $HOME/.local/bin to PATH in ~/.profile, ~/.bashrc." "the changed files"
    expect_eq "$(head -n 2 "$HOME/.bashrc")" "echo kept
# >>> alexandrite >>>" "the block after the last line"

    fresh path-fish
    SHELL=/usr/bin/fish
    publish 1.0.0 ok
    installs --no-init
    installs --no-init
    fish=$HOME/.config/fish/conf.d/alexandrite.fish
    expect_eq "$(markers "$fish")" 1 "the fish block"
    expect_contains "$(cat "$fish")" "set -gx PATH \"$HOME/.local/bin\" \$PATH" "the fish line"
}

case_leaves_path_alone_when_asked() {
    fresh no-path
    publish 1.0.0 ok
    installs --no-init --no-modify-path
    [ ! -e "$HOME/.profile" ] || fail ".profile was written"
    expect_contains "$(cat "$LOG")" "is not on your PATH" "the PATH hint"
}

case_runs_init_until_a_config_exists() {
    fresh init
    : >"$case_dir/terminal"
    ALEXANDRITE_INSTALL_TTY=$case_dir/terminal
    publish 1.0.0 ok
    installs
    expect_eq "$(inits)" 1 "init after the first install"
    expect_contains "$(cat "$LOG")" "Starting setup: alexandrite init" "the note"

    publish 1.1.0 ok
    installs
    expect_eq "$(inits)" 2 "init after an upgrade without a config"

    configured
    publish 1.2.0 ok
    installs
    expect_eq "$(inits)" 2 "init with a config"

    rm "$CONFIG"
    installs --no-init
    expect_eq "$(inits)" 2 "--no-init"

    ALEXANDRITE_INSTALL_TTY=$case_dir/no-terminal
    installs
    expect_eq "$(inits)" 2 "init without a terminal"
    expect_contains "$(cat "$LOG")" "Setup skipped (no terminal); run 'alexandrite init' after install." "the skip line"
}

for test_case in \
    case_installs_the_real_distribution \
    case_runs_on_a_downloaded_runtime \
    case_refuses_a_runtime_with_a_wrong_checksum \
    case_upgrades_keeps_two_and_rolls_back \
    case_updates_versions_before_config_check \
    case_goes_on_when_both_versions_fail_the_config_check \
    case_update_needs_an_installation \
    case_speaks_chinese \
    case_prints_plain_lines_without_a_terminal \
    case_draws_on_a_terminal \
    case_speaks_chinese_on_a_terminal \
    case_shows_the_log_of_a_failed_download \
    case_puts_the_command_on_path \
    case_leaves_path_alone_when_asked \
    case_runs_init_until_a_config_exists; do
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
