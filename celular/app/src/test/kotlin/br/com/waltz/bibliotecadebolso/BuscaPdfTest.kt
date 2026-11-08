package br.com.waltz.bibliotecadebolso

import br.com.waltz.bibliotecadebolso.busca.Consulta
import br.com.waltz.bibliotecadebolso.busca.ConsultaFts
import br.com.waltz.bibliotecadebolso.busca.DicionarioPtEn
import br.com.waltz.bibliotecadebolso.download.Importador
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuscaPdfTest {
    @Test
    fun cadaPalavraVaiEntreAspas() {
        assertEquals("texto : (\"picada\" \"cobra\")", ConsultaFts.todas(Consulta.termosPt("picada de cobra")))
        assertEquals("texto : (\"picada\" OR \"cobra\")", ConsultaFts.alguma("picada cobra"))
    }

    @Test
    fun oQueAPessoaDigitaNaoViraOperador() {
        // Sem as aspas, estes dariam erro de sintaxe no FTS5 ou mudariam o sentido da busca.
        assertEquals("texto : (\"agua\" \"or\" \"not\")", ConsultaFts.todas("agua OR NOT"))
        assertEquals("texto : (\"a\" \"b\")", ConsultaFts.todas("\"a\" -b*"))
        assertNull(ConsultaFts.todas("??? !!"))
        assertNull(ConsultaFts.alguma("agua"))
    }

    @Test
    fun acentoNaoAtrapalha() {
        assertEquals(ConsultaFts.todas("agua"), ConsultaFts.todas("Água"))
    }

    @Test
    fun dicionarioTemOsTermosDaSecao8() {
        assertTrue("só ${DicionarioPtEn.termos.size} termos", DicionarioPtEn.termos.size >= 500)
        assertEquals("water", DicionarioPtEn.termos["agua"])
        assertEquals("snake", DicionarioPtEn.termos["cobra"])
        DicionarioPtEn.termos.keys.forEach { k ->
            assertEquals("chave com acento ou maiúscula: $k", Consulta.semAcento(k).lowercase(), k)
        }
    }

    @Test
    fun indiceTambemVemDoComputador() {
        assertEquals("indice/indice_slim_1.zip", Importador.caminhoNormal("BibliotecaDeBolso/indice/indice_slim_1.zip"))
    }
}
