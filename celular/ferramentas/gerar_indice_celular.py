#!/usr/bin/env python3
"""Gera o índice de busca dos PDFs do celular a partir do índice da biblioteca do PC:

    python ferramentas/gerar_indice_celular.py app/src/main/assets/pacotes.json D:/ PASTA_SAIDA

Saída: PASTA_SAIDA/indice_slim_<versao>.zip e ferramentas/indice_info.json (nome, tamanho, SHA-256).
Rode com a biblioteca fechada e depois de ela ter lido tudo. É a versão da seção 7 com três diferenças:
- Lê as páginas por faixa de rowid (pag_ini, pag_fim). Filtrar a coluna doc_id varre o índice inteiro.
- Grava pag_ini e pag_fim também no índice do celular, para ler uma página sem varrer.
- As obras que não têm permissão de redistribuir (ver espelhar_fontes.py) não levam o texto, porque o índice
  é público. Entram só pelo título: quem baixou o PDF do site oficial acha pelo nome.
"""
import hashlib
import json
import os
import sqlite3
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from espelhar_fontes import DIRETO_DO_SITE, NAO_ESPELHAR  # noqa: E402

ESQUEMA = """
CREATE TABLE docs (
    id INTEGER PRIMARY KEY,
    caminho TEXT UNIQUE NOT NULL,
    item_id TEXT NOT NULL,
    pacote TEXT NOT NULL,
    titulo TEXT, tema TEXT, idioma TEXT,
    paginas INTEGER DEFAULT 0,
    situacao TEXT,
    pag_ini INTEGER, pag_fim INTEGER
);
CREATE VIRTUAL TABLE pag USING fts5(
    titulo, texto, doc_id UNINDEXED, pagina UNINDEXED,
    tokenize = 'unicode61 remove_diacritics 2'
);
CREATE TABLE meta (chave TEXT PRIMARY KEY, valor TEXT);
"""


def titulo_bom(pc, item):
    """Título do PC, salvo quando é sobra do nome do arquivo ou uma palavra solta da capa."""
    if not pc or len(pc) < 6 or pc.lower().replace(' ', '_').rstrip('.pdf') in os.path.basename(item['destino']).lower():
        return item['titulo']
    return pc


def main():
    entrada, raiz, saida = sys.argv[1], sys.argv[2], sys.argv[3]
    d = json.load(open(entrada, encoding='utf-8'))
    versao = d['versao']
    base = os.path.join(saida, 'indice_slim_%s' % versao)
    db = base + '.db'
    os.makedirs(saida, exist_ok=True)
    if os.path.exists(db):
        os.remove(db)
    src = sqlite3.connect('file:%s?mode=ro' % os.path.join(raiz, 'App/dados/indice.db').replace('\\', '/'), uri=True)
    dst = sqlite3.connect(db)
    dst.executescript(ESQUEMA)
    fechados = set(NAO_ESPELHAR) | set(DIRETO_DO_SITE)
    n = paginas = sem_texto = so_titulo = fora = 0
    for i in d['itens']:
        if i['tipo'] != 'pdf' or i['pacote'] not in ('minimo', 'slim'):
            continue
        q = None
        if i.get('origem_pc'):
            q = src.execute('SELECT id, titulo, idioma, paginas, situacao, pag_ini, pag_fim FROM docs WHERE caminho = ?',
                            (i['origem_pc'],)).fetchone()
        if not q:
            fora += 1
            q = (None, None, None, 0, 'fora', None, None)
        doc_pc, titulo, idioma, pags, situacao, ini, fim = q
        titulo = titulo_bom(titulo, i)
        cur = dst.execute('INSERT INTO docs (caminho,item_id,pacote,titulo,tema,idioma,paginas,situacao) VALUES (?,?,?,?,?,?,?,?)',
                          (i['destino'], i['id'], i['pacote'], titulo, i['tema'], idioma, pags, situacao))
        novo = cur.lastrowid
        n += 1
        if os.path.basename(i.get('origem_pc') or i['destino']) in fechados:
            so_titulo += 1
            continue
        if doc_pc is None or ini is None:
            sem_texto += 1
            continue
        primeiro = ultimo = None
        for txt, pagina in src.execute('SELECT texto, pagina FROM pag WHERE rowid BETWEEN ? AND ? AND doc_id = ? ORDER BY rowid',
                                       (ini, fim, doc_pc)):
            r = dst.execute('INSERT INTO pag (titulo, texto, doc_id, pagina) VALUES (?,?,?,?)', (titulo, txt, novo, pagina))
            ultimo = r.lastrowid
            primeiro = primeiro or ultimo
            paginas += 1
        if primeiro is None:
            sem_texto += 1
        else:
            dst.execute('UPDATE docs SET pag_ini = ?, pag_fim = ? WHERE id = ?', (primeiro, ultimo, novo))
        if n % 200 == 0:
            dst.commit()
            print(n, 'documentos', paginas, 'paginas', flush=True)
    dst.execute("INSERT INTO meta VALUES ('versao', ?)", (versao,))
    dst.execute("INSERT INTO meta VALUES ('documentos', ?)", (str(n),))
    dst.commit()
    dst.execute("INSERT INTO pag(pag) VALUES ('optimize')")
    dst.commit()
    dst.execute('VACUUM')
    dst.close()
    z = base + '.zip'
    with zipfile.ZipFile(z, 'w', zipfile.ZIP_DEFLATED, compresslevel=9, allowZip64=True) as f:
        f.write(db, os.path.basename(db))
    h = hashlib.sha256()
    with open(z, 'rb') as f:
        for b in iter(lambda: f.read(1 << 20), b''):
            h.update(b)
    info = {'versao': versao, 'arquivo': os.path.basename(z), 'tam': os.path.getsize(z), 'tam_db': os.path.getsize(db), 'sha256': h.hexdigest()}
    json.dump(info, open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'indice_info.json'), 'w'), indent=1)
    print('documentos', n, 'paginas', paginas, 'sem texto', sem_texto, 'so titulo (sem permissao)', so_titulo, 'fora do indice do PC', fora)
    print(info)


if __name__ == '__main__':
    main()
