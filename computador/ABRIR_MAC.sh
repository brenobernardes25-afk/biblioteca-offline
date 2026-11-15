#!/bin/bash
# Biblioteca Offline — abrir no macOS
# Equivalente ao ABRIR_BIBLIOTECA.bat do Windows.
# Uso: ./ABRIR_MAC.sh   (dentro da pasta da biblioteca, no SSD)
# O app abre em http://127.0.0.1:8800/ — para sair, feche esta janela (Ctrl+C).
set -e
cd "$(dirname "$0")"
if [ ! -f "App/main.py" ]; then
  echo "ERRO: não achei App/main.py aqui. Rode este script dentro da pasta"
  echo "da biblioteca (a que tem App/ e _Baixador/)."
  exit 1
fi
# Homebrew no PATH (Apple Silicon: /opt/homebrew; Intel: /usr/local)
if [ -x /opt/homebrew/bin/brew ]; then eval "$(/opt/homebrew/bin/brew shellenv)" 2>/dev/null || export PATH="/opt/homebrew/bin:$PATH"; fi
if [ -x /usr/local/bin/brew ]; then export PATH="/usr/local/bin:$PATH"; fi
export PATH="/opt/homebrew/opt/python@3.12/libexec/bin:/usr/local/opt/python@3.12/libexec/bin:$PATH"
export SSL_CERT_FILE="${SSL_CERT_FILE:-$PWD/_Baixador/certificados/cacert.pem}"
[ -f "$SSL_CERT_FILE" ] || unset SSL_CERT_FILE
export DYLD_LIBRARY_PATH="$PWD/Programas/LlamaCpp${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"
export TMPDIR="$PWD/.temporarios"
export PYTHONDONTWRITEBYTECODE=1
mkdir -p "$TMPDIR"
exec python3 -X utf8 App/main.py "$@"
