"""Gera o índice de busca do app Biblioteca de Bolso a partir do índice que a biblioteca do PC já montou.

Rodar no PC, com o SSD da biblioteca plugado e o app da biblioteca já tendo indexado os PDFs:

    Programas\\Python\\python.exe gerar_indice_celular.py  pacotes.json  [pasta_da_biblioteca]
    Programas\\Python\\python.exe gerar_indice_celular.py  --completo pacotes.json  [pasta_da_biblioteca]

Saída (na pasta atual):
    indice_slim_<versao>.zip         só os PDFs dos pacotes Slim (caminhos = 'destino' do pacotes.json)
    indice_completo_<versao>.zip     todos os PDFs da biblioteca (caminhos = os do HD: 5_Acervo/...),
                                     para o modo HD externo. Se passar de 1,9 GB, sai em partes
                                     (.zip.001, .zip.002...), porque o GitHub aceita até 2 GB por arquivo.

O celular nunca lê os PDFs para indexar: baixa este arquivo pronto. Caminhos dentro do índice
são os 'destino' do pacotes.json (ex.: manuais/Saude/Hesperian/...), relativos à pasta do app.
"""
import json
import os
import sqlite3
import sys
import zipfile

ESQUEMA = """
CREATE TABLE docs (
    id INTEGER PRIMARY KEY,
    caminho TEXT UNIQUE NOT NULL,      -- 'destino' do pacotes.json
    item_id TEXT NOT NULL,             -- 'id' do item no pacotes.json
    pacote TEXT NOT NULL,              -- essencial | completo
    titulo TEXT, tema TEXT, idioma TEXT,
    paginas INTEGER DEFAULT 0,
    situacao TEXT                      -- ok | sem_texto | protegido | erro
);
CREATE VIRTUAL TABLE pag USING fts5(
    titulo, texto, doc_id UNINDEXED, pagina UNINDEXED,
    tokenize = 'unicode61 remove_diacritics 2'
);
CREATE VIRTUAL TABLE titulos USING fts5(
    titulo, doc_id UNINDEXED,
    tokenize = 'unicode61 remove_diacritics 2'
);
CREATE TABLE meta (chave TEXT PRIMARY KEY, valor TEXT);
"""


def main():
    args = [a for a in sys.argv[1:] if a != '--completo']
    completo = '--completo' in sys.argv
    if not args:
        print(__doc__)
        sys.exit(1)
    pacotes = json.load(open(args[0], encoding='utf-8'))
    raiz = args[1] if len(args) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    origem = os.path.join(raiz, 'App', 'dados', 'indice.db')
    if not os.path.isfile(origem):
        sys.exit('Não achei %s. Abra a biblioteca no PC e deixe ela indexar os PDFs primeiro.' % origem)

    versao = pacotes['versao']
    destino = 'indice_%s_%s.db' % ('completo' if completo else 'slim', versao)
    if os.path.exists(destino):
        os.remove(destino)

    src = sqlite3.connect('file:%s?mode=ro' % origem.replace('\\', '/'), uri=True)
    if completo:   # todos os documentos, com o caminho do próprio HD
        itens = {c: {'destino': c, 'id': 'hd:' + c, 'pacote': 'completo', 'titulo': '', 'tema': tm or ''}
                 for c, tm in src.execute("SELECT caminho, tema FROM docs WHERE situacao != 'erro'")}
    else:
        itens = {i['origem_pc']: i for i in pacotes['itens'] if i['tipo'] == 'pdf' and i.get('origem_pc')}
    dst = sqlite3.connect(destino)
    dst.executescript(ESQUEMA)

    copiados = sem_texto = faltando = 0
    for caminho_pc, item in itens.items():
        linha = src.execute('SELECT id, titulo, tema, idioma, paginas, situacao FROM docs WHERE caminho = ?',
                            (caminho_pc,)).fetchone()
        if not linha:
            faltando += 1
            continue
        doc_pc, titulo, tema, idioma, paginas, situacao = linha
        titulo = titulo or item['titulo']
        cur = dst.execute('INSERT INTO docs (caminho, item_id, pacote, titulo, tema, idioma, paginas, situacao) '
                          'VALUES (?,?,?,?,?,?,?,?)',
                          (item['destino'], item['id'], item['pacote'], titulo, item['tema'], idioma, paginas, situacao))
        novo = cur.lastrowid
        dst.execute('INSERT INTO titulos (titulo, doc_id) VALUES (?, ?)', (titulo, novo))
        n = 0
        for txt, pagina in src.execute('SELECT texto, pagina FROM pag WHERE doc_id = ?', (doc_pc,)):
            dst.execute('INSERT INTO pag (titulo, texto, doc_id, pagina) VALUES (?,?,?,?)', (titulo, txt, novo, pagina))
            n += 1
        copiados += 1
        sem_texto += (n == 0)
        if copiados % 200 == 0:
            dst.commit()
            print('  %d documentos...' % copiados)

    dst.execute("INSERT INTO meta VALUES ('versao', ?)", (versao,))
    dst.execute("INSERT INTO meta VALUES ('documentos', ?)", (str(copiados),))
    dst.commit()
    dst.execute("INSERT INTO pag(pag) VALUES ('optimize')")
    dst.execute("INSERT INTO titulos(titulos) VALUES ('optimize')")
    dst.commit()
    dst.execute('VACUUM')
    dst.close()

    with zipfile.ZipFile(destino[:-3] + '.zip', 'w', zipfile.ZIP_DEFLATED, compresslevel=9, allowZip64=True) as z:
        z.write(destino, os.path.basename(destino))

    print('\nPronto: %d documentos no índice (%d sem texto pesquisável; %d ainda não indexados no PC).'
          % (copiados, sem_texto, faltando))
    print('Arquivos: %s (%.0f MB) e %s (%.0f MB)' % (
        destino, os.path.getsize(destino) / 1e6, destino[:-3] + '.zip', os.path.getsize(destino[:-3] + '.zip') / 1e6))
    partes = dividir(destino[:-3] + '.zip')
    if partes:
        print('O zip passou de 1,9 GB e foi dividido em %d partes (%s ...). Suba todas nos Releases.' % (len(partes), partes[0]))
    if faltando and not completo:
        print('Os que faltam não estão no SSD ou o PC ainda não indexou. O app lista esses pelo título, sem busca no texto.')


def dividir(caminho, limite=1900 * 1024 * 1024):
    """Divide em partes de até 1,9 GB (limite do GitHub: 2 GB por arquivo). O app junta na ordem."""
    if os.path.getsize(caminho) <= limite:
        return []
    partes = []
    with open(caminho, 'rb') as f:
        n = 1
        while True:
            bloco = f.read(limite)
            if not bloco:
                break
            nome = '%s.%03d' % (caminho, n)
            with open(nome, 'wb') as g:
                g.write(bloco)
            partes.append(nome)
            n += 1
    os.remove(caminho)
    return partes


if __name__ == '__main__':
    main()
