@echo off
rem Builds BtCarKey and installs it on the connected phone. Options: -Tests, -NoLaunch, -Phone <id>
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0deploy.ps1" %*
exit /b %ERRORLEVEL%
