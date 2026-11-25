"""Atualização do PEN DRIVE (botão "Atualizar pela internet" do app, quando a biblioteca está num pen drive).

O pen drive tem uma seleção feita à mão, com a lista de fábrica em App/dados/lista_de_fabrica.tsv
(SHA-256, tamanho, caminho; é a mesma que o "3 - VERIFICAR" confere). Por isso aqui NÃO rodamos os baixadores
do HD completo: eles trariam a Embrapa inteira e encheriam o pen drive. Em vez disso:

  1. conserta: o que está na lista de fábrica e sumiu ou ficou com tamanho errado é baixado de novo,
     do nosso servidor primeiro (espelho) e, se falhar, do site original;
  2. novidades: baixa o que estiver em <servidor>/pendrive/novidades.tsv e ainda não estiver igual no pen drive
     (linhas "sha256<TAB>tamanho<TAB>caminho[<TAB>endereço]"; sem endereço, baixa do servidor, no mesmo caminho).

Tudo que chega é conferido pelo SHA-256 antes de entrar no lugar e vai para a lista de fábrica. As enciclopédias
continuam com o atualizar.py (versão nova das que já estão no pen drive); aqui só acertamos a lista depois.
"""
import gzip
import hashlib
import os
import re
import shutil
import time
import urllib.error
import urllib.request

import sys

AQUI = os.path.dirname(os.path.abspath(__file__))
for _p in (AQUI, os.path.join(AQUI, 'instalador')):
    if _p not in sys.path:
        sys.path.insert(0, _p)      # o Python roda com -I: acha baixa_seguro.py e instalador/espelho.py
import baixa_seguro  # noqa: E402
import espelho  # noqa: E402

RAIZ = os.path.dirname(AQUI)
LISTA = os.path.join(RAIZ, 'App', 'dados', 'lista_de_fabrica.tsv')
MANIFESTO = os.path.join(AQUI, 'instalador', 'manifesto.tsv.gz')
NOVIDADES = 'pendrive/novidades.tsv'
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) BibliotecaOffline/1.0'
COM_MARCA = ('1_Enciclopedia/', '2_Mapas/', '4_IA/', '5_Acervo/', '6_Instaladores/')   # pastas com marca .ok
_NOME_ZIM = re.compile(r'^(.+?)(?:_(maxi|nopic|mini))?_(\d{4}-\d{2})[a-z]?\.zim$')


def e_pen_drive(raiz=RAIZ):
    """Mesmo teste do app (config.PEN_DRIVE), mais a lista de fábrica."""
    atalho = any(os.path.isfile(os.path.join(raiz, '1 - ABRIR NO COMPUTADOR' + e)) for e in ('.exe', '.bat'))
    return atalho and os.path.isfile(os.path.join(raiz, 'App', 'dados', 'lista_de_fabrica.tsv'))


# ------------------------------------------------------------------ lista de fábrica

def ler_lista():
    linhas = []
    with open(LISTA, encoding='utf-8') as f:
        for l in f:
            p = l.rstrip('\n').split('\t')
            if len(p) == 3 and p[1].isdigit():
                linhas.append(p)
    return linhas


def gravar_lista(linhas):
    tmp = LISTA + '.novo'
    with open(tmp, 'w', encoding='utf-8', newline='\n') as f:
        f.writelines('%s\t%s\t%s\n' % tuple(l) for l in linhas)
    os.replace(tmp, LISTA)


def sha256(caminho):
    h = hashlib.sha256()
    with open(caminho, 'rb', buffering=0) as f:
        for b in iter(lambda: f.read(4 << 20), b''):
            h.update(b)
    return h.hexdigest()


def no_disco(rel):
    return os.path.join(RAIZ, *rel.split('/'))


def caminho_seguro(rel):
    """Só caminhos dentro do pen drive (nada de '..', letra de disco ou barra no começo)."""
    partes = rel.replace('\\', '/').split('/')
    return bool(rel) and not rel.startswith('/') and ':' not in rel and '..' not in partes and '' not in partes


# ------------------------------------------------------------------ de onde baixar

def fontes():
    """(base do espelho, nomes no espelho, {caminho: [endereços do manifesto]})."""
    base, nomes = espelho.ler()
    man = {}
    try:
        with gzip.open(MANIFESTO, 'rt', encoding='utf-8') as f:
            for l in f:
                if l.startswith('#'):
                    continue
                c = l.rstrip('\n').split('\t')
                if len(c) >= 5:
                    urls = [c[4]] + (c[6:7] if len(c) > 6 else []) + (c[7].split() if len(c) > 7 else [])
                    man[c[2]] = [u for u in urls if u.startswith('http')]
    except OSError:
        pass
    return base, nomes, man


def enderecos(rel, base, nomes, man, extra='', novidade=False):
    urls = []
    if extra.startswith('http'):
        urls.append(extra)
    if base and (rel in nomes or (novidade and not extra)):
        urls.append(espelho.endereco(base, rel))   # novidade sem endereço: está no servidor, no mesmo caminho
    urls += man.get(rel, [])
    return [u for n, u in enumerate(urls) if u not in urls[:n]]


def ler_novidades(base):
    """Lista de novidades do servidor: [(sha, tam, rel, endereço)]; [] se não há; None se não deu para ler."""
    if not base:
        return []
    url = espelho.endereco(base, NOVIDADES)
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': UA}), timeout=60) as r:
            texto = r.read().decode('utf-8-sig')
    except urllib.error.HTTPError as e:
        return [] if e.code in (403, 404) else None
    except Exception:
        return None
    itens = []
    for l in texto.splitlines():
        p = l.strip().split('\t')
        if l.startswith('#') or len(p) < 3 or not re.fullmatch(r'[0-9a-f]{64}', p[0]) or not p[1].isdigit():
            continue
        if caminho_seguro(p[2]):
            itens.append((p[0], int(p[1]), p[2].replace('\\', '/'), p[3] if len(p) > 3 else ''))
    return itens


# ------------------------------------------------------------------ o que falta

def pendencias(lista, novidades):
    """(consertar, novos): itens {'sha','tam','rel','extra','motivo'} a baixar."""
    consertar = []
    for h, tam, rel in lista:
        p = no_disco(rel)
        if not os.path.exists(p):
            motivo = 'sumiu'
        elif os.path.getsize(p) != int(tam):
            motivo = 'tamanho errado'
        else:
            continue
        consertar.append({'sha': h, 'tam': int(tam), 'rel': rel, 'extra': '', 'motivo': motivo})
    na_lista = {rel: h for h, _, rel in lista}
    novos = []
    for h, tam, rel, extra in novidades or []:
        p = no_disco(rel)
        igual = na_lista.get(rel) == h and os.path.exists(p) and os.path.getsize(p) == tam
        if not igual:
            consertar = [c for c in consertar if c['rel'] != rel]   # a novidade vale mais que a versão antiga
            novos.append({'sha': h, 'tam': tam, 'rel': rel, 'extra': extra, 'motivo': 'novidade'})
    return consertar, novos


def baixar_item(item, urls, reserva_bytes, escreve):
    """Baixa para <arquivo>.baixando, confere tamanho e SHA-256 e só então põe no lugar. Devolve (ok, motivo)."""
    destino = no_disco(item['rel'])
    tmp = destino + '.baixando'
    if not urls:
        return False, 'sem cópia na internet'
    os.makedirs(os.path.dirname(destino), exist_ok=True)
    for url in urls:
        ja = os.path.getsize(tmp) if os.path.exists(tmp) else 0
        if ja > item['tam']:
            os.remove(tmp)
            ja = 0
        if shutil.disk_usage(RAIZ).free - (item['tam'] - ja) < reserva_bytes:
            return False, 'sem espaço'
        codigo = baixa_seguro.baixar(url, tmp, UA)
        if not os.path.exists(tmp):
            continue
        tam = os.path.getsize(tmp)
        if codigo != 0 and tam < item['tam']:
            continue        # interrompido: o pedaço fica para continuar da próxima vez (ou no próximo endereço)
        if tam != item['tam'] or sha256(tmp) != item['sha']:
            escreve('Veio diferente do esperado, tentando outro endereço: %s' % item['rel'])
            os.remove(tmp)
            continue
        try:
            if os.path.exists(destino):
                os.chmod(destino, 0o666)
            os.replace(tmp, destino)
        except OSError as e:
            return False, 'não consegui gravar (%s)' % e.strerror
        if item['rel'].startswith(COM_MARCA):
            baixa_seguro.marcar_ok(destino, url + '\n')
        return True, ''
    return False, 'não baixou'


def _trocar_na_lista(lista, item):
    for l in lista:
        if l[2] == item['rel']:
            l[0], l[1] = item['sha'], str(item['tam'])
            return
    lista.append([item['sha'], str(item['tam']), item['rel']])


# ------------------------------------------------------------------ enciclopédias

def acertar_zims(atualizadas=(), saindo=(), escreve=print):
    """Depois de o atualizar.py trocar enciclopédias: a versão nova entra na lista no lugar da antiga, para o
    VERIFICAR não acusar a antiga como "sumiu". atualizadas = versões novas baixadas agora; saindo = antigas que o
    app ainda vai apagar. Também acerta trocas de uma vez anterior que foi interrompida."""
    lista = ler_lista()
    pasta = os.path.join(RAIZ, '1_Enciclopedia')
    try:
        no_pen = {f for f in os.listdir(pasta) if f.endswith('.zim') and os.path.exists(os.path.join(pasta, f + '.ok'))}
    except OSError:
        return 0
    na_lista = {l[2].split('/', 1)[1] for l in lista if l[2].startswith('1_Enciclopedia/')}

    def chave(arq):
        m = _NOME_ZIM.match(arq)
        return (m.group(1), m.group(2) or '') if m else None

    novas = {}
    for f in sorted(no_pen - na_lista):
        if chave(f):
            novas.setdefault(chave(f), []).append(f)
    trocas = 0
    for l in lista:
        if not l[2].startswith('1_Enciclopedia/'):
            continue
        antigo = l[2].split('/', 1)[1]
        candidatas = [c for c in novas.get(chave(antigo), []) if c > antigo]
        if not candidatas:
            continue
        nova = candidatas[-1]
        if not (antigo not in no_pen or antigo in saindo or nova in atualizadas):
            continue        # a antiga continua no pen drive e ninguém pediu a troca
        escreve('Lista do VERIFICAR: %s entra no lugar de %s (conferindo...)' % (nova, antigo))
        p = os.path.join(pasta, nova)
        l[0], l[1], l[2] = sha256(p), str(os.path.getsize(p)), '1_Enciclopedia/' + nova
        novas[chave(antigo)].remove(nova)
        trocas += 1
    if trocas:
        gravar_lista(lista)
    return trocas


# ------------------------------------------------------------------ principal

def verificar(escreve=print):
    base, nomes, man = fontes()
    nov = ler_novidades(base)
    consertar, novos = pendencias(ler_lista(), nov)
    sem_copia = [c for c in consertar if not enderecos(c['rel'], base, nomes, man)]
    return {'pen_drive': True, 'consertar': len(consertar) - len(sem_copia), 'sem_copia': len(sem_copia),
            'novidades': len(novos), 'gb': round(sum(i['tam'] for i in consertar + novos) / 2**30, 2),
            'servidor_ok': nov is not None}


def atualizar(reserva_gb, escreve=print, estado=lambda **k: None):
    base, nomes, man = fontes()
    nov = ler_novidades(base)
    if nov is None:
        escreve('Não consegui ler a lista de novidades do servidor; só vou consertar o que faltar.')
    lista = ler_lista()
    consertar, novos = pendencias(lista, nov)
    fila = novos + consertar
    escreve('Pen drive: %d novidades e %d arquivos para consertar.' % (len(novos), len(consertar)))
    r = {'pen_drive': True, 'novos_arquivos': 0, 'consertados': 0, 'sem_copia': [], 'sem_espaco': [], 'falhas': [],
         'erros': [] if nov is not None else ['lista de novidades do servidor']}
    ultima_gravacao, mudou = time.time(), False
    for i, item in enumerate(fila, 1):
        estado(etapa='Pen drive: %d de %d' % (i, len(fila)), progresso=round(i / len(fila), 3))
        urls = enderecos(item['rel'], base, nomes, man, item['extra'], item['motivo'] == 'novidade')
        ok, motivo = baixar_item(item, urls, reserva_gb * 2**30, escreve)
        if ok:
            _trocar_na_lista(lista, item)
            mudou = True
            r['novos_arquivos' if item['motivo'] == 'novidade' else 'consertados'] += 1
            escreve('%s: %s' % ('Novidade' if item['motivo'] == 'novidade' else 'Consertado', item['rel']))
        else:
            r['sem_copia' if motivo == 'sem cópia na internet' else 'sem_espaco' if motivo == 'sem espaço'
              else 'falhas'].append(item['rel'])
            escreve('Ficou faltando (%s): %s' % (motivo, item['rel']))
        if mudou and time.time() - ultima_gravacao > 30:
            gravar_lista(lista)
            ultima_gravacao = time.time()
    if mudou:
        gravar_lista(lista)
    if r['falhas']:
        r['erros'].append('%d arquivos não baixaram' % len(r['falhas']))
    estado(progresso=None)
    return r
