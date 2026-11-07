# Biblioteca Offline de Sobrevivência

Uma biblioteca do conhecimento humano que funciona **sem internet**, num HD/SSD de 1 TB:
enciclopédias (Wikipédia e outras), manuais de saúde, agricultura (Embrapa), sobrevivência,
manuais do Exército Brasileiro, livros acadêmicos, mapas do Brasil, textos religiosos, e uma
**IA local** que responde perguntas citando as fontes, tudo em português.

**Criado por Breno Paiva**: Instagram [@breno.paiva40](https://instagram.com/breno.paiva40).
Feito para ajudar as pessoas em tempos difíceis. **Gratuito**: não estou cobrando nada, só peço
que me sigam no Instagram.

## Manual de Sobrevivência Brasil (PDF gratuito)

Quer começar sem precisar de HD? Baixe o **Manual de Sobrevivência Brasil**: 45 páginas para a
família passar 72 horas sem ajuda em enchente, El Niño, apagão, crise e primeiros socorros.

- [**Baixar o PDF**](https://github.com/brenobernardes25-afk/manual-de-sobrevivencia-brasil/releases/latest/download/Manual_de_Sobrevivencia_Brasil.pdf) (versão mais recente);
- [página do manual](https://github.com/brenobernardes25-afk/manual-de-sobrevivencia-brasil), com índice e recomendações de uso.

## Como instalar

Você precisa de:

- Windows 10 ou 11;
- um HD/SSD vazio de **1 TB**;
- internet para o download inicial (cerca de 830 GB).

Passos:

1. Baixe o arquivo `Kit_Biblioteca_Offline_<data>.zip` em **Releases** (coluna da direita desta página).
2. Descompacte na **raiz** do HD/SSD (ex.: `E:\`, não dentro de uma pasta).
3. Dê dois cliques em **`INSTALAR_BIBLIOTECA.bat`** e deixe rodar.
   - Pode levar de horas a dias, conforme a internet.
   - Se fechar ou a internet cair, rode de novo: ele continua de onde parou.
4. No fim, dê dois cliques em **`ABRIR_BIBLIOTECA.bat`**.

Leia o `LEIA-ME_PRIMEIRO.txt` que vem dentro do kit.

## Já instalou e as enciclopédias ou a IA não baixaram?

Vale para dois erros do kit de 30/10:

- a instalação mostrou **"[WinError 2] O sistema não pode encontrar o arquivo especificado"**
  (o computador não tem o programa curl do Windows);
- o instalador diz **"Sem internet"** com a internet funcionando (o Windows não tem todos os
  certificados de segurança atualizados).

A correção resolve os dois. Não precisa começar de novo:

1. Baixe a [**correção (170 KB)**](https://github.com/brenobernardes25-afk/biblioteca-offline/raw/correcao-curl/correcao/Correcao_curl_2026-11-04.zip).
2. Descompacte na **raiz** do HD, onde está o `INSTALAR_BIBLIOTECA.bat`, e escolha **substituir os arquivos**.
3. Rode o `INSTALAR_BIBLIOTECA.bat` de novo: ele continua de onde parou.

## Já instalou? Corrija os PDFs cortados

Os kits até 05/11/2026 traziam 943 PDFs cortados no meio: saúde, livros da SciELO e alguns da Embrapa.
O kit de 07/11 aponta para as versões inteiras. Não precisa começar de novo:

1. Baixe o kit mais recente em **Releases**.
2. Descompacte na **raiz** do HD, onde está o `INSTALAR_BIBLIOTECA.bat`, e escolha **substituir os arquivos**.
3. Rode o `INSTALAR_BIBLIOTECA.bat`. Ele baixa só o que estava cortado (cerca de 12 GB).

## O que o kit contém

O zip é pequeno (~28 MB) porque não leva o conteúdo em si. Ele traz:

- **O aplicativo**, que oferece:
  - busca
  - leitor
  - IA
  - tradutor
  - leitor de texto em fotos (OCR)
  - mapa
  - ajuda
- **Os baixadores** e o **Python portátil**.
- **Uma lista com mais de 54 mil arquivos**, cada um com o endereço público de onde vem.

O instalador baixa tudo direto das fontes originais, por exemplo Kiwix, Embrapa, SciELO,
Hesperian e Biblioteca Digital do Exército.

## Licenças

Cada material baixado pertence a quem o publicou e segue a licença da fonte original.
Alguns, como o Alcorão (Tanzil) e a Torá em português (Sefaria), são **apenas para uso
pessoal e não comercial**. As bibliotecas incluídas no app são de código aberto: MapLibre,
Protomaps, pdf.js, tesseract.js e Python.

Projeto pessoal, distribuído de graça. **Não é para venda.**

## Autor

**Breno Paiva**: se esta biblioteca te ajudou, me siga no Instagram:
[@breno.paiva40](https://instagram.com/breno.paiva40).
