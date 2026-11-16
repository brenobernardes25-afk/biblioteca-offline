package br.com.waltz.bibliotecadebolso.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import br.com.waltz.bibliotecadebolso.BolsoApp
import br.com.waltz.bibliotecadebolso.dados.Filtro
import br.com.waltz.bibliotecadebolso.download.Armazenamento
import br.com.waltz.bibliotecadebolso.ssd.RegrasKit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Texto curto com a situação do app, para a pessoa colar na conversa de ajuda. Poupa horas de tentativa e erro.
 * Não leva nome, conta, número nem localização: só o modelo do aparelho, a versão do Android e o estado dos downloads.
 * Nada é enviado: o texto vai para a área de transferência e a pessoa cola onde quiser.
 */
object Relatorio {
    /** Partes sem Android, para testar. */
    fun montar(
        cabecalho: List<String>,
        slim: List<String>,
        completa: List<String>,
        registro: List<String>,
    ): String = buildString {
        appendLine("RELATÓRIO DA BIBLIOTECA DE BOLSO (cole este texto inteiro na conversa de ajuda)")
        cabecalho.forEach(::appendLine)
        if (slim.isNotEmpty()) {
            appendLine("NO CELULAR")
            slim.forEach { appendLine("  $it") }
        }
        if (completa.isNotEmpty()) {
            appendLine("MONTAGEM NO SSD")
            completa.forEach { appendLine("  $it") }
        }
        appendLine("ÚLTIMAS LINHAS DO REGISTRO")
        if (registro.isEmpty()) appendLine("  (vazio)") else registro.forEach { appendLine("  ${it.take(220)}") }
    }

    fun texto(ctx: Context, app: BolsoApp): String {
        val versaoApp = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val rede = when {
            caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> "sem internet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "dados móveis"
            else -> "outra rede"
        }
        val economia = ctx.getSystemService(PowerManager::class.java).isPowerSaveMode
        val cabecalho = listOf(
            "Gerado em ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ROOT).format(Date())} · app $versaoApp",
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}",
            "Rede: $rede · Só no Wi-Fi: ${if (app.prefs.soWifi) "sim" else "não"} · economia de bateria: ${if (economia) "LIGADA" else "desligada"}",
            "Espaço livre no celular: ${bytesLegivel(Armazenamento.livre(ctx))}",
        )

        val slim = ArrayList<String>()
        val versao = app.prefs.versao
        if (versao != null) {
            val t = app.banco.totais(versao)
            slim += "Versão: $versao · ${inteiro(t.prontos)} de ${inteiro(t.total)} prontos · ${bytesLegivel(t.bytesFeitos)} de ${bytesLegivel(t.bytesTotal)}"
            slim += "Faltam ${inteiro(t.pendentes)} · falharam ${inteiro(t.falhos)}"
            val falhos = app.banco.listar(Filtro.FALHOU, versao)
            falhos.groupingBy { it.erro.orEmpty() }.eachCount().entries.sortedByDescending { it.value }.take(6)
                .forEach { slim += "${it.value} × ${it.key}" }
            falhos.take(8).forEach { slim += "falhou: ${it.id}" }
        }

        val completa = ArrayList<String>()
        if (app.montagem.ativa) {
            app.montagem.atualizarTotais()
            val m = app.montagem.estado.value
            val t = m.totais
            completa += "Situação: ${m.etapa.ifBlank { "parada" }}${if (m.pausada) " (pausada pela pessoa)" else ""}"
            completa += "${inteiro(t.prontos)} de ${inteiro(t.total)} arquivos · ${bytesLegivel(m.bytesFeitos)} de ${bytesLegivel(t.bytesTotal)} · falharam ${inteiro(t.falhos)}"
            m.atual?.let { completa += "Agora: $it · ${bytesLegivel(m.atualFeito)} de ${bytesLegivel(m.atualTam)}" }
            if (m.etapa == "Baixando") completa += RegrasKit.ritmo(m.velocidade, m.paradoS, (t.bytesTotal - m.bytesFeitos).coerceAtLeast(0))
            m.aviso?.let { completa += "Aviso na tela: $it" }
            if (app.prefs.montagemSemGrandes) completa += "SSD em FAT32: arquivos acima de 4 GB ficaram de fora"
            app.montagem.motivos(6).forEach { (motivo, n) -> completa += "$n × $motivo" }
            app.montagem.faltaram(8).forEach { (rel, _) -> completa += "falhou: ${rel.substringAfterLast('/')}" }
        }

        val registro = try {
            app.diario.arquivo.readLines().takeLast(30)
        } catch (_: Exception) {
            emptyList()
        }
        return montar(cabecalho, slim, completa, registro)
    }

    fun copiar(ctx: Context, app: BolsoApp) {
        try {
            val t = texto(ctx, app)
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Relatório da Biblioteca de Bolso", t))
            Toast.makeText(ctx, "Relatório copiado. Abra a conversa e cole.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            app.diario.escrever("relatório: $e")
            Toast.makeText(ctx, "Não consegui montar o relatório. Use Exportar os logs, em Sobre.", Toast.LENGTH_LONG).show()
        }
    }
}
