@echo off
setlocal
for %%I in ("%~dp0..") do set "PROJECT=%%~fI"
cd /d "%PROJECT%"
set "JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JDK=%JAVA_HOME%"
if not defined JDK for /d %%I in ("%PROJECT%\.tooling\jdk\jdk-*") do if exist "%%I\bin\javac.exe" set "JDK=%%I"
if not defined JDK (
  echo Java JDK 21 or newer is required to build. Set JAVA_HOME to its folder.
  exit /b 1
)
set "BUILD=%PROJECT%\build"
set /p APP_VERSION=<"%PROJECT%\VERSION"
if not exist "%BUILD%\classes" mkdir "%BUILD%\classes"
if not exist "%BUILD%\input" mkdir "%BUILD%\input"
> "%BUILD%\sources.txt" (for %%I in ("%PROJECT%\src\main\java\pixelclipboard\*.java") do echo src/main/java/pixelclipboard/%%~nxI)
"%JDK%\bin\javac.exe" -source 21 -target 21 -encoding UTF-8 -d "%BUILD%\classes" @"%BUILD%\sources.txt"
if errorlevel 1 exit /b 1
xcopy /e /i /y "%PROJECT%\src\main\resources\*" "%BUILD%\classes\" >nul
copy /y "%PROJECT%\VERSION" "%BUILD%\classes\version.txt" >nul
"%JDK%\bin\jar.exe" --create --file "%BUILD%\input\pixelclipboard.jar" --main-class pixelclipboard.PixelClipboard -C "%BUILD%\classes" .
if errorlevel 1 exit /b 1
if /i "%~1"=="jar" (
  echo Built %BUILD%\input\pixelclipboard.jar
  exit /b 0
)
rem Package folders are fixed children of this resolved project folder.
set "PACKAGE_ROOT=%PROJECT%\dist"
if /i "%~1"=="release" set "PACKAGE_ROOT=%PROJECT%\dist\releases\%APP_VERSION%"
set "PACKAGE=%PACKAGE_ROOT%\PixelClipboard"
if exist "%PACKAGE%" (
  rmdir /s /q "%PACKAGE%"
  if exist "%PACKAGE%" (
    echo Close Pixel Clipboard before rebuilding the Windows app.
    exit /b 1
  )
)
"%JDK%\bin\jpackage.exe" --type app-image --name PixelClipboard --app-version "%APP_VERSION%" --vendor "atalamcom" --description "A resizable pixel-art desktop checklist" --dest "%PACKAGE_ROOT%" --input "%BUILD%\input" --main-jar pixelclipboard.jar --main-class pixelclipboard.PixelClipboard --icon "%PROJECT%\src\main\resources\assets\clipboard.ico" --add-modules java.desktop --java-options "-Dfile.encoding=UTF-8" --jlink-options "--strip-debug --no-header-files --no-man-pages --compress=2"
if errorlevel 1 exit /b 1
copy /y "%PROJECT%\docs\USAGE.txt" "%PACKAGE%\README.txt" >nul
copy /y "%PROJECT%\src\main\resources\fonts\OFL.txt" "%PACKAGE%\Silkscreen-OFL.txt" >nul
echo Built %PACKAGE%\PixelClipboard.exe
exit /b 0
