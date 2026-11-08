package br.com.waltz.bibliotecadebolso.ssd

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import br.com.waltz.bibliotecadebolso.BolsoApp
import br.com.waltz.bibliotecadebolso.R
import br.com.waltz.bibliotecadebolso.download.ErroHttp
import br.com.waltz.bibliotecadebolso.download.LimiteHost
import br.com.waltz.bibliotecadebolso.download.Rede
import br.com.waltz.bibliotecadebolso.download.SaidaParcial
import br.com.waltz.bibliotecadebolso.download.Transferidor
import br.com.waltz.bibliotecadebolso.util.bytesLegivel
import br.com.waltz.bibliotecadebolso.util.inteiro
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

data class ResultadoTeste(val gravou: Boolean, val acima4Gb: Boolean, val livre: Long, val total: Long, val erro: String?)

data class ArquivoKit(val prio: Int, val tipo: String, val rel: String, val tam: Long, val url: String)

/** Partes sem Android: fáceis de testar. */
object RegrasKit {
    /** Uma linha do manifesto do kit. Null para comentário ou linha estranha. */
    fun lerLinha(l: String): ArquivoKit? {
        if (l.startsWith("#") || l.isBlank()) return null
        val c = l.split('\t')
        if (c.size < 5) return null
        val rel = c[2]
        if (!caminhoSeguro(rel)) return null
        return ArquivoKit(c[0].toIntOrNull() ?: return null, c[1], rel, c[3].toLongOrNull() ?: 0, c[4])
    }

    /** O arquivo vem da internet: nada de escapar da pasta escolhida. */
    fun caminhoSeguro(rel: String) = rel.isNotEmpty() && !rel.startsWith("/") && !rel.contains('\\') &&
        rel.split('/').none { it == ".." || it == "." || it.isEmpty() || it.contains(':') }

    /** "wikipedia_pt_all_maxi_2026-05.zim" vira "wikipedia_pt_all_maxi", o nome que o catálogo entende. */
    fun nomeKiwix(arquivo: String): String? = Regex("""^(.+)_\d{4}-\d{2}[a-z]?\.zim$""").find(arquivo)?.groupValues?.get(1)

    /** Confere o começo do arquivo: um site fora do ar costuma devolver uma página HTML. */
    fun comecoCerto(rel: String, ini: ByteArray): Boolean {
        val magico = when (rel.substringAfterLast('.').lowercase()) {
            "pdf" -> "%PDF"
            "epub", "zip", "apk" -> "PK"
            "zim" -> "ZIM\u0004"
            "gguf" -> "GGUF"
            else -> null
        }
        val inicio = String(ini, Charsets.ISO_8859_1)
        if (magico != null) return inicio.startsWith(magico)
        val limpo = inicio.trimStart().lowercase()
        return ini.isNotEmpty() && !limpo.startsWith("<!doc") && !limpo.startsWith("<html")
    }

    const val LIMITE_FAT32 = 4_294_967_295L
}

data class TotaisMontagem(
    val total: Int = 0, val prontos: Int = 0, val falhos: Int = 0,
    val bytesTotal: Long = 0, val bytesFeitos: Long = 0,
) {
    val pendentes get() = total - prontos - falhos
}

data class EstadoMontagem(
    val ativa: Boolean = false,
    val etapa: String = "",
    val aviso: String? = null,
    val totais: TotaisMontagem = TotaisMontagem(),
    val pausada: Boolean = false,
    val terminou: Boolean = false,
)

private class BancoMontagem(ctx: Context) : SQLiteOpenHelper(ctx, "montagem.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE arq(rel TEXT PRIMARY KEY, prio INTEGER, tipo TEXT, tam INTEGER, url TEXT,
               status TEXT NOT NULL DEFAULT 'pendente', tentativas INTEGER NOT NULL DEFAULT 0, erro TEXT)"""
        )
        db.execSQL("CREATE INDEX arq_fila ON arq(status, prio, tam)")
        db.execSQL("CREATE TABLE meta(chave TEXT PRIMARY KEY, valor TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, a: Int, b: Int) = Unit

    fun meta(c: String): String? = readableDatabase.rawQuery("SELECT valor FROM meta WHERE chave=?", arrayOf(c))
        .use { if (it.moveToFirst()) it.getString(0) else null }

    fun meta(c: String, v: String) = writableDatabase.insertWithOnConflict(
        "meta", null, ContentValues().apply { put("chave", c); put("valor", v) }, SQLiteDatabase.CONFLICT_REPLACE,
    )

    fun totais(): TotaisMontagem = readableDatabase.rawQuery(
        "SELECT COUNT(*), SUM(status='pronto'), SUM(status='falhou'), COALESCE(SUM(tam),0), COALESCE(SUM(CASE WHEN status='pronto' THEN tam ELSE 0 END),0) FROM arq",
        null,
    ).use { it.moveToFirst(); TotaisMontagem(it.getInt(0), it.getInt(1), it.getInt(2), it.getLong(3), it.getLong(4)) }

    fun proximo(excluir: Set<String>, hosts: Set<String>): ArquivoKit? {
        val filtro = hosts.joinToString("") { " AND url NOT LIKE ?" }
        val args = hosts.map { "%://$it/%" }.toTypedArray()
        val limite = excluir.size + 1
        readableDatabase.rawQuery(
            "SELECT prio,tipo,rel,tam,url FROM arq WHERE status='pendente'$filtro ORDER BY prio,tam LIMIT $limite", args,
        ).use { c ->
            while (c.moveToNext()) {
                val rel = c.getString(2)
                if (rel !in excluir) return ArquivoKit(c.getInt(0), c.getString(1), rel, c.getLong(3), c.getString(4))
            }
        }
        return null
    }

    fun marcar(rel: String, status: String, erro: String? = null) {
        writableDatabase.update(
            "arq", ContentValues().apply { put("status", status); put("erro", erro) }, "rel=?", arrayOf(rel),
        )
    }

    fun tentarDeNovo() = writableDatabase.execSQL("UPDATE arq SET status='pendente', tentativas=0, erro=NULL WHERE status='falhou'")

    fun buscarPorNome(palavras: List<String>, limite: Int): List<String> {
        if (palavras.isEmpty()) return emptyList()
        val cond = palavras.joinToString(" AND ") { "rel LIKE ? ESCAPE '\\'" }
        val args = palavras.map { "%" + it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%" }.toTypedArray()
        return readableDatabase.rawQuery("SELECT rel FROM arq WHERE status='pronto' AND rel LIKE '%.pdf' AND $cond LIMIT $limite", args).use { c ->
            val r = ArrayList<String>()
            while (c.moveToNext()) r += c.getString(0)
            r
        }
    }

    fun faltaram(limite: Int): List<Pair<String, String>> =
        readableDatabase.rawQuery("SELECT rel, erro FROM arq WHERE status='falhou' ORDER BY tam DESC LIMIT $limite", null).use { c ->
            val r = ArrayList<Pair<String, String>>()
            while (c.moveToNext()) r += c.getString(0) to (c.getString(1) ?: "")
            r
        }
}

/** Grava o .part pelo SSD: o mesmo jeito do instalador do PC, para os dois se entenderem. */
private class SaidaSaf(private val a: ArvoreSaf, private val rel: String) : SaidaParcial {
    override val tamanho: Long get() = a.info(rel)?.tam ?: 0
    override fun apagar() = a.apagar(rel)
    override fun abrir(continuar: Boolean): java.io.OutputStream {
        a.garantirArquivo(rel)
        return ParcelFileDescriptor.AutoCloseOutputStream(a.escrever(rel, continuar))
    }
}

/**
 * Monta a biblioteca do zero, só pelo celular (seção 6.2). O SSD fica igual ao do instalador do PC, com as
 * mesmas marcas .ok: dá para continuar em qualquer um dos dois. Nunca formata nada.
 */
class Montagem(private val ctx: Context, private val app: BolsoApp) {
    val estado = MutableStateFlow(EstadoMontagem())
    private val db = BancoMontagem(ctx)
    private val umaPorVez = Mutex()
    private val emCurso = ConcurrentHashMap.newKeySet<String>()
    private val chamadas = ConcurrentHashMap<String, okhttp3.Call>()
    private val limite = LimiteHost()
    private val transferidor by lazy { Transferidor(app.http) }
    private val wm get() = WorkManager.getInstance(ctx)

    val ativa get() = app.prefs.montagemUri != null

    fun iniciar(substituir: Boolean = false) {
        if (!ativa || app.prefs.montagemPausada) {
            atualizarTotais()
            return
        }
        val regras = Constraints.Builder()
            .setRequiredNetworkType(if (app.prefs.soWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val pedido = OneTimeWorkRequestBuilder<MontagemWorker>().setConstraints(regras).build()
        wm.enqueueUniqueWork(NOME, if (substituir) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, pedido)
        atualizarTotais()
    }

    /**
     * Testa o SSD antes de começar. Grava um arquivo-teste e o estica até passar de 4 GB, sem ocupar espaço:
     * é o que separa o exFAT do FAT32. Devolve null se está tudo certo.
     */
    fun testar(uri: Uri): ResultadoTeste {
        val a = ArvoreSaf(ctx, uri)
        val nome = ".teste_biblioteca"
        try {
            a.garantirArquivo(nome)
            val pfd = a.escrever(nome, false)
            pfd.use {
                val st = Os.fstatvfs(it.fileDescriptor)
                val livre = st.f_bavail * st.f_frsize
                val total = st.f_blocks * st.f_frsize
                val grande = try {
                    Os.ftruncate(it.fileDescriptor, RegrasKit.LIMITE_FAT32 + 1024)
                    true
                } catch (e: ErrnoException) {
                    if (e.errno != OsConstants.EFBIG && e.errno != OsConstants.EINVAL) throw e
                    false
                }
                return ResultadoTeste(true, grande, livre, total, null)
            }
        } catch (e: Exception) {
            app.diario.escrever("montagem: SSD não aceitou gravar: $e")
            return ResultadoTeste(false, false, 0, 0, "Não consegui gravar neste SSD. Confira o cabo e se ele não está protegido contra gravação.")
        } finally {
            a.apagar(nome)
        }
    }

    fun comecar(uri: Uri, semArquivosGrandes: Boolean) {
        ctx.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        app.prefs.montagemUri = uri.toString()
        app.prefs.montagemSemGrandes = semArquivosGrandes
        app.prefs.montagemPausada = false
        app.prefs.assistenteConcluido = true
        iniciar(substituir = true)
    }

    /** O SSD está saindo: solta tudo agora. Quando ele voltar, MEDIA_MOUNTED recomeça daqui. */
    fun aoSairSsd() {
        wm.cancelUniqueWork(NOME)
        chamadas.values.forEach { it.cancel() }
        estado.value = estado.value.copy(etapa = "Esperando o SSD", aviso = "Conecte o SSD para continuar. Quando ele voltar, o download continua sozinho.")
    }

    fun pausar() {
        app.prefs.montagemPausada = true
        wm.cancelUniqueWork(NOME)
        estado.value = estado.value.copy(pausada = true, etapa = "Pausado")
    }

    fun retomar() {
        app.prefs.montagemPausada = false
        estado.value = estado.value.copy(pausada = false)
        iniciar(substituir = true)
    }

    fun tentarDeNovo() {
        db.tentarDeNovo()
        iniciar(substituir = true)
    }

    fun faltaram(n: Int = 50) = db.faltaram(n)

    fun atualizarTotais() {
        estado.value = estado.value.copy(ativa = ativa, pausada = app.prefs.montagemPausada, totais = db.totais())
    }

    private fun etapa(t: String, aviso: String? = null) {
        estado.value = estado.value.copy(ativa = true, etapa = t, aviso = aviso, totais = db.totais())
    }

    /** Sem índice no SSD, os PDFs que já chegaram são achados pelo nome do arquivo. */
    fun buscarPorNome(palavras: List<String>, limite: Int = 25) = db.buscarPorNome(palavras, limite)

    suspend fun rodar(aoTick: suspend () -> Unit) = umaPorVez.withLock {
        val uri = app.prefs.montagemUri?.let(Uri::parse) ?: return@withLock
        val a = ArvoreSaf(ctx, uri)
        try {
            a.listar()
        } catch (_: Exception) {
            etapa("Esperando o SSD", "Conecte o SSD para continuar. Quando ele voltar, o download continua sozinho.")
            return@withLock
        }
        try {
            if (db.meta("kit") != "ok") instalarKit(a)
            if (db.meta("lista") != "ok") carregarLista(a)
            app.ssd.aoChegarArquivo(uri)
            baixarTudo(a, aoTick)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            app.diario.escrever("montagem parou: $e")
            val temSsd = try {
                a.listar()
                true
            } catch (_: Exception) {
                false
            }
            etapa(
                if (temSsd) "Parado" else "Esperando o SSD",
                if (temSsd) "Sem conseguir continuar: ${e.message}. Tenta de novo sozinho." else "Conecte o SSD para continuar. Quando ele voltar, o download continua sozinho.",
            )
            if (temSsd) throw e
        }
    }

    private fun urlDoKit(): String {
        val req = Request.Builder().url(URL_ULTIMO_KIT).header("User-Agent", Transferidor.USER_AGENT)
            .header("Accept", "application/vnd.github+json").build()
        app.http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ErroHttp(r.code)
            val assets = JSONObject(r.body?.string().orEmpty()).getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val o = assets.getJSONObject(i)
                if (o.getString("name").startsWith("Kit_Biblioteca_Offline") && o.getString("name").endsWith(".zip")) {
                    return o.getString("browser_download_url")
                }
            }
        }
        throw IOException("o kit não está no GitHub")
    }

    private suspend fun instalarKit(a: ArvoreSaf) {
        // SSD que já tem o kit: não baixa de novo.
        if (a.info(ARQ_MANIFESTO) != null) {
            db.meta("kit", "ok")
            return
        }
        etapa("Baixando o kit da biblioteca")
        val zip = File(ctx.cacheDir, "kit.zip.parcial")
        transferidor.baixar(urlDoKit(), zip)
        etapa("Preparando o SSD")
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                coroutineContext.ensureActive()
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                if (!RegrasKit.caminhoSeguro(e.name)) throw IOException("kit com caminho inválido: ${e.name}")
                a.garantirArquivo(e.name)
                ParcelFileDescriptor.AutoCloseOutputStream(a.escrever(e.name, false)).use { z.copyTo(it, 1 shl 16) }
            }
        }
        zip.delete()
        db.meta("kit", "ok")
        app.diario.escrever("montagem: kit extraído no SSD")
    }

    private suspend fun carregarLista(a: ArvoreSaf) {
        etapa("Lendo a lista de arquivos")
        val itens = a.ler(ARQ_MANIFESTO).use { pfd ->
            BufferedReader(InputStreamReader(GZIPInputStream(FileInputStream(pfd.fileDescriptor)), Charsets.UTF_8)).useLines { l ->
                l.mapNotNull { RegrasKit.lerLinha(it) }.filter { it.tipo != "gerado" }.toList()
            }
        }
        // O que já tem marca .ok no SSD conta como pronto: pode ser um SSD começado no PC.
        etapa("Vendo o que já está no SSD")
        val porPasta = itens.groupBy { it.rel.substringBeforeLast('/', "") }
        val prontos = HashSet<String>()
        for ((pasta, arquivos) in porPasta) {
            coroutineContext.ensureActive()
            val nomes = try {
                a.listar(pasta).associateBy { it.nome }
            } catch (_: Exception) {
                continue // A pasta ainda não existe.
            }
            for (i in arquivos) {
                val nome = i.rel.substringAfterLast('/')
                if ("$nome.ok" in nomes && (nomes[nome]?.tam ?: 0) > 0) prontos += i.rel
            }
        }
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            val v = ContentValues()
            for (i in itens) {
                v.clear()
                v.put("rel", i.rel); v.put("prio", i.prio); v.put("tipo", i.tipo); v.put("tam", i.tam); v.put("url", i.url)
                v.put("status", if (i.rel in prontos) "pronto" else "pendente")
                w.insertWithOnConflict("arq", null, v, SQLiteDatabase.CONFLICT_IGNORE)
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
        db.meta("lista", "ok")
        app.diario.escrever("montagem: lista com ${itens.size} arquivos, ${prontos.size} já no SSD")
    }

    private suspend fun baixarTudo(a: ArvoreSaf, aoTick: suspend () -> Unit) = coroutineScope {
        val relogio = launch {
            while (true) {
                etapa(if (limite.emEspera() != null && emCurso.isEmpty()) "O servidor ${limite.emEspera()} pediu uma pausa" else "Baixando")
                aoTick()
                delay(5000)
            }
        }
        val semGrandes = app.prefs.montagemSemGrandes
        while (true) {
            coroutineContext.ensureActive()
            if (emCurso.size >= PARALELOS) {
                delay(300)
                continue
            }
            val item = db.proximo(emCurso.toSet(), limite.indisponiveis())
            if (item == null) {
                if (emCurso.isEmpty() && db.proximo(emptySet(), emptySet()) == null) break
                delay(500)
                continue
            }
            if (semGrandes && item.tam > RegrasKit.LIMITE_FAT32) {
                db.marcar(item.rel, "falhou", "Maior que 4 GB: não cabe num SSD em FAT32.")
                continue
            }
            val host = Uri.parse(item.url).host.orEmpty()
            limite.comecou(host)
            emCurso += item.rel
            launch(Dispatchers.IO) {
                var ok = false
                try {
                    ok = baixarItem(a, item, host)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    app.diario.escrever("montagem: erro em ${item.rel}: $e")
                    db.marcar(item.rel, "falhou", "Erro inesperado neste arquivo.")
                } finally {
                    limite.terminou(host, ok)
                    emCurso -= item.rel
                }
            }
        }
        relogio.cancel()
        val t = db.totais()
        estado.value = estado.value.copy(etapa = "Pronto", terminou = true, totais = t, aviso = null)
        app.diario.escrever("montagem: fim. ${t.prontos} prontos, ${t.falhos} faltaram")
        Avisos.concluido(ctx, t)
    }

    /** Devolve true se ficou pronto. IOException aqui é o SSD saindo: para tudo e espera voltar. */
    private suspend fun baixarItem(a: ArvoreSaf, item: ArquivoKit, host: String): Boolean {
        val nome = item.rel.substringAfterLast('/')
        val pasta = item.rel.substringBeforeLast('/', "")
        a.garantirPasta(pasta)
        val relParcial = "${item.rel}.part"
        val saida = SaidaSaf(a, relParcial)
        val falta = (item.tam - saida.tamanho).coerceAtLeast(0)
        if (falta > 0 && livre(a, relParcial) < falta + FOLGA_SSD) {
            db.marcar(item.rel, "falhou", "Não coube: faltam ${bytesLegivel(falta)} livres no SSD.")
            return false
        }
        var url = item.url
        var relFinal = item.rel
        var tentativas = 0
        var catalogoFeito = false
        while (true) {
            coroutineContext.ensureActive()
            try {
                val destino = if (relFinal == item.rel) saida else SaidaSaf(a, "$relFinal.part")
                val recebido = try {
                    transferidor.baixar(url, destino, aoChamar = { chamadas[item.rel] = it })
                } finally {
                    chamadas.remove(item.rel)
                }
                val relP = "$relFinal.part"
                if (!conferir(a, relP, relFinal, recebido, item, relFinal != item.rel)) return false
                if (a.info(relFinal) != null) a.apagar(relFinal)
                a.renomear(relP, relFinal.substringAfterLast('/'))
                a.garantirArquivo("$relFinal.ok")
                ParcelFileDescriptor.AutoCloseOutputStream(a.escrever("$relFinal.ok", false)).use {
                    it.write("$url\r\n".toByteArray())
                }
                db.marcar(item.rel, "pronto")
                if (item.tipo == "zim") app.prefs.montagemUri?.let { app.ssd.aoChegarArquivo(Uri.parse(it)) }
                if (item.tam > 200_000_000L) app.diario.escrever("montagem: pronto ${relFinal} (${bytesLegivel(recebido)})")
                return true
            } catch (e: ErroHttp) {
                if (e.codigo == 429 || e.codigo == 503) {
                    limite.frear(host, e.retryAfterS)
                    if (++tentativas >= 5) {
                        db.marcar(item.rel, "falhou", "O servidor está sobrecarregado (erro ${e.codigo}).")
                        return false
                    }
                    limite.terminou(host, false)
                    delay(30_000L * tentativas)
                    limite.comecou(host)
                    continue
                }
                // Enciclopédia que saiu do ar: pega a versão mais nova do mesmo livro, como faz o PC.
                if (e.codigo == 404 && item.tipo == "zim" && !catalogoFeito) {
                    catalogoFeito = true
                    val nova = RegrasKit.nomeKiwix(nome)?.let {
                        try {
                            app.catalogo.maisNova(it)
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (nova != null) {
                        url = nova.url
                        relFinal = "$pasta/${nova.nomeArquivo}"
                        continue
                    }
                }
                db.marcar(item.rel, "falhou", "O site respondeu erro ${e.codigo}.")
                return false
            } catch (e: IOException) {
                // Sem SSD não adianta insistir: sobe o erro para parar tudo e esperar ele voltar.
                if (!ssdPresente(a)) throw e
                // Sem rede não conta como tentativa.
                if (!Rede.disponivel(ctx, app.prefs.soWifi)) {
                    delay(10_000)
                    continue
                }
                if (++tentativas >= 5) {
                    db.marcar(item.rel, "falhou", "Não consegui baixar: ${e.message}")
                    return false
                }
                delay(5_000L * tentativas * tentativas)
            }
        }
    }

    private fun ssdPresente(a: ArvoreSaf) = try {
        a.listar()
        true
    } catch (_: Exception) {
        false
    }

    private fun livre(a: ArvoreSaf, rel: String): Long {
        a.garantirArquivo(rel)
        return a.ler(rel).use { val st = Os.fstatvfs(it.fileDescriptor); st.f_bavail * st.f_frsize }
    }

    private fun conferir(a: ArvoreSaf, relP: String, rel: String, recebido: Long, item: ArquivoKit, outraVersao: Boolean): Boolean {
        val ini = ByteArray(8)
        val inteiro = a.ler(relP).use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { s ->
                val n = s.read(ini).coerceAtLeast(0)
                if (!RegrasKit.comecoCerto(rel, ini.copyOf(n))) return@use false
                if (!rel.lowercase().endsWith(".pdf")) return@use true
                Transferidor.pdfInteiro(s.channel)
            }
        }
        val tamanhoBom = outraVersao || item.tam == 0L || Transferidor.tamanhoConfere(recebido, item.tam, item.tipo == "zim")
        if (!inteiro || !tamanhoBom) {
            a.apagar(relP)
            db.marcar(item.rel, "falhou", if (!inteiro) "O arquivo veio cortado ou é outra coisa. Não foi guardado." else "Veio com tamanho diferente do esperado.")
            return false
        }
        return true
    }

    private object Avisos {
        fun concluido(ctx: Context, t: TotaisMontagem) {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            val n = NotificationCompat.Builder(ctx, CANAL).setSmallIcon(R.drawable.ic_notificacao)
                .setContentTitle("Biblioteca montada no SSD")
                .setContentText("${inteiro(t.prontos)} arquivos prontos. ${inteiro(t.falhos)} faltaram.")
                .setAutoCancel(true).build()
            try {
                nm.notify(ID_FIM, n)
            } catch (_: SecurityException) {
            }
        }
    }

    companion object {
        const val NOME = "montagem"
        const val CANAL = "downloads"
        const val ID_PROGRESSO = 3
        const val ID_FIM = 4
        const val PARALELOS = 3
        const val FOLGA_SSD = 500_000_000L
        const val ARQ_MANIFESTO = "_Baixador/instalador/manifesto.tsv.gz"
        const val URL_ULTIMO_KIT = "https://api.github.com/repos/brenobernardes25-afk/biblioteca-offline/releases/latest"
    }
}

class MontagemWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app = ctx.applicationContext as BolsoApp

    private fun aviso(): ForegroundInfo {
        val e = app.montagem.estado.value
        val t = e.totais
        val pct = if (t.bytesTotal > 0) (t.bytesFeitos * 1000 / t.bytesTotal).toInt() else 0
        val texto = "${inteiro(t.prontos)} de ${inteiro(t.total)} · ${bytesLegivel(t.bytesFeitos)} de ${bytesLegivel(t.bytesTotal)}"
        val nm = applicationContext.getSystemService(android.app.NotificationManager::class.java)
        if (nm.getNotificationChannel(Montagem.CANAL) == null) {
            nm.createNotificationChannel(
                android.app.NotificationChannel(Montagem.CANAL, "Downloads da biblioteca", android.app.NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = NotificationCompat.Builder(applicationContext, Montagem.CANAL)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setContentTitle("Montando a biblioteca no SSD")
            .setContentText(texto)
            .setProgress(1000, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Montagem.ID_PROGRESSO, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Montagem.ID_PROGRESSO, n)
        }
    }

    override suspend fun getForegroundInfo() = aviso()

    override suspend fun doWork(): Result {
        val pm = applicationContext.getSystemService(PowerManager::class.java)
        // Tela desligada sem parar o download. Só enquanto baixa (seção 6.2).
        val trava = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bolso:montagem").apply { setReferenceCounted(false) }
        return try {
            trava.acquire(12 * 60 * 60 * 1000L)
            try {
                setForeground(aviso())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.diario.escrever("montagem: primeiro plano recusado: ${e.javaClass.simpleName}")
            }
            app.montagem.rodar {
                try {
                    setForeground(aviso())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            app.diario.escrever("montagem: nova tentativa depois de: $e")
            Result.retry()
        } finally {
            withContext(NonCancellable) { if (trava.isHeld) trava.release() }
        }
    }
}
