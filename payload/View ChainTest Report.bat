@echo off
setlocal enabledelayedexpansion
title ChainTest-Katalon Bridge - View Report
cd /d "%~dp0"

set "LATEST="
for /f "delims=" %%D in ('dir /b /ad /o-d "chaintest-report" 2^>nul') do (
    if not defined LATEST set "LATEST=%%D"
)

if not defined LATEST (
    echo No report found yet under chaintest-report\.
    echo Run a Katalon test suite first - the report is generated automatically when it finishes.
    pause
    exit /b 1
)

set "REPORT_FILE=%cd%\chaintest-report\%LATEST%\Index.html"
echo Opening report: %REPORT_FILE%
start "" "%REPORT_FILE%"
