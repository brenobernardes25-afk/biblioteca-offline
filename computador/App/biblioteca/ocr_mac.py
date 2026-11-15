"""Ler texto de imagens e de PDFs escaneados (OCR) no macOS com o tesseract.

[mac] Substitui o ocr.py original, que usa PowerShell + Windows.Media.Ocr
(Windows 10/11) e trava fora do Windows com "O leitor de imagens usa o
Windows 10 ou 11."

Instale antes:  brew install tesseract tesseract-lang
  (tesseract-lang traz o português; sem ele, só inglês funciona bem)

- ler_imagem(): usa o binário `tesseract` direto.
- ler_pagina_pdf(): usa `pdftoppm` (brew install poppler) para renderizar a
  página e depois o tesseract. Sem poppler, devolve erro explicando.
- Sem tesseract instalado, disponivel() devolve False e o app segue sem OCR
  de fundo — busca, leitura e IA continuam funcionando.
"""
import os
import shutil
import subprocess
import time

from . import config

log = config.log if hasattr(config, "log") else None


def _tesseract():
    return shutil.which("tesseract")


def disponivel():
    if not _tesseract():
        return False, ("OCR não instalado no Mac. Instale com: "
                       "brew install tesseract tesseract-lang")
    return True, ""


def idiomas():
    t = _tesseract()
    if not t:
        return []
    try:
        out = subprocess.check_output([t, "--list-langs"], text=True,
                                      stderr=subprocess.DEVNULL, timeout=30)
        langs = [l.strip() for l in out.splitlines()[1:] if l.strip()]
        return langs
    except Exception:
        return ["por", "eng"]


def _idioma(pedido):
    langs = idiomas()
    if pedido:
        for pref in (pedido[:3].lower(), pedido[:2].lower()):
            for lang in langs:
                if lang.lower().startswith(pref):
                    return lang
    for pref in ("por", "eng"):
        if pref in langs:
            return pref
    return langs[0] if langs else "eng"


def ler_imagem(caminho, idioma=""):
    ok, motivo = disponivel()
    if not ok:
        return {"ok": False, "erro": motivo}
    t0 = time.time()
    try:
        out = subprocess.check_output(
            [_tesseract(), caminho, "stdout", "-l", _idioma(idioma)],
            text=True, stderr=subprocess.PIPE, timeout=180)
        return {"ok": True, "texto": out.strip(),
                "segundos": round(time.time() - t0, 1)}
    except subprocess.CalledProcessError as e:
        return {"ok": False, "erro": "tesseract falhou: %s" % (e.stderr or e)}
    except Exception as e:
        return {"ok": False, "erro": str(e)}


def ler_pagina_pdf(caminho, pagina, idioma="", png=None):
    ok, motivo = disponivel()
    if not ok:
        return {"ok": False, "erro": motivo}
    pdftoppm = shutil.which("pdftoppm")
    if not pdftoppm:
        return {"ok": False, "erro": ("Para OCR de PDF no Mac instale o poppler: "
                                      "brew install poppler (pdftoppm)")}
    t0 = time.time()
    try:
        base = os.path.join(config.TMP, "ocr_pagina")
        os.makedirs(config.TMP, exist_ok=True)
        subprocess.check_call(
            [pdftoppm, "-png", "-r", "200", "-f", str(pagina), "-l", str(pagina),
             caminho, base], timeout=120)
        img = base + "-%d.png" % pagina
        if not os.path.exists(img):
            imgs = [f for f in os.listdir(config.TMP) if f.startswith("ocr_pagina")]
            img = os.path.join(config.TMP, sorted(imgs)[0]) if imgs else None
        if not img:
            return {"ok": False, "erro": "não consegui renderizar a página %d" % pagina}
        if png:
            shutil.copy2(img, png)
        r = ler_imagem(img, idioma)
        r["segundos"] = round(time.time() - t0, 1)
        return r
    except Exception as e:
        return {"ok": False, "erro": str(e)}


def fechar_pdf():
    return None


def fechar():
    return None
