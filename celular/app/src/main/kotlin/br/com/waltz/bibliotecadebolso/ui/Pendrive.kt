package br.com.waltz.bibliotecadebolso.ui

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Modo pen drive: com um pen drive ou SSD ligado, a primeira tela mostra um botão só. */
object Pendrive {
    /** Endereço da pasta principal do primeiro disco USB ligado, para o seletor do Android já abrir nele. */
    fun raizLigada(ctx: Context): Uri? = try {
        ctx.getSystemService(StorageManager::class.java).storageVolumes
            .firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED && it.uuid != null }
            ?.let { DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "${it.uuid}:") }
    } catch (_: Exception) {
        null
    }
}

/**
 * Aparece só quando há um disco USB ligado e o app ainda não usa nenhum. O Android exige que a pessoa confirme
 * a pasta uma vez ("Usar esta pasta" e "Permitir"): não dá para pular, mas o seletor já abre no lugar certo.
 */
@Composable
fun CartaoPendrive(vm: AppVM, aoEntrar: () -> Unit) {
    val ctx = LocalContext.current
    var raiz by remember { mutableStateOf(Pendrive.raizLigada(ctx)) }
    var erro by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            raiz = Pendrive.raizLigada(ctx)
        }
    }
    val escolher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            erro = vm.app.ssd.escolher(uri)
            if (erro == null) aoEntrar()
        }
    }
    if (raiz == null) return
    Surface(
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pen drive ligado", style = MaterialTheme.typography.titleLarge)
            Text(
                "Se ele já traz a biblioteca, não precisa baixar nada: funciona sem internet.",
                style = MaterialTheme.typography.bodyLarge,
            )
            BotaoGrande("Usar este pen drive", { erro = null; escolher.launch(raiz) })
            Text(
                "O Android vai pedir para confirmar: toque em \"Usar esta pasta\" e depois em \"Permitir\". O app só lê o pen drive, não apaga nada.",
                style = MaterialTheme.typography.bodyMedium,
            )
            erro?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary) }
        }
    }
}
