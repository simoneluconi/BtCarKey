<#
.SYNOPSIS
    Builds BtCarKey (debug), installs it on the phone connected over adb and opens it.

.EXAMPLE
    scripts\deploy.cmd                  build, install, open the app on the phone
    scripts\deploy.cmd -Tests           run the unit tests before installing
    scripts\deploy.cmd -NoLaunch        install without opening the app
    scripts\deploy.cmd -Phone <id>      pick the phone when more than one is connected
#>
param(
    [string]$Phone,
    [switch]$Tests,
    [switch]$NoLaunch
)

. (Join-Path $PSScriptRoot 'phone.ps1')
$apk = Join-Path $root 'app\build\outputs\apk\debug\app-debug.apk'

Use-Java
$adb = Find-Adb
$Phone = Select-Phone $adb $Phone
$model = Get-PhoneModel $adb $Phone

$tasks = @('assembleDebug')
if ($Tests) { $tasks = @('testDebugUnitTest') + $tasks }
Step "Building: $($tasks -join ', ')"
Push-Location $root
& .\gradlew.bat --console=plain @tasks
$buildExit = $LASTEXITCODE
Pop-Location
if ($buildExit -ne 0) { Fail 'Build failed, see the errors above' }

Step "Installing on $model ($Phone)"
$install = (& $adb -s $Phone install -r $apk 2>&1 | ForEach-Object { "$_" }) -join "`n"
Write-Host $install
if ($install -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    Fail "The phone has a BtCarKey signed with another key. Uninstall it first (this deletes its saved keys!):`n    `"$adb`" -s $Phone uninstall $package"
}
if ($install -notmatch 'Success') { Fail 'Install failed, see the message above' }

if (-not $NoLaunch) {
    Step 'Opening the app'
    & $adb -s $Phone shell monkey -p $package -c android.intent.category.LAUNCHER 1 | Out-Null
}
Write-Host 'Done. Live log: adb logcat -s BtCarKey   |   saved log: scripts\pull-log.cmd' -ForegroundColor Green
