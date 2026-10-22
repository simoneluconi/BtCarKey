@echo off
rem Sends a box's key to BtCarKey on the phone. Options: -File, -Serial, -Key, -Name, -Mac, -Phone
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0push-key.ps1" %*
exit /b %ERRORLEVEL%
