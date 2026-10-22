<#
.SYNOPSIS
    Adds a box and its device key to BtCarKey on the phone, over adb.

.DESCRIPTION
    The values come from a JSON file (default: device_secrets.json in the repository root, which git
    ignores) with "serial", "deviceKey" and optionally "bleMac"/"mac", "name" and "protocol"; any of them can be
    given or overridden on the command line. The app must be installed; it saves the key encrypted,
    selects the box and connects. Only the adb shell can send this (the receiver needs DUMP).

    The key travels on the adb command line: fine on your own phone and PC, but don't run this on a
    shared machine.

.EXAMPLE
    scripts\push-key.cmd -Name "My car"
    scripts\push-key.cmd -File C:\keys\btcarkey-My-car.json
    scripts\push-key.cmd -Serial AA:BB:CC:DD:EE:FF -Key 00112233445566778899aabbccddeeff -Name "Van"
#>
param(
    [string]$File,
    [string]$Serial,
    [string]$Key,
    [string]$Name,
    [string]$Mac,
    # Protocol id (e.g. ftk); default: the file's "protocol", else the app's default
    [string]$Protocol,
    [string]$Phone
)

. (Join-Path $PSScriptRoot 'phone.ps1')

if (-not $File -and -not ($Serial -and $Key)) { $File = Join-Path $root 'device_secrets.json' }
if ($File) {
    if (-not (Test-Path $File)) { Fail "File not found: $File" }
    $json = Get-Content $File -Raw | ConvertFrom-Json
    if (-not $Serial) { $Serial = $json.serial }
    if (-not $Key) { $Key = $json.deviceKey }
    if (-not $Mac) { $Mac = if ($json.bleMac) { $json.bleMac } else { $json.mac } }
    if (-not $Name) { $Name = $json.name }
    if (-not $Protocol) { $Protocol = $json.protocol }
}
if ($Serial -notmatch '^([0-9A-Fa-f]{2}[:-]?){5}[0-9A-Fa-f]{2}$') { Fail "Invalid serial '$Serial' (expected AA:BB:CC:DD:EE:FF)" }
# The app checks the length for the protocol; here only that it is hex
if ($Key -notmatch '^([0-9A-Fa-f]{2})+$') { Fail 'Invalid key: expected hex digits' }

$adb = Find-Adb
$Phone = Select-Phone $adb $Phone
if (-not (& $adb -s $Phone shell pm list packages $package | Where-Object { $_.Trim() -eq "package:$package" })) {
    Fail "BtCarKey is not installed on the phone: run scripts\deploy.cmd first"
}

# adb shell joins the arguments into one command for the phone's sh: quote the free-text name there
function Quote-Remote($value) { "'" + ($value -replace "'", "'\''") + "'" }

$broadcast = @('-s', $Phone, 'shell', 'am', 'broadcast',
    '-n', "$package/.keys.KeyImportReceiver",
    '-a', "$package.IMPORT_KEY",
    '--es', 'serial', $Serial,
    '--es', 'key', $Key)
if ($Name) { $broadcast += @('--es', 'name', (Quote-Remote $Name)) }
if ($Mac) { $broadcast += @('--es', 'mac', $Mac) }
if ($Protocol) { $broadcast += @('--es', 'protocol', $Protocol) }

Step "Sending the key for $Serial$(if ($Name) { " ($Name)" }) to $(Get-PhoneModel $adb $Phone)"
$out = (& $adb @broadcast 2>&1 | ForEach-Object { "$_" }) -join "`n"
if ($out -match 'result=0') {
    Write-Host ($out -replace '(?s).*data="([^"]*)".*', '$1') -ForegroundColor Green
} else {
    Write-Host $out
    Fail 'The app did not accept the key (is it installed and up to date?)'
}
