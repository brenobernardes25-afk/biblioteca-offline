@echo off
chcp 65001 >nul
title Biblioteca Offline - conferir a biblioteca
cd /d "%~dp0"
rem Confere o que ja foi baixado (inclusive a soma de verificacao de cada enciclopedia) e oferece
rem apagar o que estiver estragado, para o INSTALAR_BIBLIOTECA.bat baixar de novo.
rem Conferencia rapida, sem a soma das enciclopedias:  CONFERIR_BIBLIOTECA.bat --rapido
call "%~dp0INSTALAR_BIBLIOTECA.bat" --auditar %*
