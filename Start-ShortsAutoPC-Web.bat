@echo off
setlocal
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo Node.js is required. Install Node.js LTS first.
  pause
  exit /b 1
)

if not exist "node_modules" (
  echo Installing dependencies...
  call npm.cmd install
  if errorlevel 1 goto fail
)

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$port=3737; $listener=Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue; if (-not $listener) { Start-Process -FilePath 'node' -ArgumentList 'pc-automation/server.js' -WorkingDirectory (Get-Location) -WindowStyle Hidden }; Start-Sleep -Seconds 2; Start-Process 'http://localhost:3737'"

echo Shorts Auto PC web page opened.
echo You can close this window.
pause
exit /b 0

:fail
echo Failed. Check the error above.
pause
exit /b 1
