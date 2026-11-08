package br.com.waltz.bibliotecadebolso.traducao

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import br.com.waltz.bibliotecadebolso.util.Diario
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.ByteArrayInputStream

data class EstadoTraducao(
    val pronto: Boolean = false,
    val traduzindo: Boolean = false,
    val feitos: Int = 0,
    val total: Int = 0,
    val resultado: String = "",
    val erro: String? = null,
)

/**
 * Tradução embutida (fase 3b da seção 9), com o motor e o modelo abertos da Mozilla (MPL-2.0).
 * O motor é WebAssembly e roda numa página sem tela. Só existe enquanto a tela de tradução está aberta,
 * porque o modelo ocupa algumas centenas de MB de memória.
 */
class Tradutor(ctx: Context, private val diario: Diario) {
    val estado = MutableStateFlow(EstadoTraducao())
    private var pedido = 0

    private val carregador = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    private val web = WebView(ctx.applicationContext).apply {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true // Segunda trava: tudo vem dos arquivos do app.
        addJavascriptInterface(Ponte(), "Ponte")
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse =
                carregador.shouldInterceptRequest(r.url)
                    ?: WebResourceResponse("text/plain", "utf-8", 404, "fora do app", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
        loadUrl("https://appassets.androidplatform.net/assets/tradutor/index.html")
    }

    fun traduzir(texto: String) {
        pedido++
        estado.value = estado.value.copy(traduzindo = true, feitos = 0, total = 0, resultado = "", erro = null)
        web.evaluateJavascript("traduzir($pedido, ${JSONObject.quote(limitar(texto))})", null)
    }

    fun fechar() {
        web.stopLoading()
        web.removeJavascriptInterface("Ponte")
        web.destroy()
    }

    private inner class Ponte {
        @JavascriptInterface
        fun pronto() {
            estado.value = estado.value.copy(pronto = true)
            diario.escrever("tradutor pronto")
        }

        @JavascriptInterface
        fun parcial(id: Int, feitos: Int, total: Int, texto: String) {
            if (id == pedido) estado.value = estado.value.copy(feitos = feitos, total = total, resultado = texto)
        }

        @JavascriptInterface
        fun fim(id: Int, texto: String) {
            if (id == pedido) estado.value = estado.value.copy(traduzindo = false, resultado = texto)
        }

        @JavascriptInterface
        fun erro(id: Int, msg: String) {
            diario.escrever("tradutor: $msg")
            if (id == 0 || id == pedido) {
                estado.value = estado.value.copy(traduzindo = false, erro = "Não consegui traduzir aqui. Use o botão para abrir em outro tradutor.")
            }
        }
    }

    companion object {
        const val LIMITE = 30_000

        /** Um artigo pode ser enorme: traduz o começo, sem cortar no meio de uma palavra. */
        fun limitar(t: String): String {
            if (t.length <= LIMITE) return t
            val corte = t.lastIndexOf(' ', LIMITE).takeIf { it > 0 } ?: LIMITE
            return t.substring(0, corte)
        }

        /**
         * O texto do PDF vem quebrado linha a linha, como na página. Traduzir assim corta a frase ao meio:
         * junta a linha com a seguinte quando a frase claramente continua.
         */
        fun juntarLinhas(t: String): String {
            val saida = StringBuilder()
            val linhas = t.split('\n').map { it.trim() }
            for ((i, l) in linhas.withIndex()) {
                saida.append(l)
                val prox = linhas.getOrNull(i + 1) ?: break
                val continua = l.isNotEmpty() && prox.isNotEmpty() && l.last() !in ".:;!?" && prox.first().isLowerCase()
                when {
                    continua && l.endsWith("-") -> saida.setLength(saida.length - 1) // Palavra partida entre linhas.
                    continua -> saida.append(' ')
                    else -> saida.append('\n')
                }
            }
            return saida.toString()
        }

        /**
         * Fase 3a da seção 9: entrega ao tradutor do celular. O Google Tradutor funciona sem internet
         * depois de baixar o pacote de idiomas. Devolve false se não há app nenhum para receber o texto.
         */
        fun abrirEmOutroApp(ctx: Context, texto: String): Boolean {
            val curto = texto.take(LIMITE_OUTRO_APP)
            val pm = ctx.packageManager
            val proprio = ctx.packageName
            val processar = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, curto).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            // Nem todo app que recebe texto traduz: o TalkBack, por exemplo, só lê em voz alta.
            val opcoes = pm.queryIntentActivities(processar, 0).filter {
                val pacote = it.activityInfo.packageName
                val nome = it.loadLabel(pm).toString()
                pacote != proprio && pareceTradutor(pacote, nome)
            }
            val intencao = when {
                opcoes.isNotEmpty() -> {
                    val g = opcoes.firstOrNull { it.activityInfo.packageName == GOOGLE_TRADUTOR }
                    if (g != null) processar.setClassName(g.activityInfo.packageName, g.activityInfo.name)
                    else Intent.createChooser(processar, "Traduzir com")
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    pm.queryIntentActivities(Intent(Intent.ACTION_TRANSLATE).setType("text/plain"), 0).isNotEmpty() ->
                    Intent(Intent.ACTION_TRANSLATE).setType("text/plain").putExtra(Intent.EXTRA_TEXT, curto)
                else -> Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, curto), "Traduzir com",
                )
            }
            return try {
                ctx.startActivity(intencao)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }

        fun pareceTradutor(pacote: String, nome: String): Boolean {
            val t = (pacote + " " + nome).lowercase()
            return "transl" in t || "tradu" in t
        }

        fun lojaGoogleTradutor() = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$GOOGLE_TRADUTOR"))

        // Um Intent grande demais derruba o app: o limite do Android é cerca de 1 MB, e o de texto é bem menor.
        private const val LIMITE_OUTRO_APP = 20_000
        private const val GOOGLE_TRADUTOR = "com.google.android.apps.translate"
    }
}
