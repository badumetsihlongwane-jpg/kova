@echo off
title Kova Workstation Host (CLI)
cd /d "%~dp0"
if exist "%~dp0KovaHost.exe" (
    "%~dp0KovaHost.exe" --cli %*
) else if exist "%~dp0..\.venv\Scripts\python.exe" (
    "%~dp0..\.venv\Scripts\python.exe" -m kova_host.main --cli %*
) else (
    python -m kova_host.main --cli %*
)
echo.
pause
