@echo off
rem Runs the unit tests and builds the signed bundle for Google Play (app-release.aab)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0release.ps1" %*
exit /b %ERRORLEVEL%
