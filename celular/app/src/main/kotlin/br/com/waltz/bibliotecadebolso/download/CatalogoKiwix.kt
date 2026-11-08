package br.com.waltz.bibliotecadebolso.download

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class VersaoZim(val url: String, val tam: Long, val nomeArquivo: String, val atualizadoEm: String)

/**
 * Procura a versão mais nova de um .zim no catálogo OPDS do Kiwix.
 *
 * O catálogo guarda nome e "flavour" separados: "wikipedia_pt_all_maxi" tem que ser pedido como
 * name=wikipedia_pt_all&flavour=maxi. Com o nome inteiro ele responde vazio, então tentamos os dois.
 */
class CatalogoKiwix(private val http: OkHttpClient, private val base: String = BASE) {

    fun maisNova(kiwixNome: String): VersaoZim? {
        val (nome, sabor) = separarSabor(kiwixNome)
        if (sabor != null) buscar(nome, sabor)?.let { return it }
        return buscar(kiwixNome, null)
    }

    private fun buscar(nome: String, sabor: String?): VersaoZim? {
        val url = "$base/catalog/v2/entries".toHttpUrl().newBuilder()
            .addQueryParameter("name", nome)
            .apply { if (sabor != null) addQueryParameter("flavour", sabor) }
            .build()
        val req = Request.Builder().url(url).header("User-Agent", Transferidor.USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ErroHttp(r.code)
            return lerFeed(r.body?.string().orEmpty(), nome, sabor)
        }
    }

    companion object {
        const val BASE = "https://library.kiwix.org"
        private val SABORES = listOf("maxi", "nopic", "mini")

        fun separarSabor(kiwixNome: String): Pair<String, String?> {
            val sabor = SABORES.firstOrNull { kiwixNome.endsWith("_$it") }
            return if (sabor == null) kiwixNome to null else kiwixNome.removeSuffix("_$sabor") to sabor
        }

        /** Lê o Atom e devolve a entrada mais nova que bate com nome e sabor. */
        fun lerFeed(xml: String, nome: String, sabor: String?): VersaoZim? {
            val p = XmlPullParserFactory.newInstance().newPullParser()
            p.setInput(StringReader(xml))
            var melhor: VersaoZim? = null
            var dentro = false
            var fundo = 0
            var eNome = ""; var eSabor = ""; var eData = ""; var eHref: String? = null; var eTam = 0L
            var evento = p.eventType
            while (evento != XmlPullParser.END_DOCUMENT) {
                when (evento) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "entry" -> {
                            dentro = true
                            fundo = p.depth
                            eNome = ""; eSabor = ""; eData = ""; eHref = null; eTam = 0
                        }
                        // Só filhos diretos da entrada: <author> e <publisher> também têm <name>.
                        "name" -> if (dentro && p.depth == fundo + 1) eNome = p.nextText().trim()
                        "flavour" -> if (dentro && p.depth == fundo + 1) eSabor = p.nextText().trim()
                        "updated" -> if (dentro && p.depth == fundo + 1) eData = p.nextText().trim()
                        "link" -> if (dentro && p.getAttributeValue(null, "type") == "application/x-zim") {
                            eHref = p.getAttributeValue(null, "href")
                            eTam = p.getAttributeValue(null, "length")?.toLongOrNull() ?: 0
                        }
                    }
                    XmlPullParser.END_TAG -> if (p.name == "entry") {
                        dentro = false
                        val href = eHref
                        val bate = eNome == nome && (sabor == null || eSabor == sabor)
                        if (bate && href != null && (melhor == null || eData > melhor.atualizadoEm)) {
                            val url = href.removeSuffix(".meta4")
                            val arquivo = url.substringAfterLast('/')
                            if (arquivo.endsWith(".zim") && !arquivo.contains("..")) {
                                melhor = VersaoZim(url, eTam, arquivo, eData)
                            }
                        }
                    }
                }
                evento = p.next()
            }
            return melhor
        }
    }
}
