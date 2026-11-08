package br.com.waltz.bibliotecadebolso.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.waltz.bibliotecadebolso.BolsoApp
import br.com.waltz.bibliotecadebolso.busca.Atalho
import br.com.waltz.bibliotecadebolso.busca.Consulta
import br.com.waltz.bibliotecadebolso.dados.Filtro
import br.com.waltz.bibliotecadebolso.dados.ItemBanco
import br.com.waltz.bibliotecadebolso.download.Armazenamento
import br.com.waltz.bibliotecadebolso.download.Resumo
import br.com.waltz.bibliotecadebolso.zim.EstadoBusca
import br.com.waltz.bibliotecadebolso.zim.LivroZim
import br.com.waltz.bibliotecadebolso.zim.ServidorZim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import br.com.waltz.bibliotecadebolso.busca.ResultadoPdf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface Tela {
    data object Assistente : Tela
    data object Inicio : Tela
    data object Resultados : Tela
    data class Leitor(val url: String, val fonte: String, val traduzivel: Boolean = false) : Tela
    data class Traduzir(val texto: String = "") : Tela
    data object Downloads : Tela
    data object Ssd : Tela
    data object Mapa : Tela
    data object Sobre : Tela
    data object Manual : Tela
    data class Pdf(val caminho: String, val titulo: String, val pagina: Int, val doSsd: Boolean = false, val traduzivel: Boolean = false) : Tela
    data object Montar : Tela
    data object Perguntar : Tela
    data object Importar : Tela
}

class AppVM(a: Application) : AndroidViewModel(a) {
    val app = a as BolsoApp
    val servidor = ServidorZim(app.zim, app.diario)

    val pilha = mutableStateListOf<Tela>(
        if (app.prefs.assistenteConcluido) Tela.Inicio else Tela.Assistente
    )
    val tela get() = pilha.last()

    fun ir(t: Tela) {
        pilha.add(t)
    }

    fun voltar(): Boolean {
        if (pilha.size <= 1) return false
        pilha.removeAt(pilha.lastIndex)
        return true
    }

    /** Lido do banco a cada 2 s, só enquanto a tela está visível. */
    val resumo = flow {
        while (true) {
            emit(app.controle.resumo())
            delay(2000)
        }
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Resumo())

    init {
        app.aoAbrir()
    }

    // ---- Assistente ----

    var livreCelular by mutableStateOf(0L)
        private set
    var faltaMinimo by mutableStateOf(0L)
        private set
    var faltaSlim by mutableStateOf(0L)
        private set
    var manifestoPronto by mutableStateOf(false)
        private set

    fun atualizarEspaco() {
        viewModelScope.launch(Dispatchers.IO) {
            app.preparar()
            livreCelular = Armazenamento.livre(app)
            faltaMinimo = app.banco.faltaBaixar("minimo")
            faltaSlim = app.banco.faltaBaixar("slim")
            manifestoPronto = true
        }
    }

    fun escolherVersao(versao: String?, soWifi: Boolean, comIa: Boolean = false) {
        app.prefs.versao = versao
        if (comIa) {
            app.prefs.iaId = app.ia.modelo?.id
            app.banco.iaEscolhida = app.prefs.iaId
        }
        app.prefs.soWifi = soWifi
        app.prefs.pausado = false
        app.prefs.assistenteConcluido = true
        pilha.clear()
        pilha.add(Tela.Inicio)
        viewModelScope.launch(Dispatchers.IO) {
            app.preparar()
            if (versao != null) app.controle.iniciar(substituir = true)
        }
    }

    fun irParaInicio() {
        pilha.clear()
        pilha.add(Tela.Inicio)
        atualizarLivros()
    }

    // ---- Início ----

    var livros by mutableStateOf<List<LivroZim>>(emptyList())
        private set
    var pdfsProntos by mutableStateOf(0)
        private set

    fun atualizarLivros() {
        viewModelScope.launch(Dispatchers.IO) {
            app.zim.marcarAlterado()
            app.preparar()
            livros = app.zim.livros()
            pdfsProntos = app.banco.contarProntos("pdf")
        }
    }

    fun abrirPaginaInicial(l: LivroZim) {
        viewModelScope.launch(Dispatchers.IO) {
            val url = ServidorZim.paginaInicial(l)
            withContext(Dispatchers.Main) {
                if (url != null) ir(Tela.Leitor(url, "${l.titulo} · ${l.origem.rotulo}", l.idioma != "PT"))
            }
        }
    }

    // ---- Busca ----

    var texto by mutableStateOf("")
    var incluirIngles by mutableStateOf(app.prefs.incluirIngles)
        private set
    var busca by mutableStateOf(EstadoBusca())
        private set
    var buscando by mutableStateOf(false)
        private set
    private var atalhoAtual: Atalho? = null
    private var trabalhoBusca: Job? = null
    var manuais by mutableStateOf<List<ResultadoPdf>>(emptyList())
        private set
    var buscandoManuais by mutableStateOf(false)
        private set
    var indicePronto by mutableStateOf(true)
        private set

    fun buscarTexto() {
        if (texto.isBlank()) return
        atalhoAtual = null
        disparar()
        if (tela != Tela.Resultados) ir(Tela.Resultados)
    }

    fun buscarAtalho(a: Atalho) {
        texto = a.nome
        atalhoAtual = a
        disparar()
        if (tela != Tela.Resultados) ir(Tela.Resultados)
    }

    fun buscarDeNovo() = disparar()

    fun mudarIngles(v: Boolean) {
        incluirIngles = v
        app.prefs.incluirIngles = v
        if (tela == Tela.Resultados) disparar()
    }

    private fun disparar() {
        trabalhoBusca?.cancel()
        val a = atalhoAtual
        val pt = a?.pt?.map { Consulta.termosPt(it) } ?: listOf(Consulta.termosPt(texto))
        val en = a?.en ?: listOf(Consulta.termosEn(texto))
        buscando = true
        busca = EstadoBusca()
        manuais = emptyList()
        buscandoManuais = true
        val ingles = incluirIngles
        trabalhoBusca = viewModelScope.launch {
            app.preparar()
            val inicio = System.currentTimeMillis()
            launch(Dispatchers.IO) {
                val r = try {
                    app.indicePdf.buscar(pt, en, ingles, { app.montagem.buscarPorNome(it) }) { parcial -> manuais = parcial }
                } catch (e: Exception) {
                    app.diario.escrever("busca nos manuais: $e")
                    emptyList()
                }
                indicePronto = app.indicePdf.pronto || app.indicePdf.completoPronto
                manuais = r
                buscandoManuais = false
                app.diario.escrever("busca nos manuais $pt/$en: ${r.size} em ${System.currentTimeMillis() - inicio} ms")
            }
            var primeiro = 0L
            app.zim.buscar(pt, en, incluirIngles).collect {
                if (primeiro == 0L && it.resultados.isNotEmpty()) primeiro = System.currentTimeMillis() - inicio
                busca = it
            }
            buscando = false
            app.diario.escrever(
                "busca $pt/$en: ${busca.resultados.size} resultados em ${busca.fontesTotal} fontes, " +
                    "primeiro em $primeiro ms, total ${System.currentTimeMillis() - inicio} ms, lentas: ${busca.lentas}"
            )
        }
    }

    // ---- Downloads ----

    var filtro by mutableStateOf(Filtro.ANDAMENTO)
    var itens by mutableStateOf<List<ItemBanco>>(emptyList())
        private set

    fun atualizarItens() {
        viewModelScope.launch(Dispatchers.IO) {
            itens = app.banco.listar(filtro, app.prefs.versao)
        }
    }

    var mensagem by mutableStateOf<String?>(null)
    var atualizando by mutableStateOf(false)
        private set

    fun atualizarBiblioteca() {
        atualizando = true
        viewModelScope.launch(Dispatchers.IO) {
            val r = app.atualizarBiblioteca()
            mensagem = when {
                r.erros > 0 && r.novasVersoes == 0 -> "Não consegui falar com o catálogo. Veja a internet e tente de novo."
                r.novasVersoes == 0 -> "A biblioteca já está em dia."
                r.novasVersoes == 1 -> "1 fonte tem versão nova. A versão antiga segue funcionando até a nova chegar."
                else -> "${r.novasVersoes} fontes têm versão nova. A versão antiga segue funcionando até a nova chegar."
            }
            atualizando = false
        }
    }

    /** Do mínimo para o Slim baixa só a diferença. Do Slim para o mínimo, oferece apagar o que sobra. */
    fun trocarVersao(nova: String) {
        app.prefs.versao = nova
        app.prefs.pausado = false
        viewModelScope.launch(Dispatchers.IO) {
            app.preparar()
            app.controle.iniciar(substituir = true)
            atualizarSobra()
        }
    }

    var sobra by mutableStateOf<List<ItemBanco>>(emptyList())
        private set

    fun atualizarSobra() {
        viewModelScope.launch(Dispatchers.IO) { sobra = app.banco.sobra(app.prefs.versao) }
    }

    fun apagarSobra() {
        viewModelScope.launch(Dispatchers.IO) {
            val n = app.apagarSobra()
            mensagem = "$n arquivos apagados."
            sobra = emptyList()
            atualizarLivros()
        }
    }
}
