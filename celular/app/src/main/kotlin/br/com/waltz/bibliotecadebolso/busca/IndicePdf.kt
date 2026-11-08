package br.com.waltz.bibliotecadebolso.busca

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import br.com.waltz.bibliotecadebolso.dados.Banco
import br.com.waltz.bibliotecadebolso.dados.ItemBanco
import br.com.waltz.bibliotecadebolso.download.Armazenamento
import br.com.waltz.bibliotecadebolso.util.Diario
import br.com.waltz.bibliotecadebolso.zim.Origem
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/** Um manual achado. Sem texto no índice, vem só pelo título: página 1, sem trecho. */
data class ResultadoPdf(
    val caminho: String,
    val titulo: String,
    val tema: String,
    val idioma: String,
    val pagina: Int,
    val trecho: String?,
    val pontos: Double,
    val origem: Origem = Origem.CELULAR,
    val tipo: String = "pdf",
)

private data class DocIndice(
    val id: Long, val caminho: String, val titulo: String, val tema: String, val idioma: String,
    val tipo: String, val pagIni: Long?, val pagFim: Long?,
)

/** Um índice aberto: o do celular (Slim) ou a cópia do índice do PC (Completo). */
private class FonteIndice(val c: SQLiteConnection, docs: List<DocIndice>, val origem: Origem, val temTitulos: Boolean) {
    val porCaminho = docs.associateBy { it.caminho }
    val porId = docs.associateBy { it.id }
}

/**
 * Busca nos PDFs (seção 7). O índice vem pronto do PC: o celular nunca lê os PDFs para indexar.
 * Usa uma SQLite própria porque a do Android não garante FTS5. Cada conexão só é usada sob a trava.
 */
class IndicePdf(private val ctx: Context, private val banco: Banco, private val diario: Diario) {
    private val trava = Any()
    private var slim: FonteIndice? = null
    private var completo: FonteIndice? = null

    /** Falso com o SSD desligado: a busca não oferece o que não dá para abrir. */
    @Volatile var completoDisponivel = false

    private val pasta get() = File(Armazenamento.pasta(ctx), "indice")

    val pronto: Boolean get() = synchronized(trava) { abrirSlim() != null }

    /**
     * Abre o zip baixado, confere e troca o índice em uso. O zip sai depois, para não ocupar espaço duas vezes.
     * Lança exceção se o arquivo estiver ruim ou faltar espaço: o índice antigo continua valendo.
     */
    fun instalar(zip: File) = synchronized(trava) {
        val temporario = File(pasta, zip.nameWithoutExtension + ".db.parcial")
        val final = File(pasta, zip.nameWithoutExtension + ".db")
        pasta.mkdirs()
        try {
            ZipInputStream(zip.inputStream().buffered()).use { z ->
                val e = z.nextEntry ?: throw IOException("o arquivo do índice está vazio")
                if (!e.name.endsWith(".db")) throw IOException("o arquivo do índice não é o esperado")
                FileOutputStream(temporario).use { sai -> z.copyTo(sai, 1 shl 20) }
                z.closeEntry() // Confere o CRC do zip: lança exceção se veio corrompido.
            }
            val teste = BundledSQLiteDriver().open(temporario.path, SQLITE_OPEN_READONLY)
            try {
                teste.prepare("SELECT valor FROM meta WHERE chave='versao'").use { if (!it.step()) throw IOException("índice sem versão") }
            } finally {
                teste.close()
            }
        } catch (e: Exception) {
            temporario.delete()
            throw e
        }
        slim?.c?.close()
        slim = null
        final.delete()
        if (!temporario.renameTo(final)) throw IOException("não consegui gravar o índice")
        zip.delete()
        pasta.listFiles()?.filter { it.name != final.name }?.forEach { it.delete() }
        diario.escrever("índice de PDFs instalado: ${final.name} (${final.length()} bytes)")
    }

    fun fechar() = synchronized(trava) {
        slim?.c?.close()
        slim = null
    }

    fun fecharCompleto() = synchronized(trava) {
        completo?.c?.close()
        completo = null
    }

    /** O índice do PC, já copiado para dentro do celular. Null: ainda não há. */
    fun abrirCompleto(arquivo: File?) = synchronized(trava) {
        completo?.c?.close()
        completo = null
        if (arquivo != null) {
            completo = try {
                // Leitura e escrita porque o índice do PC usa WAL. A cópia é só deste app: nada volta para o SSD.
                carregar(arquivo, SQLITE_OPEN_READWRITE, Origem.SSD)
            } catch (e: Exception) {
                diario.escrever("não abri o índice do SSD: $e")
                null
            }
        }
    }

    val completoPronto: Boolean get() = synchronized(trava) { completo != null }

    private fun abrirSlim(): FonteIndice? {
        slim?.let { return it }
        val arquivos = pasta.listFiles().orEmpty()
        // O app pode ter sido fechado entre o fim do download e a instalação.
        val db = arquivos.filter { it.name.endsWith(".db") }.maxByOrNull { it.name }
            ?: arquivos.firstOrNull { it.name.endsWith(".zip") }?.let { z ->
                try {
                    instalar(z)
                    File(pasta, z.nameWithoutExtension + ".db")
                } catch (e: Exception) {
                    diario.escrever("índice de PDFs: $e")
                    null
                }
            }
            ?: return null
        return try {
            carregar(db, SQLITE_OPEN_READONLY, Origem.CELULAR).also { slim = it }
        } catch (e: Exception) {
            diario.escrever("não abri o índice ${db.name}: $e")
            null
        }
    }

    private fun carregar(db: File, modo: Int, origem: Origem): FonteIndice {
        val c = BundledSQLiteDriver().open(db.path, modo)
        val colunas = HashSet<String>()
        c.prepare("PRAGMA table_info(docs)").use { s -> while (s.step()) colunas += s.getText(1) }
        val tabelas = HashSet<String>()
        c.prepare("SELECT name FROM sqlite_master").use { s -> while (s.step()) tabelas += s.getText(0) }
        // O índice do PC tem coluna "tipo", e pode faltar a faixa de páginas em bibliotecas antigas (seção 7).
        val tipo = if ("tipo" in colunas) "tipo" else "'pdf'"
        val ini = if ("pag_ini" in colunas) "pag_ini" else "NULL"
        val fim = if ("pag_fim" in colunas) "pag_fim" else "NULL"
        val lidos = ArrayList<DocIndice>()
        c.prepare("SELECT id,caminho,titulo,tema,idioma,$tipo,$ini,$fim FROM docs WHERE situacao IS NULL OR situacao <> 'erro'").use { s ->
            while (s.step()) {
                lidos += DocIndice(
                    s.getLong(0), s.getText(1), s.textoOuVazio(2), temaLegivel(s.textoOuVazio(3)), s.textoOuVazio(4),
                    s.textoOuVazio(5).ifEmpty { "pdf" },
                    if (s.isNull(6)) null else s.getLong(6), if (s.isNull(7)) null else s.getLong(7),
                )
            }
        }
        return FonteIndice(c, lidos, origem, "titulos" in tabelas)
    }

    private fun androidx.sqlite.SQLiteStatement.textoOuVazio(i: Int) = if (isNull(i)) "" else getText(i)

    /**
     * Só devolve o que está disponível agora: a busca funciona durante o download e com o SSD desligado.
     * [aoAvancar] recebe o que já se sabe: o índice do PC tem 2 milhões de páginas, e o título responde bem antes.
     */
    fun buscar(
        pt: List<String>,
        en: List<String>,
        incluirIngles: Boolean,
        porNomeNoSsd: (List<String>) -> List<String> = { emptyList() },
        aoAvancar: (List<ResultadoPdf>) -> Unit = {},
    ): List<ResultadoPdf> {
        val achados = LinkedHashMap<String, ResultadoPdf>()
        val noPt = pt.flatMap { listOf(it) + Consulta.sinonimos(it) }
        val noEn = if (incluirIngles) en else emptyList()
        val prontos = banco.pdfsProntos()
        val caminhos = prontos.associateBy { it.destinoEfetivo }
        synchronized(trava) {
            abrirSlim()?.let { f ->
                for (q in noPt + noEn) noTexto(f, q, incluirIngles, achados) { it.caminho in caminhos }
            }
            // Pelo título: acha o que não tem texto no índice, ou tudo enquanto o índice não chegou.
            for (q in noPt + noEn) noTituloCelular(q, prontos, incluirIngles, achados)
        }
        if (achados.isNotEmpty()) aoAvancar(ordenados(achados))
        if (completoDisponivel) {
            val noCelular = prontos.map { arquivoDe(it.destinoEfetivo) }.toSet()
            val doSsd = LinkedHashMap<String, ResultadoPdf>()
            val fonte = synchronized(trava) { completo }
            if (fonte != null) {
                val aceitar = { d: DocIndice -> arquivoDe(d.caminho) !in noCelular }
                synchronized(trava) {
                    for (q in noPt + noEn) noTituloIndice(fonte, q, incluirIngles, doSsd, aceitar)
                }
                if (doSsd.isNotEmpty()) aoAvancar(ordenados(achados + doSsd))
                synchronized(trava) {
                    for (q in noPt + noEn) noTexto(fonte, q, incluirIngles, doSsd, aceitar)
                }
            }
            if (fonte == null) {
                for (q in noPt + noEn) for (rel in porNomeNoSsd(Consulta.palavras(q))) {
                    if (arquivoDe(rel) in noCelular || rel in doSsd) continue
                    val nome = rel.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ')
                    doSsd[rel] = ResultadoPdf(rel, nome, rel.split('/').getOrElse(1) { "Acervo" }, "PT", 1, null, PONTOS_TITULO, Origem.SSD)
                }
            }
            achados += doSsd
        }
        return ordenados(achados)
    }

    private fun arquivoDe(caminho: String) = caminho.substringAfterLast('/').lowercase()

    private fun ordenados(m: Map<String, ResultadoPdf>) = m.values.sortedBy { it.pontos }.take(MAX_RESULTADOS)

    private fun noTexto(
        f: FonteIndice, termos: String, ingles: Boolean, sai: MutableMap<String, ResultadoPdf>, aceitar: (DocIndice) -> Boolean,
    ) {
        val tudo = ConsultaFts.todas(termos) ?: return
        val algum = ConsultaFts.alguma(termos)
        val palavras = Consulta.palavras(termos)
        val achou = rodar(f, tudo, palavras, ingles, sai, aceitar)
        if (achou == 0 && algum != null) rodar(f, algum, palavras, ingles, sai, aceitar)
    }

    /** Pega muitas páginas pela nota e fica com a melhor de cada manual. Trecho só das que aparecem na tela. */
    private fun rodar(
        f: FonteIndice, fts: String, palavras: List<String>, ingles: Boolean, sai: MutableMap<String, ResultadoPdf>, aceitar: (DocIndice) -> Boolean,
    ): Int {
        val novos = LinkedHashMap<Long, Triple<Long, Int, Double>>()
        for (limite in LIMITES) {
            novos.clear()
            var linhas = 0
            try {
                f.c.prepare(
                    "SELECT rowid, doc_id, pagina, rank FROM pag WHERE pag MATCH ? AND rank MATCH 'bm25(0.0, 1.0)' ORDER BY rank LIMIT ?"
                ).use { s ->
                    s.bindText(1, fts)
                    s.bindLong(2, limite.toLong())
                    while (s.step()) {
                        linhas++
                        val id = s.getLong(1)
                        val d = f.porId[id] ?: continue
                        if (!aceitar(d) || (!ingles && d.idioma == "en")) continue
                        if (id !in novos) novos[id] = Triple(s.getLong(0), s.getLong(2).toInt(), s.getDouble(3))
                    }
                }
            } catch (e: Exception) {
                diario.escrever("busca nos PDFs ($fts): $e")
                return 0
            }
            // Durante o download, as melhores páginas podem ser de manuais que ainda não chegaram.
            if (novos.size >= MAX_DOCS || linhas < limite) break
        }
        var n = 0
        for ((id, v) in novos.entries.take(MAX_DOCS).map { it.key to it.value }) {
            val d = f.porId.getValue(id)
            n++
            val titulo = d.titulo.ifBlank { d.caminho.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ') }
            // O manual cujo título é o assunto vem antes de um que só cita o assunto numa página.
            val noNome = Consulta.palavras(titulo).toSet().containsAll(palavras)
            val pontos = v.third - (if (noNome) BONUS_TITULO else 0.0) + (if (d.idioma == "en") PESO_INGLES else 0.0)
            val velho = sai[d.caminho]
            if (velho != null && velho.pontos <= pontos) continue
            sai[d.caminho] = ResultadoPdf(
                d.caminho, titulo, d.tema, if (d.idioma == "en") "EN" else "PT", v.second.coerceAtLeast(1),
                trecho(f.c, fts, v.first), pontos, f.origem, d.tipo,
            )
        }
        return n
    }

    private fun trecho(c: SQLiteConnection, fts: String, rowid: Long): String? = try {
        c.prepare("SELECT snippet(pag, 1, '<b>', '</b>', '…', 32) FROM pag WHERE pag MATCH ? AND rowid = ?").use { s ->
            s.bindText(1, fts)
            s.bindLong(2, rowid)
            if (s.step()) s.getText(0) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun noTituloCelular(termos: String, prontos: List<ItemBanco>, ingles: Boolean, sai: MutableMap<String, ResultadoPdf>) {
        val p = Consulta.palavras(termos)
        if (p.isEmpty()) return
        val docs = slim?.porCaminho.orEmpty()
        for (i in prontos) {
            if (i.destinoEfetivo in sai) continue
            val d = docs[i.destinoEfetivo]
            if (!ingles && d?.idioma == "en") continue
            val titulo = d?.titulo?.takeIf { it.isNotBlank() } ?: i.titulo
            val nome = Consulta.palavras("$titulo ${i.titulo}").toSet()
            if (p.all { it in nome }) {
                sai[i.destinoEfetivo] = ResultadoPdf(
                    i.destinoEfetivo, titulo, i.tema, if (d?.idioma == "en") "EN" else "PT", 1, null, PONTOS_TITULO,
                )
            }
        }
    }

    /** O índice do PC tem tabela própria de títulos: responde na hora, mesmo com palavra muito comum. */
    private fun noTituloIndice(
        f: FonteIndice, termos: String, ingles: Boolean, sai: MutableMap<String, ResultadoPdf>, aceitar: (DocIndice) -> Boolean,
    ) {
        if (!f.temTitulos) return
        val fts = ConsultaFts.tokens(termos).takeIf { it.isNotEmpty() }?.joinToString(" ") { "\"$it\"" } ?: return
        try {
            f.c.prepare("SELECT doc_id FROM titulos WHERE titulos MATCH ? ORDER BY rank LIMIT 30").use { s ->
                s.bindText(1, fts)
                while (s.step()) {
                    val d = f.porId[s.getLong(0)] ?: continue
                    if (!aceitar(d) || (!ingles && d.idioma == "en") || d.caminho in sai) continue
                    sai[d.caminho] = ResultadoPdf(
                        d.caminho, d.titulo.ifBlank { d.caminho.substringAfterLast('/') }, d.tema,
                        if (d.idioma == "en") "EN" else "PT", 1, null, -BONUS_TITULO + (if (d.idioma == "en") PESO_INGLES else 0.0),
                        f.origem, d.tipo,
                    )
                }
            }
        } catch (e: Exception) {
            diario.escrever("busca por título ($fts): $e")
        }
    }

    /** Texto da página, para compartilhar. Null se a página não tem texto no índice. */
    fun textoDaPagina(caminho: String, pagina: Int, doSsd: Boolean): String? = synchronized(trava) {
        val f = (if (doSsd) completo else abrirSlim()) ?: return null
        val d = f.porCaminho[caminho] ?: return null
        val ini = d.pagIni ?: return null
        val fim = d.pagFim ?: return null
        try {
            f.c.prepare("SELECT texto FROM pag WHERE rowid BETWEEN ? AND ? AND pagina = ?").use { s ->
                s.bindLong(1, ini)
                s.bindLong(2, fim)
                s.bindLong(3, pagina.toLong())
                if (s.step()) s.getText(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        /** As pastas do PC não têm acento. */
        val TEMAS = mapOf("Saude" to "Saúde", "Agua" to "Água", "Sobrevivencia" to "Sobrevivência", "Eletrica" to "Elétrica", "Religiao" to "Religião")
        fun temaLegivel(t: String) = TEMAS[t] ?: t.replace('_', ' ')

        const val MAX_DOCS = 25
        const val MAX_RESULTADOS = 40
        const val PONTOS_TITULO = 0.0 // bm25 é negativo: o que tem trecho vem antes.
        const val BONUS_TITULO = 15.0
        const val PESO_INGLES = 10.0
        val LIMITES = listOf(400, 4000, 40000)
    }
}

/** Monta a consulta do FTS5. Cada palavra vai entre aspas: "e", "ou" e hífens digitados não viram operador. */
object ConsultaFts {
    fun tokens(termos: String) = Consulta.palavras(termos).distinct()

    /** Só na coluna do texto: quem tem a palavra só no título é achado à parte, sem apontar para uma página qualquer. */
    fun todas(termos: String): String? =
        tokens(termos).takeIf { it.isNotEmpty() }?.joinToString(" ", "texto : (", ")") { "\"$it\"" }

    fun alguma(termos: String): String? =
        tokens(termos).takeIf { it.size > 1 }?.joinToString(" OR ", "texto : (", ")") { "\"$it\"" }
}
