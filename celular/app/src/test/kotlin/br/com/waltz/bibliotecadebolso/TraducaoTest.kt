package br.com.waltz.bibliotecadebolso

import br.com.waltz.bibliotecadebolso.traducao.Tradutor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class TraducaoTest {
    @Test
    fun textoCurtoNaoMuda() {
        assertEquals("water", Tradutor.limitar("water"))
    }

    @Test
    fun textoLongoCortaEntrePalavras() {
        val r = Tradutor.limitar("palavra ".repeat(10_000))
        assertTrue(r.length <= Tradutor.LIMITE)
        assertTrue(r.endsWith("palavra"))
    }

    @Test
    fun juntaAFraseQuebradaEntreLinhas() {
        assertEquals(
            "Try to get information on the kinds of snakes in your area.\nPOISONOUS\nSNAKE",
            Tradutor.juntarLinhas("Try to get information on the kinds of\nsnakes in your area.\nPOISONOUS\nSNAKE"),
        )
        assertEquals("Boil the water before drinking", Tradutor.juntarLinhas("Boil the wa-\nter before drinking"))
        assertEquals("Um.\n\nDois.", Tradutor.juntarLinhas("Um.\n\nDois."))
    }

    @Test
    fun soOfereceQuemTraduz() {
        assertTrue(Tradutor.pareceTradutor("com.google.android.apps.translate", "Traduzir"))
        assertTrue(Tradutor.pareceTradutor("org.exemplo", "Tradutor Offline"))
        assertFalse(Tradutor.pareceTradutor("com.google.android.marvin.talkback", "Ler em voz alta"))
    }

    @Test
    fun modeloEstaInteiroNosArquivosDoApp() {
        val pasta = File("src/main/assets/tradutor")
        val reg = JSONObject(File(pasta, "registry.json").readText()).getJSONObject("enpt")
        for (k in listOf("model", "lex", "vocab")) {
            val o = reg.getJSONObject(k)
            val f = File(pasta, o.getString("name"))
            assertEquals(o.getLong("size"), f.length())
            val h = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(o.getString("expectedSha256Hash"), h)
        }
        for (n in listOf("index.html", "translator.js", "worker/translator-worker.js", "worker/bergamot-translator-worker.wasm", "LEIA-ME.txt")) {
            assertTrue(n, File(pasta, n).length() > 0)
        }
    }
}
