package br.com.waltz.bibliotecadebolso

import br.com.waltz.bibliotecadebolso.dados.ItemBanco
import br.com.waltz.bibliotecadebolso.download.Achado
import br.com.waltz.bibliotecadebolso.download.Importador
import br.com.waltz.bibliotecadebolso.ui.MANUAL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportadorTest {
    private fun item(id: String, pacote: String, destino: String, tam: Long, kiwix: String? = null) = ItemBanco(
        id, if (kiwix != null) "zim" else "pdf", pacote, "t", id, "https://x/$id", tam, destino, false, kiwix,
        "pendente", 0, null, null, null, null, 0, null,
    )

    @Test
    fun aceitaAPastaDeCimaERecusaOQueNaoEDaBiblioteca() {
        assertEquals("manuais/a.pdf", Importador.caminhoNormal("manuais/a.pdf"))
        assertEquals("manuais/a.pdf", Importador.caminhoNormal("BibliotecaDeBolso/manuais/a.pdf"))
        assertNull(Importador.caminhoNormal("fotos/a.pdf"))
        assertNull(Importador.caminhoNormal("manuais/a.pdf.parcial"))
        assertNull(Importador.caminhoNormal("manuais/../../a.pdf"))
    }

    @Test
    fun zimComOutraDataValeMasOutroLivroNao() {
        val i = item("z", "minimo", "enciclopedias/wikem_en_all_maxi_2026-10.zim", 100, "wikem_en_all_maxi")
        val novo = Achado("enciclopedias/wikem_en_all_maxi_2026-12.zim", 100, "1")
        assertEquals(novo, Importador.casar(listOf(i), listOf(novo)).single().second)
        val outro = Achado("enciclopedias/wikem_en_all_maxi_extra_2026-12.zim", 100, "2")
        assertTrue(Importador.casar(listOf(i), listOf(outro)).isEmpty())
    }

    @Test
    fun arquivoVazioNaoCasa() {
        val i = item("p", "minimo", "manuais/a.pdf", 100)
        assertTrue(Importador.casar(listOf(i), listOf(Achado("manuais/a.pdf", 0, "1"))).isEmpty())
    }

    @Test
    fun poucasSobrasDoSlimNaoViramSlim() {
        val min = item("m", "minimo", "manuais/m.pdf", 100)
        val a = item("a", "slim", "manuais/a.pdf", 10)
        val b = item("b", "slim", "manuais/b.pdf", 90)
        val itens = listOf(min, a, b)
        assertEquals("minimo", Importador.versaoDetectada(itens, listOf(min to Achado("manuais/m.pdf", 100, "1"))))
        assertEquals("minimo", Importador.versaoDetectada(itens, listOf(a to Achado("manuais/a.pdf", 10, "2"))))
        assertEquals("slim", Importador.versaoDetectada(itens, listOf(b to Achado("manuais/b.pdf", 90, "3"))))
    }

    @Test
    fun manualCobreCadaCaminhoDeUso() {
        val texto = MANUAL.joinToString(" ") { it.first + " " + it.second }
        for (termo in listOf("Slim mínimo", "Slim (78 GB)", "Completo", "PREPARAR_CELULAR.bat", "Começar a baixar",
            "O que faltou", "desinstalar", "Não substitui atendimento médico")) {
            assertTrue("manual sem: $termo", texto.contains(termo))
        }
        assertFalse(MANUAL.any { it.second.isBlank() })
    }
}
