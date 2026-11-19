@echo off
chcp 65001 >nul
title Biblioteca Offline - relatorio para pedir ajuda
cd /d "%~dp0"
rem Monta um texto com a situacao da instalacao e ja deixa copiado, para colar na conversa de ajuda.
call "%~dp0INSTALAR_BIBLIOTECA.bat" --relatorio
