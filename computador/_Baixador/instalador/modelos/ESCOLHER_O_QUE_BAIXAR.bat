@echo off
chcp 65001 >nul
title Biblioteca Offline - escolher o que baixar
cd /d "%~dp0"
rem Mostra de novo o menu de categorias e continua a instalação com a nova escolha.
call "%~dp0INSTALAR_BIBLIOTECA.bat" --escolher
