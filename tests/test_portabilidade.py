"""Regressões que motivaram a adaptação; não precisam de rede nem dos programas."""
import ast
import glob
import os
from pathlib import Path
import shutil
import struct
import tempfile
import types
import unittest
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1] / 'computador'


def functions(rel, names, scope):
    tree = ast.parse((ROOT / rel).read_text(encoding='utf-8'))
    nodes = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in names]
    exec(compile(ast.Module(body=nodes, type_ignores=[]), rel, 'exec'), scope)
    return scope


class Portabilidade(unittest.TestCase):
    def test_zim_truncado_com_marca_ok_continua_pendente(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            data = bytearray(112)
            data[:4] = b'ZIM\x04'
            struct.pack_into('<Q', data, 72, 96)
            p = root / 'livro.zim'
            p.write_bytes(data[:-1])
            (root / 'livro.zim.ok').touch()
            scope = functions('_Baixador/instalador/instalar.py',
                              {'zim_concluido', 'pronto', 'pdf_cortado'},
                              {'os': os, 'caminho': lambda rel: str(root / rel),
                               'estado': {'trocados': {}}})
            item = {'rel': p.name, 'tam': len(data)}
            self.assertFalse(scope['pronto'](item))
            p.write_bytes(data)
            self.assertTrue(scope['pronto'](item))

    def test_executaveis_nativos_em_disco_compartilhado(self):
        for module, folder, binary in [('ia', 'LlamaCpp', 'llama-server'),
                                       ('kiwix', 'KiwixServe', 'kiwix-serve')]:
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                mac = root / 'Programas' / folder / binary
                win = root / 'Programas/Windows' / folder / (binary + '.exe')
                for p in (mac, win):
                    p.parent.mkdir(parents=True, exist_ok=True)
                    p.touch()
                for name, expected in [('posix', mac), ('nt', win)]:
                    scope = functions('App/biblioteca/' + module + '.py', {'achar_exe'}, {
                        'os': types.SimpleNamespace(name=name, path=os.path, access=os.access, X_OK=os.X_OK),
                        'shutil': types.SimpleNamespace(which=lambda _: None),
                        'sistema': types.SimpleNamespace(achar=lambda pat: glob.glob(str(root / pat), recursive=True)),
                        '_motor': {'evitar_llamacpp': False}, 'achar_llamafile': lambda: None})
                    self.assertEqual(scope['achar_exe'](), str(expected))

    def test_zip_corrompido_nao_impede_programa_seguinte(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            install = root / '6_Instaladores'
            install.mkdir()
            (root / 'App/runtime').mkdir(parents=True)
            bad = install / 'bad.zip'
            bad.write_bytes(b'corrompido')
            Path(str(bad) + '.ok').touch()
            good = install / 'good.zip'
            with zipfile.ZipFile(good, 'w') as archive:
                archive.writestr('top/program.exe', b'programa de teste')
            Path(str(good) + '.ok').touch()
            scope = functions('_Baixador/instalador/instalar.py', {'instalar_programas'}, {
                'os': types.SimpleNamespace(name='nt', path=os.path, listdir=os.listdir,
                                           makedirs=os.makedirs, remove=os.remove),
                'RAIZ': tmp, 'PROGRAMAS': [('bad', 'Bad'), ('good', 'Good')],
                'zipfile': zipfile, 'zlib': zlib, 'shutil': shutil, 'escreve': lambda _: None})
            scope['instalar_programas']()
            self.assertFalse(bad.exists())
            self.assertFalse(Path(str(bad) + '.ok').exists())
            self.assertEqual((root / 'Programas/Good/program.exe').read_bytes(), b'programa de teste')
            self.assertTrue((root / 'Programas/Good/.instalado').exists())


if __name__ == '__main__':
    unittest.main()
