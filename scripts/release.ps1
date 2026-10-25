<#
.SYNOPSIS
    Runs the unit tests and builds the signed Android App Bundle to upload to Google Play.

.DESCRIPTION
    The upload key comes from %USERPROFILE%\.gradle\gradle.properties, outside the repository:

        BTCARKEY_STORE_FILE=C:/Users/<you>/.keys/btcarkey-upload.jks
        BTCARKEY_STORE_PASSWORD=...
        BTCARKEY_KEY_ALIAS=upload
        BTCARKEY_KEY_PASSWORD=...

    Raise versionCode (and versionName) in app\build.gradle.kts before every upload: Play refuses a
    versionCode it has already seen.

.EXAMPLE
    scripts\release.cmd
#>

. (Join-Path $PSScriptRoot 'phone.ps1')
$bundle = Join-Path $root 'app\build\outputs\bundle\release\app-release.aab'
$userProperties = Join-Path $env:USERPROFILE '.gradle\gradle.properties'

$keys = 'BTCARKEY_STORE_FILE', 'BTCARKEY_STORE_PASSWORD', 'BTCARKEY_KEY_ALIAS', 'BTCARKEY_KEY_PASSWORD'
$defined = if (Test-Path $userProperties) { @(Get-Content $userProperties | ForEach-Object { ($_ -split '=', 2)[0].Trim() }) } else { @() }
$missing = @($keys | Where-Object { $defined -notcontains $_ })
if ($missing) { Fail "Upload key not configured, missing in ${userProperties}: $($missing -join ', ')" }

Use-Java
# AGP only creates unit tests for the debug variant: they cover the same code
Step 'Building: testDebugUnitTest, bundleRelease'
Push-Location $root
& .\gradlew.bat --console=plain testDebugUnitTest bundleRelease
$buildExit = $LASTEXITCODE
Pop-Location
if ($buildExit -ne 0) { Fail 'Build failed, see the errors above' }

$version = Select-String -Path (Join-Path $root 'app\build.gradle.kts') -Pattern 'version(Code|Name) = (.+)$' |
    ForEach-Object { $_.Matches[0].Groups[2].Value.Trim('"') }
Write-Host "Done: $bundle (versionCode $($version[0]), versionName $($version[1]))" -ForegroundColor Green
Write-Host 'Upload it in Play Console: Test and release > Internal testing > Create new release.'
