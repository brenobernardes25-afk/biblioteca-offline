package br.com.waltz.bibliotecadebolso.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import br.com.waltz.bibliotecadebolso.zim.ServidorZim
import org.json.JSONArray

/** Artigo do .zim num WebView. Links internos funcionam e nada carrega da internet (seção 8). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Leitor(vm: AppVM, t: Tela.Leitor) {
    val ctx = LocalContext.current
    val escuro = isSystemInDarkTheme()
    var titulo by remember(t.url) { mutableStateOf(t.fonte) }
    var podeVoltar by remember(t.url) { mutableStateOf(false) }
    var web by remember(t.url) { mutableStateOf<WebView?>(null) }

    BackHandler(enabled = podeVoltar) { web?.goBack() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { if (podeVoltar) web?.goBack() else vm.voltar() },
                modifier = Modifier.size(56.dp),
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(titulo, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Fonte: ${t.fonte}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (t.traduzivel) {
                IconButton(onClick = {
                    val w = web ?: return@IconButton
                    // O trecho marcado ou, se não houver, o artigo inteiro.
                    w.evaluateJavascript("(window.getSelection().toString() || document.body.innerText)") { r ->
                        val texto = try {
                            JSONArray("[$r]").getString(0)
                        } catch (_: Exception) {
                            ""
                        }
                        if (texto.isBlank()) Toast.makeText(ctx, "Esta página não tem texto para traduzir", Toast.LENGTH_LONG).show()
                        else vm.ir(Tela.Traduzir(texto))
                    }
                }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Translate, contentDescription = "Traduzir")
                }
            }
            IconButton(onClick = {
                val w = web ?: return@IconButton
                w.evaluateJavascript("window.getSelection().toString()") { r ->
                    val sel = try {
                        JSONArray("[$r]").getString(0)
                    } catch (_: Exception) {
                        ""
                    }
                    if (sel.isBlank()) {
                        Toast.makeText(ctx, "Selecione o trecho que quer compartilhar", Toast.LENGTH_LONG).show()
                    } else {
                        val texto = "$sel\n\n— ${titulo} (${t.fonte})"
                        ctx.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, texto),
                                "Compartilhar trecho",
                            )
                        )
                    }
                }
            }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.Share, contentDescription = "Compartilhar trecho")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true // Artigos zimit precisam de JS.
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.blockNetworkLoads = true // Segunda trava, além do servidor.
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.textZoom = 115
                    if (escuro && WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse =
                            vm.servidor.responder(r)

                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                            if (r.url.host == ServidorZim.HOST) return false
                            Toast.makeText(c, "Este link é para fora da biblioteca e precisaria de internet", Toast.LENGTH_LONG).show()
                            return true
                        }

                        override fun doUpdateVisitedHistory(v: WebView, url: String?, reload: Boolean) {
                            podeVoltar = v.canGoBack()
                        }

                        override fun onPageFinished(v: WebView, url: String?) {
                            v.title?.takeIf { it.isNotBlank() && !it.startsWith("https://") }?.let { titulo = it }
                            podeVoltar = v.canGoBack()
                        }
                    }
                    web = this
                    loadUrl(t.url)
                }
            },
            update = {},
            onRelease = { it.destroy() },
        )
    }
}
