package br.com.waltz.bibliotecadebolso.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Agriculture
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cabin
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Egg
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.PestControl
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.waltz.bibliotecadebolso.busca.Atalho
import br.com.waltz.bibliotecadebolso.busca.Atalhos
import br.com.waltz.bibliotecadebolso.dados.Filtro
import br.com.waltz.bibliotecadebolso.dados.ItemBanco
import br.com.waltz.bibliotecadebolso.dados.Status
import br.com.waltz.bibliotecadebolso.download.Armazenamento
import br.com.waltz.bibliotecadebolso.download.Resumo
import br.com.waltz.bibliotecadebolso.util.bytesLegivel
import br.com.waltz.bibliotecadebolso.util.inteiro
import br.com.waltz.bibliotecadebolso.busca.ResultadoPdf
import br.com.waltz.bibliotecadebolso.ssd.SituacaoSsd
import br.com.waltz.bibliotecadebolso.zim.Origem
import br.com.waltz.bibliotecadebolso.zim.ResultadoZim
import br.com.waltz.bibliotecadebolso.zim.ServidorZim
import kotlinx.coroutines.delay

@Composable
fun Raiz(vm: AppVM) {
    BackHandler(enabled = vm.pilha.size > 1) { vm.voltar() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (val t = vm.tela) {
                Tela.Assistente -> Assistente(vm)
                Tela.Inicio -> Inicio(vm)
                Tela.Resultados -> Resultados(vm)
                is Tela.Leitor -> Leitor(vm, t)
                is Tela.Pdf -> LeitorPdf(vm, t)
                is Tela.Traduzir -> TelaTraduzir(vm, t)
                Tela.Downloads -> Downloads(vm)
                Tela.Ssd -> TelaSsd(vm)
                Tela.Montar -> TelaMontar(vm)
                Tela.Perguntar -> TelaPerguntar(vm)
                Tela.Mapa -> TelaMapa(vm)
                Tela.Sobre -> Sobre(vm)
                Tela.Manual -> TelaManual(vm)
                Tela.Importar -> TelaImportar(vm)
            }
        }
    }
    vm.mensagem?.let { m ->
        AlertDialog(
            onDismissRequest = { vm.mensagem = null },
            confirmButton = { TextButton(onClick = { vm.mensagem = null }) { Text("Entendi") } },
            text = { Text(m, style = MaterialTheme.typography.bodyLarge) },
        )
    }
}

// ---------- Peças comuns ----------

@Composable
internal fun Cabecalho(titulo: String, vm: AppVM) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { vm.voltar() }, modifier = Modifier.size(56.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
        }
        Text(titulo, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
}

@Composable
internal fun BotaoGrande(
    texto: String,
    aoTocar: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
) {
    Button(
        onClick = aoTocar,
        enabled = habilitado,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp),
    ) { Text(texto, style = MaterialTheme.typography.labelLarge) }
}

@Composable
internal fun BotaoContorno(
    texto: String,
    aoTocar: () -> Unit,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
) {
    OutlinedButton(
        onClick = aoTocar,
        enabled = habilitado,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.heightIn(min = 56.dp),
    ) { Text(texto, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun Selo(texto: String) {
    Text(
        texto,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun CaixaBusca(vm: AppVM) {
    val teclado = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = vm.texto,
        onValueChange = { vm.texto = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        placeholder = { Text("Ex.: como purificar água", style = MaterialTheme.typography.bodyLarge) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            teclado?.hide()
            vm.buscarTexto()
        }),
        shape = MaterialTheme.shapes.medium,
    )
}

// ---------- Assistente (seção 5) ----------

@Composable
private fun Assistente(vm: AppVM) {
    var passo by rememberSaveable { mutableStateOf(1) }
    var escolha by rememberSaveable { mutableStateOf<String?>(null) }
    var soWifi by rememberSaveable { mutableStateOf(true) }
    var comIa by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = passo > 1) { passo-- }
    LaunchedEffect(Unit) { vm.atualizarEspaco() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Passo $passo de 3", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        when (passo) {
            1 -> {
                Text("Biblioteca de Bolso", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Manuais de saúde, água e sobrevivência, Embrapa e enciclopédias no seu celular.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Uma busca só para tudo, sempre mostrando de onde veio a informação.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "Depois de baixado, funciona sem internet. Sem conta, sem anúncio, sem rastreio.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(12.dp))
                BotaoGrande("Começar", { passo = 2 })
                TextButton(
                    onClick = { vm.ir(Tela.Manual) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Ver o manual") }
            }
            2 -> {
                Text("Qual versão você quer?", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (vm.manifestoPronto) "Este celular tem ${bytesLegivel(vm.livreCelular)} livres."
                    else "Conferindo o espaço livre…",
                    style = MaterialTheme.typography.bodyLarge,
                )
                OpcaoVersao(
                    "Slim mínimo", "13 GB, no celular",
                    "Saúde, água, sobrevivência e o essencial da Embrapa (cerca de 400 títulos).",
                    vm.faltaMinimo, vm.livreCelular, vm.manifestoPronto, escolha == "minimo",
                ) { escolha = "minimo" }
                OpcaoVersao(
                    "Slim", "78 GB, no celular",
                    "Tudo do mínimo, mais a Wikipédia em português completa, manuais do Exército dos EUA, consertos, fóruns práticos e muito mais Embrapa.",
                    vm.faltaSlim, vm.livreCelular, vm.manifestoPronto, escolha == "slim",
                ) { escolha = "slim" }
                CartaoOpcao(
                    "Completo", "~830 GB, num SSD externo",
                    "A biblioteca inteira, igual à do computador. Não ocupa o celular, salvo o índice de busca (cerca de 7,5 GB).",
                    aviso = null, habilitado = true, selecionado = false,
                ) {
                    vm.app.prefs.assistenteConcluido = true
                    vm.irParaInicio()
                    vm.ir(Tela.Ssd)
                }
                vm.app.ia.modelo?.let { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Switch) { comIa = !comIa }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("+ IA (mais ${m.tamanho})", style = MaterialTheme.typography.titleMedium)
                            Text("Opcional. Responde em português com base na biblioteca e mostra de onde tirou. Gasta bastante bateria.", style = MaterialTheme.typography.bodyMedium)
                        }
                        Switch(checked = comIa, onCheckedChange = { comIa = it })
                    }
                }
                BotaoGrande("Continuar", { passo = 3 }, habilitado = escolha != null)
                TextButton(
                    onClick = { vm.ir(Tela.Importar) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) { Text("Já preparei a pasta no computador") }
            }
            else -> {
                Text("Como baixar", style = MaterialTheme.typography.headlineMedium)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Switch) { soWifi = !soWifi }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Só no Wi-Fi", style = MaterialTheme.typography.titleMedium)
                        Text("Para não gastar seu plano de dados.", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = soWifi, onCheckedChange = { soWifi = it })
                }
                Text("Pode levar horas. Deixe o celular carregando se puder.", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Dá para usar o app enquanto baixa: a busca já encontra o que chegou. Se a internet cair ou você reiniciar o celular, o download continua de onde parou.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                val permissao = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    vm.escolherVersao(escolha, soWifi, comIa)
                }
                BotaoGrande("Começar a baixar", {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissao.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.escolherVersao(escolha, soWifi, comIa)
                    }
                })
                Text(
                    "O celular vai pedir permissão de notificação: é só para mostrar o progresso.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun OpcaoVersao(
    nome: String,
    tamanho: String,
    descricao: String,
    falta: Long,
    livre: Long,
    pronto: Boolean,
    selecionado: Boolean,
    aoTocar: () -> Unit,
) {
    val necessario = falta + Armazenamento.RESERVA
    val cabe = !pronto || livre >= necessario
    CartaoOpcao(
        nome, tamanho, descricao,
        aviso = if (cabe) null else "Não cabe: faltam ${bytesLegivel(necessario - livre)} de espaço livre.",
        habilitado = cabe && pronto,
        selecionado = selecionado,
        aoTocar = aoTocar,
    )
}

@Composable
private fun CartaoOpcao(
    nome: String,
    tamanho: String,
    descricao: String,
    aviso: String?,
    habilitado: Boolean,
    selecionado: Boolean,
    aoTocar: () -> Unit,
) {
    val cores = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selecionado) cores.surfaceVariant else cores.surface,
        border = BorderStroke(if (selecionado) 3.dp else 1.dp, if (selecionado) cores.primary else cores.outline),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = habilitado, role = Role.RadioButton, onClick = aoTocar),
    ) {
        val cor = if (habilitado) cores.onSurface else cores.onSurfaceVariant
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(nome, style = MaterialTheme.typography.titleLarge, color = cor)
            Text(tamanho, style = MaterialTheme.typography.titleMedium, color = cor)
            Text(descricao, style = MaterialTheme.typography.bodyMedium, color = cor)
            aviso?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, color = cores.error)
            }
        }
    }
}

// ---------- Início ----------

private fun iconeDe(a: String): ImageVector = when (a) {
    "agua" -> Icons.Filled.WaterDrop
    "socorro" -> Icons.Filled.MedicalServices
    "picada" -> Icons.Filled.PestControl
    "fogo" -> Icons.Filled.LocalFireDepartment
    "abrigo" -> Icons.Filled.Cabin
    "plantio" -> Icons.Filled.Grass
    "criacao" -> Icons.Filled.Egg
    "alimentos" -> Icons.Filled.Kitchen
    "plantas" -> Icons.Filled.Spa
    "consertos" -> Icons.Filled.Build
    else -> Icons.Filled.Agriculture
}

@Composable
private fun Inicio(vm: AppVM) {
    val ssd by vm.app.ssd.estado.collectAsStateWithLifecycle()
    val resumo by vm.resumo.collectAsStateWithLifecycle()
    LaunchedEffect(resumo.totais.prontos) { vm.atualizarLivros() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Biblioteca de Bolso", style = MaterialTheme.typography.headlineMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (resumo.versao == null && ssd.situacao != SituacaoSsd.NENHUM) "Completo · no SSD" else rotuloVersao(resumo),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.ir(Tela.Downloads) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.Download, contentDescription = "Downloads e versão")
                }
                IconButton(onClick = { vm.ir(Tela.Ssd) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.Storage, contentDescription = "SSD externo")
                }
                IconButton(onClick = { vm.ir(Tela.Manual) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Help, contentDescription = "Manual")
                }
                IconButton(onClick = { vm.ir(Tela.Sobre) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.Info, contentDescription = "Sobre")
                }
            }
        }
        if (ssd.situacao != SituacaoSsd.NENHUM) {
            item {
                Surface(
                    onClick = { vm.ir(Tela.Ssd) },
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, if (ssd.conectado) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.secondary),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when (ssd.situacao) {
                            SituacaoSsd.CONECTADO -> "Completo · SSD conectado"
                            SituacaoSsd.ABRINDO -> "Completo · abrindo o SSD (${inteiro(ssd.zimsAbertos)} de ${inteiro(ssd.zimsTotal)})"
                            else -> "SSD desconectado: conecte de novo para ver o conteúdo completo"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }
        item { CaixaBusca(vm) }
        // A IA só aparece em quem pode usar: o app é completo sem ela.
        if (vm.app.ia.modelo != null) {
            item { BotaoContorno("Perguntar à IA", { vm.ir(Tela.Perguntar) }, Modifier.fillMaxWidth()) }
        }
        if (resumo.versao != null && !resumo.terminou) item { CartaoProgresso(vm, resumo) }
        if (resumo.versao != null && resumo.terminou && resumo.totais.falhos > 0) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.filtro = Filtro.FALHOU; vm.ir(Tela.Downloads) },
                ) {
                    Text(
                        "${inteiro(resumo.totais.falhos)} arquivos não baixaram. Toque para ver o que faltou.",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        if (resumo.versao == null && ssd.situacao == SituacaoSsd.NENHUM) {
            item {
                BotaoContorno(
                    "Escolher uma versão para baixar",
                    { vm.ir(Tela.Assistente) },
                    Modifier.fillMaxWidth(),
                )
            }
        }
        val pares = (Atalhos.todos.map<Atalho, Atalho?> { it } + listOf(null)).chunked(2)
        items(pares) { par ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                par.forEach { a ->
                    BotaoAtalho(
                        nome = a?.nome ?: "Mapa",
                        icone = a?.let { iconeDe(it.icone) } ?: Icons.Filled.Map,
                        modifier = Modifier.weight(1f),
                    ) { if (a != null) vm.buscarAtalho(a) else vm.ir(Tela.Mapa) }
                }
                if (par.size == 1) {
                    BotaoAtalho("Traduzir", Icons.Filled.Translate, Modifier.weight(1f)) { vm.ir(Tela.Traduzir()) }
                }
            }
        }
        if (vm.livros.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text("Fontes prontas (${vm.livros.size})", style = MaterialTheme.typography.titleLarge)
            }
            items(vm.livros, key = { it.id }) { l ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { vm.abrirPaginaInicial(l) }
                        .heightIn(min = 52.dp)
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Selo(l.idioma)
                    Text(l.titulo, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
    }
}

private fun rotuloVersao(r: Resumo): String = when (r.versao) {
    "minimo" -> "Slim mínimo · 13 GB"
    "slim" -> "Slim · 78 GB"
    else -> "Nenhuma versão baixada"
}

@Composable
private fun BotaoAtalho(nome: String, icone: ImageVector, modifier: Modifier, aoTocar: () -> Unit) {
    Surface(
        onClick = aoTocar,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = modifier.heightIn(min = 96.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icone, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(32.dp))
            Text(nome, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun CartaoProgresso(vm: AppVM, r: Resumo) {
    Surface(
        onClick = { vm.ir(Tela.Downloads) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(r.motivoEspera ?: "Baixando", style = MaterialTheme.typography.titleMedium)
            val t = r.totais
            LinearProgressIndicator(
                progress = { if (t.bytesTotal > 0) (t.bytesFeitos.toFloat() / t.bytesTotal) else 0f },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
            Text(r.linha(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---------- Resultados (seção 8) ----------

private const val MANUAIS_NO_COMECO = 5

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Resultados(vm: AppVM) {
    var idioma by rememberSaveable { mutableStateOf<String?>(null) }
    var tema by rememberSaveable { mutableStateOf<String?>(null) }
    var todosManuais by rememberSaveable(vm.manuais) { mutableStateOf(false) }
    val estadoSsd by vm.app.ssd.estado.collectAsStateWithLifecycle()
    val ssdFora = estadoSsd.situacao == SituacaoSsd.DESCONECTADO
    val b = vm.busca
    val filtrados = b.resultados.filter { (idioma == null || it.idioma == idioma) && (tema == null || it.tema == tema) }
    val manuais = vm.manuais.filter { (idioma == null || it.idioma == idioma) && (tema == null || it.tema == tema) }
    val temas = (b.resultados.map { it.tema } + vm.manuais.map { it.tema }).distinct().sorted()

    Column(Modifier.fillMaxSize()) {
        Cabecalho("Resultados", vm)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { CaixaBusca(vm) }
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Switch) { vm.mudarIngles(!vm.incluirIngles) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Incluir fontes em inglês", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = vm.incluirIngles, onCheckedChange = { vm.mudarIngles(it) })
                }
            }
            if (b.resultados.isNotEmpty() || vm.manuais.isNotEmpty()) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("PT", "EN").forEach { i ->
                            FilterChip(
                                selected = idioma == i,
                                onClick = { idioma = if (idioma == i) null else i },
                                label = { Text(if (i == "PT") "Português" else "Inglês") },
                            )
                        }
                        temas.forEach { t ->
                            FilterChip(
                                selected = tema == t,
                                onClick = { tema = if (tema == t) null else t },
                                label = { Text(t) },
                            )
                        }
                    }
                }
            }
            if (ssdFora && (manuais.isNotEmpty() || filtrados.isNotEmpty())) {
                item {
                    Text(
                        "SSD desconectado: estes resultados são só os do celular.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            if (manuais.isNotEmpty()) {
                item { Text("Manuais", style = MaterialTheme.typography.titleLarge) }
                items(if (todosManuais) manuais else manuais.take(MANUAIS_NO_COMECO), key = { "pdf/" + it.caminho }) { r -> CartaoManual(vm, r) }
                if (!todosManuais && manuais.size > MANUAIS_NO_COMECO) {
                    item {
                        BotaoContorno("Ver todos os ${manuais.size} manuais", { todosManuais = true }, Modifier.fillMaxWidth())
                    }
                }
            }
            if (vm.buscando) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                        Text("Procurando em ${b.fontesFeitas} de ${b.fontesTotal} fontes…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (filtrados.isNotEmpty()) {
                item { Text("Enciclopédias", style = MaterialTheme.typography.titleLarge) }
                items(filtrados, key = { it.livroId + "/" + it.caminho }) { r -> CartaoResultado(vm, r) }
            }
            if (!vm.buscando && !vm.buscandoManuais && filtrados.isEmpty() && manuais.isEmpty()) {
                item {
                    Text(
                        when {
                            ssdFora && b.fontesTotal == 0 && vm.pdfsProntos == 0 -> "SSD desconectado: conecte de novo para procurar no conteúdo completo."
                            b.fontesTotal == 0 && vm.pdfsProntos == 0 -> "Ainda não há nada pronto. A busca passa a encontrar assim que os primeiros arquivos chegarem."
                            b.resultados.isNotEmpty() || vm.manuais.isNotEmpty() -> "Nenhum resultado com esses filtros."
                            else -> "Não achei nada no que já está pronto. Tente outras palavras, com menos termos."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            if (!vm.buscando && b.lentas.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (b.lentas.size > 5) "${b.lentas.size} fontes demoraram e ficaram de fora." else "${b.lentas.size} fontes demoraram e ficaram de fora: ${b.lentas.joinToString(", ")}.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        BotaoContorno("Buscar de novo", { vm.buscarDeNovo() }, Modifier.fillMaxWidth())
                    }
                }
            }
            if (!vm.buscandoManuais && !vm.indicePronto && vm.pdfsProntos > 0) {
                item {
                    Text(
                        "O índice dos manuais ainda não chegou: por enquanto, os manuais prontos (${inteiro(vm.pdfsProntos)}) são procurados só pelo título.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CartaoManual(vm: AppVM, r: ResultadoPdf) {
    val ctx = LocalContext.current
    val destaque = TextLinkStyles(SpanStyle())
    val trecho = remember(r.trecho) { r.trecho?.let { AnnotatedString.fromHtml(escaparTrecho(it), linkStyles = destaque) } }
    Surface(
        onClick = {
            if (r.tipo == "pdf") vm.ir(Tela.Pdf(r.caminho, r.titulo, r.pagina, r.origem == Origem.SSD, r.idioma == "EN"))
            else abrirForaDoApp(ctx, vm, r)
        },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.titulo, style = MaterialTheme.typography.titleMedium)
            trecho?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Selo(r.idioma)
                Text(
                    (if (r.trecho != null) "Página ${r.pagina} · " else "") + "${r.tema} · ${r.origem.rotulo}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
    }
}

/** EPUB e texto do SSD: quem abre é outro app. */
private fun abrirForaDoApp(ctx: android.content.Context, vm: AppVM, r: ResultadoPdf) {
    val uri = vm.app.ssd.uriDocumento(r.caminho)
    if (uri == null) {
        vm.mensagem = "SSD desconectado: conecte de novo para abrir este arquivo."
        return
    }
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: android.content.ActivityNotFoundException) {
        vm.mensagem = "Não há app neste celular para abrir este tipo de arquivo."
    }
}

/** O texto do PDF é só texto: apenas o destaque que a busca pôs vira formatação. */
private fun escaparTrecho(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("&lt;b&gt;", "<b>").replace("&lt;/b&gt;", "</b>")

@Composable
private fun CartaoResultado(vm: AppVM, r: ResultadoZim) {
    val destaque = TextLinkStyles(SpanStyle())
    val trecho = remember(r.trecho) { r.trecho?.let { AnnotatedString.fromHtml(it, linkStyles = destaque) } }
    Surface(
        onClick = { vm.ir(Tela.Leitor(ServidorZim.url(r.livroId, r.caminho), "${r.livro} · ${r.origem.rotulo}", r.idioma != "PT")) },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(r.titulo, style = MaterialTheme.typography.titleMedium)
            trecho?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Selo(r.idioma)
                Text(
                    "${r.livro} · ${r.origem.rotulo}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Normal,
                )
            }
        }
    }
}

// ---------- Downloads e versão ----------

@Composable
private fun Downloads(vm: AppVM) {
    val r by vm.resumo.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var dialogoTroca by remember { mutableStateOf(false) }

    LaunchedEffect(vm.filtro) {
        while (true) {
            vm.atualizarItens()
            delay(2000)
        }
    }
    LaunchedEffect(r.versao) { vm.atualizarSobra() }

    Column(Modifier.fillMaxSize()) {
        Cabecalho("Downloads e versão", vm)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(rotuloVersao(r), style = MaterialTheme.typography.titleLarge)
                    if (r.versao != null) {
                        val t = r.totais
                        LinearProgressIndicator(
                            progress = { if (t.bytesTotal > 0) (t.bytesFeitos.toFloat() / t.bytesTotal) else 0f },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                        )
                        Text(r.linha(), style = MaterialTheme.typography.bodyMedium)
                        r.motivoEspera?.let {
                            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                    Text(
                        "Espaço livre no celular: ${bytesLegivel(Armazenamento.livre(ctx))}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (r.pausado) {
                        BotaoGrande("Retomar", { vm.app.controle.retomar() }, Modifier.weight(1f))
                    } else {
                        BotaoContorno("Pausar", { vm.app.controle.pausar() }, Modifier.weight(1f), habilitado = !r.terminou && r.versao != null)
                    }
                    BotaoContorno("Tentar de novo", { vm.app.controle.tentarDeNovo() }, Modifier.weight(1f), habilitado = r.totais.falhos > 0)
                }
            }
            item {
                var soWifi by remember { mutableStateOf(vm.app.prefs.soWifi) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Switch) { soWifi = !soWifi; vm.app.controle.mudarSoWifi(soWifi) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Só no Wi-Fi", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = soWifi, onCheckedChange = { soWifi = it; vm.app.controle.mudarSoWifi(it) })
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    BotaoContorno(
                        if (vm.atualizando) "Conferindo…" else "Atualizar biblioteca",
                        { vm.atualizarBiblioteca() }, Modifier.weight(1f), habilitado = !vm.atualizando && r.versao != null,
                    )
                    BotaoContorno("Trocar de versão", { dialogoTroca = true }, Modifier.weight(1f))
                }
            }
            item {
                BotaoContorno("Trazer do computador", { vm.ir(Tela.Importar) }, Modifier.fillMaxWidth())
            }
            if (vm.sobra.isNotEmpty()) {
                item {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val bytes = vm.sobra.sumOf { it.tamEfetivo }
                            Text(
                                "${inteiro(vm.sobra.size)} arquivos (${bytesLegivel(bytes)}) não fazem parte desta versão. Eles seguem funcionando até você apagar.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            BotaoContorno("Apagar e liberar espaço", { vm.apagarSobra() }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        Filtro.ANDAMENTO to "Agora",
                        Filtro.FALTA to "Falta",
                        Filtro.FALHOU to "Faltou",
                        Filtro.PRONTOS to "Prontos",
                    ).forEach { (f, nome) ->
                        FilterChip(selected = vm.filtro == f, onClick = { vm.filtro = f }, label = { Text(nome) })
                    }
                }
            }
            if (vm.itens.isEmpty()) {
                item {
                    Text(
                        when (vm.filtro) {
                            Filtro.ANDAMENTO -> "Nada baixando agora."
                            Filtro.FALTA -> "Não falta nada."
                            Filtro.FALHOU -> "Nenhum arquivo falhou."
                            Filtro.PRONTOS -> "Nenhum arquivo pronto ainda."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            items(vm.itens, key = { it.id }) { LinhaItem(it) }
        }
    }

    if (dialogoTroca) {
        AlertDialog(
            onDismissRequest = { dialogoTroca = false },
            title = { Text("Trocar de versão") },
            text = {
                Text(
                    "Do mínimo para o Slim, o app baixa só a diferença. Do Slim para o mínimo, nada se apaga sem você mandar.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                val outra = if (r.versao == "slim") "minimo" else "slim"
                TextButton(onClick = {
                    dialogoTroca = false
                    if (outra == "slim" && Armazenamento.livre(ctx) < vm.app.banco.faltaBaixar("slim") + Armazenamento.RESERVA) {
                        vm.mensagem = "Não cabe: o Slim precisa de mais ${bytesLegivel(vm.app.banco.faltaBaixar("slim") + Armazenamento.RESERVA - Armazenamento.livre(ctx))} de espaço livre."
                    } else {
                        vm.trocarVersao(outra)
                    }
                }) { Text(if (r.versao == "slim") "Usar o Slim mínimo" else "Usar o Slim") }
            },
            dismissButton = { TextButton(onClick = { dialogoTroca = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun LinhaItem(i: ItemBanco) {
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(i.titulo, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val situacao = when (i.status) {
            Status.PRONTO -> "Pronto"
            Status.FALHOU -> "Falhou"
            Status.BAIXANDO -> "Baixando"
            else -> if (i.baixados > 0) "Parado no meio" else "Na fila"
        }
        val progresso = if (i.baixados > 0 && i.status != Status.PRONTO) "${bytesLegivel(i.baixados)} de " else ""
        Text(
            "$situacao · $progresso${bytesLegivel(i.tamEfetivo)} · ${i.tema}",
            style = MaterialTheme.typography.labelMedium,
            color = if (i.status == Status.FALHOU) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (i.status == Status.BAIXANDO && i.tamEfetivo > 0) {
            LinearProgressIndicator(
                progress = { i.baixados.toFloat() / i.tamEfetivo },
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
        i.erro?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

// ---------- SSD: prova da fase 1 ----------

// ---------- Mapa (seção 11) ----------

@Composable
private fun TelaMapa(vm: AppVM) {
    val ctx = LocalContext.current
    val instalado = remember {
        ctx.packageManager.getLaunchIntentForPackage("app.organicmaps") != null
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
            if (instalado) {
                Text("Os mapas ficam no Organic Maps, que funciona sem internet.", style = MaterialTheme.typography.bodyLarge)
                BotaoGrande("Abrir o Organic Maps", {
                    ctx.packageManager.getLaunchIntentForPackage("app.organicmaps")?.let(ctx::startActivity)
                })
            } else {
                Text("Mapas sem internet", style = MaterialTheme.typography.headlineMedium)
                Text("Enquanto ainda tiver internet:", style = MaterialTheme.typography.bodyLarge)
                Text("1. Instale o app Organic Maps, que é gratuito.", style = MaterialTheme.typography.bodyLarge)
                Text("2. Abra o app e baixe o mapa do seu estado (de 30 a 200 MB cada).", style = MaterialTheme.typography.bodyLarge)
                Text("3. Pronto: depois é só tocar em Mapa aqui, mesmo sem internet.", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// ---------- Sobre ----------

@Composable
private fun Sobre(vm: AppVM) {
    val ctx = LocalContext.current
    val fontes = listOf(
        "Wikipédia, Wikilivros, Wikcionário e Wikisource" to "CC BY-SA",
        "Hesperian (Onde não há médico)" to "CC BY-NC-SA",
        "Stack Exchange" to "CC BY-SA",
        "Embrapa e Ministério da Saúde" to "publicações oficiais do Brasil",
        "Manuais militares dos EUA" to "domínio público",
        "Kiwix (libkiwix e libzim)" to "GPL-3.0",
        "Tradução: Bergamot e Firefox Translations, da Mozilla" to "MPL-2.0",
        "IA: llama.cpp" to "MIT",
        "IA: modelos Qwen3" to "Apache-2.0",
    )
    Column(Modifier.fillMaxSize()) {
        Cabecalho("Sobre e licenças", vm)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Biblioteca de Bolso", style = MaterialTheme.typography.headlineMedium)
            Text("Uso pessoal e gratuito. Não substitui atendimento médico.", style = MaterialTheme.typography.titleMedium)
            Text("App de código aberto, licença GPL-3.0. Sem anúncios, sem analytics, sem login.", style = MaterialTheme.typography.bodyLarge)
            Text("@breno.paiva40 no Instagram", style = MaterialTheme.typography.bodyLarge)
            BotaoContorno("Abrir o manual", { vm.ir(Tela.Manual) }, Modifier.fillMaxWidth())
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Text("Fontes", style = MaterialTheme.typography.titleLarge)
            fontes.forEach { (nome, licenca) ->
                Column {
                    Text(nome, style = MaterialTheme.typography.bodyLarge)
                    Text(licenca, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Text(
                "Se algo der errado, exporte os logs e mande para o Breno. Eles ficam só no seu celular até você compartilhar.",
                style = MaterialTheme.typography.bodyLarge,
            )
            BotaoContorno("Exportar os logs", {
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.arquivos", vm.app.diario.arquivo)
                ctx.startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        "Exportar os logs",
                    )
                )
            }, Modifier.fillMaxWidth())
            Spacer(Modifier.width(1.dp))
        }
    }
}

// ---------- Manual ----------

@Composable
private fun TelaManual(vm: AppVM) {
    Column(Modifier.fillMaxSize()) {
        Cabecalho("Manual", vm)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(MANUAL) { (titulo, texto) ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(titulo, style = MaterialTheme.typography.titleLarge)
                    Text(texto, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

// ---------- Trazer do computador ----------

/** O seletor já abre na pasta certa: a pessoa só confirma. Se ela não existir, ele abre onde costuma abrir. */
private val PASTA_INICIAL_IMPORTACAO: android.net.Uri = android.net.Uri.parse(
    "content://com.android.externalstorage.documents/document/primary%3ADownload%2FBibliotecaDeBolso"
)

@Composable
private fun TelaImportar(vm: AppVM) {
    val e by vm.app.importador.estado.collectAsStateWithLifecycle()
    var apagar by rememberSaveable { mutableStateOf(true) }
    val escolher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.app.importador.iniciar(uri, apagar)
    }
    // A cópia roda enquanto o app está aberto: a tela fica acesa para o Android não interromper.
    val visao = LocalView.current
    DisposableEffect(e.rodando) {
        visao.keepScreenOn = e.rodando
        onDispose { visao.keepScreenOn = false }
    }
    DisposableEffect(Unit) { onDispose { vm.app.importador.limparResultado() } }
    Column(Modifier.fillMaxSize()) {
        Cabecalho("Trazer do computador", vm)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                e.rodando -> {
                    Text("Copiando…", style = MaterialTheme.typography.headlineMedium)
                    Text(e.etapa, style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(
                        progress = { if (e.bytesTotal > 0) e.bytesFeitos.toFloat() / e.bytesTotal else 0f },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                    if (e.total > 0) {
                        Text(
                            "${inteiro(e.feitos)} de ${inteiro(e.total)} · ${bytesLegivel(e.bytesFeitos)} de ${bytesLegivel(e.bytesTotal)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text("Deixe esta tela aberta e não tire o cabo.", style = MaterialTheme.typography.titleMedium)
                    BotaoContorno("Parar", { vm.app.importador.parar() }, Modifier.fillMaxWidth())
                }
                e.terminou -> {
                    Text("Pronto", style = MaterialTheme.typography.headlineMedium)
                    Text(if (e.copiados == 1) "1 arquivo veio do computador." else "${inteiro(e.copiados)} arquivos vieram do computador.", style = MaterialTheme.typography.bodyLarge)
                    if (e.rejeitados > 0) {
                        Text(if (e.rejeitados == 1) "1 veio com defeito e não foi guardado." else "${inteiro(e.rejeitados)} vieram com defeito e não foram guardados.", style = MaterialTheme.typography.bodyLarge)
                    }
                    if (e.faltam > 0) {
                        Text(
                            "Ainda faltam ${inteiro(e.faltam)}. O app baixa pela internet quando puder. Dá para acompanhar em Downloads e versão.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    BotaoGrande("Ir para a biblioteca", { vm.irParaInicio() })
                }
                else -> {
                    Text(
                        "Use a pasta BibliotecaDeBolso que o PREPARAR_CELULAR.bat montou no computador. Tem o SSD da biblioteca completa? Use a opção SSD externo, no início.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "Copie essa pasta para a pasta Download do celular, ou ligue o pendrive onde ela está. Depois toque no botão e escolha a pasta.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    e.erro?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                    if (e.ehSsd) {
                        BotaoGrande("Usar o SSD", { vm.irParaInicio(); vm.ir(Tela.Ssd) })
                        return@Column
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Switch) { apagar = !apagar }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Apagar da pasta depois de copiar", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Para não ocupar o espaço duas vezes. Só apaga o que já foi copiado e conferido.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Switch(checked = apagar, onCheckedChange = { apagar = it })
                    }
                    BotaoGrande("Escolher a pasta", { escolher.launch(PASTA_INICIAL_IMPORTACAO) })
                }
            }
        }
    }
}
