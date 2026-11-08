package br.com.waltz.bibliotecadebolso.ssd

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class FilhoSaf(val nome: String, val tam: Long, val pasta: Boolean, val modificado: Long)

/**
 * Acesso à pasta que a pessoa escolheu no SSD. O provedor de armazenamento usa o caminho como
 * identificador ("ABCD-1234:5_Acervo/x.pdf"), então não precisa percorrer pasta por pasta: com 54 mil
 * arquivos, isso faz diferença.
 */
class ArvoreSaf(private val ctx: Context, val arvore: Uri) {
    private val cr get() = ctx.contentResolver
    private val base = caminhoBase(DocumentsContract.getTreeDocumentId(arvore))
    private val pastasProntas = ConcurrentHashMap.newKeySet<String>()
    private val travaPasta = Any()

    fun uri(rel: String): Uri = DocumentsContract.buildDocumentUriUsingTree(arvore, base + rel)

    /** Null se não existe ou se o SSD saiu. */
    fun info(rel: String): FilhoSaf? = try {
        cr.query(
            uri(rel),
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) null
            else FilhoSaf(
                c.getString(0) ?: rel.substringAfterLast('/'), c.getLong(1),
                c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, c.getLong(3),
            )
        }
    } catch (_: Exception) {
        null
    }

    /** Lança exceção se a pasta sumiu: é assim que dá para saber que o SSD foi desconectado. */
    fun listar(rel: String = ""): List<FilhoSaf> {
        val doc = (base + rel).let { if (rel.isEmpty()) DocumentsContract.getTreeDocumentId(arvore) else it }
        val filhos = DocumentsContract.buildChildDocumentsUriUsingTree(arvore, doc)
        val r = ArrayList<FilhoSaf>()
        val c = cr.query(
            filhos,
            arrayOf(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        ) ?: throw IOException("a pasta não respondeu")
        c.use {
            while (it.moveToNext()) {
                r += FilhoSaf(
                    it.getString(0) ?: continue, it.getLong(1),
                    it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, it.getLong(3),
                )
            }
        }
        return r
    }

    fun ler(rel: String): ParcelFileDescriptor =
        cr.openFileDescriptor(uri(rel), "r") ?: throw FileNotFoundException(rel)

    /** Daqui para baixo só a montagem usa. Conectar um SSD pronto nunca escreve nada nele. */
    fun escrever(rel: String, continuar: Boolean): ParcelFileDescriptor =
        cr.openFileDescriptor(uri(rel), if (continuar) "wa" else "wt") ?: throw FileNotFoundException(rel)

    fun garantirPasta(rel: String) {
        if (rel.isEmpty() || rel in pastasProntas) return
        synchronized(travaPasta) {
            if (rel in pastasProntas) return
            val existe = info(rel)
            if (existe == null) {
                val pai = rel.substringBeforeLast('/', "")
                garantirPasta(pai)
                DocumentsContract.createDocument(
                    cr, uriDaPasta(pai), DocumentsContract.Document.MIME_TYPE_DIR, rel.substringAfterLast('/'),
                ) ?: throw IOException("não consegui criar a pasta $rel")
            } else if (!existe.pasta) {
                throw IOException("$rel já existe e não é uma pasta")
            }
            pastasProntas += rel
        }
    }

    private fun uriDaPasta(rel: String): Uri =
        if (rel.isEmpty()) DocumentsContract.buildDocumentUriUsingTree(arvore, DocumentsContract.getTreeDocumentId(arvore))
        else uri(rel)

    /** Cria se não existir. O tipo genérico impede que o provedor mude a extensão do nome. */
    fun garantirArquivo(rel: String) {
        if (info(rel) != null) return
        val pai = rel.substringBeforeLast('/', "")
        garantirPasta(pai)
        DocumentsContract.createDocument(cr, uriDaPasta(pai), "application/octet-stream", rel.substringAfterLast('/'))
            ?: throw IOException("não consegui criar $rel")
    }

    fun apagar(rel: String) {
        try {
            DocumentsContract.deleteDocument(cr, uri(rel))
        } catch (_: Exception) {
            // Já não estava lá.
        }
    }

    fun renomear(rel: String, novoNome: String) {
        DocumentsContract.renameDocument(cr, uri(rel), novoNome) ?: throw IOException("não consegui renomear $rel")
    }

    companion object {
        /** "ABCD:" fica "ABCD:"; "ABCD:pasta" fica "ABCD:pasta/": é o começo do id de cada arquivo. */
        fun caminhoBase(idDaArvore: String): String =
            if (idDaArvore.endsWith(":") || idDaArvore.endsWith("/")) idDaArvore else "$idDaArvore/"
    }
}
