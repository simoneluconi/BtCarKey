@echo off
rem Wrapper so flash.ps1 runs from cmd.exe or a double click; arguments are passed through.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0flash.ps1" %*
