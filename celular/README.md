# Biblioteca de Bolso

Biblioteca de sobrevivência para Android que funciona sem internet. Este é o código do app; para instalar,
veja os [guias passo a passo](../README.md#escolha-a-sua-versão). Especificação completa em
[spec/bolso/ESPECIFICACAO_APP_CELULAR.md](spec/bolso/ESPECIFICACAO_APP_CELULAR.md).
Licença: GPL-3.0 (por causa da libkiwix).

## Estado: versão 1.0.0

Pronto: assistente de primeiro uso, downloads do Slim com retomada, busca e leitor dos .zim,
"Atualizar biblioteca", trocar de versão, tela "O que faltou", Mapa, Sobre e licenças, logs
exportáveis, manual dentro do app, "Trazer do computador", busca nos PDFs, leitor de PDF na página certa
a versão Completa (ler um SSD pronto e montar a biblioteca do zero pelo celular), a tradução de inglês para
português, sem internet, e a IA opcional.

Publicado no GitHub como APK, fora da Play Store (Release `celular-v1.0.0`). Das telas da seção 12,
Estante e Favoritos ainda não existem. O `indice_completo` da seção 7 não foi gerado nem publicado: ver abaixo.

## Como compilar

Precisa do Android Studio (usa o JDK dele). O Gradle baixa sozinho o NDK e o CMake. Fora do Android Studio,
diga onde está o SDK: `export ANDROID_HOME="$LOCALAPPDATA/Android/Sdk"` (ou crie o `local.properties` com `sdk.dir`).

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew testDebugUnitTest assembleRelease
```

O APK sai em `app/build/outputs/apk/release/`. Em pasta muito funda, o Windows estoura o limite de
260 caracteres: acrescente `-PpastaCurta=C:/temp/bolso`, e o APK sai em
`C:/temp/bolso/build/outputs/apk/release/`. Para testar o release no emulador, acrescente
`-Pemulador=true` (inclui x86_64).

O APK publicado é assinado com a chave do autor (a de depuração do computador dele). Um APK compilado por outra
pessoa sai com outra chave: para instalar por cima do publicado, é preciso desinstalar antes.

## Como o código está dividido

| Pasta (`app/src/main/`) | O que faz |
|---|---|
| `kotlin/.../dados` | `pacotes.json`, banco local dos downloads, preferências |
| `kotlin/.../download` | Fila, retomada, catálogo do Kiwix, limite por servidor, serviço e notificação |
| `kotlin/.../zim` | Abrir e buscar nos .zim, servir ao WebView |
| `kotlin/.../ssd` | Ler o SSD pela SAF (`ArvoreSaf`, `BibliotecaSsd`) e montar a biblioteca do zero (`Montagem`) |
| `kotlin/.../busca` | Tratamento da consulta, atalhos, dicionário PT→EN, índice dos PDFs |
| `kotlin/.../ia` | IA: trechos, pergunta, resposta e citações (`RegrasIa`), ponte com o llama.cpp (`MotorIa`) |
| `cpp/ia_jni.cpp` | Ponte JNI com o llama.cpp (só em 64 bits) |
| `kotlin/.../traducao` | Tradutor embutido e envio para outro tradutor |
| `kotlin/.../ui` | Telas em Compose |
| `assets/tradutor` | Motor e modelo de tradução da Mozilla (MPL-2.0); ver o `LEIA-ME.txt` da pasta |
| `cpp/gancho_fd.c` | Faz a libzim aceitar o descritor da SAF (ver abaixo) |

## Decisões que não estão na especificação

**Gancho nativo para o descritor da SAF.** `Archive(FileDescriptor)` da libkiwix 2.7.0 não usa o
descritor: a libzim reabre `/dev/fd/N`, e o Android nega (`Permission denied`) para arquivo em volume
externo sem permissão de armazenamento. O `gancho_fd.c` troca o `open`, só dentro da `libzim.so`, por um
`dup(N)` quando o caminho é `/dev/fd/N`. A alternativa seria pedir "acesso a todos os arquivos", que a
especificação proíbe. Se a libzim passar a usar `dup`, o gancho pode sair.

**Limite por servidor.** O `web.archive.org` responde 429 a pedidos seguidos. Ele recebe um pedido por
vez, com 15 s de intervalo. Qualquer servidor que responda 429 ou 503 entra em espera crescente (1 a 15 min).

**Espelho das fontes bloqueadas.** As fontes do Slim mínimo e do Slim que vinham de `web.archive.org`,
`trueprepper.com` e do WikEM estão no Release
[`fontes-celular-2026-11`](https://github.com/brenobernardes25-afk/biblioteca-offline/releases/tag/fontes-celular-2026-11)
(537 arquivos), criado com `--latest=false` porque o kit do PC depende de `releases/latest`. O
`assets/pacotes.json` do app é gerado por `ferramentas/espelhar_fontes.py`, que troca o `url` e guarda
o antigo em `url_original`. Rode o script de novo sempre que rodar o `montar_pacotes.py`:

```bash
python ferramentas/espelhar_fontes.py spec/bolso/pacotes.json app/src/main/assets/pacotes.json
```

Nem tudo foi para o Release. A situação de cada item, com o motivo, está em `ferramentas/espelho_lista.csv`:

- 16 obras de terceiros sem permissão de redistribuir: o celular baixa do site oficial do Ministério.
- 6 arquivos com distribuição restrita ou permissão duvidosa: continuam nos endereços originais, que
  recusam o download, e aparecem em "O que faltou".
- `caderneta_crianca_menina_2019.pdf`: saiu da lista, porque só existe cortada.

**PDFs cortados.** 60 PDFs do `D:` não têm final válido: muitas capturas do `web.archive.org` param em
exatamente 1 MiB. O tamanho na lista era o do arquivo cortado, então a conferência de tamanho não
acusava nada. `ferramentas/corrigidos.json` guarda o tamanho e o SHA-256 das 58 versões inteiras, e
`ferramentas/pdfs_cortados_no_D.csv` lista os 60. Em 2026-11-06 as 58 versões inteiras substituíram as
cortadas no `D:`, com as marcas `.ok` novas (registro em `ferramentas/conserto_D.csv`). Agora o app confere
o começo e o fim de cada PDF antes de aceitar (`Transferidor.pdfInteiro`) e manda para "O que faltou" o que
veio cortado.

O problema não se limitava à lista do celular. A varredura dos 54.266 PDFs do `5_Acervo` achou 903 sem final
válido. Em 2026-11-07, 885 deles foram trocados pela versão inteira (`ferramentas/conserto_D_acervo.csv`).
Os 18 que sobraram não têm versão melhor nas fontes, e o motivo de cada um está em
`ferramentas/acervo_pdfs_sem_final.csv`. Entre eles estão a `caderneta_crianca_menina_2019` e o `doc14FINAL`
da Embrapa.

**Kit do PC.** O código do `D:` e o kit publicado tinham se separado: o `D:` tinha o leitor de EPUB (01/11) e o kit
as correções de curl e certificados (04 e 05/11). Em 2026-11-07 as duas partes foram juntadas no `D:`, e o Release
`v2026-11-07` saiu de lá. O instalador agora baixa de novo um PDF com marca `.ok` que esteja menor que o da lista e
sem final válido: assim, quem instalou um kit antigo só precisa substituir o kit e rodar de novo.

**Baixar no computador.** `ferramentas/preparar_celular/` monta a pasta `BibliotecaDeBolso` (`PREPARAR_CELULAR.bat`,
em PowerShell, para não exigir Python). Se o HD da biblioteca estiver ligado, copia dele em vez de baixar. No app,
`Importador.kt` confere cada arquivo como num download e só então marca como pronto. A cópia roda com o app aberto e
a tela acesa, sem serviço em primeiro plano: se o Android interromper, basta escolher a pasta de novo. Rode
`gerar_lista.py` sempre que o `pacotes.json` mudar:

```bash
python ferramentas/preparar_celular/gerar_lista.py app/src/main/assets/pacotes.json
```

**Manual.** O do celular está em `ui/Manual.kt`, dentro do app, para funcionar sem internet. Só descreve o que o app
já faz: atualize junto com cada fase. O de quem baixa no computador é `ferramentas/preparar_celular/LEIA-ME_CELULAR.txt`.

**Índice dos PDFs.** `ferramentas/gerar_indice_celular.py` substitui o script da seção 7: lê as páginas por faixa de
rowid, porque filtrar `doc_id` varre o índice inteiro, e grava `pag_ini` e `pag_fim` também no índice do celular. As
22 obras sem permissão de redistribuir não levam o texto, porque o índice é público: são achadas só pelo título. Fica um
índice só para as duas versões (zip de 175 MB, 513 MB aberto), no Release `fontes-celular-2026-11`, e ele entra na lista
como item `tipo: indice`, no pacote mínimo. Para gerar uma versão nova, nesta ordem:

```bash
rm ferramentas/indice_info.json
python ferramentas/espelhar_fontes.py spec/bolso/pacotes.json app/src/main/assets/pacotes.json
python ferramentas/gerar_indice_celular.py app/src/main/assets/pacotes.json D:/ PASTA_SAIDA
python ferramentas/espelhar_fontes.py spec/bolso/pacotes.json app/src/main/assets/pacotes.json
python ferramentas/preparar_celular/gerar_lista.py app/src/main/assets/pacotes.json
```

**SQLite própria.** A seção 3.1 sugere `sqlite-android` do requery. Usei `androidx.sqlite:sqlite-bundled`, da própria
AndroidX, que traz FTS5, está no repositório do Google e não exige o JitPack.

**Busca nos PDFs.** Pede as páginas pela nota e fica com a melhor de cada manual. O trecho só é calculado para o que
aparece na tela. O manual com o assunto no título ganha um bônus. `Consulta.sinonimos` liga "picada de cobra" a "animais
peçonhentos", porque o manual do Ministério quase não usa essas palavras. Durante o download, se as melhores páginas
são de manuais que ainda não chegaram, a busca amplia o limite de páginas.

**Ler um SSD pronto (6.1).** A permissão guardada é só de leitura: o app não tem como alterar o SSD. Os .zim abrem pelo
descritor da SAF, usando o gancho nativo. O índice do PC vai para a pasta do app, com o `-wal` se houver, e só é copiado
de novo se tamanho ou data mudarem. O app abre até 184 .zim: cada um tem cache de 16 MB, a busca roda em no máximo 6
ao mesmo tempo, com 10 s por arquivo, e o que está em português vem primeiro.

**Tirar o SSD.** O Android encerra quem segura arquivo aberto num disco que está saindo. Por isso o receptor de
`MEDIA_EJECT` fecha tudo na hora, inclusive a cópia do descritor que o gancho abre dentro da libzim. O `DisposableEffect`
do leitor solta o PDF, e a montagem cancela as conexões. O receptor só reage ao volume do SSD: tirar um pendrive
qualquer não dá aviso.

**Montar do zero (6.2).** O SSD fica igual ao do `instalar.py`: `.part` durante o download, marca `.ok` com a origem no
fim e a mesma ordem de prioridade. Assim, dá para começar num e terminar no outro. O teste do SSD estica um arquivo até
passar de 4 GB, sem ocupar espaço: é o que separa o exFAT do FAT32. Em FAT32, o app explica como formatar num
computador, nunca formata, e oferece continuar sem os arquivos acima de 4 GB. Sem índice no SSD, os PDFs são achados
pelo nome do arquivo.

**Índice completo não publicado.** A seção 7 prevê publicar o `indice_completo` para quem monta o SSD pelo celular. O
índice tem o texto dos 54 mil documentos, e a permissão de redistribuir só foi conferida em parte deles. Sem ele, quem
montou pelo celular busca pelo nome do arquivo até abrir o SSD uma vez num computador, que cria o índice; depois disso,
o celular copia esse índice.

**Tradução.** Confirmei a restrição da seção 9: o ML Kit do Google é proprietário e não combina com a GPL-3.0. O app faz
as duas fases. A 3a entrega o texto ao tradutor do celular, oferecendo só apps que parecem tradutores, porque o TalkBack
também recebe `PROCESS_TEXT`. A 3b roda o motor Bergamot em WebAssembly, numa página sem tela (`index.html`), com
`blockNetworkLoads` ligado. Escolhi o WebAssembly para não compilar o motor C++ para cada arquitetura: ele já vem
pronto no pacote `@browsermt/bergamot-translator`. O modelo en-pt 2.1 veio do serviço do Firefox, com o SHA-256 conferido, porque
o LFS do repositório `firefox-translations-models` não entrega mais os arquivos. Modelo e motor ficam dentro do APK
(cerca de 28 MB a mais): funciona sem baixar nada, inclusive para quem só usa o SSD. O sentido português para inglês não está incluído.

**IA (seção 10).** O llama.cpp entra pelo `FetchContent` do CMake, na mesma versão do PC (b11889, com o SHA-256 do
pacote conferido), e só em arm64 e x86_64. O arm64 é compilado para armv8.2 com dotprod e fp16. O app confere o
processador antes de oferecer a IA e confere a RAM: só o Qwen3 4B, a partir de 7 GB no `totalMem` (celular de 8 GB). O Qwen3 1.7B,
para 6 GB, saiu em 2026-11-08: no teste, ele errou as quantidades do soro caseiro mesmo com a receita inteira no trecho.
Nesses celulares fica a busca, que mostra o texto original. A especificação ainda cita o 1.7B. As regras
de busca, o prompt e a nota mínima dos trechos são os do `ia.py` do PC. A diferença é que não há a etapa em que a IA
escolhe palavras-chave, porque ela é lenta demais no celular: o dicionário PT→EN faz esse papel. Os trechos têm
1.100 caracteres, mais que os 750 do PC, porque com 750 uma receita era cortada ao meio e a IA completava o resto.
Do SSD, o modelo é lido pelo descritor (`llama_model_load_from_file_ptr`), sem mmap e sem cópia. No celular, usa mmap.
O modelo sai da memória no `onStop` da tela principal e depois de 3 minutos parada. Citação para fonte que não existe é
apagada da resposta.

**Catálogo do Kiwix.** A consulta `?name=<kiwix_nome>` da seção 4 volta vazia quando o nome inclui o
sabor. O certo é `?name=wikipedia_pt_all&flavour=maxi`. O app tenta assim e depois pelo nome inteiro.

**Rede depois do download.** A seção 4 manda buscar o `pacotes.json` ao abrir, e o critério 14 proíbe
requisição depois de baixado. Regra adotada: o app só consulta o GitHub enquanto há download pendente
ou no botão "Atualizar biblioteca".

**Serviço em primeiro plano.** O Android pode recusá-lo depois de reiniciar o celular, e o Android 15
limita esse tipo de serviço a 6 h por dia. Quando recusa, o download segue como trabalho comum, em
janelas curtas, e volta ao primeiro plano quando o app é aberto.
