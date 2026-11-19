"""Espelho da Biblioteca Offline (cópia nossa no Cloudflare R2): põe o endereço do espelho na lista.

espelho.tsv (gerado por ferramentas/espelho_r2.py conferir) diz o endereço base do espelho e quais arquivos
estão lá inteiros. Para esses, a lista (manifesto) fica assim:

    5ª coluna (endereço)   o espelho
    7ª coluna (reserva)    o endereço de antes (o site original ou o web.archive.org)
    8ª coluna (mais)       outras reservas, separadas por espaço (ex.: a cópia nos Releases do GitHub)

Instaladores e apps antigos leem só até a 7ª coluna: baixam do espelho e, se falhar, do site de antes.
"""
import os
import urllib.parse

AQUI = os.path.dirname(os.path.abspath(__file__))
ARQUIVO = os.path.join(AQUI, 'espelho.tsv')


def ler(arquivo=ARQUIVO):
    """(base, {caminho}) ou ('', set()) se não há espelho."""
    base, nomes = '', set()
    try:
        with open(arquivo, encoding='utf-8') as f:
            for linha in f:
                linha = linha.rstrip('\n')
                if linha.startswith('# base\t'):
                    base = linha.split('\t', 1)[1].strip().rstrip('/')
                elif linha and not linha.startswith('#'):
                    nomes.add(linha)
    except OSError:
        pass
    return (base, nomes) if base.startswith('http') else ('', set())


def endereco(base, rel):
    return base + '/' + urllib.parse.quote(rel, safe='/~')


def aplicar(colunas, base, nomes):
    """Recebe as colunas de uma linha do manifesto e devolve as colunas com o espelho (ou iguais)."""
    c = list(colunas) + ['-'] * (8 - len(colunas))
    if not base or c[2] not in nomes:
        return colunas
    novo = endereco(base, c[2])
    antes = [u for u in [c[4], c[6]] + c[7].split() if u.startswith('http')]
    antes = [u for n, u in enumerate(antes) if u != novo and u not in antes[:n]]   # sem repetir
    c[4] = novo
    c[6] = antes[0] if antes else '-'
    c[7] = ' '.join(antes[1:]) or '-'
    return c
