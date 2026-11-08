package br.com.waltz.bibliotecadebolso.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.waltz.bibliotecadebolso.download.Armazenamento
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private class DocumentoPdf(private val pfd: ParcelFileDescriptor) {
    private val renderer = PdfRenderer(pfd)
    private val trava = Mutex() // O PdfRenderer só aguenta uma página aberta por vez.
    val paginas = renderer.pageCount

    suspend fun desenhar(pagina: Int, larguraPx: Int): Bitmap = trava.withLock {
        withContext(Dispatchers.IO) {
            renderer.openPage(pagina).use { p ->
                // Dobro da tela para o zoom ficar legível, mas com teto: página gigante estoura a memória.
                val largura = (larguraPx * 2).coerceAtMost(LARGURA_MAXIMA)
                val altura = (largura.toFloat() * p.height / p.width).toInt().coerceIn(1, ALTURA_MAXIMA)
                val bmp = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

    fun fechar() {
        try {
            renderer.close()
        } finally {
            pfd.close()
        }
    }

    private companion object {
        const val LARGURA_MAXIMA = 2400
        const val ALTURA_MAXIMA = 3600
    }
}

/** PDF na página do resultado (seção 8). Abre direto do arquivo: nada de internet. */
@Composable
fun LeitorPdf(vm: AppVM, t: Tela.Pdf) {
    val ctx = LocalContext.current
    val arquivo = remember(t.caminho) { File(Armazenamento.pasta(ctx), t.caminho) }
    val ssd by vm.app.ssd.estado.collectAsStateWithLifecycle()
    val semSsd = t.doSsd && !ssd.conectado
    var doc by remember(t.caminho) { mutableStateOf<DocumentoPdf?>(null) }
    var erro by remember(t.caminho) { mutableStateOf<String?>(null) }
    var pagina by rememberSaveable(t.caminho) { mutableIntStateOf(t.pagina) }
    var imagem by remember(t.caminho) { mutableStateOf<Bitmap?>(null) }
    var ir by remember { mutableStateOf(false) }
    var escala by remember { mutableFloatStateOf(1f) }
    var deslocamento by remember { mutableStateOf(Offset.Zero) }

    DisposableEffect(t.caminho, semSsd) {
        erro = null
        val d = try {
            if (semSsd) {
                null
            } else if (t.doSsd) {
                vm.app.ssd.abrirDocumento(t.caminho)?.let { DocumentoPdf(it) }
                    ?: throw java.io.IOException("não abriu no SSD")
            } else {
                DocumentoPdf(ParcelFileDescriptor.open(arquivo, ParcelFileDescriptor.MODE_READ_ONLY))
            }
        } catch (e: Exception) {
            vm.app.diario.escrever("PDF não abriu (${t.caminho}): $e")
            erro = "Este visualizador não conseguiu abrir o arquivo. Tente abrir em outro app."
            null
        }
        doc = d
        // O PDF fica aberto pelo SSD: precisa soltar na hora em que o SSD sai.
        onDispose {
            doc = null
            d?.fechar()
        }
    }

    fun abrirFora() {
        val uri = if (t.doSsd) vm.app.ssd.uriDocumento(t.caminho) else FileProvider.getUriForFile(ctx, "${ctx.packageName}.arquivos", arquivo)
        if (uri == null) {
            Toast.makeText(ctx, "SSD desconectado", Toast.LENGTH_LONG).show()
            return
        }
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "Não há outro app de PDF neste celular", Toast.LENGTH_LONG).show()
        }
    }

    val total = doc?.paginas ?: 0
    val atual = if (total > 0) pagina.coerceIn(1, total) else pagina

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.voltar() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
            }
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Text(t.titulo, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Fonte: ${arquivo.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (t.traduzivel) {
                IconButton(onClick = {
                    val texto = vm.app.indicePdf.textoDaPagina(t.caminho, atual, t.doSsd)
                    if (texto.isNullOrBlank()) Toast.makeText(ctx, "Esta página não tem texto para traduzir", Toast.LENGTH_LONG).show()
                    else vm.ir(Tela.Traduzir(br.com.waltz.bibliotecadebolso.traducao.Tradutor.juntarLinhas(texto)))
                }, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.Translate, contentDescription = "Traduzir esta página") }
            }
            IconButton(onClick = {
                val texto = vm.app.indicePdf.textoDaPagina(t.caminho, atual, t.doSsd)
                if (texto.isNullOrBlank()) {
                    Toast.makeText(ctx, "Esta página não tem texto para compartilhar", Toast.LENGTH_LONG).show()
                } else {
                    ctx.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, "$texto\n\n— ${t.titulo}, página $atual"),
                            "Compartilhar trecho",
                        )
                    )
                }
            }, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.Share, contentDescription = "Compartilhar a página") }
            IconButton(onClick = { abrirFora() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Abrir em outro app")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.TopCenter,
        ) {
            val larguraPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
            LaunchedEffect(doc, atual, larguraPx) {
                val d = doc ?: return@LaunchedEffect
                imagem = null
                escala = 1f
                deslocamento = Offset.Zero
                try {
                    imagem = d.desenhar(atual - 1, larguraPx)
                } catch (e: Exception) {
                    vm.app.diario.escrever("página $atual de ${t.caminho}: $e")
                    erro = "Não consegui mostrar esta página. Tente abrir em outro app."
                }
            }
            val b = imagem
            when {
                semSsd -> Text(
                    "SSD desconectado: conecte de novo para continuar lendo.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(20.dp),
                )
                erro != null -> Column(
                    Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(erro.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { abrirFora() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text("Abrir em outro app")
                    }
                }
                b == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> Image(
                    bitmap = b.asImageBitmap(),
                    contentDescription = "Página $atual",
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, mover, zoom, _ ->
                                escala = (escala * zoom).coerceIn(1f, 5f)
                                deslocamento = if (escala == 1f) Offset.Zero else deslocamento + mover
                            }
                        }
                        .graphicsLayer {
                            scaleX = escala
                            scaleY = escala
                            translationX = deslocamento.x
                            translationY = deslocamento.y
                        },
                )
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { pagina = atual - 1 },
                enabled = atual > 1,
                modifier = Modifier.heightIn(min = 52.dp),
            ) { Text("Anterior") }
            TextButton(
                onClick = { ir = true },
                enabled = total > 0,
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text(if (total > 0) "$atual de $total" else "…", style = MaterialTheme.typography.titleMedium) }
            OutlinedButton(
                onClick = { pagina = atual + 1 },
                enabled = atual < total,
                modifier = Modifier.heightIn(min = 52.dp),
            ) { Text("Seguinte") }
        }
    }

    if (ir) {
        var digitado by remember { mutableStateOf(atual.toString()) }
        AlertDialog(
            onDismissRequest = { ir = false },
            title = { Text("Ir para a página") },
            text = {
                OutlinedTextField(
                    value = digitado,
                    onValueChange = { novo -> digitado = novo.filter { it.isDigit() }.take(6) },
                    singleLine = true,
                    label = { Text("De 1 a $total") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    digitado.toIntOrNull()?.let { pagina = it.coerceIn(1, total) }
                    ir = false
                }) { Text("Ir") }
            },
            dismissButton = { TextButton(onClick = { ir = false }) { Text("Cancelar") } },
        )
    }
}
