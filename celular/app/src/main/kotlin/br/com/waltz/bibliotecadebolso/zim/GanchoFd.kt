package br.com.waltz.bibliotecadebolso.zim

/**
 * Faz a libzim aceitar o descritor da SAF. Detalhes em src/main/cpp/gancho_fd.c.
 * Tem que ser instalado depois de a libzim estar carregada.
 */
object GanchoFd {
    private external fun instalarNativo(): Int

    /** Quantos pontos foram trocados. Zero significa que abrir pelo SSD não vai funcionar. */
    val pontos: Int by lazy {
        try {
            System.loadLibrary("bolsofd")
            instalarNativo()
        } catch (_: Throwable) {
            0
        }
    }
}
