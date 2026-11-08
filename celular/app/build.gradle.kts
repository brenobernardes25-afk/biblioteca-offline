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

android {
    namespace = "br.com.waltz.bibliotecadebolso"
    compileSdk = 36
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "br.com.waltz.bibliotecadebolso"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.0.0"
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
            // Provisório até a fase 6 (publicação): assinado com a chave de depuração,
            // só para o Breno conseguir instalar o APK e testar.
            signingConfig = signingConfigs.getByName("debug")
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
