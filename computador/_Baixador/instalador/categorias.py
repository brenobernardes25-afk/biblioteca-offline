"""BIBLIOTECA OFFLINE - o que baixar, por categoria.

O instalador pergunta uma vez o que a pessoa NÃO quer (ENTER = tudo) e guarda a resposta em
instalacao_escolha.json. A atualização pela internet respeita a mesma escolha.
Nada já baixado é apagado: deixar uma categoria de fora só faz o instalador não baixar o que falta dela.

Para mudar depois: dois cliques em ESCOLHER_O_QUE_BAIXAR.bat (ou INSTALAR_BIBLIOTECA.bat --escolher)
"""
import json
import os

AQUI = os.path.dirname(os.path.abspath(__file__))
ESCOLHA = os.path.join(AQUI, 'instalacao_escolha.json')

# (código, nome na tela). A ordem é a do menu. 'essencial' sempre vai.
CATEGORIAS = [
    ('essencial', 'Essencial: saúde, sobrevivência, água, programas e a IA'),
    ('pt', 'Wikipédia e outras enciclopédias em português'),
    ('pratico_en', 'Manuais práticos e de sobrevivência em inglês (Exército dos EUA, iFixit, medicina...)'),
    ('survivor', 'Survivor Library: livros antigos de técnicas e ofícios, em inglês'),
    ('mapas', 'Mapas do Brasil'),
    ('agricultura', 'Agricultura (Embrapa)'),
    ('exercito', 'Manuais do Exército Brasileiro'),
    ('academicos', 'Livros acadêmicos e didáticos (SciELO, LibreTexts...)'),
    ('wikipedia_en', 'Wikipédia e outras enciclopédias em inglês'),
    ('forums', 'Fóruns de perguntas e respostas (Stack Exchange), em inglês'),
    ('literatura', 'Literatura clássica (Projeto Gutenberg), em inglês'),
    ('religiao', 'Textos religiosos (Bíblias, Torá, Alcorão)'),
    ('ia_extra', 'IAs extras (maiores e mais lentas)'),
]
NOMES = dict(CATEGORIAS)
OPCIONAIS = [c for c, _ in CATEGORIAS if c != 'essencial']

PRATICOS_EN = ('mdwiki', 'ifixit_en', 'appropedia', 'medlineplus', 'wikem', 'zimgit', 'ready.gov', 'cd3wd', 'energypedia',
               'wikipedia_en_medicine', 'nhs.uk', 'wwwnc.cdc', 'wikipedia_en_knots', 'trueprepper', 'lrnselfreliance',
               'armypubs', 'irp.fas.org', 'usda-2015', 'quickguidesformedicine')
WIKIMEDIA_EN = ('wikipedia_en', 'wikisource_en', 'wikibooks_en', 'wikiversity_en', 'wikivoyage_en', 'wikiquote_en',
                'vikidia_en', 'citizendium', 'theworldfactbook', 'wikiciv', 'encyclopedie-environnement')


def categoria(rel):
    """Categoria de um caminho do manifesto (ex.: '1_Enciclopedia/gutenberg_en_lcc-a_2026-03.zim')."""
    r = rel.replace('\\', '/').lower()
    if r.startswith('4_ia/'):
        return 'essencial' if '4b' in r else 'ia_extra'
    if r.startswith('programas/ia/'):
        return 'ia_extra'
    if r.startswith('5_acervo/agricultura/'):
        return 'agricultura'
    if r.startswith('5_acervo/exercito_brasileiro/'):
        return 'exercito'
    if r.startswith('5_acervo/livros_scielo/'):
        return 'academicos'
    if r.startswith('5_acervo/religiao/'):
        return 'religiao'
    if r.startswith('2_mapas/'):
        return 'mapas'
    if r.startswith('1_enciclopedia/'):
        n = r.split('/')[-1]
        if n.startswith('maps_'):
            return 'mapas'
        if n.startswith('survivorlibrary'):
            return 'survivor'
        if n.startswith('gutenberg_'):
            return 'pt' if n.startswith('gutenberg_pt') else 'literatura'
        if '_pt_' in n or n.startswith('pt.') or n.startswith('portuguese.') or n.startswith('wiktionary_pt'):
            return 'pt'
        if n.startswith(PRATICOS_EN):
            return 'pratico_en'
        if 'stackexchange' in n or n.startswith('mathoverflow'):
            return 'forums'
        if n.startswith(WIKIMEDIA_EN):
            return 'wikipedia_en'
        return 'academicos'
    return 'essencial'   # saúde, sobrevivência, água, programas, instaladores e o que não tiver categoria


def ler_escolha():
    """Categorias deixadas de fora, ou None se a pessoa ainda não escolheu."""
    try:
        with open(ESCOLHA, encoding='utf-8') as f:
            fora = json.load(f).get('fora', [])
        return [c for c in fora if c in OPCIONAIS]
    except (OSError, ValueError, AttributeError):
        return None


def gravar_escolha(fora):
    with open(ESCOLHA, 'w', encoding='utf-8') as f:
        json.dump({'fora': sorted(set(fora) & set(OPCIONAIS))}, f, ensure_ascii=False, indent=1)


def quer(rel, fora):
    return categoria(rel) not in (fora or ())


def ler_numeros(texto):
    """'3 9, 10' -> categorias opcionais 3, 9 e 10 (numeração do menu). Devolve None se tiver algo inválido."""
    fora = []
    for p in texto.replace(',', ' ').split():
        if not p.isdigit() or not 1 <= int(p) <= len(OPCIONAIS):
            return None
        fora.append(OPCIONAIS[int(p) - 1])
    return fora
