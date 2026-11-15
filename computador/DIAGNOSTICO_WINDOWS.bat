@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
set "PATH=%~dp0Programas\Windows\LlamaCpp;%~dp0Programas\Windows\KiwixServe;%~dp0App\runtime;%PATH%"
set "PY=%~dp0Programas\Windows\Python\python.exe"
if not exist "%PY%" set "PY=%~dp0Programas\Python\python.exe"
"%PY%" -B -X utf8 "App\diagnostico_windows.py"
echo.
pause
