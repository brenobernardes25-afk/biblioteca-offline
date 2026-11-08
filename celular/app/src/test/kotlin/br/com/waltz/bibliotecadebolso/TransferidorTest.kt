package br.com.waltz.bibliotecadebolso

import br.com.waltz.bibliotecadebolso.download.ErroHttp
import br.com.waltz.bibliotecadebolso.download.Transferidor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class TransferidorTest {
    private lateinit var srv: MockWebServer
    private lateinit var pasta: File
    private lateinit var parcial: File
    private val t = Transferidor(OkHttpClient())
    private val dados = ByteArray(100_000) { (it % 251).toByte() }

    @Before
    fun antes() {
        srv = MockWebServer().apply { start() }
        pasta = Files.createTempDirectory("bolso").toFile()
        parcial = File(pasta, "a.pdf.parcial")
    }

    @After
    fun depois() {
        srv.shutdown()
        pasta.deleteRecursively()
    }

    private fun corpo(b: ByteArray) = Buffer().write(b)

    @Test
    fun baixaDoZero() {
        srv.enqueue(MockResponse().setBody(corpo(dados)))
        assertEquals(dados.size.toLong(), t.baixar(srv.url("/a").toString(), parcial))
        assertArrayEquals(dados, parcial.readBytes())
        assertNull(srv.takeRequest().getHeader("Range"))
    }

    @Test
    fun retomaCom206SemRecomecar() {
        parcial.writeBytes(dados.copyOf(40_000))
        srv.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 40000-99999/100000")
                .setBody(corpo(dados.copyOfRange(40_000, dados.size)))
        )
        assertEquals(100_000L, t.baixar(srv.url("/a").toString(), parcial))
        assertArrayEquals(dados, parcial.readBytes())
        assertEquals("bytes=40000-", srv.takeRequest().getHeader("Range"))
    }

    @Test
    fun servidorIgnoraRangeERespondem200Recomeca() {
        parcial.writeBytes(ByteArray(40_000) { 9 })
        srv.enqueue(MockResponse().setBody(corpo(dados)))
        assertEquals(100_000L, t.baixar(srv.url("/a").toString(), parcial))
        assertArrayEquals(dados, parcial.readBytes())
    }

    @Test
    fun conexaoCaindoGuardaOQueChegou() {
        srv.enqueue(
            MockResponse().setBody(corpo(dados))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        assertThrows(IOException::class.java) { t.baixar(srv.url("/a").toString(), parcial) }
        val chegou = parcial.length()
        assertTrue(chegou < dados.size)

        srv.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $chegou-99999/100000")
                .setBody(corpo(dados.copyOfRange(chegou.toInt(), dados.size)))
        )
        assertEquals(100_000L, t.baixar(srv.url("/a").toString(), parcial))
        assertArrayEquals(dados, parcial.readBytes())
    }

    @Test
    fun parcialCompletoCom416ContaComoPronto() {
        parcial.writeBytes(dados)
        srv.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */100000"))
        assertEquals(100_000L, t.baixar(srv.url("/a").toString(), parcial))
    }

    @Test
    fun parcialMaiorQueOArquivoCom416Recomeca() {
        parcial.writeBytes(ByteArray(200_000))
        srv.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */100000"))
        assertThrows(IOException::class.java) { t.baixar(srv.url("/a").toString(), parcial) }
        assertFalse(parcial.exists())
    }

    @Test
    fun faixaDiferenteDaPedidaNaoCorrompe() {
        parcial.writeBytes(dados.copyOf(40_000))
        srv.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-99999/100000")
                .setBody(corpo(dados))
        )
        assertThrows(IOException::class.java) { t.baixar(srv.url("/a").toString(), parcial) }
        assertFalse(parcial.exists())
    }

    @Test
    fun erro404ViraErroHttp() {
        srv.enqueue(MockResponse().setResponseCode(404))
        val e = assertThrows(ErroHttp::class.java) { t.baixar(srv.url("/a").toString(), parcial) }
        assertEquals(404, e.codigo)
    }

    @Test
    fun erro429TrazORetryAfter() {
        srv.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        val e = assertThrows(ErroHttp::class.java) { t.baixar(srv.url("/a").toString(), parcial) }
        assertEquals(429, e.codigo)
        assertEquals(120L, e.retryAfterS)
    }

    @Test
    fun confereTamanho() {
        assertTrue(Transferidor.tamanhoConfere(100, 100, false))
        assertFalse(Transferidor.tamanhoConfere(101, 100, false))
        // Catálogo arredonda: 19211264 anunciado, 19210629 de verdade.
        assertTrue(Transferidor.tamanhoConfere(19_210_629, 19_211_264, true))
        assertFalse(Transferidor.tamanhoConfere(10_000_000, 19_211_264, true))
    }

    @Test
    fun pdfCortadoNaoPassa() {
        val miolo = ByteArray(5_000) { 'x'.code.toByte() }
        val inteiro = "%PDF-1.4\n".toByteArray() + miolo + "\nstartxref\n123\n%%EOF\n".toByteArray()

        parcial.writeBytes(inteiro)
        assertTrue(Transferidor.pdfInteiro(parcial))

        // Cortado no meio, como as cópias de exatamente 1 MiB.
        parcial.writeBytes(inteiro.copyOf(3_000))
        assertFalse(Transferidor.pdfInteiro(parcial))

        // Linearizado: tem um %%EOF no começo, mas o final sumiu.
        parcial.writeBytes("%PDF-1.4\n%%EOF\n".toByteArray() + miolo)
        assertFalse(Transferidor.pdfInteiro(parcial))

        // Página de erro do servidor com o tamanho certo.
        parcial.writeBytes("<html>erro</html>%%EOF".toByteArray())
        assertFalse(Transferidor.pdfInteiro(parcial))

        parcial.writeBytes(ByteArray(0))
        assertFalse(Transferidor.pdfInteiro(parcial))
    }
}
