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

1. Baixe o kit: **[Kit_Biblioteca_Offline_2026-11-19.zip (30 MB)](https://github.com/brenobernardes25-afk/biblioteca-offline/releases/download/v2026-11-19/Kit_Biblioteca_Offline_2026-11-19.zip)**.
2. Ligue o HD/SSD vazio no computador.
3. Clique com o botão direito no zip › **Extrair tudo…**.
   - Em **"Os arquivos serão extraídos para esta pasta"**, escolha a **raiz** do HD/SSD, por exemplo `E:\`.
     Não pode ser dentro de uma pasta.
   - Clique em **Extrair**.
   - No HD/SSD devem aparecer as pastas `App`, `_Baixador` e `Programas`, e os arquivos
     `INSTALAR_BIBLIOTECA.bat` e `ABRIR_BIBLIOTECA.bat`.
4. Dê dois cliques em **`INSTALAR_BIBLIOTECA.bat`**.
   - **Pouco espaço?** Use o **`INSTALAR_VERSAO_LEVE.bat`** no lugar: baixa ~100 GB, sem perguntar nada
     (Wikipédia em português, saúde, sobrevivência, água, mapas, Exército e a IA). Cabe no próprio computador:
     nesse caso descompacte o kit numa pasta como `C:\BibliotecaOffline`, nunca na Área de Trabalho (o OneDrive
     tenta copiar tudo para a nuvem). Para baixar mais depois: `ESCOLHER_O_QUE_BAIXAR.bat`.
   - Se aparecer *"O Windows protegeu o computador"*, clique em **Mais informações** › **Executar assim mesmo**.
   - Na primeira vez, ele mostra **O QUE BAIXAR**, com o tamanho de cada parte e o espaço livre no disco:
     - **para baixar tudo** (recomendado), só tecle **ENTER**;
     - **para deixar algo de fora**, digite os números e tecle **ENTER**. Por exemplo, `3 10` deixa de fora a
       Survivor Library (205 GB) e a literatura em inglês (150 GB). Depois tecle **ENTER** para confirmar.
     - O essencial (saúde, sobrevivência, água e a IA) sempre vem.
   - Deixe a janela aberta. A cada 30 segundos ela mostra quantos GB já vieram, a velocidade dos últimos
     minutos, o tempo que falta e o arquivo grande que está chegando agora.
   - Deixe o computador **na tomada** e configure para **nunca suspender** (Configurações › Sistema ›
     Energia). Se ele dormir, o download para.
   - **Quanto tempo leva:** depende da internet. Tudo (~830 GB) leva de 3 a 5 dias com uma internet boa.
     Sem a Survivor Library e a literatura em inglês, cai para uns 475 GB.
   - **A ordem é de propósito:** primeiro o essencial; os PDFs que vêm de um servidor lento (web.archive.org)
     e os dois arquivos gigantes (Survivor Library, 205 GB, e Wikipédia em inglês, 119 GB) ficam por último.
     Enquanto um gigante baixa, o número de arquivos fica parado por horas: é normal, olhe os GB.
   - **Se fechar, faltar luz ou cair a internet:** dê dois cliques de novo. Ele continua de onde parou e
     não baixa nada duas vezes.
5. Quando aparecer **PRONTO**, dê dois cliques em **ABRIR BIBLIOTECA** (o atalho com o ícone do livro, que o
   instalador cria no fim) ou em **`ABRIR_BIBLIOTECA.bat`**. O guia rápido de uso é o **`COMECE_AQUI.html`**.
   Na primeira vez, o app monta o índice de busca, o que leva algumas horas. Dá para ir usando enquanto isso.
   **Esse índice é o que o celular usa para buscar dentro dos manuais**, por isso abra a biblioteca uma vez antes
   de levar o SSD para o celular.

**A lista de tudo o que foi baixado** fica em **`CATALOGO.html`**, na pasta da biblioteca: abre em qualquer
navegador, mesmo sem o app, tem busca (sem precisar de acento) e abre cada PDF com um clique. Também há
`CATALOGO.csv`, para planilha.

**Mudou de ideia sobre o que baixar?** Dê dois cliques em **`ESCOLHER_O_QUE_BAIXAR.bat`**. Nada do que já foi
baixado é apagado. A atualização pela internet também respeita a sua escolha.

Dentro do kit, o `LEIA-ME_PRIMEIRO.txt` explica tudo de novo, e o menu **Ajuda** do app também.

## Já instalou antes?

### Download parado, muito lento ou com "FALHOU" (kits até 08/11/2026)

O kit de 15/11 corrige o que vocês encontraram:

- quando um servidor do Kiwix falha, ele tenta os outros sozinho (antes aparecia "Enciclopédia fora do ar" e "FALHOU");
- 537 arquivos de sites lentos ou que bloqueavam (web.archive.org, trueprepper, WikEM) passam a vir da nossa cópia
  no GitHub;
- a tela mostra a velocidade real e o arquivo que está chegando, em vez de "velocidade caindo" e "faltam 1300 h";
- um arquivo que chegou pela metade não passa mais como pronto: na próxima rodada ele continua de onde parou;
- se o instalador de um programa vier estragado, ele baixa de novo em vez de parar com "zlib.error".

Para atualizar, faça os 3 passos de "Corrigir os PDFs cortados", logo abaixo. Nada do que você já baixou se perde.

### Corrigir os PDFs cortados (kits até 05/11/2026)

Os kits até 05/11/2026 traziam 943 PDFs cortados no meio: saúde, livros da SciELO e alguns da Embrapa.
Não precisa começar de novo:

1. Baixe o [kit mais recente](https://github.com/brenobernardes25-afk/biblioteca-offline/releases/download/v2026-11-19/Kit_Biblioteca_Offline_2026-11-19.zip).
2. Descompacte na **raiz** do HD, onde está o `INSTALAR_BIBLIOTECA.bat`, e escolha **substituir os arquivos**.
3. Rode o `INSTALAR_BIBLIOTECA.bat`. Na primeira vez com o kit novo, ele pergunta o que baixar: tecle **ENTER**
   para continuar com tudo. Depois ele baixa só o que estava cortado (cerca de 12 GB).

### "[WinError 2]" ou "Sem internet" com a internet funcionando (kit de 30/10)

O kit mais recente já resolve os dois problemas. Faça os mesmos 3 passos acima.
Se preferir só a correção, baixe a
[**correção (170 KB)**](https://github.com/brenobernardes25-afk/biblioteca-offline/raw/correcao-curl/correcao/Correcao_curl_2026-11-04.zip),
descompacte na raiz do HD substituindo os arquivos e rode o `INSTALAR_BIBLIOTECA.bat` de novo.

## Se der problema

- **Apareceu "FALHOU" em alguns arquivos:** deixe terminar e rode a instalação de novo. Ele tenta só o que
  faltou. As enciclopédias que mudaram de versão são trocadas sozinhas pela mais nova.
- **Parece parado:** olhe os GB e o arquivo que aparece em "agora". Se os GB sobem, está baixando.
- **Muito lento:** teste a internet em [fast.com](https://fast.com). Se for menos de 10 Mbps, deixe de fora as partes
  grandes em `ESCOLHER_O_QUE_BAIXAR.bat` (Survivor Library, Wikipédia e literatura em inglês) e baixe depois.
- **Pedir ajuda:** dê dois cliques em **`COPIAR_RELATORIO.bat`**. Ele monta um texto com a situação da instalação
  (o que falta, o que falhou e por quê), sem o seu nome, e já deixa copiado: é só colar na conversa.
- **Para ver o que falta sem baixar nada:** abra um Prompt de Comando na pasta e digite
  `INSTALAR_BIBLIOTECA.bat --verificar`.
- **Desconfia de algum arquivo estragado?** Dê dois cliques em **`CONFERIR_BIBLIOTECA.bat`**. Ele confere o que
  já foi baixado (arquivo sumido, PDF cortado, ZIP quebrado e a soma de verificação de cada enciclopédia) e
  oferece apagar o que estiver com problema, para o instalador baixar de novo. A soma lê cada enciclopédia
  inteira e pode levar horas; para uma conferência rápida, sem essa parte: `CONFERIR_BIBLIOTECA.bat --rapido`.
- **Registro detalhado:** `_Baixador\instalador\instalacao_registro.txt`.
- **Depois de instalada,** a biblioteca se atualiza pelo próprio app: **Situação › Atualizar pela internet**.

## Também quer no celular?

- **O mesmo SSD no celular:** [Completo com SSD](4_CELULAR_COMPLETO_SSD.md).
- **Uma versão menor dentro do celular, preparada pelo computador:** [Slim mínimo](2_CELULAR_SLIM_MINIMO.md)
  ou [Slim](3_CELULAR_SLIM.md), no **Caminho B**. Com o HD da biblioteca ligado, o `PREPARAR_CELULAR.bat`
  copia de lá, sem baixar de novo.

[← Voltar para o início](../README.md)
