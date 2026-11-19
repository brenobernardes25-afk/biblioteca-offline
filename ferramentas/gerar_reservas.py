#!/usr/bin/env python3
"""Gera computador/_Baixador/instalador/reservas.tsv: o endereço reserva de cada arquivo que tem cópia
nossa nos Releases do GitHub.

As cópias já existem: foram publicadas para o app do celular (celular/pacotes.json), só com o que permite
redistribuição (ver celular/ferramentas/espelhar_fontes.py). Aqui elas passam a servir também ao kit do
computador e à montagem do SSD pelo celular: se o site original falhar (web.archive.org lento, trueprepper
recusando celular, enciclopédia fora do catálogo), o instalador busca a cópia.

Uso:  python ferramentas/gerar_reservas.py
"""
import gzip
import json
import os

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PACOTES = os.path.join(RAIZ, 'celular', 'pacotes.json')
INSTALADOR = os.path.join(RAIZ, 'computador', '_Baixador', 'instalador')


def main():
    tamanhos = {}
    with gzip.open(os.path.join(INSTALADOR, 'manifesto.tsv.gz'), 'rt', encoding='utf-8') as f:
        for linha in f:
            c = linha.rstrip('\n').split('\t')
            if not linha.startswith('#') and len(c) >= 5:
                tamanhos[c[2]] = int(c[3] or 0)
    linhas = []
    for i in json.load(open(PACOTES, encoding='utf-8'))['itens']:
        if '/releases/download/' not in i.get('url', ''):
            continue
        rel = i.get('origem_pc') or '1_Enciclopedia/' + i['destino'].rsplit('/', 1)[-1]
        if tamanhos.get(rel) == i.get('tam'):          # só vale se for exatamente o mesmo arquivo
            linhas.append('%s\t%s' % (rel, i['url']))
    linhas.sort()
    with open(os.path.join(INSTALADOR, 'reservas.tsv'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('# caminho\tendereço reserva (cópia nossa nos Releases do GitHub)\n' + '\n'.join(linhas) + '\n')
    print('%d arquivos com endereço reserva' % len(linhas))


if __name__ == '__main__':
    main()
