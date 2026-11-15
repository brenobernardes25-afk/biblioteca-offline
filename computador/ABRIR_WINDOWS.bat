@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
set "PY=%~dp0Programas\Windows\Python\python.exe"
if not exist "%PY%" set "PY=%~dp0Programas\Python\python.exe"
if not exist "%PY%" (
  echo Nao encontrei o Python portatil do Windows neste pendrive.
  pause
  exit /b 1
)
set "PATH=%~dp0Programas\Windows\LlamaCpp;%~dp0Programas\Windows\KiwixServe;%~dp0App\runtime;%PATH%"
set "TEMP=%~dp0.temporarios\Windows"
set "TMP=%TEMP%"
if not exist "%TEMP%" mkdir "%TEMP%"
if exist "%~dp0_Baixador\certificados\cacert.pem" set "SSL_CERT_FILE=%~dp0_Baixador\certificados\cacert.pem"
set "PYTHONDONTWRITEBYTECODE=1"
"%PY%" -B -X utf8 "App\main.py" %*
set "RESULTADO=%ERRORLEVEL%"
if not "%RESULTADO%"=="0" (
  echo.
  echo Nao foi possivel abrir. Consulte App\dados\log.txt ou execute DIAGNOSTICO_WINDOWS.bat.
  pause
)
exit /b %RESULTADO%
