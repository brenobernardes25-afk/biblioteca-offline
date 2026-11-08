@echo off
cd /d "%~dp0"
if exist "certificados\cacert.pem" set "SSL_CERT_FILE=%~dp0certificados\cacert.pem"
"..\Programas\Python\python.exe" -I -X utf8 baixar_exercito.py %*
echo.
pause
