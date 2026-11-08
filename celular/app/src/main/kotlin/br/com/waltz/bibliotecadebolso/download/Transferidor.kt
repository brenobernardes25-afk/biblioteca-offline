package br.com.waltz.bibliotecadebolso.download

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

/** Onde o download vai sendo gravado: um arquivo comum ou um documento no SSD. */
interface SaidaParcial {
    val tamanho: Long
    fun apagar()
    fun abrir(continuar: Boolean): java.io.OutputStream
}

private class SaidaArquivo(private val f: File) : SaidaParcial {
    override val tamanho get() = f.length()
    override fun apagar() {
        f.delete()
    }
    override fun abrir(continuar: Boolean) = FileOutputStream(f, continuar)
}

class ErroHttp(val codigo: Int, val retryAfterS: Long? = null) : IOException("O servidor respondeu com erro $codigo")

/** Baixa um arquivo com retomada (seção 5). Não sabe nada de fila nem de tentativas. */
class Transferidor(private val http: OkHttpClient) {

    /**
     * Continua de onde o .parcial parou e devolve o tamanho final.
     * Se o servidor responder 200 em vez de 206, recomeça o arquivo.
     * Lança IOException em qualquer falha: o que já chegou fica no .parcial.
     */
    fun baixar(
        url: String,
        parcial: File,
        aoChamar: (Call) -> Unit = {},
        aoProgredir: (Long) -> Unit = {},
    ): Long {
        parcial.parentFile?.mkdirs()
        return baixar(url, SaidaArquivo(parcial), aoChamar, aoProgredir)
    }

    fun baixar(
        url: String,
        parcial: SaidaParcial,
        aoChamar: (Call) -> Unit = {},
        aoProgredir: (Long) -> Unit = {},
    ): Long {
        val ja = parcial.tamanho
        val req = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
            .apply { if (ja > 0) header("Range", "bytes=$ja-") }
            .build()
        val chamada = http.newCall(req)
        aoChamar(chamada)
        chamada.execute().use { r ->
            val inicio: Long
            val total: Long
            when (r.code) {
                200 -> {
                    inicio = 0
                    total = r.body?.contentLength() ?: -1
                }
                206 -> {
                    val faixa = lerContentRange(r.header("Content-Range"))
                    if (faixa == null || faixa.first != ja) {
                        parcial.apagar()
                        throw IOException("Resposta de retomada inesperada; o arquivo vai recomeçar")
                    }
                    inicio = ja
                    total = faixa.second
                }
                416 -> {
                    // O .parcial pode já estar completo (o app parou antes de renomear).
                    val t = r.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                    if (t != null && t == ja) return ja
                    parcial.apagar()
                    throw IOException("Retomada recusada pelo servidor; o arquivo vai recomeçar")
                }
                else -> throw ErroHttp(r.code, r.header("Retry-After")?.trim()?.toLongOrNull())
            }
            val corpo = r.body ?: throw IOException("Resposta vazia do servidor")
            var escrito = inicio
            parcial.abrir(inicio > 0).use { saida ->
                corpo.byteStream().use { entrada ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = entrada.read(buf)
                        if (n < 0) break
                        saida.write(buf, 0, n)
                        escrito += n
                        aoProgredir(escrito)
                    }
                }
            }
            if (total >= 0 && escrito != total) {
                throw IOException("Conexão caiu: chegaram $escrito de $total bytes")
            }
            return escrito
        }
    }

    companion object {
        /** Alguns servidores (Embrapa, web.archive.org) não gostam de cliente desconhecido. */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/126.0.0.0 Mobile Safari/537.36"

        fun cliente(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        /** "bytes 100-199/200" vira (100, 200). O total pode ser "*": aí volta -1. */
        fun lerContentRange(h: String?): Pair<Long, Long>? {
            val m = Regex("""bytes\s+(\d+)-\d+/(\d+|\*)""").find(h ?: return null) ?: return null
            return m.groupValues[1].toLong() to (m.groupValues[2].toLongOrNull() ?: -1L)
        }

        /** PDFs têm que bater exato. .zim tem tolerância: o tamanho do catálogo é arredondado. */
        fun tamanhoConfere(recebido: Long, esperado: Long, tolerante: Boolean): Boolean {
            if (esperado <= 0) return recebido > 0
            if (!tolerante) return recebido == esperado
            return abs(recebido - esperado) <= max(esperado / 50, 1_000_000L)
        }

        /**
         * Confere começo e fim do PDF. O tamanho sozinho não basta: algumas cópias antigas foram
         * cortadas em 1 MiB, e o tamanho esperado na lista era o do arquivo cortado.
         */
        fun pdfInteiro(arquivo: File): Boolean = RandomAccessFile(arquivo, "r").use { pdfInteiro(it.channel) }

        /** Para arquivo no SSD, aberto por descritor. */
        fun pdfInteiro(canal: java.nio.channels.FileChannel): Boolean {
            val total = canal.size()
            if (total < 8) return false
            val n = minOf(total, FOLGA_PDF).toInt()
            val ler = { pos: Long ->
                val b = java.nio.ByteBuffer.allocate(n)
                while (b.hasRemaining() && canal.read(b, pos + b.position()) >= 0) Unit
                String(b.array(), 0, b.position(), Charsets.ISO_8859_1)
            }
            return ler(0).contains("%PDF") && ler(total - n).contains("%%EOF")
        }

        private const val FOLGA_PDF = 1024L
    }
}
