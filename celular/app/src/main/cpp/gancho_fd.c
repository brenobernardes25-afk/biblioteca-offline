/*
 * Permite à libzim abrir um .zim pelo descritor da SAF, sem permissão de armazenamento.
 *
 * O problema: Archive(FileDescriptor) não usa o descritor recebido. A libzim monta o caminho
 * "/dev/fd/N" e chama open() nele. Isso obriga o kernel a conferir de novo a permissão do arquivo,
 * e o Android nega (EACCES) para quem só recebeu o descritor pela SAF.
 *
 * A solução: troca, só dentro da libzim.so, as chamadas a open() e __open_2(). Se o caminho for
 * "/dev/fd/N", devolve um dup(N), que é o que a libzim queria. Qualquer outro caminho segue normal.
 *
 * Parte da Biblioteca de Bolso, licença GPL-3.0.
 */
#include <android/log.h>
#include <fcntl.h>
#include <jni.h>
#include <link.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "BolsoGancho", __VA_ARGS__)
#define PREFIXO "/dev/fd/"

extern int __open_2(const char *, int);

static int duplicar(const char *caminho) {
    int fd = atoi(caminho + strlen(PREFIXO));
    return fcntl(fd, F_DUPFD_CLOEXEC, 0);
}

static int meu_open(const char *caminho, int flags, ...) {
    if (strncmp(caminho, PREFIXO, strlen(PREFIXO)) == 0) return duplicar(caminho);
    mode_t modo = 0;
    if (flags & O_CREAT) {
        va_list a;
        va_start(a, flags);
        modo = (mode_t) va_arg(a, int);
        va_end(a);
    }
    return open(caminho, flags, modo);
}

static int meu_open_2(const char *caminho, int flags) {
    if (strncmp(caminho, PREFIXO, strlen(PREFIXO)) == 0) return duplicar(caminho);
    return __open_2(caminho, flags);
}

#if defined(__LP64__)
typedef Elf64_Rela Reloc;
#define R_SYM(i) ELF64_R_SYM(i)
#define TEM_ADDEND 1
#else
typedef Elf32_Rel Reloc;
#define R_SYM(i) ELF32_R_SYM(i)
#define TEM_ADDEND 0
#endif

static int trocar(void **ponto, void *novo) {
    long pag = sysconf(_SC_PAGESIZE);
    void *inicio = (void *) ((uintptr_t) ponto & ~((uintptr_t) pag - 1));
    /* A tabela costuma estar protegida (RELRO) depois de carregada. */
    if (mprotect(inicio, (size_t) pag, PROT_READ | PROT_WRITE) != 0) return 0;
    *ponto = novo;
    __builtin___clear_cache((char *) ponto, (char *) (ponto + 1));
    mprotect(inicio, (size_t) pag, PROT_READ);
    return 1;
}

static int trocados;

static void percorrer(uintptr_t base, const Reloc *rel, size_t bytes, const ElfW(Sym) *sim, const char *txt) {
    if (!rel) return;
    for (size_t i = 0; i < bytes / sizeof(Reloc); i++) {
        const char *nome = txt + sim[R_SYM(rel[i].r_info)].st_name;
        void *novo = NULL;
        if (strcmp(nome, "open") == 0) novo = (void *) meu_open;
        else if (strcmp(nome, "__open_2") == 0) novo = (void *) meu_open_2;
        if (novo && trocar((void **) (base + rel[i].r_offset), novo)) trocados++;
    }
}

static int visitar(struct dl_phdr_info *info, size_t tam, void *dados) {
    (void) tam;
    (void) dados;
    const char *nome = info->dlpi_name;
    size_t n = nome ? strlen(nome) : 0;
    if (n < 9 || strcmp(nome + n - 9, "libzim.so") != 0) return 0;

    uintptr_t base = info->dlpi_addr;
    const ElfW(Dyn) *din = NULL;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC) din = (const ElfW(Dyn) *) (base + info->dlpi_phdr[i].p_vaddr);
    }
    if (!din) return 1;

    const ElfW(Sym) *sim = NULL;
    const char *txt = NULL;
    const Reloc *plt = NULL, *dat = NULL;
    size_t tplt = 0, tdat = 0;
    for (; din->d_tag != DT_NULL; din++) {
        uintptr_t v = (uintptr_t) din->d_un.d_ptr;
        uintptr_t p = v < base ? base + v : v;
        switch (din->d_tag) {
            case DT_SYMTAB: sim = (const ElfW(Sym) *) p; break;
            case DT_STRTAB: txt = (const char *) p; break;
            case DT_JMPREL: plt = (const Reloc *) p; break;
            case DT_PLTRELSZ: tplt = (size_t) din->d_un.d_val; break;
#if TEM_ADDEND
            case DT_RELA: dat = (const Reloc *) p; break;
            case DT_RELASZ: tdat = (size_t) din->d_un.d_val; break;
#else
            case DT_REL: dat = (const Reloc *) p; break;
            case DT_RELSZ: tdat = (size_t) din->d_un.d_val; break;
#endif
            default: break;
        }
    }
    if (!sim || !txt) return 1;
    percorrer(base, plt, tplt, sim, txt);
    percorrer(base, dat, tdat, sim, txt);
    return 1;
}

JNIEXPORT jint JNICALL
Java_br_com_waltz_bibliotecadebolso_zim_GanchoFd_instalarNativo(JNIEnv *env, jobject obj) {
    (void) env;
    (void) obj;
    trocados = 0;
    dl_iterate_phdr(visitar, NULL);
    LOG("open trocado em %d pontos da libzim", trocados);
    return trocados;
}
