@echo off
setlocal
for %%I in ("%~dp0..") do set "PROJECT=%%~fI"
cd /d "%PROJECT%"
set "JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jpackage.exe" set "JDK=%JAVA_HOME%"
if not defined JDK for /d %%I in ("%PROJECT%\.tooling\jdk\jdk-*") do if exist "%%I\bin\jpackage.exe" set "JDK=%%I"
if not defined JDK (
  echo Set JAVA_HOME to a JDK 21 or newer.
  exit /b 1
)
if exist "%PROJECT%\.tooling\wix\candle.exe" set "PATH=%PROJECT%\.tooling\wix;%PATH%"
where candle.exe >nul 2>nul
if errorlevel 1 (
  echo WiX Toolset 3.14.1 is required. Put its portable tools in .tooling\wix or on PATH.
  exit /b 1
)
set /p APP_VERSION=<"%PROJECT%\VERSION"
set "PACKAGE_ROOT=%PROJECT%\dist"
if /i "%~1"=="release" set "PACKAGE_ROOT=%PROJECT%\dist\releases\%APP_VERSION%"
if not exist "%PACKAGE_ROOT%\PixelClipboard\PixelClipboard.exe" (
  call "%PROJECT%\scripts\build.cmd" %~1
  if errorlevel 1 exit /b 1
)
"%JDK%\bin\jpackage.exe" --type exe --name PixelClipboard --app-version "%APP_VERSION%" --vendor "atalamcom" --description "Pixel-art desktop checklist by atalamcom" --app-image "%PACKAGE_ROOT%\PixelClipboard" --dest "%PACKAGE_ROOT%" --temp "%PROJECT%\build\installer-%APP_VERSION%-%RANDOM%" --verbose --win-per-user-install --install-dir PixelClipboard --win-menu --win-menu-group "Pixel Clipboard" --win-shortcut --win-shortcut-prompt --win-dir-chooser --win-upgrade-uuid 87b18a7e-b0ac-4c84-af89-d85d297535f7 --win-update-url "https://github.com/atalamcom/pixel-clipboard/releases/latest" --about-url "https://github.com/atalamcom/pixel-clipboard" --copyright "Maintained by atalamcom; coding assistance by ChatGPT"
if errorlevel 1 exit /b 1
move /y "%PACKAGE_ROOT%\PixelClipboard-%APP_VERSION%.exe" "%PACKAGE_ROOT%\PixelClipboard-Setup-%APP_VERSION%.exe" >nul
echo Built %PACKAGE_ROOT%\PixelClipboard-Setup-%APP_VERSION%.exe
