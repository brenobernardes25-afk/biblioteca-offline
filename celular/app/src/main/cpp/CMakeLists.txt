cmake_minimum_required(VERSION 3.22.1)
project(bolsofd C CXX)
add_library(bolsofd SHARED gancho_fd.c)
target_compile_options(bolsofd PRIVATE -Wall -O2)
# Páginas de 16 KB: exigência do Android 15+.
target_link_options(bolsofd PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(bolsofd log)

# IA (seção 10). Só em 64 bits: um aparelho de 32 bits não tem memória para os modelos.
# Mesma versão do llama.cpp que a biblioteca do PC usa. Licença MIT.
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64")
    include(FetchContent)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        # O app confere o processador antes de carregar (nativoServe): sem dotprod, a IA não é oferecida.
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    else()
        # Só para o emulador.
        set(GGML_AVX2 ON CACHE BOOL "" FORCE)
        set(GGML_FMA ON CACHE BOOL "" FORCE)
        set(GGML_F16C ON CACHE BOOL "" FORCE)
        set(GGML_BMI2 ON CACHE BOOL "" FORCE)
    endif()
    FetchContent_Declare(
        llama
        URL https://github.com/ggml-org/llama.cpp/archive/refs/tags/b11889.tar.gz
        URL_HASH SHA256=634e76016ffb363659d160acb8ac70852e20b78ef11df43cd3221322c3a68f3b
    )
    FetchContent_MakeAvailable(llama)

    add_library(bolsoia SHARED ia_jni.cpp)
    target_compile_options(bolsoia PRIVATE -Wall -O2)
    target_link_options(bolsoia PRIVATE -Wl,-z,max-page-size=16384)
    target_link_libraries(bolsoia PRIVATE llama log)
endif()
