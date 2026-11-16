package br.com.waltz.bibliotecadebolso

import br.com.waltz.bibliotecadebolso.ssd.ArvoreSaf
import br.com.waltz.bibliotecadebolso.ssd.BibliotecaSsd
import br.com.waltz.bibliotecadebolso.ssd.RegrasKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdTest {
    @Test
    fun soAceitaAPastaDaBiblioteca() {
        assertTrue(BibliotecaSsd.validaBiblioteca(setOf("1_Enciclopedia", "5_Acervo", "App")))
        assertFalse(BibliotecaSsd.validaBiblioteca(setOf("1_Enciclopedia")))
        assertFalse(BibliotecaSsd.validaBiblioteca(setOf("Fotos", "Musicas")))
    }

    @Test
    fun caminhoDoDocumentoValeNaRaizENumaSubpasta() {
        assertEquals("ABCD-1234:", ArvoreSaf.caminhoBase("ABCD-1234:"))
        assertEquals("ABCD-1234:Biblioteca/", ArvoreSaf.caminhoBase("ABCD-1234:Biblioteca"))
    }

    @Test
    fun leLinhaDoManifesto() {
        val a = RegrasKit.lerLinha("2\tbaixar\t5_Acervo/Saude/a b.pdf\t123\thttps://x/a.pdf")
        assertNotNull(a)
        assertEquals(2, a!!.prio)
        assertEquals("5_Acervo/Saude/a b.pdf", a.rel)
        assertEquals(123L, a.tam)
        assertNull(RegrasKit.lerLinha("# prioridade\ttipo"))
        assertNull(RegrasKit.lerLinha("linha estranha"))
    }

    @Test
    fun manifestoNaoEscapaDaPastaEscolhida() {
        assertNull(RegrasKit.lerLinha("1\tbaixar\t../fora.pdf\t1\thttps://x"))
        assertNull(RegrasKit.lerLinha("1\tbaixar\ta/../../fora.pdf\t1\thttps://x"))
        assertFalse(RegrasKit.caminhoSeguro("/raiz.pdf"))
        assertFalse(RegrasKit.caminhoSeguro("C:/windows/x"))
        assertFalse(RegrasKit.caminhoSeguro("a\\b"))
        assertFalse(RegrasKit.caminhoSeguro(""))
        assertTrue(RegrasKit.caminhoSeguro("Programas/Python/python.exe"))
    }

    @Test
    fun nomeDoLivroParaOCatalogo() {
        assertEquals("wikipedia_pt_all_maxi", RegrasKit.nomeKiwix("wikipedia_pt_all_maxi_2026-05.zim"))
        assertEquals("zimgit-water_en", RegrasKit.nomeKiwix("zimgit-water_en_2024-08a.zim"))
        assertNull(RegrasKit.nomeKiwix("sem_data.zim"))
    }

    @Test
    fun paginaDeErroNaoPassaPorArquivo() {
        assertTrue(RegrasKit.comecoCerto("a.pdf", "%PDF-1.7".toByteArray()))
        assertFalse(RegrasKit.comecoCerto("a.pdf", "<!DOCTYP".toByteArray()))
        assertTrue(RegrasKit.comecoCerto("a.zim", byteArrayOf(90, 73, 77, 4, 0, 0)))
        assertFalse(RegrasKit.comecoCerto("a.zim", "<html>".toByteArray()))
        assertFalse(RegrasKit.comecoCerto("a.txt", "  <html>".toByteArray()))
        assertTrue(RegrasKit.comecoCerto("a.txt", "Biblioteca".toByteArray()))
    }

    @Test
    fun leOSha256QuandoALinhaTem() {
        val sha = "ab".repeat(32)
        assertEquals(sha, RegrasKit.lerLinha("2	baixar	5_Acervo/a.pdf	123	https://x/a.pdf	" + sha.uppercase())!!.sha)
        // Lista antiga (5 colunas), "-" e lixo: sem conferência, mas a linha continua valendo.
        assertEquals("", RegrasKit.lerLinha("2	baixar	5_Acervo/a.pdf	123	https://x/a.pdf")!!.sha)
        assertEquals("", RegrasKit.lerLinha("2	baixar	5_Acervo/a.pdf	123	https://x/a.pdf	-")!!.sha)
        assertEquals("", RegrasKit.lerLinha("2	baixar	5_Acervo/a.pdf	123	https://x/a.pdf	" + "zz".repeat(32))!!.sha)
    }

    @Test
    fun oZimDizOProprioTamanho() {
        val cab = ByteArray(80)
        "ZIM".toByteArray(Charsets.ISO_8859_1).copyInto(cab)
        // Posição do checksum = 220 GB - 16, em 8 bytes, o menos significativo primeiro.
        var v = 220_000_000_000L - 16
        for (i in 72..79) {
            cab[i] = (v and 0xFF).toByte()
            v = v shr 8
        }
        assertEquals(220_000_000_000L, RegrasKit.tamanhoDoZim(cab))
        assertNull(RegrasKit.tamanhoDoZim(cab.copyOf(40)))
        assertNull(RegrasKit.tamanhoDoZim(ByteArray(80)))
    }

    @Test
    fun oRitmoDizAVerdade() {
        assertEquals("Medindo a velocidade…", RegrasKit.ritmo(-1.0, 0, 1000))
        assertTrue(RegrasKit.ritmo(2_000_000.0, 0, 7_200_000_000L).contains("2 MB/s"))
        assertTrue(RegrasKit.ritmo(2_000_000.0, 0, 7_200_000_000L).contains("1 h"))
        assertTrue(RegrasKit.ritmo(2_000_000.0, 600, 1000).startsWith("Sem receber nada há 10 min"))
        assertTrue(RegrasKit.ritmo(100.0, 0, 1000).startsWith("Quase parado"))
    }

    @Test
    fun oRelatorioTemAsPartesCertas() {
        val t = br.com.waltz.bibliotecadebolso.util.Relatorio.montar(
            listOf("app 1.1.0"), emptyList(), listOf("Situação: Baixando"), listOf("x".repeat(500)),
        )
        assertTrue(t.startsWith("RELATÓRIO DA BIBLIOTECA DE BOLSO"))
        assertTrue(t.contains("MONTAGEM NO SSD") && t.contains("  Situação: Baixando"))
        assertFalse(t.contains("NO CELULAR"))
        assertTrue(t.lines().none { it.length > 230 })
    }

    @Test
    fun aCopiaNossaVaiNaFrenteQuandoOSiteEFragil() {
        val copia = "https://github.com/x/y/releases/download/t/a.pdf"
        val lento = RegrasKit.lerLinha("2\tbaixar\t5_Acervo/a.pdf\t1\thttps://web.archive.org/web/1id_/http://s/a.pdf\t-\t$copia")!!
        assertEquals(copia, lento.url)
        assertTrue(lento.reserva.startsWith("https://web.archive.org/"))
        val firme = RegrasKit.lerLinha("2\tbaixar\t5_Acervo/a.pdf\t1\thttps://bvsms.saude.gov.br/a.pdf\t-\t$copia")!!
        assertEquals("https://bvsms.saude.gov.br/a.pdf", firme.url)
        assertEquals(copia, firme.reserva)
        assertEquals("", RegrasKit.lerLinha("2\tbaixar\t5_Acervo/a.pdf\t1\thttps://s/a.pdf\t-\t-")!!.reserva)
    }
}
