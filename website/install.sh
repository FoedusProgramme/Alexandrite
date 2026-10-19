#!/bin/sh
# Installs, upgrades or updates Alexandrite for the current user on Linux and macOS:
#   curl -fsSL https://alexandrite.unitedsoftware.org/install.sh | sh
#   curl -fsSL https://alexandrite.unitedsoftware.org/install.sh | sh -s -- --version 0.2.0 --no-init
set -eu

RELEASES=${ALEXANDRITE_INSTALL_BASE:-https://github.com/FoedusProgramme/alexandrite/releases}
ADOPTIUM=${ALEXANDRITE_ADOPTIUM_BASE:-https://api.adoptium.net}
ROOT=${ALEXANDRITE_INSTALL_DIR:-$HOME/.local/lib/alexandrite}
BIN_DIR=${ALEXANDRITE_BIN_DIR:-$HOME/.local/bin}
READY_TIMEOUT=${ALEXANDRITE_READY_TIMEOUT:-90}
SYSTEMD_UNIT=alexandrite.service
LAUNCHD_LABEL=org.unitedsoftware.alexandrite
JAVA_MAJOR=21
# The first release whose `alexandrite config check` exists.
CONFIG_CHECK_SINCE=0.1.0
EX_USAGE=64

case "${LC_ALL:-${LC_MESSAGES:-${LANG:-}}}" in
    zh*) ZH=1 ;;
    *) ZH=0 ;;
esac

say() {
    if [ "$ZH" = 1 ]; then printf '%s\n' "$2"; else printf '%s\n' "$1"; fi
}

warn() {
    say "$@" >&2
}

die() {
    warn "$@"
    exit 1
}

usage() {
    say "Usage: install.sh [--version <v>] [--no-init] [--update] [--download-jre] [--config <file>] [--data-dir <dir>]

Installs Alexandrite into $ROOT and links the command into $BIN_DIR.
Running it again upgrades in place; two versions are kept.

  --version <v>     install release <v> instead of the latest
  --no-init         do not run 'alexandrite init' after a first install
  --update          update an existing installation (what 'alexandrite update' runs)
  --download-jre    use a Java runtime of Alexandrite's own even when the system has Java $JAVA_MAJOR+
  --config <file>   the config file the new version is checked against
  --data-dir <dir>  the data directory of the instance to wait for

ALEXANDRITE_INSTALL_DIR and ALEXANDRITE_BIN_DIR change the two directories.
ALEXANDRITE_INSTALL_BASE and ALEXANDRITE_ADOPTIUM_BASE replace the download sites (file:// URLs are for testing)." \
        "用法：install.sh [--version <版本>] [--no-init] [--update] [--download-jre] [--config <文件>] [--data-dir <目录>]

把 Alexandrite 安装到 ${ROOT}，并把命令链接到 ${BIN_DIR}。
再次运行即原地升级；保留两个版本。

  --version <版本>   安装指定版本，而不是最新版本
  --no-init          首次安装后不运行 'alexandrite init'
  --update           更新已有的安装（'alexandrite update' 运行的就是它）
  --download-jre     即使系统已有 Java $JAVA_MAJOR+，也使用 Alexandrite 自带的 Java 运行时
  --config <文件>    检查新版本时使用的配置文件
  --data-dir <目录>  要等待其就绪的实例的数据目录

ALEXANDRITE_INSTALL_DIR 和 ALEXANDRITE_BIN_DIR 可改变这两个目录。
ALEXANDRITE_INSTALL_BASE 和 ALEXANDRITE_ADOPTIUM_BASE 可替换下载地址（file:// 地址用于测试）。"
}

have() {
    command -v "$1" >/dev/null 2>&1
}

fetch() {
    case $1 in
        file://*) cp "${1#file://}" "$2" ;;
        https://*)
            if have curl; then
                curl -fsSL --proto '=https' --proto-redir '=https' --retry 3 -o "$2" "$1"
            elif have wget; then
                wget -q --https-only -O "$2" "$1"
            else
                die "Neither curl nor wget is installed." "curl 和 wget 都没有安装。"
            fi
            ;;
        *) die "Refusing to download $1: only https:// and file:// URLs are used." \
            "拒绝下载 $1：只使用 https:// 和 file:// 地址。" ;;
    esac
}

# The location $1 redirects to, $1 itself for a file:// URL.
redirect_target() {
    case $1 in
        file://*) printf '%s\n' "$1" ;;
        *)
            if have curl; then
                curl -fsS --proto '=https' -o /dev/null -w '%{redirect_url}' "$1"
            else
                wget --https-only --max-redirect=0 -S --spider "$1" 2>&1 |
                    sed -n 's/^ *[Ll]ocation: *//p' | tr -d '\r' | head -n 1
            fi
            ;;
    esac
}

sha256() {
    if have sha256sum; then
        sha256sum "$1" | cut -d ' ' -f 1
    elif have shasum; then
        shasum -a 256 "$1" | cut -d ' ' -f 1
    elif have openssl; then
        openssl dgst -sha256 -r "$1" | cut -d ' ' -f 1
    else
        return 1
    fi
}

verify() {
    actual=$(sha256 "$1") || die "No SHA-256 tool (sha256sum, shasum or openssl) is installed." \
        "没有安装 SHA-256 工具（sha256sum、shasum 或 openssl）。"
    expected=$(printf '%s' "$2" | tr 'A-F' 'a-f')
    if [ -z "$expected" ] || [ "$actual" != "$expected" ]; then
        die "The checksum of $3 does not match ($actual, expected $expected); nothing was changed." \
            "$3 的校验和不符（${actual}，应为 ${expected}）；未做任何更改。"
    fi
}

# Whether version $1 sorts before $2, a pre-release (`1.0.0-rc1`) before its release.
version_lt() {
    awk -v a="$1" -v b="$2" 'BEGIN {
        na = split(a, A, "-"); nb = split(b, B, "-")
        ca = split(A[1], X, "."); cb = split(B[1], Y, ".")
        n = ca > cb ? ca : cb
        for (i = 1; i <= n; i++) {
            if (X[i] + 0 < Y[i] + 0) exit 0
            if (X[i] + 0 > Y[i] + 0) exit 1
        }
        exit (na > 1 && nb == 1) ? 0 : 1
    }'
}

detect_platform() {
    case $(uname -s) in
        Linux)
            OS=linux
            if [ -f /etc/alpine-release ] || ldd --version 2>&1 | grep -qi musl; then OS=alpine-linux; fi
            ;;
        Darwin) OS=mac ;;
        MINGW* | MSYS* | CYGWIN*) die "On Windows, run install.ps1 in PowerShell." "在 Windows 上请在 PowerShell 中运行 install.ps1。" ;;
        *) die "Alexandrite installs on Linux and macOS only, not on $(uname -s)." \
            "Alexandrite 只能安装在 Linux 和 macOS 上，不支持 $(uname -s)。" ;;
    esac
    case $(uname -m) in
        x86_64 | amd64) ARCH=x64 ;;
        aarch64 | arm64) ARCH=aarch64 ;;
        *) die "Alexandrite installs on x64 and arm64 only, not on $(uname -m)." \
            "Alexandrite 只能安装在 x64 和 arm64 上，不支持 $(uname -m)。" ;;
    esac
    if [ "$OS" = mac ] && [ "$ARCH" = x64 ] && [ "$(sysctl -n sysctl.proc_translated 2>/dev/null || true)" = 1 ]; then
        ARCH=aarch64
    fi
}

java_major() {
    [ -x "$1" ] || return 1
    v=$("$1" -version 2>&1 | sed -n 's/.* version "\([^"]*\)".*/\1/p' | head -n 1)
    case $v in 1.*) v=${v#1.} ;; esac
    v=${v%%[!0-9]*}
    [ -n "$v" ] && printf '%s\n' "$v"
}

java_ok() {
    major=$(java_major "$1") && [ "$major" -ge "$JAVA_MAJOR" ]
}

# Sets JAVA_PIN to the Java home the launcher uses and records it in java-home.
ensure_java() {
    JAVA_PIN=
    if [ "$DOWNLOAD_JRE" = 0 ]; then
        if [ -f "$ROOT/java-home" ] && java_ok "$(cat "$ROOT/java-home")/bin/java"; then
            JAVA_PIN=$(cat "$ROOT/java-home")
        elif [ -n "${JAVA_HOME:-}" ] && java_ok "$JAVA_HOME/bin/java"; then
            JAVA_PIN=$JAVA_HOME
        elif [ "$OS" = mac ] && home=$(/usr/libexec/java_home -v "$JAVA_MAJOR+" 2>/dev/null) &&
            java_ok "$home/bin/java"; then
            JAVA_PIN=$home
        elif java=$(command -v java) && { [ "$OS" != mac ] || [ "$java" != /usr/bin/java ]; } && java_ok "$java"; then
            case $java in
                */bin/java) JAVA_PIN=${java%/bin/java} ;;
                *) JAVA_PIN=$("$java" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java\.home = //p') ;;
            esac
        fi
    fi
    if [ -z "$JAVA_PIN" ]; then download_jre; fi
    printf '%s\n' "$JAVA_PIN" >"$TMP/java-home"
    mv -f "$TMP/java-home" "$ROOT/java-home"
    JAVA_HOME=$JAVA_PIN
    export JAVA_HOME
}

download_jre() {
    url="$ADOPTIUM/v3/binary/latest/$JAVA_MAJOR/ga/$OS/$ARCH/jre/hotspot/normal/eclipse"
    package=$(redirect_target "$url") || package=
    [ -n "$package" ] || die "Adoptium names no Java $JAVA_MAJOR runtime for $OS $ARCH." \
        "Adoptium 没有提供适用于 $OS $ARCH 的 Java $JAVA_MAJOR 运行时。"
    say "No Java $JAVA_MAJOR+ found; downloading a Java runtime from Adoptium into $ROOT/jre ..." \
        "未找到 Java $JAVA_MAJOR+；正在从 Adoptium 下载 Java 运行时到 $ROOT/jre ……"
    fetch "$package" "$TMP/jre.tar.gz" || die "Cannot download $package." "无法下载 ${package}。"
    fetch "$package.sha256.txt" "$TMP/jre.sha256" || die "Cannot download $package.sha256.txt." \
        "无法下载 $package.sha256.txt。"
    verify "$TMP/jre.tar.gz" "$(cut -d ' ' -f 1 <"$TMP/jre.sha256")" "the Java runtime"
    mkdir "$TMP/jre"
    tar -xzf "$TMP/jre.tar.gz" -C "$TMP/jre" || die "Cannot unpack the Java runtime." "无法解压 Java 运行时。"
    set -- "$TMP/jre"/*
    if [ $# -ne 1 ] || [ ! -d "$1" ]; then die "The Java runtime archive is not one directory." "Java 运行时压缩包不是单个目录。"; fi
    if [ -x "$1/Contents/Home/bin/java" ]; then
        home=/Contents/Home
    elif [ -x "$1/bin/java" ]; then
        home=
    else
        die "The Java runtime archive holds no bin/java." "Java 运行时压缩包中没有 bin/java。"
    fi
    java_ok "$1$home/bin/java" || die "The downloaded Java runtime does not run." "下载的 Java 运行时无法运行。"
    rm -rf "$ROOT/jre.old"
    if [ -e "$ROOT/jre" ]; then mv "$ROOT/jre" "$ROOT/jre.old"; fi
    mv "$1" "$ROOT/jre"
    rm -rf "$ROOT/jre.old"
    JAVA_PIN=$ROOT/jre$home
}

# Sets VERSION, ARCHIVE and ARCHIVE_SHA from the release's SHA256SUMS.
resolve_release() {
    if [ -n "$VERSION" ]; then
        sums="$RELEASES/download/v$VERSION/SHA256SUMS"
    else
        sums="$RELEASES/latest/download/SHA256SUMS"
    fi
    fetch "$sums" "$TMP/SHA256SUMS" || die "Cannot download $sums: no such release?" "无法下载 ${sums}：该版本不存在？"
    tr -d '\r' <"$TMP/SHA256SUMS" >"$TMP/sums"
    line=$(sed -n 's/^\([0-9a-fA-F]\{64\}\) [ *]\(alexandrite-[^ /]*\.tar\.gz\)$/\1 \2/p' "$TMP/sums" | head -n 1)
    [ -n "$line" ] || die "$sums lists no alexandrite-<version>.tar.gz." "$sums 中没有 alexandrite-<版本>.tar.gz。"
    ARCHIVE=${line#* }
    ARCHIVE_SHA=${line%% *}
    found=${ARCHIVE#alexandrite-}
    found=${found%.tar.gz}
    if [ -n "$VERSION" ] && [ "$found" != "$VERSION" ]; then
        die "Release $VERSION holds $ARCHIVE." "版本 $VERSION 中的压缩包是 ${ARCHIVE}。"
    fi
    VERSION=$found
    case $VERSION in
        '' | .* | *[!0-9A-Za-z.+_-]*) die "'$VERSION' is no version." "'$VERSION' 不是版本号。" ;;
    esac
}

sums_hash() {
    sed -n "s/^\([0-9a-fA-F]\{64\}\) [ *]$1\$/\1/p" "$TMP/sums" | head -n 1
}

link_version() {
    if [ -L "$ROOT/$1" ]; then
        target=$(readlink "$ROOT/$1")
        printf '%s\n' "${target##*/}"
    fi
}

stage_release() {
    say "Downloading Alexandrite $VERSION ..." "正在下载 Alexandrite $VERSION ……"
    fetch "$RELEASES/download/v$VERSION/$ARCHIVE" "$TMP/$ARCHIVE" || die "Cannot download $ARCHIVE." "无法下载 ${ARCHIVE}。"
    verify "$TMP/$ARCHIVE" "$ARCHIVE_SHA" "$ARCHIVE"
    mkdir "$TMP/release"
    tar -xzf "$TMP/$ARCHIVE" -C "$TMP/release" || die "Cannot unpack $ARCHIVE." "无法解压 ${ARCHIVE}。"
    set -- "$TMP/release"/*
    if [ $# -ne 1 ] || [ ! -x "$1/bin/alexandrite" ]; then
        die "$ARCHIVE holds no alexandrite-$VERSION/bin/alexandrite." "$ARCHIVE 中没有 alexandrite-$VERSION/bin/alexandrite。"
    fi
    rm -rf "$ROOT/versions/$VERSION"
    mv "$1" "$ROOT/versions/$VERSION"
    hash=$(sums_hash install.sh)
    if [ -n "$hash" ]; then
        fetch "$RELEASES/download/v$VERSION/install.sh" "$TMP/install.sh" || die "Cannot download install.sh." \
            "无法下载 install.sh。"
        verify "$TMP/install.sh" "$hash" install.sh
    elif [ -f "$0" ] && [ "${0##*/}" = install.sh ]; then
        cp "$0" "$TMP/install.sh"
    fi
}

# Runs the app binary $1 with the arguments that follow and the locations given to this script.
app() {
    binary=$1
    shift
    if [ -n "$CONFIG_FILE" ]; then set -- "$@" --config "$CONFIG_FILE"; fi
    if [ -n "$DATA_DIR" ]; then set -- "$@" --data-dir "$DATA_DIR"; fi
    "$binary" "$@"
}

missing_command() {
    [ "$1" = "$EX_USAGE" ] && case $2 in *"Unknown command"*) true ;; *) false ;; esac
}

check_release() {
    new=$ROOT/versions/$VERSION/bin/alexandrite
    if ! out=$("$new" --version 2>&1); then
        warn "Alexandrite $VERSION does not start:" "Alexandrite $VERSION 无法启动："
        printf '%s\n' "$out" >&2
        return 1
    fi
    case $out in
        *"alexandrite $VERSION"*) ;;
        *)
            warn "Alexandrite $VERSION names itself '$out'." "Alexandrite $VERSION 报告的版本是 '$out'。"
            return 1
            ;;
    esac
    [ -n "$PREV" ] || return 0
    if out=$(app "$new" config check 2>&1); then return 0; else code=$?; fi
    if missing_command "$code" "$out"; then
        if version_lt "$VERSION" "$CONFIG_CHECK_SINCE"; then
            say "Alexandrite $VERSION has no 'config check'; the config is checked when it starts." \
                "Alexandrite $VERSION 没有 'config check'；配置将在启动时检查。"
            return 0
        fi
        warn "Alexandrite $VERSION has no 'config check'." "Alexandrite $VERSION 没有 'config check'。"
        return 1
    fi
    if old=$(app "$ROOT/versions/$PREV/bin/alexandrite" config check 2>&1); then old_code=0; else old_code=$?; fi
    if [ "$old_code" != 0 ] && ! missing_command "$old_code" "$old"; then
        warn "The config fails the check of $VERSION and of the installed $PREV alike; going on:" \
            "配置在 $VERSION 和已安装的 $PREV 上都未通过检查；继续："
        printf '%s\n' "$out" >&2
        return 0
    fi
    warn "The config fails the check of Alexandrite $VERSION:" "配置未通过 Alexandrite $VERSION 的检查："
    printf '%s\n' "$out" >&2
    return 1
}

write_launcher() {
    mkdir -p "$ROOT/bin"
    quoted=$(printf '%s' "$ROOT" | sed "s/'/'\\\\''/g")
    {
        printf '#!/bin/sh\n'
        printf "ALEXANDRITE_INSTALL_DIR='%s'\n" "$quoted"
        cat <<'EOF'
export ALEXANDRITE_INSTALL_DIR
if [ -z "${ALEXANDRITE_JAVA_HOME:-}" ] && [ -f "$ALEXANDRITE_INSTALL_DIR/java-home" ]; then
    ALEXANDRITE_JAVA_HOME=$(cat "$ALEXANDRITE_INSTALL_DIR/java-home")
fi
if [ -n "${ALEXANDRITE_JAVA_HOME:-}" ] && [ -x "$ALEXANDRITE_JAVA_HOME/bin/java" ]; then
    JAVA_HOME=$ALEXANDRITE_JAVA_HOME
    export JAVA_HOME ALEXANDRITE_JAVA_HOME
fi
exec "$ALEXANDRITE_INSTALL_DIR/current/bin/alexandrite" "$@"
EOF
    } >"$TMP/launcher"
    chmod 755 "$TMP/launcher"
    mv -f "$TMP/launcher" "$ROOT/bin/alexandrite"
}

link_launcher() {
    mkdir -p "$BIN_DIR"
    LAUNCHER=$BIN_DIR/alexandrite
    if [ -e "$LAUNCHER" ] && [ ! -L "$LAUNCHER" ]; then
        warn "$LAUNCHER is a file of its own and stays; the command is $ROOT/bin/alexandrite." \
            "$LAUNCHER 是另一个文件，保持不变；命令位于 $ROOT/bin/alexandrite。"
        LAUNCHER=$ROOT/bin/alexandrite
        return 0
    fi
    ln -sfn "$ROOT/bin/alexandrite" "$LAUNCHER"
    case ":$PATH:" in
        *":$BIN_DIR:"*) ;;
        *) say "$BIN_DIR is not on your PATH. Add this line to your shell's profile (~/.profile, ~/.bashrc or ~/.zshrc):
  export PATH=\"$BIN_DIR:\$PATH\"" "$BIN_DIR 不在 PATH 中。请把这一行加入 shell 的配置文件（~/.profile、~/.bashrc 或 ~/.zshrc）：
  export PATH=\"$BIN_DIR:\$PATH\"" ;;
    esac
}

# Sets SERVICE to the background service that runs now, empty when none does.
detect_service() {
    SERVICE=
    PLIST=$HOME/Library/LaunchAgents/$LAUNCHD_LABEL.plist
    if [ "$OS" = mac ]; then
        if [ -f "$PLIST" ] && have launchctl && launchctl print "gui/$(id -u)/$LAUNCHD_LABEL" >/dev/null 2>&1; then
            SERVICE=launchd
        fi
    elif have systemctl && systemctl --user is-active --quiet "$SYSTEMD_UNIT" 2>/dev/null; then
        SERVICE=systemd
    fi
}

restart_service() {
    case $SERVICE in
        systemd) systemctl --user restart "$SYSTEMD_UNIT" ;;
        launchd)
            domain=gui/$(id -u)
            launchctl bootout "$domain/$LAUNCHD_LABEL" 2>/dev/null || true
            tries=0
            until launchctl bootstrap "$domain" "$PLIST" 2>/dev/null; do
                tries=$((tries + 1))
                [ "$tries" -lt 10 ] || return 1
                sleep 1
            done
            ;;
    esac
}

# Polls `alexandrite status` (`pairings` for a version without it) until the instance answers.
wait_ready() {
    probe=status
    waited=0
    while [ "$waited" -lt "$READY_TIMEOUT" ]; do
        if out=$(app "$ROOT/bin/alexandrite" "$probe" 2>&1); then return 0; else code=$?; fi
        if [ "$probe" = status ] && missing_command "$code" "$out"; then
            probe=pairings
            continue
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

switch_to() {
    ln -sfn "versions/$1" "$ROOT/current"
    if [ -n "$2" ]; then ln -sfn "versions/$2" "$ROOT/previous"; else rm -f "$ROOT/previous"; fi
}

rollback() {
    warn "$1" "$2"
    if [ "$OLD_PREVIOUS" = "$VERSION" ]; then OLD_PREVIOUS=; fi
    switch_to "$PREV" "$OLD_PREVIOUS"
    if [ -n "$SERVICE" ]; then
        if ! { restart_service && wait_ready; }; then
            warn "Alexandrite $PREV did not come back either; see its logs." "Alexandrite $PREV 也未能恢复运行；请查看日志。"
        fi
    fi
    rm -rf "${ROOT:?}/versions/$VERSION"
    die "Switched back to Alexandrite $PREV." "已切换回 Alexandrite ${PREV}。"
}

prune() {
    keep_current=$(link_version current)
    keep_previous=$(link_version previous)
    for directory in "$ROOT/versions"/*; do
        [ -d "$directory" ] || continue
        name=${directory##*/}
        if [ "$name" != "$keep_current" ] && [ "$name" != "$keep_previous" ]; then rm -rf "$directory"; fi
    done
}

cleanup() {
    rm -rf "$TMP"
    rmdir "$ROOT/.lock" 2>/dev/null || true
}

run_init() {
    if [ -t 0 ]; then
        "$LAUNCHER" init || true
    elif (: </dev/tty) 2>/dev/null; then
        "$LAUNCHER" init </dev/tty || true
    else
        say "Run 'alexandrite init' to set Alexandrite up." "请运行 'alexandrite init' 完成设置。"
    fi
}

main() {
    VERSION=
    PINNED=0
    NO_INIT=0
    UPDATE=0
    DOWNLOAD_JRE=0
    CONFIG_FILE=
    DATA_DIR=
    while [ $# -gt 0 ]; do
        case $1 in
            --version | --config | --data-dir)
                [ $# -ge 2 ] || die "$1 needs a value." "$1 需要一个值。"
                case $1 in
                    --version) VERSION=$2 ;;
                    --config) CONFIG_FILE=$2 ;;
                    *) DATA_DIR=$2 ;;
                esac
                shift 2
                ;;
            --version=*) VERSION=${1#*=} && shift ;;
            --config=*) CONFIG_FILE=${1#*=} && shift ;;
            --data-dir=*) DATA_DIR=${1#*=} && shift ;;
            --no-init) NO_INIT=1 && shift ;;
            --update) UPDATE=1 && NO_INIT=1 && shift ;;
            --download-jre) DOWNLOAD_JRE=1 && shift ;;
            -h | --help) usage && exit 0 ;;
            *) usage >&2 && die "Unknown option $1." "未知选项 $1。" ;;
        esac
    done
    VERSION=${VERSION#v}
    if [ -n "$VERSION" ]; then PINNED=1; fi
    case $ROOT in
        /*) ;;
        *) die "ALEXANDRITE_INSTALL_DIR must be an absolute path." "ALEXANDRITE_INSTALL_DIR 必须是绝对路径。" ;;
    esac
    detect_platform
    mkdir -p "$ROOT/versions"
    mkdir "$ROOT/.lock" 2>/dev/null || die "Another install runs in $ROOT (remove $ROOT/.lock if none does)." \
        "$ROOT 中正在进行另一次安装（如果没有，请删除 $ROOT/.lock）。"
    TMP=$ROOT/.tmp.$$
    trap cleanup EXIT
    trap 'exit 1' INT TERM HUP
    mkdir "$TMP"

    PREV=$(link_version current)
    OLD_PREVIOUS=$(link_version previous)
    if [ "$UPDATE" = 1 ] && [ -z "$PREV" ]; then
        die "Alexandrite is not installed in $ROOT; run install.sh without --update." \
            "$ROOT 中没有安装 Alexandrite；请不带 --update 运行 install.sh。"
    fi
    resolve_release
    if [ "$VERSION" = "$PREV" ] && [ -x "$ROOT/versions/$PREV/bin/alexandrite" ]; then
        ensure_java
        write_launcher
        link_launcher
        say "Alexandrite $VERSION is installed already." "Alexandrite $VERSION 已经安装。"
        return 0
    fi
    if [ "$UPDATE" = 1 ] && [ "$PINNED" = 0 ] && version_lt "$VERSION" "$PREV"; then
        say "Alexandrite $PREV is newer than the latest release $VERSION; nothing to do." \
            "Alexandrite $PREV 比最新发布的 $VERSION 更新；无需操作。"
        return 0
    fi
    ensure_java
    stage_release
    if ! check_release; then
        rm -rf "${ROOT:?}/versions/$VERSION"
        if [ -n "$PREV" ]; then
            die "Alexandrite $VERSION was not installed; $PREV stays." "未安装 Alexandrite ${VERSION}；继续使用 ${PREV}。"
        fi
        die "Alexandrite $VERSION was not installed." "未安装 Alexandrite ${VERSION}。"
    fi
    write_launcher
    SERVICE=
    if [ -n "$PREV" ]; then detect_service; fi
    switch_to "$VERSION" "$PREV"
    if [ -n "$PREV" ]; then
        if [ -n "$SERVICE" ]; then
            say "Restarting the background service with Alexandrite $VERSION ..." "正在用 Alexandrite $VERSION 重启后台服务……"
            restart_service || rollback "The background service did not restart with Alexandrite $VERSION." \
                "后台服务未能用 Alexandrite $VERSION 重启。"
            wait_ready || rollback "Alexandrite $VERSION did not answer within $READY_TIMEOUT seconds." \
                "Alexandrite $VERSION 在 $READY_TIMEOUT 秒内没有响应。"
        else
            say "No background service runs; the next start runs Alexandrite $VERSION." \
                "没有正在运行的后台服务；下次启动时将运行 Alexandrite ${VERSION}。"
        fi
        prune
    fi
    if [ -f "$TMP/install.sh" ]; then
        chmod 755 "$TMP/install.sh"
        mv -f "$TMP/install.sh" "$ROOT/install.sh"
    fi
    link_launcher
    if [ -n "$PREV" ]; then
        say "Alexandrite $VERSION is installed (was $PREV)." "已安装 Alexandrite ${VERSION}（原为 ${PREV}）。"
    else
        say "Alexandrite $VERSION is installed: $LAUNCHER" "已安装 Alexandrite ${VERSION}：$LAUNCHER"
    fi
    cleanup
    trap - EXIT
    if [ -z "$PREV" ] && [ "$NO_INIT" = 0 ]; then run_init; fi
}

main "$@"
