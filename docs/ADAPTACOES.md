# Adaptações deste fork

Base: projeto de Breno Paiva, incluindo as correções oficiais de 12/11/2026.
Este fork mantém o código de Android e os créditos originais.

## O que foi incorporado

- Lançadores e instalador para macOS; programas Kiwix e llama.cpp nativos.
- Seleção de executáveis por sistema, com suporte a `Programas/Windows` e ao layout original do kit.
- OCR separado: Tesseract/Poppler no Mac e implementação original do Windows.
- Cache, memória, processos e atualização compatíveis com macOS.
- Exclusão dos arquivos auxiliares `._` do exFAT da indexação.
- Verificação estrutural dos ZIMs, inclusive quando existe marca `.ok`: arquivos truncados não são aceitos como completos.
- Downloads com falha conservam o parcial para retomada.
- Correções oficiais de 12/11 preservadas: fontes do web.archive.org por último e recuperação de ZIP corrompido.
- Fonte alternativa do WikEM 2026-10 publicada pelo autor no GitHub.

## Conteúdo e programas

Este commit acrescenta código, instruções e testes. Não acrescenta documentos,
ZIMs, modelos GGUF, binários, logs, índices ou arquivos pessoais.
O mesmo acervo pode ser usado por Mac e Windows em exFAT, com os programas nativos de cada sistema.
`ABRIR_WINDOWS.bat` aceita Python em `Programas/Windows/Python` ou `Programas/Python`.
Os binários Windows portáteis devem ser preparados separadamente; copiar o código não os instala.

## Validação

A instalação local foi usada num Mac Apple Silicon: leitura de enciclopédias,
mapas e geração de resposta com Qwen3-4B foram testadas; o WikEM de outubro de 2026
abriu no SSD e no pendrive, com SHA-256 e checksum interno conferidos.
A execução em um PC Windows real ainda precisa ser validada. Não há garantia de
instalação limpa em Mac Intel; o instalador seleciona os pacotes dessa arquitetura.

Testes reproduzíveis sem conteúdo nem rede:

```sh
python3 -m unittest discover -s tests -v
bash -n computador/INSTALAR_MAC.sh computador/ABRIR_MAC.sh
```

## Manutenção

Mantenha `origin` apontando para este fork e adicione o repositório original como `upstream`.
Traga alterações do upstream em uma branch, revise o diff e rode os testes antes de integrar.
Não descompacte um kit Windows inteiro sobre uma instalação personalizada sem revisar as alterações.
O patch Mac antigo não é mais necessário: as adaptações estão integradas ao código.
