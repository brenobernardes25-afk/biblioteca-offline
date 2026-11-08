package br.com.waltz.bibliotecadebolso.dados

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

object Status {
    const val PENDENTE = "pendente"
    const val BAIXANDO = "baixando"
    const val PRONTO = "pronto"
    const val FALHOU = "falhou"
}

data class ItemBanco(
    val id: String,
    val tipo: String,
    val pacote: String,
    val tema: String,
    val titulo: String,
    val url: String,
    val tam: Long,
    val destino: String,
    val atualizavel: Boolean,
    val kiwixNome: String?,
    val status: String,
    val baixados: Long,
    val urlAtual: String?,
    val tamAtual: Long?,
    val destinoAtual: String?,
    val destinoAntigo: String?,
    val tentativas: Int,
    val erro: String?,
) {
    val urlEfetiva get() = urlAtual ?: url
    val tamEfetivo get() = tamAtual ?: tam
    val destinoEfetivo get() = destinoAtual ?: destino
}

data class Totais(
    val total: Int = 0,
    val prontos: Int = 0,
    val falhos: Int = 0,
    val pendentes: Int = 0,
    val bytesTotal: Long = 0,
    val bytesFeitos: Long = 0,
)

enum class Filtro { ANDAMENTO, FALTA, FALHOU, PRONTOS }

/** Estado local dos downloads. Só o que o app precisa lembrar entre uma abertura e outra. */
class Banco(ctx: Context) : SQLiteOpenHelper(ctx, "bolso.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE itens(
                id TEXT PRIMARY KEY, tipo TEXT NOT NULL, pacote TEXT NOT NULL, tema TEXT NOT NULL,
                titulo TEXT NOT NULL, url TEXT NOT NULL, tam INTEGER NOT NULL, destino TEXT NOT NULL,
                atualizavel INTEGER NOT NULL, kiwix_nome TEXT, ord INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'pendente', baixados INTEGER NOT NULL DEFAULT 0,
                url_atual TEXT, tam_atual INTEGER, destino_atual TEXT, destino_antigo TEXT,
                tentativas INTEGER NOT NULL DEFAULT 0, erro TEXT)"""
        )
        db.execSQL("CREATE INDEX itens_fila ON itens(status, ord)")
        db.execSQL("CREATE TABLE meta(chave TEXT PRIMARY KEY, valor TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, antes: Int, depois: Int) = Unit

    fun meta(chave: String): String? = readableDatabase
        .rawQuery("SELECT valor FROM meta WHERE chave=?", arrayOf(chave))
        .use { if (it.moveToFirst()) it.getString(0) else null }

    fun meta(chave: String, valor: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply {
            put("chave", chave); put("valor", valor)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * Carrega um manifesto novo sem perder o que já foi baixado.
     * PDF pronto cujo tamanho mudou volta para a fila. Item que saiu do manifesto:
     * se está pronto, fica guardado como "removido"; se não, sai da fila.
     */
    fun sincronizar(m: Manifesto) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existentes = HashMap<String, Pair<String, Long>>()
            db.rawQuery("SELECT id, status, tam FROM itens", null).use {
                while (it.moveToNext()) existentes[it.getString(0)] = it.getString(1) to it.getLong(2)
            }
            OrdemDownload.ordenar(m.itens).forEachIndexed { ordem, i ->
                val v = ContentValues().apply {
                    put("tipo", i.tipo); put("pacote", i.pacote); put("tema", i.tema)
                    put("titulo", i.titulo); put("url", i.url); put("tam", i.tam)
                    put("destino", i.destino); put("atualizavel", if (i.atualizavel) 1 else 0)
                    put("kiwix_nome", i.kiwixNome); put("ord", ordem)
                }
                val velho = existentes.remove(i.id)
                if (velho == null) {
                    v.put("id", i.id)
                    db.insertOrThrow("itens", null, v)
                } else {
                    if (i.tipo != "zim" && velho.first == Status.PRONTO && velho.second != i.tam) {
                        v.put("status", Status.PENDENTE); v.put("baixados", 0)
                    }
                    db.update("itens", v, "id=?", arrayOf(i.id))
                }
            }
            existentes.forEach { (id, v) ->
                if (v.first == Status.PRONTO) {
                    db.execSQL("UPDATE itens SET pacote='removido' WHERE id=?", arrayOf(id))
                } else {
                    db.delete("itens", "id=?", arrayOf(id))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        meta("versao_manifesto", m.versao)
    }

    /** O modelo de IA que a pessoa pediu. Fica de fora da fila de quem não pediu: a IA é opcional. */
    @Volatile var iaEscolhida: String? = null

    private fun querido(versao: String?): String {
        val base = when (versao) {
            "slim" -> "pacote IN ('minimo','slim')"
            "minimo" -> "pacote = 'minimo'"
            else -> "0"
        }
        val ia = iaEscolhida?.takeIf { versao != null && it.matches(Regex("[a-z0-9:.-]+")) } ?: return base
        return "($base OR id = '$ia')"
    }

    private val COLUNAS = "id,tipo,pacote,tema,titulo,url,tam,destino,atualizavel,kiwix_nome," +
        "status,baixados,url_atual,tam_atual,destino_atual,destino_antigo,tentativas,erro"

    private fun Cursor.item() = ItemBanco(
        id = getString(0), tipo = getString(1), pacote = getString(2), tema = getString(3),
        titulo = getString(4), url = getString(5), tam = getLong(6), destino = getString(7),
        atualizavel = getInt(8) == 1, kiwixNome = getString(9), status = getString(10),
        baixados = getLong(11), urlAtual = getString(12),
        tamAtual = if (isNull(13)) null else getLong(13), destinoAtual = getString(14),
        destinoAntigo = getString(15), tentativas = getInt(16), erro = getString(17),
    )

    private fun lista(sql: String, args: Array<String>? = null): List<ItemBanco> =
        readableDatabase.rawQuery(sql, args).use { c ->
            val r = ArrayList<ItemBanco>(c.count)
            while (c.moveToNext()) r += c.item()
            r
        }

    /** Próximo da fila, pulando o que já está em curso e os servidores que pediram para esperar. */
    fun proximo(versao: String?, excluir: Set<String>, hostsEmEspera: Set<String> = emptySet()): ItemBanco? {
        val limite = excluir.size + 1
        val hosts = hostsEmEspera.map { "%://$it/%" }
        val semHost = hosts.joinToString("") { " AND COALESCE(url_atual, url) NOT LIKE ?" }
        return lista(
            "SELECT $COLUNAS FROM itens WHERE status IN ('pendente','baixando') AND ${querido(versao)}$semHost " +
                "ORDER BY ord LIMIT $limite",
            hosts.toTypedArray(),
        ).firstOrNull { it.id !in excluir }
    }

    fun totais(versao: String?): Totais = readableDatabase.rawQuery(
        """SELECT COUNT(*),
             SUM(status='pronto'), SUM(status='falhou'), SUM(status IN ('pendente','baixando')),
             SUM(COALESCE(tam_atual, tam)),
             SUM(CASE WHEN status='pronto' THEN COALESCE(tam_atual, tam) ELSE baixados END)
           FROM itens WHERE ${querido(versao)}""", null
    ).use { c ->
        c.moveToFirst()
        Totais(c.getInt(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getLong(4), c.getLong(5))
    }

    /** Quanto ainda falta baixar de cada versão (para a tela de escolha). */
    fun faltaBaixar(versao: String): Long = readableDatabase.rawQuery(
        "SELECT SUM(COALESCE(tam_atual,tam) - CASE WHEN status='pronto' THEN COALESCE(tam_atual,tam) ELSE baixados END) " +
            "FROM itens WHERE ${querido(versao)}", null
    ).use { if (it.moveToFirst()) it.getLong(0) else 0 }

    fun listar(filtro: Filtro, versao: String?): List<ItemBanco> {
        val cond = when (filtro) {
            Filtro.ANDAMENTO -> "status='baixando' OR (status='pendente' AND baixados>0)"
            Filtro.FALTA -> "status IN ('pendente','baixando')"
            Filtro.FALHOU -> "status='falhou'"
            Filtro.PRONTOS -> "status='pronto'"
        }
        return lista("SELECT $COLUNAS FROM itens WHERE ($cond) AND ${querido(versao)} ORDER BY ord")
    }

    /** Pronto agora, ou com a versão antiga ainda no lugar enquanto a nova baixa. */
    fun zimsDisponiveis(): List<ItemBanco> = lista(
        "SELECT $COLUNAS FROM itens WHERE tipo='zim' AND (status='pronto' OR destino_antigo IS NOT NULL) ORDER BY ord"
    )

    fun zimsProntos(): List<ItemBanco> = lista(
        "SELECT $COLUNAS FROM itens WHERE tipo='zim' AND status='pronto' AND atualizavel=1 ORDER BY ord"
    )

    fun sobra(versao: String?): List<ItemBanco> = lista(
        "SELECT $COLUNAS FROM itens WHERE status='pronto' AND NOT (${querido(versao)}) AND pacote<>'opcional'"
    )

    fun contarProntos(tipo: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM itens WHERE tipo=? AND status='pronto'", arrayOf(tipo)
    ).use { it.moveToFirst(); it.getInt(0) }

    fun todos(): List<ItemBanco> = lista("SELECT $COLUNAS FROM itens")

    fun pdfsProntos(): List<ItemBanco> = lista("SELECT $COLUNAS FROM itens WHERE tipo='pdf' AND status='pronto'")

    fun reabrirInterrompidos() {
        writableDatabase.execSQL("UPDATE itens SET status='pendente' WHERE status='baixando'")
    }

    fun marcarBaixando(id: String) = atualizar(id, ContentValues().apply {
        put("status", Status.BAIXANDO); putNull("erro")
    })

    fun resolvido(id: String, url: String, tam: Long, destino: String) = atualizar(id, ContentValues().apply {
        put("url_atual", url); put("tam_atual", tam); put("destino_atual", destino)
    })

    /** Volta para a fila sem perder o que já baixou. */
    fun aguardar(id: String, n: Int, erro: String?) = atualizar(id, ContentValues().apply {
        put("status", Status.PENDENTE); put("tentativas", n); put("erro", erro)
    })

    fun tentativas(id: String, n: Int, erro: String?) = atualizar(id, ContentValues().apply {
        put("tentativas", n); put("erro", erro)
    })

    fun pronto(id: String, tamReal: Long) = atualizar(id, ContentValues().apply {
        put("status", Status.PRONTO); put("baixados", tamReal); put("tam_atual", tamReal)
        put("tentativas", 0); putNull("erro"); putNull("destino_antigo")
    })

    fun falhou(id: String, erro: String) = atualizar(id, ContentValues().apply {
        put("status", Status.FALHOU); put("erro", erro)
    })

    /** O arquivo sumiu do armazenamento: volta para a fila, sem drama. */
    fun voltarParaFila(id: String) = atualizar(id, ContentValues().apply {
        put("status", Status.PENDENTE); put("baixados", 0); put("tentativas", 0); putNull("erro")
    })

    fun novaVersaoZim(id: String, url: String, tam: Long, destino: String, antigo: String) =
        atualizar(id, ContentValues().apply {
            put("status", Status.PENDENTE); put("baixados", 0); put("tentativas", 0)
            put("url_atual", url); put("tam_atual", tam); put("destino_atual", destino)
            put("destino_antigo", antigo); putNull("erro")
        })

    fun apagado(id: String) = atualizar(id, ContentValues().apply {
        put("status", Status.PENDENTE); put("baixados", 0); put("tentativas", 0)
        putNull("url_atual"); putNull("tam_atual"); putNull("destino_atual"); putNull("erro")
    })

    fun bytes(valores: Map<String, Long>) {
        if (valores.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            valores.forEach { (id, b) ->
                db.execSQL("UPDATE itens SET baixados=? WHERE id=? AND status<>'pronto'", arrayOf(b, id))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun tentarDeNovo() {
        writableDatabase.execSQL("UPDATE itens SET status='pendente', tentativas=0, erro=NULL WHERE status='falhou'")
    }

    private fun atualizar(id: String, v: ContentValues) {
        writableDatabase.update("itens", v, "id=?", arrayOf(id))
    }
}
