"""Monta pacotes.json (Essencial / Completo) do app Biblioteca de Bolso a partir do manifesto real da biblioteca."""
import json, sys, re, csv, os

MANIFESTO, EMBRAPA, PDFS, SAIDA = sys.argv[1:5]
linhas = [l.rstrip('\n').split('\t') for l in open(MANIFESTO, encoding='utf-8') if not l.startswith('#')]
por_cam = {l[2]: (int(l[3]), l[4]) for l in linhas if len(l) >= 5}
por_url = {l[4].replace('https://', 'http://', 1): l[2] for l in linhas if len(l) >= 5}

def zim(prefixo):
    achados = [c for c in por_cam if c.startswith('1_Enciclopedia/' + prefixo)]
    assert len(achados) == 1, (prefixo, achados)
    return achados[0]

ZIMS = [  # (prefixo, pacote, tema, título)
    ('mdwiki_en_all_maxi', 'essencial', 'Saúde', 'MDWiki — medicina (EN)'),
    ('wikem_en_all_maxi', 'essencial', 'Saúde', 'WikEM — emergência médica (EN)'),
    ('irp.fas.org_en_military-medicine', 'essencial', 'Saúde', 'Medicina militar (EN)'),
    ('zimgit-medicine_en', 'essencial', 'Saúde', 'Medicina de emergência — seleção Kiwix (EN)'),
    ('quickguidesformedicine_en_all', 'essencial', 'Saúde', 'Guias rápidos de medicina (EN)'),
    ('zimgit-post-disaster_en', 'essencial', 'Sobrevivência', 'Pós-desastre — seleção Kiwix (EN)'),
    ('zimgit-water_en', 'essencial', 'Água', 'Tratamento de água (EN)'),
    ('zimgit-food-preparation_en', 'essencial', 'Alimentos', 'Preparo e conservação de alimentos (EN)'),
    ('zimgit-knots_en', 'essencial', 'Sobrevivência', 'Nós e amarras (EN)'),
    ('trueprepper.com_en_all', 'essencial', 'Sobrevivência', 'TruePrepper (EN)'),
    ('ready.gov_en_all', 'essencial', 'Sobrevivência', 'Ready.gov — preparação para desastres (EN)'),
    ('wikibooks_pt_all_maxi', 'essencial', 'Enciclopédia PT', 'Wikilivros'),
    ('wiktionary_pt_all_nopic', 'essencial', 'Enciclopédia PT', 'Wikcionário'),
    ('wikipedia_pt_all_maxi', 'completo', 'Enciclopédia PT', 'Wikipédia em português (completa, com imagens)'),
    ('ifixit_pt_all', 'completo', 'Consertos', 'iFixit — consertos (PT)'),
    ('wikisource_pt_all_maxi', 'completo', 'Enciclopédia PT', 'Wikisource'),
    ('gutenberg_pt_all', 'completo', 'Enciclopédia PT', 'Livros clássicos em português (Gutenberg)'),
    ('wikivoyage_pt_all_maxi', 'completo', 'Enciclopédia PT', 'Wikivoyage'),
    ('vikidia_pt_all_maxi', 'completo', 'Enciclopédia PT', 'Vikidia — enciclopédia infantil'),
    ('phet_pt_all', 'completo', 'Educação', 'PhET — simulações de ciência'),
    ('medlineplus.gov_en_all', 'completo', 'Saúde', 'MedlinePlus (EN)'),
    ('wwwnc.cdc.gov_en_all', 'completo', 'Saúde', 'CDC — saúde do viajante (EN)'),
    ('nhs.uk_en_medicines', 'completo', 'Saúde', 'NHS — remédios (EN)'),
    ('armypubs_en_all', 'completo', 'Sobrevivência', 'Manuais do Exército dos EUA (EN)'),
    ('lrnselfreliance_en_all', 'completo', 'Sobrevivência', 'Learn Self-Reliance — autossuficiência (EN)'),
    ('appropedia_en_all_maxi', 'completo', 'Tecnologia apropriada', 'Appropedia (EN)'),
    ('energypedia_en_all_maxi', 'completo', 'Energia', 'Energypedia — energia (EN)'),
    ('cd3wdproject.org_en_all', 'completo', 'Tecnologia apropriada', 'CD3WD — tecnologia para o campo (EN)'),
    ('gardening.stackexchange.com_en_all', 'completo', 'Horta', 'Perguntas: horta e jardim (EN)'),
    ('diy.stackexchange.com_en_all', 'completo', 'Consertos', 'Perguntas: reformas da casa (EN)'),
    ('outdoors.stackexchange.com_en_all', 'completo', 'Sobrevivência', 'Perguntas: vida ao ar livre (EN)'),
    ('cooking.stackexchange.com_en_all', 'completo', 'Alimentos', 'Perguntas: cozinha (EN)'),
    ('mechanics.stackexchange.com_en_all', 'completo', 'Consertos', 'Perguntas: mecânica de veículos (EN)'),
    ('homebrew.stackexchange.com_en_all', 'completo', 'Alimentos', 'Perguntas: fermentação (EN)'),
    ('ham.stackexchange.com_en_all', 'completo', 'Comunicação', 'Perguntas: radioamador (EN)'),
    ('sustainability.stackexchange.com_en_all', 'completo', 'Energia', 'Perguntas: sustentabilidade (EN)'),
    ('bicycles.stackexchange.com_en_all', 'completo', 'Consertos', 'Perguntas: bicicletas (EN)'),
    ('woodworking.stackexchange.com_en_all', 'completo', 'Consertos', 'Perguntas: marcenaria (EN)'),
    ('pets.stackexchange.com_en_all', 'completo', 'Animais', 'Perguntas: animais domésticos (EN)'),
    ('ted_mul_gardening', 'completo', 'Horta', 'Palestras TED sobre cultivo'),
    ('wikipedia_en-simple_all_maxi', 'completo', 'Enciclopédia EN', 'Wikipédia em inglês simplificado'),
]

itens = []
def add(**k):
    itens.append(k)

for pref, pac, tema, tit in ZIMS:
    pac = {'essencial': 'minimo', 'completo': 'slim'}[pac]
    cam = zim(pref)
    tam, url = por_cam[cam]
    add(id='zim:' + pref, tipo='zim', pacote=pac, tema=tema, titulo=tit, url=url, tam=tam,
        destino='enciclopedias/' + cam.split('/')[-1], atualizavel=True, kiwix_nome=pref)

# PDFs pequenos que vão inteiros
for cam, (tam, url) in por_cam.items():
    if re.match(r'5_Acervo/(Saude/Hesperian|Saude/Hesperian_EN|Sobrevivencia|Agua|Eletrica|Solda|Desastres)/', cam) or \
       re.match(r'5_Acervo/Agricultura/[^/]+\.pdf$', cam) or re.match(r'5_Acervo/Saude/[^/]+\.pdf$', cam):
        tema = 'Saúde' if '/Saude/' in cam else cam.split('/')[1].replace('Sobrevivencia', 'Sobrevivência').replace('Agua', 'Água').replace('Eletrica', 'Elétrica').replace('Agricultura', 'Agricultura')
        add(id='pdf:' + cam, tipo='pdf', pacote='minimo', tema=tema, titulo=os.path.splitext(cam.split('/')[-1])[0].replace('_', ' '),
            url=url, tam=tam, destino='manuais/' + cam.split('/', 1)[1], origem_pc=cam)

pc = json.load(open(PDFS, encoding='utf-8'))
for pasta, tema in (('5_Acervo/Saude/Ministerio_da_Saude/', 'Saúde'), ('5_Acervo/Exercito_Brasileiro/', 'Exército Brasileiro')):
    ess = {c['caminho'] for c in pc[pasta]['essencial']}
    for c in pc[pasta]['completo']:
        cam = c['caminho']
        add(id='pdf:' + cam, tipo='pdf', pacote='minimo' if cam in ess else 'slim', tema=tema,
            titulo=os.path.splitext(cam.split('/')[-1])[0].replace('_', ' '), url=c['url'], tam=c['tam'], destino='manuais/' + cam.split('/', 1)[1], origem_pc=cam)

ec = json.load(open(EMBRAPA, encoding='utf-8'))
ess = set(ec['essencial'])
for did in ec['completo']:
    v = ec['itens'][did]
    for i, (url, tam) in enumerate(v['pdfs']):
        nome = did + '_' + url.rstrip('/').split('/')[-1]
        origem = por_url.get(url.replace('https://', 'http://', 1))
        if origem:
            nome = origem.split('/')[-1]
        add(id='embrapa:%s:%d' % (did, i), tipo='pdf', pacote='minimo' if did in ess else 'slim',
            tema='Embrapa — ' + v['tema'], titulo=v['titulo'], ano=v['ano'], url=url.replace('http://', 'https://', 1), tam=tam,
            destino='manuais/Agricultura/Embrapa/' + nome, origem_pc=origem)

# IA opcional
cam = '4_IA/Qwen3-4B-Instruct-2507-Q4_K_M.gguf'
add(id='ia:qwen3-4b', tipo='ia', pacote='opcional', tema='IA', titulo='IA Qwen3 4B (celular com 8 GB de RAM)',
    url=por_cam[cam][1], tam=por_cam[cam][0], destino='ia/' + cam.split('/')[-1])
# O Qwen3 1.7B (6 GB de RAM) saiu do celular em 2026-11-08: errou as quantidades do soro caseiro no teste.

# remove duplicados de destino
vistos, uniq = set(), []
for it in itens:
    if it['destino'] in vistos:
        continue
    vistos.add(it['destino']); uniq.append(it)
itens = uniq

def soma(f):
    return sum(i['tam'] for i in itens if f(i))
res = {
    'versao': '2026-11-06',
    'pacotes': {
        'minimo': {'nome': 'Slim mínimo', 'onde': 'celular',
                   'descricao': 'Saúde, água, sobrevivência e o melhor da Embrapa. Para celulares com pouco espaço (64 GB).',
                   'tam': soma(lambda i: i['pacote'] == 'minimo')},
        'slim': {'nome': 'Slim', 'onde': 'celular',
                 'descricao': 'Tudo do mínimo + Wikipédia em português completa, manuais do Exército dos EUA, consertos e muito mais. Até 100 GB, no próprio celular.',
                 'tam': soma(lambda i: i['pacote'] in ('minimo', 'slim'))},
        'completo': {'nome': 'Completo', 'onde': 'hd_externo',
                     'descricao': 'A biblioteca inteira (a mesma da versão para PC), num SSD externo ligado ao celular pelo USB-C. Precisa de um SSD de 1 TB.',
                     'tam': 827000000000,
                     'kit_pc': 'https://github.com/brenobernardes25-afk/biblioteca-offline/releases/latest',
                     'manifesto_no_kit': '_Baixador/instalador/manifesto.tsv.gz',
                     'indice': 'indice_completo_<versao>.zip (Releases do biblioteca-offline)'},
    },
    'itens': itens,
}
json.dump(res, open(SAIDA, 'w', encoding='utf-8'), ensure_ascii=False, indent=1)

# planilha para revisão humana
with open(os.path.splitext(SAIDA)[0] + '_revisao.csv', 'w', encoding='utf-8-sig', newline='') as f:
    w = csv.writer(f, delimiter=';')
    w.writerow(['pacote', 'tipo', 'tema', 'titulo', 'ano', 'tamanho_MB', 'id'])
    for it in sorted(itens, key=lambda i: (('minimo', 'slim', 'opcional').index(i['pacote']), i['tema'], i['titulo'])):
        w.writerow([it['pacote'], it['tipo'], it['tema'], it['titulo'], it.get('ano', ''), round(it['tam'] / 1e6, 1), it['id']])

import collections
print('itens', len(itens))
for p in ('minimo', 'slim', 'opcional'):
    sel = [i for i in itens if i['pacote'] == p]
    print(p, len(sel), round(sum(i['tam'] for i in sel) / 1e9, 2), 'GB')
    t = collections.Counter()
    for i in sel: t[(i['tipo'], i['tema'].split(' — ')[0])] += i['tam']
    for k, v in sorted(t.items(), key=lambda x: -x[1]): print('   %-45s %6.2f GB' % (k, v / 1e9))
print('Slim mínimo', round(res['pacotes']['minimo']['tam'] / 1e9, 2), 'GB | Slim', round(res['pacotes']['slim']['tam'] / 1e9, 2), 'GB')
