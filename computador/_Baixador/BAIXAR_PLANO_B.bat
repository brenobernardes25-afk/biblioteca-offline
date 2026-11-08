@echo off
title Biblioteca Offline - completando a biblioteca (plano B)
cd /d "%~dp0"
if exist "certificados\cacert.pem" set "SSL_CERT_FILE=%~dp0certificados\cacert.pem"
"..\Programas\Python\python.exe" -I -X utf8 plano_b.py
echo.
pause
