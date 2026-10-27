plugins {
    alias(libs.plugins.android.library)
}

/**
 * Sdílené jádro pro telefon i hodinky, v čistém Kotlinu bez Android API:
 *  - formát zpráv mezi telefonem a hodinkami (WatchPayload, PayloadCodec),
 *  - převod GTFS, čtení GTFS-RT, odhad zpoždění, odjezdy (balíček transit).
 *
 * Testy běží na JVM: ./gradlew :shared:testDebugUnitTest
 */
android {
    namespace = "cz.dias.dpmbwear.shared"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // java.time je od API 26; telefon má minSdk 29, hodinky 30.
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
