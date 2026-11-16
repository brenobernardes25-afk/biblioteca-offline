import java.security.KeyStore
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Windows não aceita caminhos com mais de 260 caracteres, e o CMake passa disso em pastas muito
// fundas. Nesse caso: gradlew assembleRelease -PpastaCurta=C:/temp/bolso
// O APK sai em <pastaCurta>/build/outputs/apk/. Em pastas normais, não precisa.
val pastaCurta: String? = providers.gradleProperty("pastaCurta").orNull
pastaCurta?.let { layout.buildDirectory.set(file("$it/build")) }

// ASSINATURA FIXA. O Android só instala uma atualização por cima se ela for assinada com a MESMA chave do
// app já instalado. A chave do app publicado fica fora do repositório; o arquivo assinatura.properties
// (também fora do repositório, ver assinatura.properties.exemplo) diz onde ela está. Sem esse arquivo, o
// release sai com a chave de depuração de quem compilou e NÃO instala por cima do app publicado.
val assinatura = Properties().apply {
    rootProject.file("assinatura.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
// Impressão digital (SHA-256) do certificado do app publicado no GitHub. É pública: qualquer um a vê no APK.
val impressaoPublicada = "6C:2C:62:D0:6C:F3:C1:39:EB:A2:69:10:0B:CF:87:04:02:F8:39:EF:95:1D:6A:E5:0A:44:7B:FC:C4:DC:1A:15"

fun impressaoDaChave(arquivo: File, senha: String, apelido: String): String {
    val ks = KeyStore.getInstance(KeyStore.getDefaultType())
    arquivo.inputStream().use { ks.load(it, senha.toCharArray()) }
    val cert = ks.getCertificate(apelido) ?: throw GradleException("A chave ${arquivo.name} não tem o apelido \"$apelido\".")
    return MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }
}

android {
    namespace = "br.com.waltz.bibliotecadebolso"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    signingConfigs {
        assinatura.getProperty("arquivo")?.let { caminho ->
            create("publicacao") {
                storeFile = file(caminho)
                storePassword = assinatura.getProperty("senha")
                keyAlias = assinatura.getProperty("apelido")
                keyPassword = assinatura.getProperty("senhaDaChave") ?: assinatura.getProperty("senha")
            }
        }
    }

    defaultConfig {
        applicationId = "br.com.waltz.bibliotecadebolso"
        minSdk = 26
        targetSdk = 36
        versionCode = 17
        versionName = "1.2.8"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            // Só para testar no emulador: gradlew assembleRelease -Pemulador=true
            if (project.hasProperty("emulador")) abiFilters += "x86_64"
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += "x86_64" }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Com assinatura.properties: a chave do app publicado. Sem ele: a de depuração de quem compila.
            signingConfig = signingConfigs.findByName("publicacao") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    buildFeatures { compose = true }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
            // Ver "pastaCurta" no começo do arquivo.
            pastaCurta?.let { buildStagingDirectory = file("$it/cxx") }
        }
    }
    packaging {
        // Comprime as bibliotecas nativas no APK (a libkiwix tem ~50 MB por arquitetura).
        jniLibs { useLegacyPackaging = true }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.documentfile:documentfile:1.1.0")
    // SQLite própria, com FTS5: a do Android não garante (seção 3.1). Apache-2.0, da AndroidX.
    implementation("androidx.sqlite:sqlite-bundled:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // GPL-3.0: é por causa dela que o app inteiro é GPL-3.0.
    implementation("org.kiwix:libkiwix:2.7.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20250517")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

// Trava de segurança da publicação: gradlew assembleRelease -Ppublicar=true só termina se a chave usada for
// a do app publicado. Evita soltar, por engano, uma versão que ninguém consegue instalar por cima.
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        if (!project.hasProperty("publicar")) return@doFirst
        val arq = assinatura.getProperty("arquivo")
            ?: throw GradleException("Para publicar, crie o assinatura.properties (veja assinatura.properties.exemplo).")
        val achada = impressaoDaChave(rootProject.file(arq), assinatura.getProperty("senha"), assinatura.getProperty("apelido"))
        if (achada != impressaoPublicada) {
            throw GradleException("A chave em $arq NÃO é a do app publicado.\n  esperada: $impressaoPublicada\n  achada:   $achada")
        }
        println("Assinatura conferida: é a chave do app publicado.")
    }
}
