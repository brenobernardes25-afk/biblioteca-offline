package br.com.waltz.bibliotecadebolso.download

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.DocumentsContract
import br.com.waltz.bibliotecadebolso.BolsoApp
import br.com.waltz.bibliotecadebolso.dados.ItemBanco
import br.com.waltz.bibliotecadebolso.dados.Status
import br.com.waltz.bibliotecadebolso.ssd.BibliotecaSsd
import br.com.waltz.bibliotecadebolso.util.bytesLegivel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Um arquivo achado na pasta que veio do computador. */
data class Achado(val caminho: String, val tam: Long, val docId: String)

data class EstadoImportacao(
    val rodando: Boolean = false,
    val etapa: String = "",
    val feitos: Int = 0,
    val total: Int = 0,
    val bytesFeitos: Long = 0,
    val bytesTotal: Long = 0,
    val copiados: Int = 0,
    val rejeitados: Int = 0,
    val faltam: Int = 0,
    val erro: String? = null,
    val terminou: Boolean = false,
    /** A pessoa escolheu o SSD da biblioteca completa: ele não se copia, se conecta. */
    val ehSsd: Boolean = false,
)

/**
 * Traz para o app a pasta preparada no computador (PREPARAR_CELULAR.bat).
 * Os arquivos ficam na pasta do app, igual a um download. Só marca como pronto depois de conferir.
 */
class Importador(private val ctx: Context, private val app: BolsoApp) {
    val estado = MutableStateFlow(EstadoImportacao())
    private val escopo = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trabalho: Job? = null

    fun iniciar(pasta: Uri, apagarOrigem: Boolean) {
        if (trabalho?.isActive == true) return
        estado.value = EstadoImportacao(rodando = true, etapa = "Lendo a pasta…")
        trabalho = escopo.launch {
            try {
                importar(pasta, apagarOrigem)
            } catch (e: kotlinx.coroutines.CancellationException) {
                estado.value = estado.value.copy(rodando = false, terminou = false, etapa = "Parado. Toque em escolher a pasta para continuar.")
                throw e
            } catch (e: Exception) {
                app.diario.escrever("importação falhou: $e")
                estado.value = estado.value.copy(
                    rodando = false,
                    erro = "A cópia parou: ${e.message}. Confira o cabo ou o espaço e escolha a pasta de novo. O que já veio não se perde.",
                )
            } finally {
                app.banco.reabrirInterrompidos()
                app.controle.iniciar(substituir = true)
            }
        }
    }

    fun limparResultado() {
        if (trabalho?.isActive != true) estado.value = EstadoImportacao()
    }

    fun parar() {
        escopo.launch { trabalho?.cancelAndJoin() }
    }

    private suspend fun importar(raiz: Uri, apagarOrigem: Boolean) {
        app.preparar()
        // O SSD da biblioteca completa não é a pasta do computador. Nada dele é copiado nem apagado.
        if (BibliotecaSsd.validaBiblioteca(nomesNaRaiz(raiz))) {
            estado.value = EstadoImportacao(
                erro = "Esta é a biblioteca completa, do SSD. Ela não precisa ser copiada: o app lê direto do SSD. Toque no botão abaixo.",
                ehSsd = true,
            )
            return
        }
        app.controle.suspenderParaImportar()

        val achados = listar(raiz)
        val itens = app.banco.todos()
        val pares = casar(itens, achados)
        if (pares.isEmpty()) {
            estado.value = EstadoImportacao(
                erro = "Não achei os arquivos da biblioteca nesta pasta. Escolha a pasta BibliotecaDeBolso que o computador preparou.",
            )
            return
        }

        // A versão sai do que veio. Não pede para escolher de novo, e nunca rebaixa quem já tem o Slim.
        val detectada = versaoDetectada(itens, pares)
        val versao = if (app.prefs.versao == "slim") "slim" else detectada
        app.prefs.versao = versao
        app.prefs.assistenteConcluido = true
        // Da IA, só o modelo que este celular usa: um modelo a mais seriam gigas à toa.
        val fila = pares.filter { (i, _) -> if (i.tipo == "ia") i.id == app.ia.modelo?.id else versao == "slim" || i.pacote == "minimo" }
        // Quem copiou um modelo de IA para a pasta quer a IA.
        fila.firstOrNull { it.first.tipo == "ia" && it.first.id == app.ia.modelo?.id }?.let {
            app.prefs.iaId = it.first.id
            app.banco.iaEscolhida = it.first.id
        }

        val pasta = Armazenamento.pasta(ctx)
        val total = fila.sumOf { it.second.tam }
        var feitosBytes = 0L
        var copiados = 0
        var rejeitados = 0
        fila.forEachIndexed { n, (item, a) ->
            coroutineContext.ensureActive()
            estado.value = estado.value.copy(
                etapa = item.titulo, feitos = n, total = fila.size, bytesFeitos = feitosBytes, bytesTotal = total,
                copiados = copiados, rejeitados = rejeitados,
            )
            val destinoRel = a.caminho
            val destino = File(pasta, destinoRel)
            if (item.status == Status.PRONTO && (item.tipo == "indice" || destino.length() == a.tam)) {
                if (apagarOrigem) apagar(raiz, a)
            } else if (copiar(raiz, a, item, destinoRel, pasta, feitosBytes)) {
                copiados++
                if (apagarOrigem) apagar(raiz, a)
            } else {
                rejeitados++
            }
            feitosBytes += a.tam
        }

        if (fila.any { it.first.tipo == "zim" }) app.zim.marcarAlterado()
        val faltam = app.banco.totais(versao).pendentes
        app.diario.escrever("importação: $copiados copiados, $rejeitados recusados, $faltam ainda faltam")
        estado.value = EstadoImportacao(
            etapa = "Pronto", feitos = fila.size, total = fila.size, bytesFeitos = total, bytesTotal = total,
            copiados = copiados, rejeitados = rejeitados, faltam = faltam, terminou = true,
        )
    }

    /** Copia, confere e só então marca como pronto. Devolve false se o arquivo veio ruim. */
    private suspend fun copiar(raiz: Uri, a: Achado, item: ItemBanco, destinoRel: String, pasta: File, jaFeitos: Long): Boolean {
        val destino = File(pasta, destinoRel)
        destino.parentFile?.mkdirs()
        val parcial = File(destino.path + ".parcial")
        if (parcial.length() > a.tam) parcial.delete()
        val antes = parcial.length()

        val falta = a.tam - antes
        val livre = StatFs(pasta.path).availableBytes
        if (livre < falta + Armazenamento.RESERVA) {
            throw IOException("falta espaço no celular (${bytesLegivel(falta + Armazenamento.RESERVA - livre)})")
        }

        val doc = DocumentsContract.buildDocumentUriUsingTree(raiz, a.docId)
        val pfd = ctx.contentResolver.openFileDescriptor(doc, "r") ?: throw IOException("não consegui abrir ${a.caminho}")
        pfd.use {
            FileInputStream(it.fileDescriptor).use { ent ->
                ent.channel.position(antes)
                FileOutputStream(parcial, antes > 0).use { sai ->
                    val b = ByteArray(1 shl 20)
                    var copiado = antes
                    var aviso = 0
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = ent.read(b)
                        if (n < 0) break
                        sai.write(b, 0, n)
                        copiado += n
                        if (++aviso % 32 == 0) estado.value = estado.value.copy(bytesFeitos = jaFeitos + copiado)
                    }
                    sai.fd.sync()
                }
            }
        }

        val recebido = parcial.length()
        val mesmoNome = a.caminho == item.destinoEfetivo || a.caminho == item.destino
        val bate = recebido == a.tam && (
            if (mesmoNome) Transferidor.tamanhoConfere(recebido, item.tamEfetivo.takeIf { a.caminho == item.destinoEfetivo } ?: item.tam, item.tipo == "zim")
            else recebido > 1_000_000L
        )
        if (!bate || (item.tipo == "pdf" && !Transferidor.pdfInteiro(parcial))) {
            parcial.delete()
            app.diario.escrever("importação recusou ${a.caminho}: ${recebido} bytes")
            return false
        }
        destino.delete()
        if (!parcial.renameTo(destino)) throw IOException("não consegui gravar ${destino.name}")
        if (item.tipo == "indice") {
            try {
                app.indicePdf.instalar(destino)
            } catch (e: Exception) {
                destino.delete()
                app.diario.escrever("importação: índice não instalado: $e")
                return false
            }
        }
        if (destinoRel != item.destinoEfetivo) {
            File(pasta, item.destinoEfetivo).delete()
            File(pasta, item.destinoEfetivo + ".parcial").delete()
            app.banco.resolvido(item.id, item.urlEfetiva, recebido, destinoRel)
        }
        app.banco.pronto(item.id, recebido)
        return true
    }

    private fun apagar(raiz: Uri, a: Achado) {
        try {
            DocumentsContract.deleteDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(raiz, a.docId))
        } catch (e: Exception) {
            // Sem permissão de apagar (pendrive só de leitura, por exemplo): a cópia já está garantida.
            app.diario.escrever("não apaguei da origem ${a.caminho}: ${e.message}")
        }
    }

    private fun nomesNaRaiz(raiz: Uri): Set<String> {
        val filhos = DocumentsContract.buildChildDocumentsUriUsingTree(raiz, DocumentsContract.getTreeDocumentId(raiz))
        val r = HashSet<String>()
        ctx.contentResolver.query(filhos, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) c.getString(0)?.let { r += it }
        }
        return r
    }

    private fun listar(raiz: Uri): List<Achado> {
        val r = ArrayList<Achado>()
        val colunas = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val fila = ArrayDeque<Pair<String, String>>()
        fila.add(DocumentsContract.getTreeDocumentId(raiz) to "")
        while (fila.isNotEmpty()) {
            val (doc, pre) = fila.removeFirst()
            val filhos = DocumentsContract.buildChildDocumentsUriUsingTree(raiz, doc)
            ctx.contentResolver.query(filhos, colunas, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val nome = c.getString(1) ?: continue
                    val rel = pre + nome
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (rel.count { it == '/' } < 6) fila.add(id to "$rel/")
                    } else {
                        r += Achado(caminhoNormal(rel) ?: continue, c.getLong(3), id)
                    }
                }
            }
        }
        return r
    }

    companion object {
        private val PASTAS = listOf("enciclopedias/", "manuais/", "ia/", "indice/")

        /** Aceita também a pasta de cima: o que vale é o caminho a partir de manuais/, enciclopedias/ ou ia/. */
        fun caminhoNormal(rel: String): String? {
            if (rel.endsWith(".parcial") || rel.contains("..")) return null
            for (p in PASTAS) {
                if (rel.startsWith(p)) return rel
                val i = rel.indexOf("/$p")
                if (i >= 0) return rel.substring(i + 1)
            }
            return null
        }

        /** Liga os arquivos aos itens. Um .zim com outra data no nome também vale, pelo nome do livro. */
        fun casar(itens: List<ItemBanco>, achados: List<Achado>): List<Pair<ItemBanco, Achado>> {
            val porCaminho = achados.associateBy { it.caminho }
            val zims = achados.filter { it.caminho.startsWith("enciclopedias/") && it.caminho.endsWith(".zim") }
            val r = ArrayList<Pair<ItemBanco, Achado>>()
            for (i in itens) {
                val exato = porCaminho[i.destinoEfetivo] ?: porCaminho[i.destino]
                val a = exato ?: i.kiwixNome?.let { nome ->
                    zims.filter { z -> z.caminho.removePrefix("enciclopedias/").startsWith(nome + "_") }
                        .filter { z -> Regex("""^\d{4}-\d{2}[a-z]?\.zim$""").matches(z.caminho.substringAfterLast('/').removePrefix(nome + "_")) }
                        .maxByOrNull { it.caminho }
                }
                if (a != null && a.tam > 0) r += i to a
            }
            return r
        }

        /** Slim quando veio a maior parte do que só o Slim tem. Assim, sobras não disparam 65 GB de download. */
        fun versaoDetectada(itens: List<ItemBanco>, pares: List<Pair<ItemBanco, Achado>>): String {
            val soSlim = itens.filter { it.pacote == "slim" }.sumOf { it.tam }
            val veio = pares.filter { it.first.pacote == "slim" }.sumOf { it.first.tam }
            return if (soSlim > 0 && veio * 2 >= soSlim) "slim" else "minimo"
        }
    }
}
