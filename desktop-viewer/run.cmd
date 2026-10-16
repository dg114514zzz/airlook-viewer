@echo off
setlocal
chcp 65001 >nul
title AIR-Look Viewer

set "PY=C:\Users\Administrator\.workbuddy\binaries\python\versions\3.13.12\python.exe"
set "PYTHONIOENCODING=utf-8"
set "PYTHONUTF8=1"

if exist "%PY%" goto run
where python >nul 2>nul
if %errorlevel% neq 0 goto nopython
set "PY=python"
goto run

:nopython
echo.
echo [ERROR] Python 3 not found on this machine.
echo Please install Python 3.8+ from https://www.python.org/downloads/
echo (tick "Add python.exe to PATH" during install), then run this file again.
echo.
pause
exit /b 1

:run
echo.
echo ============================================================
echo   AIR-Look Viewer
echo ============================================================
echo   1. Power on the otoscope (hold the button for 2 seconds)
echo   2. Connect this PC to the otoscope Wi-Fi (no password)
echo      SSID looks like  JesHome-XXXX  or  Enjoy-XXXXXX
echo   3. Wait for the browser hint below, then open the URL
echo ============================================================
echo.
"%PY%" "%~dp0airlook_view.py" %*
echo.
echo Viewer stopped. Press any key to close.
pause >nul
