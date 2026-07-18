// Configuracao de build do modulo "app" (o app Android em si).
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun configValue(name: String, defaultValue: String = ""): String {
    return (project.findProperty(name) as String?)
        ?: localProperties.getProperty(name)
        ?: System.getenv(name)
        ?: defaultValue
}

fun String.asBuildConfigString(): String {
    return "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

android {
    namespace = "com.pedro.screenshare"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pedro.screenshare"
        // minSdk 24 (Android 7) cobre a grande maioria dos aparelhos.
        // OBS: recursos de MediaProjection em Foreground Service tipado
        // (foregroundServiceType="mediaProjection") e a permissao
        // POST_NOTIFICATIONS so existem a partir da API 29/33 - o codigo
        // trata essas diferencas em tempo de execucao (ver PermissionUtils.kt
        // e ScreenCaptureService.kt).
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "SIGNALING_SERVER_URL",
            configValue("signalingServerUrl", "ws://192.168.18.12:3000").asBuildConfigString()
        )
        buildConfigField(
            "String",
            "SIGNALING_AUTH_TOKEN",
            configValue("signalingAuthToken").asBuildConfigString()
        )
        buildConfigField(
            "String",
            "STUN_SERVER_URL",
            configValue("stunServerUrl", "stun:stun.l.google.com:19302").asBuildConfigString()
        )
        buildConfigField(
            "String",
            "TURN_SERVER_URL",
            configValue("turnServerUrl").asBuildConfigString()
        )
        buildConfigField(
            "String",
            "TURN_USERNAME",
            configValue("turnUsername").asBuildConfigString()
        )
        buildConfigField(
            "String",
            "TURN_PASSWORD",
            configValue("turnPassword").asBuildConfigString()
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Armazenamento local seguro (EncryptedSharedPreferences) - usado para
    // salvar o papel do aparelho (TRANSMISSOR/VISUALIZADOR) de forma
    // criptografada. Ver data/LocalConfigManager.kt.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Cliente WebSocket usado para conversar com o backend de sinalizacao.
    // Ver signaling/SignalingClient.kt.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Biblioteca do WebRTC para Android (captura, codifica e envia o video em
    // tempo real). Ver webrtc/PeerConnectionFactoryProvider.kt.
    // NOTA DE MANUTENCAO: o Google parou de publicar o artefato classico
    // "org.webrtc:google-webrtc" em um repositorio estavel (era distribuido
    // via jcenter, hoje desativado). Por isso usamos aqui o fork mantido pela
    // GetStream, que publica no Maven Central com o MESMO pacote Java
    // "org.webrtc.*" (ou seja, todo o resto do codigo deste projeto continua
    // igual). Se esta versao especifica nao existir mais quando voce ler
    // isso, veja a versao mais recente em:
    // https://github.com/GetStream/webrtc-android (aba "Releases").
    implementation("io.getstream:stream-webrtc-android:1.1.1")
}
