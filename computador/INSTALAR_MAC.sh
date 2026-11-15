#!/bin/bash
# Biblioteca Offline — instalador macOS
# Repo original (Windows): https://github.com/brenobernardes25-afk/biblioteca-offline
# Uso:
#   1. Copie este arquivo + ABRIR_MAC.sh para o Mac.
#   2. No Terminal, na pasta onde estão os 2 arquivos:
#        chmod +x INSTALAR_MAC.sh ABRIR_MAC.sh
#        ./INSTALAR_MAC.sh
#   3. Quando perguntar o destino, escolha um SSD externo de 1 TB em exFAT
#      (ou uma pasta no Mac, se for só testar com pouco conteúdo).
#
# O que ele faz, nesta ordem:
#   a) confere python3, curl, espaço e arquitetura (arm64 = Apple Silicon, x86_64 = Intel)
#   b) instala Homebrew (se faltar) + kiwix-tools + llama.cpp + tesseract + poppler
#      — é o caminho mais simples no Mac; os binários também ficam em Programas/
#   c) baixa o código do repo (git -c core.symlinks=false clone --depth 1) para o destino
#   d) reutiliza o código multiplataforma deste fork
#   e) baixa os programas de Mac em 6_Instaladores/ (kiwix-tools .tar.gz e
#      llama.cpp .tar.gz — o manifesto original só lista as versões Windows)
#   f) roda o instalador Python (o mesmo do Windows, já patchado), que baixa
#      o conteúdo: 830 GB tudo, ou só o essencial com --essencial
#
# Opções:
#   ./INSTALAR_MAC.sh --essencial        só saúde/sobrevivência/água/programas/IA (~26 GB)
#   ./INSTALAR_MAC.sh --verificar        só mostra o que falta, não baixa nada
#   ./INSTALAR_MAC.sh --destino /Volumes/MeuSSD
#   ./INSTALAR_MAC.sh --max-mb 50 --limite 30   teste rápido (só arquivos <= 50 MB, 30 por parte)
set -e
cd "$(dirname "$0")"

ESSENCIAL=0
VERIFICAR=0
DESTINO=""
EXTRA_ARGS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --essencial) ESSENCIAL=1; shift;;
    --verificar) VERIFICAR=1; shift;;
    --destino) DESTINO="$2"; shift 2;;
    --max-mb|--limite) EXTRA_ARGS+=("$1" "$2"); shift 2;;
    *) EXTRA_ARGS+=("$1"); shift;;
  esac
done

echo "=== Biblioteca Offline — instalador macOS ==="
echo

# --- arquitetura ---
ARQ="$(uname -m)"
if [ "$ARQ" = "arm64" ]; then KIWIX_ARQ="arm64"; LLAMA_ARQ="arm64"; BREW_PREFIX="/opt/homebrew";
elif [ "$ARQ" = "x86_64" ]; then KIWIX_ARQ="x86_64"; LLAMA_ARQ="x64"; BREW_PREFIX="/usr/local";
else echo "Arquitetura não reconhecida: $ARQ"; exit 1; fi
echo "Mac: $ARQ (kiwix $KIWIX_ARQ, llama $LLAMA_ARQ)"

# --- python3 ---
export PATH="$BREW_PREFIX/opt/python@3.12/libexec/bin:$BREW_PREFIX/bin:$PATH"
if ! command -v python3 >/dev/null; then echo "ERRO: python3 não encontrado. Instale o Xcode Command Line Tools: xcode-select --install"; exit 1; fi
PYV="$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
echo "Python: $PYV"
python3 -c 'import sys; assert sys.version_info >= (3, 11), "precisa de Python 3.11 ou mais novo"' || { echo "ERRO: o app precisa de Python 3.11+. O macOS atual traz 3.9 no /usr/bin/python3 — instale um mais novo com: brew install python@3.12"; exit 1; }

# --- destino ---
if [ -z "$DESTINO" ]; then
  echo
  echo "Onde instalar? Digite o caminho de um SSD externo de 1 TB (exFAT),"
  echo "ou tecle ENTER para instalar numa pasta aqui mesmo (só para testar)."
  echo "Exemplo: /Volumes/Biblioteca"
  read -r -p "Destino [./BibliotecaOffline]: " DESTINO
  DESTINO="${DESTINO:-./BibliotecaOffline}"
fi
case "$DESTINO" in
  /Volumes/*)
    VOLUME="${DESTINO#/Volumes/}"
    VOLUME="/Volumes/${VOLUME%%/*}"
    [ -d "$VOLUME" ] && mount | grep -F " on $VOLUME (" >/dev/null || { echo "ERRO: volume não está montado: $VOLUME"; exit 1; };;
esac
mkdir -p "$DESTINO"
DESTINO="$(cd "$DESTINO" && pwd)"
echo "Destino: $DESTINO"
export TMPDIR="$DESTINO/.temporarios"
export PYTHONDONTWRITEBYTECODE=1
mkdir -p "$TMPDIR"
LIVRE_GB=$(df -g "$DESTINO" | tail -1 | awk '{print $4}')
echo "Espaço livre no destino: ${LIVRE_GB} GB (completo: 830 GB; essencial: ~26 GB)"
if [ "$ESSENCIAL" = "0" ] && [ "$VERIFICAR" = "0" ] && [ "${EXTRA_ARGS[*]}" = "" ] && [ "$LIVRE_GB" -lt 850 ]; then
  echo "AVISO: menos de 850 GB livres. O instalador vai baixar por ordem de"
  echo "importância e pular o que não couber. Para só o essencial, rode:"
  echo "  ./INSTALAR_MAC.sh --essencial --destino \"$DESTINO\""
  read -r -p "Continuar mesmo assim? (s/N) " R; [ "$R" = "s" ] || [ "$R" = "S" ] || exit 0
fi

if [ "$VERIFICAR" = "1" ]; then
  [ -f "$DESTINO/_Baixador/instalador/instalar.py" ] || { echo "ERRO: biblioteca não instalada neste destino."; exit 1; }
  exec python3 "$DESTINO/_Baixador/instalador/instalar.py" --verificar "${EXTRA_ARGS[@]}"
fi

# --- Homebrew + programas ---
if ! command -v brew >/dev/null; then
  echo
  echo "Homebrew não encontrado — instalando (vai pedir sua senha do Mac)..."
  /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
  if [ -x "$BREW_PREFIX/bin/brew" ]; then eval "$("$BREW_PREFIX/bin/brew" shellenv)"; fi
fi
echo
echo "Instalando/atualizando: llama.cpp, tesseract, poppler (Kiwix via pacote oficial)..."
# Reutiliza dependências já instaladas, sem downloads no disco interno.
if ! command -v llama-server >/dev/null || ! command -v tesseract >/dev/null || ! command -v pdftotext >/dev/null; then
  echo "ERRO: faltam dependências locais (llama-server, tesseract ou pdftotext)."; exit 1
fi
command -v kiwix-serve >/dev/null && echo "  kiwix-serve: $(command -v kiwix-serve)"
command -v llama-server >/dev/null && echo "  llama-server: $(command -v llama-server)"
command -v tesseract >/dev/null && echo "  tesseract: $(tesseract --version | head -1)"

# --- código do repo ---
if [ ! -d "$DESTINO/App" ] || [ ! -d "$DESTINO/_Baixador" ]; then
  echo
  echo "Baixando o código do repo (só o código, sem o conteúdo de 830 GB)..."
  if command -v git >/dev/null; then
    git -c core.symlinks=false clone --depth 1 https://github.com/diegohoraadm-cpu/biblioteca-offline.git "$DESTINO/.codigo-temporario" \
      && mv "$DESTINO/.codigo-temporario/computador/"* "$DESTINO/" && rm -rf "$DESTINO/.codigo-temporario"
  else
    cd "$DESTINO" && curl -sL -o repo.zip https://github.com/diegohoraadm-cpu/biblioteca-offline/archive/refs/heads/main.zip \
      && python3 -c "import zipfile; zipfile.ZipFile('repo.zip').extractall('.')" \
      && mv biblioteca-offline-main/computador/* . && rm -rf biblioteca-offline-main repo.zip && cd - >/dev/null
  fi
else
  echo "Código já existe em $DESTINO — reaproveitando (git pull não é feito aqui)."
fi

# --- lançador macOS (o código deste fork já é multiplataforma) ---
[ "$PWD" = "$DESTINO" ] || cp -f "$PWD/ABRIR_MAC.sh" "$DESTINO/ABRIR_MAC.sh"
chmod +x "$DESTINO/ABRIR_MAC.sh"

# --- programas de Mac em 6_Instaladores/ ---
echo
echo "Baixando os programas de Mac (kiwix-tools 3.8.2 + llama.cpp b11889)..."
mkdir -p "$DESTINO/6_Instaladores"
KIWIX_URL="https://download.kiwix.org/release/kiwix-tools/kiwix-tools_macos-${KIWIX_ARQ}-3.8.2.tar.gz"
LLAMA_URL="https://github.com/ggml-org/llama.cpp/releases/download/b11889/llama-b11889-bin-macos-${LLAMA_ARQ}.tar.gz"
KIWIX_ARQ_FILE="$DESTINO/6_Instaladores/kiwix-tools_macos-${KIWIX_ARQ}-3.8.2.tar.gz"
LLAMA_ARQ_FILE="$DESTINO/6_Instaladores/llama-b11889-bin-macos-${LLAMA_ARQ}.tar.gz"
[ -f "$KIWIX_ARQ_FILE" ] || curl -fL --retry 3 -o "$KIWIX_ARQ_FILE" "$KIWIX_URL"
[ -f "$LLAMA_ARQ_FILE" ] || curl -fL --retry 3 -o "$LLAMA_ARQ_FILE" "$LLAMA_URL"
tar -tzf "$KIWIX_ARQ_FILE" >/dev/null
tar -tzf "$LLAMA_ARQ_FILE" >/dev/null
echo "$KIWIX_URL" > "$KIWIX_ARQ_FILE.ok"
echo "$LLAMA_URL" > "$LLAMA_ARQ_FILE.ok"
ls -lh "$DESTINO/6_Instaladores/"*.tar.gz

# Instala os programas antes do conteúdo; materializa links para suportar exFAT.
python3 - "$DESTINO" <<'PY_PROGRAMAS'
import sys
sys.path.insert(0, sys.argv[1] + '/_Baixador/instalador')
import instalar
instalar.instalar_programas()
PY_PROGRAMAS

# --- escolha do que baixar ---
INST_ARGS=()
[ "$VERIFICAR" = "1" ] && INST_ARGS+=(--verificar)
INST_ARGS+=("${EXTRA_ARGS[@]}")
if [ "$ESSENCIAL" = "1" ]; then
  # O menu do instalador pergunta o que NÃO baixar; o essencial sempre vem.
  # Números das categorias opcionais (ver categorias.py): 1=pt 2=pratico_en
  # 3=survivor 4=mapas 5=agricultura 6=exercito 7=academicos 8=wikipedia_en
  # 9=forums 10=literatura 11=religiao 12=ia_extra
  echo "1 2 3 4 5 6 7 8 9 10 11 12" | python3 "$DESTINO/_Baixador/instalador/instalar.py" --escolher "${INST_ARGS[@]}"
else
  python3 "$DESTINO/_Baixador/instalador/instalar.py" "${INST_ARGS[@]}"
fi

echo
echo "=== Fim do instalador ==="
echo "Para abrir:  $DESTINO/ABRIR_MAC.sh"
echo "Na primeira vez o app monta o índice de busca (algumas horas; dá para ir usando)."
echo "O app abre em http://127.0.0.1:8800/ no navegador."
