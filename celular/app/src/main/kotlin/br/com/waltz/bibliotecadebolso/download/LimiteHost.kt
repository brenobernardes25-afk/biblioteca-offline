package br.com.waltz.bibliotecadebolso.download

/**
 * Educação com os servidores. O web.archive.org responde 429 se recebe pedidos em sequência, e 205
 * PDFs do Ministério da Saúde vêm de lá. Servidor "cuidadoso" recebe um pedido por vez, com intervalo;
 * qualquer servidor que peça para ir devagar (429/503) entra em espera e passa a ser tratado assim.
 */
class LimiteHost(private val agora: () -> Long = System::currentTimeMillis) {
    private val cuidadosos = hashSetOf("web.archive.org")
    private val ativos = HashMap<String, Int>()
    private val ultimoInicio = HashMap<String, Long>()
    private val freioAte = HashMap<String, Long>()
    private val freadas = HashMap<String, Int>()

    /** Servidores conhecidos que agora não podem receber mais um pedido. */
    @Synchronized
    fun indisponiveis(): Set<String> {
        val t = agora()
        val r = HashSet<String>()
        freioAte.forEach { (h, ate) -> if (t < ate) r += h }
        cuidadosos.forEach { h ->
            val cheio = (ativos[h] ?: 0) >= 1
            val cedo = t - (ultimoInicio[h] ?: 0L) < INTERVALO_MS
            if (cheio || cedo) r += h
        }
        return r
    }

    /** Servidor que mandou esperar e ainda não liberou. */
    @Synchronized
    fun emEspera(): String? = freioAte.entries.firstOrNull { agora() < it.value }?.key

    @Synchronized
    fun comecou(host: String) {
        ativos[host] = (ativos[host] ?: 0) + 1
        ultimoInicio[host] = agora()
    }

    @Synchronized
    fun terminou(host: String, sucesso: Boolean) {
        ativos[host] = ((ativos[host] ?: 1) - 1).coerceAtLeast(0)
        if (sucesso) freadas.remove(host)
    }

    /** Põe em espera: 1, 2, 4, 8, até 15 min. Devolve true quando já está na espera máxima. */
    @Synchronized
    fun frear(host: String, retryAfterS: Long?): Boolean {
        cuidadosos += host
        val n = freadas[host] ?: 0
        val espera = minOf(ESPERA_BASE_MS shl n.coerceAtMost(5), ESPERA_MAX_MS)
        val pedida = (retryAfterS ?: 0).coerceAtMost(ESPERA_MAX_MS / 1000) * 1000
        freioAte[host] = agora() + maxOf(espera, pedida)
        freadas[host] = n + 1
        return espera >= ESPERA_MAX_MS
    }

    companion object {
        /** O archive.org aceita ~15 pedidos/min e cada download pode gastar 2 ou 3 (redirecionamentos). */
        const val INTERVALO_MS = 15_000L
        const val ESPERA_BASE_MS = 60_000L
        const val ESPERA_MAX_MS = 15 * 60_000L
    }
}
