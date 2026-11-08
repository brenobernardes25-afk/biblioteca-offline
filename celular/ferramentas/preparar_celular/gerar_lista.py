#!/usr/bin/env python3
"""Gera a lista_celular.tsv a partir do pacotes.json do app. Rode sempre depois do espelhar_fontes.py:

    python ferramentas/preparar_celular/gerar_lista.py app/src/main/assets/pacotes.json
"""
import json
import os
import sys

d = json.load(open(sys.argv[1], encoding="utf-8"))
saida = os.path.join(os.path.dirname(os.path.abspath(__file__)), "lista_celular.tsv")
n = 0
with open(saida, "w", encoding="utf-8", newline="\n") as f:
    f.write("pacote\ttipo\ttam\tdestino\turl\torigem_pc\tkiwix_nome\ttitulo\n")
    for i in d["itens"]:
        if i["pacote"] not in ("minimo", "slim"):
            continue
        origem = i.get("origem_pc") or ("1_Enciclopedia/" + i["destino"].rsplit("/", 1)[-1] if i["tipo"] == "zim" else "")
        titulo = i["titulo"].replace("\t", " ").replace("\n", " ")
        f.write("\t".join([i["pacote"], i["tipo"], str(i["tam"]), i["destino"], i["url"], origem, i.get("kiwix_nome", ""), titulo]) + "\n")
        n += 1
print("itens", n, "versao", d["versao"])
