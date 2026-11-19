#!/usr/bin/env python3
"""Espelho da Biblioteca Offline no Cloudflare R2 (servidor próprio).

Guarda no nosso servidor uma cópia do que é frágil (sites que bloqueiam, travam ou apagam versões antigas),
para o instalador baixar de lá primeiro e usar o site original como reserva. Não leva o Kiwix (tem vários
espelhos) nem o que já é cópia nossa no GitHub.

Roda no PC que tem a biblioteca completa (o HD), e só usa a biblioteca padrão do Python:

    python ferramentas/espelho_r2.py lista                  o que vai, quanto ocupa, o que falta no HD e o custo
    python ferramentas/espelho_r2.py configurar             pede os dados do R2 (uma vez; ficam só neste PC)
    python ferramentas/espelho_r2.py enviar                 envia o que falta (pode fechar e rodar de novo:
                                                            continua de onde parou)
    python ferramentas/espelho_r2.py enviar --grupo saude   só um grupo (ver "lista")
    python ferramentas/espelho_r2.py conferir               confere o que está no R2 e grava espelho.tsv
    python ferramentas/espelho_r2.py aplicar                põe o espelho na lista do PC e na do celular
    python ferramentas/espelho_r2.py creditos               envia a página de fontes e créditos

Antes de enviar, cada arquivo é conferido pelo SHA-256 da lista (manifesto). O R2 também confere: o envio
vai assinado com o SHA-256 do conteúdo, e o servidor recusa se chegar diferente.

Opções: --hd D:\\ (onde está a biblioteca), --manifesto CAMINHO, --tarefas 3 (envios ao mesmo tempo).
"""
import argparse
import concurrent.futures
import datetime
import gzip
import hashlib
import hmac
import html
import http.client
import json
import os
import re
import sys
import threading
import time
import urllib.parse
import xml.etree.ElementTree as ET

AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ_REPO = os.path.dirname(AQUI)
INSTALADOR = os.path.join(RAIZ_REPO, 'computador', '_Baixador', 'instalador')
MANIFESTO = os.path.join(INSTALADOR, 'manifesto.tsv.gz')
ESPELHO_TSV = os.environ.get('ESPELHO_TSV') or os.path.join(INSTALADOR, 'espelho.tsv')
PASTA_DADOS = os.environ.get('ESPELHO_DADOS') or os.path.join(os.environ.get('APPDATA') or os.path.expanduser('~'),
                                                               'BibliotecaOffline')
CONFIG = os.path.join(PASTA_DADOS, 'espelho_r2.json')            # chaves do R2: nunca vão para o repositório
ENVIADOS = os.path.join(PASTA_DADOS, 'espelho_enviados.tsv')     # diário do que já subiu
PARTES = os.path.join(PASTA_DADOS, 'espelho_partes.json')        # envios grandes pela metade

PARTE = 64 * 2**20          # envios acima de 2 partes vão em pedaços de 64 MB (retomáveis)
CUSTO_GB_MES = 0.015        # US$ por GB por mês (R2, armazenamento padrão); download não paga nada
GRATIS_GB = 10

# Servidores que NÃO vão para o espelho.
FORA = {
    'lb.download.kiwix.org': 'Kiwix: tem vários espelhos e o instalador já troca de espelho',
    'download.kiwix.org': 'Kiwix: tem vários espelhos e o instalador já troca de espelho',
    'github.com': 'já é cópia nossa (Releases do GitHub) ou programa publicado no GitHub',
    'www.python.org': 'site estável',
    'download.mozilla.org': 'endereço de "última versão" (o arquivo muda)',
}
# Grupo de cada servidor de origem (para web.archive.org, vale o site arquivado). O resto cai em "outros".
GRUPOS = {
    'bvsms.saude.gov.br': 'saude',
    'books.scielo.org': 'scielo',
    'www.infoteca.cnptia.embrapa.br': 'embrapa',
    'bdex.eb.mil.br': 'exercito',
    'cdn-us1.organicmaps.app': 'mapas',
    'download.mapsforge.org': 'mapas',
    'huggingface.co': 'ia',
    'hesperian.org': 'hesperian',
}
NOMES = {
    'saude': 'Ministério da Saúde', 'scielo': 'SciELO Livros', 'embrapa': 'Embrapa', 'exercito': 'Exército Brasileiro',
    'mapas': 'Mapas (OpenStreetMap)', 'ia': 'Modelos de IA (Qwen3)', 'hesperian': 'Hesperian', 'outros': 'Outros sites',
}
CREDITOS = [
    ('Ministério da Saúde (Biblioteca Virtual em Saúde, bvsms.saude.gov.br)',
     'Aviso das publicações: "é permitida a reprodução parcial ou total desta obra, desde que citada a fonte e que '
     'não seja para venda ou qualquer fim comercial".'),
    ('SciELO Livros (books.scielo.org)', 'Livros em acesso aberto, licença Creative Commons indicada em cada obra.'),
    ('Embrapa (Infoteca-e, www.infoteca.cnptia.embrapa.br)', 'Publicações da Embrapa, com crédito à fonte.'),
    ('Exército Brasileiro (Biblioteca Digital do Exército, bdex.eb.mil.br)',
     'Aviso dos manuais: "É permitida a reprodução do conteúdo da obra desde que seja, obrigatoriamente, citada a '
     'fonte. É proibida a reprodução para fins comerciais, bem como qualquer alteração no conteúdo da obra."'),
    ('Mapas: Organic Maps e Mapsforge, dados do OpenStreetMap',
     '© colaboradores do OpenStreetMap, licença ODbL (openstreetmap.org/copyright).'),
    ('Modelos de IA Qwen3 (Alibaba Cloud), via Hugging Face', 'Licença Apache 2.0.'),
    ('Hesperian Health Guides (hesperian.org)', 'Materiais de saúde de distribuição gratuita, com crédito à fonte.'),
    ('Internet Archive (web.archive.org)', 'Cópias arquivadas das páginas originais, de onde parte dos PDFs veio.'),
]
TIPOS = {'.pdf': 'application/pdf', '.epub': 'application/epub+zip', '.zip': 'application/zip',
         '.html': 'text/html; charset=utf-8', '.htm': 'text/html; charset=utf-8', '.txt': 'text/plain; charset=utf-8',
         '.jpg': 'image/jpeg', '.png': 'image/png', '.mp4': 'video/mp4', '.mp3': 'audio/mpeg'}


# ------------------------------------------------------------------ lista
def origem(url):
    """Servidor de onde o arquivo vem. Para web.archive.org, o do site arquivado."""
    m = re.match(r'https?://web\.archive\.org/web/[^/]+/(.+)$', url)
    if m:
        alvo = m.group(1)
        if not alvo.startswith('http'):
            alvo = 'http://' + alvo
        return urllib.parse.urlsplit(alvo).netloc.lower().split(':')[0], True
    return urllib.parse.urlsplit(url).netloc.lower(), False


def ler_manifesto(caminho=MANIFESTO):
    itens = []
    with gzip.open(caminho, 'rt', encoding='utf-8') as f:
        for linha in f:
            if linha.startswith('#') or not linha.strip():
                continue
            c = linha.rstrip('\n').split('\t')
            if len(c) < 5 or not c[4].startswith('http'):
                continue
            sha = c[5].strip().lower() if len(c) > 5 else ''
            itens.append({'rel': c[2], 'tam': int(c[3] or 0), 'url': c[4], 'sha': sha if len(sha) == 64 else ''})
    return itens


def escolher(itens):
    """(vão, ficam_de_fora). Cada item que vai ganha 'grupo' e 'host'."""
    vao, fora = [], []
    for i in itens:
        host, arquivado = origem(i['url'])
        real = urllib.parse.urlsplit(i['url']).netloc.lower()
        if real in FORA:
            fora.append((i, FORA[real]))
            continue
        i['host'] = host
        i['arquivado'] = arquivado
        i['grupo'] = GRUPOS.get(host, 'outros')
        vao.append(i)
    return vao, fora


def no_hd(hd, rel):
    return os.path.join(hd, *rel.split('/'))


def tamanhos_no_hd(hd, pastas):
    """{caminho: tamanho} de tudo o que está nestas pastas do HD (varredura rápida, sem abrir arquivo por arquivo)."""
    achados = {}

    def varrer(dir_abs, dir_rel):
        try:
            with os.scandir(dir_abs) as it:
                for e in it:
                    rel = dir_rel + '/' + e.name if dir_rel else e.name
                    if not e.is_dir(follow_symlinks=False):     # cada pasta da lista é lida só uma vez
                        try:
                            achados[rel] = e.stat(follow_symlinks=False).st_size
                        except OSError:
                            pass
        except OSError:
            pass
    for pasta in sorted(pastas):
        varrer(os.path.join(hd, *pasta.split('/')), pasta)
    return achados


def chave(rel):
    """Nome do arquivo no R2: o mesmo caminho da biblioteca."""
    return rel


def endereco_publico(base, rel):
    return base.rstrip('/') + '/' + urllib.parse.quote(chave(rel), safe='/~')


def gb(n):
    return n / 1e9


def cmd_lista(a):
    vao, fora = escolher(ler_manifesto(a.manifesto))
    enviados = ler_enviados()
    grupos = {}
    faltam, diferentes = [], []
    no_disco = tamanhos_no_hd(a.hd, {i['rel'].rsplit('/', 1)[0] for i in vao if '/' in i['rel']})
    for i in vao:
        g = grupos.setdefault(i['grupo'], {'n': 0, 't': 0, 'arq': 0, 'falta': 0, 'tfalta': 0, 'env': 0})
        g['n'] += 1
        g['t'] += i['tam']
        g['arq'] += i['arquivado']
        g['env'] += i['rel'] in enviados
        s = no_disco.get(i['rel'])
        if s is not None:
            if i['tam'] and s != i['tam']:
                diferentes.append((i, s))
        else:
            g['falta'] += 1
            g['tfalta'] += i['tam']
            faltam.append(i)
    print('\nO QUE VAI PARA O ESPELHO (R2)\n')
    print('%-24s %8s %10s %18s %16s %9s' % ('Grupo', 'Arquivos', 'Tamanho', 'via web.archive', 'Faltam no HD', 'Enviados'))
    tn = tt = 0
    for k, g in sorted(grupos.items(), key=lambda x: -x[1]['t']):
        print('%-24s %8d %7.1f GB %18d %8d (%4.1f GB) %9d' % (NOMES.get(k, k), g['n'], gb(g['t']), g['arq'], g['falta'],
                                                             gb(g['tfalta']), g['env']))
        tn += g['n']
        tt += g['t']
    print('%-24s %8d %7.1f GB' % ('TOTAL', tn, gb(tt)))
    custo = max(0, gb(tt) - GRATIS_GB) * CUSTO_GB_MES
    print('\nCusto estimado do R2: US$ %.2f por mês (%.0f GB × US$ %.3f, menos %d GB grátis). Download: grátis.'
          % (custo, gb(tt), CUSTO_GB_MES, GRATIS_GB))
    nfora = {}
    for i, motivo in fora:
        n, t = nfora.get(motivo, (0, 0))
        nfora[motivo] = (n + 1, t + i['tam'])
    print('\nFicam de fora:')
    for motivo, (n, t) in sorted(nfora.items(), key=lambda x: -x[1][1]):
        print('  %6d arquivos, %6.1f GB: %s' % (n, gb(t), motivo))
    if diferentes:
        print('\n%d arquivo(s) no HD com tamanho diferente da lista (não vão):' % len(diferentes))
        for i, s in diferentes[:20]:
            print('  %s (HD %d, lista %d)' % (i['rel'], s, i['tam']))
    if a.faltas:
        with open(a.faltas, 'w', encoding='utf-8') as f:
            for i in faltam:
                f.write('%s\t%d\t%s\t%s\n' % (i['rel'], i['tam'], i['url'], i['sha'] or '-'))
        print('\nLista do que falta no HD gravada em %s' % a.faltas)


# ------------------------------------------------------------------ R2 (S3 com assinatura AWS v4)
class R2:
    def __init__(self, cfg):
        self.conta = cfg['conta'].strip()
        self.id = cfg['chave'].strip()
        self.segredo = cfg['segredo'].strip()
        self.balde = cfg['balde'].strip()
        self.host = cfg.get('host') or '%s.r2.cloudflarestorage.com' % self.conta   # 'host' só para teste
        self.regiao = 'auto'
        self.seguro = not cfg.get('host', '').startswith('127.0.0.1')
        self.local = threading.local()

    def _con(self, nova=False):
        c = getattr(self.local, 'con', None)
        if c is None or nova:
            if c is not None:
                c.close()
            c = (http.client.HTTPSConnection if self.seguro else http.client.HTTPConnection)(self.host, timeout=300)
            self.local.con = c
        return c

    def _assinar(self, metodo, uri, query, cab, sha_corpo, agora=None):
        agora = agora or datetime.datetime.now(datetime.timezone.utc)
        amz = agora.strftime('%Y%m%dT%H%M%SZ')
        dia = amz[:8]
        cab = {k.lower(): str(v).strip() for k, v in cab.items()}
        cab['host'] = self.host
        cab['x-amz-date'] = amz
        cab['x-amz-content-sha256'] = sha_corpo
        q = '&'.join('%s=%s' % (urllib.parse.quote(k, safe='-_.~'), urllib.parse.quote(v, safe='-_.~'))
                     for k, v in sorted(query.items()))
        assinados = ';'.join(sorted(cab))
        canon = '\n'.join([metodo, uri, q, ''.join('%s:%s\n' % (k, cab[k]) for k in sorted(cab)), assinados, sha_corpo])
        escopo = '%s/%s/s3/aws4_request' % (dia, self.regiao)
        texto = '\n'.join(['AWS4-HMAC-SHA256', amz, escopo, hashlib.sha256(canon.encode()).hexdigest()])
        k = ('AWS4' + self.segredo).encode()
        for parte in (dia, self.regiao, 's3', 'aws4_request'):
            k = hmac.new(k, parte.encode(), hashlib.sha256).digest()
        assinatura = hmac.new(k, texto.encode(), hashlib.sha256).hexdigest()
        cab['authorization'] = 'AWS4-HMAC-SHA256 Credential=%s/%s, SignedHeaders=%s, Signature=%s' % (
            self.id, escopo, assinados, assinatura)
        return cab, q

    def pedir(self, metodo, nome=None, query=None, corpo=b'', cab=None, sha_corpo=None, tentativas=5):
        uri = '/' + urllib.parse.quote(self.balde, safe='') + ('/' + urllib.parse.quote(nome, safe='/~') if nome else '')
        query = query or {}
        sha_corpo = sha_corpo or hashlib.sha256(corpo).hexdigest()
        erro = None
        for n in range(tentativas):
            cab2, q = self._assinar(metodo, uri, query, dict(cab or {}), sha_corpo)
            try:
                c = self._con(nova=n > 0)
                c.request(metodo, uri + ('?' + q if q else ''), body=corpo or None, headers=cab2)
                r = c.getresponse()
                dados = r.read()
                if r.status >= 500 or r.status == 429:
                    raise IOError('R2 respondeu %d' % r.status)
                return r.status, {k.lower(): v for k, v in r.getheaders()}, dados
            except (OSError, http.client.HTTPException) as e:
                erro = e
                time.sleep(min(60, 3 * 2 ** n))
        raise IOError('R2 não respondeu (%s)' % erro)

    def info(self, nome):
        st, cab, _ = self.pedir('HEAD', nome)
        return cab if st == 200 else None

    def listar(self, prefixo=''):
        """Gera (nome, tamanho) de tudo o que está no balde."""
        token = None
        while True:
            q = {'list-type': '2', 'max-keys': '1000'}
            if prefixo:
                q['prefix'] = prefixo
            if token:
                q['continuation-token'] = token
            st, _, dados = self.pedir('GET', None, q)
            if st != 200:
                raise IOError('listar: R2 respondeu %d: %s' % (st, dados[:300]))
            raiz = ET.fromstring(dados)
            ns = raiz.tag.split('}')[0] + '}' if raiz.tag.startswith('{') else ''
            for o in raiz.findall(ns + 'Contents'):
                yield o.find(ns + 'Key').text, int(o.find(ns + 'Size').text)
            if (raiz.findtext(ns + 'IsTruncated') or '').lower() != 'true':
                return
            token = raiz.findtext(ns + 'NextContinuationToken')


def ler_config():
    try:
        with open(CONFIG, encoding='utf-8') as f:
            cfg = json.load(f)
        if all(cfg.get(k) for k in ('conta', 'chave', 'segredo', 'balde', 'publico')):
            return cfg
    except (OSError, ValueError):
        pass
    sys.exit('Falta configurar o R2. Rode:  python ferramentas/espelho_r2.py configurar')


def cmd_configurar(a):
    import getpass
    print('Dados do Cloudflare R2. Ficam só neste computador, em %s\n' % CONFIG)
    cfg = {}
    try:
        with open(CONFIG, encoding='utf-8') as f:
            cfg = json.load(f)
    except (OSError, ValueError):
        pass
    perguntas = [('conta', 'ID da conta (Account ID, 32 letras e números)'),
                 ('balde', 'Nome do balde (bucket), ex.: biblioteca-offline'),
                 ('publico', 'Endereço público do balde, ex.: https://arquivos.seudominio.com'),
                 ('chave', 'Access Key ID do token'), ('segredo', 'Secret Access Key do token (não aparece ao digitar)')]
    for k, texto in perguntas:
        atual = cfg.get(k, '')
        mostra = '' if not atual else (' [Enter mantém o atual]' if k == 'segredo' else ' [%s]' % atual)
        v = (getpass.getpass if k == 'segredo' else input)('%s%s: ' % (texto, mostra)).strip()
        if v:
            cfg[k] = v.rstrip('/') if k == 'publico' else v
    os.makedirs(PASTA_DADOS, exist_ok=True)
    with open(CONFIG, 'w', encoding='utf-8') as f:
        json.dump(cfg, f, indent=1)
    r2 = R2(cfg)
    st, _, dados = r2.pedir('GET', None, {'list-type': '2', 'max-keys': '1'})
    if st == 200:
        print('\nTudo certo: o balde respondeu.')
    else:
        print('\nO R2 recusou (%d). Confira os dados e rode "configurar" de novo.\n%s' % (st, dados[:300].decode('utf-8', 'replace')))


# ------------------------------------------------------------------ envio
_trava = threading.Lock()


def ler_enviados():
    feitos = {}
    try:
        with open(ENVIADOS, encoding='utf-8') as f:
            for linha in f:
                c = linha.rstrip('\n').split('\t')
                if len(c) >= 3:
                    feitos[c[0]] = (int(c[1]), c[2])
    except OSError:
        pass
    return feitos


def marcar_enviado(rel, tam, sha):
    with _trava:
        os.makedirs(PASTA_DADOS, exist_ok=True)
        with open(ENVIADOS, 'a', encoding='utf-8') as f:
            f.write('%s\t%d\t%s\t%s\n' % (rel, tam, sha, time.strftime('%Y-%m-%d %H:%M')))


def ler_partes():
    try:
        with open(PARTES, encoding='utf-8') as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def gravar_partes(estado):
    with _trava:
        tmp = PARTES + '.tmp'
        with open(tmp, 'w', encoding='utf-8') as f:
            json.dump(estado, f)
        os.replace(tmp, PARTES)


def sha256_arquivo(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for bloco in iter(lambda: f.read(8 * 2**20), b''):
            h.update(bloco)
    return h.hexdigest()


def cabecalhos(rel, sha):
    ext = os.path.splitext(rel)[1].lower()
    return {'content-type': TIPOS.get(ext, 'application/octet-stream'), 'x-amz-meta-sha256': sha}


def enviar_um(r2, hd, item, partes):
    """'ok', 'ja' ou uma frase com o problema."""
    rel = item['rel']
    p = no_hd(hd, rel)
    try:
        tam = os.stat(p).st_size
    except OSError:
        return 'falta no HD'
    if item['tam'] and tam != item['tam']:
        return 'tamanho no HD (%d) diferente da lista (%d)' % (tam, item['tam'])
    ja = r2.info(chave(rel))
    if ja and int(ja.get('content-length', -1)) == tam and (not item['sha'] or ja.get('x-amz-meta-sha256') == item['sha']):
        marcar_enviado(rel, tam, ja.get('x-amz-meta-sha256') or item['sha'] or '-')
        return 'ja'
    if tam <= 2 * PARTE:
        with open(p, 'rb') as f:
            dados = f.read()
        sha = hashlib.sha256(dados).hexdigest()
        if item['sha'] and sha != item['sha']:
            return 'SHA-256 do HD diferente da lista (arquivo estragado?)'
        st, _, resp = r2.pedir('PUT', chave(rel), corpo=dados, cab=cabecalhos(rel, sha), sha_corpo=sha)
        if st != 200:
            return 'R2 recusou (%d): %s' % (st, resp[:200].decode('utf-8', 'replace'))
        marcar_enviado(rel, tam, sha)
        return 'ok'
    # arquivo grande: confere o SHA-256 inteiro antes, depois envia em pedaços (retomável)
    sha = sha256_arquivo(p)
    if item['sha'] and sha != item['sha']:
        return 'SHA-256 do HD diferente da lista (arquivo estragado?)'
    est = partes.get(rel)
    if est and (est.get('tam') != tam or est.get('sha') != sha):
        r2.pedir('DELETE', chave(rel), {'uploadId': est['id']})
        est = None
    if not est:
        st, _, resp = r2.pedir('POST', chave(rel), {'uploads': ''}, cab=cabecalhos(rel, sha))
        if st != 200:
            return 'R2 recusou o início (%d): %s' % (st, resp[:200].decode('utf-8', 'replace'))
        raiz = ET.fromstring(resp)
        uid = next(e.text for e in raiz.iter() if e.tag.endswith('UploadId'))
        est = {'id': uid, 'tam': tam, 'sha': sha, 'feitas': {}}
        partes[rel] = est
        gravar_partes(partes)
    total = (tam + PARTE - 1) // PARTE
    with open(p, 'rb') as f:
        for n in range(1, total + 1):
            if str(n) in est['feitas']:
                continue
            f.seek((n - 1) * PARTE)
            dados = f.read(PARTE)
            st, cab, resp = r2.pedir('PUT', chave(rel), {'partNumber': str(n), 'uploadId': est['id']}, corpo=dados)
            if st == 404:      # o envio pela metade expirou no R2: recomeça
                partes.pop(rel, None)
                gravar_partes(partes)
                return 'envio pela metade expirou; rode de novo'
            if st != 200:
                return 'R2 recusou a parte %d (%d): %s' % (n, st, resp[:200].decode('utf-8', 'replace'))
            est['feitas'][str(n)] = cab.get('etag', '')
            gravar_partes(partes)
    corpo = ('<CompleteMultipartUpload>' + ''.join(
        '<Part><PartNumber>%d</PartNumber><ETag>%s</ETag></Part>' % (n, html.escape(est['feitas'][str(n)]))
        for n in range(1, total + 1)) + '</CompleteMultipartUpload>').encode()
    st, _, resp = r2.pedir('POST', chave(rel), {'uploadId': est['id']}, corpo=corpo)
    if st != 200 or b'<Error' in resp:
        return 'R2 recusou o fim (%d): %s' % (st, resp[:200].decode('utf-8', 'replace'))
    partes.pop(rel, None)
    gravar_partes(partes)
    ja = r2.info(chave(rel))
    if not ja or int(ja.get('content-length', -1)) != tam:
        return 'depois de enviado, o tamanho no R2 não confere'
    marcar_enviado(rel, tam, sha)
    return 'ok'


def cmd_enviar(a):
    cfg = ler_config()
    r2 = R2(cfg)
    vao, _ = escolher(ler_manifesto(a.manifesto))
    if a.grupo:
        vao = [i for i in vao if i['grupo'] in a.grupo]
    feitos = ler_enviados()
    fila = [i for i in vao if feitos.get(i['rel'], (None,))[0] != i['tam'] or not i['tam']]
    fila.sort(key=lambda i: (i['grupo'] != 'saude', i['grupo'] != 'scielo', i['rel']))
    if a.limite:
        fila = fila[:a.limite]
    total = sum(i['tam'] for i in fila)
    print('%d arquivos para enviar (%.1f GB); %d já estavam no R2.' % (len(fila), gb(total), len(vao) - len(fila)))
    partes = ler_partes()
    feito = [0, 0]
    problemas = []
    inicio = time.time()

    def um(item):
        try:
            res = enviar_um(r2, a.hd, item, partes)
        except Exception as e:      # noqa: BLE001 - segue com os outros e conta no fim
            res = 'erro: %s' % e
        with _trava:
            feito[0] += 1
            if res in ('ok', 'ja'):
                feito[1] += item['tam']
            else:
                problemas.append((item['rel'], res))
            if feito[0] % 25 == 0 or feito[0] == len(fila) or item['tam'] > 50e6:
                passou = time.time() - inicio
                vel = feito[1] / passou if passou else 0
                resta = (total - feito[1]) / vel if vel else 0
                print('  %d/%d arquivos, %.1f de %.1f GB, %.1f MB/s, faltam ~%s' % (
                    feito[0], len(fila), gb(feito[1]), gb(total), vel / 1e6, tempo(resta)), flush=True)
        return res

    with concurrent.futures.ThreadPoolExecutor(a.tarefas) as ex:
        list(ex.map(um, fila))
    print('\nPronto: %d enviados, %d com problema.' % (len(fila) - len(problemas), len(problemas)))
    if problemas:
        arq = os.path.join(PASTA_DADOS, 'espelho_problemas.tsv')
        with open(arq, 'w', encoding='utf-8') as f:
            for rel, res in problemas:
                f.write('%s\t%s\n' % (rel, res))
        for rel, res in problemas[:30]:
            print('  %s: %s' % (rel, res))
        print('Lista completa: %s' % arq)


def tempo(s):
    s = int(s)
    return '%dh%02d' % (s // 3600, s % 3600 // 60) if s >= 3600 else '%d min' % max(1, s // 60)


# ------------------------------------------------------------------ conferência e créditos
def cmd_conferir(a):
    """Lista o balde e grava espelho.tsv só com o que está lá inteiro (tamanho igual ao da lista)."""
    cfg = ler_config()
    r2 = R2(cfg)
    vao, _ = escolher(ler_manifesto(a.manifesto))
    no_r2 = dict(r2.listar())
    ok = [i for i in vao if no_r2.get(chave(i['rel'])) == i['tam'] and i['tam']]
    nomes_ok = {i['rel'] for i in ok}
    faltam = [i for i in vao if i['rel'] not in nomes_ok]
    with open(ESPELHO_TSV, 'w', encoding='utf-8', newline='\n') as f:
        f.write('# Arquivos com cópia no espelho da Biblioteca Offline (Cloudflare R2). Gerado por '
                'ferramentas/espelho_r2.py conferir em %s.\n' % time.strftime('%Y-%m-%d %H:%M'))
        f.write('# base\t%s\n' % cfg['publico'].rstrip('/'))
        for i in sorted(ok, key=lambda i: i['rel']):
            f.write('%s\n' % i['rel'])
    print('No R2: %d de %d arquivos da lista (%.1f GB). Gravado %s' % (len(ok), len(vao), gb(sum(i['tam'] for i in ok)),
                                                                       ESPELHO_TSV))
    if faltam:
        print('Ainda não estão no R2: %d (%.1f GB)' % (len(faltam), gb(sum(i['tam'] for i in faltam))))


def cmd_aplicar(a):
    """Põe o espelho nas listas publicadas, sem precisar gerar o kit de novo:
    manifesto.tsv.gz (computador) e celular/pacotes.json (app do celular)."""
    sys.path.insert(0, INSTALADOR)
    import espelho
    base, nomes = espelho.ler(ESPELHO_TSV)
    if not base:
        sys.exit('Sem espelho.tsv (rode "conferir" antes).')
    linhas, trocados = [], 0
    with gzip.open(a.manifesto, 'rt', encoding='utf-8') as f:
        for linha in f:
            linha = linha.rstrip('\n')
            if linha.startswith('#'):
                if '\treserva' in linha and 'mais_reservas' not in linha:
                    linha = linha.replace('\treserva', '\treserva\tmais_reservas', 1)
                linhas.append(linha)
                continue
            c = linha.split('\t')
            novo = espelho.aplicar(c, base, nomes)
            trocados += novo is not c
            linhas.append('\t'.join(novo))
    with gzip.open(a.manifesto + '.tmp', 'wt', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(linhas) + '\n')
    os.replace(a.manifesto + '.tmp', a.manifesto)
    print('Lista do computador: %d arquivos baixam do espelho.' % trocados)
    # app do celular: só itens com o mesmo arquivo do computador; o que já vem do GitHub continua de lá
    pac = a.pacotes
    if not os.path.exists(pac):
        return
    tam_pc = {i['rel']: i['tam'] for i in ler_manifesto(a.manifesto)}
    with open(pac, encoding='utf-8') as f:
        d = json.load(f)
    n = 0
    for i in d['itens']:
        rel = i.get('origem_pc', '')
        if (rel in nomes and i.get('tipo') != 'zim' and tam_pc.get(rel) == i.get('tam')
                and 'github.com/' not in i['url'] and not i['url'].startswith(base)):
            i.setdefault('url_original', i['url'])
            i['url'] = espelho.endereco(base, rel)
            n += 1
    if n:
        d['versao'] = time.strftime('%Y-%m-%d') + '-espelho'      # mais nova: o app aceita a lista
        with open(pac, 'w', encoding='utf-8', newline='\n') as f:
            json.dump(d, f, ensure_ascii=False, separators=(',', ':'))     # mesmo formato de espelhar_fontes.py
    print('App do celular: %d arquivos passam a baixar do espelho.' % n)


def pagina_creditos():
    linhas = ''.join('<li><b>%s</b><br>%s</li>' % (html.escape(n), html.escape(t)) for n, t in CREDITOS)
    return ('<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
            '<title>Biblioteca Offline: fontes e créditos</title>'
            '<style>body{font-family:system-ui,sans-serif;max-width:46rem;margin:2rem auto;padding:0 1rem;line-height:1.5;'
            'color:#222;background:#fff}li{margin:.8rem 0}</style>'
            '<h1>Biblioteca Offline: fontes e créditos</h1>'
            '<p>Este servidor guarda uma cópia de materiais públicos usados pela <a href="https://github.com/'
            'brenobernardes25-afk/biblioteca-offline">Biblioteca Offline</a>, um projeto gratuito de Breno Paiva '
            '(Instagram @breno.paiva40), feito para ajudar as pessoas em tempos difíceis. Os arquivos são os mesmos '
            'publicados pelas fontes abaixo, sem alteração. <b>Nada aqui é vendido</b>; os direitos são de cada autor.</p>'
            '<ul>%s</ul><p>É autor de alguma obra e quer que ela saia daqui? Abra uma issue no repositório do projeto.</p>'
            % linhas).encode('utf-8')


def cmd_creditos(a):
    r2 = R2(ler_config())
    corpo = pagina_creditos()
    for nome in ('index.html', 'CREDITOS.html'):
        st, _, resp = r2.pedir('PUT', nome, corpo=corpo, cab={'content-type': 'text/html; charset=utf-8'})
        print(nome, 'ok' if st == 200 else 'R2 recusou (%d): %s' % (st, resp[:200]))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('acao', choices=['lista', 'configurar', 'enviar', 'conferir', 'aplicar', 'creditos'])
    ap.add_argument('--hd', default='D:\\', help='onde está a biblioteca completa')
    ap.add_argument('--manifesto', default=MANIFESTO)
    ap.add_argument('--grupo', nargs='*', help='só estes grupos: ' + ', '.join(NOMES))
    ap.add_argument('--limite', type=int, default=0, help='no máximo N arquivos (teste)')
    ap.add_argument('--tarefas', type=int, default=3, help='envios ao mesmo tempo')
    ap.add_argument('--pacotes', default=os.path.join(RAIZ_REPO, 'celular', 'pacotes.json'),
                    help='(aplicar) lista do app do celular')
    ap.add_argument('--faltas', help='(lista) grava aqui o que falta no HD')
    a = ap.parse_args()
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    {'lista': cmd_lista, 'configurar': cmd_configurar, 'enviar': cmd_enviar, 'conferir': cmd_conferir,
     'aplicar': cmd_aplicar, 'creditos': cmd_creditos}[a.acao](a)


if __name__ == '__main__':
    main()
