<#
.SYNOPSIS
  Turn the launcher's remote-button remapping on or off.

.DESCRIPTION
  Some remote buttons are taken by the TV's system before any app sees them, and on this TV three of
  them (Settings, Tuner, User) go to a disabled launcher and do nothing. The launcher can only catch
  them as an accessibility service, which Android keeps switched off until you enable it. This does
  that over adb. The service reads nothing from the screen; it only sees key presses and acts on the
  ones listed in RemoteKeys (app/.../remote/RemoteKeyService.kt). `disable` puts every button back
  exactly as it was. An update keeps the setting; an uninstall removes it, so run `enable` again.

  Out of the box: Settings button -> notifications panel (with an Open Settings shortcut),
  Tuner button -> Stremio.

.EXAMPLE
  ./tools/remote.ps1 enable
  ./tools/remote.ps1 status
  ./tools/remote.ps1 disable -Serial 10.0.0.68:5555
#>
param(
    [Parameter(Mandatory, Position = 0)][ValidateSet('enable', 'disable', 'status')][string]$Action,
    [string]$Serial
)

$ErrorActionPreference = 'Stop'
$component = 'com.andrewevmiller.tvlauncher/com.andrewevmiller.tvlauncher.remote.RemoteKeyService'

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { $adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' }
if (-not (Test-Path $adb)) { throw "adb not found. Add it to PATH or install the Android SDK platform-tools." }

$target = @()
if ($Serial) { $target = @('-s', $Serial) } elseif ($env:ANDROID_SERIAL) { $target = @('-s', $env:ANDROID_SERIAL) }

function Get-Services {
    $raw = (& $adb @target shell settings get secure enabled_accessibility_services | Out-String).Trim()
    if ($raw -eq 'null' -or [string]::IsNullOrWhiteSpace($raw)) { return @() }
    return @($raw -split ':' | Where-Object { $_ })
}

function Set-Services([string[]]$list) {
    if ($list.Count -eq 0) {
        & $adb @target shell settings delete secure enabled_accessibility_services | Out-Null
        & $adb @target shell settings put secure accessibility_enabled 0 | Out-Null
    } else {
        & $adb @target shell settings put secure enabled_accessibility_services ($list -join ':') | Out-Null
        & $adb @target shell settings put secure accessibility_enabled 1 | Out-Null
    }
}

$current = Get-Services
switch ($Action) {
    'enable' {
        # Keep anything else that is already enabled.
        Set-Services (@($current | Where-Object { $_ -ne $component }) + $component)
        Write-Host "Remote buttons enabled. Settings -> notifications panel, Tuner -> Stremio."
    }
    'disable' {
        Set-Services @($current | Where-Object { $_ -ne $component })
        Write-Host "Remote buttons restored to the system's defaults."
    }
    'status' {
        Write-Host ("Remote remapping is " + $(if ($current -contains $component) { 'enabled' } else { 'not enabled' }))
    }
}
