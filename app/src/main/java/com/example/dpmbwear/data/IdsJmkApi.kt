package com.example.dpmbwear.data

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface IdsJmkApi {
    @GET("api/Departures")
    suspend fun getDepartures(
        @Query("stopid") stopId: String,
        @Header("User-Agent") userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        @Header("Referer") referer: String = "https://mapa.idsjmk.cz/",
        @Header("Accept") accept: String = "application/json, text/plain, */*",
        @Header("X-Requested-With") requestedWith: String = "XMLHttpRequest",
        @Header("Sec-Fetch-Site") secFetchSite: String = "same-origin",
        @Header("Sec-Fetch-Mode") secFetchMode: String = "cors",
        @Header("Sec-Fetch-Dest") secFetchDest: String = "empty"
    ): DeparturesResponse
}

data class DeparturesResponse(
    val PostList: List<PostNode>?
)

data class PostNode(
    val Departures: List<ApiDeparture>?
)

data class ApiDeparture(
    val LineName: String?,
    val FinalStop: String?,
    val TimeMark: String?, // např. "2" nebo "15:45"
    val IsLive: Boolean?
)

object NetworkClient {
    val api: IdsJmkApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://mapa.idsjmk.cz/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(IdsJmkApi::class.java)
    }
}
