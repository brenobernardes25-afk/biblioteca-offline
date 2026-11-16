package br.com.waltz.bibliotecadebolso.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.WebResourceError
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import br.com.waltz.bibliotecadebolso.util.InstaladorApk
import java.io.ByteArrayInputStream
import java.io.File

// O da Play Store é "app.organicmaps"; o instalador que vem no pen drive (do site) instala "app.organicmaps.web".
private val PACOTES_OM = listOf("app.organicmaps", "app.organicmaps.web")

private fun aberturaOrganicMaps(ctx: Context): Intent? =
    PACOTES_OM.firstNotNullOfOrNull { ctx.packageManager.getLaunchIntentForPackage(it) }

private fun organicMapsInstalado(ctx: Context) = aberturaOrganicMaps(ctx) != null

/**
 * Seção 11. Duas formas de mapa sem internet:
 * 1. O mapa do Brasil dentro do próprio app (TelaMapaBrasil): lido direto do pen drive, sem instalar nem copiar
 *    nada; busca de lugares, GPS, medir distância e marcar pontos. Não traça rotas.
 * 2. O Organic Maps, para rotas e navegação: o instalador vem no pen drive, mas os mapas precisam estar dentro do
 *    celular (o Android não deixa um app gravar na pasta de outro), por isso o passo 2 é pelo computador.
 */
@Composable
internal fun TelaMapa(vm: AppVM) {
    val ctx = LocalContext.current
    val escopo = rememberCoroutineScope()
    val ssd by vm.app.ssd.estado.collectAsStateWithLifecycle()
    var instalado by remember { mutableStateOf(organicMapsInstalado(ctx)) }
    var apk by remember { mutableStateOf<String?>(null) }
    var temMapa by remember { mutableStateOf<Boolean?>(null) }
    var aviso by remember { mutableStateOf<String?>(null) }
    var copiando by remember { mutableStateOf(false) }
    val situacaoInstalacao by InstaladorApk.situacao.collectAsStateWithLifecycle()

    // Volta da tela de instalação do Android: confere de novo, sem a pessoa precisar sair e entrar.
    LaunchedEffect(Unit) {
        while (true) {
            instalado = organicMapsInstalado(ctx)
            delay(1500)
        }
    }
    LaunchedEffect(ssd.conectado) {
        apk = if (ssd.conectado) withContext(Dispatchers.IO) { vm.app.ssd.apkOrganicMaps() } else null
        temMapa = if (ssd.conectado) withContext(Dispatchers.IO) { vm.app.mapa.disponivel() } else false
    }

    fun instalar(rel: String) {
        val pm = ctx.packageManager
        if (!pm.canRequestPackageInstalls()) {
            aviso = "O Android vai pedir uma permissão: ligue \"Permitir desta fonte\" para a Biblioteca de Bolso, " +
                "volte para cá e toque de novo em Instalar."
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        copiando = true
        InstaladorApk.situacao.value = "Preparando a instalação…"
        escopo.launch {
            val tam = withContext(Dispatchers.IO) { vm.app.ssd.tamanho(rel) } ?: -1L
            val ok = withContext(Dispatchers.IO) {
                InstaladorApk.instalar(ctx, { vm.app.ssd.abrirDocumento(rel) }, tam) { pct ->
                    InstaladorApk.situacao.value = "Preparando a instalação: $pct%"
                }
            }
            copiando = false
            InstaladorApk.situacao.value = if (ok) {
                "Já vai abrir uma janela do Android perguntando se quer instalar o Organic Maps: toque em Instalar."
            } else {
                "Não consegui ler o instalador. Confira se o pen drive está bem encaixado e tente de novo."
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Cabecalho("Mapa", vm)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Mapa do Brasil sem internet", style = MaterialTheme.typography.headlineMedium)

            // ---------- O mapa do próprio app ----------
            Passo("Mapa do Brasil (no próprio app)", null) {
                Text(
                    "O Brasil inteiro, direto do pen drive, sem instalar nem copiar nada: ruas, rios, trilhas, " +
                        "busca de cidades e lugares, o GPS mostrando onde você está, medir distâncias e marcar pontos.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                when (temMapa) {
                    true -> BotaoGrande("Abrir o mapa do Brasil", { vm.ir(Tela.MapaBrasil) }, Modifier.fillMaxWidth())
                    null -> CircularProgressIndicator()
                    false -> Text(
                        if (ssd.conectado) "Este pen drive não tem o mapa do Brasil (2_Mapas › brasil.pmtiles)."
                        else "Ligue o pen drive no celular e toque em \"Usar este pen drive\" na tela inicial.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                Text(
                    "Ele não traça rotas. Para rotas e navegação, use o Organic Maps, logo abaixo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text("Para rotas e navegação: Organic Maps (opcional)", style = MaterialTheme.typography.titleLarge)

            // ---------- Organic Maps, passo 1 ----------
            Passo(
                "Passo 1: instalar o Organic Maps",
                if (instalado) "Pronto: o Organic Maps está instalado." else null,
            ) {
                when {
                    instalado -> BotaoGrande("Abrir o Organic Maps", {
                        aberturaOrganicMaps(ctx)?.let(ctx::startActivity)
                    }, Modifier.fillMaxWidth())
                    apk != null -> {
                        Text("O instalador está no pen drive. Não precisa de internet.", style = MaterialTheme.typography.bodyLarge)
                        BotaoGrande(
                            if (copiando) "Copiando…" else "Instalar o Organic Maps",
                            { apk?.let(::instalar) },
                            Modifier.fillMaxWidth(),
                            habilitado = !copiando,
                        )
                    }
                    ssd.conectado -> Text(
                        "Este pen drive não tem o instalador. Com internet, instale o Organic Maps pela Play Store.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    else -> Text(
                        "Ligue o pen drive no celular e toque em \"Usar este pen drive\" na tela inicial: o instalador " +
                            "do Organic Maps está nele. Com internet, também dá para instalar pela Play Store.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                (situacaoInstalacao ?: aviso)?.takeIf { !instalado || situacaoInstalacao?.startsWith("Pronto") == true }?.let {
                    Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }

            // ---------- Organic Maps, passo 2 ----------
            Passo("Passo 2: passar os mapas para o Organic Maps", null) {
                Text(
                    "Atenção: o Organic Maps só mostra os estados que estão DENTRO do celular. Ele não lê o mapa " +
                        "direto do pen drive. Sem este passo, sem internet ele não mostra as ruas.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Os mapas dele, do Brasil inteiro, estão no pen drive, na pasta 2_Mapas › OrganicMaps_260928 (2,2 GB, todos os estados).",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text("Com um computador (uma vez só):", style = MaterialTheme.typography.titleMedium)
                Text(
                    "1. Abra o Organic Maps uma vez e feche (ele cria a pasta dos mapas).\n" +
                        "2. Ligue o celular no computador pelo cabo e escolha \"Transferência de arquivos\".\n" +
                        "3. No computador, abra: Celular › Armazenamento interno › Android › data › app.organicmaps.web (ou app.organicmaps) › files. " +
                        "Lá dentro há uma pasta com número (por exemplo 260928).\n" +
                        "4. Copie para dentro dessa pasta os arquivos .mwm da pasta 2_Mapas › OrganicMaps_260928 do pen drive: " +
                        "todos (2,2 GB) ou só os estados que quiser.\n" +
                        "5. Abra o Organic Maps: os estados copiados funcionam sem internet.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text("Sem computador, mas com internet:", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Abra o Organic Maps e baixe o mapa de cada estado que você pode precisar (de 30 a 200 MB cada).",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Se o computador não mostrar a pasta \"Android › data\", o Android do celular a esconde: use a opção com internet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * O mapa do Brasil do app: a mesma página do mapa da biblioteca do PC, servida pelo ServidorMapa (127.0.0.1),
 * lendo o pen drive. O WebView não sai para a internet: tudo que não for do próprio celular é recusado.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun TelaMapaBrasil(vm: AppVM) {
    val ctx = LocalContext.current
    var porta by remember { mutableStateOf<Int?>(null) }
    var pendente by remember { mutableStateOf<Pair<String, GeolocationPermissions.Callback>?>(null) }
    val pedirLocalizacao = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val ok = r.values.any { it }
        pendente?.let { (origem, cb) -> cb.invoke(origem, ok, false) }
        pendente = null
        if (!ok) vm.mensagem = "Sem a permissão de localização, o mapa funciona, mas não mostra onde você está."
    }
    LaunchedEffect(Unit) {
        porta = withContext(Dispatchers.IO) {
            try {
                vm.app.mapa.iniciar()
            } catch (e: Exception) {
                vm.app.diario.escrever("mapa: não liguei o servidor: $e")
                null
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Cabecalho("Mapa do Brasil", vm)
        val p = porta
        if (p == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    // Sem isto o WebView fica com a altura do conteúdo: o mapa mede "100% da tela" e vira 0.
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    WebView.setWebContentsDebuggingEnabled(
                        (c.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                    )
                    settings.javaScriptEnabled = true // MapLibre desenha o mapa em JavaScript
                    settings.domStorageEnabled = true // as marcas e a última vista ficam guardadas
                    settings.setGeolocationEnabled(true)
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                            if (r.url.host == "127.0.0.1") null
                            else WebResourceResponse(
                                "text/plain", "UTF-8", 403, "Sem internet", mapOf("Cache-Control" to "no-store"),
                                ByteArrayInputStream(ByteArray(0)),
                            )

                        override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                            vm.app.diario.escrever("mapa: falhou ${r.url}: ${e.errorCode} ${e.description}")
                        }

                        override fun onReceivedHttpError(v: WebView, r: WebResourceRequest, resp: WebResourceResponse) {
                            vm.app.diario.escrever("mapa: ${resp.statusCode} em ${r.url}")
                        }

                        override fun onPageFinished(v: WebView, url: String) {
                            vm.app.diario.escrever("mapa: página aberta ($url)")
                        }

                        // Link para fora (o crédito do OpenStreetMap, por exemplo): não abre nada.
                        override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = r.url.host != "127.0.0.1"
                    }
                    webChromeClient = object : WebChromeClient() {
                        // Os erros da página vão para o diário do app (Sobre › Copiar relatório).
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                                m.messageLevel() == ConsoleMessage.MessageLevel.WARNING
                            ) {
                                vm.app.diario.escrever("mapa js: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                            }
                            return false
                        }

                        override fun onGeolocationPermissionsShowPrompt(origem: String, cb: GeolocationPermissions.Callback) {
                            val tem = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                                .any { ContextCompat.checkSelfPermission(c, it) == PackageManager.PERMISSION_GRANTED }
                            if (tem) {
                                cb.invoke(origem, true, false)
                            } else {
                                pendente = origem to cb
                                pedirLocalizacao.launch(
                                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                                )
                            }
                        }
                    }
                    loadUrl("http://127.0.0.1:$p/mapa")
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

@Composable
private fun Passo(titulo: String, pronto: String?, conteudo: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleLarge)
            pronto?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            conteudo()
        }
    }
}
