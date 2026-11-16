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

data class ArquivoKit(
    val prio: Int, val tipo: String, val rel: String, val tam: Long, val url: String,
    val sha: String = "",
    /** Outro endereço para o mesmo arquivo, tentado se o primeiro falhar. */
    val reserva: String = "",
)

/** Partes sem Android: fáceis de testar. */
object RegrasKit {
    /** Uma linha do manifesto do kit. Null para comentário ou linha estranha. */
    fun lerLinha(l: String): ArquivoKit? {
        if (l.startsWith("#") || l.isBlank()) return null
        val c = l.split('\t')
        if (c.size < 5) return null
        val rel = c[2]
        if (!caminhoSeguro(rel)) return null
        // A 6ª coluna (SHA-256) é opcional: listas antigas não têm, e "-" quer dizer "sem conferência".
        val sha = c.getOrNull(5)?.trim()?.lowercase()?.takeIf { s -> s.length == 64 && s.all { it in "0123456789abcdef" } }.orEmpty()
        // A 7ª coluna (opcional) é a cópia nossa nos Releases do GitHub. Quando o site original é dos frágeis
        // (lento, ou que recusa celular), a cópia vai na frente e o site vira a reserva.
        val copia = c.getOrNull(6)?.trim()?.takeIf { it.startsWith("https://") }.orEmpty()
        val fragil = FRAGEIS.any { c[4].contains(it) }
        val (url, reserva) = if (copia.isNotEmpty() && fragil) copia to c[4] else c[4] to copia
        return ArquivoKit(c[0].toIntOrNull() ?: return null, c[1], rel, c[3].toLongOrNull() ?: 0, url, sha, reserva)
    }

    val FRAGEIS = listOf("web.archive.org/", "trueprepper.com/")

    /** Tamanho que o próprio .zim diz ter (posição do checksum + 16), ou null se o cabeçalho não serve. */
    fun tamanhoDoZim(cab: ByteArray): Long? {
        if (cab.size < 80 || !String(cab, 0, 4, Charsets.ISO_8859_1).startsWith("ZIM\u0004")) return null
        var v = 0L
        for (i in 79 downTo 72) v = (v shl 8) or (cab[i].toLong() and 0xFF)
        return if (v > 0) v + 16 else null
    }

    /** O que mostrar sobre o ritmo do download. velocidade < 0: ainda medindo. */
    fun ritmo(velocidade: Double, paradoS: Long, faltam: Long): String = when {
        velocidade < 0 -> "Medindo a velocidade…"
        paradoS > 180 -> "Sem receber nada há ${paradoS / 60} min. A internet ou o site estão parados; continua tentando sozinho."
        velocidade < 20_000 -> "Quase parado nos últimos minutos. Continua tentando sozinho."
        else -> "${br.com.waltz.bibliotecadebolso.util.bytesLegivel(velocidade.toLong())}/s nos últimos minutos · " +
            br.com.waltz.bibliotecadebolso.util.tempoRestante((faltam / velocidade).toLong())
    }

    /** Arquivos a partir deste tamanho ficam para o fim da fila. */
    const val GIGANTE = 50_000_000_000L

    /** Acima disto o SHA-256 não é conferido no celular: reler o arquivo inteiro pelo USB levaria horas. */
    const val LIMITE_SHA = 4_000_000_000L

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
    /** Bytes já gravados dos arquivos que estão baixando agora (os totais só contam arquivo terminado). */
    val bytesAndando: Long = 0,
    /** O maior arquivo em andamento, para a tela mostrar que não travou. */
    val atual: String? = null,
    val atualFeito: Long = 0,
    val atualTam: Long = 0,
    /** Bytes por segundo nos últimos minutos. Negativo: ainda medindo. */
    val velocidade: Double = -1.0,
    /** Há quantos segundos não chega nada. */
    val paradoS: Long = 0,
) {
    val bytesFeitos get() = totais.bytesFeitos + bytesAndando
}

private class BancoMontagem(ctx: Context) : SQLiteOpenHelper(ctx, "montagem.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE arq(rel TEXT PRIMARY KEY, prio INTEGER, tipo TEXT, tam INTEGER, url TEXT,
               status TEXT NOT NULL DEFAULT 'pendente', tentativas INTEGER NOT NULL DEFAULT 0, erro TEXT, sha TEXT, reserva TEXT)"""
        )
        db.execSQL("CREATE INDEX arq_fila ON arq(status, prio, tam)")
        db.execSQL("CREATE TABLE meta(chave TEXT PRIMARY KEY, valor TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, a: Int, b: Int) {
        if (a < 2) {
            db.execSQL("ALTER TABLE arq ADD COLUMN sha TEXT")
            db.execSQL("ALTER TABLE arq ADD COLUMN reserva TEXT")
        }
    }

    /** Os motivos de falha mais comuns, para o relatório. */
    fun motivos(limite: Int): List<Pair<String, Int>> =
        readableDatabase.rawQuery("SELECT COALESCE(erro,''), COUNT(*) FROM arq WHERE status='falhou' GROUP BY 1 ORDER BY 2 DESC LIMIT $limite", null).use { c ->
            val r = ArrayList<Pair<String, Int>>()
            while (c.moveToNext()) r += c.getString(0) to c.getInt(1)
            r
        }

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
        // Os gigantes (220 GB, 127 GB) vão para o fim: sozinhos, seguravam a fila por dias.
        readableDatabase.rawQuery(
            "SELECT prio,tipo,rel,tam,url,sha,reserva FROM arq WHERE status='pendente'$filtro " +
                "ORDER BY (tam >= ${RegrasKit.GIGANTE}),prio,tam LIMIT $limite", args,
        ).use { c ->
            while (c.moveToNext()) {
                val rel = c.getString(2)
                if (rel !in excluir) {
                    return ArquivoKit(
                        c.getInt(0), c.getString(1), rel, c.getLong(3), c.getString(4),
                        c.getString(5).orEmpty(), c.getString(6).orEmpty(),
                    )
                }
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

    /** Quanto já chegou de cada arquivo em andamento: é o que deixa a tela honesta com arquivo grande. */
    private class Andando(val tam: Long, @Volatile var feito: Long)
    private val andando = ConcurrentHashMap<String, Andando>()
    private val recebidos = java.util.concurrent.atomic.AtomicLong()
    private val amostras = ArrayDeque<Pair<Long, Long>>()
    private var ultimoAvanco = 0L

    /** O Android recusou o serviço em primeiro plano: a tela explica, e abrir o app recomeça direito. */
    @Volatile var limitado: String? = null

    /** Chamado quando o app volta para a tela: é o que libera de novo o download em segundo plano. */
    fun aoAbrirApp() {
        if (limitado == null || !ativa || app.prefs.montagemPausada) return
        limitado = null
        app.diario.escrever("montagem: app aberto, recomeçando o download em primeiro plano")
        iniciar(substituir = true)
    }

    fun motivos(n: Int = 8) = db.motivos(n)
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
        val agora = android.os.SystemClock.elapsedRealtime()
        val total = recebidos.get()
        synchronized(amostras) {
            if (amostras.isEmpty() || total > amostras.last().second) ultimoAvanco = agora
            amostras.addLast(agora to total)
            while (amostras.size > 2 && agora - amostras.first().first > JANELA_MS) amostras.removeFirst()
        }
        val (t0, b0) = synchronized(amostras) { amostras.first() }
        val dt = (agora - t0) / 1000.0
        val maior = andando.entries.filter { it.value.tam > 200_000_000L }.maxByOrNull { it.value.tam }
        estado.value = estado.value.copy(
            ativa = true, etapa = t, aviso = aviso ?: limitado, totais = db.totais(),
            bytesAndando = andando.values.sumOf { it.feito },
            atual = maior?.key?.substringAfterLast('/'), atualFeito = maior?.value?.feito ?: 0, atualTam = maior?.value?.tam ?: 0,
            velocidade = if (dt >= 20 && andando.isNotEmpty()) (total - b0) / dt else -1.0,
            paradoS = if (andando.isEmpty()) 0 else (agora - ultimoAvanco) / 1000,
        )
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
                v.put("sha", i.sha); v.put("reserva", i.reserva)
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
                    andando.remove(item.rel)
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
        var jaRepetiu = false
        val meu = Andando(item.tam, saida.tamanho)
        andando[item.rel] = meu
        if (item.tam >= RegrasKit.GIGANTE) app.diario.escrever("montagem: começando arquivo gigante ${item.rel} (${bytesLegivel(item.tam)}), já tem ${bytesLegivel(meu.feito)}")
        while (true) {
            coroutineContext.ensureActive()
            try {
                val destino = if (relFinal == item.rel) saida else SaidaSaf(a, "$relFinal.part")
                val recebido = try {
                    transferidor.baixar(
                        url, destino,
                        aoChamar = { chamadas[item.rel] = it },
                        aoProgredir = { n ->
                            val d = n - meu.feito
                            if (d > 0) recebidos.addAndGet(d)
                            meu.feito = n
                        },
                    )
                } finally {
                    chamadas.remove(item.rel)
                }
                val relP = "$relFinal.part"
                val problema = conferir(a, relP, relFinal, recebido, item, relFinal != item.rel)
                if (problema != null) {
                    a.apagar(relP)
                    meu.feito = 0
                    app.diario.escrever("montagem: ${item.rel}: $problema")
                    // Veio estragado: baixa de novo uma vez antes de desistir.
                    if (!jaRepetiu) {
                        jaRepetiu = true
                        continue
                    }
                    db.marcar(item.rel, "falhou", problema)
                    return false
                }
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
                // O primeiro endereço recusou: tenta a reserva antes de qualquer outra coisa.
                if (item.reserva.isNotEmpty() && url == item.url && relFinal == item.rel) {
                    app.diario.escrever("montagem: ${item.rel}: erro ${e.codigo} no primeiro endereço; indo para a reserva")
                    url = item.reserva
                    tentativas = 0
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
                    if (item.reserva.isNotEmpty() && url == item.url && relFinal == item.rel) {
                        app.diario.escrever("montagem: ${item.rel}: o primeiro endereço não respondeu; indo para a reserva")
                        url = item.reserva
                        tentativas = 0
                        continue
                    }
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

    /** Null se o arquivo recebido está inteiro; senão, o que há de errado com ele. */
    private fun conferir(a: ArvoreSaf, relP: String, rel: String, recebido: Long, item: ArquivoKit, outraVersao: Boolean): String? {
        val ini = ByteArray(80)
        val ehZim = rel.lowercase().endsWith(".zim")
        val inteiro = a.ler(relP).use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { s ->
                var n = 0
                while (n < ini.size) {
                    val k = s.read(ini, n, ini.size - n)
                    if (k < 0) break
                    n += k
                }
                if (!RegrasKit.comecoCerto(rel, ini.copyOf(minOf(n, 8)))) return@use false
                // O .zim diz o próprio tamanho: é a conferência que vale para os gigantes.
                if (ehZim) return@use RegrasKit.tamanhoDoZim(ini.copyOf(n))?.let { it == recebido } ?: true
                if (!rel.lowercase().endsWith(".pdf")) return@use true
                Transferidor.pdfInteiro(s.channel)
            }
        }
        if (!inteiro) return "O arquivo veio cortado ou é outra coisa. Não foi guardado."
        // Instalador de "última versão" (sem SHA na lista) muda de tamanho no site: não é erro.
        val tamanhoLivre = item.sha.isEmpty() && item.rel.startsWith("6_Instaladores/")
        val tamanhoBom = outraVersao || tamanhoLivre || item.tam == 0L || Transferidor.tamanhoConfere(recebido, item.tam, item.tipo == "zim")
        if (!tamanhoBom) return "Veio com tamanho diferente do esperado."
        if (item.sha.isNotEmpty() && !outraVersao && recebido == item.tam && recebido <= RegrasKit.LIMITE_SHA) {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            a.ler(relP).use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { s ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val k = s.read(buf)
                        if (k < 0) break
                        md.update(buf, 0, k)
                    }
                }
            }
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            if (sha != item.sha) return "Veio corrompido (a conferência SHA-256 não bateu). Não foi guardado."
        }
        return null
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
        const val JANELA_MS = 300_000L
        const val ARQ_MANIFESTO = "_Baixador/instalador/manifesto.tsv.gz"
        const val URL_ULTIMO_KIT = "https://api.github.com/repos/brenobernardes25-afk/biblioteca-offline/releases/latest"
    }
}

class MontagemWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app = ctx.applicationContext as BolsoApp

    private companion object {
        const val PRAZO_TRAVA_MS = 30 * 60 * 1000L
    }

    /**
     * No Android 15 em diante, o serviço do tipo "dataSync" só pode rodar 6 horas por dia em segundo plano;
     * depois disso o download ficava reduzido a pedaços de 10 minutos (o caso do SSD que "parou" com 41 GB).
     * Gravar num SSD pelo USB é trabalho com aparelho conectado ("connectedDevice"), que não tem esse limite.
     */
    private var tipoServico = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
    private var emPrimeiroPlano = false
    private var ultimaTentativa = 0L

    private suspend fun primeiroPlano() {
        val agora = android.os.SystemClock.elapsedRealtime()
        if (emPrimeiroPlano) {
            try {
                setForeground(aviso())      // só atualiza o texto da notificação
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            return
        }
        if (ultimaTentativa != 0L && agora - ultimaTentativa < 60_000) return
        ultimaTentativa = agora
        val tipos = if (Build.VERSION.SDK_INT >= 35) {
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
        for (tipo in tipos) {
            tipoServico = tipo
            try {
                setForeground(aviso())
                emPrimeiroPlano = true
                app.montagem.limitado = null
                app.diario.escrever("montagem: em primeiro plano (tipo $tipo)")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.diario.escrever("montagem: primeiro plano (tipo $tipo) recusado: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        app.montagem.limitado = "O Android limitou o download em segundo plano. Abra o app e deixe esta tela aberta: ele recomeça sozinho."
    }

    private fun aviso(): ForegroundInfo {
        val e = app.montagem.estado.value
        val t = e.totais
        val pct = if (t.bytesTotal > 0) (e.bytesFeitos * 1000 / t.bytesTotal).toInt() else 0
        val texto = "${inteiro(t.prontos)} de ${inteiro(t.total)} · ${bytesLegivel(e.bytesFeitos)} de ${bytesLegivel(t.bytesTotal)}" +
            (e.atual?.let { " · agora: $it" } ?: "")
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
            ForegroundInfo(Montagem.ID_PROGRESSO, n, tipoServico)
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
            // Renovada a cada passo do relógio: com prazo fixo de 12 h, um arquivo de dias ficava sem a trava no meio.
            trava.acquire(PRAZO_TRAVA_MS)
            primeiroPlano()
            app.montagem.rodar {
                trava.acquire(PRAZO_TRAVA_MS)
                primeiroPlano()
            }
            Result.success()
        } catch (e: CancellationException) {
            // Fica no registro por que o Android parou o trabalho (ex.: -128 = prazo do serviço em primeiro plano).
            if (Build.VERSION.SDK_INT >= 31) app.diario.escrever("montagem: trabalho interrompido (motivo do Android: $stopReason)")
            throw e
        } catch (e: Exception) {
            app.diario.escrever("montagem: nova tentativa depois de: $e")
            Result.retry()
        } finally {
            withContext(NonCancellable) { if (trava.isHeld) trava.release() }
        }
    }
}
