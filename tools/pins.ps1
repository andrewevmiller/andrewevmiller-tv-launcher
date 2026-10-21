<#
.SYNOPSIS
  Save and restore the launcher's pins and their order, so an uninstall or a signing-key change does not lose them.

.DESCRIPTION
  Pins and order live in the app's private saved data (shared_prefs/layout.xml), which Android deletes on
  uninstall. Normal updates (./gradlew installDebug) keep it, so this is only needed around an uninstall.
  Needs a debug build (run-as) and adb on the PATH or in the Android SDK.

.EXAMPLE
  ./tools/pins.ps1 save                     # before uninstalling
  ./tools/pins.ps1 restore                  # after installing again
  ./tools/pins.ps1 save -Serial 10.0.0.68:5555 -File D:\pins.xml
  ./tools/pins.ps1 save -IncludeHistory     # also keep Recent and the featured band
#>
param(
    [Parameter(Mandatory, Position = 0)][ValidateSet('save', 'restore')][string]$Action,
    [string]$Serial,
    [string]$File = (Join-Path $env:USERPROFILE '.field-launcher\pins.xml'),
    [switch]$IncludeHistory
)

$ErrorActionPreference = 'Stop'
$pkg = 'com.andrewevmiller.tvlauncher'

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { throw "adb not found. Add it to PATH or install the Android SDK platform-tools." }

function Adb { & $adb @script:target @args }
$script:target = @()
if ($Serial) { $script:target = @('-s', $Serial) }
elseif ($env:ANDROID_SERIAL) { $script:target = @('-s', $env:ANDROID_SERIAL) }

$files = @('layout.xml')
if ($IncludeHistory) { $files += 'launch_history.xml' }

function Dest($name) {
    # layout.xml keeps the given -File name; extra files sit beside it.
    if ($name -eq 'layout.xml') { return $File }
    return Join-Path (Split-Path $File) $name
}

if ($Action -eq 'save') {
    New-Item -ItemType Directory -Force (Split-Path $File) | Out-Null
    $saved = 0
    foreach ($name in $files) {
        $text = (Adb shell "run-as $pkg cat shared_prefs/$name" 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0 -or $text -match 'No such file|not debuggable|Unknown package|error:') {
            if ($name -eq 'layout.xml') {
                Write-Host "No saved pins to back up ($($text.Trim()))."
            }
            continue
        }
        [IO.File]::WriteAllText((Dest $name), $text)
        Write-Host "Saved $name -> $(Dest $name)"
        $saved++
    }
    if ($saved -eq 0) { exit 1 }
}
else {
    $restored = 0
    foreach ($name in $files) {
        $src = Dest $name
        if (-not (Test-Path $src)) {
            if ($name -eq 'layout.xml') { throw "No backup found at $src. Run './tools/pins.ps1 save' first." }
            continue
        }
        # The app must not be running: it would write its own copy back over ours on exit.
        Adb shell am force-stop $pkg | Out-Null
        Adb push $src "/data/local/tmp/$name" | Out-Null
        Adb shell "run-as $pkg mkdir -p shared_prefs" | Out-Null
        Adb shell "run-as $pkg cp /data/local/tmp/$name shared_prefs/$name" | Out-Null
        Adb shell "rm -f /data/local/tmp/$name" | Out-Null
        Write-Host "Restored $name from $src"
        $restored++
    }
    if ($restored -gt 0) {
        Adb shell monkey -p $pkg -c android.intent.category.LAUNCHER 1 *> $null
        Write-Host "Launcher restarted."
    }
}
