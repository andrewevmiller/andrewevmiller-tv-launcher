<#
.SYNOPSIS
  Turn the launcher's Now playing band on or off.

.DESCRIPTION
  Android only lets an app read what other apps are playing if it is a notification listener, and the
  TV has no settings screen to grant that. This grants it over adb. The launcher never reads a
  notification; the listener exists only to unlock media sessions. Updates keep the grant; an
  uninstall removes it, so run `enable` again after reinstalling.

.EXAMPLE
  ./tools/now-playing.ps1 enable
  ./tools/now-playing.ps1 status
  ./tools/now-playing.ps1 disable -Serial 10.0.0.68:5555
#>
param(
    [Parameter(Mandatory, Position = 0)][ValidateSet('enable', 'disable', 'status')][string]$Action,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$component = 'com.andrewevmiller.tvlauncher/com.andrewevmiller.tvlauncher.home.data.NowPlayingListener'

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { throw "adb not found. Add it to PATH or install the Android SDK platform-tools." }

$target = @()
if ($Serial) { $target = @('-s', $Serial) } elseif ($env:ANDROID_SERIAL) { $target = @('-s', $env:ANDROID_SERIAL) }

switch ($Action) {
    'enable'  { & $adb @target shell cmd notification allow_listener $component; Write-Host "Now playing enabled." }
    'disable' { & $adb @target shell cmd notification disallow_listener $component; Write-Host "Now playing disabled." }
    'status'  {
        $on = (& $adb @target shell settings get secure enabled_notification_listeners) -match [regex]::Escape($component.Split('/')[0])
        Write-Host ("Now playing is " + $(if ($on) { 'enabled' } else { 'not enabled' }))
    }
}
