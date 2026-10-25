@echo off
setlocal
for %%I in ("%~dp0..") do set "PROJECT=%%~fI"
cd /d "%PROJECT%"
call "%PROJECT%\scripts\build.cmd" jar
if errorlevel 1 exit /b 1
set "JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JDK=%JAVA_HOME%"
if not defined JDK for /d %%I in ("%PROJECT%\.tooling\jdk\jdk-*") do if exist "%%I\bin\javac.exe" set "JDK=%%I"
set "BUILD=%PROJECT%\build"
if not exist "%BUILD%\test-classes" mkdir "%BUILD%\test-classes"
"%JDK%\bin\javac.exe" -encoding UTF-8 -d "%BUILD%\test-classes" @"%BUILD%\sources.txt" src/test/java/pixelclipboard/AppSelfTest.java src/test/java/pixelclipboard/AppWindowTest.java
if errorlevel 1 exit /b 1
"%JDK%\bin\java.exe" -Djava.awt.headless=true -cp "%BUILD%\input\pixelclipboard.jar;%BUILD%\test-classes" pixelclipboard.AppSelfTest "%BUILD%\test-output"
if errorlevel 1 exit /b 1
if /i "%~1"=="headless" exit /b 0
set "APPDATA=%BUILD%\test-output\window-appdata"
"%JDK%\bin\java.exe" -cp "%BUILD%\input\pixelclipboard.jar;%BUILD%\test-classes" pixelclipboard.AppWindowTest
exit /b %errorlevel%
