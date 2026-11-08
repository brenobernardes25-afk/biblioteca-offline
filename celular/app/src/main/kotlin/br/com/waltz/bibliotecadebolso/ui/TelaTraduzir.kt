package br.com.waltz.bibliotecadebolso.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.waltz.bibliotecadebolso.traducao.Tradutor
import br.com.waltz.bibliotecadebolso.util.inteiro

/** Seção 9. Inglês para português aqui mesmo, sem internet, e o caminho para outro tradutor. */
@Composable
fun TelaTraduzir(vm: AppVM, t: Tela.Traduzir) {
    val ctx = LocalContext.current
    val tradutor = remember { Tradutor(ctx, vm.app.diario) }
    DisposableEffect(Unit) { onDispose { tradutor.fechar() } }
    val e by tradutor.estado.collectAsStateWithLifecycle()
    var texto by rememberSaveable(t.texto) { mutableStateOf(t.texto) }
    var automatico by remember { mutableStateOf(t.texto.isNotBlank()) }

    // Veio de um artigo ou de um PDF: já traduz, sem mais um toque.
    LaunchedEffect(e.pronto) {
        if (e.pronto && automatico) {
            automatico = false
            tradutor.traduzir(texto)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Cabecalho("Traduzir", vm)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Inglês para português, sem internet.", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = texto,
                onValueChange = { texto = it },
                label = { Text("Texto em inglês") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = if (t.texto.isBlank()) 260.dp else 120.dp),
                textStyle = MaterialTheme.typography.bodyLarge,
            )
            if (texto.length > Tradutor.LIMITE) {
                Text("Texto longo: serão traduzidos os primeiros ${inteiro(Tradutor.LIMITE)} caracteres.", style = MaterialTheme.typography.bodyMedium)
            }
            when {
                e.erro != null -> Text(e.erro.orEmpty(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
                !e.pronto -> {
                    Text("Preparando o tradutor…", style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp))
                }
                else -> BotaoGrande(
                    if (e.traduzindo) "Traduzindo…" else "Traduzir",
                    { tradutor.traduzir(texto) },
                    habilitado = texto.isNotBlank() && !e.traduzindo,
                )
            }
            if (e.traduzindo && e.total > 0) {
                LinearProgressIndicator(
                    progress = { e.feitos.toFloat() / e.total },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                Text("Parágrafo ${inteiro(e.feitos)} de ${inteiro(e.total)}", style = MaterialTheme.typography.bodyMedium)
            }
            if (e.resultado.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Text("Em português", style = MaterialTheme.typography.titleLarge)
                SelectionContainer { Text(e.resultado, style = MaterialTheme.typography.bodyLarge) }
                Text(
                    "Tradução automática pode errar. Em dose de remédio ou procedimento de saúde, confira no original.",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                BotaoContorno("Compartilhar a tradução", {
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, e.resultado),
                            "Compartilhar a tradução",
                        )
                    )
                }, Modifier.fillMaxWidth())
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            BotaoContorno("Abrir em outro tradutor", {
                if (texto.isBlank()) {
                    Toast.makeText(ctx, "Escreva o texto primeiro", Toast.LENGTH_LONG).show()
                } else if (!Tradutor.abrirEmOutroApp(ctx, texto)) {
                    Toast.makeText(ctx, "Não há outro tradutor neste celular", Toast.LENGTH_LONG).show()
                }
            }, Modifier.fillMaxWidth())
            Text(
                "Para usar o Google Tradutor sem internet: enquanto tiver internet, abra o app, toque na sua foto, em Traduções baixadas, e baixe inglês e português.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
