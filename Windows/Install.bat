@echo off
setlocal
title ChainTest-Katalon Bridge - Install

if "%~1"=="" (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-GUI.ps1"
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install.ps1" -ProjectPath "%~1"
)

echo.
pause
