package br.com.waltz.bibliotecadebolso

import br.com.waltz.bibliotecadebolso.mapa.CanalArquivo
import br.com.waltz.bibliotecadebolso.mapa.FonteMapa
import br.com.waltz.bibliotecadebolso.mapa.Lugares
import br.com.waltz.bibliotecadebolso.mapa.Pmtiles
import br.com.waltz.bibliotecadebolso.mapa.ServidorMapa
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * O mapa do celular contra o mapa do PC. Os tiles, ids e buscas esperados saíram do mapa.py (biblioteca do PC)
 * lendo o mesmo brasil.pmtiles. Os testes que precisam do pen drive pulam se ele não estiver em E:.
 */
class MapaTest {
    private val raiz = File(System.getenv("BIBLIOTECA_PENDRIVE") ?: "E:/")

    private val fonte = object : FonteMapa {
        override fun ler(rel: String): ByteArray? = File(raiz, rel).takeIf { it.isFile }?.readBytes()
        override fun abrirCanal(rel: String): CanalArquivo? {
            val f = File(raiz, rel).takeIf { it.isFile } ?: return null
            val raf = RandomAccessFile(f, "r")
            return CanalArquivo(raf.channel) { raf.close() }
        }
    }

    private fun temPenDrive() = File(raiz, ServidorMapa.ARQ_MAPA).isFile

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun idDoTileIgualAoDoPc() {
        assertEquals(0L, Pmtiles.idDoTile(0, 0, 0))
        assertEquals(13696979L, Pmtiles.idDoTile(12, 1527, 2297))
        assertEquals(876606696L, Pmtiles.idDoTile(15, 12216, 18381))
    }

    @Test
    fun buscaDeLugaresSemAcentoEComEstado() {
        val l = Lugares(
            "nome\tlat\tlon\ttipo\testado\tpop\n" +
                "Juiz de Fora\t-21.76\t-43.35\tPPLA2\tMinas Gerais\t540756\n" +
                "Juiz De Fora Airport\t-21.79\t-43.38\tAIRP\tMinas Gerais\t0\n" +
                "São José\t-27.6\t-48.6\tPPLA2\tSanta Catarina\t250000\n",
        )
        val r = l.buscar("juiz de fora")
        assertEquals("Juiz de Fora", r[0].first.nome)
        assertEquals("cidade (sede de município)", r[0].second)
        assertEquals("aeroporto", r[1].second)
        assertEquals("São José", l.buscar("sao jose, santa")[0].first.nome)
        assertTrue(l.buscar("sao jose, bahia").isEmpty())
        assertTrue(l.buscar("j").isEmpty())
    }

    @Test
    fun tilesDoPenDriveIguaisAosDoPc() {
        assumeTrue("pen drive não está em $raiz", temPenDrive())
        Pmtiles(fonte.abrirCanal(ServidorMapa.ARQ_MAPA)!!).use { p ->
            assertEquals(0, p.minz)
            assertEquals(15, p.maxz)
            assertTrue(p.gzip)
            assertEquals(-74.1, p.limites[0], 1e-6)
            val esperados = mapOf(
                Triple(0, 0, 0) to "d8e74839926b1a54901fd6f2f597ab1048801fee3894ac1a1c8e7424414d0d9e",
                Triple(4, 5, 8) to "193e68f0330ebd382bd20f04cad54a56c304b47c736e25f3fda3552949b06fa9",
                Triple(8, 95, 143) to "222e09903e2f82189828f82eb2a4743eca5d7f1e4d94640c23ddb9fe536fa482",
                Triple(12, 1527, 2297) to "4ce457e86bd9ab19b5aa0f804df66938cf4f9a588138100d26609044c421fa51",
                Triple(14, 6108, 9190) to "92453086bc3ea72ad756da68d467169f514cab58bf224bc92d217811e38b6428",
                Triple(15, 12216, 18381) to "3682a1a089e55b071c0b091426718add37f6b548228a8049a9561e4157288f66",
            )
            for ((zxy, h) in esperados) assertEquals("tile $zxy", h, sha(p.tile(zxy.first, zxy.second, zxy.third)!!))
            assertNull(p.tile(3, 0, 0)) // fora do Brasil
            assertNull(p.tile(16, 0, 0)) // mais perto do que o mapa tem
        }
    }

    private fun pedir(porta: Int, caminho: String): Triple<Int, ByteArray, Map<String, List<String>>> {
        val c = URL("http://127.0.0.1:$porta$caminho").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept-Encoding", "identity")
        val cod = c.responseCode
        val corpo = (if (cod < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return Triple(cod, corpo, c.headerFields.filterKeys { it != null })
    }

    @Test
    fun servidorEntregaPaginaTilesFontesELugares() {
        assumeTrue("pen drive não está em $raiz", temPenDrive())
        val s = ServidorMapa(fonte, { println(it) })
        val porta = s.iniciar()
        try {
            val (c1, info, _) = pedir(porta, "/api/mapa")
            assertEquals(200, c1)
            val j = JSONObject(String(info))
            assertTrue(j.getBoolean("ok"))
            assertEquals(15, j.getInt("maxzoom"))

            val (c2, html, _) = pedir(porta, "/mapa")
            assertEquals(200, c2)
            val h = String(html)
            assertTrue(h.contains("/static/mapa/maplibre-gl.js"))
            assertFalse("o botão de voltar para a biblioteca do PC sai", h.contains("href=\"/\""))

            val (c3, tile, cab) = pedir(porta, "/mapa/tiles/12/1527/2297.mvt")
            assertEquals(200, c3)
            assertEquals("4ce457e86bd9ab19b5aa0f804df66938cf4f9a588138100d26609044c421fa51", sha(tile))
            assertEquals("gzip", cab["Content-Encoding"]?.firstOrNull())
            assertEquals(204, pedir(porta, "/mapa/tiles/3/0/0.mvt").first)

            val (c4, fonteGlifo, _) = pedir(porta, "/mapa/fontes/Noto%20Sans%20Regular/0-255.pbf")
            assertEquals(200, c4)
            assertEquals(File(raiz, "App/static/mapa/fonts/Noto Sans Regular/0-255.pbf").length(), fonteGlifo.size.toLong())

            val (c5, js, cabJs) = pedir(porta, "/static/mapa/maplibre-gl.js")
            assertEquals(200, c5)
            assertTrue(js.size > 500_000)
            assertTrue(cabJs["Content-Type"]!!.first().startsWith("text/javascript"))

            val (c6, lug, _) = pedir(porta, "/api/lugares?q=juiz%20de%20fora")
            assertEquals(200, c6)
            val l = JSONObject(String(lug, Charsets.UTF_8)).getJSONArray("lugares")
            assertEquals("Juiz de Fora", l.getJSONObject(0).getString("nome"))
            assertEquals("Minas Gerais", l.getJSONObject(0).getString("estado"))
            assertEquals(540756, l.getJSONObject(0).getInt("populacao"))

            assertEquals(404, pedir(porta, "/static/../App/dados/indice.db").first)
        } finally {
            s.parar()
        }
    }

    /** Só quando pedido (BIBLIOTECA_MAPA_OLHAR=segundos): deixa o servidor na porta 8897 para abrir num navegador. */
    @Test
    fun servirParaOlharNoNavegador() {
        val segundos = System.getenv("BIBLIOTECA_MAPA_OLHAR")?.toLongOrNull()
        assumeTrue(segundos != null && temPenDrive())
        val s = ServidorMapa(fonte, { println(it) })
        s.iniciar(8897)
        try {
            Thread.sleep(segundos!! * 1000)
        } finally {
            s.parar()
        }
    }

    @Test
    fun semPenDriveExplicaEmVezDeQuebrar() {
        val s = ServidorMapa(fonte, {}, { "Ligue o pen drive no celular." })
        val porta = s.iniciar()
        try {
            val j = JSONObject(String(pedir(porta, "/api/mapa").second))
            assertFalse(j.getBoolean("ok"))
            assertEquals("Ligue o pen drive no celular.", j.getString("motivo"))
            assertTrue(String(pedir(porta, "/mapa").second).contains("Ligue o pen drive"))
        } finally {
            s.parar()
        }
    }
}
