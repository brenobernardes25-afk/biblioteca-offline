@echo off
chcp 65001 >nul
title Biblioteca de Bolso - preparar para o celular
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0preparar_celular.ps1" %*
