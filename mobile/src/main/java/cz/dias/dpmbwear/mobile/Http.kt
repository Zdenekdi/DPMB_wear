package cz.dias.dpmbwear.mobile

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Sdílený HTTP klient telefonu. Vše jde přes mobilní připojení / Wi-Fi telefonu. */
object Http {

    const val GTFS_URL = "https://kordis-jmk.cz/gtfs/gtfs.zip"
    const val GTFS_RT_URL = "https://kordis-jmk.cz/gtfs/gtfsReal.dat"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // Jízdní řád má desítky MB; na pomalém připojení se stahuje déle.
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", "DPMBWear-Android/1.0 (+https://nexusstack.eu)")
                        .build()
                )
            }
            .build()
    }
}
