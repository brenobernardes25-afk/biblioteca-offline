package br.com.waltz.bibliotecadebolso.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.StatFs
import br.com.waltz.bibliotecadebolso.dados.Banco
import br.com.waltz.bibliotecadebolso.dados.ItemBanco
import br.com.waltz.bibliotecadebolso.dados.Preferencias
import br.com.waltz.bibliotecadebolso.util.Diario
import br.com.waltz.bibliotecadebolso.util.bytesLegivel
import br.com.waltz.bibliotecadebolso.zim.BibliotecaZim
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Limita a concorrência: até 3 ao mesmo tempo, e arquivos grandes sozinhos (seção 5). */
class Vagas(private val limite: Int = 3) {
    private val sem = Semaphore(limite)
    private val fila = Mutex()

    /** O Mutex impede dois grandes de ficarem cada um com parte das vagas, esperando o outro. */
    suspend fun pegar(n: Int) = fila.withLock { repeat(n) { sem.acquire() } }
    fun soltar(n: Int) = repeat(n) { sem.release() }
    fun quantas(tam: Long) = if (tam >= GRANDE) limite else 1

    companion object {
        const val GRANDE = 1_000_000_000L
    }
}

/** A fila em si. Roda dentro do DownloadWorker; o WorkManager cuida de quando ela pode rodar. */
class MotorDownload(
    private val ctx: Context,
    private val banco: Banco,
    private val prefs: Preferencias,
    private val transferidor: Transferidor,
    private val catalogo: CatalogoKiwix,
    private val zim: BibliotecaZim,
    private val diario: Diario,
    private val indicePdf: () -> br.com.waltz.bibliotecadebolso.busca.IndicePdf,
) {
    private val emCurso = ConcurrentHashMap<String, Long>()
    private var ultimoTotal = 0L
    private var ultimoInstante = 0L
    private val limite = LimiteHost()

    /**
     * Ao retomar ou mudar o "Só no Wi-Fi", o WorkManager cancela o trabalho antigo e já começa o novo.
     * Sem esta trava, os dois poderiam escrever no mesmo .parcial por um instante e corromper o arquivo.
     */
    private val umaRodadaPorVez = Mutex()

    /** Bytes por segundo, média móvel. Só para a estimativa de tempo. */
    @Volatile var velocidade = 0.0
        private set

    @Volatile var economiaDeBateria = false
        private set

    /** Nada baixando agora porque o servidor do que resta pediu para esperar. */
    val servidorEmEspera: String?
        get() = if (emCurso.isEmpty()) limite.emEspera() else null

    private val ESPERAS_S = longArrayOf(5, 15, 45, 120)

    suspend fun rodar(aoTick: suspend () -> Unit) = umaRodadaPorVez.withLock {
        banco.reabrirInterrompidos()
        try {
            coroutineScope {
                val relogio = launch {
                    while (true) {
                        delay(2000)
                        medir()
                        aoTick()
                    }
                }
                val vagas = Vagas()
                while (true) {
                    esperarSairDaEconomia()
                    val item = banco.proximo(prefs.versao, emCurso.keys.toSet(), limite.indisponiveis())
                    if (item == null) {
                        // Nada pode começar agora. Só termina quando não restar nem o que está em espera.
                        if (emCurso.isEmpty() && banco.proximo(prefs.versao, emptySet()) == null) break
                        delay(500)
                        continue
                    }
                    val n = vagas.quantas(item.tamEfetivo)
                    vagas.pegar(n)
                    // Pode ter entrado em espera enquanto aguardava a vaga.
                    val host = hostDe(item)
                    if (host in limite.indisponiveis()) {
                        vagas.soltar(n)
                        continue
                    }
                    limite.comecou(host)
                    emCurso[item.id] = item.baixados
                    launch(Dispatchers.IO) {
                        var sucesso = false
                        try {
                            sucesso = baixarItem(item, vagas, n)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            diario.escrever("erro inesperado em ${item.id}: $e")
                            banco.falhou(item.id, "Erro inesperado neste arquivo. Toque em Tentar de novo.")
                        } finally {
                            limite.terminou(host, sucesso)
                            emCurso.remove(item.id)
                            vagas.soltar(n)
                        }
                    }
                }
                relogio.cancel()
            }
        } finally {
            withContext(NonCancellable) {
                banco.bytes(emCurso.toMap())
                banco.reabrirInterrompidos()
                velocidade = 0.0
            }
        }
    }

    private fun medir() {
        banco.bytes(emCurso.toMap())
        val agora = System.nanoTime()
        val total = banco.totais(prefs.versao).bytesFeitos
        if (ultimoInstante != 0L) {
            val dt = (agora - ultimoInstante) / 1e9
            val inst = (total - ultimoTotal).coerceAtLeast(0) / dt
            velocidade = if (velocidade == 0.0) inst else velocidade * 0.9 + inst * 0.1
        }
        ultimoInstante = agora
        ultimoTotal = total
    }

    /** Em modo de economia de bateria e fora da tomada, não começa nada novo. */
    private suspend fun esperarSairDaEconomia() {
        val power = ctx.getSystemService(PowerManager::class.java)
        val bateria = ctx.getSystemService(BatteryManager::class.java)
        while (power.isPowerSaveMode && !bateria.isCharging) {
            economiaDeBateria = true
            delay(30_000)
        }
        economiaDeBateria = false
    }

    private fun hostDe(item: ItemBanco) = item.urlEfetiva.toHttpUrlOrNull()?.host.orEmpty()

    /** Devolve true se o arquivo ficou pronto. */
    private suspend fun baixarItem(inicial: ItemBanco, vagas: Vagas, n: Int): Boolean {
        var item = inicial
        val pasta = Armazenamento.pasta(ctx)
        banco.marcarBaixando(item.id)

        // .zim sem URL escolhida ainda: pergunta ao catálogo qual é a versão mais nova.
        // Se já há um .parcial da URL reserva (catálogo fora do ar antes), continua nele.
        val temParcial = File(pasta, item.destino + ".parcial").exists()
        if (item.tipo == "zim" && item.urlAtual == null && !temParcial) item = resolverZim(item)

        var tentativas = item.tentativas
        var catalogoRefeito = false
        while (true) {
            coroutineContext.ensureActive()
            val destino = File(pasta, item.destinoEfetivo)
            val parcial = File(destino.path + ".parcial")
            val antes = parcial.length()

            val falta = item.tamEfetivo - antes
            val livre = StatFs(pasta.path).availableBytes
            if (livre < falta + Armazenamento.RESERVA) {
                banco.falhou(
                    item.id,
                    "Sem espaço: faltam ${bytesLegivel(falta + Armazenamento.RESERVA - livre)}. " +
                        "Libere espaço no celular e toque em Tentar de novo.",
                )
                return false
            }

            try {
                val final = baixarUmaVez(item, parcial)
                if (parcial.length() != final) {
                    parcial.delete()
                    throw IOException("Arquivo em disco diferente do recebido; vai recomeçar")
                }
                val tolerante = item.tipo == "zim"
                if (!Transferidor.tamanhoConfere(final, item.tamEfetivo, tolerante)) {
                    parcial.delete()
                    throw IOException(
                        "Tamanho diferente do esperado (${bytesLegivel(final)} em vez de ${bytesLegivel(item.tamEfetivo)})"
                    )
                }
                // Baixar de novo traria o mesmo arquivo, então não gasta tentativas nem dados.
                if (item.tipo == "pdf" && !Transferidor.pdfInteiro(parcial)) {
                    parcial.delete()
                    banco.falhou(item.id, "O arquivo está cortado no servidor. Não foi guardado.")
                    diario.escrever("PDF cortado: ${item.id} (${bytesLegivel(final)})")
                    return false
                }
                destino.delete()
                if (!parcial.renameTo(destino)) throw IOException("Não consegui gravar ${destino.name}")
                item.destinoAntigo?.takeIf { it != item.destinoEfetivo }?.let { File(pasta, it).delete() }
                if (item.tipo == "indice") {
                    try {
                        indicePdf().instalar(destino)
                    } catch (e: Exception) {
                        destino.delete()
                        banco.falhou(item.id, "Não consegui preparar o índice dos manuais: ${e.message}. Confira o espaço e toque em Tentar de novo.")
                        diario.escrever("índice não instalado: $e")
                        return false
                    }
                }
                banco.pronto(item.id, final)
                diario.escrever("pronto: ${item.id} (${bytesLegivel(final)})")
                if (item.tipo == "zim") zim.marcarAlterado()
                return true
            } catch (e: IOException) {
                coroutineContext.ensureActive()

                // Sem rede não conta como tentativa: é só esperar voltar.
                if (!Rede.disponivel(ctx, prefs.soWifi)) {
                    diario.escrever("sem rede, aguardando: ${item.id}")
                    esperarLiberandoVagas(vagas, n, 10)
                    continue
                }

                // Servidor pediu para ir devagar: não é culpa do arquivo. Volta para a fila e o
                // servidor inteiro entra em espera. Só conta tentativa quando a espera já é a máxima.
                if (e is ErroHttp && (e.codigo == 429 || e.codigo == 503)) {
                    val saturado = limite.frear(hostDe(item), e.retryAfterS)
                    diario.escrever("servidor pediu calma (${e.codigo}): ${hostDe(item)}")
                    if (saturado) tentativas++
                    if (tentativas >= MAX_TENTATIVAS) {
                        banco.falhou(item.id, "O servidor está sobrecarregado (erro ${e.codigo}). Tente de novo mais tarde.")
                    } else {
                        banco.aguardar(item.id, tentativas, "O servidor pediu para ir mais devagar. Continua sozinho.")
                    }
                    return false
                }

                // URL de .zim que sumiu do servidor: versão antiga. Pergunta de novo ao catálogo.
                if (e is ErroHttp && e.codigo == 404 && item.tipo == "zim" && !catalogoRefeito && item.destinoAntigo == null) {
                    catalogoRefeito = true
                    parcial.delete()
                    banco.bytes(mapOf(item.id to 0L))
                    item = resolverZim(item)
                    continue
                }

                // Se chegou algo, a conexão está instável, não o arquivo. Não gasta tentativa.
                tentativas = if (parcial.length() > antes) 0 else tentativas + 1
                diario.escrever("erro em ${item.id} (tentativa $tentativas): ${e.message}")
                if (tentativas >= MAX_TENTATIVAS) {
                    banco.falhou(item.id, mensagemErro(e))
                    return false
                }
                banco.tentativas(item.id, tentativas, mensagemErro(e))
                // Em espera, também não segura o servidor para os outros arquivos.
                val host = hostDe(item)
                limite.terminou(host, false)
                try {
                    esperarLiberandoVagas(vagas, n, ESPERAS_S[(tentativas - 1).coerceIn(ESPERAS_S.indices)])
                } finally {
                    limite.comecou(host)
                }
            }
        }
    }

    /** Enquanto um item espera, libera a vaga para os outros continuarem. */
    private suspend fun esperarLiberandoVagas(vagas: Vagas, n: Int, segundos: Long) {
        vagas.soltar(n)
        var devolver = false
        try {
            delay(segundos * 1000)
            vagas.pegar(n)
            devolver = true
        } finally {
            // Se foi cancelado na espera, o finally de quem chamou vai soltar n de novo:
            // pega de volta sem esperar para o saldo fechar.
            if (!devolver) withContext(NonCancellable) { vagas.pegar(n) }
        }
    }

    private suspend fun baixarUmaVez(item: ItemBanco, parcial: File): Long = withContext(Dispatchers.IO) {
        var chamada: Call? = null
        val vigia = launch {
            try {
                awaitCancellation()
            } finally {
                chamada?.cancel()
            }
        }
        try {
            transferidor.baixar(
                item.urlEfetiva, parcial,
                aoChamar = { chamada = it },
                aoProgredir = { emCurso[item.id] = it },
            )
        } catch (e: CancellationException) {
            throw e
        } finally {
            vigia.cancel()
        }
    }

    private fun resolverZim(item: ItemBanco): ItemBanco {
        val nome = item.kiwixNome ?: return item
        val nova = try {
            catalogo.maisNova(nome)
        } catch (e: Exception) {
            diario.escrever("catálogo indisponível para $nome: ${e.message}; usando a URL do pacotes.json")
            null
        }
        if (nova == null) return item
        val destino = item.destino.substringBeforeLast('/') + "/" + nova.nomeArquivo
        banco.resolvido(item.id, nova.url, nova.tam, destino)
        diario.escrever("catálogo: $nome -> ${nova.nomeArquivo} (${bytesLegivel(nova.tam)})")
        return item.copy(urlAtual = nova.url, tamAtual = nova.tam, destinoAtual = destino)
    }

    private fun mensagemErro(e: IOException): String = when {
        e is ErroHttp && e.codigo == 404 -> "O arquivo não existe mais no servidor (erro 404)."
        e is ErroHttp -> "O servidor recusou o download (erro ${e.codigo})."
        e.message?.startsWith("Tamanho") == true -> e.message!!
        else -> "O servidor não respondeu direito. Tente de novo mais tarde."
    }

    companion object {
        const val MAX_TENTATIVAS = 5
    }
}

object Armazenamento {
    /** Margem para o celular não ficar sem nenhum espaço. */
    const val RESERVA = 200_000_000L

    /** Pasta específica do app: não pede permissão. */
    fun pasta(ctx: Context): File = (ctx.getExternalFilesDir(null) ?: ctx.filesDir).apply { mkdirs() }

    fun livre(ctx: Context): Long = StatFs(pasta(ctx).path).availableBytes
}

object Rede {
    fun disponivel(ctx: Context, soWifi: Boolean): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return !soWifi || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
