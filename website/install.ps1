# Installs, upgrades or updates Alexandrite for the current user on Windows:
#   irm https://alexandrite.unitedsoftware.org/install.ps1 | iex
#   & ([scriptblock]::Create((irm https://alexandrite.unitedsoftware.org/install.ps1))) -Version 0.2.0 -NoInit
param(
    [string]$Version = '',
    [switch]$NoInit,
    [switch]$Update,
    [switch]$DownloadJre,
    [string]$Config = '',
    [string]$DataDir = '',
    [switch]$Help
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12

$Releases = if ($env:ALEXANDRITE_INSTALL_BASE) { $env:ALEXANDRITE_INSTALL_BASE } else { 'https://github.com/FoedusProgramme/alexandrite/releases' }
$Adoptium = if ($env:ALEXANDRITE_ADOPTIUM_BASE) { $env:ALEXANDRITE_ADOPTIUM_BASE } else { 'https://api.adoptium.net' }
$Root = if ($env:ALEXANDRITE_INSTALL_DIR) { $env:ALEXANDRITE_INSTALL_DIR } else { Join-Path $env:LOCALAPPDATA 'Programs\Alexandrite' }
$ReadyTimeout = if ($env:ALEXANDRITE_READY_TIMEOUT) { [int]$env:ALEXANDRITE_READY_TIMEOUT } else { 90 }
$TaskName = 'Alexandrite'
$JavaMajor = 21
# The first release whose `alexandrite config check` exists.
$ConfigCheckSince = '0.1.0'
$ExUsage = 64
$Chinese = [Globalization.CultureInfo]::CurrentUICulture.Name -like 'zh*'

function Text([string]$English, [string]$Zh) {
    if ($Chinese) { $Zh } else { $English }
}

function Say([string]$English, [string]$Zh) {
    Write-Host (Text $English $Zh)
}

function Warn([string]$English, [string]$Zh) {
    [Console]::Error.WriteLine((Text $English $Zh))
}

function Fail([string]$English, [string]$Zh) {
    throw (Text $English $Zh)
}

function Show-Usage {
    Say @"
Usage: install.ps1 [-Version <v>] [-NoInit] [-Update] [-DownloadJre] [-Config <file>] [-DataDir <dir>]

Installs Alexandrite into $Root and adds its bin directory to your PATH.
Running it again upgrades in place; two versions are kept.

  -Version <v>     install release <v> instead of the latest
  -NoInit          do not run 'alexandrite init' after a first install
  -Update          update an existing installation (what 'alexandrite update' runs)
  -DownloadJre     use a Java runtime of Alexandrite's own even when the system has Java $JavaMajor+
  -Config <file>   the config file the new version is checked against
  -DataDir <dir>   the data directory of the instance to wait for

ALEXANDRITE_INSTALL_DIR changes the directory.
ALEXANDRITE_INSTALL_BASE and ALEXANDRITE_ADOPTIUM_BASE replace the download sites (file:// URLs are for testing).
"@ @"
用法：install.ps1 [-Version <版本>] [-NoInit] [-Update] [-DownloadJre] [-Config <文件>] [-DataDir <目录>]

把 Alexandrite 安装到 $Root，并把其 bin 目录加入 PATH。
再次运行即原地升级；保留两个版本。

  -Version <版本>   安装指定版本，而不是最新版本
  -NoInit           首次安装后不运行 'alexandrite init'
  -Update           更新已有的安装（'alexandrite update' 运行的就是它）
  -DownloadJre      即使系统已有 Java $JavaMajor+，也使用 Alexandrite 自带的 Java 运行时
  -Config <文件>    检查新版本时使用的配置文件
  -DataDir <目录>   要等待其就绪的实例的数据目录

ALEXANDRITE_INSTALL_DIR 可改变安装目录。
ALEXANDRITE_INSTALL_BASE 和 ALEXANDRITE_ADOPTIUM_BASE 可替换下载地址（file:// 地址用于测试）。
"@
}

function Get-Url([string]$Url, [string]$Destination) {
    if ($Url.StartsWith('file://')) {
        Copy-Item -LiteralPath ([Uri]$Url).LocalPath -Destination $Destination
    } elseif ($Url.StartsWith('https://')) {
        Invoke-WebRequest -Uri $Url -OutFile $Destination -UseBasicParsing
    } else {
        Fail "Refusing to download ${Url}: only https:// and file:// URLs are used." "拒绝下载 ${Url}：只使用 https:// 和 file:// 地址。"
    }
}

# The location $Url redirects to, $Url itself for a file:// URL.
function Get-RedirectTarget([string]$Url) {
    if ($Url.StartsWith('file://')) { return $Url }
    $request = [Net.HttpWebRequest]::Create($Url)
    $request.Method = 'HEAD'
    $request.AllowAutoRedirect = $false
    try {
        $response = $request.GetResponse()
    } catch [Net.WebException] {
        $response = $_.Exception.Response
        if (-not $response) { throw }
    }
    try { $response.Headers['Location'] } finally { $response.Close() }
}

function Assert-Checksum([string]$File, [string]$Expected, [string]$Label) {
    $actual = (Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLowerInvariant()
    $expected = $Expected.Trim().ToLowerInvariant()
    if (-not $expected -or $actual -ne $expected) {
        Fail "The checksum of $Label does not match ($actual, expected $expected); nothing was changed." "$Label 的校验和不符（${actual}，应为 ${expected}）；未做任何更改。"
    }
}

# Whether version $A sorts before $B, a pre-release (`1.0.0-rc1`) before its release.
function Test-VersionBefore([string]$A, [string]$B) {
    $coreA = $A.Split('-')[0].Split('.')
    $coreB = $B.Split('-')[0].Split('.')
    for ($i = 0; $i -lt [Math]::Max($coreA.Length, $coreB.Length); $i++) {
        $x = if ($i -lt $coreA.Length) { [int]($coreA[$i] -replace '\D.*$', '') } else { 0 }
        $y = if ($i -lt $coreB.Length) { [int]($coreB[$i] -replace '\D.*$', '') } else { 0 }
        if ($x -lt $y) { return $true }
        if ($x -gt $y) { return $false }
    }
    return $A.Contains('-') -and -not $B.Contains('-')
}

# Runs $Binary with $Arguments, then the locations given to this script when $Locations is set.
function Invoke-App([string]$Binary, [string[]]$Arguments, [switch]$Locations) {
    $all = @($Arguments)
    if ($Locations -and $Config) { $all += @('--config', $Config) }
    if ($Locations -and $DataDir) { $all += @('--data-dir', $DataDir) }
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $Binary @all 2>&1 | ForEach-Object { "$_" }
        $code = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $saved
    }
    [pscustomobject]@{ Code = $code; Output = ($output -join [Environment]::NewLine) }
}

function Test-MissingCommand($Result) {
    $Result.Code -eq $ExUsage -and $Result.Output -match 'Unknown command'
}

function Get-JavaMajor([string]$Java) {
    if (-not (Test-Path -LiteralPath $Java -PathType Leaf)) { return 0 }
    $result = Invoke-App $Java @('-version')
    if ($result.Output -match 'version "([^"]*)"') {
        $v = $Matches[1]
        if ($v.StartsWith('1.')) { $v = $v.Substring(2) }
        if ($v -match '^(\d+)') { return [int]$Matches[1] }
    }
    return 0
}

# Downloads a Java runtime into jre when neither JAVA_HOME nor PATH offers Java 21+; the launcher prefers jre.
function Initialize-Java {
    $jre = Join-Path $Root 'jre'
    if (-not $DownloadJre) {
        if (Test-Path -LiteralPath (Join-Path $jre 'bin\java.exe')) {
            if ((Get-JavaMajor (Join-Path $jre 'bin\java.exe')) -ge $JavaMajor) {
                $env:JAVA_HOME = $jre
                return
            }
        } elseif ($env:JAVA_HOME) {
            if ((Get-JavaMajor (Join-Path $env:JAVA_HOME 'bin\java.exe')) -ge $JavaMajor) { return }
        } else {
            $java = Get-Command java.exe -ErrorAction SilentlyContinue
            if ($java -and (Get-JavaMajor $java.Source) -ge $JavaMajor) { return }
        }
    }
    $url = "$Adoptium/v3/binary/latest/$JavaMajor/ga/windows/x64/jre/hotspot/normal/eclipse"
    $package = Get-RedirectTarget $url
    if (-not $package) {
        Fail "Adoptium names no Java $JavaMajor runtime for Windows x64." "Adoptium 没有提供适用于 Windows x64 的 Java $JavaMajor 运行时。"
    }
    Say "No Java $JavaMajor+ found; downloading a Java runtime from Adoptium into $Root\jre ..." "未找到 Java $JavaMajor+；正在从 Adoptium 下载 Java 运行时到 $Root\jre ……"
    $archive = Join-Path $Tmp 'jre.zip'
    Get-Url $package $archive
    Get-Url "$package.sha256.txt" (Join-Path $Tmp 'jre.sha256')
    Assert-Checksum $archive ((Get-Content -LiteralPath (Join-Path $Tmp 'jre.sha256') -Raw).Trim().Split(' ')[0]) (Text 'the Java runtime' 'Java 运行时')
    $unpacked = Join-Path $Tmp 'jre'
    Expand-Archive -LiteralPath $archive -DestinationPath $unpacked
    $top = @(Get-ChildItem -LiteralPath $unpacked)
    if ($top.Count -ne 1 -or -not (Test-Path -LiteralPath (Join-Path $top[0].FullName 'bin\java.exe'))) {
        Fail 'The Java runtime archive holds no bin\java.exe.' 'Java 运行时压缩包中没有 bin\java.exe。'
    }
    if ((Get-JavaMajor (Join-Path $top[0].FullName 'bin\java.exe')) -lt $JavaMajor) {
        Fail 'The downloaded Java runtime does not run.' '下载的 Java 运行时无法运行。'
    }
    $old = Join-Path $Root 'jre.old'
    if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old -Recurse -Force }
    if (Test-Path -LiteralPath (Join-Path $Root 'jre')) { Move-Item -LiteralPath (Join-Path $Root 'jre') $old }
    Move-Item -LiteralPath $top[0].FullName (Join-Path $Root 'jre')
    if (Test-Path -LiteralPath $old) { Remove-Item -LiteralPath $old -Recurse -Force }
    $env:JAVA_HOME = $jre
}

function Get-Pointer([string]$Name) {
    $file = Join-Path $Root "$Name.txt"
    if (Test-Path -LiteralPath $file) { (Get-Content -LiteralPath $file -Raw).Trim() } else { '' }
}

function Set-Pointer([string]$Name, [string]$Value) {
    $file = Join-Path $Root "$Name.txt"
    if (-not $Value) {
        if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force }
        return
    }
    $next = "$file.new"
    [IO.File]::WriteAllText($next, $Value)
    Move-Item -LiteralPath $next $file -Force
}

function Switch-To([string]$Current, [string]$Previous) {
    Set-Pointer 'current' $Current
    Set-Pointer 'previous' $Previous
}

# Sets $script:Version, $script:Archive and $script:ArchiveSha from the release's SHA256SUMS.
function Resolve-Release {
    $sums = if ($script:Version) { "$Releases/download/v$script:Version/SHA256SUMS" } else { "$Releases/latest/download/SHA256SUMS" }
    $file = Join-Path $Tmp 'SHA256SUMS'
    try { Get-Url $sums $file } catch {
        Fail "Cannot download ${sums}: no such release?" "无法下载 ${sums}：该版本不存在？"
    }
    $script:Sums = @(Get-Content -LiteralPath $file | ForEach-Object { $_.TrimEnd("`r") })
    $line = $script:Sums | Where-Object { $_ -match '^[0-9a-fA-F]{64} [ *]alexandrite-[^ /]*\.zip$' } | Select-Object -First 1
    if (-not $line) { Fail "$sums lists no alexandrite-<version>.zip." "$sums 中没有 alexandrite-<版本>.zip。" }
    $script:ArchiveSha = $line.Substring(0, 64)
    $script:Archive = $line.Substring(66)
    $found = $script:Archive.Substring('alexandrite-'.Length, $script:Archive.Length - 'alexandrite-'.Length - '.zip'.Length)
    if ($script:Version -and $found -ne $script:Version) {
        Fail "Release $script:Version holds $script:Archive." "版本 $script:Version 中的压缩包是 $script:Archive。"
    }
    if ($found -notmatch '^[0-9A-Za-z][0-9A-Za-z.+_-]*$') { Fail "'$found' is no version." "'$found' 不是版本号。" }
    $script:Version = $found
}

function Get-SumsHash([string]$Name) {
    $line = $script:Sums | Where-Object { $_ -match "^[0-9a-fA-F]{64} [ *]$([regex]::Escape($Name))$" } | Select-Object -First 1
    if ($line) { $line.Substring(0, 64) } else { '' }
}

function Install-Release {
    Say "Downloading Alexandrite $Version ..." "正在下载 Alexandrite $Version ……"
    $archive = Join-Path $Tmp $Archive
    Get-Url "$Releases/download/v$Version/$Archive" $archive
    Assert-Checksum $archive $ArchiveSha $Archive
    $unpacked = Join-Path $Tmp 'release'
    Expand-Archive -LiteralPath $archive -DestinationPath $unpacked
    $top = @(Get-ChildItem -LiteralPath $unpacked)
    if ($top.Count -ne 1 -or -not (Test-Path -LiteralPath (Join-Path $top[0].FullName 'bin\alexandrite.bat'))) {
        Fail "$Archive holds no alexandrite-$Version\bin\alexandrite.bat." "$Archive 中没有 alexandrite-$Version\bin\alexandrite.bat。"
    }
    $target = Join-Path $Root "versions\$Version"
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
    Move-Item -LiteralPath $top[0].FullName $target
    $hash = Get-SumsHash 'install.ps1'
    if ($hash) {
        Get-Url "$Releases/download/v$Version/install.ps1" (Join-Path $Tmp 'install.ps1')
        Assert-Checksum (Join-Path $Tmp 'install.ps1') $hash 'install.ps1'
    } elseif ($PSCommandPath -and (Split-Path -Leaf $PSCommandPath) -eq 'install.ps1') {
        Copy-Item -LiteralPath $PSCommandPath (Join-Path $Tmp 'install.ps1')
    }
}

function Test-Release {
    $new = Join-Path $Root "versions\$Version\bin\alexandrite.bat"
    $result = Invoke-App $new @('--version')
    if ($result.Code -ne 0) {
        Warn "Alexandrite $Version does not start:" "Alexandrite $Version 无法启动："
        Warn $result.Output $result.Output
        return $false
    }
    if (-not $result.Output.Contains("alexandrite $Version")) {
        Warn "Alexandrite $Version names itself '$($result.Output)'." "Alexandrite $Version 报告的版本是 '$($result.Output)'。"
        return $false
    }
    if (-not $Prev) { return $true }
    $check = Invoke-App $new @('config', 'check') -Locations
    if ($check.Code -eq 0) { return $true }
    if (Test-MissingCommand $check) {
        if (Test-VersionBefore $Version $ConfigCheckSince) {
            Say "Alexandrite $Version has no 'config check'; the config is checked when it starts." "Alexandrite $Version 没有 'config check'；配置将在启动时检查。"
            return $true
        }
        Warn "Alexandrite $Version has no 'config check'." "Alexandrite $Version 没有 'config check'。"
        return $false
    }
    $old = Invoke-App (Join-Path $Root "versions\$Prev\bin\alexandrite.bat") @('config', 'check') -Locations
    if ($old.Code -ne 0 -and -not (Test-MissingCommand $old)) {
        Warn "The config fails the check of $Version and of the installed $Prev alike; going on:" "配置在 $Version 和已安装的 $Prev 上都未通过检查；继续："
        Warn $check.Output $check.Output
        return $true
    }
    Warn "The config fails the check of Alexandrite ${Version}:" "配置未通过 Alexandrite $Version 的检查："
    Warn $check.Output $check.Output
    return $false
}

function Write-Launcher {
    $bin = Join-Path $Root 'bin'
    New-Item -ItemType Directory -Force -Path $bin | Out-Null
    $launcher = @'
@echo off
setlocal
for %%i in ("%~dp0..") do set "ALEXANDRITE_INSTALL_DIR=%%~fi"
set /p ALEXANDRITE_CURRENT=<"%ALEXANDRITE_INSTALL_DIR%\current.txt"
if not defined ALEXANDRITE_JAVA_HOME if exist "%ALEXANDRITE_INSTALL_DIR%\jre\bin\java.exe" set "ALEXANDRITE_JAVA_HOME=%ALEXANDRITE_INSTALL_DIR%\jre"
if defined ALEXANDRITE_JAVA_HOME set "JAVA_HOME=%ALEXANDRITE_JAVA_HOME%"
call "%ALEXANDRITE_INSTALL_DIR%\versions\%ALEXANDRITE_CURRENT%\bin\alexandrite.bat" %*
exit /b %ERRORLEVEL%
'@
    $next = Join-Path $bin 'alexandrite.cmd.new'
    [IO.File]::WriteAllText($next, $launcher.Replace("`r`n", "`n").Replace("`n", "`r`n"))
    Move-Item -LiteralPath $next (Join-Path $bin 'alexandrite.cmd') -Force
}

function Add-ToPath {
    $bin = Join-Path $Root 'bin'
    $key = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Environment')
    try {
        $user = [string]$key.GetValue('Path', '', [Microsoft.Win32.RegistryValueOptions]::DoNotExpandEnvironmentNames)
        $entries = @($user -split ';' | Where-Object { $_ })
        if ($entries -notcontains $bin) {
            $key.SetValue('Path', ((@($entries) + $bin) -join ';'), [Microsoft.Win32.RegistryValueKind]::ExpandString)
        }
    } finally {
        $key.Close()
    }
    if ($entries -notcontains $bin) {
        # Setting a variable through Environment tells running programs that the environment changed.
        [Environment]::SetEnvironmentVariable('ALEXANDRITE_PATH_CHANGED', '1', 'User')
        [Environment]::SetEnvironmentVariable('ALEXANDRITE_PATH_CHANGED', $null, 'User')
        Say "Added $bin to your PATH; open a new terminal to use 'alexandrite'." "已把 $bin 加入 PATH；请打开新的终端以使用 'alexandrite'。"
    }
    if (@($env:Path -split ';') -notcontains $bin) { $env:Path = "$env:Path;$bin" }
}

function Get-RunningTask {
    try {
        $task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    } catch {
        $task = $null
    }
    if ($task -and $task.State -eq 'Running') { $TaskName } else { '' }
}

function Restart-Task {
    Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    for ($i = 0; $i -lt 30 -and (Get-ScheduledTask -TaskName $TaskName).State -eq 'Running'; $i++) { Start-Sleep -Seconds 1 }
    Start-ScheduledTask -TaskName $TaskName
}

# Polls `alexandrite status` (`pairings` for a version without it) until the instance answers.
function Wait-Ready {
    $probe = 'status'
    $launcher = Join-Path $Root 'bin\alexandrite.cmd'
    for ($waited = 0; $waited -lt $ReadyTimeout; ) {
        $result = Invoke-App $launcher @($probe) -Locations
        if ($result.Code -eq 0) { return $true }
        if ($probe -eq 'status' -and (Test-MissingCommand $result)) {
            $probe = 'pairings'
            continue
        }
        Start-Sleep -Seconds 1
        $waited++
    }
    return $false
}

function Undo-Switch([string]$English, [string]$Zh) {
    Warn $English $Zh
    if ($OldPrevious -eq $Version) { $script:OldPrevious = '' }
    Switch-To $Prev $OldPrevious
    if ($Service) {
        Restart-Task
        if (-not (Wait-Ready)) {
            Warn "Alexandrite $Prev did not come back either; see its logs." "Alexandrite $Prev 也未能恢复运行；请查看日志。"
        }
    }
    Remove-Item -LiteralPath (Join-Path $Root "versions\$Version") -Recurse -Force -ErrorAction SilentlyContinue
    Fail "Switched back to Alexandrite $Prev." "已切换回 Alexandrite $Prev。"
}

function Remove-OldVersions {
    $keep = @((Get-Pointer 'current'), (Get-Pointer 'previous'))
    Get-ChildItem -LiteralPath (Join-Path $Root 'versions') -Directory | Where-Object { $keep -notcontains $_.Name } |
        ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force -ErrorAction SilentlyContinue }
}

# Keeps this script for `alexandrite update`, with a byte order mark so Windows PowerShell reads it as UTF-8.
function Save-Installer {
    $bytes = [IO.File]::ReadAllBytes((Join-Path $Tmp 'install.ps1'))
    if (-not ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF)) {
        $bytes = [byte[]](0xEF, 0xBB, 0xBF) + $bytes
    }
    [IO.File]::WriteAllBytes((Join-Path $Root 'install.ps1.new'), $bytes)
    Move-Item -LiteralPath (Join-Path $Root 'install.ps1.new') (Join-Path $Root 'install.ps1') -Force
}

function Invoke-Install {
    if ($env:OS -ne 'Windows_NT') {
        Fail 'install.ps1 installs on Windows; on Linux and macOS run install.sh.' 'install.ps1 只能在 Windows 上安装；在 Linux 和 macOS 上请运行 install.sh。'
    }
    $script:Version = $Version.TrimStart('v')
    $pinned = [bool]$script:Version
    New-Item -ItemType Directory -Force -Path (Join-Path $Root 'versions') | Out-Null
    $lock = Join-Path $Root '.lock'
    try {
        New-Item -ItemType Directory -Path $lock | Out-Null
    } catch {
        Fail "Another install runs in $Root (remove $lock if none does)." "$Root 中正在进行另一次安装（如果没有，请删除 ${lock}）。"
    }
    $script:Tmp = Join-Path $Root ".tmp.$PID"
    $initAfter = $false
    $javaHome = $env:JAVA_HOME
    try {
        New-Item -ItemType Directory -Path $Tmp | Out-Null
        $script:Prev = Get-Pointer 'current'
        $script:OldPrevious = Get-Pointer 'previous'
        if ($Update -and -not $Prev) {
            Fail "Alexandrite is not installed in $Root; run install.ps1 without -Update." "$Root 中没有安装 Alexandrite；请不带 -Update 运行 install.ps1。"
        }
        Resolve-Release
        if ($Version -eq $Prev -and (Test-Path -LiteralPath (Join-Path $Root "versions\$Prev\bin\alexandrite.bat"))) {
            Initialize-Java
            Write-Launcher
            Add-ToPath
            Say "Alexandrite $Version is installed already." "Alexandrite $Version 已经安装。"
            return
        }
        if ($Update -and -not $pinned -and (Test-VersionBefore $Version $Prev)) {
            Say "Alexandrite $Prev is newer than the latest release $Version; nothing to do." "Alexandrite $Prev 比最新发布的 $Version 更新；无需操作。"
            return
        }
        Initialize-Java
        Install-Release
        if (-not (Test-Release)) {
            Remove-Item -LiteralPath (Join-Path $Root "versions\$Version") -Recurse -Force -ErrorAction SilentlyContinue
            if ($Prev) { Fail "Alexandrite $Version was not installed; $Prev stays." "未安装 Alexandrite ${Version}；继续使用 ${Prev}。" }
            Fail "Alexandrite $Version was not installed." "未安装 Alexandrite ${Version}。"
        }
        Write-Launcher
        $script:Service = if ($Prev) { Get-RunningTask } else { '' }
        Switch-To $Version $Prev
        if ($Prev) {
            if ($Service) {
                Say "Restarting the background service with Alexandrite $Version ..." "正在用 Alexandrite $Version 重启后台服务……"
                try { Restart-Task } catch {
                    Undo-Switch "The background service did not restart with Alexandrite ${Version}: $_" "后台服务未能用 Alexandrite $Version 重启：$_"
                }
                if (-not (Wait-Ready)) {
                    Undo-Switch "Alexandrite $Version did not answer within $ReadyTimeout seconds." "Alexandrite $Version 在 $ReadyTimeout 秒内没有响应。"
                }
            } else {
                Say "No background service runs; the next start runs Alexandrite $Version." "没有正在运行的后台服务；下次启动时将运行 Alexandrite ${Version}。"
            }
            Remove-OldVersions
        }
        if (Test-Path -LiteralPath (Join-Path $Tmp 'install.ps1')) { Save-Installer }
        Add-ToPath
        if ($Prev) {
            Say "Alexandrite $Version is installed (was $Prev)." "已安装 Alexandrite ${Version}（原为 ${Prev}）。"
        } else {
            Say "Alexandrite $Version is installed: $Root\bin\alexandrite.cmd" "已安装 Alexandrite ${Version}：$Root\bin\alexandrite.cmd"
        }
        $initAfter = -not $Prev -and -not $NoInit -and -not $Update
    } finally {
        $env:JAVA_HOME = $javaHome
        Remove-Item -LiteralPath $Tmp -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $lock -Force -ErrorAction SilentlyContinue
    }
    if ($initAfter) { & (Join-Path $Root 'bin\alexandrite.cmd') init }
}

if ($Help) {
    Show-Usage
    return
}
try {
    Invoke-Install
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    if ($PSCommandPath) { exit 1 }
}
