@echo off
chcp 65001 >nul
title Biblioteca Offline - gerar o kit de instalacao
cd /d "%~dp0"
if exist "certificados\cacert.pem" set "SSL_CERT_FILE=%~dp0certificados\cacert.pem"
echo.
echo   Gera o KIT (zip pequeno) que reinstala esta biblioteca inteira em outro HD.
echo   Leva uns 20 minutos (le a lista de todos os arquivos). O zip fica na raiz do SSD.
echo.
"..\Programas\Python\python.exe" -I -X utf8 instalador\gerar_kit.py
echo.
pause
