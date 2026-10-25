@echo off
call "%~dp0scripts\package-installer.cmd" %*
exit /b %errorlevel%
