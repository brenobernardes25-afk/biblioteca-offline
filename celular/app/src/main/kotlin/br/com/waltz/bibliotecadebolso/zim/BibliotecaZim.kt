package br.com.waltz.bibliotecadebolso.zim

import android.app.ActivityManager
import android.content.Context
import android.os.ParcelFileDescriptor
import br.com.waltz.bibliotecadebolso.busca.Consulta
import br.com.waltz.bibliotecadebolso.dados.Banco
import br.com.waltz.bibliotecadebolso.download.Armazenamento
import br.com.waltz.bibliotecadebolso.util.Diario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.kiwix.libkiwix.JNIKiwix
import org.kiwix.libzim.Archive
import org.kiwix.libzim.Query
import org.kiwix.libzim.Searcher
import org.kiwix.libzim.SuggestionSearcher
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class Origem(val rotulo: String) { CELULAR("no celular"), SSD("no SSD") }

/** Uma fonte aberta. Mantém o descritor vivo enquanto o Archive existir (caso do SSD). */
class LivroZim(
    val id: String,
    val titulo: String,
    val tema: String,
    val idioma: String,
    val origem: Origem,
    val archive: Archive,
    val fd: ParcelFileDescriptor? = null,
) {
    /** Busca do Xapian não pode rodar duas vezes ao mesmo tempo no mesmo arquivo. */
    val trava = Mutex()
    private val preguicaBuscador = lazy {
        try {
            if (archive.hasFulltextIndex()) Searcher(archive) else null
        } catch (_: Exception) {
            null
        }
    }
    private val preguicaSugestor = lazy {
        try {
            SuggestionSearcher(archive)
        } catch (_: Exception) {
            null
        }
    }
    val buscador: Searcher? by preguicaBuscador
    val sugestor: SuggestionSearcher? by preguicaSugestor

    /** Solta tudo que segura o arquivo: o gancho do descritor abre uma cópia dele dentro da libzim. */
    fun fechar() {
        try {
            if (preguicaBuscador.isInitialized()) buscador?.dispose()
            if (preguicaSugestor.isInitialized()) sugestor?.dispose()
            archive.dispose()
        } catch (_: Exception) {
        }
        try {
            fd?.close()
        } catch (_: Exception) {
        }
    }
}

data class ResultadoZim(
    val livroId: String,
    val livro: String,
    val tema: String,
    val idioma: String,
    val origem: Origem,
    val caminho: String,
    val titulo: String,
    val trecho: String?,
    val pontos: Int,
)

data class EstadoBusca(
    val resultados: List<ResultadoZim> = emptyList(),
    val fontesTotal: Int = 0,
    val fontesFeitas: Int = 0,
    val lentas: List<String> = emptyList(),
    val terminou: Boolean = false,
)

class BibliotecaZim(
    private val ctx: Context,
    private val banco: Banco,
    private val diario: Diario,
) {
    private val alterado = AtomicBoolean(true)
    private val abertos = ConcurrentHashMap<String, LivroZim>()
    private val extras = ConcurrentHashMap<String, LivroZim>()
    private val travaAbrir = Mutex()
    private val fundo = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val vagasSsd = Semaphore(VAGAS_SSD) // Pelo USB, muitos ao mesmo tempo só atrapalha.
    private val vagasCelular = Semaphore(64)

    /** Carrega as bibliotecas nativas uma vez. */
    private val nativo by lazy { JNIKiwix(ctx.applicationContext) }

    /** Limite de memória: 4 GB de RAM têm que dar conta (seção 3). */
    private val cacheClusters: Long by lazy {
        val mem = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mem)
        if (mem.totalMem < 5_000_000_000L) 48_000_000L else 128_000_000L
    }

    fun garantirNativo() {
        nativo
        if (GanchoFd.pontos == 0) diario.escrever("gancho do descritor não instalado")
    }

    fun marcarAlterado() = alterado.set(true)

    fun livro(id: String): LivroZim? = abertos[id] ?: extras[id]

    /** Fonte aberta de fora (SSD), com sua própria origem. */
    fun registrarExtra(l: LivroZim) {
        extras.put(l.id, l)?.let { velho -> fundo.launch { velho.trava.withLock { velho.fechar() } } }
    }

    /** SSD saindo: ninguém novo usa, e cada um fecha assim que terminar a busca que estiver fazendo. */
    fun removerExtras() {
        val saindo = extras.values.toList()
        extras.clear()
        saindo.forEach { l -> fundo.launch { l.trava.withLock { l.fechar() } } }
    }

    /** Abre o que estiver pronto. Arquivo ruim ou sumido não derruba nada. */
    suspend fun livros(): List<LivroZim> = withContext(Dispatchers.IO) {
        travaAbrir.withLock {
            if (alterado.getAndSet(false)) sincronizar()
        }
        (abertos.values + extras.values).sortedBy { it.titulo }
    }

    private fun sincronizar() {
        garantirNativo()
        val pasta = Armazenamento.pasta(ctx)
        val disponiveis = banco.zimsDisponiveis()
        val idsQuerem = HashSet<String>()
        for (item in disponiveis) {
            val pronto = item.status == "pronto"
            val rel = if (pronto) item.destinoEfetivo else item.destinoAntigo ?: continue
            val arq = File(pasta, rel)
            val chave = chaveDe(item.id)
            if (!arq.exists()) {
                if (pronto) {
                    diario.escrever("arquivo sumiu, volta para a fila: $rel")
                    banco.voltarParaFila(item.id)
                }
                continue
            }
            idsQuerem += chave
            val atual = abertos[chave]
            if (atual != null && atual.archive.filename == arq.path) continue
            try {
                val a = Archive(arq.path)
                a.setClusterCacheMaxSize(cacheClusters)
                abertos[chave] = LivroZim(chave, item.titulo, item.tema, idiomaDe(a, item.kiwixNome), Origem.CELULAR, a)
            } catch (e: Exception) {
                diario.escrever("não abriu $rel: ${e.javaClass.simpleName} ${e.message}")
                if (pronto) banco.falhou(item.id, "O arquivo baixado está com defeito. Toque em Tentar de novo.")
                arq.takeIf { pronto }?.delete()
            }
        }
        abertos.keys.filter { it !in idsQuerem }.forEach { abertos.remove(it) }
    }

    /**
     * Busca em cada .zim em paralelo. Os resultados aparecem conforme chegam, então um arquivo lento
     * não segura os outros. Passado o limite, a tela segue sem ele e avisa quais fontes ficaram de fora.
     * A JNI não pode ser interrompida: a chamada termina em segundo plano e deixa o cache quente.
     */
    fun buscar(
        consultasPt: List<String>,
        consultasEn: List<String>,
        incluirIngles: Boolean,
        limitePorArquivoMs: Long = LIMITE_BUSCA_MS,
    ): Flow<EstadoBusca> = channelFlow {
        // Primeiro o que está no celular, depois o português: se o tempo acabar, fica de fora o menos útil.
        val fontes = livros().filter { incluirIngles || it.idioma != "EN" }
            .sortedWith(compareBy({ it.origem != Origem.CELULAR }, { it.idioma != "PT" }))
        val feitos = ConcurrentHashMap<String, List<ResultadoZim>>()
        val lentas = ConcurrentHashMap.newKeySet<String>()
        val trava = Mutex()
        send(EstadoBusca(fontesTotal = fontes.size, terminou = fontes.isEmpty()))
        var pendentes = fontes.size
        for (livro in fontes) {
            launch {
                val trabalho = fundo.async {
                    (if (livro.origem == Origem.SSD) vagasSsd else vagasCelular).withPermit { livro.trava.withLock {
                        val consultas = if (livro.idioma == "EN") consultasEn else consultasPt
                        val inicio = System.currentTimeMillis()
                        val r = buscarEm(livro, consultas)
                        val ms = System.currentTimeMillis() - inicio
                        if (ms > 1000) diario.escrever("busca lenta: ${livro.titulo} levou $ms ms")
                        r
                    } }
                }
                val limiteDesta = if (livro.origem == Origem.SSD) LIMITE_BUSCA_SSD_MS else limitePorArquivoMs
                val r = withTimeoutOrNull(limiteDesta) { trabalho.await() }
                if (r == null) trabalho.cancel() // Quem ainda nem começou não precisa mais rodar.
                trava.withLock {
                    if (r == null) lentas += livro.titulo else feitos[livro.id] = r
                    pendentes--
                    send(
                        EstadoBusca(
                            resultados = feitos.values.flatten().sortedByDescending { it.pontos },
                            fontesTotal = fontes.size,
                            fontesFeitas = fontes.size - pendentes,
                            lentas = lentas.toList(),
                            terminou = pendentes == 0,
                        )
                    )
                    if (pendentes == 0) close()
                }
            }
        }
        if (fontes.isEmpty()) close()
        awaitClose { }
    }

    /**
     * Deixa os índices prontos em segundo plano ao abrir o app. Sem isso, a primeira busca
     * paga o tempo de abrir o índice de cada arquivo de uma vez só.
     */
    suspend fun aquecer() = withContext(Dispatchers.IO) {
        val inicio = System.currentTimeMillis()
        val lista = livros().filter { it.origem == Origem.CELULAR } // Aquecer 184 arquivos pelo USB travaria tudo.
        lista.forEach { l ->
            try {
                l.trava.withLock {
                    l.sugestor
                    l.buscador?.let { s ->
                        val q = Query("a")
                        val b = s.search(q)
                        b.getResults(0, 1).dispose()
                        b.dispose()
                        q.dispose()
                    }
                }
            } catch (e: Exception) {
                diario.escrever("aquecer ${l.titulo}: ${e.message}")
            }
        }
        diario.escrever("índices aquecidos: ${lista.size} fontes em ${System.currentTimeMillis() - inicio} ms")
    }

    private fun buscarEm(l: LivroZim, consultas: List<String>): List<ResultadoZim> {
        val porCaminho = LinkedHashMap<String, ResultadoZim>()
        val por = { caminho: String, titulo: String, trecho: String?, pontos: Int ->
            val velho = porCaminho[caminho]
            porCaminho[caminho] = ResultadoZim(
                l.id, l.titulo, l.tema, l.idioma, l.origem, caminho,
                velho?.titulo ?: titulo, trecho ?: velho?.trecho, maxOf(pontos, velho?.pontos ?: 0),
            )
        }
        for (c in consultas) {
            if (c.isBlank()) continue
            try {
                sugestoes(l, c, por)
                val achou = textoCompleto(l, c, por)
                if (achou == 0) Consulta.comOu(c)?.let { textoCompleto(l, it, por) }
            } catch (e: Exception) {
                diario.escrever("busca falhou em ${l.titulo}: ${e.javaClass.simpleName} ${e.message}")
            }
        }
        return porCaminho.values.sortedByDescending { it.pontos }.take(POR_FONTE)
    }

    private fun sugestoes(l: LivroZim, c: String, por: (String, String, String?, Int) -> Unit) {
        val s = l.sugestor ?: return
        val busca = s.suggest(c)
        val it = busca.getResults(0, 3)
        var i = 0
        while (it.hasNext()) {
            val item = it.next()
            val exato = Consulta.semAcento(item.title).equals(c, ignoreCase = true)
            por(item.path, item.title, null, if (exato) 110 else 92 - i * 4)
            i++
        }
        it.dispose()
        busca.dispose()
    }

    private fun textoCompleto(l: LivroZim, c: String, por: (String, String, String?, Int) -> Unit): Int {
        val s = l.buscador ?: return 0
        val q = Query(c)
        val busca = s.search(q)
        val it = busca.getResults(0, POR_FONTE)
        var n = 0
        while (it.hasNext()) {
            // next() devolve o atual e já avança: os getters têm que vir antes dele.
            por(it.path, it.title, it.snippet?.takeIf { t -> t.isNotBlank() }, it.score)
            it.next()
            n++
        }
        it.dispose()
        busca.dispose()
        q.dispose()
        return n
    }

    companion object {
        const val POR_FONTE = 6
        const val LIMITE_BUSCA_MS = 20_000L
        const val LIMITE_BUSCA_SSD_MS = 10_000L
        const val VAGAS_SSD = 6

        /** Serve de pedaço de URL: sem barra nem caractere estranho. */
        fun chaveDe(id: String) = id.removePrefix("zim:").replace(Regex("[^A-Za-z0-9._-]"), "_")

        fun idiomaDe(a: Archive, kiwixNome: String?): String {
            val idioma = try {
                a.mainLanguage
            } catch (_: Exception) {
                null
            }
            return when {
                idioma?.startsWith("por") == true || idioma == "pt" -> "PT"
                idioma?.startsWith("eng") == true || idioma == "en" -> "EN"
                kiwixNome?.contains("_pt") == true -> "PT"
                kiwixNome?.contains("_en") == true -> "EN"
                else -> idioma?.take(3)?.uppercase() ?: "?"
            }
        }
    }
}
