plugins {
    alias(libs.plugins.android.application)
}

/**
 * Aplikace pro telefon: proxy mezi KORDIS JMK a hodinkami.
 *
 * DŮLEŽITÉ: applicationId i podpisový klíč musí být STEJNÉ jako u aplikace
 * pro hodinky (:app). Jinak se přes Wearable Data Layer neuvidí.
 */
android {
    namespace = "cz.dias.dpmbwear.mobile"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "cz.dias.dpmbwear"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.play.services.wearable)
    implementation("com.google.android.gms:play-services-location:21.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
