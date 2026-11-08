package br.com.waltz.bibliotecadebolso

import br.com.waltz.bibliotecadebolso.ia.ModeloIa
import br.com.waltz.bibliotecadebolso.ia.RegrasIa
import br.com.waltz.bibliotecadebolso.ia.Trecho
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IaTest {
    private fun trecho(titulo: String, texto: String, pos: Int = 0, manual: Boolean = true, tema: String = "Água") =
        Trecho(titulo, tema, "PT", 3, texto, pos, manual, "$titulo.pdf")

    @Test
    fun modeloDependeDaMemoria() {
        assertEquals("ia:qwen3-4b", RegrasIa.modeloPara(7_400_000_000L)?.id)
        // 6 GB ficam sem IA: o modelo pequeno errava doses.
        assertNull(RegrasIa.modeloPara(5_400_000_000L))
        assertNull(RegrasIa.modeloPara(3_800_000_000L))
    }

    @Test
    fun trechoSemOAssuntoFicaDeFora() {
        val r = RegrasIa.escolher(
            listOf(
                trecho("Como purificar água", "Para purificar a água, ferva por um minuto. A água purificada dura."),
                trecho("Criação de galinhas", "As galinhas comem milho e dormem cedo.", manual = false, pos = 9),
            ),
            listOf("purificar", "agua"), emptyList(),
        )
        assertEquals(listOf("Como purificar água"), r.map { it.titulo })
    }

    @Test
    fun noMaximoDoisPorDocumento() {
        val t = "ferver agua agua agua"
        val r = RegrasIa.escolher(
            (1..4).map { Trecho("Manual", "Água", "PT", it, t, 0, true, "m.pdf") },
            listOf("ferver", "agua"), emptyList(),
        )
        assertEquals(2, r.size)
    }

    @Test
    fun contextoNumeraERespeitaOLimite() {
        val (texto, usados) = RegrasIa.contexto(
            listOf(trecho("A", "x".repeat(500)), trecho("B", "y y ".repeat(200)), trecho("C", "z".repeat(500))), 900,
        )
        assertTrue(texto.startsWith("[1] A (página 3)"))
        assertTrue(texto.contains("[2] B"))
        assertFalse(texto.contains("[3]"))
        assertEquals(2, usados.size)
    }

    @Test
    fun formatoDoPromptSegueOModelo() {
        // Nenhum modelo do app pensa hoje, mas um modelo sem "Instruct" teria que receber o pedido para não pensar.
        val semPensar = ModeloIa("x", "x.gguf", "1 GB", 3000, instruct = false)
        val p = RegrasIa.prompt(semPensar, "[1] T", "como?")
        assertTrue(p.contains("/no_think"))
        assertTrue(p.endsWith("<think>\n\n</think>\n\n"))
        assertFalse(RegrasIa.prompt(RegrasIa.modeloPara(8_000_000_000L)!!, "[1] T", "como?").contains("think"))
    }

    @Test
    fun escondeORaciocinio() {
        assertEquals("Resposta.", RegrasIa.semPensamento("<think>hum</think>Resposta."))
        assertEquals("", RegrasIa.semPensamento("<think>ainda pensando"))
        assertEquals("Ferva", RegrasIa.semPensamento("Ferva<th"))
        assertEquals("a < b", RegrasIa.semPensamento("a < b"))
    }

    @Test
    fun janelaPegaAParteDoAssunto() {
        val texto = "enchimento ".repeat(300) + "\nPara ferver a agua use panela. " + "enchimento ".repeat(300)
        assertTrue(RegrasIa.janela(texto, listOf("ferver", "agua")).contains("ferver a agua"))
        assertEquals("curto", RegrasIa.janela(" curto ", listOf("x")))
    }

    @Test
    fun avisoDeSaude() {
        assertTrue(RegrasIa.ehSaude("O que fazer com picada de cobra?", emptyList()))
        assertTrue(RegrasIa.ehSaude("como fazer", listOf(trecho("x", "y", tema = "Saúde"))))
        assertFalse(RegrasIa.ehSaude("como plantar milho", listOf(trecho("x", "y", tema = "Agricultura"))))
    }

    @Test
    fun tiraOHtmlDoArtigo() {
        val t = RegrasIa.textoDoHtml("<html><head><title>x</title></head><style>a{}</style><p>Um &amp; dois</p><script>r()</script><p>Tres</p></html>")
        assertEquals("Um & dois\nTres", t)
    }
}
