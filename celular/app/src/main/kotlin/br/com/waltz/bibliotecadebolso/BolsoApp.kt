package br.com.waltz.bibliotecadebolso

import android.app.Application
import br.com.waltz.bibliotecadebolso.dados.Banco
import br.com.waltz.bibliotecadebolso.dados.Manifesto
import br.com.waltz.bibliotecadebolso.dados.Preferencias
import br.com.waltz.bibliotecadebolso.dados.Status
import br.com.waltz.bibliotecadebolso.download.Armazenamento
import br.com.waltz.bibliotecadebolso.download.CatalogoKiwix
import br.com.waltz.bibliotecadebolso.download.ControleDownload
import br.com.waltz.bibliotecadebolso.download.MotorDownload
import br.com.waltz.bibliotecadebolso.download.Transferidor
import br.com.waltz.bibliotecadebolso.ia.RegrasIa
import br.com.waltz.bibliotecadebolso.util.Diario
import br.com.waltz.bibliotecadebolso.util.bytesLegivel
import br.com.waltz.bibliotecadebolso.zim.BibliotecaZim
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import java.io.File
import java.io.IOException

class BolsoApp : Application() {
    val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val diario by lazy { Diario(this) }
    // Um modelo que saiu do app (o 1.7B) não entra mais na fila de downloads.
    val banco by lazy { Banco(this).also { it.iaEscolhida = prefs.iaId?.takeIf { id -> RegrasIa.todos.any { m -> m.id == id } } } }
    val prefs by lazy { Preferencias(this) }
    val http by lazy { Transferidor.cliente() }
    val catalogo by lazy { CatalogoKiwix(http) }
    val zim by lazy { BibliotecaZim(this, banco, diario) }
    val motor by lazy { MotorDownload(this, banco, prefs, Transferidor(http), catalogo, zim, diario) { indicePdf } }
    val controle by lazy { ControleDownload(this, this) }
    val ia by lazy { br.com.waltz.bibliotecadebolso.ia.AssistenteIa(this, this) }
    val ssd by lazy { br.com.waltz.bibliotecadebolso.ssd.BibliotecaSsd(this, this) }
    val montagem by lazy { br.com.waltz.bibliotecadebolso.ssd.Montagem(this, this) }
    val indicePdf by lazy { br.com.waltz.bibliotecadebolso.busca.IndicePdf(this, banco, diario) }
    val importador by lazy { br.com.waltz.bibliotecadebolso.download.Importador(this, this) }

    private val trava = Mutex()
    private var preparado = false

    /**
     * Carrega o manifesto no banco, só se mudou. Ler o JSON inteiro (3.600 itens) a cada abertura
     * atrasaria a primeira tela; basta olhar a versão no começo do arquivo.
     */
    suspend fun preparar() = trava.withLock {
        if (preparado) return@withLock
        val noBanco = banco.meta("versao_manifesto")
        val local = maxOf(
            versaoNoComeco(assets.open("pacotes.json")).orEmpty(),
            File(filesDir, "pacotes.json").takeIf { it.exists() }?.let { versaoNoComeco(it.inputStream()) }.orEmpty(),
        )
        if (noBanco != local) banco.sincronizar(manifestoLocal())
        preparado = true
    }

    private fun versaoNoComeco(entrada: java.io.InputStream): String? = entrada.use {
        val inicio = String(it.readNBytesCompat(256), Charsets.UTF_8)
        Regex("\"versao\"\\s*:\\s*\"([^\"]+)\"").find(inicio)?.groupValues?.get(1)
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val b = ByteArray(n)
        var lidos = 0
        while (lidos < n) {
            val r = read(b, lidos, n - lidos)
            if (r < 0) break
            lidos += r
        }
        return b.copyOf(lidos)
    }

    private fun manifestoLocal(): Manifesto {
        val embutido = Manifesto.ler(assets.open("pacotes.json").bufferedReader().use { it.readText() })
        val cache = File(filesDir, "pacotes.json")
        if (cache.exists()) {
            try {
                val baixado = Manifesto.ler(cache.readText())
                if (baixado.versao >= embutido.versao) return baixado
            } catch (e: Exception) {
                diario.escrever("pacotes.json guardado está ruim, usando o embutido: ${e.message}")
            }
        }
        return embutido
    }

    /**
     * Busca o pacotes.json mais novo no GitHub. Devolve true se havia versão nova.
     * Só é chamado enquanto há download pendente ou pelo botão "Atualizar biblioteca":
     * depois de baixado, o app não faz nenhuma requisição sozinho (critério 14).
     */
    fun buscarManifestoNovo(): Boolean {
        val req = Request.Builder().url(URL_MANIFESTO).header("User-Agent", Transferidor.USER_AGENT).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) {
                diario.escrever("pacotes.json do GitHub: erro ${r.code}, seguindo com a cópia local")
                return false
            }
            val texto = r.body?.string() ?: return false
            val novo = Manifesto.ler(texto)
            if (novo.versao <= banco.meta("versao_manifesto").orEmpty()) return false
            File(filesDir, "pacotes.json").writeText(texto)
            banco.sincronizar(novo)
            diario.escrever("pacotes.json atualizado para ${novo.versao}")
            return true
        }
    }

    /** Ao abrir: se ainda há o que baixar, retoma e confere se há lista nova. */
    fun aoAbrir() {
        ssd.iniciar()
        escopo.launch(Dispatchers.IO) { montagem.iniciar() }
        escopo.launch(Dispatchers.IO) {
            try {
                preparar()
            } catch (e: Exception) {
                diario.escrever("falha ao preparar: ${e.message}")
                return@launch
            }
            launch {
                delay(2500) // Depois de a primeira tela aparecer.
                zim.aquecer()
            }
            val r = controle.resumo()
            if (r.versao != null && !r.terminou && !r.pausado) {
                delay(1500) // Rede e download só depois da primeira tela.
                try {
                    buscarManifestoNovo()
                } catch (e: Exception) {
                    diario.escrever("sem acesso ao pacotes.json do GitHub: ${e.message}")
                }
                controle.iniciar()
            }
        }
    }

    data class RelatorioAtualizacao(val novasVersoes: Int, val erros: Int)

    /** Botão "Atualizar biblioteca": baixa só o que mudou e as versões novas dos .zim. */
    fun atualizarBiblioteca(): RelatorioAtualizacao {
        try {
            buscarManifestoNovo()
        } catch (e: Exception) {
            diario.escrever("atualizar: sem pacotes.json novo: ${e.message}")
        }
        var novas = 0
        var erros = 0
        for (item in banco.zimsProntos()) {
            val nome = item.kiwixNome ?: continue
            try {
                val v = catalogo.maisNova(nome) ?: continue
                val destino = item.destino.substringBeforeLast('/') + "/" + v.nomeArquivo
                if (destino != item.destinoEfetivo) {
                    banco.novaVersaoZim(item.id, v.url, v.tam, destino, item.destinoEfetivo)
                    diario.escrever("nova versão: ${item.destinoEfetivo} -> $destino (${bytesLegivel(v.tam)})")
                    novas++
                }
            } catch (e: IOException) {
                diario.escrever("atualizar: $nome: ${e.message}")
                erros++
            }
        }
        controle.iniciar(substituir = true)
        return RelatorioAtualizacao(novas, erros)
    }

    /** Trocar do Slim para o mínimo: apaga o que sobra, se a pessoa aceitar. */
    fun apagarSobra(): Int {
        val sobra = banco.sobra(prefs.versao)
        val pasta = Armazenamento.pasta(this)
        sobra.filter { it.status == Status.PRONTO }.forEach {
            File(pasta, it.destinoEfetivo).delete()
            banco.apagado(it.id)
        }
        zim.marcarAlterado()
        return sobra.size
    }

    companion object {
        const val URL_MANIFESTO =
            "https://raw.githubusercontent.com/brenobernardes25-afk/biblioteca-offline/main/celular/pacotes.json"
    }
}
