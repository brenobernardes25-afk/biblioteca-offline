package br.com.waltz.bibliotecadebolso.util

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PT_BR = Locale.Builder().setLanguage("pt").setRegion("BR").build()

/** "4,1 GB", "812 MB". */
fun bytesLegivel(b: Long): String {
    val v = b.toDouble()
    return when {
        v >= 1e12 -> "%.1f TB".format(PT_BR, v / 1e12)
        v >= 1e9 -> "%.1f GB".format(PT_BR, v / 1e9)
        v >= 1e6 -> "%.0f MB".format(PT_BR, v / 1e6)
        v >= 1e3 -> "%.0f KB".format(PT_BR, v / 1e3)
        else -> "$b B"
    }
}

/** "1.177" */
fun inteiro(n: Int): String = NumberFormat.getIntegerInstance(PT_BR).format(n)

/** "~2 h restantes", "~15 min restantes". */
fun tempoRestante(segundos: Long): String = when {
    segundos < 90 -> "menos de 2 min restantes"
    segundos < 3600 -> "~${segundos / 60} min restantes"
    segundos < 86_400 -> "~${(segundos + 1800) / 3600} h restantes"
    else -> "~${(segundos + 43_200) / 86_400} dias restantes"
}

/** Log local, que a pessoa pode exportar em Sobre e mandar para o Breno. Nada sai do aparelho sozinho. */
class Diario(ctx: Context) {
    private val pasta = File(ctx.filesDir, "logs").apply { mkdirs() }
    val arquivo = File(pasta, "biblioteca.log")
    private val hora = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

    init {
        escrever("--- app aberto · Android ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    @Synchronized
    fun escrever(msg: String) {
        Log.i("Bolso", msg)
        try {
            if (arquivo.length() > 2_000_000) {
                File(pasta, "biblioteca.anterior.log").delete()
                arquivo.renameTo(File(pasta, "biblioteca.anterior.log"))
            }
            arquivo.appendText("${hora.format(Date())}  $msg\n")
        } catch (_: Exception) {
            // Log nunca derruba o app.
        }
    }
}
