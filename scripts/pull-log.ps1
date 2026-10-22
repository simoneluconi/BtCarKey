<#
.SYNOPSIS
    Copies the app's saved log (every connection step and command) from the phone to logs\ here.

.DESCRIPTION
    The app keeps its log in its private storage, so a session at the car can be read later at the PC.
    Works with debug builds (run-as). The files land in logs\ (ignored by git) and the last lines are shown.

.EXAMPLE
    scripts\pull-log.cmd
    scripts\pull-log.cmd -Lines 200
#>
param(
    [string]$Phone,
    [int]$Lines = 60
)

. (Join-Path $PSScriptRoot 'phone.ps1')

$adb = Find-Adb
$Phone = Select-Phone $adb $Phone
$dir = Join-Path $root 'logs'
New-Item -ItemType Directory -Force $dir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$target = Join-Path $dir "events-$stamp.txt"

Step "Reading the log from $(Get-PhoneModel $adb $Phone)"
$parts = @()
foreach ($name in @('events.2.txt', 'events.1.txt', 'events.txt')) {
    $text = & $adb -s $Phone exec-out run-as $package cat "files/log/$name" 2>$null
    if ($LASTEXITCODE -eq 0 -and $text) { $parts += $text }
}
if ($parts.Count -eq 0) { Fail 'No log found (is a debug build installed and has it run?)' }
$parts | Set-Content -Path $target -Encoding utf8
Write-Host "Saved to $target" -ForegroundColor Green
Get-Content $target -Tail $Lines
