package br.com.waltz.bibliotecadebolso.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.waltz.bibliotecadebolso.ssd.ResultadoTeste
import br.com.waltz.bibliotecadebolso.ssd.SituacaoIndiceSsd
import br.com.waltz.bibliotecadebolso.ssd.SituacaoSsd
import br.com.waltz.bibliotecadebolso.util.bytesLegivel
import br.com.waltz.bibliotecadebolso.util.inteiro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val ESPACO_COMPLETO = 900_000_000_000L

@Composable
fun TelaSsd(vm: AppVM) {
    val e by vm.app.ssd.estado.collectAsStateWithLifecycle()
    val m by vm.app.montagem.estado.collectAsStateWithLifecycle()
    var erro by remember { mutableStateOf<String?>(null) }
    val escolher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) erro = vm.app.ssd.escolher(uri)
    }
    LaunchedEffect(Unit) { vm.app.ssd.reconectar() }

    Column(Modifier.fillMaxSize()) {
        Cabecalho("SSD externo", vm)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (e.situacao) {
                SituacaoSsd.NENHUM -> {
                    Text("A biblioteca inteira, num SSD", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Cerca de 830 GB: tudo que a versão do computador tem. O mesmo SSD funciona no notebook e no celular.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                SituacaoSsd.DESCONECTADO -> {
                    Text("SSD desconectado", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.secondary)
                    Text(
                        e.aviso ?: "Conecte de novo para ver o conteúdo completo. Não precisa escolher a pasta outra vez.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                SituacaoSsd.ABRINDO, SituacaoSsd.CONECTADO -> {
                    Text(
                        if (e.situacao == SituacaoSsd.CONECTADO) "SSD conectado" else "Abrindo o SSD…",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        "Enciclopédias: ${inteiro(e.zimsAbertos)} de ${inteiro(e.zimsTotal)}" +
                            if (e.zimsRuins > 0) " (${inteiro(e.zimsRuins)} com defeito)" else "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    when (e.indice) {
                        SituacaoIndiceSsd.PRONTO -> Text("Busca nos manuais: pronta.", style = MaterialTheme.typography.bodyLarge)
                        SituacaoIndiceSsd.COPIANDO -> {
                            Text(
                                "Copiando o índice de busca para o celular: ${bytesLegivel(e.indiceFeito)} de ${bytesLegivel(e.indiceTotal)}. " +
                                    "Só acontece na primeira vez. Deixe o app aberto.",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            LinearProgressIndicator(
                                progress = { if (e.indiceTotal > 0) e.indiceFeito.toFloat() / e.indiceTotal else 0f },
                                modifier = Modifier.fillMaxWidth().height(8.dp),
                            )
                        }
                        SituacaoIndiceSsd.SEM_ESPACO -> Text(
                            "A busca nos manuais precisa de ${bytesLegivel(e.indiceTotal)} livres no celular. Libere espaço e conecte o SSD de novo. As enciclopédias funcionam mesmo assim.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        SituacaoIndiceSsd.AUSENTE -> Text(
                            "Este SSD ainda não tem o índice de busca dos manuais. Abra a biblioteca uma vez no computador para ele ser criado. As enciclopédias funcionam mesmo assim.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        SituacaoIndiceSsd.FALHOU -> Text(
                            "A cópia do índice parou. Confira o cabo: ela continua quando o SSD for conectado de novo.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
            erro?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary) }
            if (e.situacao == SituacaoSsd.NENHUM) {
                BotaoGrande("Já tenho o SSD com a biblioteca", { erro = null; escolher.launch(null) })
                Text(
                    "Ligue o SSD no USB-C e escolha a pasta principal dele, onde estão 1_Enciclopedia e 5_Acervo. O app só lê: não grava nada no SSD.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                BotaoContorno("Esquecer este SSD", { vm.app.ssd.esquecer() }, Modifier.fillMaxWidth())
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            if (m.ativa) {
                BotaoGrande("Ver a montagem do SSD", { vm.ir(Tela.Montar) })
            } else {
                Text("Não tem a biblioteca ainda?", style = MaterialTheme.typography.titleLarge)
                BotaoContorno("Montar a biblioteca completa neste SSD", { vm.ir(Tela.Montar) }, Modifier.fillMaxWidth())
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            QueSsdUsar()
        }
    }
}

@Composable
private fun QueSsdUsar() {
    Text("Que SSD usar?", style = MaterialTheme.typography.titleLarge)
    Text("SSD portátil USB-C (SanDisk, Samsung T7, Kingston, Crucial): funciona na maioria dos celulares.", style = MaterialTheme.typography.bodyLarge)
    Text("HD mecânico de 2,5\": muitas vezes não liga sem energia extra (hub com fonte).", style = MaterialTheme.typography.bodyLarge)
    Text("HD de 3,5\" (de computador de mesa): não funciona no celular.", style = MaterialTheme.typography.bodyLarge)
    Text("Precisa de celular com USB-C com OTG, que é quase todo Android recente.", style = MaterialTheme.typography.bodyLarge)
}

/** Seção 6.2: os avisos vêm antes de tudo, e o app nunca formata o SSD. */
@Composable
fun TelaMontar(vm: AppVM) {
    val m by vm.app.montagem.estado.collectAsStateWithLifecycle()
    val escopo = rememberCoroutineScope()
    var teste by remember { mutableStateOf<Pair<Uri, ResultadoTeste>?>(null) }
    var testando by remember { mutableStateOf(false) }
    val escolher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            testando = true
            escopo.launch {
                val r = withContext(Dispatchers.IO) { vm.app.montagem.testar(uri) }
                testando = false
                if (r.gravou && r.acima4Gb && r.total >= ESPACO_COMPLETO) vm.app.montagem.comecar(uri, false) else teste = uri to r
            }
        }
    }
    LaunchedEffect(Unit) { vm.app.montagem.atualizarTotais() }

    Column(Modifier.fillMaxSize()) {
        Cabecalho("Montar a biblioteca", vm)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (m.ativa) {
                val t = m.totais
                Text(m.etapa.ifBlank { "Montando" }, style = MaterialTheme.typography.headlineMedium)
                m.aviso?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary) }
                if (t.total > 0) {
                    LinearProgressIndicator(
                        progress = { if (t.bytesTotal > 0) t.bytesFeitos.toFloat() / t.bytesTotal else 0f },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                    Text(
                        "${inteiro(t.prontos)} de ${inteiro(t.total)} · ${bytesLegivel(t.bytesFeitos)} de ${bytesLegivel(t.bytesTotal)}",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (m.pausada) {
                    BotaoGrande("Retomar", { vm.app.montagem.retomar() })
                } else if (!m.terminou) {
                    BotaoContorno("Pausar", { vm.app.montagem.pausar() }, Modifier.fillMaxWidth())
                }
                Text(
                    "Deixe o celular na tomada e não tire o cabo. Dá para desligar a tela. Se parar, continua de onde estava. No computador, o INSTALAR_BIBLIOTECA.bat continua do mesmo ponto.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (t.falhos > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text("Faltaram ${inteiro(t.falhos)}", style = MaterialTheme.typography.titleLarge)
                    BotaoContorno("Tentar de novo", { vm.app.montagem.tentarDeNovo() }, Modifier.fillMaxWidth())
                    vm.app.montagem.faltaram(20).forEach { (rel, motivo) ->
                        Column {
                            Text(rel.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium)
                            Text(motivo, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                Text("Antes de começar", style = MaterialTheme.typography.headlineMedium)
                Aviso("Leva dias, não horas.", "São cerca de 830 GB e 54 mil arquivos.")
                Aviso("Deixe na tomada.", "Use um adaptador USB-C com entrada de carregador (hub com PD). Só com a bateria do celular, o SSD não aguenta.")
                Aviso("Não tire o cabo.", "O app retoma sozinho, mas cada desconexão atrasa.")
                Aviso("O SSD precisa estar em exFAT.", "Muitos celulares não leem NTFS. O app confere, mas nunca formata nada: formatar apaga tudo e é com você, num computador.")
                Aviso("O melhor caminho é um computador.", "Mesmo emprestado, com o INSTALAR_BIBLIOTECA.bat, é bem mais rápido e seguro.")
                if (testando) {
                    Text("Conferindo o SSD…", style = MaterialTheme.typography.titleMedium)
                } else {
                    BotaoGrande("Escolher o SSD e começar", { escolher.launch(null) })
                }
                Text("Precisa ser um SSD de 1 TB, de preferência vazio. Nada que já estiver nele é apagado.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    teste?.let { (uri, r) ->
        val fat = r.gravou && !r.acima4Gb
        val pequeno = r.gravou && r.total < ESPACO_COMPLETO
        AlertDialog(
            onDismissRequest = { teste = null },
            title = { Text(if (!r.gravou) "Não deu para gravar" else if (fat) "Este SSD está em FAT32" else "Este SSD é pequeno") },
            text = {
                Text(
                    buildString {
                        if (!r.gravou) append(r.erro)
                        if (fat) {
                            append("Em FAT32, nenhum arquivo passa de 4 GB, e a Wikipédia e outras enciclopédias são bem maiores. ")
                            append("Para ter tudo, formate em exFAT num computador. Formatar apaga o que estiver no SSD: o app nunca faz isso.\n\n")
                            append("No Windows: botão direito no disco, Formatar, sistema de arquivos exFAT.\n\n")
                        }
                        if (pequeno) append("O SSD tem ${bytesLegivel(r.total)}, e a biblioteca inteira tem cerca de 830 GB. O app baixa o mais importante primeiro e pula o que não couber.")
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                if (r.gravou) {
                    TextButton(onClick = {
                        teste = null
                        vm.app.montagem.comecar(uri, fat)
                    }) { Text(if (fat) "Continuar sem os arquivos grandes" else "Continuar") }
                }
            },
            dismissButton = { TextButton(onClick = { teste = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Aviso(titulo: String, texto: String) {
    Surface(
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(titulo, style = MaterialTheme.typography.titleMedium)
            Text(texto, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
