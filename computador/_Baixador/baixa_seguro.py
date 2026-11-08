"""Download de arquivos grandes com retomada, com ou sem o curl.exe do Windows.

O Windows 10 (desde 2018) e o 11 trazem o curl.exe, mas há PCs sem ele (Windows antigo, PATH alterado,
curl removido). Antes, nesses PCs todo arquivo grande falhava na hora com "[WinError 2] O sistema não
pode encontrar o arquivo especificado". Agora:
  1. procura o curl.exe (Programas\\curl, Programas\\Python, System32, PATH);
  2. se não houver, baixa pelo próprio Python, também retomável (cabeçalho Range).

Uso:  p = iniciar(url, destino, ua)   ->  objeto com .poll() e .wait() (como subprocess.Popen)
      codigo = p.wait()               ->  0 = terminou bem; outro número = falhou (pode tentar de novo)
"""
import os
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.request

AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ = os.path.dirname(AQUI)
SEM_JANELA = 0x08000000 if os.name == 'nt' else 0
_curl = {'achado': False, 'caminho': None}
CERTIFICADOS = os.path.join(AQUI, 'certificados', 'cacert.pem')


def usar_certificados():
    """No Windows, o Python só confia nos certificados que o Windows já tem guardados; num Windows sem
    atualizar, faltam alguns (ex.: o do Let's Encrypt, usado pelo Kiwix) e todo HTTPS falha. Somamos os da
    Mozilla (certifi) aos do Windows. Vale para este processo e para os que ele abrir."""
    if os.path.isfile(CERTIFICADOS) and not os.environ.get('SSL_CERT_FILE'):
        os.environ['SSL_CERT_FILE'] = CERTIFICADOS


usar_certificados()


def achar_curl():
    if _curl['achado']:
        return _curl['caminho']
    candidatos = [os.path.join(RAIZ, 'Programas', 'curl', 'curl.exe'),
                  os.path.join(RAIZ, 'Programas', 'curl', 'bin', 'curl.exe'),
                  os.path.join(RAIZ, 'Programas', 'Python', 'curl.exe'),
                  os.path.join(os.environ.get('SystemRoot', r'C:\Windows'), 'System32', 'curl.exe'),
                  shutil.which('curl.exe') or shutil.which('curl')]
    achado = None
    for c in candidatos:
        if c and os.path.isfile(c):
            try:   # confere se roda de verdade
                subprocess.run([c, '--version'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               timeout=20, creationflags=SEM_JANELA, check=True)
                achado = c
                break
            except Exception:
                continue
    _curl.update(achado=True, caminho=achado)
    return achado


def _python_baixa(url, destino, ua, parar):
    """Baixa com retomada: continua do tamanho que já existe em destino. Devolve 0 se terminou."""
    os.makedirs(os.path.dirname(destino) or '.', exist_ok=True)
    for tentativa in range(1, 6):
        if parar.is_set():
            return 1
        ja = os.path.getsize(destino) if os.path.exists(destino) else 0
        cab = {'User-Agent': ua}
        if ja:
            cab['Range'] = 'bytes=%d-' % ja
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=cab), timeout=120) as r:
                status = getattr(r, 'status', 200)
                if ja and status == 200:      # o site não aceita retomar: recomeça do zero
                    modo = 'wb'
                else:
                    modo = 'ab'
                with open(destino, modo) as f:
                    while True:
                        if parar.is_set():
                            return 1
                        bloco = r.read(1 << 20)
                        if not bloco:
                            break
                        f.write(bloco)
            return 0
        except urllib.error.HTTPError as e:
            if e.code == 416:                 # já estava completo
                return 0
            if e.code in (403, 404, 410):
                return 22
        except Exception:
            pass
        time.sleep(15 * tentativa)
    return 28


class _Processo:
    """Imita o subprocess.Popen para o download feito em Python."""

    def __init__(self, url, destino, ua):
        self._parar = threading.Event()
        self.returncode = None
        self._t = threading.Thread(target=self._roda, args=(url, destino, ua), daemon=True)
        self._t.start()

    def _roda(self, url, destino, ua):
        try:
            self.returncode = _python_baixa(url, destino, ua, self._parar)
        except Exception:
            self.returncode = 1

    def poll(self):
        return None if self._t.is_alive() else self.returncode

    def wait(self, timeout=None):
        self._t.join(timeout)
        return self.poll()

    def terminate(self):
        self._parar.set()

    kill = terminate


def iniciar(url, destino, ua, mostrar=False):
    curl = achar_curl()
    if curl:
        saida = {} if mostrar else {'stdout': subprocess.DEVNULL, 'stderr': subprocess.DEVNULL}
        return subprocess.Popen([curl, '-s', '-S', '-L', '-f', '-C', '-', '--retry', '5', '--retry-delay', '15',
                                 '--connect-timeout', '30', '--speed-limit', '20000', '--speed-time', '120',
                                 '-A', ua, '-o', destino, url], creationflags=SEM_JANELA, **saida)
    return _Processo(url, destino, ua)


def baixar(url, destino, ua):
    """Atalho: baixa e espera. Devolve 0 se terminou bem."""
    return iniciar(url, destino, ua).wait()
