"""Curadoria da Embrapa para o celular: pontua títulos práticos (casa, sítio, quintal) e descarta pesquisa."""
import json, re, sys, unicodedata, collections

CAT = sys.argv[1]
SAIDA = sys.argv[2]

def norm(s):
    s = unicodedata.normalize('NFKD', s.lower())
    return ''.join(c for c in s if not unicodedata.combining(c))

TIPOS_OK = {'Folhetos': 0, 'Folders': 1, 'Livros': 2, 'Manuais': 3, 'Material não-convencional': 0,
            'Obras de referência (dicionários, enciclopédias, etc.)': 1}

# (peso, tema, palavras) — palavras sem acento
TEMAS = [
    (5, 'Horta e hortaliças', r'\bhorta|hortalica|hortalicas|quintal|canteiro|alface|tomate|couve|cenoura|abobora|pimenta|cebola|alho\b|batata-doce|inhame|taioba|ora-pro-nobis|pancs?\b|nao convencionais'),
    (5, 'Grãos e roças básicas', r'mandioca|aipim|macaxeira|\bmilho\b|feijao|arroz|amendoim|gergelim|sorgo|fava\b'),
    (4, 'Frutas e pomar', r'pomar|banana|laranja|citros|limao|manga|mamao|maracuja|abacaxi|goiaba|acerola|caju|coco\b|coqueiro|uva\b|videira|abacate|jabuticaba|pitanga|umbu|acai|cupuacu|frutifera|fruteira'),
    (5, 'Pequenas criações', r'galinha|frango caipira|aves caipiras|ovos?\b|codorna|suino|porco|cabra|caprino|ovino|ovelha|coelho|abelha|apicultura|meliponicultura|\bmel\b|patos?\b'),
    (4, 'Peixes', r'piscicultura|tilapia|tambaqui|peixes?\b|viveiro escavado|aquicultura'),
    (3, 'Gado de leite (pequeno)', r'vaca|leite\b|bezerr|ordenha|mastite|gado de leite|pastagem|capineira|silagem|feno\b'),
    (6, 'Água', r'\bagua\b|cisterna|poco\b|chuva|barragem subterranea|barreiro|dessalinizador|filtro|potavel|cloracao|tratamento de agua|irrigacao|gotejamento'),
    (6, 'Saneamento rural', r'fossa septica|saneamento|esgoto|banheiro seco|biodigestor|jardim filtrante'),
    (6, 'Conservação e preparo de alimentos', r'conserva|doce\b|doces|geleia|compota|desidrata|secagem|defumad|embutido|linguica|queijo|iogurte|requeijao|farinha|polvilho|tapioca|pao\b|receita|aproveitamento|agroindustria|processamento|pos-colheita|armazenamento de graos|silo\b|beneficiamento|rapadura|melado|fuba|fermentad|vinagre|picles'),
    (5, 'Solo, adubo e compostagem', r'compostagem|composto organico|adubacao organica|adubo|esterco|biofertilizante|calagem|cobertura morta|minhoc|vermicompost|adubacao verde|biochar'),
    (4, 'Pragas e doenças sem veneno', r'controle alternativo|controle biologico|caldas?\b|calda bordalesa|defensivos? naturais|manejo de pragas|formigas?|cupim|praga'),
    (4, 'Sementes e mudas', r'sementes? crioul|producao de sementes|banco de sementes|guardar sementes|mudas?\b|enxertia|estaquia|propagacao|viveiro de mudas'),
    (5, 'Plantas medicinais', r'plantas? medicin|fitoterap|ervas?\b|chas?\b medicin'),
    (3, 'Agroecologia e sistemas', r'agroecolog|organico|agrofloresta|sistemas? agroflorest|permacultura|consorcio|integracao lavoura'),
    (3, 'Energia e construções rurais', r'secador solar|energia solar|fogao|forno|galinheiro|chiqueiro|aprisco|construc|cerca\b|cercas'),
    (2, 'Clima e convivência com a seca', r'convivencia com o semiarido|seca\b|semiarido|estiagem'),
]
DIDATICO = r'\babc\b|500 perguntas|cartilha|como plantar|como criar|como fazer|como produzir|passo a passo|cultivo d|cultivo e |criacao d|criacao e |recomendacoes tecnicas|orientacoes (tecnicas |para )|sistema de producao|guia pratico|manual pratico|manual de (criacao|cultivo|producao|boas praticas)|boas praticas|colecao (plantar|criar)|faca voce|aprenda|dicas|o produtor pergunta|perguntas e respostas|pratos|receitas'
PESQUISA = (r'avaliacao|efeito|efeitos|desempenho de|genotip|melhoramento|marcador|cultivares?\b(?!.*plantar)|zoneamento|relatorio|seminario|'
            r'congresso|simposio|workshop|encontro|anais|reuniao|resumos|programa|plano diretor|balanco social|catalogo|boletim de pesquisa|'
            r'estatistic|mercado|economi|custo de producao|impacto|politica|competitividade|indicadores|metodologia|modelagem|sensoriamento|'
            r'geoprocessamento|transgen|biotecnolog|in vitro|molecular|caracterizacao|diagnostico|levantamento|tese|dissertacao|anuario|'
            r'\bbrs\b|atlas|credito|comunicacao|estimativa|metodolog|agencia de informacao|\bteve\b|variacao|experimentacao|formas de|atividade de|necropsia|corte\b|gaiolas|entupimento|distribuicao de raizes|recursos geneticos|cercosporiose|fosforo|latossolo|submersao|entrelinhas|internalizacao|embrapa (oferta|trabalha|lanca)|transferencia de tecnologia|capacitacao|equipamento|laboratorio|analise|germoplasma|biosseguranca|quantificacao|disponibilidade|safra|custos?\b|composicao|validacao|enquadramento|territorio|evolucao|panorama|comercio|remanescente|floresta|arvores|clones?\b|deficitaria|altas temperaturas|planilha|nutricao mineral|materia seca|valorizacao|biomassa|inventario|monitoramento|resposta|influencia|variabilidade|selecao|registro|lancamento|cultivar\b|hibrido|'
            r'eucalipto|pinus|soja|algodao|cana-de-acucar|trigo|cevada|dende|seringueira|cafe\b|cacau|fumo|tabaco|exportacao|bovinos de corte|confinamento')

itens = json.load(open(CAT, encoding='utf-8'))['itens']
cands = []
for did_, v in itens.items():
    tipo = v.get('tipo') or ''
    if tipo not in TIPOS_OK:
        continue
    t = v.get('t') or ''
    n = norm(t)
    if re.search(PESQUISA, n):
        continue
    temas = [(p, nome) for p, nome, rx in TEMAS if re.search(rx, n)]
    if not temas:
        continue
    pdfs = [p for p in v.get('pdfs', []) if p and p[1]]
    if not pdfs:
        continue
    tam = sum(p[1] for p in pdfs)
    if tam > 120e6:             # nada gigante no celular
        continue
    did = bool(re.search(DIDATICO, n))
    nota = max(p for p, _ in temas) + TIPOS_OK[tipo] + (4 if did else 0)
    try:
        ano = int(v.get('ano') or 0)
    except ValueError:
        ano = 0
    nota += 1 if ano >= 2010 else 0
    nota += 8 if re.search(r'o produtor pergunta|500 perguntas|\babc\b|colecao (plantar|criar)', n) else 0
    tema = max(temas)[1]
    cands.append(dict(didatico=did, id=did_, titulo=t.strip(), tipo=tipo, ano=ano, tema=tema, nota=nota, tam=tam,
                      pdfs=pdfs, chave=re.sub(r'[^a-z0-9]', '', n)[:60]))

# um por título (fica o mais recente)
melhor = {}
for c in cands:
    k = c['chave']
    if k not in melhor or (c['ano'], c['nota']) > (melhor[k]['ano'], melhor[k]['nota']):
        melhor[k] = c
cands = sorted(melhor.values(), key=lambda c: (-c['nota'], -c['ano'], c['tam']))

def corte(limite, so_didatico=False):
    sel, tot = [], 0
    for c in cands:
        if so_didatico and not c['didatico']:
            continue
        if tot + c['tam'] > limite:
            continue
        sel.append(c); tot += c['tam']
    return sel, tot

ess, te = corte(3e9, so_didatico=True)
comp, tc = corte(15e9)
print('candidatos', len(cands), 'total', round(sum(c["tam"] for c in cands)/1e9, 1), 'GB')
print('essencial', len(ess), round(te/1e9, 2), 'GB  nota minima', min(c['nota'] for c in ess))
print('completo', len(comp), round(tc/1e9, 2), 'GB  nota minima', min(c['nota'] for c in comp))
for nome, sel in (('ESSENCIAL', ess), ('COMPLETO', comp)):
    cnt = collections.Counter(c['tema'] for c in sel)
    print(nome, dict(cnt.most_common()))
json.dump({'essencial': [c['id'] for c in ess], 'completo': [c['id'] for c in comp],
           'itens': {c['id']: {k: c[k] for k in ('titulo', 'tipo', 'ano', 'tema', 'nota', 'tam', 'pdfs', 'didatico')} for c in comp}},
          open(SAIDA, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
