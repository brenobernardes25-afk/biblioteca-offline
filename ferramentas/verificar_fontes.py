#!/usr/bin/env python3
"""Verificador de fontes da Biblioteca Offline.

Testa se os endereços de download ainda funcionam, para um arquivo que sumiu do servidor ser descoberto por
nós, e não por quem está instalando. Lê as duas listas do repositório:

  computador/_Baixador/instalador/manifesto.tsv.gz   (kit do computador: ~54 mil arquivos)
  celular/pacotes.json                               (app do celular: ~3,6 mil arquivos)

Não baixa nada: só pergunta ao servidor se o arquivo existe (HEAD) e, quando o servidor informa, se o tamanho
ainda é o da lista. Servidores com muitos arquivos são testados por amostra, que roda a cada semana, de modo que
em algumas semanas todos os arquivos passam pelo teste sem sobrecarregar ninguém.

Roda à mão (e pode rodar toda semana no GitHub com um workflow em .github/workflows/):

    python ferramentas/verificar_fontes.py                 (amostra da semana)
    python ferramentas/verificar_fontes.py --limite 20     (teste rápido: até 20 por servidor)
    python ferramentas/verificar_fontes.py --tudo          (todos; leva horas, use com cuidado)

Só usa a biblioteca padrão do Python.
"""
import argparse
import concurrent.futures
import datetime
import gzip
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MANIFESTO = os.path.join(RAIZ, 'computador', '_Baixador', 'instalador', 'manifesto.tsv.gz')
PACOTES = os.path.join(RAIZ, 'celular', 'pacotes.json')
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) BibliotecaOffline-verificador/1.0'

# Quantos arquivos testar por servidor em cada rodada, e a pausa entre um pedido e outro (segundos).
LIMITE_PADRAO = 300
REGRAS = {
    'web.archive.org': {'limite': 120, 'pausa': 3.0},                # recusa quem pede depressa (erro 429)
    'www.infoteca.cnptia.embrapa.br': {'limite': 600, 'pausa': 0.3},
    'github.com': {'limite': 600, 'pausa': 0.1},
}
# Endereços de "última versão": o tamanho muda de propósito.
TAMANHO_LIVRE = ('download.mozilla.org', 'product=firefox-latest')


def ler_listas():
    """[(lista, caminho, url, tamanho, tipo)] sem endereços repetidos."""
    itens, vistos = [], set()
    if os.path.exists(MANIFESTO):
        with gzip.open(MANIFESTO, 'rt', encoding='utf-8') as f:
            for linha in f:
                c = linha.rstrip('\n').split('\t')
                if linha.startswith('#') or len(c) < 5 or not c[4].startswith('http'):
                    continue
                if c[4] not in vistos:
                    vistos.add(c[4])
                    itens.append(('computador', c[2], c[4], int(c[3] or 0), c[1]))
    if os.path.exists(PACOTES):
        with open(PACOTES, encoding='utf-8') as f:
            for i in json.load(f).get('itens', []):
                url = i.get('url', '')
                if url.startswith('http') and url not in vistos:
                    vistos.add(url)
                    itens.append(('celular', i.get('destino') or i.get('id', ''), url, int(i.get('tam') or 0), i.get('tipo', '')))
    return itens


def servidor(url):
    host = urllib.parse.urlsplit(url).netloc.lower()
    return 'web.archive.org' if 'web.archive.org' in url else host


def amostra(lista, limite, semana):
    """Uma fatia diferente a cada semana; em ceil(N/limite) semanas, todos passam."""
    if limite <= 0 or len(lista) <= limite:
        return lista
    partes = -(-len(lista) // limite)
    return [x for n, x in enumerate(lista) if n % partes == semana % partes]


def pedir(url, metodo):
    cab = {'User-Agent': UA, 'Accept-Encoding': 'identity'}
    if metodo == 'GET':
        cab['Range'] = 'bytes=0-0'
    req = urllib.request.Request(url, method=metodo, headers=cab)
    with urllib.request.urlopen(req, timeout=60) as r:
        # No HEAD, o Content-Length é o tamanho do arquivo; no GET de 1 byte, o tamanho vem no Content-Range.
        tam = r.headers.get('Content-Length') if metodo == 'HEAD' else None
        faixa = r.headers.get('Content-Range') or ''
        if metodo == 'GET' and '/' in faixa:
            tam = faixa.rsplit('/', 1)[-1].strip()
        orig = r.headers.get('x-archive-orig-content-length')     # web.archive.org: tamanho da cópia guardada
        if 'text/html' in (r.headers.get('Content-Type') or '').lower():
            tam = None      # página de espera (ex.: SourceForge) no lugar do arquivo: o tamanho não diz nada
        return (r.status, int(tam) if tam and tam.isdigit() else None,
                int(orig) if orig and orig.isdigit() else None)


def conferir(item):
    """Devolve (situação, detalhe). Situações: ok, tamanho, sumiu, ocupado, erro."""
    lista, rel, url, tam, tipo = item
    ultimo = ''
    for tentativa in range(1, 4):
        for metodo in ('HEAD', 'GET'):
            try:
                status, achado, original = pedir(url, metodo)
                achado = achado or original
                if tam and achado and achado != tam and tipo != 'zim' and not any(x in url for x in TAMANHO_LIVRE):
                    return 'tamanho', 'a lista diz %d bytes e o servidor tem %d' % (tam, achado)
                return 'ok', ''
            except urllib.error.HTTPError as e:
                ultimo = 'erro %d' % e.code
                if e.code in (404, 410):
                    return 'sumiu', ultimo
                if e.code in (403, 405, 501) and metodo == 'HEAD':
                    continue                       # alguns servidores não aceitam HEAD: tenta um GET de 1 byte
                if e.code in (429, 500, 502, 503, 504):
                    try:
                        espera = min(120, int(e.headers.get('Retry-After') or 0))
                    except (TypeError, ValueError):
                        espera = 0
                    time.sleep(max(espera, 20 * tentativa))
                    break
                return 'erro', ultimo
            except Exception as e:
                ultimo = str(getattr(e, 'reason', None) or e.__class__.__name__)[:80]
                time.sleep(5 * tentativa)
                break
    return ('ocupado' if ultimo.startswith('erro 429') or ultimo.startswith('erro 50') else 'erro'), ultimo


def versao_nova_no_kiwix(nome_arquivo):
    """Nome da versão mais nova da mesma enciclopédia no catálogo do Kiwix, ou ''."""
    m = re.match(r'^(.+?)_(\d{4}-\d{2})[a-z]?\.zim$', nome_arquivo)
    if not m:
        return ''
    try:
        url = 'https://library.kiwix.org/catalog/v2/entries?count=50&name=' + urllib.parse.quote(m.group(1))
        with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': UA}), timeout=60) as r:
            xml = r.read().decode('utf-8', 'replace')
    except Exception:
        return '?'
    achados = sorted(set(re.findall(r'/([^/"]+?_\d{4}-\d{2}[a-z]?)\.zim', xml)))
    achados = [a for a in achados if a.rsplit('_', 1)[0] == m.group(1)]
    return achados[-1] + '.zim' if achados else ''


def testar_servidor(host, lista, pausa):
    saida = []
    for item in lista:
        saida.append((item,) + conferir(item))
        time.sleep(pausa)
    return saida


def main():
    ap = argparse.ArgumentParser(description='Testa os endereços de download da Biblioteca Offline.')
    ap.add_argument('--limite', type=int, default=None, help='máximo de arquivos por servidor nesta rodada')
    ap.add_argument('--tudo', action='store_true', help='testa todos os arquivos (leva horas)')
    ap.add_argument('--saida', default=os.path.join(RAIZ, 'relatorio_fontes.md'))
    ap.add_argument('--so', default='', help='testa só os servidores cujo nome contém este texto')
    a = ap.parse_args()

    itens = ler_listas()
    semana = datetime.date.today().isocalendar()[1]
    por_host = {}
    for i in itens:
        por_host.setdefault(servidor(i[2]), []).append(i)
    plano = {}
    for host, lista in por_host.items():
        if a.so and a.so not in host:
            continue
        regra = REGRAS.get(host, {})
        limite = 0 if a.tudo else (a.limite if a.limite is not None else regra.get('limite', LIMITE_PADRAO))
        plano[host] = (amostra(lista, limite, semana), regra.get('pausa', 0.5))
    total = sum(len(l) for l, _ in plano.values())
    print('Listas: %d endereços em %d servidores. Nesta rodada: %d.' % (len(itens), len(por_host), total), flush=True)

    resultados = []
    t0 = time.time()
    with concurrent.futures.ThreadPoolExecutor(12) as ex:      # um fio por servidor: ninguém recebe pedidos em rajada
        futuros = {ex.submit(testar_servidor, h, l, p): h for h, (l, p) in plano.items()}
        for fut in concurrent.futures.as_completed(futuros):
            r = fut.result()
            resultados += r
            ruins = sum(1 for x in r if x[1] not in ('ok',))
            print('  %-40s %5d testados, %d com problema (%.0f s)' % (futuros[fut], len(r), ruins, time.time() - t0), flush=True)

    sumiram = [r for r in resultados if r[1] == 'sumiu']
    trocas, fora = [], []
    for r in sumiram:
        item = r[0]
        if item[4] == 'zim':
            nova = versao_nova_no_kiwix(item[2].rsplit('/', 1)[-1])
            (trocas if nova and nova != '?' else fora).append(r + (nova,))
        else:
            fora.append(r + ('',))
    tamanhos = [r for r in resultados if r[1] == 'tamanho']
    erros = [r for r in resultados if r[1] == 'erro']
    ocupados = [r for r in resultados if r[1] == 'ocupado']
    quebrados = len(fora) + len(tamanhos)

    L = ['# Verificador de fontes: %s' % datetime.date.today().strftime('%d/%m/%Y'), '',
         '%d endereços testados de %d (amostra da semana %d). **%d precisam de atenção.**' % (
             len(resultados), len(itens), semana, quebrados), '',
         '| Servidor | Na lista | Testados | Sumiram | Tamanho mudou | Erro | Ocupado |', '|---|---:|---:|---:|---:|---:|---:|']
    for host in sorted(plano, key=lambda h: -len(por_host[h])):
        rs = [r for r in resultados if servidor(r[0][2]) == host]
        conta = lambda s: sum(1 for r in rs if r[1] == s)   # noqa: E731
        L.append('| %s | %d | %d | %d | %d | %d | %d |' % (host, len(por_host[host]), len(rs), conta('sumiu'),
                                                         conta('tamanho'), conta('erro'), conta('ocupado')))

    def bloco(titulo, explicacao, linhas, fmt, maximo=150):
        if not linhas:
            return
        L.extend(['', '## %s (%d)' % (titulo, len(linhas)), '', explicacao, ''])
        for r in linhas[:maximo]:
            L.append(fmt(r))
        if len(linhas) > maximo:
            L.append('- … e mais %d.' % (len(linhas) - maximo))

    bloco('Sumiram do servidor', 'Quem instalar hoje fica sem estes arquivos. Achar outro endereço, publicar uma cópia '
          'nossa (se a licença deixar) ou tirar da lista.', fora,
          lambda r: '- `%s` (%s) — %s — %s' % (r[0][1], r[0][0], r[2], r[0][2]))
    bloco('Tamanho diferente do da lista', 'O arquivo foi trocado no servidor, ou a nossa cópia estava cortada. '
          'Conferir e gerar a lista de novo.', tamanhos,
          lambda r: '- `%s` (%s) — %s — %s' % (r[0][1], r[0][0], r[2], r[0][2]))
    bloco('Enciclopédias que trocaram de versão', 'Não é problema para quem instala: o instalador e o app pegam a '
          'versão nova sozinhos. Vale atualizar a lista na próxima geração do kit.', trocas,
          lambda r: '- `%s` → `%s`' % (r[0][1].rsplit('/', 1)[-1], r[3]))
    bloco('Erro ao testar', 'O servidor não respondeu direito agora. Só preocupa se repetir por semanas.', erros,
          lambda r: '- `%s` (%s) — %s' % (r[0][1], r[0][0], r[2]), maximo=40)
    if ocupados:
        L.extend(['', '%d endereços ficaram sem teste porque o servidor pediu para esperar (não é quebra).' % len(ocupados)])
    texto = '\n'.join(L) + '\n'
    if len(texto) > 60000:                          # limite do corpo de uma issue no GitHub
        texto = texto[:60000] + '\n\n(relatório cortado: grande demais)\n'
    with open(a.saida, 'w', encoding='utf-8', newline='\n') as f:
        f.write(texto)
    print('\n' + '\n'.join(L[:14]))
    print('\nRelatório: %s · precisam de atenção: %d' % (a.saida, quebrados))
    if os.environ.get('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as f:
            f.write('quebrados=%d\n' % quebrados)


if __name__ == '__main__':
    main()
