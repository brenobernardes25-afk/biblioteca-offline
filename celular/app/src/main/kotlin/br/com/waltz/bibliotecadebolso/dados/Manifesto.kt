package br.com.waltz.bibliotecadebolso.dados

import org.json.JSONObject

/** Um item do pacotes.json (seção 4 da especificação). */
data class ItemManifesto(
    val id: String,
    val tipo: String,
    val pacote: String,
    val tema: String,
    val titulo: String,
    val url: String,
    val tam: Long,
    val destino: String,
    val atualizavel: Boolean,
    val kiwixNome: String?,
)

data class Manifesto(
    val versao: String,
    val tamMinimo: Long,
    val tamSlim: Long,
    val itens: List<ItemManifesto>,
) {
    companion object {
        /** Lê e valida. Lança exceção se o arquivo estiver ruim: quem chama mantém a cópia anterior. */
        fun ler(json: String): Manifesto {
            val raiz = JSONObject(json)
            val pacotes = raiz.getJSONObject("pacotes")
            val lista = raiz.getJSONArray("itens")
            val itens = ArrayList<ItemManifesto>(lista.length())
            for (i in 0 until lista.length()) {
                val o = lista.getJSONObject(i)
                val destino = o.getString("destino")
                require(caminhoSeguro(destino)) { "destino inválido: $destino" }
                itens += ItemManifesto(
                    id = o.getString("id"),
                    tipo = o.getString("tipo"),
                    pacote = o.getString("pacote"),
                    tema = o.getString("tema"),
                    titulo = o.getString("titulo"),
                    url = o.getString("url"),
                    tam = o.getLong("tam"),
                    destino = destino,
                    atualizavel = o.optBoolean("atualizavel", false),
                    kiwixNome = if (o.has("kiwix_nome")) o.getString("kiwix_nome") else null,
                )
            }
            require(itens.isNotEmpty()) { "manifesto sem itens" }
            return Manifesto(
                versao = raiz.getString("versao"),
                tamMinimo = pacotes.getJSONObject("minimo").getLong("tam"),
                tamSlim = pacotes.getJSONObject("slim").getLong("tam"),
                itens = itens,
            )
        }

        /** O manifesto vem da internet: nenhum destino pode escapar da pasta de dados do app. */
        fun caminhoSeguro(caminho: String): Boolean =
            caminho.isNotBlank() && !caminho.startsWith("/") && !caminho.contains('\\') &&
                caminho.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}

/**
 * Ordem de download (seção 5): Saúde primeiro, depois Água e Sobrevivência, depois o resto,
 * do menor para o maior dentro de cada grupo. O app fica útil cedo.
 */
object OrdemDownload {
    fun grupo(tema: String): Int = when {
        tema.startsWith("Saúde") -> 0
        tema.contains("Água") || tema.startsWith("Sobrevivência") -> 1
        else -> 2
    }

    fun ordenar(itens: List<ItemManifesto>): List<ItemManifesto> =
        itens.sortedWith(compareBy({ if (it.tipo == "indice") -1 else grupo(it.tema) }, { it.tam }, { it.id }))
}
