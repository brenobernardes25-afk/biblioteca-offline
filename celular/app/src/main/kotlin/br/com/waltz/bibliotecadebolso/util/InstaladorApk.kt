package br.com.waltz.bibliotecadebolso.util

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.ParcelFileDescriptor
import br.com.waltz.bibliotecadebolso.BolsoApp
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.FileInputStream

/**
 * Instala um app que vem no pen drive (o Organic Maps) pelo instalador do próprio Android, o mesmo que as lojas
 * de apps usam: o app manda o arquivo e o Android mostra a janela "Instalar?". Mais garantido do que pedir para
 * outro app abrir o arquivo (em alguns celulares, como os Xiaomi, isso não abria nada).
 */
object InstaladorApk {
    /** O que dizer na tela: andamento, "toque em Instalar", pronto ou o erro. */
    val situacao = MutableStateFlow<String?>(null)

    fun instalar(ctx: Context, abrir: () -> ParcelFileDescriptor?, tamanho: Long, aoAndar: (Int) -> Unit): Boolean {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (tamanho > 0) params.setSize(tamanho)
        val id = pi.createSession(params)
        val sessao = pi.openSession(id)
        try {
            val pfd = abrir() ?: run {
                sessao.abandon()
                return false
            }
            pfd.use { p ->
                FileInputStream(p.fileDescriptor).use { ent ->
                    sessao.openWrite("app.apk", 0, if (tamanho > 0) tamanho else -1).use { sai ->
                        val b = ByteArray(1 shl 20)
                        var feito = 0L
                        while (true) {
                            val n = ent.read(b)
                            if (n < 0) break
                            sai.write(b, 0, n)
                            feito += n
                            if (tamanho > 0) aoAndar((100 * feito / tamanho).toInt())
                        }
                        sessao.fsync(sai)
                    }
                }
            }
            val aviso = Intent(ctx, ReceptorInstalacao::class.java).setAction(ACAO)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            sessao.commit(PendingIntent.getBroadcast(ctx, id, aviso, flags).intentSender)
            sessao.close()
            return true
        } catch (e: Exception) {
            (ctx.applicationContext as? BolsoApp)?.diario?.escrever("instalar: ${e.javaClass.simpleName} ${e.message}")
            try {
                sessao.abandon()
            } catch (_: Exception) {
            }
            return false
        }
    }

    const val ACAO = "br.com.waltz.bibliotecadebolso.RESULTADO_INSTALACAO"
}

/** O Android responde aqui: pede a confirmação da pessoa, ou diz se instalou. */
class ReceptorInstalacao : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val diario = (c.applicationContext as? BolsoApp)?.diario
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmar: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    i.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmar != null) {
                    c.startActivity(confirmar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    InstaladorApk.situacao.value = "Abriu a janela do Android: toque em Instalar."
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                diario?.escrever("instalar: Organic Maps instalado")
                InstaladorApk.situacao.value = "Pronto: o Organic Maps foi instalado."
            }
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                diario?.escrever("instalar: status $st $msg")
                InstaladorApk.situacao.value = if (st == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    "A instalação foi cancelada. Toque em Instalar o Organic Maps para tentar de novo."
                } else {
                    "O Android não deixou instalar ($msg). Confira o espaço livre no celular e tente de novo."
                }
            }
        }
    }
}
