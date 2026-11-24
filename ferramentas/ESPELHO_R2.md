# Espelho da Biblioteca Offline (servidor próprio no Cloudflare R2)

Alguns sites de onde a biblioteca baixa os arquivos são frágeis:
- o **web.archive.org** bloqueia quem pede muito;
- o **Ministério da Saúde** recusa várias conexões;
- o **Organic Maps** apaga as versões antigas dos mapas.

Por isso guardamos uma cópia desses arquivos no nosso servidor (Cloudflare R2). O instalador e o app baixam do nosso servidor primeiro e usam o site original como reserva.

## O que vai e o que não vai

| Vai | Por quê |
|---|---|
| Ministério da Saúde (incluindo o que vem pelo web.archive.org) | bloqueia conexões e o web.archive.org trava |
| SciELO Livros | quase tudo só vinha pelo web.archive.org |
| Embrapa | site único, sem espelho |
| Exército Brasileiro (BDEx) | site único, sem espelho |
| Mapas (Organic Maps, Mapsforge) | o Organic Maps apaga versões antigas |
| Modelos de IA (Qwen3) | arquivos grandes; mais rápido do nosso servidor |
| Hesperian e sites pequenos | sites que mudam ou saem do ar |

| Não vai | Por quê |
|---|---|
| Kiwix (enciclopédias) | tem vários espelhos, e o instalador já troca de espelho sozinho |
| O que já está nos Releases do GitHub | já é cópia nossa |

## Fontes e créditos

Os arquivos são os mesmos publicados pelas fontes, **sem alteração**. **Nada é vendido**, e os direitos são de cada autor.

- **Ministério da Saúde** (bvsms.saude.gov.br): "é permitida a reprodução parcial ou total desta obra, desde que citada a fonte e que não seja para venda".
- **SciELO Livros** (books.scielo.org): acesso aberto, com a licença Creative Commons de cada obra.
- **Embrapa** (Infoteca-e): publicações da Embrapa; os direitos são da Embrapa e dos autores.
- **Exército Brasileiro** (bdex.eb.mil.br): o aviso varia de obra para obra e está na ficha de cada uma. O mais comum: "É permitida a reprodução do conteúdo da obra desde que seja, obrigatoriamente, citada a fonte. É proibida a reprodução para fins comerciais, bem como qualquer alteração no conteúdo da obra."
- **Mapas:** © colaboradores do OpenStreetMap, licença ODbL.
- **Modelos Qwen3** (Alibaba Cloud): licença Apache 2.0.
- **Hesperian Health Guides:** os direitos são da Hesperian.

A mesma lista fica no próprio servidor, em `index.html` (enviada com `espelho_r2.py creditos`).

## Como funciona

1. `python ferramentas/espelho_r2.py lista` mostra o que vai, quanto ocupa, o que falta no HD e o custo do mês.
2. `configurar` pede os dados do R2. Eles ficam só no PC, em `%USERPROFILE%\.biblioteca_offline`, e nunca vão para o repositório.
3. `enviar` manda do HD o que ainda não está no R2.
   - Antes de enviar, confere o SHA-256 da lista.
   - O R2 confere de novo na chegada.
   - Pode fechar no meio: na próxima vez, continua de onde parou (os arquivos grandes também, pedaço por pedaço).
4. `conferir` lista o que está no R2 e grava `computador/_Baixador/instalador/espelho.tsv`, só com o que chegou inteiro.
5. `aplicar` põe o espelho nas listas (`manifesto.tsv.gz` do PC e `celular/pacotes.json`). O `gerar_kit.py` faz o mesmo sozinho quando gera um kit novo.

Na lista do computador, um arquivo com cópia no espelho fica assim:
- **5ª coluna:** o espelho;
- **7ª coluna (reserva):** o endereço de antes;
- **8ª coluna:** outras reservas, separadas por espaço.

Instaladores e apps antigos só leem até a 7ª coluna, e continuam funcionando.

O instalador tenta, nesta ordem:
1. o espelho;
2. o site original;
3. os sites frágeis (web.archive.org) por último.

Se um servidor falha 8 vezes seguidas, ele vai para o fim da fila nesta rodada. Assim cada arquivo não fica esperando por ele.

O verificador semanal (`verificar_fontes.py`) testa até 8 mil arquivos do espelho por semana.

## Custo

- Armazenamento: US$ 0,015 por GB por mês, com os primeiros 10 GB grátis.
- Download: grátis.
- O domínio próprio custa à parte (US$ 11,17 por ano para um .com no Cloudflare). Sem ele, o endereço r2.dev tem limite de velocidade e é só para testes.

O total aparece em `espelho_r2.py lista`.
