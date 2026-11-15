"""OCR nativo de cada sistema; documentos e modelos são compartilhados."""
import os
if os.name == 'nt':
    from .ocr_windows import disponivel, idiomas, ler_imagem, ler_pagina_pdf, fechar_pdf, fechar
else:
    from .ocr_mac import disponivel, idiomas, ler_imagem, ler_pagina_pdf, fechar_pdf, fechar
