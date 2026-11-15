# Biblioteca Offline no macOS

Este é um fork não oficial do projeto de Breno Paiva, com código multiplataforma.

## Abrir uma instalação existente

No Terminal (⌘ + Espaço → Terminal):

```sh
bash "/Volumes/NOME_DO_DISCO/ABRIR_MAC.sh"
```

Use o endereço mostrado no Terminal e mantenha a janela aberta. Encerre com Ctrl+C antes de ejetar o disco.

## Instalar

Requer macOS, Python 3.11+ e dependências locais. Em um Mac com Homebrew:

```sh
brew install python@3.12 llama.cpp tesseract tesseract-lang poppler
```

Dentro de `computador`, execute:

```sh
bash INSTALAR_MAC.sh --destino "/Volumes/NOME_DO_DISCO/BibliotecaOffline" --escolher
```

O menu pergunta o que **não** baixar. Para somente o essencial, use `--essencial`.
O instalador baixa os programas Mac oficiais e reaproveita conteúdo já existente.
Nunca formata o disco. Usa o destino escolhido; Homebrew e suas dependências ficam no Mac.

Para apenas verificar uma instalação existente, sem baixar conteúdo:

```sh
bash INSTALAR_MAC.sh --verificar --destino "/Volumes/NOME_DO_DISCO/BibliotecaOffline"
```

Em instalação personalizada já existente, o lançador não faz `git pull` nem substitui automaticamente todo o código.
Para atualizar o app, faça backup e copie apenas as alterações de código revisadas, preservando dados e programas.

## Mac e Windows no mesmo disco

Compartilhe documentos, mapas, ZIMs e modelos GGUF; mantenha os programas nativos separados.
O Windows usa `ABRIR_WINDOWS.bat` (também chamado por `ABRIR_BIBLIOTECA.bat`).
Veja [as limitações e os testes](../docs/ADAPTACOES.md).
