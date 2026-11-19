"""BIBLIOTECA OFFLINE - instalador a partir do kit.

Recria a biblioteca inteira na pasta onde o kit foi descompactado (de preferência a raiz de um HD/SSD
vazio de 1 TB): baixa cada arquivo do mesmo endereço de onde a biblioteca original foi baixada
(lista em manifesto.tsv.gz), instala os programas e refaz o que é gerado aqui (mapa do PC, textos
religiosos). Retomável: se parar (internet, energia, fechou a janela), rode de novo e ele continua.

Uso:  INSTALAR_BIBLIOTECA.bat              (instala / continua)
      INSTALAR_BIBLIOTECA.bat --verificar  (só mostra o que falta, não baixa nada)
      INSTALAR_BIBLIOTECA.bat --escolher   (muda o que baixar: as categorias)
      CONFERIR_BIBLIOTECA.bat              (= --auditar: acha arquivo estragado e oferece baixar de novo)
      COPIAR_RELATORIO.bat                 (= --relatorio: texto com a situação, já copiado, para pedir ajuda)
"""
import collections
import concurrent.futures
import gzip
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import zipfile
import zlib

AQUI = os.path.dirname(os.path.abspath(__file__))
BAIXADOR = os.path.dirname(AQUI)
RAIZ = os.path.dirname(BAIXADOR)
MANIFESTO = os.path.join(AQUI, 'manifesto.tsv.gz')
REGISTRO = os.path.join(AQUI, 'instalacao_registro.txt')
TROCAS = os.path.join(AQUI, 'instalacao_trocas.json')
FALHAS = os.path.join(AQUI, 'instalacao_falhas.json')     # motivos da última rodada, para o relatório
TESTE = os.path.join(AQUI, 'instalacao_teste.json')       # resultado do teste antes de começar
RELATORIO = os.path.join(RAIZ, 'RELATORIO_PARA_AJUDA.txt')
VERSAO = '2026-11-19'
PYTHON = sys.executable
UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) BibliotecaOffline/1.0'
FOLGA_GB = 5
PARALELO = 6
PARALELO_LENTO = 2   # o web.archive.org bloqueia por horas quem pede muito de uma vez
DESISTE_LENTO = 12   # tantas falhas seguidas no web.archive.org = ele não está entregando agora: deixa para a próxima rodada
SEM_JANELA = 0x08000000 if os.name == 'nt' else 0
GRUPOS = {1: 'Programas e IA', 2: 'Saúde, sobrevivência e religião', 3: 'Enciclopédias essenciais, mapas e instaladores',
          4: 'Agricultura, Exército, livros acadêmicos', 5: 'Demais enciclopédias', 6: 'Gutenberg, IAs extras e o resto',
          7: 'PDFs guardados no web.archive.org (servidor lento)',
          8: 'Arquivos gigantes (Survivor Library, Wikipédia em inglês)'}
PARTE_LENTA = 7   # o web.archive.org às vezes fica quase parado: o resto não espera por ele
PARTE_GIGANTE = 8   # um arquivo de 200 GB leva dias: fica por último, para o resto não esperar
GIGANTE = 50 * 2**30
PROGRAMAS = [  # zip em 6_Instaladores -> pasta em Programas
    ('kiwix-tools_win', 'KiwixServe'), ('kiwix-desktop_windows', 'Kiwix'), ('llama-b', 'LlamaCpp')]

sys.path.insert(0, BAIXADOR)
sys.path.insert(0, AQUI)
import baixa_seguro  # noqa: E402  (ativa os certificados e o download sem curl)
from baixa_seguro import marcar_ok, ocultar  # noqa: E402
import categorias  # noqa: E402  (o que a pessoa quer baixar)
GERADOS_GB = {'mapas': 6, 'religiao': 0.1}   # feitos aqui por script, fora do manifesto
_trava = threading.Lock()
estado = {'feitos': 0, 'bytes': 0, 'falhas': [], 'sem_espaco': [], 'pulados': [], 'lento_parado': threading.Event(), 'trocados': {}, 'diferentes': 0,
          'inicio': time.time(), 'total_bytes': 0, 'total_arquivos': 0,
          'baixado': 0,     # bytes que vieram mesmo pela internet nesta rodada (para a velocidade)
          'ativos': {},     # arquivo .part em andamento -> (tamanho quando começou, tamanho esperado)
          'motivos': {},    # caminho -> por que falhou (vai para o relatório)
          'teste': {}}
FRAGEIS = ('web.archive.org/', 'trueprepper.com/')   # lentos ou que recusam: com cópia nossa, ela vai na frente


def escreve(msg, tela=True):
    linha = '[%s] %s' % (time.strftime('%d/%m %H:%M'), msg)
    if tela:
        print(linha, flush=True)
    with _trava:
        with open(REGISTRO, 'a', encoding='utf-8') as f:
            f.write(linha + '\n')


def gb(n):
    return '%.1f' % (n / 2**30)


def livre():
    return shutil.disk_usage(RAIZ).free


def ler_manifesto():
    itens = []
    with gzip.open(MANIFESTO, 'rt', encoding='utf-8') as f:
        for linha in f:
            if linha.startswith('#') or not linha.strip():
                continue
            campos = linha.rstrip('\n').split('\t')
            prio, tipo, rel, tam, url = campos[:5]
            sha = campos[5].strip().lower() if len(campos) > 5 else ''   # 6ª coluna (opcional): SHA-256
            reserva = campos[6].strip() if len(campos) > 6 else ''      # 7ª coluna (opcional): outro endereço
            itens.append({'prio': int(prio), 'tipo': tipo, 'rel': rel, 'tam': int(tam or 0), 'url': url,
                          'sha': sha if len(sha) == 64 else '', 'reserva': reserva if reserva.startswith('http') else ''})
    return itens


def enderecos(item):
    """Os endereços a tentar, em ordem: o da lista e o reserva (na frente, se o da lista é de um site frágil)."""
    url, reserva = item['url'], item.get('reserva', '')
    if not reserva:
        return [url]
    return [reserva, url] if any(x in url for x in FRAGEIS) else [url, reserva]


def sha256_de(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for bloco in iter(lambda: f.read(4 << 20), b''):
            h.update(bloco)
    return h.hexdigest()


def data_do_manifesto():
    try:
        with gzip.open(MANIFESTO, 'rt', encoding='utf-8') as f:
            cab = f.readline()
        return cab.split('gerado em')[1].strip(' )\n') if 'gerado em' in cab else '?'
    except (OSError, IndexError):
        return '?'


def caminho(rel):
    return os.path.join(RAIZ, *rel.split('/'))


def pdf_cortado(p, tam):
    """PDF menor que o da lista e sem o final: veio cortado de um kit antigo. Só lê o fim do arquivo."""
    t = os.path.getsize(p)
    if not p.lower().endswith('.pdf') or t >= tam:
        return False
    with open(p, 'rb') as f:
        f.seek(max(0, t - 1029))
        return b'%%EOF' not in f.read()


def pronto(item):
    p = caminho(item['rel'])
    if os.path.exists(p + '.ok') and os.path.isfile(p) and os.path.getsize(p) > 0:
        return not pdf_cortado(p, item['tam'])
    novo = estado['trocados'].get(item['rel'])
    return bool(novo and os.path.exists(caminho(novo) + '.ok'))


MAGICOS = {'.pdf': b'%PDF', '.epub': b'PK', '.zip': b'PK', '.apk': b'PK', '.zim': b'ZIM\x04', '.gguf': b'GGUF'}


def conteudo_certo(p, rel):
    ext = os.path.splitext(rel)[1].lower()
    try:
        with open(p, 'rb') as f:
            ini = f.read(8)
    except OSError:
        return False
    if ext in MAGICOS:
        return ini.startswith(MAGICOS[ext])
    return len(ini) > 0 and not ini.lstrip().lower().startswith((b'<!doc', b'<html'))


def tamanho(p):
    try:
        return os.path.getsize(p)
    except OSError:
        return 0


def zim_inteiro(p):
    """O cabeçalho do ZIM diz onde o arquivo termina: confere se chegou até lá (pega download cortado)."""
    try:
        with open(p, 'rb') as f:
            cab = f.read(80)
        import struct
        return len(cab) == 80 and cab[:4] == b'ZIM\x04' and os.path.getsize(p) == struct.unpack_from('<Q', cab, 72)[0] + 16
    except OSError:
        return False


def baixar_url(url, destino, tam, tentativas=5, espera=180):
    """Baixa para destino.part (retomável para arquivos grandes) e só no fim troca o nome.
    Arquivo grande que não chegou inteiro fica como .part (a próxima rodada continua dele) e conta como falha."""
    parcial = destino + '.part'
    os.makedirs(os.path.dirname(destino), exist_ok=True)
    grande = tam > 50 * 2**20 or tam == 0 and url.endswith(('.zim', '.gguf', '.map', '.exe'))
    inicial = tamanho(parcial) if grande else 0
    with _trava:
        estado['ativos'][parcial] = (inicial, tam)
    try:
        if grande:   # baixa_seguro: usa o curl.exe se existir; senão baixa pelo Python (retomável)
            rc = 1
            for t in range(1, tentativas + 1):
                rc = baixa_seguro.baixar(url, parcial, UA)
                feito = tamanho(parcial)
                if rc == 0 or (tam and feito >= tam):
                    break
                if rc == 22 and t >= 2:   # o servidor recusou (ex.: 403, 404): insistir não adianta
                    break
                time.sleep(10 * t)
            feito = tamanho(parcial)
            if rc != 0 and (not tam or feito < tam):
                raise RuntimeError('veio incompleto (%s de %s GB)' % (gb(feito), gb(tam)) if feito else 'não baixou')
        else:
            for t in range(1, tentativas):
                try:
                    req = urllib.request.Request(url, headers={'User-Agent': UA})
                    with urllib.request.urlopen(req, timeout=espera) as r, open(parcial, 'wb') as f:
                        shutil.copyfileobj(r, f, 1 << 16)
                    break
                except urllib.error.HTTPError as e:
                    if e.code in (404, 410):
                        raise
                    time.sleep(8 * t)
                except Exception:
                    time.sleep(8 * t)
        if tamanho(parcial) == 0:
            raise RuntimeError('não baixou')
        if destino.endswith('.zim') and not zim_inteiro(parcial):
            raise RuntimeError('veio incompleto (%s GB)' % gb(tamanho(parcial)))
        if destino.endswith('.zim'):
            if tamanho(parcial) > 2**30:
                escreve('Conferindo a enciclopédia %s (%s GB)...' % (os.path.basename(destino), gb(tamanho(parcial))))
            if not baixa_seguro.zim_md5_certo(parcial):
                os.remove(parcial)   # chegou inteiro mas estragado no meio: só baixando de novo
                raise RuntimeError('veio estragado (soma de verificação errada)')
        os.replace(parcial, destino)
    finally:
        with _trava:
            estado['ativos'].pop(parcial, None)
            estado['baixado'] += max(0, (tamanho(destino) if not os.path.exists(parcial) else tamanho(parcial)) - inicial)


def espelhos(url):
    """Outros servidores com o mesmo arquivo do Kiwix (lista .meta4). O Kiwix manda cada pessoa para um
    espelho perto dela; se esse espelho falha ou ainda não tem o arquivo, os outros costumam ter."""
    if 'download.kiwix.org/' not in url:
        return []
    try:
        lista = url.replace('://download.kiwix.org/', '://lb.download.kiwix.org/') + '.meta4'
        with urllib.request.urlopen(urllib.request.Request(lista, headers={'User-Agent': UA}), timeout=60) as r:
            texto = r.read().decode('utf-8', 'replace')
    except Exception:
        return []
    achados = re.findall(r'<url[^>]*>\s*(https?://[^<\s]+)\s*</url>', texto)
    return [u for u in dict.fromkeys(achados) if u != url][:6]


def baixar(url, destino, tam):
    """Baixa do endereço da lista; se falhar e for do Kiwix, tenta os outros espelhos antes de desistir."""
    if 'web.archive.org/' in url:   # quando ele bloqueia, a conexão fica pendurada: falha logo e deixa o resto andar
        return baixar_url(url, destino, tam, tentativas=3, espera=60)
    try:
        return baixar_url(url, destino, tam)
    except Exception as e:
        erro = e
    for alt in espelhos(url):
        try:
            baixar_url(alt, destino, tam, tentativas=2)
            escreve('Baixado de outro servidor do Kiwix: %s' % os.path.basename(destino), tela=False)
            return
        except Exception as e:
            erro = e
    raise erro


def baixar_de_algum(item):
    """Tenta cada endereço do item (o da lista e o reserva). Devolve o que funcionou; se nenhum, sobe o 1º erro."""
    primeiro = None
    for n, url in enumerate(enderecos(item)):
        try:
            baixar(url, caminho(item['rel']), item['tam'])
            if n:
                escreve('%s: o primeiro endereço falhou; veio do endereço reserva.' % os.path.basename(item['rel']), tela=False)
            return url
        except Exception as e:
            primeiro = primeiro or e
    raise primeiro


def falhou(rel, motivo, tela=False):
    escreve('FALHOU: %s (%s)' % (rel, motivo), tela=tela)
    with _trava:
        estado['motivos'][rel] = motivo
    return 'falha'


def zim_novo(item):
    """Enciclopédia cujo arquivo saiu do ar: pega a versão mais nova do mesmo livro no catálogo do Kiwix."""
    import re
    import baixar_conhecimento as bc
    nome = os.path.basename(item['rel'])
    m = re.match(r'^(.+?)(?:_(maxi|nopic|mini))?_(\d{4}-\d{2})[a-z]?\.zim$', nome)
    if not m:
        return None
    achado = bc.procurar(m.group(1), m.group(2) or '')
    if not achado:
        return None
    url, tam = achado
    return url, '1_Enciclopedia/' + url.rsplit('/', 1)[-1], tam


def instalar_item(item):
    if pronto(item):
        return 'ja'
    if estado['lento_parado'].is_set() and 'web.archive.org/' in item['url']:
        return 'pulado'
    p = caminho(item['rel'])
    falta = item['tam'] - (os.path.getsize(p + '.part') if os.path.exists(p + '.part') else 0)
    if livre() - max(0, falta) < FOLGA_GB * 2**30:
        return 'espaco'
    url, rel, tam = item['url'], item['rel'], item['tam']
    try:
        url = baixar_de_algum(item)
    except Exception as e:
        if item['tipo'] != 'zim':
            return falhou(rel, str(e), tela=tam > 200 * 2**20)   # arquivo grande (ex.: IA): mostra na tela
        novo = zim_novo(item)
        if not novo or novo[1] == rel:
            # mesmo arquivo no catálogo: não saiu do ar, os servidores é que não entregaram agora
            return falhou(rel, '%s. Rode a instalação de novo mais tarde' % e if novo else 'saiu do catálogo do Kiwix',
                          tela=True)
        url, rel, tam = novo
        escreve('Enciclopédia fora do ar; baixando a versão nova: %s' % os.path.basename(rel))
        try:
            baixar(url, caminho(rel), tam)
        except Exception as e2:
            return falhou(rel, str(e2), tela=True)
        with _trava:
            estado['trocados'][item['rel']] = rel
            with open(TROCAS, 'w', encoding='utf-8') as f:
                json.dump(estado['trocados'], f, ensure_ascii=False, indent=1)
    p = caminho(rel)
    if not conteudo_certo(p, rel):
        os.remove(p)
        return falhou(rel, 'o site devolveu outra coisa (página de erro?)')
    if pdf_cortado(p, tam):
        os.remove(p)
        return falhou(rel, 'o PDF veio cortado')
    if (item['sha'] and rel == item['rel'] and not rel.endswith('.zim') and os.path.getsize(p) == tam
            and sha256_de(p) != item['sha']):
        os.remove(p)   # mesmo tamanho do original e conteúdo diferente: veio estragado no caminho
        return falhou(rel, 'veio corrompido (a conferência SHA-256 não bateu)')
    if tam and os.path.getsize(p) != tam:
        estado['diferentes'] += 1   # o arquivo mudou no site desde a biblioteca original; vale igual
    marcar_ok(p, url + '\n')
    if os.path.getsize(p) > 200 * 2**20:
        escreve('OK: %s (%s GB)' % (os.path.basename(rel), gb(os.path.getsize(p))))
    return 'ok'


def ocultar_marcas():
    """Bibliotecas instaladas antes de 19/11/2026 têm as marcas .ok à mostra no Explorador (52 mil arquivos
    pequenos entre os livros). Esconde todas uma vez; as novas já nascem ocultas (baixa_seguro.marcar_ok)."""
    feito = os.path.join(AQUI, 'instalacao_ok_ocultos')
    if os.path.exists(feito):
        return
    print('Escondendo as marcas .ok antigas (uma vez só)...', flush=True)
    n = 0
    pendentes = [os.path.join(RAIZ, p) for p in ('1_Enciclopedia', '2_Mapas', '4_IA', '5_Acervo', '6_Instaladores', 'Programas')]
    while pendentes:
        try:
            entradas = list(os.scandir(pendentes.pop()))
        except OSError:
            continue
        for e in entradas:   # a listagem da pasta já diz se está oculto: só mexe no que precisa
            if e.is_dir(follow_symlinks=False):
                pendentes.append(e.path)
            elif e.name.endswith('.ok') and not getattr(e.stat(), 'st_file_attributes', 2) & 2:
                ocultar(e.path)
                n += 1
    open(feito, 'w').write('%d marcas .ok ocultadas em %s\n' % (n, time.strftime('%d/%m/%Y %H:%M')))
    if n:
        escreve('As %d marcas ".ok" (controle do instalador) agora ficam ocultas no Explorador.' % n, tela=False)


def criar_atalho():
    """Põe na pasta da biblioteca o "ABRIR BIBLIOTECA.exe" com ícone, compilado AQUI com o compilador C# do próprio
    Windows (um .exe baixado pronto da internet assusta o Windows e alguns antivírus). Se a biblioteca está na raiz
    de um disco, o disco também ganha o ícone e o nome "Biblioteca Offline" (autorun.inf: o Windows só usa o ícone e
    o nome, não executa nada). Sem o compilador, fica o ABRIR_BIBLIOTECA.bat de sempre."""
    if os.name != 'nt':
        return
    pasta = os.path.join(AQUI, 'atalho')
    icone = os.path.join(RAIZ, 'App', 'biblioteca.ico')
    try:
        if not os.path.exists(icone):
            shutil.copyfile(os.path.join(pasta, 'biblioteca.ico'), icone)
        exe = os.path.join(RAIZ, 'ABRIR BIBLIOTECA.exe')
        windir = os.environ.get('WINDIR', r'C:\Windows')
        csc = next((c for c in (os.path.join(windir, 'Microsoft.NET', f, 'v4.0.30319', 'csc.exe')
                                for f in ('Framework64', 'Framework')) if os.path.exists(c)), None)
        if csc and not os.path.exists(exe):
            fonte = os.path.join(AQUI, 'instalacao_atalho.cs')
            shutil.copyfile(os.path.join(pasta, 'atalho.cs.modelo'), fonte)
            r = subprocess.run([csc, '/nologo', '/target:winexe', '/optimize+', '/codepage:65001',
                                '/r:System.Windows.Forms.dll', '/win32icon:' + icone, '/out:' + exe, fonte],
                               capture_output=True, text=True, creationflags=SEM_JANELA, timeout=120)
            os.remove(fonte)
            escreve('Atalho com ícone: %s' % ('ABRIR BIBLIOTECA.exe' if r.returncode == 0 else
                                               'não deu (%s); use o ABRIR_BIBLIOTECA.bat' % r.stdout.strip()[:200]),
                    tela=False)
        if os.path.normcase(os.path.abspath(RAIZ)) == os.path.normcase(os.path.splitdrive(os.path.abspath(RAIZ))[0] + '\\'):
            aut = os.path.join(RAIZ, 'autorun.inf')
            if not os.path.exists(aut):
                with open(aut, 'w', encoding='ascii', newline='') as f:
                    f.write('[autorun]\r\nicon=App\\biblioteca.ico\r\nlabel=Biblioteca Offline\r\n')
                ocultar(aut)
    except Exception as e:
        escreve('Não consegui criar o atalho com ícone (%s); use o ABRIR_BIBLIOTECA.bat.' % e, tela=False)


def auditar(itens, fora):
    """CONFERIR_BIBLIOTECA.bat: confere o que já foi baixado (arquivo sumido, tipo errado, PDF cortado, ZIP
    quebrado e a soma de verificação de cada enciclopédia) e oferece apagar o que estiver estragado, para o
    instalador baixar de novo. --rapido pula a soma das enciclopédias (a parte demorada)."""
    rapido = '--rapido' in sys.argv
    print('Conferindo a biblioteca em %s' % RAIZ)
    if not rapido:
        print('A conferência completa lê cada arquivo inteiro (a "impressão digital" de cada um): num HD pode')
        print('levar horas. Para uma conferência rápida, sem essa parte: CONFERIR_BIBLIOTECA.bat --rapido')
    print(flush=True)
    problemas, zims, conferidos, t0 = [], [], 0, time.time()
    for n, i in enumerate(itens, 1):
        if n % 5000 == 0:
            print('   … %d de %d conferidos (%d s)' % (n, len(itens), time.time() - t0), flush=True)
        if i['tipo'] == 'gerado' or not categorias.quer(i['rel'], fora):
            continue
        rel = estado['trocados'].get(i['rel']) or i['rel']
        p = caminho(rel)
        tem_ok = os.path.exists(p + '.ok')
        if not os.path.isfile(p):
            if tem_ok:
                problemas.append((rel, 'sumiu (estava marcado como baixado)'))
            continue
        if not tem_ok:
            continue   # pela metade: o instalador continua dele
        conferidos += 1
        ext = os.path.splitext(rel)[1].lower()
        if not conteudo_certo(p, rel):
            problemas.append((rel, 'não é o tipo de arquivo esperado (página de erro?)'))
        elif pdf_cortado(p, i['tam'] if rel == i['rel'] else 0):
            problemas.append((rel, 'PDF cortado (menor que o original e sem o final)'))
        elif (not rapido and i['sha'] and rel == i['rel'] and ext != '.zim' and tamanho(p) == i['tam']
              and sha256_de(p) != i['sha']):
            problemas.append((rel, 'corrompido (a conferência SHA-256 não bateu)'))
        elif ext == '.zim':
            zims.append(rel)
        elif ext in ('.zip', '.epub', '.apk'):
            try:
                zipfile.ZipFile(p).close()   # lê o índice do fim do arquivo: pega zip cortado
            except (zipfile.BadZipFile, OSError):
                problemas.append((rel, 'arquivo compactado quebrado'))
    for n, rel in enumerate(sorted(zims, key=lambda r: tamanho(caminho(r))), 1):
        p = caminho(rel)
        if not zim_inteiro(p):
            problemas.append((rel, 'enciclopédia incompleta'))
            continue
        if rapido:
            continue
        print('   Enciclopédia %d de %d: %s (%s GB)' % (n, len(zims), os.path.basename(rel), gb(tamanho(p))), flush=True)
        passo = {'ultimo': 0}

        def andamento(lidos, total):
            if lidos - passo['ultimo'] >= 10 * 2**30:
                passo['ultimo'] = lidos
                print('      … %s de %s GB' % (gb(lidos), gb(total)), flush=True)
        if not baixa_seguro.zim_md5_certo(p, andamento):
            problemas.append((rel, 'enciclopédia estragada (soma de verificação errada)'))
    print()
    escreve('Conferência: %d arquivo(s) conferido(s) (%d enciclopédia(s)%s), %d com problema.'
            % (conferidos, len(zims), ' sem a soma de verificação' if rapido else '', len(problemas)))
    if not problemas:
        print('Tudo certo.')
        return
    for rel, motivo in problemas:
        escreve('PROBLEMA: %s: %s' % (rel, motivo), tela=False)
    for rel, motivo in problemas[:30]:
        print('   - %s: %s' % (rel, motivo))
    if len(problemas) > 30:
        print('   … e mais %d (lista completa em %s)' % (len(problemas) - 30, REGISTRO))
    limpar_teclas()
    r = perguntar('\nApagar %s para o instalador baixar de novo? (s/n) '
                  % ('esse arquivo' if len(problemas) == 1 else 'esses %d arquivos' % len(problemas)))
    if not r.lower().startswith('s'):
        print('Nada foi apagado.')
        return
    for rel, _ in problemas:
        for f in (caminho(rel), caminho(rel) + '.ok'):
            try:
                os.remove(f)
            except OSError:
                pass
    escreve('Apagado(s) %d arquivo(s) com problema.' % len(problemas))
    print('Agora dê dois cliques em INSTALAR_BIBLIOTECA.bat: ele baixa de novo só o que foi apagado.')


def foto_andamento():
    """(bytes vindos da internet até agora, bytes dos arquivos pela metade, o maior arquivo em andamento)."""
    with _trava:
        ativos = dict(estado['ativos'])
        baixado = estado['baixado']
    meio, maior = 0, None
    for p, (inicial, tam) in ativos.items():
        t = tamanho(p)
        baixado += max(0, t - inicial)
        meio += t
        if tam > 200 * 2**20 and (maior is None or tam > maior[2]):
            maior = (os.path.basename(p)[:-5], t, tam)
    return baixado, meio, maior


def texto_tempo(seg):
    if seg >= 48 * 3600:
        return '~%d dias' % round(seg / 86400)
    return '~%d h %02d min' % (seg // 3600, seg % 3600 // 60)


def mostrar_andamento(parar):
    """A velocidade é a dos últimos 10 minutos, contando o que está chegando nos arquivos grandes; antes a tela
    só contava arquivo terminado e mostrava a média desde o começo ("velocidade caindo", "faltam 1300 h")."""
    amostras = collections.deque()
    while not parar.wait(30):
        agora = time.time()
        baixado, meio, maior = foto_andamento()
        amostras.append((agora, baixado))
        while len(amostras) > 2 and agora - amostras[0][0] > 600:
            amostras.popleft()
        t0, b0 = amostras[0]
        vel = (baixado - b0) / (agora - t0) if agora - t0 >= 60 else 0
        feito = min(estado['total_bytes'], estado['bytes'] + meio)
        falta = max(0, estado['total_bytes'] - feito)
        partes = ['   … %d de %d arquivos · %s de %s GB' % (estado['feitos'], estado['total_arquivos'], gb(feito),
                                                           gb(estado['total_bytes']))]
        if estado['falhas']:
            partes.append('%d não vieram' % len(estado['falhas']))
        if vel:
            partes.append('%.1f MB/s' % (vel / 2**20))
            if vel > 2e4:
                partes.append('faltam %s' % texto_tempo(falta / vel))
        if maior:
            partes.append('agora: %s (%s de %s GB)' % (maior[0], gb(maior[1]), gb(maior[2])))
        print(' · '.join(partes), flush=True)


def instalar_programas():
    for prefixo, pasta in PROGRAMAS:
        destino = os.path.join(RAIZ, 'Programas', pasta)
        marca = os.path.join(destino, '.instalado')
        zips = sorted(f for f in os.listdir(os.path.join(RAIZ, '6_Instaladores')) if f.startswith(prefixo) and f.endswith('.zip')
                      and os.path.exists(os.path.join(RAIZ, '6_Instaladores', f + '.ok'))) \
            if os.path.isdir(os.path.join(RAIZ, '6_Instaladores')) else []
        if not zips or os.path.exists(marca):
            continue
        arq = os.path.join(RAIZ, '6_Instaladores', zips[-1])
        try:
            with zipfile.ZipFile(arq) as z:
                nomes = [n for n in z.namelist() if not n.endswith('/')]
                topo = {n.split('/')[0] for n in nomes}
                corta = (len(topo) == 1 and all('/' in n for n in nomes))   # zip com uma pasta só: tira a pasta
                os.makedirs(destino, exist_ok=True)
                for n in nomes:
                    alvo = os.path.join(destino, *(n.split('/')[1:] if corta else n.split('/')))
                    os.makedirs(os.path.dirname(alvo), exist_ok=True)
                    with z.open(n) as a, open(alvo, 'wb') as b:
                        shutil.copyfileobj(a, b)
        except (zipfile.BadZipFile, zlib.error, EOFError) as e:
            # Veio estragado (download emendado errado). Apaga para baixar de novo na próxima vez, sem parar o resto.
            for f in (arq, arq + '.ok'):
                try:
                    os.remove(f)
                except OSError:
                    pass
            escreve('O instalador do %s veio estragado (%s). Apaguei: rode o INSTALAR_BIBLIOTECA.bat de novo '
                    'que ele baixa outra vez.' % (pasta, e))
            continue
        for dll in ('msvcp140.dll', 'vcruntime140.dll', 'vcruntime140_1.dll'):   # runtime do Visual C++ levado no kit
            o = os.path.join(RAIZ, 'App', 'runtime', dll)
            if os.path.exists(o) and not os.path.exists(os.path.join(destino, dll)) and pasta != 'Kiwix':
                shutil.copy2(o, destino)
        open(marca, 'w').write(zips[-1])
        escreve('Programa instalado: %s (de %s)' % (pasta, zips[-1]))


def rodar_script(nome, rotulo, *args):
    escreve('%s…' % rotulo)
    env = dict(os.environ, BIBLIOTECA_RESERVA_GB=str(FOLGA_GB))
    r = subprocess.call([PYTHON, '-I', '-X', 'utf8', os.path.join(BAIXADOR, nome)] + list(args), cwd=BAIXADOR, env=env)
    if r != 0:
        escreve('%s terminou com problema (código %s); rode a instalação de novo depois.' % (rotulo, r))



def testar_internet():
    """Há internet se QUALQUER um destes sites responder (mesmo com erro HTTP). Devolve (ok, motivo)."""
    import ssl
    motivos = []
    for url in ('https://download.kiwix.org/', 'https://github.com/', 'https://www.google.com/generate_204'):
        try:
            urllib.request.urlopen(urllib.request.Request(url, headers={'User-Agent': UA}), timeout=20).close()
            return True, ''
        except urllib.error.HTTPError:
            return True, ''                      # o servidor respondeu: há internet
        except Exception as e:
            r = getattr(e, 'reason', e)
            if isinstance(r, ssl.SSLError) or 'CERTIFICATE' in str(e).upper():
                motivos.append('%s: certificado de segurança recusado (%s)' % (url.split('/')[2], r))
            else:
                motivos.append('%s: %s' % (url.split('/')[2], r))
    return False, ' | '.join(motivos)

def perguntar(texto):
    try:
        return input(texto).strip()
    except EOFError:
        return ''


def formato_do_disco():
    """'NTFS', 'exFAT', 'FAT32'... ou '' se não der para saber."""
    if os.name != 'nt':
        return ''
    try:
        import ctypes
        unidade = os.path.splitdrive(os.path.abspath(RAIZ))[0] + '\\'
        nome = ctypes.create_unicode_buffer(64)
        ok = ctypes.windll.kernel32.GetVolumeInformationW(ctypes.c_wchar_p(unidade), None, 0, None, None, None, nome, 64)
        return nome.value if ok else ''
    except Exception:
        return ''


def velocidade_de_gravacao(mb=64):
    """MB/s gravando um arquivo de teste na pasta da biblioteca. 0 = não testou; -1 = não conseguiu gravar."""
    if livre() < (mb + 300) * 2**20:
        return 0
    p = os.path.join(AQUI, 'instalacao_teste_de_gravacao.tmp')
    bloco = os.urandom(1 << 20)
    try:
        t = time.time()
        with open(p, 'wb') as f:
            for _ in range(mb):
                f.write(bloco)
            f.flush()
            os.fsync(f.fileno())
        return mb / max(time.time() - t, 0.001)
    except OSError:
        return -1
    finally:
        try:
            os.remove(p)
        except OSError:
            pass


def velocidade_da_internet(itens, segundos=8):
    """MB/s baixando por alguns segundos o começo de um arquivo grande da lista. 0 = não deu para medir."""
    grandes = sorted((i for i in itens if i['tam'] > 300 * 2**20 and 'web.archive.org' not in i['url']),
                     key=lambda i: i['tam'])
    vistos = set()
    for i in grandes:
        host = i['url'].split('/')[2] if '//' in i['url'] else ''
        if host in vistos or len(vistos) >= 3:
            continue
        vistos.add(host)
        try:
            req = urllib.request.Request(i['url'], headers={'User-Agent': UA, 'Range': 'bytes=0-67108863'})
            n, t0 = 0, time.time()
            with urllib.request.urlopen(req, timeout=20) as r:
                while time.time() - t0 < segundos:
                    bloco = r.read(1 << 16)
                    if not bloco:
                        break
                    n += len(bloco)
            dt = time.time() - t0
            if n > 2**20 and dt > 1:
                return n / 2**20 / dt
        except Exception:
            continue
    return 0


def nao_dormir(ligar):
    """Pede ao Windows para não suspender o PC sozinho enquanto a instalação roda (leva dias)."""
    if os.name != 'nt':
        return
    try:
        import ctypes
        ctypes.windll.kernel32.SetThreadExecutionState(0x80000000 | (0x00000001 if ligar else 0))
    except Exception:
        pass


def teste_inicial(itens, faltam, total):
    """Confere o disco e a internet antes de baixar. Devolve False se não dá para seguir (ou a pessoa desistiu)."""
    print('\nTESTE ANTES DE COMEÇAR')
    t = {'quando': time.strftime('%d/%m/%Y %H:%M')}
    fmt = formato_do_disco()
    t['formato'] = fmt
    uso = shutil.disk_usage(RAIZ)
    t['disco_gb'], t['livre_gb'] = round(uso.total / 2**30), round(uso.free / 2**30)
    print('  Disco: %s GB no total, %s GB livres, formato %s.' % (gb(uso.total), gb(uso.free), fmt or 'desconhecido'))
    continuar = True
    if fmt.upper() in ('FAT32', 'FAT'):
        grandes = [i for i in faltam if i['tam'] >= 4 * 2**30 - 1]
        print('  PROBLEMA: este disco está no formato %s, que não aceita arquivos maiores que 4 GB.' % fmt)
        print('  %d arquivos da lista (%s GB) são maiores que isso e vão falhar, entre eles a Wikipédia.' % (
            len(grandes), gb(sum(i['tam'] for i in grandes))))
        print('  Solução: formate o disco como exFAT (botão direito no disco > Formatar > Sistema de arquivos: exFAT).')
        print('  ATENÇÃO: formatar APAGA tudo o que está no disco; copie o kit de novo depois.')
        continuar = perguntar('  Continuar assim mesmo, só com os arquivos menores? (s/n) ').lower().startswith('s')
    if continuar:
        v = velocidade_de_gravacao()
        t['gravacao_mb_s'] = round(v, 1)
        if v < 0:
            print('  PROBLEMA: não consegui gravar neste disco. Ele pode estar protegido contra gravação, ou o')
            print('  antivírus pode estar bloqueando (no Windows: Segurança > Proteção contra ransomware >')
            print('  Acesso controlado a pastas). Libere e rode de novo.')
            continuar = False
        elif v:
            print('  Gravação no disco: %.0f MB/s%s' % (v, '.' if v >= 15 else
                  ' (LENTO: é provável que seja um pen drive comum ou uma porta USB antiga. Funciona,\n'
                  '  mas demora mais. Se puder, use a porta USB azul do computador).'))
    if continuar:
        ok, motivo = testar_internet()
        t['internet'] = 'ok' if ok else motivo
        if not ok:
            print('  PROBLEMA: não consegui falar com os sites de download.')
            print('  Motivo: %s' % motivo)
            print('  Confira: a internet está ligada? A data e a hora do PC estão certas? O antivírus ou o firewall')
            print('  está bloqueando o Python (Programas\\Python\\python.exe)?')
            continuar = perguntar('  Tentar baixar mesmo assim? (s/n) ').lower().startswith('s')
        else:
            v = velocidade_da_internet(itens)
            t['internet_mb_s'] = round(v, 1)
            if v:
                print('  Internet: %.1f MB/s agora. Neste ritmo, os %s GB que faltam levam %s.' % (
                    v, gb(total), texto_tempo(total / (v * 2**20))))
            else:
                print('  Internet: funcionando (não deu para medir a velocidade agora).')
    if continuar and livre() < total + FOLGA_GB * 2**30:
        print('  O espaço livre NÃO dá para tudo: vou instalar por ordem de importância (o essencial primeiro)')
        print('  e pular o que não couber. Para a biblioteca completa use um HD/SSD de 1 TB vazio.')
    t['curl'] = 'sim' if baixa_seguro.achar_curl() else 'não (baixando pelo próprio Python)'
    estado['teste'] = t
    try:
        with open(TESTE, 'w', encoding='utf-8') as f:
            json.dump(t, f, ensure_ascii=False, indent=1)
    except OSError:
        pass
    print()
    return continuar


def ler_json(arq, padrao):
    try:
        with open(arq, encoding='utf-8') as f:
            return json.load(f)
    except (OSError, ValueError):
        return padrao


def texto_do_relatorio(itens, fora):
    """Texto curto com a situação da instalação, para a pessoa colar na conversa de ajuda. Sem dados pessoais."""
    import platform
    t = estado['teste'] or ler_json(TESTE, {})
    falhas = estado['motivos'] or ler_json(FALHAS, {})
    uso = shutil.disk_usage(RAIZ)
    querem = [i for i in itens if i['tipo'] != 'gerado' and categorias.quer(i['rel'], fora)]
    faltam = [i for i in querem if not pronto(i)]
    por = {}
    for i in faltam:
        k = '/'.join(i['rel'].split('/')[:2 if i['rel'].startswith(('5_Acervo', '2_Mapas')) else 1])
        n, b = por.get(k, (0, 0))
        por[k] = (n + 1, b + i['tam'])
    pasta = os.path.abspath(RAIZ)
    casa = os.path.expanduser('~')
    if casa and pasta.lower().startswith(casa.lower()):
        pasta = '(pasta do usuário)' + pasta[len(casa):]      # não leva o nome da pessoa
    L = ['RELATÓRIO DA BIBLIOTECA OFFLINE (cole este texto inteiro na conversa de ajuda)',
         'Gerado em %s · instalador %s · lista de %s' % (time.strftime('%d/%m/%Y %H:%M'), VERSAO, data_do_manifesto()),
         'Windows %s · Python %s' % (platform.version(), platform.python_version()),
         'Pasta: %s · disco %s · %s GB no total · %s GB livres' % (
             pasta, formato_do_disco() or '?', gb(uso.total), gb(uso.free))]
    if t:
        L.append('Teste de %s: gravação %s MB/s · internet %s%s · curl %s' % (
            t.get('quando', '?'), t.get('gravacao_mb_s', '?'), t.get('internet', '?'),
            ' (%s MB/s)' % t['internet_mb_s'] if t.get('internet_mb_s') else '', t.get('curl', '?')))
    L.append('Deixado de fora: %s' % (', '.join(fora) if fora else 'nada (baixando tudo)'))
    L.append('Prontos: %d de %d arquivos · faltam %s GB' % (
        len(querem) - len(faltam), len(querem), gb(sum(i['tam'] for i in faltam))))
    for k, (n, b) in sorted(por.items(), key=lambda x: -x[1][1])[:15]:
        L.append('  falta %s: %d arquivos, %s GB' % (k, n, gb(b)))
    sobras = [i for i in faltam if os.path.exists(caminho(i['rel']) + '.part')]
    for i in sorted(sobras, key=lambda i: -i['tam'])[:5]:
        L.append('  pela metade: %s (%s de %s GB)' % (
            os.path.basename(i['rel']), gb(os.path.getsize(caminho(i['rel']) + '.part')), gb(i['tam'])))
    if falhas:
        L.append('Falhas da última rodada: %d' % len(falhas))
        for m, n in collections.Counter(falhas.values()).most_common(8):
            L.append('  %d × %s' % (n, m))
        for rel, m in list(falhas.items())[:12]:
            L.append('  %s — %s' % (rel, m))
    try:
        with open(REGISTRO, encoding='utf-8', errors='replace') as f:
            fim = f.readlines()[-25:]
        L.append('Últimas linhas do registro:')
        L += ['  ' + x.rstrip()[:200] for x in fim]
    except OSError:
        L.append('Ainda não há registro: a instalação não rodou nesta pasta.')
    return '\r\n'.join(L) + '\r\n'


def copiar_relatorio(itens, fora):
    """COPIAR_RELATORIO.bat: monta o texto, grava em RELATORIO_PARA_AJUDA.txt e já deixa copiado."""
    print('Montando o relatório (pode levar um minuto)…', flush=True)
    texto = texto_do_relatorio(itens, fora)
    try:
        with open(RELATORIO, 'w', encoding='utf-8-sig', newline='') as f:
            f.write(texto)
    except OSError:
        pass
    copiado = False
    if os.name == 'nt':
        try:    # o clip.exe do Windows entende UTF-16 com a marca no começo
            subprocess.run(['clip'], input=('﻿' + texto).encode('utf-16-le'), timeout=20,
                           creationflags=SEM_JANELA, check=True)
            copiado = True
        except Exception:
            pass
    print()
    print(texto)
    if copiado:
        print('O RELATÓRIO JÁ ESTÁ COPIADO. Abra a conversa de ajuda e cole (Ctrl+V).')
    else:
        print('Não consegui copiar sozinho. O texto está no arquivo %s: abra, selecione tudo e copie.' % RELATORIO)


def menu(itens):
    """Pergunta o que NÃO baixar. ENTER = tudo. Devolve a lista de categorias deixadas de fora."""
    tam = {}
    for i in itens:
        c = categorias.categoria(i['rel'])
        tam[c] = tam.get(c, 0) + i['tam']
    for c, g in GERADOS_GB.items():
        tam[c] = tam.get(c, 0) + g * 2**30
    total = sum(tam.values())
    while True:
        print()
        print('O QUE BAIXAR')
        print('  Sempre vem:  %s  (%s GB)' % (categorias.NOMES['essencial'], gb(tam.get('essencial', 0))))
        for n, c in enumerate(categorias.OPCIONAIS, 1):
            print('  %2d  %s  (%s GB)' % (n, categorias.NOMES[c], gb(tam.get(c, 0))))
        print()
        print('  Espaço livre neste disco: %s GB.' % gb(livre()))
        print('  Para baixar TUDO (recomendado, %s GB): só tecle ENTER.' % gb(total))
        print('  Para deixar algo de fora: digite os números, separados por espaço, e tecle ENTER. Ex.: 3 10')
        r = perguntar('  > ')
        fora = categorias.ler_numeros(r)
        if fora is None:
            print('  Não entendi. Digite só números da lista, ou tecle ENTER para tudo.')
            continue
        if not fora:
            return []
        print()
        print('  Vou deixar de fora:')
        for c in fora:
            print('    - %s' % categorias.NOMES[c])
        print('  Vai baixar: %s GB.' % gb(total - sum(tam.get(c, 0) for c in fora)))
        if not perguntar('  Tecle ENTER para confirmar, ou digite N para escolher de novo: ').lower().startswith('n'):
            return fora


def limpar_teclas():
    """Tira do buffer as teclas apertadas sem querer enquanto rodava (senão respondem a pergunta ou o 'pause')."""
    try:
        import msvcrt
        while msvcrt.kbhit():
            msvcrt.getwch()
    except Exception:
        pass


def tentar_archive_de_novo(faltam):
    """No fim de tudo, tenta de novo (devagar, um por vez) os PDFs do web.archive.org que não vieram.
    Se o site continuar bloqueando, avisa e pergunta se a pessoa quer tentar mais uma vez."""
    while True:
        pend = [i for i in faltam if 'web.archive.org/' in i['url'] and not pronto(i)]
        if not pend:
            return 0
        print()
        escreve('Tentando de novo os %d PDFs do web.archive.org (agora devagar, um de cada vez)...' % len(pend))
        estado['lento_parado'].clear()
        seguidas = vieram = 0
        for n, item in enumerate(pend, 1):
            try:
                res = instalar_item(item)
            except Exception as e:
                escreve('FALHOU: %s (%s)' % (item['rel'], e), tela=False)
                res = 'falha'
            if res in ('ok', 'ja'):
                vieram += 1
                seguidas = 0
                estado['bytes'] += item['tam']
            elif res == 'falha':
                seguidas += 1
                if seguidas >= DESISTE_LENTO:
                    break
            if n % 25 == 0:
                print('   … %d de %d tentados · %d vieram' % (n, len(pend), vieram), flush=True)
        estado['de_novo'] = estado.get('de_novo', 0) + vieram
        resto = len(pend) - vieram
        if not resto:
            escreve('Os PDFs do web.archive.org vieram todos (%d).' % vieram)
            return 0
        print()
        print('AVISO: o web.archive.org continua bloqueando a sua conexão agora. Faltam %d PDFs' % resto)
        print('(Ministério da Saúde e livros SciELO). O resto da biblioteca já está pronto.')
        limpar_teclas()
        try:
            r = input('Quer tentar de novo agora? Ele espera 10 minutos antes, às vezes o bloqueio passa. (s/n): ')
        except EOFError:
            r = 'n'
        if not r.strip().lower().startswith('s'):
            print('Tudo bem. Rode o INSTALAR_BIBLIOTECA.bat outro dia: ele baixa só o que falta.')
            return resto
        escreve('Esperando 10 minutos antes de tentar de novo (pode deixar a janela aberta)...')
        time.sleep(600)


def main():
    print('=' * 72)
    print('  BIBLIOTECA OFFLINE — instalação')
    print('=' * 72)
    if '--diagnostico' in sys.argv:
        # Grava a cada 30 s onde o programa está parado (para achar travamentos sem ver a tela da pessoa).
        import faulthandler
        diag = open(os.path.join(AQUI, 'diagnostico.txt'), 'w', encoding='utf-8')
        faulthandler.dump_traceback_later(30, repeat=True, file=diag)
        print('Modo diagnóstico: anotando em _Baixador\\instalador\\diagnostico.txt', flush=True)
    if os.name == 'nt' and sys.getwindowsversion().major < 10:
        print('Atenção: a biblioteca precisa do Windows 10 ou 11.')
    print('Lendo a lista de arquivos...', flush=True)
    itens = ler_manifesto()
    if os.path.exists(TROCAS):
        estado['trocados'] = json.load(open(TROCAS, encoding='utf-8'))
    fora = categorias.ler_escolha()
    if '--leve' in sys.argv:
        fora = list(categorias.LEVE_FORA)
        categorias.gravar_escolha(fora)
        print('Versão leve (~100 GB). Para baixar mais depois: dois cliques em ESCOLHER_O_QUE_BAIXAR.bat.')
    elif '--escolher' in sys.argv or (fora is None and '--verificar' not in sys.argv and '--auditar' not in sys.argv):
        fora = menu(itens)
        categorias.gravar_escolha(fora)
    fora = fora or []
    if '--auditar' in sys.argv:
        return auditar(itens, fora)
    if '--relatorio' in sys.argv:
        return copiar_relatorio(itens, fora)
    if fora:
        print('Deixado de fora (para mudar: dois cliques em ESCOLHER_O_QUE_BAIXAR.bat):')
        for c in fora:
            print('   - %s' % categorias.NOMES[c])
    print('Conferindo o que já está no HD (pode levar alguns minutos)...', flush=True)
    faltam, t0 = [], time.time()
    for n, i in enumerate(itens, 1):
        if i['tipo'] != 'gerado' and categorias.quer(i['rel'], fora) and not pronto(i):
            faltam.append(i)
        if n % 5000 == 0:
            print('   … %d de %d conferidos (%d s)' % (n, len(itens), time.time() - t0), flush=True)
    for i in faltam:
        if 'web.archive.org/' in i['url']:
            i['prio'] = PARTE_LENTA
        elif i['tam'] > GIGANTE:
            i['prio'] = PARTE_GIGANTE
    # só para testar o kit: --max-mb 50 (pula arquivos maiores) --limite 30 (no máximo 30 por parte)
    if '--max-mb' in sys.argv:
        teto = float(sys.argv[sys.argv.index('--max-mb') + 1]) * 2**20
        faltam = [i for i in faltam if i['tam'] <= teto]
    if '--limite' in sys.argv:
        n = int(sys.argv[sys.argv.index('--limite') + 1])
        faltam = [i for p in sorted({x['prio'] for x in faltam}) for i in [x for x in faltam if x['prio'] == p][:n]]
    total = sum(i['tam'] for i in faltam)
    print('Pasta da biblioteca: %s' % RAIZ)
    print('Espaço livre aqui: %s GB · ainda falta baixar: %d arquivos, %s GB' % (gb(livre()), len(faltam), gb(total)))
    if '--verificar' in sys.argv:
        por = {}
        for i in faltam:
            k = '/'.join(i['rel'].split('/')[:2 if i['rel'].startswith(('5_Acervo', '2_Mapas')) else 1])
            por[k] = (por.get(k, (0, 0))[0] + 1, por.get(k, (0, 0))[1] + i['tam'])
        for k, (n, t) in sorted(por.items()):
            print('   falta %-45s %6d arquivos  %8s GB' % (k, n, gb(t)))
        return
    ocultar_marcas()
    if not teste_inicial(itens, faltam, total):
        return
    nao_dormir(True)   # o Windows não suspende o PC no meio (a instalação leva dias); desliga no fim
    print('Isto leva de horas a alguns dias (depende da internet). Pode deixar rodando; se parar, rode de novo.')
    print('O computador não vai dormir sozinho enquanto esta janela estiver aberta.\n')
    escreve('===== instalação: início (%d arquivos, %s GB a baixar) =====' % (len(faltam), gb(total)))
    estado.update(total_bytes=total, total_arquivos=len(faltam), inicio=time.time())
    parar = threading.Event()
    threading.Thread(target=mostrar_andamento, args=(parar,), daemon=True).start()
    try:
        for prio in sorted({i['prio'] for i in faltam}):
            grupo = [i for i in faltam if i['prio'] == prio]
            escreve('--- Parte %d de %d: %s (%d arquivos, %s GB) ---' % (prio, max(GRUPOS), GRUPOS.get(prio, ''),
                                                                         len(grupo), gb(sum(i['tam'] for i in grupo))))
            grupo.sort(key=lambda i: i['tam'])   # pequenos primeiro: a biblioteca fica útil mais cedo
            seguidas = 0
            with concurrent.futures.ThreadPoolExecutor(PARALELO_LENTO if prio == PARTE_LENTA else PARALELO) as ex:
                futuros = {ex.submit(instalar_item, i): i for i in grupo}
                for fut in concurrent.futures.as_completed(futuros):   # conta conforme cada um termina
                    item = futuros[fut]
                    try:
                        res = fut.result()
                    except Exception as e:
                        escreve('FALHOU: %s (%s)' % (item['rel'], e), tela=False)
                        res = 'falha'
                    estado['feitos'] += 1
                    if res in ('ok', 'ja'):
                        estado['bytes'] += item['tam']   # só conta o que chegou de verdade
                    if res == 'falha':
                        estado['falhas'].append(item['rel'])
                    elif res == 'espaco':
                        estado['sem_espaco'].append(item['rel'])
                    elif res == 'pulado':
                        estado['pulados'].append(item['rel'])
                    if prio == PARTE_LENTA and res in ('ok', 'falha'):
                        seguidas = seguidas + 1 if res == 'falha' else 0
                        if seguidas >= DESISTE_LENTO and not estado['lento_parado'].is_set():
                            estado['lento_parado'].set()
                            escreve('O web.archive.org não está entregando agora (%d falhas seguidas). Os PDFs de lá '
                                    'ficam para o fim (tento de novo depois de baixar o resto).' % seguidas)
            if prio == 1:
                instalar_programas()
    finally:
        parar.set()
    instalar_programas()
    # o que é gerado aqui, não baixado pronto
    if '--sem-mapa' not in sys.argv and 'mapas' not in fora:
        rodar_script('baixar_mapa_pc.py', 'Mapa do Brasil para o PC (recorte do mapa-múndi; ~6 GB)')
    if 'religiao' not in fora:
        rodar_script('baixar_religiao.py', 'Textos religiosos (Bíblias, Torá, Alcorão)')
    arquivo_web = {i['rel'] for i in faltam if 'web.archive.org/' in i['url']}
    falta_web = tentar_archive_de_novo(faltam)
    baixados = (estado['feitos'] - len(estado['falhas']) - len(estado['sem_espaco']) - len(estado['pulados'])
                + estado.get('de_novo', 0))
    estado['falhas'] = [r for r in estado['falhas'] if r not in arquivo_web]   # esses viram o aviso abaixo
    nao_dormir(False)
    try:
        with open(FALHAS, 'w', encoding='utf-8') as f:   # para o COPIAR_RELATORIO.bat
            json.dump({r: m for r, m in estado['motivos'].items() if not os.path.exists(caminho(r) + '.ok')}, f, ensure_ascii=False, indent=1)
    except OSError:
        pass
    print()
    escreve('===== instalação: fim =====')
    print('Baixados nesta rodada: %d arquivos.' % baixados)
    if estado['diferentes']:
        print('%d arquivos tinham mudado no site desde a biblioteca original (versões novas; tudo bem).' % estado['diferentes'])
    if estado['trocados']:
        print('%d enciclopédias foram trocadas pela versão mais nova.' % len(estado['trocados']))
    if estado['sem_espaco']:
        print('NÃO couberam %d arquivos (falta espaço). Lista no registro: %s' % (len(estado['sem_espaco']), REGISTRO))
        escreve('sem espaço: ' + ' | '.join(estado['sem_espaco'][:500]), tela=False)
    if falta_web:
        print('Ficaram faltando %d PDFs do web.archive.org (o site estava bloqueando a sua conexão). O resto não'
              % falta_web)
        print('depende deles. Rode a instalação de novo outro dia: ela baixa só o que falta.')
    if estado['falhas']:
        print('FALHARAM %d arquivos (site fora do ar ou endereço mudou). Rode a instalação de novo mais tarde;' % len(estado['falhas']))
        print('se continuar, os baixadores em _Baixador (ex.: BAIXAR_EMBRAPA.bat) buscam pelo catálogo atual.')
        print('Para pedir ajuda: dois cliques em COPIAR_RELATORIO.bat e cole o texto na conversa de ajuda.')
        escreve('falhas: ' + ' | '.join(estado['falhas'][:2000]), tela=False)
        for r in estado['falhas'][:15]:
            print('   - %s' % r)
    try:
        import gerar_catalogo
        gerar_catalogo.gerar(RAIZ)
        print('Lista de tudo o que está na biblioteca: CATALOGO.html (abre em qualquer navegador, sem o app).')
    except Exception as e:
        escreve('Não consegui gerar o CATALOGO.html (%s)' % e, tela=False)
    criar_atalho()
    print('\nPRONTO. Para usar: dois cliques em %s.' % (
        'ABRIR BIBLIOTECA (o atalho com o ícone do livro)' if os.path.exists(os.path.join(RAIZ, 'ABRIR BIBLIOTECA.exe'))
        else 'ABRIR_BIBLIOTECA.bat'))
    print('Guia rápido de uso: COMECE_AQUI.html.')
    print('Na primeira vez o app monta o índice de busca (algumas horas; dá para ir usando enquanto isso).')
    limpar_teclas()


if __name__ == '__main__':
    main()
