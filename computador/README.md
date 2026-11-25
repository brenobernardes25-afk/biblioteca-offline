# Kit do computador: o código

Esta pasta tem o mesmo conteúdo do `Kit_Biblioteca_Offline_<data>.zip`, **sem o conteúdo baixado** e sem os
binários do Python portátil e do Visual C++, que vão só no zip.

**Para instalar a biblioteca, não use esta pasta:** baixe o kit pronto e siga o
[guia do computador](../guias/5_COMPUTADOR_WINDOWS.md).

## O que tem aqui

| Caminho | O que é |
|---|---|
| `INSTALAR_BIBLIOTECA.bat` | Inicia o instalador (`_Baixador/instalador/instalar.py`) |
| `ESCOLHER_O_QUE_BAIXAR.bat` | Abre de novo o menu de categorias |
| `CONFERIR_BIBLIOTECA.bat` | Confere o que já foi baixado (inclusive o MD5 de cada ZIM) e oferece baixar de novo o que estiver estragado |
| `COPIAR_RELATORIO.bat` | Monta um texto com a situação da instalação (sem dados pessoais) e deixa copiado, para pedir ajuda |
| `COMECE_AQUI.html` | Guia rápido de uso, para depois de instalar |
| `_Baixador/instalador/manifesto.tsv.gz` | **A lista da biblioteca**: os ~54 mil arquivos, com prioridade, tipo, caminho, tamanho, endereço, SHA-256 e endereços reserva |
| `_Baixador/instalador/categorias.py` | As categorias do menu "o que baixar" e a regra que põe cada arquivo numa delas |
| `_Baixador/instalador/instalar.py` | O instalador: baixa por prioridade, retoma, confere cada arquivo |
| `_Baixador/instalador/gerar_kit.py` | Gera o kit a partir de uma biblioteca instalada |
| `_Baixador/instalador/gerar_catalogo.py` | Gera o `CATALOGO.html` e o `CATALOGO.csv` (lista legível de tudo o que está instalado) |
| `_Baixador/instalador/espelho.tsv` e `espelho.py` | O que tem cópia no servidor do projeto ([detalhes](../ferramentas/ESPELHO_R2.md)) |
| `_Baixador/instalador/reservas.tsv` | Cópias nossas nos Releases do GitHub (endereço reserva de cada arquivo frágil) |
| `_Baixador/instalador/atalho/` | Fonte e ícone do atalho `ABRIR BIBLIOTECA.exe`, compilado no próprio PC no fim da instalação |
| `_Baixador/atualizar.py` | A atualização pela internet (botão do app), que respeita as categorias escolhidas |
| `_Baixador/baixar_*.py` | Os baixadores de cada fonte (Embrapa, Exército, SciELO, mapas, textos religiosos...) |
| `App/` | O app que abre no navegador: busca, leitor, IA, tradutor, OCR e mapa |

Para ver a lista da biblioteca sem descompactar à mão:

```bash
python -c "import gzip; print(gzip.open('_Baixador/instalador/manifesto.tsv.gz','rt',encoding='utf-8').read()[:3000])"
```

As colunas, separadas por TAB, são `prioridade`, `tipo`, `caminho`, `tamanho`, `endereço`, `sha256` (a impressão digital do arquivo, conferida depois do download) e `reserva` (outro endereço para o mesmo arquivo); `-` quer dizer vazio. A prioridade vai de 1
(programas e IA) a 6 (Gutenberg e extras), e o instalador baixa nessa ordem.

## Como sugerir conteúdo novo

Leia o [CONTRIBUINDO.md](../CONTRIBUINDO.md). Resumindo: abra uma **issue** com o modelo "Sugerir conteúdo",
dizendo o nome, o link, a licença e a categoria. O autor confere a licença e se a fonte é confiável antes de incluir.
O conteúdo entra numa versão nova do kit.

## Licenças

O código é distribuído para uso pessoal e para compartilhar sem fins comerciais. Cada material baixado segue a
licença da fonte original (veja `CONTEUDO.txt` e `ESPECIFICACAO_APP.md`). As bibliotecas incluídas em `App/static`
são de código aberto: MapLibre, Protomaps, pdf.js e tesseract.js.
