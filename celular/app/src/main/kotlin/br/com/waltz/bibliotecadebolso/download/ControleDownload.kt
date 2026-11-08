package br.com.waltz.bibliotecadebolso.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import br.com.waltz.bibliotecadebolso.BolsoApp
import br.com.waltz.bibliotecadebolso.MainActivity
import br.com.waltz.bibliotecadebolso.R
import br.com.waltz.bibliotecadebolso.dados.Totais
import br.com.waltz.bibliotecadebolso.util.bytesLegivel
import br.com.waltz.bibliotecadebolso.util.inteiro
import br.com.waltz.bibliotecadebolso.util.tempoRestante
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** O que a tela e a notificação mostram sobre os downloads. */
data class Resumo(
    val versao: String? = null,
    val totais: Totais = Totais(),
    val pausado: Boolean = false,
    val velocidade: Double = 0.0,
    val motivoEspera: String? = null,
) {
    val terminou get() = versao != null && totais.pendentes == 0
    val segundosRestantes: Long?
        get() = if (velocidade > 50_000) ((totais.bytesTotal - totais.bytesFeitos) / velocidade).toLong() else null

    fun linha(): String {
        val t = totais
        val base = "${inteiro(t.prontos)} de ${inteiro(t.total)} · ${bytesLegivel(t.bytesFeitos)} de ${bytesLegivel(t.bytesTotal)}"
        return segundosRestantes?.let { "$base · ${tempoRestante(it)}" } ?: base
    }
}

class ControleDownload(private val ctx: Context, private val app: BolsoApp) {
    private val wm get() = WorkManager.getInstance(ctx)

    /** Respeita Wi-Fi e bateria fraca. Nada roda em segundo plano depois que terminar. */
    fun iniciar(substituir: Boolean = false) {
        if (app.prefs.versao == null || app.prefs.pausado) return
        val regras = Constraints.Builder()
            .setRequiredNetworkType(if (app.prefs.soWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val pedido = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(regras)
            .setBackoffCriteria(androidx.work.BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(
            NOME, if (substituir) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, pedido,
        )
    }

    /** Para os downloads sem marcar como pausado e espera terminar: a importação escreve nos mesmos arquivos. */
    suspend fun suspenderParaImportar() {
        wm.cancelUniqueWork(NOME)
        repeat(40) {
            val rodando = wm.getWorkInfosForUniqueWork(NOME).get().any { it.state == androidx.work.WorkInfo.State.RUNNING }
            if (!rodando) return
            kotlinx.coroutines.delay(250)
        }
    }

    fun pausar() {
        app.prefs.pausado = true
        wm.cancelUniqueWork(NOME)
        Notificacoes.pausado(ctx, resumo())
    }

    fun retomar() {
        app.prefs.pausado = false
        Notificacoes.cancelarAvisos(ctx)
        iniciar(substituir = true)
    }

    fun tentarDeNovo() {
        app.banco.tentarDeNovo()
        iniciar()
    }

    fun mudarSoWifi(ligado: Boolean) {
        app.prefs.soWifi = ligado
        iniciar(substituir = true)
    }

    fun resumo(): Resumo {
        val versao = app.prefs.versao
        val totais = app.banco.totais(versao)
        val r = Resumo(versao, totais, app.prefs.pausado, app.motor.velocidade)
        return r.copy(motivoEspera = motivoEspera(r))
    }

    /** Explica para a pessoa por que parou, em vez de deixar o progresso congelado sem motivo. */
    private fun motivoEspera(r: Resumo): String? {
        if (r.terminou || r.versao == null) return null
        if (r.pausado) return "Pausado"
        if (!Rede.disponivel(ctx, false)) return "Sem internet. Continua sozinho quando voltar."
        if (app.prefs.soWifi && !Rede.disponivel(ctx, true)) return "Esperando Wi-Fi. Continua sozinho ao conectar."
        val bateria = ctx.getSystemService(BatteryManager::class.java)
        val nivel = bateria.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (nivel in 0..15 && !bateria.isCharging) return "Bateria fraca. Continua ao carregar."
        if (app.motor.economiaDeBateria) return "Modo de economia de bateria ligado. Continua na tomada ou ao desligar o modo."
        app.motor.servidorEmEspera?.let { return "O servidor $it pediu uma pausa. Continua sozinho." }
        return null
    }

    companion object {
        const val NOME = "downloads"
    }
}

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val app = ctx.applicationContext as BolsoApp
    private var emPrimeiroPlano = false
    private var ultimaTentativaFg = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo =
        Notificacoes.info(applicationContext, app.controle.resumo())

    /**
     * O Android pode recusar o serviço em primeiro plano (depois de reiniciar o celular, ou passado
     * o limite de 6 h por dia do Android 15). Aí o download segue como trabalho comum, em janelas
     * de ~10 min, e volta ao primeiro plano assim que for permitido, ao abrir o app.
     */
    private suspend fun tentarPrimeiroPlano(resumo: br.com.waltz.bibliotecadebolso.download.Resumo) {
        try {
            setForeground(Notificacoes.info(applicationContext, resumo))
            if (!emPrimeiroPlano) app.diario.escrever("download em primeiro plano")
            emPrimeiroPlano = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (emPrimeiroPlano || ultimaTentativaFg == 0L) {
                app.diario.escrever("primeiro plano recusado (segue em segundo plano): ${e.javaClass.simpleName}")
            }
            emPrimeiroPlano = false
        }
        ultimaTentativaFg = System.currentTimeMillis()
    }

    override suspend fun doWork(): Result {
        app.preparar()
        tentarPrimeiroPlano(app.controle.resumo())
        var conta = 0
        app.motor.rodar {
            val r = app.controle.resumo()
            if (emPrimeiroPlano) {
                if (++conta % 2 == 0) Notificacoes.atualizar(applicationContext, r)
            } else if (System.currentTimeMillis() - ultimaTentativaFg > 60_000) {
                tentarPrimeiroPlano(r)
            }
        }
        val r = app.controle.resumo()
        if (r.terminou) Notificacoes.concluido(applicationContext, r)
        return Result.success()
    }
}

class AcaoDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext as BolsoApp
        when (intent.action) {
            ACAO_PAUSAR -> app.controle.pausar()
            ACAO_RETOMAR -> app.controle.retomar()
        }
    }

    companion object {
        const val ACAO_PAUSAR = "br.com.waltz.bibliotecadebolso.PAUSAR"
        const val ACAO_RETOMAR = "br.com.waltz.bibliotecadebolso.RETOMAR"
    }
}

object Notificacoes {
    private const val CANAL = "downloads"
    private const val ID_PROGRESSO = 1
    private const val ID_AVISO = 2

    private fun canal(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CANAL, "Downloads da biblioteca", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun abrirApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun acao(ctx: Context, acao: String) = PendingIntent.getBroadcast(
        ctx, acao.hashCode(), Intent(ctx, AcaoDownloadReceiver::class.java).setAction(acao),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progresso(ctx: Context, r: Resumo) = run {
        canal(ctx)
        val t = r.totais
        val pct = if (t.bytesTotal > 0) (t.bytesFeitos * 1000 / t.bytesTotal).toInt() else 0
        NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setContentTitle(r.motivoEspera ?: "Baixando a biblioteca")
            .setContentText("Baixando ${r.linha()}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Baixando ${r.linha()}"))
            .setProgress(1000, pct, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(abrirApp(ctx))
            .addAction(0, "Pausar", acao(ctx, AcaoDownloadReceiver.ACAO_PAUSAR))
            .build()
    }

    fun info(ctx: Context, r: Resumo): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(ID_PROGRESSO, progresso(ctx, r), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(ID_PROGRESSO, progresso(ctx, r))
        }

    fun atualizar(ctx: Context, r: Resumo) {
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(ID_PROGRESSO, progresso(ctx, r))
        } catch (_: SecurityException) {
            // Sem permissão de notificação: o download segue e o progresso aparece no app.
        }
    }

    fun pausado(ctx: Context, r: Resumo) {
        canal(ctx)
        val n = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setContentTitle("Download pausado")
            .setContentText(r.linha())
            .setContentIntent(abrirApp(ctx))
            .addAction(0, "Retomar", acao(ctx, AcaoDownloadReceiver.ACAO_RETOMAR))
            .build()
        avisar(ctx, n)
    }

    fun concluido(ctx: Context, r: Resumo) {
        canal(ctx)
        val texto = if (r.totais.falhos == 0) "Tudo baixado. Funciona sem internet."
        else "${inteiro(r.totais.prontos)} prontos. ${inteiro(r.totais.falhos)} faltaram: veja em Downloads."
        val n = NotificationCompat.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_notificacao)
            .setContentTitle("Biblioteca baixada")
            .setContentText(texto)
            .setContentIntent(abrirApp(ctx))
            .setAutoCancel(true)
            .build()
        avisar(ctx, n)
    }

    private fun avisar(ctx: Context, n: android.app.Notification) {
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(ID_AVISO, n)
        } catch (_: SecurityException) {
        }
    }

    fun cancelarAvisos(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(ID_AVISO)
    }
}
