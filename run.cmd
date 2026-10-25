@echo off
setlocal
set /p APP_VERSION=<"%~dp0VERSION"
if exist "%~dp0dist\releases\%APP_VERSION%\PixelClipboard\PixelClipboard.exe" (
  start "" "%~dp0dist\releases\%APP_VERSION%\PixelClipboard\PixelClipboard.exe"
  exit /b 0
)
if not exist "%~dp0dist\PixelClipboard\PixelClipboard.exe" (
  echo Run build.cmd first to create the Windows app.
  pause
  exit /b 1
)
start "" "%~dp0dist\PixelClipboard\PixelClipboard.exe"
