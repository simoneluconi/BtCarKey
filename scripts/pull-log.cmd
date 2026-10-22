@echo off
rem Copies the app's log from the phone into logs\. Options: -Lines <n>, -Phone <id>
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0pull-log.ps1" %*
exit /b %ERRORLEVEL%
