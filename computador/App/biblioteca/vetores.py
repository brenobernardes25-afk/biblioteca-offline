"""Busca por significado: a pergunta vira um vetor (modelo EmbeddingGemma no llama-server, só processador) e é
comparada com os vetores de todas as páginas, feitos de antemão na placa de vídeo (App/dados/vetores*.bin).

Acha a página certa mesmo com outras palavras ("água turva" -> "potabilidade") e em outra língua (pergunta em
português -> manual em inglês). Nas 30 perguntas de teste: 0,75 de precisão nas 10 primeiras, contra 0,48 da busca
por palavras. Se faltar qualquer peça (numpy, vetores, modelo, llama-server), disponivel() é False e o app segue só
com a busca por palavras.

Arquivos (gerados por _TREINO_BUSCA/gerar_vetores.py):
  vetores.bin         int8, N x dim     vetores_escala.bin  float32, N (vetor ~= int8 * escala)
  vetores_ids.bin     uint32, N (rowid da tabela pag)      vetores.json  modelo, dimensão, prefixos
"""
import http.client
import json
import os
import threading
import time

from . import config, sistema

log = config.log

PREFIXO = 'task: search result | query: '
_BLOCO = 32768               # linhas por vez na comparação (~100 MB de memória temporária)
_estado = {'processo': None, 'porta': None, 'erro': '', 'motivo': ''}
_lock_servidor = threading.Lock()
_lock_dados = threading.Lock()
_dados = {}
_cache = {}                  # consulta -> (ids, notas): a IA busca a mesma pergunta várias vezes


def _np():
    try:
        import numpy
        return numpy
    except Exception:
        return None


def _arq(nome):
    return os.path.join(config.DADOS, nome)


def achar_modelo():
    for p in sistema.achar('4_IA/**/*embedding*.gguf'):
        if os.path.isfile(p) and sistema.arquivo_pronto(p):
            return p
    return None


def achar_servidor():
    for p in sistema.achar('Programas/LlamaCpp/**/llama-server.exe'):
        if os.path.isfile(p):
            return p
    return None


def disponivel():
    """Há tudo para a busca por significado? (não liga nada)"""
    if os.environ.get('BIBLIOTECA_SEM_VETORES') == '1':
        _estado['motivo'] = 'desligada (BIBLIOTECA_SEM_VETORES=1)'
        return False
    if _estado['erro']:
        return False
    faltam = [n for n in ('vetores.json', 'vetores.bin', 'vetores_escala.bin', 'vetores_ids.bin') if not os.path.exists(_arq(n))]
    if faltam:
        _estado['motivo'] = 'faltam ' + ', '.join(faltam)
        return False
    if _np() is None:
        _estado['motivo'] = 'falta o numpy em App\\vendor'
        return False
    if not achar_modelo():
        _estado['motivo'] = 'falta o modelo *embedding*.gguf em 4_IA'
        return False
    if not achar_servidor():
        _estado['motivo'] = 'falta o llama-server em Programas\\LlamaCpp'
        return False
    return True


def _carregar():
    with _lock_dados:
        if _dados:
            return _dados
        np = _np()
        meta = json.load(open(_arq('vetores.json'), encoding='utf-8'))
        dim, n = int(meta['dimensao']), int(meta['total'])
        modelo = achar_modelo()
        if meta.get('modelo') and os.path.basename(modelo) != meta['modelo']:
            log.warning('vetores feitos com %s, mas o modelo em 4_IA é %s', meta['modelo'], os.path.basename(modelo))
        vet = np.memmap(_arq('vetores.bin'), dtype=np.int8, mode='r', shape=(n, dim))
        esc = np.fromfile(_arq('vetores_escala.bin'), dtype=np.float32)
        ids = np.fromfile(_arq('vetores_ids.bin'), dtype=np.uint32)
        if len(esc) != n or len(ids) != n:
            raise ValueError('vetores incompletos: %d vetores, %d escalas, %d ids' % (n, len(esc), len(ids)))
        _dados.update(vet=vet, esc=esc, ids=ids, n=n, dim=dim, prefixo=meta.get('prefixo_pergunta') or PREFIXO)
        log.info('busca por significado: %d páginas, %d dimensões', n, dim)
        return _dados


def _req(corpo, timeout=60):
    c = http.client.HTTPConnection('127.0.0.1', _estado['porta'], timeout=timeout)
    try:
        c.request('POST', '/v1/embeddings', json.dumps(corpo), {'Content-Type': 'application/json'})
        r = c.getresponse()
        dados = r.read()
        if r.status != 200:
            raise OSError('llama-server respondeu %d: %s' % (r.status, dados[:200]))
        return json.loads(dados)
    finally:
        c.close()


def _ligar():
    """Sobe o llama-server do modelo de busca (pequeno: ~400 MB de memória, abre em poucos segundos)."""
    with _lock_servidor:
        p = _estado['processo']
        if p is not None and p.poll() is None:
            return True
        exe, modelo = achar_servidor(), achar_modelo()
        sistema.preparar_runtime(exe)
        porta = sistema.porta_livre(8812, evitar=(config.estado.get('porta_app'),))
        threads = max(2, min(4, sistema.nucleos_rapidos()))
        args = [exe, '-m', modelo, '--embedding', '--host', '127.0.0.1', '--port', str(porta), '-c', '2048',
                '-b', '2048', '-ub', '2048', '-np', '1', '-t', str(threads), '--no-webui']
        log.info('ligando a busca por significado (porta %d, %d threads)', porta, threads)
        p = sistema.iniciar_processo(args, cwd=config.RAIZ,
                                     log_arquivo=os.path.join(config.DADOS, 'busca_log.txt'))
        _estado.update(processo=p, porta=porta)
        limite = time.time() + 90
        while time.time() < limite:
            if p.poll() is not None:
                _estado['erro'] = 'o llama-server da busca fechou ao abrir (código %s)' % p.poll()
                log.warning(_estado['erro'])
                return False
            try:
                c = http.client.HTTPConnection('127.0.0.1', porta, timeout=2)
                c.request('GET', '/health')
                if c.getresponse().status == 200:
                    return True
            except OSError:
                pass
            time.sleep(0.5)
        log.warning('a busca por significado demorou demais para abrir')
        return False


_cache_q = {}


def vetor_da_pergunta(consulta):
    """Vetor completo (768 números), normalizado. Guardado: páginas e enciclopédias usam o mesmo."""
    chave = consulta.strip().lower()
    if chave in _cache_q:
        return _cache_q[chave]
    np = _np()
    if not _ligar():
        raise OSError('busca por significado indisponível')
    v = np.asarray(_req({'input': [PREFIXO + consulta.strip()]})['data'][0]['embedding'], dtype=np.float32)
    v = v / (float(np.linalg.norm(v)) or 1.0)
    if len(_cache_q) > 300:
        _cache_q.clear()
    _cache_q[chave] = v
    return v


def _top(vet, esc, q, k):
    np = _np()
    n = len(esc)
    notas = np.empty(n, dtype=np.float32)
    for i in range(0, n, _BLOCO):
        notas[i:i + _BLOCO] = (vet[i:i + _BLOCO].astype(np.float32) @ q) * esc[i:i + _BLOCO]
    k = min(k, n)
    top = np.argpartition(-notas, k - 1)[:k]
    return top[np.argsort(-notas[top])], notas


def buscar(consulta, k=100):
    """Os k rowids de pag mais parecidos com a consulta, do melhor para o pior: [(rowid, semelhança 0..1)]."""
    consulta = (consulta or '').strip()
    if not consulta:
        return []
    chave = (consulta.lower(), k)
    if chave in _cache:
        return _cache[chave]
    d = _carregar()
    q = vetor_da_pergunta(consulta)
    top, notas = _top(d['vet'], d['esc'], q, k)
    res = [(int(d['ids'][i]), float(notas[i])) for i in top]
    if len(_cache) > 200:
        _cache.clear()
    _cache[chave] = res
    return res


# ------------------------------------------------------------------ enciclopédias (.zim)
# vetores_zim.bin (int8, N x 256: os 256 primeiros números do vetor, renormalizados; o EmbeddingGemma foi treinado
# para isso e quase não perde), vetores_zim_escala.bin, vetores_zim.db (livros e itens: livro, caminho, título).
# Gerados por _TREINO_BUSCA/gerar_vetores_zim.py com título + primeiros parágrafos de cada artigo.

_zim = {}
_lock_zim = threading.Lock()


def disponivel_zim():
    if os.environ.get('BIBLIOTECA_SEM_VETORES_ZIM') == '1' or not disponivel():
        return False
    return all(os.path.exists(_arq(n)) for n in ('vetores_zim.json', 'vetores_zim.bin', 'vetores_zim_escala.bin', 'vetores_zim.db'))


def _carregar_zim():
    import sqlite3
    from urllib.parse import quote
    with _lock_zim:
        if _zim:
            return _zim
        np = _np()
        meta = json.load(open(_arq('vetores_zim.json'), encoding='utf-8'))
        dim, n = int(meta['dimensao']), int(meta['total'])
        uri = 'file:%s?mode=ro&immutable=1' % quote(_arq('vetores_zim.db').replace('\\', '/'))
        db = sqlite3.connect(uri, uri=True, check_same_thread=False)
        livros = {i: (lid, nome) for i, lid, nome in db.execute('SELECT i, id, nome FROM livros')}
        esc = np.fromfile(_arq('vetores_zim_escala.bin'), dtype=np.float32)
        if len(esc) != n:
            raise ValueError('vetores das enciclopédias incompletos')
        _zim.update(vet=np.memmap(_arq('vetores_zim.bin'), dtype=np.int8, mode='r', shape=(n, dim)), esc=esc, n=n,
                    dim=dim, db=db, livros=livros, lock=threading.Lock())
        log.info('busca por significado nas enciclopédias: %d artigos de %d livros', n, len(livros))
        return _zim


def buscar_zim(consulta, k=40):
    """Os k artigos mais parecidos: [{'livro_id', 'livro', 'caminho', 'titulo', 'sim'}], do melhor para o pior."""
    consulta = (consulta or '').strip()
    if not consulta:
        return []
    chave = ('zim', consulta.lower(), k)
    if chave in _cache:
        return _cache[chave]
    z = _carregar_zim()
    q = vetor_da_pergunta(consulta)[:z['dim']]
    q = q / (float(_np().linalg.norm(q)) or 1.0)
    top, notas = _top(z['vet'], z['esc'], q, k)
    res = []
    with z['lock']:
        for i in top:
            r = z['db'].execute('SELECT livro, caminho, titulo FROM itens WHERE i = ?', (int(i),)).fetchone()
            if r:
                lid, nome = z['livros'].get(r[0], ('', ''))
                res.append({'livro_id': lid, 'livro': nome, 'caminho': r[1], 'titulo': r[2], 'sim': float(notas[i])})
    _cache[chave] = res
    return res


def aquecer():
    """Ao abrir o app: liga o servidor e lê os vetores uma vez (a 1ª busca não espera o pen drive)."""
    def laco():
        try:
            if disponivel():
                t = time.time()
                buscar('água potável')
                if disponivel_zim():
                    buscar_zim('água potável')
                log.info('busca por significado pronta em %.0fs', time.time() - t)
            else:
                log.info('busca por significado desligada: %s', _estado['motivo'])
        except Exception as e:
            _estado['erro'] = str(e)
            log.warning('busca por significado indisponível: %s', e)
    threading.Thread(target=laco, daemon=True, name='vetores-aquecer').start()


def estado():
    p = _estado['processo']
    return {'disponivel': disponivel(), 'motivo': _estado['motivo'], 'erro': _estado['erro'],
            'rodando': p is not None and p.poll() is None, 'paginas': _dados.get('n', 0)}


def desligar():
    sistema.encerrar(_estado['processo'])
    _estado.update(processo=None)
