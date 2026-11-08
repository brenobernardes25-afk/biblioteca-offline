"""Curadoria por nome de arquivo para Ministério da Saúde e Exército (os nomes já são descritivos)."""
import json, re, sys, unicodedata

M = sys.argv[1]
SAIDA = sys.argv[2]

def norm(s):
    s = unicodedata.normalize('NFKD', s.lower())
    return ''.join(c for c in s if not unicodedata.combining(c)).replace('_', ' ').replace('-', ' ')

REGRAS = {
    '5_Acervo/Saude/Ministerio_da_Saude/': dict(
        bom=[(8, r'primeiros socorros|socorro'), (8, r'urgencia|emergencia|pronto atendimento|samu'),
             (8, r'peconhent|ofidic|escorpi|aranha|serpente|cobra'), (7, r'queimad|afogament|trauma|fratura|ferida|curativo'),
             (7, r'parto|gestante|gravidez|pre natal|recem nascido|aleitamento|amamentacao'),
             (6, r'aidpi|crianca|infancia|desidrata|diarreia|reidratacao'),
             (6, r'dengue|chikungunya|zika|malaria|leptospir|raiva|tetano|febre amarela|colera|hepatite a|esquistossom|verminose|doenca de chagas|leishmani'),
             (7, r'planta(s)? medicin|fitoter|formulario fitoterapico|memento fitoterapico|farmacia viva'),
             (6, r'formulario terapeutico|rename|medicamentos essenciais|guia de bolso|doencas infecciosas'),
             (7, r'qualidade da agua|agua para consumo|cloracao|desinfeccao|potabilidade|vigilancia da qualidade da agua|tratamento de agua|solucao alternativa'),
             (6, r'saneamento|fossa|esgoto|residuos|lixo|funasa'),
             (6, r'desastre|enchente|inundac|seca|calamidade|emergencias em saude publica'),
             (5, r'guia alimentar|alimentacao saudavel|alimentos regionais|desnutric'),
             (4, r'hipertens|diabet|asma|cuidador|idoso|saude bucal|caderno(s)? de atencao basica|cab \d'),
             (4, r'saude mental|suicid|crise')],
        ruim=r'relatorio|politica|gestao|conferencia|residencia|avaliacao|custo|economic|forum|seminario|oficina|portaria|legislacao|norma|regimento|'
             r'inca |tese|dissertac|plano (nacional|de acao)|pesquisa|inquerito|estatistic|indicador|financiamento|planejamento|organizacao|'
             r'manual de redacao|historia|memoria|documenta|catalogo|boletim|informe|cap\d|parte \d|sistema de informacao|sinan|esus|sus\b|'
             r'samu|especificac|mobiliario|equipamentos|arquitet|regulacao|premio|apresentac|comunicacao|codigos|covid|coronav|estudo de caso|rede de|especializacao|acolhimento|carencia|plano( de)? contingencia|declaracao|motolancia|vigilancia epidemiologica|orientacoes integradas|anticoncepc|levonorgestrel|falciforme|sarampo|suplementar|sociedade|quimico|'
             r'ingles|english|espanol|spanish|national|educacao permanente|curso de|capacitacao|formacao|aids|hiv|cancer|oncolog|transplante|radioter',
        limites=(0.6e9, 3.0e9)),
    '5_Acervo/Exercito_Brasileiro/': dict(
        bom=[(9, r'sobreviv'), (8, r'primeiros socorros|atendimento pre hospitalar|aph|emergencia e urgencia|emergencia|socorr'),
             (7, r'enfermagem|pop enfermagem|infeccoes|saude'), (7, r'qbrn|quimica biologica radiologica|nuclear'),
             (6, r'leitura de cartas|orientacao|navegacao|topografia|bussola'), (6, r'engenharia|construc|pontes|agua|purificac|abastecimento'),
             (5, r'campanha|acampamento|vida em campanha|selva|caatinga|montanha|pantanal'),
             (4, r'subsistencia|alimentacao|cozinha|rancho'), (4, r'nos e amarras|cordas|transposicao')],
        ruim=r'tese|dissertac|portaria|regimento|regulamento|manual siga|autoridades|esgrima|salto|paraquedis|aerotransporte|alvos aereos|'
             r'artilharia|blindad|carro de combate|material belico|armas|tiro|morteiro|municao|simulac|peacekeeping|united nations|'
             r'vocacional|aprimoramento|relatorio|oncolog|cartilha ensino hibrido|apresentacao|meteorolog|bateria|defesa do litoral|aeromove|planejamento|logistica|doutrina|'
             r'operacoes|operacao|comando|estado maior|inteligencia|guerra|combate',
        limites=(0.3e9, 0.8e9)),
}

linhas = [l.rstrip('\n').split('\t') for l in open(M, encoding='utf-8') if not l.startswith('#')]
res = {}
for pasta, r in REGRAS.items():
    cands = []
    for prio, tipo, cam, tam, url in (l for l in linhas if len(l) >= 5):
        if not cam.startswith(pasta):
            continue
        n = norm(cam[len(pasta):].rsplit('.', 1)[0])
        if re.search(r['ruim'], n):
            continue
        notas = [p for p, rx in r['bom'] if re.search(rx, n)]
        if not notas:
            continue
        cands.append(dict(caminho=cam, tam=int(tam), url=url, nota=max(notas) + (len(notas) > 1)))
    cands.sort(key=lambda c: (-c['nota'], c['tam']))
    out = {}
    for nome, lim in zip(('essencial', 'completo'), r['limites']):
        sel, tot = [], 0
        for c in cands:
            if tot + c['tam'] > lim or c['tam'] > 150e6:
                continue
            sel.append(c); tot += c['tam']
        out[nome] = sel
        print(pasta, nome, len(sel), round(tot / 1e9, 2), 'GB de', len(cands), 'candidatos')
    res[pasta] = out
json.dump(res, open(SAIDA, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
