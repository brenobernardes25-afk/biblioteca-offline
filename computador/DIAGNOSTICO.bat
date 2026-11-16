@echo off
chcp 65001 >nul
title Biblioteca Offline - diagnostico
cd /d "%~dp0"
echo.
echo   DIAGNOSTICO DA INSTALACAO
echo   Ele so confere o HD e anota onde o programa para. Nao baixa nada.
echo   Espere ate aparecer "FIM DO DIAGNOSTICO" (pode levar uns minutos).
echo.
if exist "_Baixador\certificados\cacert.pem" set "SSL_CERT_FILE=%~dp0_Baixador\certificados\cacert.pem"
"Programas\Python\python.exe" -I -X utf8 "_Baixador\instalador\instalar.py" --diagnostico --verificar
echo.
echo ===== ONDE O PROGRAMA PASSOU (diagnostico.txt) =====
if exist "_Baixador\instalador\diagnostico.txt" type "_Baixador\instalador\diagnostico.txt"
echo.
echo ===== FIM DO DIAGNOSTICO: tire uma foto desta janela =====
pause
