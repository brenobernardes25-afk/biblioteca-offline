// Ponte entre o app e o llama.cpp (seção 10). Uma resposta por vez; quem chama garante isso.
#include <android/log.h>
#include <jni.h>
#include <unistd.h>
#include <atomic>
#include <cstdio>
#include <string>
#include <vector>
#if defined(__aarch64__)
#include <asm/hwcap.h>
#include <sys/auxv.h>
#endif
#include "llama.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "BolsoIA", __VA_ARGS__)

namespace {
struct Sessao {
    llama_model *modelo = nullptr;
    llama_context *ctx = nullptr;
    std::atomic<bool> parar{false};
};

bool deveAbortar(void *dados) { return static_cast<Sessao *>(dados)->parar.load(); }

Sessao *abrirContexto(llama_model *modelo, int nCtx, int nucleos) {
    if (modelo == nullptr) return nullptr;
    auto *s = new Sessao();
    s->modelo = modelo;
    llama_context_params p = llama_context_default_params();
    p.n_ctx = nCtx;
    p.n_batch = nCtx; // A pergunta com os trechos vai de uma vez: passar disso derruba o llama.cpp.
    p.n_threads = nucleos;
    p.n_threads_batch = nucleos;
    p.no_perf = true;
    s->ctx = llama_init_from_model(modelo, p);
    if (s->ctx == nullptr) {
        LOGE("não criou o contexto");
        llama_model_free(modelo);
        delete s;
        return nullptr;
    }
    llama_set_abort_callback(s->ctx, deveAbortar, s);
    return s;
}
}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_br_com_waltz_bibliotecadebolso_ia_MotorIa_nativoIniciar(JNIEnv *, jobject) {
    llama_backend_init();
    llama_log_set([](ggml_log_level nivel, const char *texto, void *) {
        if (nivel == GGML_LOG_LEVEL_ERROR) LOGE("%s", texto);
    }, nullptr);
}

// O app foi compilado para armv8.2 com dotprod: em processador mais antigo, carregar daria falha fatal.
extern "C" JNIEXPORT jboolean JNICALL
Java_br_com_waltz_bibliotecadebolso_ia_MotorIa_nativoServe(JNIEnv *, jobject) {
#if defined(__aarch64__)
    unsigned long hw = getauxval(AT_HWCAP);
    return ((hw & HWCAP_ASIMDDP) != 0 && (hw & HWCAP_FPHP) != 0) ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_TRUE;
#endif
}

extern "C" JNIEXPORT jlong JNICALL
Java_br_com_waltz_bibliotecadebolso_ia_MotorIa_nativoCarregarCaminho(JNIEnv *env, jobject, jstring caminho, jint nCtx, jint nucleos) {
    const char *c = env->GetStringUTFChars(caminho, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    llama_model *m = llama_model_load_from_file(c, mp);
    env->ReleaseStringUTFChars(caminho, c);
    return reinterpret_cast<jlong>(abrirContexto(m, nCtx, nucleos));
}

// Do SSD: pelo descritor que a pessoa autorizou, lendo tudo para a memória. Sem mmap: o SSD pode sair.
extern "C" JNIEXPORT jlong JNICALL
Java_br_com_waltz_bibliotecadebolso_ia_MotorIa_nativoCarregarDescritor(JNIEnv *, jobject, jint fd, jint nCtx, jint nucleos) {
    int copia = dup(fd);
    if (copia < 0) return 0;
    FILE *f = fdopen(copia, "rb");
    if (f == nullptr) {
        close(copia);
        return 0;
    }
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_NONE;
    llama_model *m = llama_model_load_from_file_ptr(f, mp);
    fclose(f);
    return reinterpret_cast<jlong>(abrirContexto(m, nCtx, nucleos));
}

extern "C" JNIEXPORT void JNICALL
Java_br_com_waltz_bibliotecadebolso_ia_MotorIa_nativoLiberar(JNIEnv *, jobject, jlong h) {
    auto *s = reinterpret_cast<Sessao *>(h);
    if (s == nullptr) return;
    llama_free(s->ctx);
    llama_model_free(s->modelo);
    delete s;
}

extern "C" JNIEXPORT void JNICALL
Java_br_com_waltz_bibliotecadebolso_ia_MotorIa_nativoParar(JNIEnv *, jobject, jlong h) {
    auto *s = reinterpret_cast<Sessao *>(h);
    if (s != nullptr) s->parar.store(true);
}

// Devolve 0 se terminou, 1 se parou a pedido, 2 se o texto não coube e 3 em erro.
// Entrega bytes, não texto: um pedaço pode cortar um caractere UTF-8 ao meio.
extern "C" JNIEXPORT jint JNICALL
Java_br_com_waltz_bibliotecadebolso_ia_MotorIa_nativoGerar(JNIEnv *env, jobject self, jlong h, jstring prompt, jint maxTokens, jfloat temperatura) {
    auto *s = reinterpret_cast<Sessao *>(h);
    if (s == nullptr) return 3;
    s->parar.store(false);
    const llama_vocab *vocab = llama_model_get_vocab(s->modelo);
    const char *c = env->GetStringUTFChars(prompt, nullptr);
    std::string texto(c);
    env->ReleaseStringUTFChars(prompt, c);

    int n = -llama_tokenize(vocab, texto.c_str(), static_cast<int32_t>(texto.size()), nullptr, 0, true, true);
    if (n <= 0) return 3;
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(vocab, texto.c_str(), static_cast<int32_t>(texto.size()), tokens.data(), n, true, true) < 0) return 3;
    const int nCtx = static_cast<int>(llama_n_ctx(s->ctx));
    if (n + 16 >= nCtx) return 2;

    llama_memory_clear(llama_get_memory(s->ctx), true);

    llama_sampler *am = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(am, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(am, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(am, llama_sampler_init_temp(temperatura));
    llama_sampler_chain_add(am, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass classe = env->GetObjectClass(self);
    jmethodID aoPedaco = env->GetMethodID(classe, "aoPedaco", "([B)V");
    int resultado = 0;
    llama_batch lote = llama_batch_get_one(tokens.data(), n);
    int usados = n;
    llama_token atual;
    for (int i = 0; i <= maxTokens; i++) {
        if (s->parar.load()) { resultado = 1; break; }
        int r = llama_decode(s->ctx, lote);
        if (r == 2 || s->parar.load()) { resultado = 1; break; }
        if (r != 0) { LOGE("decode %d", r); resultado = 3; break; }
        if (i == maxTokens || usados + 1 >= nCtx) break;
        atual = llama_sampler_sample(am, s->ctx, -1);
        if (llama_vocab_is_eog(vocab, atual)) break;
        char buf[256];
        int t = llama_token_to_piece(vocab, atual, buf, sizeof(buf), 0, false);
        if (t > 0) {
            jbyteArray arr = env->NewByteArray(t);
            env->SetByteArrayRegion(arr, 0, t, reinterpret_cast<const jbyte *>(buf));
            env->CallVoidMethod(self, aoPedaco, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) { env->ExceptionClear(); resultado = 3; break; }
        }
        lote = llama_batch_get_one(&atual, 1);
        usados++;
    }
    llama_sampler_free(am);
    return resultado;
}
