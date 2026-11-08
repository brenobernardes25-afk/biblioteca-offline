package br.com.waltz.bibliotecadebolso.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.waltz.bibliotecadebolso.ia.EtapaIa
import br.com.waltz.bibliotecadebolso.ia.OrigemModelo
import br.com.waltz.bibliotecadebolso.ia.RegrasIa
import br.com.waltz.bibliotecadebolso.ia.Trecho
import br.com.waltz.bibliotecadebolso.zim.ServidorZim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private fun abrirFonte(vm: AppVM, f: Trecho) {
    if (f.livroId != null) {
        vm.ir(Tela.Leitor(ServidorZim.url(f.livroId, f.caminho), f.titulo, f.idioma != "PT"))
    } else {
        vm.ir(Tela.Pdf(f.caminho, f.titulo, f.pagina ?: 1, f.doSsd, f.idioma == "EN"))
    }
}

/** Seção 10. O número entre colchetes abre a fonte, e a resposta só usa o que está na biblioteca. */
@Composable
fun TelaPerguntar(vm: AppVM) {
    val ia = vm.app.ia
    val e by ia.estado.collectAsStateWithLifecycle()
    var pergunta by rememberSaveable { mutableStateOf(e.pergunta) }
    var origem by remember { mutableStateOf<OrigemModelo?>(null) }
    var conferiu by remember { mutableStateOf(false) }
    val resumo by vm.resumo.collectAsStateWithLifecycle()
    val modelo = ia.modelo
    LaunchedEffect(resumo.totais.prontos) {
        while (true) {
            origem = withContext(Dispatchers.IO) { ia.origem() }
            conferiu = true
            if (origem != null) break
            delay(5000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Cabecalho("Perguntar à IA", vm)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                modelo == null -> Text(
                    "Este celular não tem memória para a IA: precisa de 8 GB de RAM. Todo o resto do app funciona sem ela.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                !conferiu -> CircularProgressIndicator()
                origem == null -> PedirModelo(vm, modelo.tamanho)
                else -> {
                    Text(
                        "A IA responde só com o que está na biblioteca e mostra de onde tirou. Gasta bastante bateria e pode demorar um pouco.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = pergunta,
                        onValueChange = { pergunta = it },
                        label = { Text("Sua pergunta") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        minLines = 2,
                    )
                    if (e.etapa == EtapaIa.PARADA) {
                        BotaoGrande("Perguntar", { ia.perguntar(pergunta) }, habilitado = pergunta.isNotBlank())
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                            Text(
                                when (e.etapa) {
                                    EtapaIa.PROCURANDO -> "Procurando nas fontes…"
                                    EtapaIa.CARREGANDO -> "Carregando a IA… (pode levar um minuto)"
                                    else -> "Escrevendo a resposta…"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                        if (e.etapa == EtapaIa.ESCREVENDO) BotaoContorno("Parar", { ia.parar() }, Modifier.fillMaxWidth())
                    }
                    e.erro?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary) }
                    val naoAchou = e.resposta.startsWith(RegrasIa.NAO_ENCONTREI)
                    // Em saúde, o aviso vem antes da resposta: a IA pequena erra quantidades.
                    if (e.avisoSaude && e.resposta.isNotBlank() && !naoAchou) {
                        Text(
                            "A IA pode errar, principalmente em quantidades e doses. ${RegrasIa.AVISO_SAUDE} O texto original de cada fonte está logo abaixo.",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (e.resposta.isNotBlank()) Resposta(vm, e.resposta, e.fontes)
                    if (e.fontes.isNotEmpty() && !naoAchou && e.terminou) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        Text("Fontes", style = MaterialTheme.typography.titleLarge)
                        e.fontes.forEachIndexed { i, f -> CartaoFonte(vm, i + 1, f) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Resposta(vm: AppVM, texto: String, fontes: List<Trecho>) {
    val cor = MaterialTheme.colorScheme.primary
    val anotado = remember(texto, fontes, cor) {
        // Citação para fonte que não existe some, junto com o espaço antes dela.
        val limpo = Regex(""" ?\[(\d+)]""").replace(texto) { m ->
            if ((m.groupValues[1].toIntOrNull() ?: 0) in 1..fontes.size) m.value else ""
        }
        val texto = limpo
        buildAnnotatedString {
            var pos = 0
            for (m in Regex("\\[(\\d+)]").findAll(texto)) {
                val f = fontes.getOrNull(m.groupValues[1].toInt() - 1)
                append(texto.substring(pos, m.range.first))
                if (f != null) {
                    withLink(
                        LinkAnnotation.Clickable(
                            "fonte",
                            TextLinkStyles(SpanStyle(color = cor, fontWeight = FontWeight.Bold)),
                        ) { abrirFonte(vm, f) },
                    ) { append(m.value) }
                }
                // Número sem fonte: a IA inventou a citação. Some, para não parecer que há fonte.
                pos = m.range.last + 1
            }
            append(texto.substring(pos))
        }
    }
    SelectionContainer { Text(anotado, style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun CartaoFonte(vm: AppVM, n: Int, f: Trecho) {
    Surface(
        onClick = { abrirFonte(vm, f) },
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.surfaceVariant),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("[$n] ${f.titulo}", style = MaterialTheme.typography.titleMedium)
            Text(
                (f.pagina?.let { "Página $it · " } ?: "") + f.idioma + if (f.doSsd) " · no SSD" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // O trecho exato que a IA leu: dá para conferir sem sair da tela.
            Text(
                f.texto,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun PedirModelo(vm: AppVM, tamanho: String) {
    val r by vm.resumo.collectAsStateWithLifecycle()
    Text("A IA é opcional", style = MaterialTheme.typography.headlineMedium)
    Text(
        "Ela lê os manuais e enciclopédias e escreve a resposta em português, mostrando de onde tirou. Precisa de $tamanho a mais no celular e gasta bastante bateria.",
        style = MaterialTheme.typography.bodyLarge,
    )
    when {
        vm.app.prefs.iaId != null && r.versao != null -> Text(
            "A IA está na fila de downloads. Dá para acompanhar em Downloads e versão.",
            style = MaterialTheme.typography.titleMedium,
        )
        r.versao != null -> BotaoGrande("Baixar a IA ($tamanho)", { vm.app.ia.escolherBaixar() })
        else -> Text(
            "Para baixar, escolha antes uma versão do app, ou ligue um SSD que tenha a pasta 4_IA.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
