<#
.SYNOPSIS
  Builds the BtCarKey ESP32 simulator, flashes it without touching the board's buttons, and shows
  the first seconds of its serial log.

.DESCRIPTION
  Methods:
    serial   update through the simulator already running on the board (console "ota" command).
             Works on any board, auto-reset circuit or not, but needs the simulator installed once.
    esptool  classic USB flashing: esptool pulls the chip into the ROM bootloader with DTR/RTS
             (--before default_reset) and reboots it afterwards (--after hard_reset).
             Needs the usual auto-reset circuit on the board.
    auto     (default) serial if the simulator answers on the port, otherwise esptool.

.EXAMPLE
  .\flash.ps1
  .\flash.ps1 -Port COM7 -Method esptool -MonitorSeconds 15
#>
param(
    [string]$Port,
    [ValidateSet('auto', 'serial', 'esptool')][string]$Method = 'auto',
    [int]$MonitorSeconds = 8,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$firmware = Join-Path $here '.pio\build\esp32dev\firmware.bin'
$helper = Join-Path $here 'sim_serial.py'

function Find-Tool([string[]]$candidates) {
    foreach ($c in $candidates) {
        if (-not $c) { continue }
        $cmd = Get-Command $c -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
        if (Test-Path $c) { return $c }
    }
    return $null
}

$penv = Join-Path $env:USERPROFILE '.platformio\penv\Scripts'
$pio = Find-Tool @((Join-Path $penv 'pio.exe'), 'pio', 'platformio')
# PlatformIO's own Python ships pyserial
$python = Find-Tool @((Join-Path $penv 'python.exe'), 'python', 'py')
if (-not $pio) { throw 'PlatformIO not found: install it with "python -m pip install --user platformio"' }
if (-not $python) { throw 'Python not found' }

Push-Location $here
try {
    if (-not $SkipBuild) {
        Write-Host '== Building' -ForegroundColor Cyan
        & $pio run
        if ($LASTEXITCODE -ne 0) { throw "build failed ($LASTEXITCODE)" }
    }
    if (-not (Test-Path $firmware)) { throw "no firmware at $firmware" }

    if (-not $Port) {
        $Port = (& $python $helper detect).Trim()
        if ($LASTEXITCODE -ne 0 -or -not $Port) { throw 'no ESP32 serial port found; pass -Port COMx' }
    }
    Write-Host "== Port $Port" -ForegroundColor Cyan

    if ($Method -eq 'auto') {
        & $python $helper probe --port $Port
        $Method = if ($LASTEXITCODE -eq 0) { 'serial' } else { 'esptool' }
        Write-Host "== Method: $Method" -ForegroundColor Cyan
    }

    if ($Method -eq 'serial') {
        Write-Host '== Uploading through the running simulator (serial OTA)' -ForegroundColor Cyan
        & $python $helper ota --port $Port --firmware $firmware
        if ($LASTEXITCODE -ne 0) { throw 'serial OTA failed' }
    } else {
        Write-Host '== Uploading with esptool (auto-reset into the ROM bootloader)' -ForegroundColor Cyan
        & $pio run -t upload --upload-port $Port
        if ($LASTEXITCODE -ne 0) {
            throw ('esptool could not flash. "Wrong boot mode" means the board cannot be put in download ' +
                   'mode through DTR/RTS: hold BOOT while it connects once, or install the simulator once by ' +
                   'other means; later updates can then use -Method serial.')
        }
    }

    Write-Host "== Serial log ($MonitorSeconds s)" -ForegroundColor Cyan
    & $python $helper monitor --port $Port --seconds $MonitorSeconds --send version
} finally {
    Pop-Location
}
