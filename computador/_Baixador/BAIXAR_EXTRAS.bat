@echo off
cd /d "%~dp0"
if exist "certificados\cacert.pem" set "SSL_CERT_FILE=%~dp0certificados\cacert.pem"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0baixar_extras.ps1" %*
