package br.com.waltz.bibliotecadebolso.ia

import android.app.ActivityManager
import android.content.Context
import br.com.waltz.bibliotecadebolso.BolsoApp
import br.com.waltz.bibliotecadebolso.busca.Consulta
import br.com.waltz.bibliotecadebolso.dados.Status
import br.com.waltz.bibliotecadebolso.download.Armazenamento
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.kiwix.libzim.Query
import java.io.ByteArrayOutputStream
import java.io.File

/** A ponte com o llama.cpp. Em aparelho de 32 bits, a biblioteca nativa nem existe: a IA simplesmente não aparece. */
class MotorIa {
    private val nativoCarregado: Boolean = try {
        System.loadLibrary("bolsoia")
        nativoIniciar()
        true
    } catch (_: Throwable) {
        false
    }
    private var aoReceber: ((ByteArray) -> Unit)? = null

    val serve: Boolean get() = nativoCarregado && nativoServe()

    /** Chamado pela parte nativa. O nome e a assinatura não podem mudar. */
    @Suppress("unused")
    fun aoPedaco(b: ByteArray) {
        aoReceber?.invoke(b)
    }

    fun carregarCaminho(caminho: String, nucleos: Int) = nativoCarregarCaminho(caminho, N_CTX, nucleos)
    fun carregarDescritor(fd: Int, nucleos: Int) = nativoCarregarDescritor(fd, N_CTX, nucleos)
    fun liberar(h: Long) = nativoLiberar(h)
    fun parar(h: Long) = nativoParar(h)

    fun gerar(h: Long, prompt: String, aoTexto: (ByteArray) -> Unit): Int {
        aoReceber = aoTexto
        return try {
            nativoGerar(h, prompt, MAX_TOKENS, 0.3f)
        } finally {
            aoReceber = null
        }
    }

    private external fun nativoIniciar()
    private external fun nativoServe(): Boolean
    private external fun nativoCarregarCaminho(caminho: String, nCtx: Int, nucleos: Int): Long
    private external fun nativoCarregarDescritor(fd: Int, nCtx: Int, nucleos: Int): Long
    private external fun nativoLiberar(h: Long)
    private external fun nativoParar(h: Long)
    private external fun nativoGerar(h: Long, prompt: String, maxTokens: Int, temperatura: Float): Int

    private companion object {
        const val N_CTX = 4096
        const val MAX_TOKENS = 500
    }
}

enum class EtapaIa { PARADA, CARREGANDO, PROCURANDO, ESCREVENDO }

data class EstadoIa(
    val etapa: EtapaIa = EtapaIa.PARADA,
    val pergunta: String = "",
    val resposta: String = "",
    val fontes: List<Trecho> = emptyList(),
    val avisoSaude: Boolean = false,
    val erro: String? = null,
    val terminou: Boolean = false,
)

/** De onde vem o modelo. Null: ainda não há nenhum no celular nem no SSD. */
sealed interface OrigemModelo {
    data class Celular(val arquivo: File) : OrigemModelo
    data class Ssd(val rel: String) : OrigemModelo
}

/**
 * Pergunte à biblioteca (seção 10). Procura nos manuais e enciclopédias e pede à IA que responda só com o que achou.
 * Carrega só quando usada, e sai da memória quando o app vai para segundo plano ou fica parada.
 */
class AssistenteIa(private val ctx: Context, private val app: BolsoApp) {
    val estado = MutableStateFlow(EstadoIa())
    private val motor by lazy { MotorIa() }
    private val escopo = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val trava = Mutex()
    private var trabalho: Job? = null
    private var relogio: Job? = null
    @Volatile private var sessao = 0L
    private var carregadoDe: Any? = null

    val modelo: ModeloIa? by lazy {
        if (!motor.serve) return@lazy null
        val mem = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mem)
        RegrasIa.modeloPara(mem.totalMem)
    }

    fun origem(): OrigemModelo? {
        val m = modelo ?: return null
        val item = app.banco.todos().firstOrNull { it.id == m.id }
        if (item?.status == Status.PRONTO) {
            val f = File(Armazenamento.pasta(ctx), item.destinoEfetivo)
            if (f.exists()) return OrigemModelo.Celular(f)
        }
        val noSsd = app.ssd.modelosIa()
        return noSsd.firstOrNull { it.endsWith(m.nomeArquivo) }?.let { OrigemModelo.Ssd(it) }
    }

    fun escolherBaixar() {
        val m = modelo ?: return
        app.prefs.iaId = m.id
        app.banco.iaEscolhida = m.id
        app.controle.iniciar(substituir = true)
    }

    fun perguntar(pergunta: String) {
        val p = pergunta.trim().take(1000)
        if (p.isEmpty() || trabalho?.isActive == true) return
        relogio?.cancel()
        estado.value = EstadoIa(EtapaIa.PROCURANDO, p)
        trabalho = escopo.launch {
            try {
                responder(p)
            } catch (e: Exception) {
                app.diario.escrever("IA: $e")
                estado.value = estado.value.copy(etapa = EtapaIa.PARADA, erro = "A IA parou com erro. A busca normal continua funcionando.", terminou = true)
            }
            agendarLiberar()
        }
    }

    fun parar() {
        sessao.takeIf { it != 0L }?.let { motor.parar(it) }
    }

    /** O app foi para segundo plano. Solta os gigabytes do modelo, mesmo no meio de uma resposta. */
    fun aoSairDoApp() {
        parar()
        escopo.launch { liberar() }
    }

    private fun agendarLiberar() {
        relogio?.cancel()
        relogio = escopo.launch {
            delay(OCIOSA_MS)
            liberar()
        }
    }

    private suspend fun liberar() = trava.withLock {
        if (sessao != 0L) {
            motor.liberar(sessao)
            sessao = 0L
            carregadoDe = null
            app.diario.escrever("IA liberada da memória")
        }
    }

    private suspend fun responder(pergunta: String) {
        val m = modelo ?: return
        val inicio = System.currentTimeMillis()
        val termosPt = Consulta.termosPt(pergunta)
        val termosEn = Consulta.termosEn(pergunta)
        val candidatos = procurar(pergunta, termosPt, termosEn)
        val (contexto, usados) = RegrasIa.contexto(
            RegrasIa.escolher(candidatos, Consulta.palavras(termosPt), Consulta.palavras(termosEn)), m.limiteContexto,
        )
        val saude = RegrasIa.ehSaude(pergunta, usados)
        app.diario.escrever("IA: ${candidatos.size} candidatos, ${usados.size} usados em ${System.currentTimeMillis() - inicio} ms")
        estado.value = estado.value.copy(fontes = usados, avisoSaude = saude)
        if (usados.isEmpty()) {
            estado.value = estado.value.copy(
                etapa = EtapaIa.PARADA, terminou = true,
                resposta = RegrasIa.NAO_ENCONTREI + "\nTente outras palavras ou use a busca.",
            )
            return
        }
        estado.value = estado.value.copy(etapa = EtapaIa.CARREGANDO)
        trava.withLock {
            if (!carregar(m)) {
                estado.value = estado.value.copy(
                    etapa = EtapaIa.PARADA, terminou = true,
                    erro = "Não consegui carregar a IA: pode faltar memória livre. Feche outros apps e tente de novo.",
                )
                return
            }
            estado.value = estado.value.copy(etapa = EtapaIa.ESCREVENDO)
            val bytes = ByteArrayOutputStream()
            var primeira = 0L
            val t0 = System.currentTimeMillis()
            var n = 0
            val r = motor.gerar(sessao, RegrasIa.prompt(m, contexto, pergunta)) { b ->
                if (primeira == 0L) primeira = System.currentTimeMillis() - t0
                n++
                bytes.write(b)
                // Decodifica o acumulado: um pedaço pode trazer só metade de um "ç".
                val texto = bytes.toString(Charsets.UTF_8.name()).trimEnd('�')
                estado.value = estado.value.copy(resposta = RegrasIa.semPensamento(texto))
            }
            val total = System.currentTimeMillis() - t0
            app.diario.escrever("IA: código $r, primeira palavra em $primeira ms, $n pedaços em $total ms")
            estado.value = estado.value.copy(
                etapa = EtapaIa.PARADA, terminou = true,
                erro = when (r) {
                    2 -> "A pergunta ficou grande demais. Tente de novo com menos palavras."
                    3 -> "A IA parou com erro. A busca normal continua funcionando."
                    else -> null
                },
            )
        }
    }

    private fun carregar(m: ModeloIa): Boolean {
        val origem = origem() ?: return false
        if (sessao != 0L && carregadoDe == origem) return true
        if (sessao != 0L) motor.liberar(sessao)
        sessao = 0L
        val nucleos = RegrasIa.nucleos(Runtime.getRuntime().availableProcessors())
        val t0 = System.currentTimeMillis()
        sessao = when (origem) {
            is OrigemModelo.Celular -> motor.carregarCaminho(origem.arquivo.path, nucleos)
            is OrigemModelo.Ssd -> app.ssd.abrirDocumento(origem.rel)?.use { motor.carregarDescritor(it.fd, nucleos) } ?: 0L
        }
        app.diario.escrever("IA: ${m.nomeArquivo} carregada em ${System.currentTimeMillis() - t0} ms, $nucleos núcleos, de $origem: ${sessao != 0L}")
        if (sessao != 0L) carregadoDe = origem
        return sessao != 0L
    }

    private suspend fun procurar(pergunta: String, termosPt: String, termosEn: String): List<Trecho> {
        val saida = ArrayList<Trecho>()
        val manuais = app.indicePdf.buscar(listOf(termosPt), listOf(termosEn), true)
        val palavrasPt = Consulta.palavras(termosPt)
        val palavrasEn = Consulta.palavras(termosEn)
        manuais.filter { it.trecho != null && it.tipo == "pdf" }.take(MAX_MANUAIS).forEachIndexed { i, r ->
            val doSsd = r.origem == br.com.waltz.bibliotecadebolso.zim.Origem.SSD
            val texto = app.indicePdf.textoDaPagina(r.caminho, r.pagina, doSsd) ?: return@forEachIndexed
            saida += Trecho(
                r.titulo, r.tema, r.idioma, r.pagina,
                RegrasIa.janela(texto, if (r.idioma == "EN") palavrasEn else palavrasPt),
                i, true, r.caminho, null, doSsd,
            )
        }
        for (l in app.zim.livros().sortedWith(compareBy({ it.origem != br.com.waltz.bibliotecadebolso.zim.Origem.CELULAR }, { it.idioma != "PT" })).take(MAX_LIVROS)) {
            val termos = if (l.idioma == "EN") termosEn else termosPt
            if (termos.isBlank()) continue
            val palavras = if (l.idioma == "EN") palavrasEn else palavrasPt
            try {
                l.trava.withLock {
                    val s = l.buscador ?: return@withLock
                    val q = Query(termos)
                    val b = s.search(q)
                    val it = b.getResults(0, 2)
                    var i = 0
                    while (it.hasNext()) {
                        val caminho = it.path
                        val titulo = it.title
                        it.next()
                        if (l.archive.hasEntryByPath(caminho)) {
                            val item = l.archive.getEntryByPath(caminho).getItem(true)
                            if (item.mimetype.startsWith("text/html")) {
                                val html = String(item.data.data, Charsets.UTF_8)
                                saida += Trecho(
                                    "$titulo (${l.titulo})", l.tema, if (l.idioma == "EN") "EN" else "PT", null,
                                    RegrasIa.janela(RegrasIa.textoDoHtml(html), palavras), i, false, caminho, l.id,
                                )
                            }
                        }
                        i++
                    }
                    it.dispose()
                    b.dispose()
                    q.dispose()
                }
            } catch (e: Exception) {
                app.diario.escrever("IA: busca em ${l.titulo}: ${e.message}")
            }
        }
        return saida
    }

    private companion object {
        const val OCIOSA_MS = 3 * 60 * 1000L
        const val MAX_MANUAIS = 8
        const val MAX_LIVROS = 6
    }
}
