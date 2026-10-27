import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Přístupový klíč do API KORDIS JMK.
 *
 * Čte se z local.properties, který je v .gitignore – klíč tak nikdy neskončí
 * v repozitáři. Pozor: v APK být musí, aby se s ním dalo dotazovat, a odtud
 * ho lze dekompilací získat. Proti zneužití klíče to tedy nechrání, chrání to
 * jen git historii.
 */
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val idsApiKey: String = localProps.getProperty("IDS_API_KEY") ?: ""

/**
 * Vlastní server nad otevřenými daty KORDIS JMK (složka server/).
 * Když je adresa vyplněná, má přednost před API s klíčem.
 *
 *   DPMB_SERVER_URL=https://dpmb.example.cz/
 *   DPMB_SERVER_KEY=stejny-klic-jako-API_KEY-na-serveru
 */
val dpmbServerUrl: String = localProps.getProperty("DPMB_SERVER_URL") ?: ""
val dpmbServerKey: String = localProps.getProperty("DPMB_SERVER_KEY") ?: ""

android {
    namespace = "cz.dias.dpmbwear"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "cz.dias.dpmbwear"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "IDS_API_KEY", "\"$idsApiKey\"")
        buildConfigField("String", "DPMB_SERVER_URL", "\"$dpmbServerUrl\"")
        buildConfigField("String", "DPMB_SERVER_KEY", "\"$dpmbServerKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling)
    implementation(libs.core.splashscreen)
    implementation(libs.play.services.wearable)
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.wear.tooling.preview)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.wear:wear-input:1.2.0-alpha02")
    implementation(libs.zxing)
    implementation(libs.wear.compose.navigation)

    // Lifecycle – explicitně, ne jen tranzitivně přes wear-compose-navigation.
    // viewModel() a collectAsStateWithLifecycle() by jinak zmizely při jakékoli
    // změně navigační závislosti.
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Coroutines explicitně – používají se napříč aplikací, ale dosud sem
    // padaly jen tranzitivně přes lifecycle a Compose.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Networking
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    // OkHttp explicitně kvůli timeoutům – bez nich čeká Retrofit na hodinkách
    // se slabým signálem defaultních 10 s na připojení a donekonečna na čtení.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Location
    implementation("com.google.android.gms:play-services-location:21.2.0")

    // Komplikace na ciferník
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")

    // Tiles
    implementation("androidx.wear.tiles:tiles:1.4.1")
    implementation("androidx.wear.tiles:tiles-material:1.4.1")
    implementation("androidx.wear.protolayout:protolayout:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-material:1.2.1")
    implementation("androidx.wear.protolayout:protolayout-expression:1.2.1")
    implementation("com.google.guava:guava:31.1-android")

    // Čisté JVM testy logiky, která selhává tiše (parsování časů, stárnutí cache).
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.test.manifest)
    debugImplementation(libs.ui.tooling)
}