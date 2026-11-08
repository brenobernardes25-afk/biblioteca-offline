package br.com.waltz.bibliotecadebolso.ia

import br.com.waltz.bibliotecadebolso.busca.Consulta

/** O que a IA recebe e como a resposta é tratada. As regras são as mesmas da biblioteca do PC. Sem Android: fácil de testar. */
data class ModeloIa(val id: String, val nomeArquivo: String, val tamanho: String, val limiteContexto: Int, val instruct: Boolean)

data class Trecho(
    val titulo: String,
    val tema: String,
    val idioma: String,
    val pagina: Int?,
    val texto: String,
    val posicao: Int,
    val manual: Boolean,
    /** Para abrir a fonte: caminho do PDF ou do artigo. */
    val caminho: String,
    val livroId: String? = null,
    val doSsd: Boolean = false,
)

object RegrasIa {
    const val NAO_ENCONTREI = "Não encontrei isso nas fontes da biblioteca."
    const val AVISO_SAUDE = "Confira no manual original. Não substitui atendimento médico."

    const val SISTEMA =
        "Você é o assistente da Biblioteca de Bolso, usada em situações de emergência sem internet. " +
            "Responda SEMPRE em português do Brasil, de forma prática, clara e curta: se for um procedimento, no " +
            "máximo 8 passos numerados de uma ou duas frases. Use SOMENTE as informações dos trechos numerados fornecidos " +
            "(alguns estão em inglês: traduza). No fim de cada frase, cite a fonte com o número entre colchetes, por " +
            "exemplo [1] ou [2][3]; não escreva \"Fonte:\" nem liste as fontes no fim. Ignore os trechos que não tratam " +
            "do assunto da pergunta. Não use conhecimento de fora dos trechos e não invente. Somente se NENHUM trecho " +
            "tratar do assunto, responda apenas: \"$NAO_ENCONTREI\" e, numa segunda linha, sugira palavras para " +
            "buscar. Se você respondeu com base nos trechos, termine na última informação, sem avisos nem sugestões."

    private val MODELO_4B = ModeloIa("ia:qwen3-4b", "Qwen3-4B-Instruct-2507-Q4_K_M.gguf", "2,5 GB", 3000, true)

    /**
     * Só o 4B, em celular de 8 GB. O Qwen3 1.7B (6 GB) saiu: no teste, errou as quantidades do soro caseiro mesmo com a
     * receita inteira no trecho. Quem tem 6 GB fica com a busca, que mostra o texto original.
     * totalMem fica abaixo do valor anunciado: um celular de 8 GB informa cerca de 7,4 GB.
     */
    fun modeloPara(ramBytes: Long): ModeloIa? = if (ramBytes >= 7_000_000_000L) MODELO_4B else null

    val todos get() = listOf(MODELO_4B)

    fun nucleos(disponiveis: Int) = (disponiveis / 2).coerceIn(2, 4)

    /** Corta o fim da palavra: "queimaduras" acha "queimadura". */
    fun raiz(t: String) = if (t.length <= 5) t else t.dropLast(if (t.length > 7) 2 else 1)

    /** A parte da página com mais palavras da pergunta, começando numa linha. */
    fun janela(texto: String, termos: List<String>, tamanho: Int = TAM_TRECHO): String {
        if (texto.length <= tamanho) return texto.trim()
        val baixo = Consulta.semAcento(texto).lowercase()
        val raizes = termos.map { raiz(it) }
        var melhor = -1
        var pos = 0
        val passo = maxOf(200, tamanho / 4)
        var ini = 0
        while (ini < maxOf(1, texto.length - tamanho + passo)) {
            val j = baixo.substring(ini, minOf(baixo.length, ini + tamanho))
            val nota = raizes.sumOf { r -> j.windowed(r.length).count { it == r } } + 3 * raizes.count { it in j }
            if (nota > melhor) {
                melhor = nota
                pos = ini
            }
            ini += passo
        }
        val k = texto.lastIndexOf('\n', pos)
        if (k >= 0 && pos - k <= 150) pos = k + 1
        return texto.substring(pos, minOf(texto.length, pos + tamanho)).trim()
    }

    /**
     * Reordena os candidatos pela cobertura da pergunta. Manual prático tem preferência.
     * Abaixo da nota mínima fica de fora: melhor dizer que não achou do que inventar a partir de um trecho que só cita a palavra.
     */
    fun escolher(candidatos: List<Trecho>, termosPt: List<String>, termosEn: List<String>): List<Trecho> {
        val notas = candidatos.mapNotNull { t ->
            val ts = (if (t.idioma == "EN") termosEn else termosPt).map { raiz(it) }.distinct()
            if (ts.isEmpty() || t.texto.isBlank()) return@mapNotNull null
            val base = Consulta.semAcento(t.texto + " " + t.titulo).lowercase()
            val titulo = Consulta.semAcento(t.titulo).lowercase()
            val cobertura = ts.count { it in base }.toDouble() / ts.size
            val frequencia = ts.sumOf { r -> minOf(3, base.windowed(r.length).count { it == r }) } / (3.0 * ts.size)
            val noTitulo = minOf(0.3, 0.1 * ts.count { it in titulo })
            val nota = cobertura + 0.4 * frequencia + noTitulo + 0.25 / (1 + t.posicao) + if (t.manual) 0.15 else 0.0
            t to nota
        }.sortedByDescending { it.second }
        val porDoc = HashMap<String, Int>()
        val vistos = HashSet<String>()
        val saida = ArrayList<Trecho>()
        for ((t, nota) in notas) {
            if (nota < NOTA_MINIMA || !vistos.add(t.caminho + "#" + t.pagina)) continue
            if ((porDoc[t.titulo] ?: 0) >= 2) continue
            porDoc[t.titulo] = (porDoc[t.titulo] ?: 0) + 1
            saida += t
        }
        return saida
    }

    /** Numera os trechos até o limite. Quem não couber não entra, nem na lista de fontes. */
    fun contexto(trechos: List<Trecho>, limite: Int): Pair<String, List<Trecho>> {
        val partes = ArrayList<String>()
        val usados = ArrayList<Trecho>()
        var total = 0
        for (t in trechos) {
            val cab = t.titulo + (t.pagina?.let { " (página $it)" } ?: "")
            val resto = limite - total
            if (resto < 300) break
            var corpo = t.texto.trim()
            if (corpo.length > resto) corpo = corpo.take(resto).substringBeforeLast(' ') + "…"
            usados += t
            partes += "[${usados.size}] $cab\n$corpo"
            total += corpo.length + cab.length
        }
        return partes.joinToString("\n\n") to usados
    }

    /** Formato do Qwen3. Os modelos sem "Instruct" pensam antes de responder, a não ser que se peça o contrário. */
    fun prompt(modelo: ModeloIa, contexto: String, pergunta: String): String = buildString {
        append("<|im_start|>system\n").append(SISTEMA).append("<|im_end|>\n")
        append("<|im_start|>user\nTRECHOS:\n\n").append(contexto).append("\n\nPERGUNTA: ").append(pergunta)
        if (!modelo.instruct) append(" /no_think")
        append("<|im_end|>\n<|im_start|>assistant\n")
        if (!modelo.instruct) append("<think>\n\n</think>\n\n")
    }

    /** Esconde o raciocínio, se algum modelo ainda o escrever, mesmo com a tag partida entre pedaços. */
    fun semPensamento(t: String): String {
        var r = t
        while (true) {
            val i = r.indexOf("<think>")
            if (i < 0) break
            val f = r.indexOf("</think>", i)
            r = if (f < 0) r.substring(0, i) else r.substring(0, i) + r.substring(f + 8)
        }
        val quase = listOf("<think", "<thin", "<thi", "<th", "<t", "<").firstOrNull { r.endsWith(it) }
        return (if (quase != null) r.removeSuffix(quase) else r).trimStart()
    }

    private val PALAVRAS_SAUDE = Regex(
        "\\b(dor|febre|sangr|ferid|queimad|picad|mordid|remedio|medicament|dose|gravid|parto|bebe|crianca|infec|veneno|" +
            "desmai|convuls|engasg|afog|infarto|avc|diabet|pressao|alergi|fratur|respir|diarre|vomit|doenc|sintoma|tratamento)",
    )

    fun ehSaude(pergunta: String, usados: List<Trecho>) =
        PALAVRAS_SAUDE.containsMatchIn(Consulta.semAcento(pergunta).lowercase()) ||
            usados.any { Consulta.semAcento(it.tema).lowercase().startsWith("saude") }

    /** Texto puro de um artigo: tira script, estilo e marcação. */
    fun textoDoHtml(html: String): String = html
        .replace(Regex("(?is)<(script|style|head)\\b.*?</\\1>"), " ")
        .replace(Regex("(?i)</(p|div|li|h[1-6]|tr|br)\\s*>|<br\\s*/?>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\s*\\n\\s*"), "\n")
        .trim()

    /** Com 750, como no PC, uma receita podia ser cortada ao meio, e a IA inventava o resto. */
    const val TAM_TRECHO = 1100
    private const val NOTA_MINIMA = 0.45
}
