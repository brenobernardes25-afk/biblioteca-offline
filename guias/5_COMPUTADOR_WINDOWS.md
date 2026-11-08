# Computador (Windows): a biblioteca completa num HD/SSD de 1 TB

**Para quem é:** quem quer **tudo** e tem um computador com Windows. É a versão mais completa:

- enciclopédias (Wikipédia e outras);
- manuais de saúde, agricultura (Embrapa) e sobrevivência;
- manuais do Exército Brasileiro;
- livros acadêmicos;
- mapas do Brasil;
- textos religiosos;
- uma **IA** que responde citando as fontes.

São cerca de **830 GB** e funciona sem internet. O mesmo HD/SSD depois funciona também no
**celular**: veja o guia [Completo com SSD](4_CELULAR_COMPLETO_SSD.md).

## Do que você precisa

- Computador com **Windows 10 ou 11** (64 bits).
- Um **HD ou SSD externo vazio de 1 TB**. Com menos espaço, ele instala primeiro o mais importante e pula o resto.
- Internet para o download inicial. Leva de horas a alguns dias, conforme a internet. Pode parar e continuar.

## Antes de começar: deixe o SSD em exFAT (para usar também no celular)

Se você quer usar o mesmo SSD no celular, ele precisa estar em **exFAT**. Muitos SSDs novos já vêm assim.

1. Ligue o SSD e abra **Este Computador**.
2. Clique com o botão direito no SSD › **Propriedades**. Veja o **Sistema de arquivos**:
   - **exFAT**: tudo certo. Siga em frente.
   - **NTFS** ou outro: se o SSD está **vazio**, clique com o botão direito nele › **Formatar…**.
     Em **Sistema de arquivos**, escolha **exFAT** e clique em **Iniciar**.

> ⚠️ **Formatar apaga tudo o que está no disco.** Confira duas vezes se escolheu o disco certo e se ele está vazio.

## Passo a passo

1. Baixe o kit: **[Kit_Biblioteca_Offline_2026-11-08.zip (28 MB)](https://github.com/brenobernardes25-afk/biblioteca-offline/releases/download/v2026-11-08/Kit_Biblioteca_Offline_2026-11-08.zip)**.
2. Ligue o HD/SSD vazio no computador.
3. Clique com o botão direito no zip › **Extrair tudo…**.
   - Em **"Os arquivos serão extraídos para esta pasta"**, escolha a **raiz** do HD/SSD, por exemplo `E:\`.
     Não pode ser dentro de uma pasta.
   - Clique em **Extrair**.
   - No HD/SSD devem aparecer as pastas `App`, `_Baixador` e `Programas`, e os arquivos
     `INSTALAR_BIBLIOTECA.bat` e `ABRIR_BIBLIOTECA.bat`.
4. Dê dois cliques em **`INSTALAR_BIBLIOTECA.bat`**.
   - Se aparecer *"O Windows protegeu o computador"*, clique em **Mais informações** › **Executar assim mesmo**.
   - Na primeira vez, ele mostra **O QUE BAIXAR**, com o tamanho de cada parte e o espaço livre no disco:
     - **para baixar tudo** (recomendado), só tecle **ENTER**;
     - **para deixar algo de fora**, digite os números e tecle **ENTER**. Por exemplo, `3 10` deixa de fora a
       Survivor Library (205 GB) e a literatura em inglês (150 GB). Depois tecle **ENTER** para confirmar.
     - O essencial (saúde, sobrevivência, água e a IA) sempre vem.
   - Deixe a janela aberta. Ela mostra o andamento a cada 30 segundos.
   - **Se fechar, faltar luz ou cair a internet:** dê dois cliques de novo. Ele continua de onde parou e
     não baixa nada duas vezes.
5. Quando aparecer **PRONTO**, dê dois cliques em **`ABRIR_BIBLIOTECA.bat`**.
   Na primeira vez, o app monta o índice de busca, o que leva algumas horas. Dá para ir usando enquanto isso.
   **Esse índice é o que o celular usa para buscar dentro dos manuais**, por isso abra a biblioteca uma vez antes
   de levar o SSD para o celular.

**Mudou de ideia sobre o que baixar?** Dê dois cliques em **`ESCOLHER_O_QUE_BAIXAR.bat`**. Nada do que já foi
baixado é apagado. A atualização pela internet também respeita a sua escolha.

Dentro do kit, o `LEIA-ME_PRIMEIRO.txt` explica tudo de novo, e o menu **Ajuda** do app também.

## Já instalou antes?

### Corrigir os PDFs cortados (kits até 05/11/2026)

Os kits até 05/11/2026 traziam 943 PDFs cortados no meio: saúde, livros da SciELO e alguns da Embrapa.
Não precisa começar de novo:

1. Baixe o [kit mais recente](https://github.com/brenobernardes25-afk/biblioteca-offline/releases/download/v2026-11-08/Kit_Biblioteca_Offline_2026-11-08.zip).
2. Descompacte na **raiz** do HD, onde está o `INSTALAR_BIBLIOTECA.bat`, e escolha **substituir os arquivos**.
3. Rode o `INSTALAR_BIBLIOTECA.bat`. Na primeira vez com o kit novo, ele pergunta o que baixar: tecle **ENTER**
   para continuar com tudo. Depois ele baixa só o que estava cortado (cerca de 12 GB).

### "[WinError 2]" ou "Sem internet" com a internet funcionando (kit de 30/10)

O kit mais recente já resolve os dois problemas. Faça os mesmos 3 passos acima.
Se preferir só a correção, baixe a
[**correção (170 KB)**](https://github.com/brenobernardes25-afk/biblioteca-offline/raw/correcao-curl/correcao/Correcao_curl_2026-11-04.zip),
descompacte na raiz do HD substituindo os arquivos e rode o `INSTALAR_BIBLIOTECA.bat` de novo.

## Se der problema

- **Arquivos que saíram do ar:** rode a instalação de novo mais tarde. As enciclopédias são trocadas sozinhas pela
  versão mais nova.
- **Para ver o que falta sem baixar nada:** abra um Prompt de Comando na pasta e digite
  `INSTALAR_BIBLIOTECA.bat --verificar`.
- **Registro detalhado:** `_Baixador\instalador\instalacao_registro.txt`.
- **Depois de instalada,** a biblioteca se atualiza pelo próprio app: **Situação › Atualizar pela internet**.

## Também quer no celular?

- **O mesmo SSD no celular:** [Completo com SSD](4_CELULAR_COMPLETO_SSD.md).
- **Uma versão menor dentro do celular, preparada pelo computador:** [Slim mínimo](2_CELULAR_SLIM_MINIMO.md)
  ou [Slim](3_CELULAR_SLIM.md), no **Caminho B**. Com o HD da biblioteca ligado, o `PREPARAR_CELULAR.bat`
  copia de lá, sem baixar de novo.

[← Voltar para o início](../README.md)
