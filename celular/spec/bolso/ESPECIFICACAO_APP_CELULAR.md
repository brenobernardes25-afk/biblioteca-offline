# Biblioteca de Bolso — especificação do app Android

Documento para o Claude Code construir o app. É a versão de celular da **Biblioteca Offline** do Breno (que roda no Windows, a partir de um HD). Responda e comente sempre em **português do Brasil**.

Arquivos que acompanham esta especificação:

| Arquivo | O que é |
|---|---|
| `pacotes.json` | Lista exata do que o app baixa nas versões Slim: 3.602 itens, com URL, tamanho, destino e pacote. **É a fonte da verdade do conteúdo Slim.** |
| `pacotes_revisao.csv` | O mesmo, em planilha, para o Breno revisar (abre no Excel). |
| `gerar_indice_celular.py` | Roda no PC do Breno e gera os índices de busca dos PDFs que o app baixa prontos (seção 7). |
| `curar_embrapa.py`, `curar_pdfs.py`, `montar_pacotes.py` | Scripts que montaram o `pacotes.json` a partir do manifesto da biblioteca do PC. Use se precisar refazer. |

---

## 1. O que é

Um app que coloca no celular uma biblioteca de sobrevivência que funciona **sem internet**: enciclopédias (Wikipédia em português e outras), manuais de saúde, água, sobrevivência e Embrapa, com **uma busca única em tudo** e um **tradutor offline** para o material em inglês.

Cenário de uso: a internet caiu. A pessoa abre o app e digita "como purificar água" ou "picada de cobra". Aparecem os trechos certos, de manuais e enciclopédias, e um toque abre o original na página certa.

Princípios (os mesmos da versão do PC):
- **Uma caixa de busca para tudo.** A pessoa não precisa saber em que arquivo está a informação.
- **Fonte sempre visível.** Todo resultado mostra de onde veio e abre o original.
- **Funciona com o que existir.** Download pela metade, item que falhou, falta de espaço, HD desconectado: o app segue funcionando com o resto e explica o que falta. Nunca quebra.
- **Depois de baixado, zero internet.** Nada de CDN, fonte externa, telemetria ou login.
- **Português do Brasil, simples, letra grande.** Pensado para leigo, sob estresse.

## 2. As versões (a pessoa escolhe no primeiro uso)

O mesmo app oferece duas versões. A **Slim** tem dois tamanhos.

| Versão | Onde fica | Tamanho | Conteúdo |
|---|---|---|---|
| **Slim mínimo** | no celular | **12,4 GB** | Saúde, água, sobrevivência e o essencial da Embrapa (~400 títulos) |
| **Slim** | no celular | **77 GB** (~82 GB com índice e IA) | Tudo do mínimo + Wikipédia em português completa, manuais do Exército dos EUA, consertos, fóruns práticos e ~2.400 títulos da Embrapa |
| **Completo** | num **SSD externo** ligado ao celular pelo USB-C | **~830 GB** (SSD de 1 TB) | A biblioteca inteira, **igual à versão do PC**. O mesmo SSD funciona no notebook e no celular. |

E existe a terceira forma de usar, que não é deste app: a **versão do PC** (o kit no GitHub), que os celulares da casa podem acessar pelo Wi-Fi do notebook.

Na escolha, o app mostra o espaço livre do celular e, para o Completo, o espaço livre do SSD conectado. Opções que não cabem ficam desabilitadas, com a explicação de quanto falta.

## 3. Plataforma e decisões técnicas

- **Android 8.0+ (minSdk 26)**, alvo no SDK atual. Kotlin + Jetpack Compose. Pacote sugerido: `br.com.waltz.bibliotecadebolso`.
- **Sem iPhone nesta versão.**
- Celular de referência: 8 GB de RAM, 256 GB de armazenamento, USB-C, sem cartão de memória. Tem que rodar bem em 4 GB de RAM (sem a IA).

### 3.1 Bibliotecas (confirme versões e licenças antes de usar)

| Função | Biblioteca | Observação |
|---|---|---|
| Ler arquivos .zim (artigos, imagens, busca de texto) | **libkiwix para Android** (a mesma do app Kiwix; Maven Central) | **Licença GPL-3.0 → o app inteiro vira GPL-3.0.** Tudo bem: o app é gratuito e o código fica aberto no GitHub. |
| Busca nos PDFs | **SQLite com FTS5**, usando uma SQLite empacotada (ex.: `sqlite-android` do requery) | O SQLite do próprio Android não garante FTS5. |
| Mostrar PDF na página certa | `android.graphics.pdf.PdfRenderer` (nativo) | Mais um botão "Abrir em outro app". |
| Downloads | WorkManager + serviço em primeiro plano + OkHttp com `Range` (retomada) | Seção 5. |
| HD externo | Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE`) | Seção 6. |
| Tradução | Ver seção 9 (há uma restrição de licença) | |

## 4. Conteúdo Slim: o `pacotes.json`

Os tamanhos são reais, tirados do manifesto da biblioteca do PC.

- **Slim mínimo (12,4 GB, 1.177 arquivos):** saúde (MDWiki, WikEM, medicina militar, guias rápidos, *Onde não há médico*, Ministério da Saúde: primeiros socorros, animais peçonhentos, queimaduras, dengue, parto, plantas medicinais), sobrevivência (Ready.gov, TruePrepper, pós-desastre, água, alimentos, nós, Exército Brasileiro, cursos SERE/USMC), o essencial da Embrapa (3 GB, começando pelos 25 livros "o produtor pergunta, a Embrapa responde"), Wikilivros e Wikcionário.
- **Slim (77 GB, 3.600 arquivos):** tudo do mínimo + Wikipédia em português completa com imagens (20,6 GB), manuais do Exército dos EUA (8,2 GB), Learn Self-Reliance, iFixit em português, Wikisource, MedlinePlus, Appropedia, Energypedia, fóruns práticos (horta, reformas, mecânica, cozinha), Wikipédia em inglês simplificado e mais Embrapa (15 GB no total).
- **IA (opcional):** +2,5 GB (Qwen3 4B, celular com 8 GB de RAM) ou +1,8 GB (Qwen3 1.7B, 6 GB de RAM). Fase 4.

Estrutura de cada item:

```json
{"id": "zim:wikipedia_pt_all_maxi", "tipo": "zim", "pacote": "slim", "tema": "Enciclopédia PT",
 "titulo": "Wikipédia em português (completa, com imagens)", "url": "https://...", "tam": 20640000000,
 "destino": "enciclopedias/wikipedia_pt_all_maxi_2026-05.zim", "atualizavel": true, "kiwix_nome": "wikipedia_pt_all_maxi"}
```

- `tipo`: `zim` | `pdf` | `ia`. `pacote`: `minimo` | `slim` | `opcional`. O Slim inclui o mínimo.
- `destino`: caminho relativo à pasta de dados do app.
- `kiwix_nome` (só nos .zim): na hora de baixar, **procure a versão mais nova no catálogo OPDS do Kiwix** (`https://library.kiwix.org/catalog/v2/entries?name=<kiwix_nome>`) e use a URL e o tamanho de lá. A URL do `pacotes.json` é a reserva. Os .zim saem com data no nome e as versões antigas somem do servidor.
- `origem_pc` (só nos PDFs): caminho do mesmo arquivo na biblioteca do PC. Serve para gerar o índice e para, no modo HD, achar o arquivo no SSD.
- O bloco `pacotes.completo` do JSON não tem itens próprios: aponta para o kit do PC (seção 6).
- O `pacotes.json` fica hospedado no GitHub e o app busca a versão mais nova ao abrir com internet. Uma cópia vai embutida no app, para o primeiro uso.

## 5. Instalação e download — versões Slim (tudo pelo celular)

Primeira abertura (assistente):
1. **Boas-vindas**: o que é, em 3 linhas.
2. **Escolha da versão** (seção 2), com o espaço livre do celular (`StatFs`). Opção "+ IA" mostrada só se a RAM permitir (`ActivityManager.MemoryInfo.totalMem`).
3. **Como baixar**: interruptor "Só no Wi-Fi" (ligado por padrão), aviso de que pode levar horas e de que **dá para usar o app enquanto baixa**.

Regras do download:
- **WorkManager com serviço em primeiro plano** e notificação com progresso: "Baixando 312 de 1.177 · 4,1 de 12,4 GB · ~2 h restantes". Botões Pausar/Retomar na notificação.
- **Retomada**: grava em `<destino>.parcial` e continua com `Range: bytes=N-`. Se o servidor responder 200 em vez de 206, recomeça o arquivo. Ao terminar, confere o tamanho com o esperado (com tolerância para os .zim, cujo tamanho vem do catálogo), renomeia e marca como pronto num banco local.
- **3 downloads em paralelo** no máximo. Arquivos grandes, como a Wikipédia, vão um de cada vez.
- **Ordem**: o índice de busca e os itens de Saúde primeiro, depois Água e Sobrevivência, depois o resto, do menor para o maior. Assim o app fica útil cedo.
- **Falhas**: 5 tentativas com espera crescente. Se mesmo assim falhar, marca o item como "falhou" e segue. Tela "O que faltou" com botão "Tentar de novo".
- **HTTPS**: alguns servidores (Embrapa, Ministério da Saúde via web.archive.org) são lentos ou instáveis. Timeout de leitura generoso (2 min) e User-Agent de navegador comum.
- Respeitar bateria fraca e modo de economia (`Constraints`). Nada em segundo plano depois que terminar.
- **Armazenamento**: pasta específica do app (`getExternalFilesDir`), que não pede permissão. Pôr `android:hasFragileUserData="true"` no manifest, para o Android perguntar se a pessoa quer manter os dados ao desinstalar. Se não fizer isso, uma desinstalação apaga 77 GB baixados.
- Botão "Atualizar biblioteca" (manual): baixa só o que mudou no `pacotes.json` e as versões novas dos .zim.
- **Trocar de versão** depois: do mínimo para o Slim, o app baixa só a diferença. Do Slim para o mínimo, o app oferece apagar o que sobra.

## 6. Versão Completa — SSD externo no USB-C

O SSD tem **a mesma estrutura da biblioteca do PC** (`1_Enciclopedia/`, `2_Mapas/`, `4_IA/`, `5_Acervo/`, `App/`, `Programas/`, `_Baixador/`). Assim, **um mesmo SSD funciona no notebook e no celular**.

### 6.1 Conectar um SSD que já tem a biblioteca (o caso principal)
- O app detecta o SSD ligado (`StorageManager` e evento de mídia montada) e pede a pasta raiz por `ACTION_OPEN_DOCUMENT_TREE`. Guarda a permissão com `takePersistableUriPermission`, para não perguntar de novo.
- Valida a pasta: tem `1_Enciclopedia` e `5_Acervo`? Se não tiver, explica e pede para escolher de novo.
- Busca e leitura **direto do SSD, sem copiar nada**:
  - **.zim**: abrir pelo descritor de arquivo (`ContentResolver.openFileDescriptor(uri, "r")`) e passar o fd para a libkiwix. **Confirme isso na fase 1, antes de tudo**: o app Kiwix lê .zim de pendrive e cartão, então a libkiwix deve aceitar fd. Se não aceitar, esse é o maior risco técnico da versão Completa, e o Breno precisa saber logo.
  - **PDFs**: mesmo esquema (fd → `PdfRenderer`).
  - **Busca nos PDFs**: usa o índice da própria biblioteca do PC, se estiver no SSD (`App/dados/indice.db`, mesmo esquema da seção 7 e caminhos `5_Acervo/...`). Copie esse arquivo para o armazenamento interno do app na primeira conexão (algumas centenas de MB a poucos GB) e atualize quando o do SSD mudar (data e tamanho). O SQLite em cima do SAF é lento e pouco confiável. Se o SSD não tiver índice (por exemplo, foi baixado pelo celular), baixe o `indice_completo_<versao>` dos Releases (seção 7).
  - **IA**: o `.gguf` em `4_IA/` é lido do SSD para a memória.
- **SSD desconectado** com o app aberto: avisa "SSD desconectado: conecte de novo para ver o conteúdo completo" e segue funcionando com o que estiver no celular. Se a pessoa também tiver um Slim instalado, a busca continua nele.

### 6.2 Montar a biblioteca completa do zero, só pelo celular (para quem não tem computador)
- O app baixa o kit do PC (`releases/latest` do repositório `biblioteca-offline`, ~30 MB) e extrai na raiz do SSD. Assim, o SSD fica pronto também para o notebook.
- Lê a lista de arquivos do kit (`_Baixador/instalador/manifesto.tsv.gz`: prioridade, tipo, caminho, tamanho e URL; ~54 mil arquivos, 821 GB) e baixa tudo para o SSD pela SAF, com as mesmas regras de download da seção 5 e na ordem de prioridade do manifesto.
- **Avisos obrigatórios antes de começar**, numa tela clara:
  - leva **dias**, não horas;
  - o celular precisa ficar **na tomada**, ligado ao SSD por um **adaptador USB-C com entrada de carregador** (hub com PD), porque só com a bateria do celular o SSD não aguenta;
  - **não tirar o cabo** durante o download (o app retoma, mas cada desconexão atrasa);
  - **o SSD precisa estar em exFAT** (muitos celulares não leem NTFS). O app detecta e explica como formatar, mas **nunca formata sozinho**;
  - o melhor caminho continua sendo baixar num PC, mesmo emprestado, com o `INSTALAR_BIBLIOTECA.bat`.
- Manter a tela desligada sem parar o download (serviço em primeiro plano + `WakeLock` parcial só enquanto baixa).

### 6.3 Compatibilidade de hardware (mostrar numa tela "Que SSD usar?")
- **SSD portátil USB-C** (SanDisk, Samsung T7, Kingston, Crucial…): funciona na maioria dos celulares.
- **HD mecânico de 2,5"**: muitas vezes não liga sem energia extra (hub com fonte).
- **HD de 3,5" (de computador de mesa)**: não funciona no celular.
- Precisa de celular com **USB-C com OTG**, que é quase todo Android recente.

## 7. Índices de busca dos PDFs (gerados no PC, baixados prontos)

O celular **não** extrai texto de milhares de PDFs: levaria horas, esquentaria o aparelho e gastaria bateria. Os índices são gerados uma vez no PC do Breno e publicados no GitHub.

- `gerar_indice_celular.py pacotes.json` gera o `indice_slim_<versao>.zip`, só com os PDFs dos pacotes Slim e caminhos = `destino`.
- `gerar_indice_celular.py --completo pacotes.json` gera o `indice_completo_<versao>.zip`, com a biblioteca inteira e caminhos `5_Acervo/...`. Se passar de 1,9 GB, sai dividido em `.zip.001`, `.zip.002`…, porque o GitHub aceita até 2 GB por arquivo. O app baixa as partes e junta na ordem.
- Os dois partem do índice que a biblioteca do PC já montou (`App/dados/indice.db`, FTS5, com OCR dos PDFs escaneados). Testado com dados de exemplo.
- Esquema que o app lê:
  - `docs(id, caminho, item_id, pacote, titulo, tema, idioma, paginas, situacao)`;
  - `pag` FTS5 `(titulo, texto, doc_id, pagina)` com `unicode61 remove_diacritics 2`;
  - `titulos` FTS5 `(titulo, doc_id)`;
  - `meta(chave, valor)` com a `versao`.
  - O `App/dados/indice.db` do próprio SSD tem `docs` e `pag` com as mesmas colunas principais (`caminho`, `titulo`, `tema`, `idioma`, `paginas`, `situacao`), sem `item_id` e `pacote`. O app tem que aceitar os dois formatos.
- O app só mostra resultados de documentos **disponíveis agora** (baixados, ou presentes no SSD conectado). Assim a busca funciona durante o download, com o que já chegou.
- Uns 67 PDFs da Embrapa do Slim ainda não estão no SSD do Breno e por isso não têm texto no índice. O app lista esses pelo título.

## 8. Busca única

Uma caixa de busca no topo da tela inicial. Ao buscar:
1. **PDFs**: consulta FTS5 no índice (seção 7), com `bm25`, trecho destacado (`snippet`) e número da página. Busca sem acento: "agua" acha "água".
2. **Enciclopédias (.zim)**: busca de texto completo da libkiwix em cada .zim disponível, **em paralelo e com limite de tempo** (ex.: 2 s por arquivo), mais as sugestões por título.
3. **Resultados agrupados**: "Manuais" e "Enciclopédias", com título, trecho, tema, idioma (selo PT/EN) e fonte (celular ou SSD). Filtros por tema e idioma.
4. **Busca também em inglês**: interruptor "Incluir fontes em inglês", que traduz os termos com um dicionário embutido de sobrevivência e saúde (~500 termos PT→EN: água→water, picada→bite, cobra→snake…) e busca de novo.

Abrir um resultado:
- **PDF**: visualizador próprio (`PdfRenderer`), já na página do resultado. Botões: página anterior/seguinte, ir para página, "Abrir em outro app" e "Traduzir esta página" (lendo o texto da página no índice).
- **Artigo .zim**: `WebView` servindo o conteúdo do .zim por um esquema próprio (`shouldInterceptRequest` lendo da libkiwix). Links internos funcionam e **nada carrega da internet**. Botão "Traduzir".

Atalhos na tela inicial (botões grandes com ícone), cada um dispara buscas prontas em PT e EN:
**Água · Primeiros socorros · Picadas e animais · Fogo · Abrigo · Plantio · Criação de animais · Conservar alimentos · Remédios caseiros e plantas · Consertos · Mapa**.

## 9. Tradutor offline

Restrição: o app é **GPL-3.0** por causa da libkiwix. Embutir um SDK proprietário, como o ML Kit do Google, num app GPL é um conflito de licença. **Confirme isso antes de decidir.** Então:

- **Fase 3a (simples):** tela "Traduzir" com caixa de texto, e botão "Traduzir" nos artigos e páginas em inglês, que entrega o texto ao **tradutor que já estiver instalado** no celular por `Intent.ACTION_PROCESS_TEXT` ou por compartilhamento. Na configuração, explicar como baixar o pacote "Inglês ↔ Português" offline no Google Tradutor.
- **Fase 3b (completa, se der):** tradução embutida com os modelos abertos da Mozilla (Firefox Translations / Bergamot, licença MPL-2.0, compatível com GPL), rodando no aparelho.
- O dicionário de termos da seção 8 é independente e sempre funciona.

## 10. IA (opcional, fase 4)

Só para quem escolher e tiver RAM. **llama.cpp para Android** (licença MIT) com os `.gguf` (Qwen3 4B para 8 GB de RAM; Qwen3 1.7B para 6 GB), baixados no Slim ou lidos do SSD no Completo.
- Igual à biblioteca do PC: a pergunta busca trechos no índice e nos .zim, e a IA responde **em português, só com base nos trechos, citando [1], [2]**, clicáveis. Se não achar, diz que não achou.
- Carrega só quando usada e descarrega quando o app vai para segundo plano. Aviso de que gasta bateria.
- **O app tem que ser completo sem a IA.**

## 11. Mapas

Não refazer mapas. Botão "Mapa":
- se o **Organic Maps** estiver instalado, abre ele (intent `geo:` ou o esquema do Organic Maps);
- se não estiver, uma tela explica em 3 passos: instalar o Organic Maps, abrir e baixar o mapa do seu estado (cada estado tem de 30 a 200 MB).

## 12. Telas

1. **Início**: caixa de busca, atalhos e, durante o download, um cartão com o progresso. Indicador da versão em uso ("Slim · 77 GB" ou "Completo · SSD conectado").
2. **Resultados**: agrupados e filtráveis.
3. **Leitor**: PDF ou artigo, com Traduzir, Favoritar e Compartilhar trecho (texto puro).
4. **Estante**: navegar por tema e por fonte, com quantos itens estão prontos e quantos ainda baixam.
5. **Favoritos**: offline, guardados no banco local.
6. **Downloads e versão**: estado de cada item, Pausar/Retomar, "Só no Wi-Fi", "Tentar de novo", espaço usado e livre (celular e SSD), e trocar de versão.
7. **SSD externo**: conectar, escolher a pasta, "Que SSD usar?" e "Montar a biblioteca completa neste SSD" (seção 6.2).
8. **Sobre e licenças**: créditos e licença de cada fonte (Wikipédia CC BY-SA, Hesperian CC BY-NC-SA, Stack Exchange CC BY-SA, Embrapa, Ministério da Saúde, manuais militares dos EUA em domínio público, Kiwix GPL), o aviso "Uso pessoal e gratuito. Não substitui atendimento médico" e "@breno.paiva40 no Instagram".

Visual: sóbrio, de "manual de campo", **letra grande**, alto contraste, **modo escuro** quando o sistema estiver escuro. Botões grandes para dedo. Nada de cara de template.

## 13. Requisitos não funcionais

- Abrir o app em menos de 2 s. Busca respondendo em menos de 2 s no celular de referência, tanto no Slim quanto no Completo pelo SSD.
- **Funciona 100% em modo avião** depois do download.
- Nenhuma permissão além de internet, notificação (para o download) e "manter a tela" opcional no leitor. **Sem localização, contatos, câmera ou microfone.** O acesso ao SSD é só pela pasta que a pessoa escolher.
- Sem anúncios, sem analytics, sem login.
- Erros em português claro, dizendo o que fazer.
- Logs locais exportáveis (botão em Sobre) para o Breno diagnosticar problemas de quem usa.

## 14. Fases

1. **Download Slim + .zim**: assistente, downloads com retomada, leitor e busca nos .zim. **Na mesma fase, uma prova rápida**: abrir um .zim de um SSD pela SAF/fd (seção 6.1). É o maior risco técnico, e precisa ser confirmado cedo.
2. **PDFs**: índice baixado, busca unificada, leitor de PDF na página certa.
3. **Versão Completa pelo SSD**: conectar um SSD já pronto (6.1) e, depois, montar a biblioteca do zero pelo celular (6.2).
4. **Tradução**: 3a, e depois 3b se der.
5. **IA opcional.**
6. **Publicação**: APK assinado nos Releases do GitHub, e depois Google Play. Verificar a política do Play sobre apps que baixam conteúdo grande e acessam armazenamento externo.

Entregue e teste cada fase antes de seguir para a próxima.

## 15. Critérios de aceite (testar num celular de verdade)

**Slim**
1. Instalar o APK num celular sem nada, escolher o Slim mínimo e baixar tudo **sem usar computador**.
2. Desligar o Wi-Fi no meio do download, religar: continua de onde parou, sem recomeçar arquivos grandes.
3. Fechar o app e reiniciar o celular no meio: o download retoma sozinho.
4. Durante o download, a busca já encontra resultados do que já chegou.
5. Em modo avião, buscar "agua" (sem acento) traz resultados de PDFs e de enciclopédias; o PDF abre na página certa e o artigo abre com imagens.
6. Buscar "picada de cobra" traz o manual de animais peçonhentos do Ministério da Saúde entre os primeiros resultados.
7. Celular sem espaço para o Slim: a opção aparece desabilitada, com a explicação.
8. Desinstalar: o Android pergunta se quer manter os dados.

**Completo (SSD)**

9. Plugar o SSD da biblioteca do PC do Breno: o app reconhece, pede a pasta uma vez e, em modo avião, a busca acha resultados nos ~45 mil PDFs da Embrapa e na Wikipédia completa.
10. Tirar o cabo com o app aberto: aviso claro, sem travar. Plugar de novo: volta sozinho, sem pedir a pasta outra vez.
11. O mesmo SSD continua abrindo normalmente no notebook pelo `ABRIR_BIBLIOTECA.bat` (o app do celular não pode estragar nada no SSD).
12. Num SSD vazio em exFAT, "Montar a biblioteca completa" extrai o kit e começa a baixar. Interromper e retomar funciona.

**Geral**

13. Num artigo em inglês, "Traduzir" leva o texto ao tradutor (fase 3a) e mostra em português.
14. Nenhuma requisição de rede em uso normal depois do download (conferir com o log de rede).

Ao terminar cada fase, rode os critérios que se aplicam e relate o resultado de cada um.

## 16. Como fica no GitHub (repositório `biblioteca-offline`)

O mesmo repositório distribui tudo. A página inicial (README) mostra as três opções lado a lado:

| Opção | O que baixar dos Releases |
|---|---|
| **PC (notebook)** | `Kit_Biblioteca_Offline_<data>.zip` (já existe) |
| **Celular — Slim / Slim mínimo** | `Biblioteca_de_Bolso_<versao>.apk`. O app baixa o resto sozinho, incluindo o `indice_slim_<versao>.zip`. |
| **Celular + SSD — Completo** | o mesmo APK; o app usa o kit do PC e o `indice_completo_<versao>.zip` (ou partes) |

O `pacotes.json` fica na pasta `celular/` do repositório, e o app lê a versão mais nova por `raw.githubusercontent.com`. O código do app (GPL-3.0) pode ficar no mesmo repositório, numa pasta `app-android/`, ou num repositório próprio. Decida com o Breno.

## 17. O que o Breno precisa fazer

- **Revisar o `pacotes_revisao.csv`**: a curadoria da Embrapa e do Ministério da Saúde foi feita por palavras-chave nos títulos e é boa, mas não é perfeita. Apagar uma linha, ou trocar o `pacote` (`minimo` ↔ `slim`), e rodar o `montar_pacotes.py` de novo atualiza tudo.
- **Gerar os dois índices no PC** (`gerar_indice_celular.py`, com e sem `--completo`) depois que a biblioteca do PC tiver indexado tudo, e publicar nos Releases.
- **Testar no celular dele** a cada fase, inclusive com o SSD SanDisk plugado.
