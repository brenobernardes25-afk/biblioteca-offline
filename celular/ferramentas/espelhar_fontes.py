#!/usr/bin/env python3
"""
Gera o pacotes.json do app a partir do pacotes.json da especificação. Rode depois do
montar_pacotes.py, senão as URLs voltam a ser as originais:

    python ferramentas/espelhar_fontes.py ENTRADA.json SAIDA.json [--copiar PASTA --inteiros PASTA]

O que muda em relação à entrada:
- Fontes bloqueadas (web.archive.org, trueprepper.com, WikEM) passam a vir do Release do GitHub.
- PDFs cortados no D: (ver corrigidos.json) usam a versão inteira, com o tamanho certo.
- Obras de terceiros sem permissão de redistribuir não vão para o Release: o celular baixa do site
  oficial quando ele tem o arquivo; se não tem, o item sai da lista.
- O que não tem versão inteira em lugar nenhum, ou que a fonte entrega quebrado, sai da lista.

--copiar PASTA    copia para PASTA os arquivos a publicar, já com o nome do Release.
--inteiros PASTA  pasta com as versões inteiras baixadas (necessária com --copiar).
Grava ferramentas/espelho_lista.csv com a situação de cada item, e por quê.
"""
import csv
import json
import os
import re
import shutil
import sys
import unicodedata

TAG = "fontes-celular-2026-11"
BASE = f"https://github.com/brenobernardes25-afk/biblioteca-offline/releases/download/{TAG}/"
RAIZ_PC = "D:/"
SUFIXO_VERSAO = "e"  # Mais nova que a anterior, para o app aceitar.
PASTA = os.path.dirname(os.path.abspath(__file__))

# Não publicar num repositório público sem decisão do Breno. Sem endereço que funcione: saem da lista
# (antes ficavam em "Faltou" e apareciam como erro para quem baixava).
NAO_ESPELHAR = {
    "03_FM_3-05.70_Survival_2002.pdf":
        "Aviso no PDF: distribuição só para o governo dos EUA e contratados; aviso de destruição",
    "10_FM_3-25.26_Leitura_de_Mapas_e_Navegacao.pdf":
        "Aviso no PDF: distribuição só para o governo dos EUA e contratados; FOUO",
    "15_ST_31-91B_Manual_Medico_Forcas_Especiais.pdf":
        "Escaneado, sem texto nem OCR: não deu para conferir o aviso de distribuição",
    "19_Canada_Down_But_Not_Out_Sobrevivencia.pdf":
        "Obra do governo canadense (Coroa): sem permissão clara de redistribuição",
    "20_Canada_Military_Fieldcraft.pdf":
        "Aviso no PDF: pode estar isento de divulgação pública (governo canadense)",
    "ESAB_Eletrodos_Revestidos.pdf":
        "Material de empresa, sem permissão de redistribuição",
}

# Obras de terceiros hospedadas pelo Ministério: o celular baixa do site oficial, sem passar pelo Release.
DIRETO_DO_SITE = {
    "conversando_saude_criancas.pdf": "Aviso: reprodução vedada sem autorização do ICH/SBPC",
    "consenso_internacional_pe_diabetico.pdf": "Aviso: reprodução proibida (IWGDF, todos os direitos reservados)",
    "guia_saude_mental_adolescente_11_14_anos.pdf": "Copyright da Fundação Demócrito Rocha, sem permissão",
    "cartilha_prevencao_automutilacao_suicidio_orientacoes_educadores_profissionais_saude.pdf":
        "Copyright da Fundação Demócrito Rocha, sem permissão",
    "cartilha_prevencao_automutilacao_suicidio_15_18_anos.pdf":
        "Copyright da Fundação Demócrito Rocha, sem permissão",
    "saude_fronteira_seca_brasil_argentina.pdf": "Publicado pela Editora Boiteux, sem permissão",
    "v_diretrizes_brasileira_hipertensao_arterial_2006.pdf": "Sociedade Brasileira de Cardiologia, sem permissão",
    "consenso_bras_diabetes.pdf": "Sociedade Brasileira de Diabetes, sem permissão",
    "monografias_centros_integrados_cuidados_idoso.pdf": "Monografia de autora individual (UFBA), sem permissão",
    "hfb_trabalho_hugo_diabete.pdf": "Trabalho de autores individuais, sem permissão",
    "manual_impresa_suicidioms.pdf": "Publicação da OMS, sem permissão no arquivo",
    "Manual_Cuidadores_Profissionais.pdf": "Grupo Hospitalar Conceição, sem permissão no arquivo",
    "escola_protege_enfrentando_violencia_contra_criancas_adolescentes.pdf": "Editora não identificada, sem permissão",
    "condicoes_nutricionais_populacao_brasileira_adultos_idosos.pdf": "Todos os direitos reservados, titular não identificado",
    "livro_aval_malaria.pdf": "Publicação da OPAS com autores individuais, sem permissão",
    "subsidios_conhecimento_hepato_explenica_toxemica_esquistossomose_mansonica.pdf":
        "Escaneado, autoria e direitos não conferidos",
}

# PDFs da Embrapa que o próprio site entrega quebrados ou que saíram do ar (relatos de quem baixou).
REMOVER_IDS = {
    "embrapa:974421:0": "O arquivo está cortado no site da Embrapa",
    "embrapa:131401:0": "Saiu do site da Embrapa (erro 404)",
}

# Cortado no D: e sem versão inteira no site nem no arquivo: um PDF quebrado não ajuda ninguém.
REMOVER = {
    "caderneta_crianca_menina_2019.pdf": "Só existe cortado em 1 MiB; a 2ª edição da caderneta está na lista",
}


def bloqueado(i):
    if i["pacote"] == "slim":
        return "web.archive.org" in i["url"]
    if i["pacote"] != "minimo":
        return False
    return ("web.archive.org" in i["url"]
            or ("trueprepper.com/" in i["url"] and i["tipo"] == "pdf")
            or i["id"] == "zim:wikem_en_all_maxi")


def endereco_oficial(url):
    """Tira o web.archive.org da frente: nem tudo fica em /bvs/publicacoes/."""
    return url.split("id_/", 1)[1].replace("http://", "https://", 1).replace(":80/", "/", 1)


def origem(i):
    return i.get("origem_pc") or "1_Enciclopedia/" + i["destino"].rsplit("/", 1)[-1]


def nome_asset(i, usados):
    base = os.path.basename(i["destino"])
    sem_acento = unicodedata.normalize("NFKD", base).encode("ascii", "ignore").decode()
    nome = re.sub(r"[^A-Za-z0-9._-]+", "_", sem_acento).strip("._")
    raiz, ext = os.path.splitext(nome)
    n, final = 2, nome
    while final.lower() in usados:
        final = f"{raiz}_{n}{ext}"
        n += 1
    usados.add(final.lower())
    return final


def opcao(nome):
    return sys.argv[sys.argv.index(nome) + 1] if nome in sys.argv else None


def main():
    entrada, saida = sys.argv[1], sys.argv[2]
    copiar, inteiros = opcao("--copiar"), opcao("--inteiros")
    d = json.load(open(entrada, encoding="utf-8"))
    corrigidos = json.load(open(os.path.join(PASTA, "corrigidos.json"), encoding="utf-8"))
    usados, linhas, publicar, tirar = set(), [], 0, set()
    delta = {"minimo": 0, "slim": 0}
    # O mínimo vem primeiro para os nomes já publicados no Release não mudarem.
    for i in sorted((x for x in d["itens"] if bloqueado(x)), key=lambda x: x["pacote"] != "minimo"):
        arq = os.path.basename(origem(i))
        if arq in REMOVER:
            tirar.add(i["id"])
            delta[i["pacote"]] -= i["tam"]
            linhas.append([i["id"], i["titulo"], i["tam"], "removido da lista", REMOVER[arq], i["url"]])
        elif arq in NAO_ESPELHAR:
            tirar.add(i["id"])
            delta[i["pacote"]] -= i["tam"]
            linhas.append([i["id"], i["titulo"], i["tam"], "removido da lista", NAO_ESPELHAR[arq], i["url"]])
        elif arq in DIRETO_DO_SITE:
            linhas.append([i["id"], i["titulo"], i["tam"], "direto do site oficial", DIRETO_DO_SITE[arq], i["url"]])
            i["url_original"] = i["url"]
            i["url"] = endereco_oficial(i["url"])
        else:
            fix = corrigidos.get(i["id"])
            if fix:
                delta[i["pacote"]] += fix["tam"] - i["tam"]
                i["tam"] = fix["tam"]
                p = os.path.join(inteiros, arq) if inteiros else None
            else:
                p = RAIZ_PC + origem(i)
            if p and os.path.getsize(p) != i["tam"]:
                raise SystemExit(f"Tamanho difere do manifesto: {p}")
            nome = nome_asset(i, usados)
            situacao = "no Release, versão inteira" if fix else "no Release"
            linhas.append([i["id"], i["titulo"], i["tam"], situacao, nome, fix["fonte"] if fix else i["url"]])
            i["url_original"] = i["url"]
            i["url"] = BASE + nome
            publicar += 1
            if copiar:
                os.makedirs(copiar, exist_ok=True)
                destino = os.path.join(copiar, nome)
                if not os.path.exists(destino) or os.path.getsize(destino) != i["tam"]:
                    shutil.copyfile(p, destino)
    for i in d["itens"]:
        if i["id"] in REMOVER_IDS and i["id"] not in tirar:
            tirar.add(i["id"])
            delta[i["pacote"]] -= i["tam"]
            linhas.append([i["id"], i["titulo"], i["tam"], "removido da lista", REMOVER_IDS[i["id"]], i["url"]])
    d["itens"] = [i for i in d["itens"] if i["id"] not in tirar]
    # O Slim inclui o mínimo, então soma as duas diferenças.
    d["pacotes"]["minimo"]["tam"] += delta["minimo"]
    d["pacotes"]["slim"]["tam"] += delta["minimo"] + delta["slim"]
    d["versao"] += SUFIXO_VERSAO
    # O índice dos PDFs entra como item. Vem no pacote mínimo: o Slim inclui o mínimo.
    arq_indice = os.path.join(PASTA, "indice_info.json")
    if os.path.exists(arq_indice):
        ind = json.load(open(arq_indice, encoding="utf-8"))
        if ind["versao"].rstrip("abcdefghijklmnopqrstuvwxyz") != d["versao"].rstrip("abcdefghijklmnopqrstuvwxyz"):
            raise SystemExit(f"O índice é da versão {ind['versao']}, e a lista é {d['versao']}. Gere o índice de novo.")
        d["itens"].append({
            "id": "indice:slim", "tipo": "indice", "pacote": "minimo", "tema": "Índice", "titulo": "Índice de busca dos manuais",
            "url": BASE + ind["arquivo"], "tam": ind["tam"], "destino": "indice/" + ind["arquivo"], "atualizavel": False,
        })
        d["pacotes"]["minimo"]["tam"] += ind["tam"]
        d["pacotes"]["slim"]["tam"] += ind["tam"]
    with open(saida, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, separators=(",", ":"))
    with open(os.path.join(PASTA, "espelho_lista.csv"), "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(["id", "titulo", "tamanho", "situacao", "nome no Release ou motivo", "fonte"])
        w.writerows(linhas)
    print(f"{publicar} no Release, {len(linhas) - publicar} fora; versão {d['versao']}")
    print(f"mínimo {d['pacotes']['minimo']['tam']}, slim {d['pacotes']['slim']['tam']}")


if __name__ == "__main__":
    main()
