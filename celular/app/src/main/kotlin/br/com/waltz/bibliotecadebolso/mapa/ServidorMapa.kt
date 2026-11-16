package br.com.waltz.bibliotecadebolso.mapa

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/*
 * Mapa do Brasil dentro do app, lido direto do pen drive, sem copiar nada para o celular.
 *
 * É o mesmo mapa da biblioteca do computador: a página (MapLibre + estilo Protomaps) e as fontes ficam em
 * App/static/mapa do pen drive, e o Brasil inteiro em 2_Mapas/brasil.pmtiles. Um servidor pequeno, só para o
 * próprio celular (127.0.0.1), entrega tudo ao WebView com os mesmos endereços que a biblioteca do PC usa
 * (/mapa, /api/mapa, /api/lugares, /mapa/tiles, /mapa/fontes, /static). Um servidor de verdade, e não o
 * interceptador do WebView, porque o MapLibre pede os pedaços do mapa por "workers", que o interceptador
 * nem sempre vê. Nada aqui depende do Android: dá para testar no computador (MapaTest).
 */

/** De onde vêm os arquivos: no app, o pen drive (pela SAF); nos testes, uma pasta do computador. */
interface FonteMapa {
    /** O arquivo inteiro (só para os pequenos: página, fontes, lista de lugares). Null se não existe. */
    fun ler(rel: String): ByteArray?

    /** Para ler pedaços de um arquivo grande (o .pmtiles). Null se não existe. */
    fun abrirCanal(rel: String): CanalArquivo?
}

class CanalArquivo(val canal: FileChannel, private val aoFechar: () -> Unit) : Closeable {
    override fun close() = try {
        aoFechar()
    } catch (_: Exception) {
    }
}

/** PMTiles v3 (o formato do brasil.pmtiles). Igual ao mapa.py da biblioteca do PC. */
class Pmtiles(private val arq: CanalArquivo) : Closeable {
    private class Dir(val ids: LongArray, val runs: LongArray, val tams: LongArray, val offs: LongArray)

    val minz: Int
    val maxz: Int
    val limites: DoubleArray
    val centro: DoubleArray
    val gzip: Boolean
    private val folhasOff: Long
    private val dadosOff: Long
    private val raiz: Dir
    private val folhas = object : LinkedHashMap<Long, Dir>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Dir>?) = size > 256
    }

    init {
        val cab = ler(0, 127)
        if (String(cab, 0, 7, Charsets.US_ASCII) != "PMTiles" || cab[7].toInt() != 3) throw IOException("não é PMTiles v3")
        val b = ByteBuffer.wrap(cab).order(ByteOrder.LITTLE_ENDIAN)
        val raizOff = b.getLong(8)
        val raizTam = b.getLong(16)
        folhasOff = b.getLong(40)
        dadosOff = b.getLong(56)
        gzip = (cab[98].toInt() and 0xff) == 2
        minz = cab[100].toInt() and 0xff
        maxz = cab[101].toInt() and 0xff
        limites = doubleArrayOf(b.getInt(102) / 1e7, b.getInt(106) / 1e7, b.getInt(110) / 1e7, b.getInt(114) / 1e7)
        centro = doubleArrayOf(b.getInt(119) / 1e7, b.getInt(123) / 1e7, (cab[118].toInt() and 0xff).toDouble())
        raiz = diretorio(ler(raizOff, raizTam.toInt()))
    }

    private fun ler(off: Long, tam: Int): ByteArray {
        val buf = ByteBuffer.allocate(tam)
        var pos = off
        while (buf.hasRemaining()) {
            val n = arq.canal.read(buf, pos) // leitura por posição: várias ao mesmo tempo, sem trava
            if (n < 0) throw EOFException("o arquivo do mapa acabou antes da hora")
            pos += n
        }
        return buf.array()
    }

    private fun diretorio(dados: ByteArray): Dir {
        val b = GZIPInputStream(ByteArrayInputStream(dados)).use { it.readBytes() }
        var p = 0
        fun varint(): Long {
            var r = 0L
            var d = 0
            while (true) {
                val c = b[p++].toInt() and 0xff
                r = r or ((c and 0x7f).toLong() shl d)
                if (c < 0x80) return r
                d += 7
            }
        }
        val n = varint().toInt()
        val ids = LongArray(n)
        val runs = LongArray(n)
        val tams = LongArray(n)
        val offs = LongArray(n)
        var ult = 0L
        for (i in 0 until n) {
            ult += varint()
            ids[i] = ult
        }
        for (i in 0 until n) runs[i] = varint()
        for (i in 0 until n) tams[i] = varint()
        for (i in 0 until n) {
            val v = varint()
            offs[i] = if (v == 0L && i > 0) offs[i - 1] + tams[i - 1] else v - 1
        }
        return Dir(ids, runs, tams, offs)
    }

    private fun folha(off: Long, tam: Long): Dir {
        synchronized(folhas) { folhas[off]?.let { return it } }
        val d = diretorio(ler(folhasOff + off, tam.toInt()))
        synchronized(folhas) { folhas[off] = d }
        return d
    }

    /** O pedaço do mapa (ainda comprimido, como está no arquivo), ou null se ali não há nada (mar, fora do Brasil). */
    fun tile(z: Int, x: Int, y: Int): ByteArray? {
        if (z < minz || z > maxz || x < 0 || y < 0 || x >= (1 shl z) || y >= (1 shl z)) return null
        val tid = idDoTile(z, x, y)
        var d = raiz
        repeat(4) {
            // o último com id <= tid (bisect_right - 1)
            var lo = 0
            var hi = d.ids.size
            while (lo < hi) {
                val m = (lo + hi) ushr 1
                if (d.ids[m] <= tid) lo = m + 1 else hi = m
            }
            val i = lo - 1
            if (i < 0) return null
            if (d.runs[i] == 0L) {
                d = folha(d.offs[i], d.tams[i])
                return@repeat
            }
            return if (tid < d.ids[i] + d.runs[i]) ler(dadosOff + d.offs[i], d.tams[i].toInt()) else null
        }
        return null
    }

    override fun close() = arq.close()

    companion object {
        /** Número do tile na curva de Hilbert, como no formato PMTiles. */
        fun idDoTile(z: Int, x0: Int, y0: Int): Long {
            val acc = ((1L shl (2 * z)) - 1) / 3
            val n = 1L shl z
            var x = x0.toLong()
            var y = y0.toLong()
            var d = 0L
            var s = n shr 1
            while (s > 0) {
                val rx = if (x and s != 0L) 1L else 0L
                val ry = if (y and s != 0L) 1L else 0L
                d += s * s * ((3 * rx) xor ry)
                if (ry == 0L) {
                    if (rx == 1L) {
                        x = n - 1 - x
                        y = n - 1 - y
                    }
                    val t = x
                    x = y
                    y = t
                }
                s = s shr 1
            }
            return acc + d
        }
    }
}

/** A lista de cidades, bairros, serras, rios… (2_Mapas/lugares_br.tsv). Igual ao mapa.py do PC. */
class Lugares(texto: String) {
    class Lugar(val norm: String, val nome: String, val lat: Double, val lon: Double, val tipo: String, val uf: String, val pop: Long)

    val todos: List<Lugar> = texto.lineSequence().drop(1).mapNotNull { l ->
        val c = l.split('\t')
        if (c.size < 6) return@mapNotNull null
        val lat = c[1].toDoubleOrNull() ?: return@mapNotNull null
        val lon = c[2].toDoubleOrNull() ?: return@mapNotNull null
        Lugar(semAcento(c[0]), c[0], lat, lon, c[3], c[4], c[5].toLongOrNull() ?: 0L)
    }.toList()

    fun buscar(q0: String, limite: Int = 25): List<Pair<Lugar, String>> {
        var q = semAcento(q0.trim().split(Regex("\\s+")).joinToString(" ")).take(80)
        if (q.length < 2) return emptyList()
        var estado = ""
        if (',' in q) { // "Campinas, São Paulo"
            estado = q.substringAfter(',').trim()
            q = q.substringBefore(',').trim()
        }
        val achados = ArrayList<Triple<Triple<Int, Int, Long>, Lugar, String>>()
        for (l in todos) {
            if (q !in l.norm) continue
            if (estado.isNotEmpty() && estado !in semAcento(l.uf)) continue
            val cidade = l.tipo.startsWith("PPLA") || l.tipo == "PPLC" || l.pop >= 20000
            val nota = Triple(
                if (l.norm == q) 0 else if (l.norm.startsWith(q)) 1 else 2,
                if (cidade) 0 else if (l.tipo.startsWith("PPL")) 1 else 2,
                -l.pop,
            )
            val rotulo = if (l.tipo == "PPL" && l.pop >= 20000) "cidade" else TIPOS[l.tipo] ?: l.tipo
            achados += Triple(nota, l, rotulo)
        }
        achados.sortWith(compareBy({ it.first.first }, { it.first.second }, { it.first.third }))
        return achados.take(limite).map { it.second to it.third }
    }

    companion object {
        private val ACENTOS = Regex("\\p{Mn}+")
        fun semAcento(t: String): String = ACENTOS.replace(Normalizer.normalize(t.lowercase(), Normalizer.Form.NFD), "")

        val TIPOS = mapOf(
            "PPLC" to "capital do país", "PPLA" to "capital de estado", "PPLA2" to "cidade (sede de município)",
            "PPL" to "localidade", "PPLX" to "bairro", "PPLL" to "povoado", "PPLF" to "fazenda/vila",
            "PPLQ" to "lugar abandonado", "PPLR" to "assentamento", "MT" to "morro/monte", "MTS" to "serra",
            "PK" to "pico", "HLL" to "morro", "LK" to "lago/lagoa", "RSV" to "represa", "BAY" to "baía",
            "ISL" to "ilha", "PRK" to "parque", "AIRP" to "aeroporto", "HSP" to "hospital", "FLLS" to "cachoeira",
            "DAM" to "barragem", "CAPE" to "cabo/ponta", "RF" to "recife",
        )
    }
}

class ServidorMapa(
    private val fonte: FonteMapa,
    private val diario: (String) -> Unit,
    /** Por que não dá para abrir o mapa agora (pen drive desligado…), ou null se dá. */
    private val motivoSemMapa: () -> String? = { null },
) {
    private class Resposta(
        val codigo: Int,
        val tipo: String,
        val corpo: ByteArray,
        val cache: String = "no-store",
        val extra: Map<String, String> = emptyMap(),
    )

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var pm: Pmtiles? = null
    @Volatile private var lugares: Lugares? = null
    private val travaAbrir = Any()
    private val pequenos = ConcurrentHashMap<String, ByteArray>()
    private val pool = Executors.newFixedThreadPool(6) { r -> Thread(r, "mapa").apply { isDaemon = true } }

    /** Liga o servidor (se ainda não estiver) e devolve a porta. Só escuta o próprio aparelho. */
    @Synchronized
    fun iniciar(porta: Int = 0): Int {
        socket?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket(porta, 64, InetAddress.getByName("127.0.0.1"))
        socket = s
        thread(isDaemon = true, name = "mapa-portaria") {
            while (!s.isClosed) {
                val c = try {
                    s.accept()
                } catch (_: IOException) {
                    break
                }
                pool.execute { atender(c) }
            }
        }
        return s.localPort
    }

    /** O pen drive saiu ou trocou: solta o arquivo do mapa (o Android encerra quem segura arquivo de disco que saiu). */
    fun esquecer() {
        synchronized(travaAbrir) {
            pm?.close()
            pm = null
            lugares = null
            pequenos.clear()
        }
    }

    fun parar() {
        esquecer()
        socket?.close()
        socket = null
    }

    /** O mapa abre? (há pen drive com o brasil.pmtiles) */
    fun disponivel(): Boolean = motivoSemMapa() == null && abrirMapa() != null

    private fun abrirMapa(): Pmtiles? {
        pm?.let { return it }
        synchronized(travaAbrir) {
            pm?.let { return it }
            val c = fonte.abrirCanal(ARQ_MAPA) ?: return null
            return try {
                Pmtiles(c).also { pm = it }
            } catch (e: Exception) {
                diario("mapa: não abri o $ARQ_MAPA: ${e.message}")
                c.close()
                null
            }
        }
    }

    private fun abrirLugares(): Lugares? {
        lugares?.let { return it }
        synchronized(travaAbrir) {
            lugares?.let { return it }
            val b = fonte.ler(ARQ_LUGARES) ?: return null
            return Lugares(String(b, Charsets.UTF_8)).also { lugares = it }
        }
    }

    // ------------------------------------------------------------------ HTTP (o mínimo: GET, uma resposta por conexão)

    private fun atender(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 20_000
                val ent = BufferedInputStream(s.getInputStream())
                val linha = lerLinha(ent) ?: return
                while (true) {
                    val h = lerLinha(ent) ?: break
                    if (h.isEmpty()) break
                }
                val partes = linha.split(' ')
                if (partes.size < 2) return
                val alvo = partes[1]
                val caminho = URLDecoder.decode(alvo.substringBefore('?').replace("+", "%2B"), "UTF-8")
                val query = alvo.substringAfter('?', "")
                val r = try {
                    rota(caminho, query)
                } catch (e: Exception) {
                    diario("mapa: erro em $caminho: ${e.javaClass.simpleName} ${e.message}")
                    if (e is IOException) esquecer() // pen drive saiu? na próxima, tenta abrir de novo
                    Resposta(500, "text/plain; charset=utf-8", "Não consegui ler o mapa do pen drive.".toByteArray())
                }
                escrever(s.getOutputStream(), r, partes[0] == "HEAD")
            }
        } catch (_: IOException) {
            // o WebView desistiu do pedido (mapa arrastado depressa): normal
        }
    }

    private fun lerLinha(ent: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = ent.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("ISO-8859-1")
            if (c == '\n'.code) return b.toString("ISO-8859-1").trimEnd('\r')
            if (b.size() > 8192) return null
            b.write(c)
        }
    }

    private fun escrever(out: OutputStream, r: Resposta, soCabecalho: Boolean) {
        val texto = when (r.codigo) {
            200 -> "OK"; 204 -> "No Content"; 404 -> "Not Found"; else -> "Error"
        }
        val cab = StringBuilder("HTTP/1.1 ${r.codigo} $texto\r\n")
            .append("Content-Type: ${r.tipo}\r\n")
            .append("Content-Length: ${r.corpo.size}\r\n")
            .append("Cache-Control: ${r.cache}\r\n")
            .append("Connection: close\r\n")
        r.extra.forEach { (k, v) -> cab.append("$k: $v\r\n") }
        cab.append("\r\n")
        out.write(cab.toString().toByteArray(Charsets.ISO_8859_1))
        if (!soCabecalho && r.corpo.isNotEmpty()) out.write(r.corpo)
        out.flush()
    }

    private fun parametro(query: String, nome: String): String =
        query.split('&').firstOrNull { it.substringBefore('=') == nome }?.substringAfter('=', "")
            ?.let { URLDecoder.decode(it, "UTF-8") } ?: ""

    private fun json(o: Any) = Resposta(200, "application/json; charset=utf-8", o.toString().toByteArray(Charsets.UTF_8))

    private fun rota(caminho: String, query: String): Resposta {
        if (caminho == "/mapa" || caminho == "/mapa/") return pagina()
        if (caminho == "/api/mapa") return json(info())
        if (caminho == "/api/lugares") return json(JSONObject().put("lugares", buscarLugares(parametro(query, "q"))))
        TILE.matchEntire(caminho)?.let { m ->
            val (z, x, y) = m.destructured
            val a = abrirMapa() ?: return Resposta(404, "text/plain", ByteArray(0))
            val dados = a.tile(z.toInt(), x.toInt(), y.toInt())
                ?: return Resposta(204, "application/x-protobuf", ByteArray(0), "max-age=86400")
            return Resposta(
                200, "application/x-protobuf", dados, "max-age=86400",
                if (a.gzip) mapOf("Content-Encoding" to "gzip") else emptyMap(),
            )
        }
        FONTE.matchEntire(caminho)?.let { m ->
            val (pilha, faixa) = m.destructured
            // o estilo pode pedir "Fonte A,Fonte B": usa a primeira que existir; faixa sem arquivo = vazia
            val dados = pilha.split(',').asSequence()
                .map { it.trim().substringAfterLast('/') }
                .filter { it.isNotEmpty() && ".." !in it }
                .mapNotNull { pequeno("App/static/mapa/fonts/$it/$faixa.pbf") }
                .firstOrNull() ?: ByteArray(0)
            return Resposta(200, "application/x-protobuf", dados, "max-age=86400")
        }
        if (caminho.startsWith("/static/")) {
            val rel = caminho.removePrefix("/static/")
            if (".." in rel || rel.startsWith("/")) return Resposta(404, "text/plain", ByteArray(0))
            val dados = pequeno("App/static/$rel") ?: return Resposta(404, "text/plain", ByteArray(0))
            return Resposta(200, tipoDe(rel), dados, if (rel.startsWith("mapa/")) "max-age=604800" else "no-cache")
        }
        return Resposta(404, "text/plain; charset=utf-8", "Página não encontrada.".toByteArray())
    }

    private fun pequeno(rel: String): ByteArray? =
        pequenos[rel] ?: fonte.ler(rel)?.also { if (it.size < 4_000_000) pequenos[rel] = it }

    /** A página do mapa do PC, sem os botões que levam para a biblioteca do PC (no celular, o app tem o Voltar). */
    private fun pagina(): Resposta {
        val motivo = motivoSemMapa()
        val html = pequeno("App/static/mapa/mapa.html")?.toString(Charsets.UTF_8)
        if (motivo != null || html == null) {
            val msg = motivo ?: "Este pen drive não tem a página do mapa (App/static/mapa)."
            return Resposta(
                200, "text/html; charset=utf-8",
                ("<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">" +
                    "<body style=\"font:20px sans-serif;padding:24px\"><p>$msg</p></body>").toByteArray(),
            )
        }
        val limpo = html
            .replace(Regex("""<a [^>]*href="/"[^>]*>.*?</a>""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""<a [^>]*href="/#/ajuda[^"]*"[^>]*>.*?</a>""", RegexOption.DOT_MATCHES_ALL), "")
        return Resposta(200, "text/html; charset=utf-8", limpo.toByteArray(Charsets.UTF_8))
    }

    private fun info(): JSONObject {
        motivoSemMapa()?.let { return JSONObject().put("ok", false).put("motivo", it) }
        val a = abrirMapa() ?: return JSONObject().put("ok", false)
            .put("motivo", "Este pen drive não tem o mapa do Brasil (2_Mapas/brasil.pmtiles).")
        return JSONObject()
            .put("ok", true)
            .put("limites", JSONArray(a.limites.toList()))
            .put("centro", JSONArray(a.centro.toList()))
            .put("maxzoom", a.maxz)
            .put("minzoom", a.minz)
            .put("lugares", abrirLugares() != null)
    }

    private fun buscarLugares(q: String): JSONArray {
        val l = abrirLugares() ?: return JSONArray()
        val r = JSONArray()
        for ((x, rotulo) in l.buscar(q)) {
            r.put(
                JSONObject().put("nome", x.nome).put("lat", x.lat).put("lon", x.lon).put("tipo", rotulo)
                    .put("estado", x.uf).put("populacao", x.pop),
            )
        }
        return r
    }

    companion object {
        const val ARQ_MAPA = "2_Mapas/brasil.pmtiles"
        const val ARQ_LUGARES = "2_Mapas/lugares_br.tsv"
        private val TILE = Regex("""/mapa/tiles/(\d+)/(\d+)/(\d+)\.mvt""")
        private val FONTE = Regex("""/mapa/fontes/([^/]+)/(\d+-\d+)\.pbf""")

        fun tipoDe(rel: String): String = when (rel.substringAfterLast('.').lowercase()) {
            "html" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "pbf" -> "application/x-protobuf"
            else -> "application/octet-stream"
        }
    }
}
