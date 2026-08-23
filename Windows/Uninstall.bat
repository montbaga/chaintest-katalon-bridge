@echo off
setlocal
title ChainTest-Katalon Bridge - Uninstall

if "%~1"=="" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Uninstall-GUI.ps1"
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall.ps1" -ProjectPath "%~1"
)

echo.
pause
