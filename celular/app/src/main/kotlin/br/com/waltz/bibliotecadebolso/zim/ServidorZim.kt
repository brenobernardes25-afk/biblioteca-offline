package br.com.waltz.bibliotecadebolso.zim

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import br.com.waltz.bibliotecadebolso.util.Diario
import org.kiwix.libzim.Archive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Serve o conteúdo dos .zim para o WebView. Tudo passa por aqui:
 * o que não for do .zim recebe resposta vazia, então nada carrega da internet.
 */
class ServidorZim(private val zim: BibliotecaZim, private val diario: Diario) {

    fun responder(req: WebResourceRequest): WebResourceResponse {
        val u = req.url
        if (u.host != HOST) return bloqueado()
        val segs = u.pathSegments
        val livro = segs.firstOrNull()?.let { zim.livro(it) } ?: return erro(404, "Fonte não disponível.")
        val caminho = u.path.orEmpty().removePrefix("/").substringAfter('/', "")
        if (caminho.isEmpty()) return erro(404, "Página não encontrada.")
        return try {
            val a = livro.archive
            // Alguns .zim (zimit) guardam a query no próprio caminho.
            val comQuery = u.encodedQuery?.let { "$caminho?${Uri.decode(it)}" }
            val entrada = when {
                comQuery != null && a.hasEntryByPath(comQuery) -> a.getEntryByPath(comQuery)
                a.hasEntryByPath(caminho) -> a.getEntryByPath(caminho)
                else -> return erro(404, "Esta página não está neste arquivo.")
            }
            val item = entrada.getItem(true)
            responderItem(item, req.requestHeaders["Range"] ?: req.requestHeaders["range"])
        } catch (e: Exception) {
            diario.escrever("leitor: erro em ${u.path}: ${e.javaClass.simpleName} ${e.message}")
            erro(500, "Não consegui ler esta página do arquivo.")
        }
    }

    private fun responderItem(item: org.kiwix.libzim.Item, range: String?): WebResourceResponse {
        val mime = item.mimetype.substringBefore(';').trim()
        val tam = item.size
        val texto = mime.startsWith("text/") || mime.contains("javascript") || mime.contains("json")
        val cod = if (texto) "UTF-8" else null
        val cabecalhos = hashMapOf(
            "Accept-Ranges" to "bytes",
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-store",
        )

        // Vídeo e arquivo grande: lê direto do disco quando dá, sem carregar tudo na memória.
        val direto = try {
            item.directAccessInformation?.takeIf { !it.filename.isNullOrEmpty() && File(it.filename).canRead() }
        } catch (_: Exception) {
            null
        }
        val faixa = lerRange(range, tam)
        if (direto != null && (faixa != null || tam > LIMITE_MEMORIA)) {
            val ini = faixa?.first ?: 0L
            val fim = faixa?.second ?: (tam - 1)
            val n = fim - ini + 1
            cabecalhos["Content-Length"] = n.toString()
            if (faixa != null) cabecalhos["Content-Range"] = "bytes $ini-$fim/$tam"
            return WebResourceResponse(
                mime, cod, if (faixa != null) 206 else 200, if (faixa != null) "Partial Content" else "OK",
                cabecalhos, TrechoArquivo(direto.filename, direto.offset + ini, n),
            )
        }
        if (tam > LIMITE_MEMORIA) return erro(413, "Este arquivo é grande demais para abrir aqui.")

        val dados = item.data.data
        if (faixa != null) {
            val ini = faixa.first.toInt()
            val fim = faixa.second.toInt()
            cabecalhos["Content-Range"] = "bytes $ini-$fim/$tam"
            cabecalhos["Content-Length"] = (fim - ini + 1).toString()
            return WebResourceResponse(
                mime, cod, 206, "Partial Content", cabecalhos,
                ByteArrayInputStream(dados, ini, fim - ini + 1),
            )
        }
        cabecalhos["Content-Length"] = dados.size.toString()
        return WebResourceResponse(mime, cod, 200, "OK", cabecalhos, ByteArrayInputStream(dados))
    }

    private fun bloqueado() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Sem internet", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)),
    )

    private fun erro(codigo: Int, msg: String): WebResourceResponse {
        val html = """<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <body style="font:20px sans-serif;padding:24px"><p>$msg</p></body>"""
        return WebResourceResponse(
            "text/html", "UTF-8", codigo, if (codigo == 404) "Not Found" else "Error",
            mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(html.toByteArray()),
        )
    }

    /** Lê só o pedaço do .zim onde o item está guardado sem compressão. */
    private class TrechoArquivo(caminho: String, inicio: Long, private var resta: Long) : InputStream() {
        private val f = RandomAccessFile(caminho, "r").apply { seek(inicio) }
        override fun read(): Int {
            if (resta <= 0) return -1
            val b = f.read()
            if (b >= 0) resta--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (resta <= 0) return -1
            val n = f.read(b, off, minOf(len.toLong(), resta).toInt())
            if (n > 0) resta -= n
            return n
        }

        override fun close() = f.close()
    }

    companion object {
        const val HOST = "zim.biblioteca-de-bolso.local"
        private const val LIMITE_MEMORIA = 64_000_000L

        fun url(livroId: String, caminho: String): String =
            "https://$HOST/$livroId/" + Uri.encode(caminho, "/")

        fun paginaInicial(livro: LivroZim): String? = try {
            val a: Archive = livro.archive
            if (a.hasMainEntry()) url(livro.id, a.mainEntry.getItem(true).path) else null
        } catch (_: Exception) {
            null
        }

        /** "bytes=100-" ou "bytes=100-199". Fora disso, null: responde o arquivo inteiro. */
        fun lerRange(h: String?, tam: Long): Pair<Long, Long>? {
            val m = Regex("""bytes=(\d+)-(\d*)""").find(h ?: return null) ?: return null
            val ini = m.groupValues[1].toLong()
            if (ini >= tam) return null
            val fim = m.groupValues[2].toLongOrNull()?.coerceAtMost(tam - 1) ?: (tam - 1)
            return if (fim < ini) null else ini to fim
        }
    }
}
