param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path
)

$ErrorActionPreference = "Stop"
Set-Location $ProjectRoot

Write-Host "Building debug APK..."
& "$ProjectRoot\gradlew.bat" ":app:assembleDebug"

$apk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
if (!(Test-Path $apk)) {
    throw "APK was not created: $apk"
}

Write-Host "Debug APK ready:"
Write-Host $apk
