package br.com.waltz.bibliotecadebolso.dados

import android.content.Context
import androidx.core.content.edit

class Preferencias(ctx: Context) {
    private val p = ctx.getSharedPreferences("bolso", Context.MODE_PRIVATE)

    var assistenteConcluido: Boolean
        get() = p.getBoolean("assistente", false)
        set(v) = p.edit { putBoolean("assistente", v) }

    /** "minimo", "slim" ou null (nenhuma versão no celular). */
    var versao: String?
        get() = p.getString("versao", null)
        set(v) = p.edit { putString("versao", v) }

    var soWifi: Boolean
        get() = p.getBoolean("so_wifi", true)
        set(v) = p.edit { putBoolean("so_wifi", v) }

    var pausado: Boolean
        get() = p.getBoolean("pausado", false)
        set(v) = p.edit { putBoolean("pausado", v) }

    var incluirIngles: Boolean
        get() = p.getBoolean("incluir_ingles", true)
        set(v) = p.edit { putBoolean("incluir_ingles", v) }

    /** Tamanho e data do índice do SSD já copiado: só copia de novo se ele mudar. */
    var ssdAssinaturaIndice: String?
        get() = p.getString("ssd_assinatura", null)
        set(v) = p.edit { putString("ssd_assinatura", v) }

    var ssdAssinaturaParcial: String?
        get() = p.getString("ssd_assinatura_parcial", null)
        set(v) = p.edit { putString("ssd_assinatura_parcial", v) }

    /** Pasta onde a biblioteca está sendo montada pelo celular (seção 6.2). */
    var montagemUri: String?
        get() = p.getString("montagem_uri", null)
        set(v) = p.edit { putString("montagem_uri", v) }

    var montagemPausada: Boolean
        get() = p.getBoolean("montagem_pausada", false)
        set(v) = p.edit { putBoolean("montagem_pausada", v) }

    /** SSD em FAT32: os arquivos acima de 4 GB ficam de fora. */
    var montagemSemGrandes: Boolean
        get() = p.getBoolean("montagem_sem_grandes", false)
        set(v) = p.edit { putBoolean("montagem_sem_grandes", v) }

    /** Modelo de IA que a pessoa quis baixar. Null: não quis a IA (ela é opcional). */
    var iaId: String?
        get() = p.getString("ia_id", null)
        set(v) = p.edit { putString("ia_id", v) }

    /** Pasta do SSD escolhida pela pessoa (a permissão fica guardada no sistema). */
    var ssdUri: String?
        get() = p.getString("ssd_uri", null)
        set(v) = p.edit { putString("ssd_uri", v) }
}
