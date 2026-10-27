package cz.dias.dpmbwear.data

import cz.dias.dpmbwear.BuildConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Vlastní server DPMB Wear (složka server/ v repozitáři).
 *
 * Server počítá odjezdy z otevřených dat KORDIS JMK (GTFS + GTFS-RT), takže
 * nepotřebuje smlouvu ani klíč od KORDISu. Hodinky dostanou jen hotový seznam
 * odjezdů pro jednu zastávku – celý jízdní řád má desítky MB.
 */
interface DpmbServerApi {

    @GET("v1/stops/nearby")
    suspend fun nearbyStops(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Query("limit") limit: Int,
    ): StopsResponse

    @GET("v1/departures")
    suspend fun departures(
        @Query("stop") stopId: String,
        @Query("limit") limit: Int = 15,
    ): ServerDeparturesResponse
}

data class StopsResponse(val stops: List<ServerStop>?)

data class ServerStop(
    val id: String?,
    val name: String?,
    val lat: Double?,
    val lon: Double?,
    val distanceM: Int?,
)

data class ServerDeparturesResponse(
    val stop: ServerStop?,
    val departures: List<ServerDeparture>?,
    val attribution: String?,
)

data class ServerDeparture(
    val line: String?,
    val destination: String?,
    val platform: String?,
    val scheduledTime: String?,
    val minutes: Int?,
    /** Null = o spoji nemáme živá data, platí jízdní řád. */
    val delayMinutes: Int?,
    val isLive: Boolean?,
    /** True = zpoždění je odhad z polohy vozidla, ne hlášení dopravce. */
    val isEstimate: Boolean?,
)

object ServerClient {

    /** Prázdná adresa = server není nastavený a aplikace použije jiný zdroj. */
    val isConfigured: Boolean get() = BuildConfig.DPMB_SERVER_URL.isNotBlank()

    val api: DpmbServerApi by lazy {
        // Retrofit vyžaduje adresu zakončenou lomítkem; v local.properties se
        // na to snadno zapomene.
        val base = BuildConfig.DPMB_SERVER_URL.trim().let { if (it.endsWith("/")) it else "$it/" }
        val key = BuildConfig.DPMB_SERVER_KEY

        val client = NetworkClient.httpClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                if (key.isNotBlank()) request.header("X-Api-Key", key)
                chain.proceed(request.build())
            }
            .build()

        Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DpmbServerApi::class.java)
    }
}
