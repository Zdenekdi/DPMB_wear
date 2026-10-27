package cz.dias.dpmbwear.data

import cz.dias.dpmbwear.BuildConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * API KORDIS JMK.
 *
 * POZOR – stav k srpnu 2026: KORDIS zavedl technická opatření, která pouštějí
 * k datům jen smluvní partnery. Dřívější varianta tohoto rozhraní se vydávala
 * za prohlížeč (podvržený User-Agent, Referer, Sec-Fetch-*) – to už nefunguje
 * a ani se to dělat nemá. Přístup je teď přes přidělený klíč.
 *
 * Přesný způsob předání klíče zatím není potvrzený. Až přijde ze smlouvy,
 * upraví se JEN [applyKey] níže – nikde jinde v aplikaci se klíč nevyskytuje.
 */
interface IdsJmkApi {

    @GET("api/Departures")
    suspend fun getDepartures(
        @Query("stopid") stopId: String,
        @Query("key") key: String,
    ): DeparturesResponse
}

data class DeparturesResponse(
    val PostList: List<PostNode>?,
)

data class PostNode(
    val Departures: List<ApiDeparture>?,
)

data class ApiDeparture(
    val LineName: String?,
    val FinalStop: String?,
    /** Buď počet minut ("2"), nebo absolutní čas ("15:45"). */
    val TimeMark: String?,
    val IsLive: Boolean?,
)

object NetworkClient {

    /** Prázdný klíč znamená, že smlouva ještě neběží a jede se na ukázkových datech. */
    val hasApiKey: Boolean get() = BuildConfig.IDS_API_KEY.isNotBlank()

    val apiKey: String get() = BuildConfig.IDS_API_KEY

    /**
     * Bez explicitních timeoutů čeká OkHttp na čtení donekonečna. Na hodinkách,
     * které se běžně dostanou mimo dosah telefonu i wifi, to znamená obrazovku
     * zaseknutou na "Načítám" bez konce.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val api: IdsJmkApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://mapa.idsjmk.cz/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(IdsJmkApi::class.java)
    }
}
