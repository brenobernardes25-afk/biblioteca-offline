"""BIBLIOTECA OFFLINE - catálogo legível de tudo o que está instalado.

Gera, na pasta da biblioteca:
  CATALOGO.html  abre em qualquer navegador, mesmo sem o app: busca, categorias, título, tamanho e link
                 para abrir cada PDF/EPUB direto do HD;
  CATALOGO.csv   a mesma lista para planilha (Excel, LibreOffice).

O instalador gera no fim de cada rodada. Para gerar à mão:
  Programas\\Python\\python.exe _Baixador\\instalador\\gerar_catalogo.py
"""
import csv
import html
import json
import os
import re
import sys
import time

AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ = os.path.dirname(os.path.dirname(AQUI))
if AQUI not in sys.path:
    sys.path.insert(0, AQUI)
import categorias  # noqa: E402

PASTAS = ('1_Enciclopedia', '2_Mapas', '4_IA', '5_Acervo', '6_Instaladores')
PULAR = ('.ok', '.part', '.aria2', '.blocos', '.montando', '.tmp')
# nomes legíveis das enciclopédias mais comuns (o resto vira o nome do arquivo arrumado)
PROJETOS = {
    'wikipedia': 'Wikipédia', 'wiktionary': 'Wikcionário', 'wikibooks': 'Wikilivros', 'wikisource': 'Wikisource',
    'wikivoyage': 'Wikivoyage', 'wikiquote': 'Wikiquote', 'wikiversity': 'Wikiversidade', 'vikidia': 'Vikidia',
    'gutenberg': 'Projeto Gutenberg', 'mdwiki': 'MDWiki (medicina)', 'ifixit': 'iFixit (consertos)',
    'appropedia': 'Appropedia (tecnologia apropriada)', 'phet': 'PhET (simulações de ciências)',
    'survivorlibrary': 'Survivor Library', 'libretexts': 'LibreTexts', 'wikem': 'WikEM (emergência médica)',
    'medlineplus.gov': 'MedlinePlus', 'theworldfactbook': 'The World Factbook', 'armypubs': 'Manuais do Exército dos EUA',
    'energypedia': 'Energypedia', 'cd3wdproject.org': 'CD3WD (desenvolvimento)', 'ready.gov': 'Ready.gov (emergências)',
    'zimgit-water': 'Água (zimgit)', 'zimgit-medicine': 'Medicina (zimgit)', 'zimgit-knots': 'Nós (zimgit)',
    'zimgit-post-disaster': 'Pós-desastre (zimgit)', 'zimgit-food-preparation': 'Preparo de alimentos (zimgit)',
}
IDIOMAS = {'pt': 'português', 'en': 'inglês', 'es': 'espanhol', 'fr': 'francês', 'mul': 'vários idiomas'}


def ler_indice(rel_pasta, t):
    """Junta em t {caminho relativo: título} o _indice.txt ('arquivo<TAB>Título') de uma pasta do acervo."""
    try:
        linhas = open(os.path.join(RAIZ, rel_pasta, '_indice.txt'), encoding='utf-8-sig', errors='replace').read().splitlines()
    except OSError:
        return
    for linha in linhas:
        arq, _, titulo = linha.partition('\t')
        if arq and titulo.strip():
            t[rel_pasta + '/' + arq.strip()] = titulo.strip()


def nome_arrumado(arq):
    nome = os.path.splitext(arq)[0]
    nome = re.sub(r'^\d+(-\d+)?_', '', nome)                # tira o número do catálogo do começo
    return re.sub(r'[_]+', ' ', nome).strip(' .') or arq


def titulo_zim(arq):
    m = re.match(r'^(.+?)_([a-z]{2,3})_(.+?)_(\d{4}-\d{2})[a-z]?\.zim$', arq)
    if not m:
        return nome_arrumado(arq)
    projeto, idioma, variante, data = m.groups()
    nome = PROJETOS.get(projeto) or PROJETOS.get('%s-%s' % (projeto, variante)) or projeto.replace('_', ' ')
    extra = re.sub(r'_?(maxi|nopic|mini|all)_?', ' ', variante).replace('_', ' ').strip()
    partes = [nome]
    if extra:
        partes.append('— ' + extra)
    partes.append('(%s, %s)' % (IDIOMAS.get(idioma, idioma), data))
    return ' '.join(partes)


def titulo_de(rel, idx):
    t = idx.get(rel)
    arq = rel.rsplit('/', 1)[-1]
    if t and not re.search(r'\w\?\w|\?\?', t):    # títulos com acento perdido ("Opera??es") ficam de fora
        return t
    if arq.lower().endswith('.zim'):
        return titulo_zim(arq)
    return nome_arrumado(arq)


def _varrer(pasta, rel_base, idx):
    """(rel, tamanho) dos arquivos e os títulos dos _indice.txt em idx. Usa só a listagem das pastas (no
    Windows ela já traz o tamanho): num HD lento, perguntar arquivo por arquivo leva muitos minutos."""
    try:
        entradas = sorted(os.scandir(pasta), key=lambda e: e.name)
    except OSError:
        return
    nomes = {e.name for e in entradas}
    if '_indice.txt' in nomes:
        ler_indice(rel_base.rstrip('/'), idx)
    for e in entradas:
        rel = rel_base + e.name
        if e.is_dir(follow_symlinks=False):
            yield from _varrer(e.path, rel + '/', idx)
        elif not (e.name.endswith(PULAR) or e.name.startswith(('_indice', '.')) or e.name + '.part' in nomes):
            try:
                yield rel, e.stat().st_size
            except OSError:
                pass


def coletar():
    idx, achados, itens = {}, [], []
    for pasta in PASTAS:
        achados.extend(_varrer(os.path.join(RAIZ, pasta), pasta + '/', idx))
    for rel, tam in achados:
        itens.append((categorias.categoria(rel), titulo_de(rel, idx), rel, tam))
    ordem = {c: n for n, (c, _) in enumerate(categorias.CATEGORIAS)}
    itens.sort(key=lambda i: (ordem.get(i[0], 99), i[1].lower()))
    return itens


PAGINA = """<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Catálogo da Biblioteca Offline</title>
<style>
:root{--papel:#f3efe4;--cartao:#fbf9f3;--tinta:#1d221b;--tinta2:#4a5046;--linha:#c9c1ab;--verde:#3f5124}
@media (prefers-color-scheme:dark){:root{--papel:#121410;--cartao:#1b1e18;--tinta:#e8e4d8;--tinta2:#a9ae9f;--linha:#3a3f34;--verde:#9fb877}}
body{margin:0;background:var(--papel);color:var(--tinta);font:15px/1.5 "Segoe UI",Roboto,Arial,sans-serif}
main{max-width:1100px;margin:0 auto;padding:20px 16px 60px}
h1{margin:.2em 0;font-family:Georgia,serif} .meta{color:var(--tinta2);font-size:.9em}
input{width:100%%;box-sizing:border-box;padding:10px 12px;font-size:16px;border:1px solid var(--linha);border-radius:6px;background:var(--cartao);color:var(--tinta);margin:12px 0}
details{background:var(--cartao);border:1px solid var(--linha);border-radius:6px;margin:8px 0;padding:6px 12px}
summary{cursor:pointer;font-weight:600;padding:4px 0} summary span{font-weight:400;color:var(--tinta2)}
ul{list-style:none;margin:6px 0;padding:0} li{padding:5px 0;border-top:1px solid var(--linha);display:flex;gap:10px;justify-content:space-between}
li a,li b{color:var(--tinta);font-weight:400} li a{color:var(--verde)} li small{color:var(--tinta2);white-space:nowrap}
.caminho{display:block;color:var(--tinta2);font-size:.8em;word-break:break-all}
button{margin:6px 0;padding:6px 12px;border:1px solid var(--linha);border-radius:6px;background:var(--papel);color:var(--tinta);cursor:pointer}
</style></head><body><main>
<h1>Catálogo da Biblioteca Offline</h1>
<p class="meta">%(total)s itens · %(gb)s GB · gerado em %(data)s. Clique num título para abrir o arquivo
(PDF, EPUB). As enciclopédias (.zim) se leem pelo app: dois cliques em <b>ABRIR_BIBLIOTECA.bat</b>.</p>
<input id="q" type="search" placeholder="Procurar por título ou pasta…" autofocus aria-label="Procurar">
<div id="lista"></div>
</main>
<script>
var CATS = %(cats)s, ITENS = %(itens)s, POR_VEZ = 300;
function esc(s){return s.replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];});}
function tam(n){return n>=1073741824?(n/1073741824).toFixed(1)+' GB':n>=1048576?(n/1048576).toFixed(1)+' MB':Math.max(1,Math.round(n/1024))+' KB';}
function linha(i){var r=ITENS[i][2],t=esc(ITENS[i][1]),abre=!/\\.(zim|gguf|pmtiles|mwm|tsv)$/i.test(r);
  return '<li><div>'+(abre?'<a href="'+encodeURI(r).replace(/#/g,'%%23')+'">'+t+'</a>':'<b>'+t+'</b>')+'<span class="caminho">'+esc(r)+'</span></div><small>'+tam(ITENS[i][3])+'</small></li>';}
function norm(s){return s.normalize('NFD').replace(/[\\u0300-\\u036f]/g,'').toLowerCase();}
var BUSCA = null;   // texto sem acento de cada item, feito na 1ª busca
function mostrar(){var q=norm(document.getElementById('q').value.trim()),grupos={},h='';
  if(q&&!BUSCA)BUSCA=ITENS.map(function(it){return norm(it[1]+' '+it[2]);});
  ITENS.forEach(function(it,i){if(!q||BUSCA[i].indexOf(q)>=0)(grupos[it[0]]=grupos[it[0]]||[]).push(i);});
  CATS.forEach(function(c){var g=grupos[c[0]];if(!g)return;var soma=g.reduce(function(s,i){return s+ITENS[i][3];},0);
    h+='<details'+(q?' open':'')+' data-c="'+c[0]+'"><summary>'+esc(c[1])+' <span>· '+g.length+' itens · '+tam(soma)+'</span></summary><ul></ul></details>';});
  var el=document.getElementById('lista');el.innerHTML=h||'<p class="meta">Nada encontrado.</p>';
  el.querySelectorAll('details').forEach(function(d){var g=grupos[d.getAttribute('data-c')],ul=d.querySelector('ul'),n=0;
    function mais(){ul.insertAdjacentHTML('beforeend',g.slice(n,n+POR_VEZ).map(linha).join(''));n+=POR_VEZ;
      var b=d.querySelector('button');if(b)b.remove();
      if(n<g.length){b=document.createElement('button');b.textContent='Mostrar mais ('+(g.length-n)+' restantes)';b.onclick=mais;d.appendChild(b);}}
    function abrir(){if(!n)mais();}
    if(d.open)abrir();d.addEventListener('toggle',abrir);});}
var espera;document.getElementById('q').addEventListener('input',function(){clearTimeout(espera);espera=setTimeout(mostrar,200);});
mostrar();
</script></body></html>
"""


def gerar(raiz=None, saida=None):
    """saida: outra pasta para os arquivos (só para testar sem gravar na biblioteca)."""
    global RAIZ
    RAIZ = raiz or RAIZ
    saida = saida or RAIZ
    itens = coletar()
    cats = [[c, n] for c, n in categorias.CATEGORIAS]
    dados = json.dumps([[c, t, r, s] for c, t, r, s in itens], ensure_ascii=False, separators=(',', ':'))
    pagina = PAGINA % {'total': '{:,}'.format(len(itens)).replace(',', '.'),
                       'gb': '%.0f' % (sum(i[3] for i in itens) / 2**30), 'data': time.strftime('%d/%m/%Y %H:%M'),
                       'cats': json.dumps(cats, ensure_ascii=False), 'itens': dados.replace('</', '<\\/')}
    destino = os.path.join(saida, 'CATALOGO.html')
    with open(destino + '.tmp', 'w', encoding='utf-8') as f:
        f.write(pagina)
    os.replace(destino + '.tmp', destino)
    nomes = dict(categorias.CATEGORIAS)
    with open(os.path.join(saida, 'CATALOGO.csv') + '.tmp', 'w', encoding='utf-8-sig', newline='') as f:
        w = csv.writer(f, delimiter=';')
        w.writerow(['Categoria', 'Título', 'Tamanho (MB)', 'Caminho'])
        for c, t, r, s in itens:
            w.writerow([nomes.get(c, c), t, ('%.1f' % (s / 2**20)).replace('.', ','), r.replace('/', '\\')])
    os.replace(os.path.join(saida, 'CATALOGO.csv') + '.tmp', os.path.join(saida, 'CATALOGO.csv'))
    return len(itens)


if __name__ == '__main__':
    print('Catálogo: %d itens em %s' % (gerar(), os.path.join(RAIZ, 'CATALOGO.html')))
