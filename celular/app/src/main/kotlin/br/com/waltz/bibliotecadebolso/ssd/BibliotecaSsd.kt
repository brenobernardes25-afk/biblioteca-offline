package br.com.waltz.bibliotecadebolso.ssd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import androidx.core.content.ContextCompat
import br.com.waltz.bibliotecadebolso.BolsoApp
import br.com.waltz.bibliotecadebolso.download.Armazenamento
import br.com.waltz.bibliotecadebolso.util.bytesLegivel
import br.com.waltz.bibliotecadebolso.zim.BibliotecaZim
import br.com.waltz.bibliotecadebolso.zim.LivroZim
import br.com.waltz.bibliotecadebolso.zim.Origem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.kiwix.libzim.Archive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

enum class SituacaoSsd { NENHUM, DESCONECTADO, ABRINDO, CONECTADO }
enum class SituacaoIndiceSsd { AUSENTE, COPIANDO, PRONTO, SEM_ESPACO, FALHOU }

data class EstadoSsd(
    val situacao: SituacaoSsd = SituacaoSsd.NENHUM,
    val zimsAbertos: Int = 0,
    val zimsTotal: Int = 0,
    val zimsRuins: Int = 0,
    val indice: SituacaoIndiceSsd = SituacaoIndiceSsd.AUSENTE,
    val indiceFeito: Long = 0,
    val indiceTotal: Long = 0,
    val aviso: String? = null,
) {
    val conectado get() = situacao == SituacaoSsd.ABRINDO || situacao == SituacaoSsd.CONECTADO
}

/**
 * A biblioteca do PC, lida direto do SSD (seção 6.1). Lê sem copiar, salvo o índice de busca: SQLite em
 * cima da SAF é lento e pouco confiável. Só pede permissão de leitura: não tem como estragar nada no SSD.
 */
class BibliotecaSsd(private val ctx: Context, private val app: BolsoApp) {
    val estado = MutableStateFlow(EstadoSsd())
    private val escopo = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var abrindo: Job? = null
    private var copiando: Job? = null
    @Volatile private var arvore: ArvoreSaf? = null
    private var iniciado = false

    private val pastaBase get() = File(Armazenamento.pasta(ctx), "ssd")

    /**
     * Uma cópia do índice para cada disco (o HD de 1 TB, o pen drive…): trocar de um para o outro não copia tudo
     * de novo. A pasta sai do endereço do disco que a pessoa escolheu.
     */
    private val pastaIndice: File
        get() = app.prefs.ssdUri?.let { File(pastaBase, "disco_" + Integer.toHexString(it.hashCode())) } ?: pastaBase

    private fun assinaturaDe(pasta: File, nome: String = ARQ_ASSINATURA): String? =
        File(pasta, nome).takeIf { it.isFile }?.readText()

    private fun gravarAssinatura(pasta: File, valor: String, nome: String = ARQ_ASSINATURA) =
        File(pasta, nome).writeText(valor)

    private val aoMudarDisco = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Tirar um pendrive qualquer não pode dar aviso de SSD desconectado.
            val volume = i.data?.path?.substringAfterLast('/') ?: return
            val ehLeitura = volumeDe(app.prefs.ssdUri) == volume
            val ehMontagem = volumeDe(app.prefs.montagemUri) == volume
            if (!ehLeitura && !ehMontagem) return
            if (i.action == Intent.ACTION_MEDIA_MOUNTED) {
                if (ehLeitura) reconectar()
                if (ehMontagem) app.montagem.iniciar()
            } else {
                if (ehMontagem) app.montagem.aoSairSsd()
                if (ehLeitura && estado.value.situacao != SituacaoSsd.DESCONECTADO) desconectar()
            }
        }
    }

    private fun volumeDe(uri: String?): String? = try {
        uri?.let { android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(it)).substringBefore(':') }
    } catch (_: Exception) {
        null
    }

    fun iniciar() {
        if (iniciado) return
        iniciado = true
        val filtro = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(ctx, aoMudarDisco, filtro, ContextCompat.RECEIVER_NOT_EXPORTED)
        reconectar()
    }

    /** Volta sozinho, sem pedir a pasta de novo (critério 10). */
    fun reconectar() {
        val uri = app.prefs.ssdUri?.let(Uri::parse) ?: return
        if (estado.value.conectado) return
        abrir(uri)
    }

    /** A pessoa escolheu a pasta. Só guarda se for mesmo a biblioteca. */
    fun escolher(uri: Uri): String? {
        val a = ArvoreSaf(ctx, uri)
        val nomes = try {
            a.listar().map { it.nome }.toSet()
        } catch (e: Exception) {
            app.diario.escrever("SSD: não li a pasta escolhida: $e")
            return "Não consegui ler essa pasta. Confira o cabo e tente de novo."
        }
        if (!validaBiblioteca(nomes)) {
            return "Essa pasta não tem a biblioteca: faltam as pastas 1_Enciclopedia e 5_Acervo. Escolha a pasta principal do SSD."
        }
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        desconectar(esquecer = true)
        app.prefs.ssdUri = uri.toString()
        app.prefs.assistenteConcluido = true
        abrir(uri)
        return null
    }

    fun esquecer() {
        val uri = app.prefs.ssdUri?.let(Uri::parse)
        desconectar(esquecer = true)
        app.prefs.ssdUri = null
        // A montagem usa a mesma permissão: enquanto ela existir, não pode soltar.
        if (uri != null && app.prefs.montagemUri != uri.toString()) {
            try {
                ctx.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        app.indicePdf.fecharCompleto()
        // A cópia do índice deste disco fica guardada: escolher o mesmo disco de novo não copia tudo outra vez.
        estado.value = EstadoSsd()
    }

    private fun abrir(uri: Uri) {
        estado.value = estado.value.copy(situacao = SituacaoSsd.ABRINDO, zimsAbertos = 0, zimsRuins = 0, aviso = null)
        val a = ArvoreSaf(ctx, uri)
        abrindo = escopo.launch {
            val raiz = try {
                a.listar().map { it.nome }.toSet()
            } catch (_: Exception) {
                estado.value = estado.value.copy(situacao = SituacaoSsd.DESCONECTADO)
                return@launch
            }
            val emMontagem = app.prefs.montagemUri == uri.toString() && "5_Acervo" in raiz
            if (!validaBiblioteca(raiz) && !emMontagem) {
                estado.value = estado.value.copy(
                    situacao = SituacaoSsd.DESCONECTADO,
                    aviso = "A pasta da biblioteca não está mais neste SSD.",
                )
                return@launch
            }
            arvore = a
            app.indicePdf.completoDisponivel = true
            copiando = launch { prepararIndice(a) }
            try {
                abrirZims(a)
                estado.value = estado.value.copy(situacao = SituacaoSsd.CONECTADO)
            } catch (e: IOException) {
                app.diario.escrever("SSD saiu enquanto abria: $e")
                desconectar()
            }
        }
    }

    /**
     * Abre as enciclopédias do pen drive. É preciso a cada vez que ele é ligado (o Android não deixa um arquivo de
     * disco removível aberto de uma vez para outra), mas dá para ser rápido: três ao mesmo tempo, e o título e o
     * idioma de cada uma ficam guardados no celular (ler isso do .zim pelo cabo USB era a parte mais lenta).
     */
    private suspend fun abrirZims(a: ArvoreSaf) {
        app.zim.garantirNativo()
        if (a.info(PASTA_ZIM) == null) return // Montagem no começo: ainda não chegou nenhuma enciclopédia.
        val noCelular = app.banco.zimsDisponiveis().mapNotNull { it.kiwixNome }
        val nomes = a.listar(PASTA_ZIM).filter { !it.pasta && it.tam > 0 && it.nome.endsWith(".zim", ignoreCase = true) }
            .filter { app.zim.livro(idDe(it.nome)) == null }
            .filter { z -> noCelular.none { z.nome.startsWith(it + "_") } } // O do celular é mais rápido.
            .sortedWith(compareBy({ !it.nome.contains("_pt") }, { it.tam }))
        val abertos = java.util.concurrent.atomic.AtomicInteger(estado.value.zimsAbertos)
        val ruins = java.util.concurrent.atomic.AtomicInteger(0)
        estado.value = estado.value.copy(zimsTotal = abertos.get() + nomes.size)
        val guardados = ctx.getSharedPreferences("zims_do_pendrive", android.content.Context.MODE_PRIVATE)
        val t0 = System.currentTimeMillis()
        val vagas = kotlinx.coroutines.sync.Semaphore(3)
        kotlinx.coroutines.coroutineScope {
            nomes.map { z ->
                async(Dispatchers.IO) {
                    vagas.withPermit {
                        coroutineContext.ensureActive()
                        var pfd: ParcelFileDescriptor? = null
                        try {
                            pfd = a.ler("$PASTA_ZIM/${z.nome}")
                            val arquivo = Archive(pfd.fileDescriptor)
                            arquivo.setClusterCacheMaxSize(CACHE_POR_ZIM)
                            val chave = "${z.nome}:${z.tam}"
                            val salvo = guardados.getString(chave, null)?.split('\t')?.takeIf { it.size == 2 }
                            val titulo = salvo?.get(0) ?: (
                                try {
                                    arquivo.getMetadata("Title").takeIf { it.isNotBlank() }
                                } catch (_: Exception) {
                                    null
                                } ?: z.nome.removeSuffix(".zim").replace('_', ' ')
                                )
                            val idioma = salvo?.get(1) ?: BibliotecaZim.idiomaDe(arquivo, z.nome)
                            if (salvo == null) guardados.edit().putString(chave, "$titulo\t$idioma").apply()
                            app.zim.registrarExtra(
                                LivroZim(idDe(z.nome), titulo, "Enciclopédia", idioma, Origem.SSD, arquivo, pfd),
                            )
                            abertos.incrementAndGet()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            pfd?.close()
                            throw e
                        } catch (e: Exception) {
                            pfd?.close()
                            ruins.incrementAndGet()
                            app.diario.escrever("SSD: não abriu ${z.nome}: ${e.message}")
                            if (a.info(PASTA_ZIM) == null) throw IOException("SSD desconectado")
                        }
                        estado.value = estado.value.copy(zimsAbertos = abertos.get(), zimsRuins = ruins.get())
                    }
                }
            }.awaitAll()
        }
        app.diario.escrever("SSD: ${abertos.get()} enciclopédias abertas em ${(System.currentTimeMillis() - t0) / 1000} s")
    }

    private suspend fun prepararIndice(a: ArvoreSaf) {
        val origem = a.info(ARQ_ORIGEM)
        val pasta = pastaIndice.apply { mkdirs() }
        val final = File(pasta, ARQ_INDICE)
        if (origem == null) {
            // SSD montado pelo celular, ainda sem índice. Se já havia uma cópia, ela segue servindo.
            app.indicePdf.abrirCompleto(final.takeIf { it.exists() })
            estado.value = estado.value.copy(indice = if (final.exists()) SituacaoIndiceSsd.PRONTO else SituacaoIndiceSsd.AUSENTE)
            return
        }
        // Da versão antiga, que guardava um índice só: ele passa a ser o deste disco, se for deste disco (tamanho).
        val antigo = File(pastaBase, ARQ_INDICE)
        if (!final.exists() && antigo.isFile &&
            kotlin.math.abs(origem.tam - antigo.length()) <= antigo.length() * MUDANCA_PEQUENA
        ) {
            app.indicePdf.fecharCompleto()
            for (n in listOf(ARQ_INDICE, "$ARQ_INDICE-wal", "$ARQ_INDICE-shm")) {
                File(pastaBase, n).takeIf { it.exists() }?.renameTo(File(pasta, n))
            }
            (app.prefs.ssdAssinaturaIndice ?: "migrado").let { gravarAssinatura(pasta, it) }
            app.diario.escrever("SSD: índice da versão antiga passou para a pasta deste disco")
        }
        val wal = a.info("$ARQ_ORIGEM-wal")?.takeIf { it.tam > 0 }
        val assinatura = "${origem.tam}:${origem.modificado}:${wal?.tam ?: 0}"
        if (final.exists() && assinaturaDe(pasta) == assinatura) {
            app.indicePdf.abrirCompleto(final)
            estado.value = estado.value.copy(indice = SituacaoIndiceSsd.PRONTO)
            return
        }
        // O leitor do PC mexe no índice a cada uso (lê os escaneados aos poucos e acrescenta páginas). Copiar
        // 3,8 GB de novo por causa de algumas páginas faria a pessoa esperar minutos toda vez que liga o pen drive.
        // Com uma cópia já no celular, só copia de novo quando o índice mudou bastante (mais de 3% do tamanho).
        if (final.exists() && assinaturaDe(pasta) != null &&
            kotlin.math.abs(origem.tam - final.length()) <= final.length() * MUDANCA_PEQUENA
        ) {
            app.indicePdf.abrirCompleto(final)
            estado.value = estado.value.copy(indice = SituacaoIndiceSsd.PRONTO)
            app.diario.escrever(
                "SSD: índice do pen drive mudou pouco (${bytesLegivel(origem.tam)} contra ${bytesLegivel(final.length())}); " +
                    "segue a cópia do celular",
            )
            return
        }
        app.indicePdf.abrirCompleto(final.takeIf { it.exists() }) // O antigo serve enquanto o novo não chega.
        val total = origem.tam + (wal?.tam ?: 0)
        val parcial = File(pasta, "$ARQ_INDICE.parcial")
        // Se o índice do SSD mudou no meio da cópia, o que veio antes não vale mais.
        if (assinaturaDe(pasta, ARQ_ASSINATURA_PARCIAL) != assinatura) parcial.delete()
        gravarAssinatura(pasta, assinatura, ARQ_ASSINATURA_PARCIAL)
        val livre = StatFs(pasta.path).availableBytes
        if (livre < total - parcial.length() + Armazenamento.RESERVA) {
            app.diario.escrever("SSD: índice de ${bytesLegivel(total)} não cabe; livres ${bytesLegivel(livre)}")
            estado.value = estado.value.copy(indice = SituacaoIndiceSsd.SEM_ESPACO, indiceTotal = total)
            return
        }
        estado.value = estado.value.copy(indice = SituacaoIndiceSsd.COPIANDO, indiceFeito = parcial.length(), indiceTotal = total)
        try {
            copiar(a, ARQ_ORIGEM, parcial, retomar = true) { feito ->
                estado.value = estado.value.copy(indiceFeito = feito)
            }
            val parcialWal = File(pasta, "$ARQ_INDICE-wal.parcial")
            if (wal != null) copiar(a, "$ARQ_ORIGEM-wal", parcialWal, retomar = false) {}
            app.indicePdf.fecharCompleto()
            final.delete()
            File(pasta, "$ARQ_INDICE-wal").delete()
            File(pasta, "$ARQ_INDICE-shm").delete()
            if (!parcial.renameTo(final)) throw IOException("não consegui gravar o índice")
            if (wal != null) parcialWal.renameTo(File(pasta, "$ARQ_INDICE-wal"))
            gravarAssinatura(pasta, assinatura)
            app.indicePdf.abrirCompleto(final)
            estado.value = estado.value.copy(indice = SituacaoIndiceSsd.PRONTO, indiceFeito = total)
            app.diario.escrever("SSD: índice copiado (${bytesLegivel(total)})")
        } catch (e: IOException) {
            app.diario.escrever("SSD: cópia do índice parou: $e")
            estado.value = estado.value.copy(indice = SituacaoIndiceSsd.FALHOU)
        }
    }

    private suspend fun copiar(a: ArvoreSaf, rel: String, para: File, retomar: Boolean, aoAndar: (Long) -> Unit) {
        val antes = if (retomar) para.length() else 0L
        a.ler(rel).use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { ent ->
                ent.channel.position(antes)
                FileOutputStream(para, antes > 0).use { sai ->
                    val b = ByteArray(1 shl 20)
                    var feito = antes
                    var conta = 0
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = ent.read(b)
                        if (n < 0) break
                        sai.write(b, 0, n)
                        feito += n
                        if (++conta % 8 == 0) aoAndar(feito) // A cada 8 MB: num USB lento, a barra não parece parada.
                    }
                }
            }
        }
    }

    /** A montagem chamou: chegou uma enciclopédia nova, ou a biblioteca passou a existir. */
    fun aoChegarArquivo(uri: Uri) {
        if (app.prefs.ssdUri == null) {
            val nomes = try {
                ArvoreSaf(ctx, uri).listar().map { it.nome }.toSet()
            } catch (_: Exception) {
                return
            }
            if ("5_Acervo" !in nomes) return
            app.prefs.ssdUri = uri.toString()
        }
        val a = arvore
        if (a == null) {
            reconectar()
        } else if (estado.value.situacao == SituacaoSsd.CONECTADO) {
            escopo.launch {
                try {
                    abrirZims(a)
                } catch (e: IOException) {
                    app.diario.escrever("SSD: não abri a enciclopédia nova: $e")
                }
            }
        }
    }

    /** Fecha tudo depressa: o Android encerra quem segura arquivo aberto num disco que está saindo. */
    fun desconectar(esquecer: Boolean = false) {
        abrindo?.cancel()
        copiando?.cancel()
        arvore = null
        app.mapa.esquecer()
        app.zim.removerExtras()
        app.indicePdf.completoDisponivel = false
        val tinha = app.prefs.ssdUri != null && !esquecer
        estado.value = estado.value.copy(
            situacao = if (tinha) SituacaoSsd.DESCONECTADO else SituacaoSsd.NENHUM,
            zimsAbertos = 0,
            aviso = null,
        )
    }

    /** Os .gguf da pasta 4_IA. O motor lê direto do SSD, sem copiar para o celular. */
    fun modelosIa(): List<String> {
        val a = arvore ?: return emptyList()
        return try {
            a.listar("4_IA").filter { it.nome.endsWith(".gguf") && it.tam > 0 }.map { "4_IA/${it.nome}" }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Para o leitor de PDF. Null se o SSD saiu. */
    fun abrirDocumento(rel: String): ParcelFileDescriptor? = try {
        arvore?.ler(rel)
    } catch (e: Exception) {
        app.diario.escrever("SSD: não abriu $rel: ${e.message}")
        null
    }

    fun uriDocumento(rel: String): Uri? = arvore?.uri(rel)

    /** O mapa do Brasil do app (ServidorMapa) lê o pen drive por aqui, sem copiar nada para o celular. */
    val fonteMapa = object : br.com.waltz.bibliotecadebolso.mapa.FonteMapa {
        override fun ler(rel: String): ByteArray? {
            if (arvore?.info(rel) == null) return null // fonte de letra que não existe: sem encher o diário
            return abrirDocumento(rel)?.use { p -> FileInputStream(p.fileDescriptor).readBytes() }
        }

        override fun abrirCanal(rel: String): br.com.waltz.bibliotecadebolso.mapa.CanalArquivo? {
            val p = abrirDocumento(rel) ?: return null
            val ent = FileInputStream(p.fileDescriptor)
            return br.com.waltz.bibliotecadebolso.mapa.CanalArquivo(ent.channel) {
                ent.close()
                p.close()
            }
        }
    }

    fun tamanho(rel: String): Long? = arvore?.info(rel)?.tam

    /** Copia do pen drive para o celular, continuando de onde parou. Parar (cancelar) guarda o que já veio. */
    suspend fun copiarRetomando(rel: String, para: File, aoAndar: (Long) -> Unit): Boolean {
        val a = arvore ?: return false
        return try {
            copiar(a, rel, para, retomar = true, aoAndar = aoAndar)
            true
        } catch (e: IOException) {
            app.diario.escrever("SSD: cópia de $rel parou: ${e.message}")
            false
        }
    }

    /** O instalador do Organic Maps que vem no pen drive (6_Instaladores). Null se não houver. */
    fun apkOrganicMaps(): String? {
        val a = arvore ?: return null
        return try {
            a.listar(PASTA_INSTALADORES).firstOrNull {
                !it.pasta && it.tam > 0 && it.nome.startsWith("OrganicMaps", ignoreCase = true) &&
                    it.nome.endsWith(".apk", ignoreCase = true)
            }?.let { "$PASTA_INSTALADORES/${it.nome}" }
        } catch (_: Exception) {
            null
        }
    }

    /** Copia um arquivo do pen drive para dentro do celular (o instalador, para o Android instalar). */
    fun copiarParaCelular(rel: String, para: File): Boolean = try {
        para.parentFile?.mkdirs()
        val pfd = abrirDocumento(rel) ?: throw IOException("o pen drive saiu")
        pfd.use { p ->
            FileInputStream(p.fileDescriptor).use { e -> FileOutputStream(para).use { s -> e.copyTo(s, 1 shl 20) } }
        }
        true
    } catch (e: Exception) {
        app.diario.escrever("SSD: não copiei $rel: ${e.message}")
        para.delete()
        false
    }

    companion object {
        private const val PASTA_ZIM = "1_Enciclopedia"
        private const val PASTA_INSTALADORES = "6_Instaladores"
        private const val MUDANCA_PEQUENA = 0.03 // até 3% de diferença: a cópia do celular continua valendo
        private const val ARQ_ORIGEM = "App/dados/indice.db"
        const val ARQ_INDICE = "indice.db"
        private const val ARQ_ASSINATURA = "assinatura.txt"
        private const val ARQ_ASSINATURA_PARCIAL = "assinatura_parcial.txt"
        private const val CACHE_POR_ZIM = 16_000_000L // São até 184 arquivos abertos ao mesmo tempo.

        private fun idDe(arquivo: String) = "ssd-" + BibliotecaZim.chaveDe(arquivo.removeSuffix(".zim"))

        fun validaBiblioteca(nomes: Set<String>) = "1_Enciclopedia" in nomes && "5_Acervo" in nomes
    }
}
