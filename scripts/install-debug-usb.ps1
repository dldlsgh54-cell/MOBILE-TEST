param(
    [string]$ProjectRoot = (Resolve-Path "$PSScriptRoot\..").Path
)

$ErrorActionPreference = "Stop"
Set-Location $ProjectRoot

function Find-Adb {
    $candidates = @()
    if ($env:ANDROID_HOME) {
        $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe")
    }
    if ($env:ANDROID_SDK_ROOT) {
        $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe")
    }
    $candidates += "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
    $candidates += "C:\Android\Sdk\platform-tools\adb.exe"

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return $candidate
        }
    }

    $pathAdb = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($pathAdb) {
        return $pathAdb.Source
    }

    throw "adb.exe not found. Install Android Studio SDK Platform Tools or set ANDROID_HOME."
}

Write-Host "Building debug APK..."
& "$ProjectRoot\gradlew.bat" ":app:assembleDebug"

$apk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\app-debug.apk"
if (!(Test-Path $apk)) {
    throw "APK was not created: $apk"
}

$adb = Find-Adb
Write-Host "Using adb: $adb"
& $adb devices

Write-Host "Installing APK to connected device..."
& $adb install -r $apk

Write-Host "Starting app..."
& $adb shell monkey -p com.example.shortsauto 1

Write-Host "Done. Enable Accessibility Service on the phone before starting automation."
