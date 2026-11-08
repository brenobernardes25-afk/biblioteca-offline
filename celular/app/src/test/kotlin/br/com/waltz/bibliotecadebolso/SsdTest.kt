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
}
