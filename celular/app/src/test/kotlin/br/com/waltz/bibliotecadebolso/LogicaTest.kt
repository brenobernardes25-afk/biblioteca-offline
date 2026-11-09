package br.com.waltz.bibliotecadebolso

import br.com.waltz.bibliotecadebolso.busca.Consulta
import br.com.waltz.bibliotecadebolso.dados.ItemManifesto
import br.com.waltz.bibliotecadebolso.dados.Manifesto
import br.com.waltz.bibliotecadebolso.dados.OrdemDownload
import br.com.waltz.bibliotecadebolso.download.CatalogoKiwix
import br.com.waltz.bibliotecadebolso.download.LimiteHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogicaTest {
    private fun item(id: String, tema: String, tam: Long) =
        ItemManifesto(id, "pdf", "minimo", tema, id, "https://x/$id", tam, "m/$id", false, null)

    @Test
    fun ordemSaudePrimeiroDepoisAguaESobrevivenciaEDoMenorParaOMaior() {
        val r = OrdemDownload.ordenar(
            listOf(
                item("embrapa", "Embrapa — Horta e hortaliças", 1),
                item("sob", "Sobrevivência", 50),
                item("agua", "Água", 10),
                item("saude-g", "Saúde", 900),
                item("saude-p", "Saúde", 5),
            )
        ).map { it.id }
        assertEquals(listOf("saude-p", "saude-g", "agua", "sob", "embrapa"), r)
    }

    @Test
    fun manifestoRealTemOsNumerosDaEspecificacao() {
        val m = Manifesto.ler(File("src/main/assets/pacotes.json").readText(Charsets.UTF_8))
        // Nove a menos que a especificação: caderneta_crianca_menina_2019 só existe cortada, a IA de 1,7B saiu,
        // 5 manuais militares sem permissão de redistribuir e 2 PDFs quebrados no site da Embrapa.
        assertEquals(3594, m.itens.size)
        assertEquals(listOf("ia:qwen3-4b"), m.itens.filter { it.tipo == "ia" }.map { it.id })
        assertEquals(41, m.itens.count { it.tipo == "zim" })
        val minimo = m.itens.filter { it.pacote == "minimo" }
        assertEquals(1173, minimo.size)
        assertEquals(m.tamMinimo, minimo.sumOf { it.tam })
        assertEquals(3593, m.itens.count { it.pacote != "opcional" })
        assertEquals(m.tamSlim, m.itens.filter { it.pacote != "opcional" }.sumOf { it.tam })
        assertEquals(m.itens.size, m.itens.map { it.id }.toSet().size)
        assertEquals(m.itens.size, m.itens.map { it.destino }.toSet().size)
    }

    @Test
    fun versaoFicaNoComecoDoManifesto() {
        val inicio = File("src/main/assets/pacotes.json").inputStream().use { String(it.readNBytes(256), Charsets.UTF_8) }
        assertTrue(Regex("\"versao\"\\s*:\\s*\"[^\"]+\"").containsMatchIn(inicio))
    }

    @Test
    fun caminhoQueEscapaDaPastaERecusado() {
        assertTrue(Manifesto.caminhoSeguro("manuais/Agua/a.pdf"))
        assertFalse(Manifesto.caminhoSeguro("../a.pdf"))
        assertFalse(Manifesto.caminhoSeguro("manuais/../../a.pdf"))
        assertFalse(Manifesto.caminhoSeguro("/etc/a"))
        assertFalse(Manifesto.caminhoSeguro(""))
    }

    private val FEED = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/">
  <updated>2026-11-06T17:54:12Z</updated>
  <link rel="self" href="/catalog/v2/entries" type="application/atom+xml"/>
  <entry>
    <id>a</id><title>Wikipédia maxi velha</title><updated>2025-01-01T00:00:00Z</updated>
    <name>wikipedia_pt_all</name><flavour>maxi</flavour>
    <link rel="http://opds-spec.org/acquisition/open-access" type="application/x-zim" href="https://lb.download.kiwix.org/zim/wikipedia/wikipedia_pt_all_maxi_2025-01.zim.meta4" length="100" />
  </entry>
  <entry>
    <id>b</id><title>Wikipédia maxi</title><updated>2026-05-08T00:00:00Z</updated>
    <name>wikipedia_pt_all</name><flavour>maxi</flavour>
    <author><name>Wikipedia</name></author>
    <publisher><name>openZIM</name></publisher>
    <link rel="alternate" href="/content/x" type="text/html"/>
    <link rel="http://opds-spec.org/acquisition/open-access" type="application/x-zim" href="https://lb.download.kiwix.org/zim/wikipedia/wikipedia_pt_all_maxi_2026-05.zim.meta4" length="20641395712" />
  </entry>
  <entry>
    <id>c</id><title>Wikipédia mini</title><updated>2026-06-08T00:00:00Z</updated>
    <name>wikipedia_pt_all</name><flavour>mini</flavour>
    <link rel="http://opds-spec.org/acquisition/open-access" type="application/x-zim" href="https://lb.download.kiwix.org/zim/wikipedia/wikipedia_pt_all_mini_2026-06.zim.meta4" length="1703536640" />
  </entry>
</feed>"""

    @Test
    fun catalogoPegaAMaisNovaDoSaborCertoESemMeta4() {
        val v = CatalogoKiwix.lerFeed(FEED, "wikipedia_pt_all", "maxi")!!
        assertEquals("https://lb.download.kiwix.org/zim/wikipedia/wikipedia_pt_all_maxi_2026-05.zim", v.url)
        assertEquals("wikipedia_pt_all_maxi_2026-05.zim", v.nomeArquivo)
        assertEquals(20641395712L, v.tam)
    }

    @Test
    fun catalogoVazioDevolveNull() {
        assertNull(CatalogoKiwix.lerFeed("<feed><totalResults>0</totalResults></feed>", "x", null))
    }

    @Test
    fun separaSaborDoNome() {
        assertEquals("wikipedia_pt_all" to "maxi", CatalogoKiwix.separarSabor("wikipedia_pt_all_maxi"))
        assertEquals("wiktionary_pt_all" to "nopic", CatalogoKiwix.separarSabor("wiktionary_pt_all_nopic"))
        assertEquals("zimgit-water_en" to null, CatalogoKiwix.separarSabor("zimgit-water_en"))
    }

    @Test
    fun consultaTiraAcentoEPalavrasVazias() {
        assertEquals("purificar agua", Consulta.termosPt("Como purificar água?"))
        assertEquals("picada cobra", Consulta.termosPt("picada de cobra"))
        // Só palavra vazia: fica como está.
        assertEquals("como", Consulta.termosPt("como"))
    }

    @Test
    fun traducaoPeloDicionario() {
        assertEquals("purify water", Consulta.termosEn("como purificar água"))
        assertEquals("snake bite", Consulta.termosEn("picada de cobra"))
        assertEquals("first aid", Consulta.termosEn("primeiros socorros"))
        assertEquals("paracetamol", Consulta.termosEn("paracetamol"))
    }

    @Test
    fun servidorCuidadosoRecebeUmPedidoPorVezComIntervalo() {
        var t = 1_000_000L
        val l = LimiteHost { t }
        assertFalse("web.archive.org" in l.indisponiveis())
        l.comecou("web.archive.org")
        assertTrue("web.archive.org" in l.indisponiveis())
        l.terminou("web.archive.org", true)
        assertTrue("web.archive.org" in l.indisponiveis())
        t += LimiteHost.INTERVALO_MS
        assertFalse("web.archive.org" in l.indisponiveis())
        // Servidor comum não tem limite próprio.
        l.comecou("www.embrapa.br")
        assertFalse("www.embrapa.br" in l.indisponiveis())
    }

    @Test
    fun erro429PoeOServidorEmEsperaCrescenteERespeitaRetryAfter() {
        var t = 1_000_000L
        val l = LimiteHost { t }
        assertFalse(l.frear("a.org", null))
        assertTrue("a.org" in l.indisponiveis())
        t += LimiteHost.ESPERA_BASE_MS + LimiteHost.INTERVALO_MS
        assertFalse("a.org" in l.indisponiveis())
        // Segunda vez: 2 min, ou o que o servidor pedir, se for mais.
        l.frear("a.org", 600)
        t += 300_000
        assertTrue("a.org" in l.indisponiveis())
        t += 300_000
        assertFalse("a.org" in l.indisponiveis())
        // Mais esperas depois, chega ao máximo e avisa que está saturado.
        l.frear("a.org", null); l.frear("a.org", null)
        assertTrue(l.frear("a.org", null))
        // Sucesso zera a escala.
        l.terminou("a.org", true)
        assertFalse(l.frear("a.org", null))
    }

    @Test
    fun planoBComOu() {
        assertEquals("a OR b", Consulta.comOu("a b"))
        assertNull(Consulta.comOu("a"))
    }
}
