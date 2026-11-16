@echo off
chcp 65001 >nul
title Biblioteca Offline - versao leve
cd /d "%~dp0"
rem Instala a versão leve (~100 GB) sem perguntar nada. Para mudar depois: ESCOLHER_O_QUE_BAIXAR.bat
call "%~dp0INSTALAR_BIBLIOTECA.bat" --leve
