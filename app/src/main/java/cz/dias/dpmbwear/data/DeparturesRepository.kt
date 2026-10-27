package cz.dias.dpmbwear.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import cz.dias.dpmbwear.data.model.Departure
import cz.dias.dpmbwear.data.model.DeparturesResult

/**
 * Načítání odjezdů s cache.
 *
 * Na hodinkách se se ztrátou signálu počítá jako s normálním stavem, ne jako
 * s chybou. Když síť selže, vrátí se poslední známý stav označený jako
 * zastaralý – prázdná obrazovka je horší než odjezdy s poznámkou "před 3 min".
 */
class DeparturesRepository(
    context: Context,
    private val source: DeparturesSource = defaultSource(),
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** False znamená, že uživatel kouká na ukázková data a UI to musí přiznat. */
    val isUsingRealData: Boolean = source is IdsJmkDeparturesSource

    suspend fun load(stopId: String): DeparturesResult {
        return try {
            val fresh = source.getDepartures(stopId)
            val now = System.currentTimeMillis()
            writeCache(stopId, fresh, now)
            DeparturesResult(stopId, fresh, fetchedAt = now, adjustedAt = now, isStale = false)
        } catch (e: Exception) {
            // Síť selhala – zkusíme poslední známý stav.
            readCache(stopId)?.let { return it }
            throw e
        }
    }

    /** Poslední uložený stav bez pokusu o síť. Používá dlaždice a komplikace. */
    fun cached(stopId: String): DeparturesResult? = readCache(stopId)

    private fun writeCache(stopId: String, departures: List<Departure>, at: Long) {
        prefs.edit()
            .putString(keyData(stopId), gson.toJson(departures))
            .putLong(keyTime(stopId), at)
            .apply()
    }

    private fun readCache(stopId: String): DeparturesResult? {
        val json = prefs.getString(keyData(stopId), null) ?: return null
        val fetchedAt = prefs.getLong(keyTime(stopId), 0L)
        if (fetchedAt == 0L) return null

        val stored: List<Departure> = try {
            gson.fromJson(json, object : TypeToken<List<Departure>>() {}.type) ?: return null
        } catch (e: Exception) {
            return null
        }

        val now = System.currentTimeMillis()
        val ageMinutes = ((now - fetchedAt) / 60_000L).toInt()
        if (ageMinutes > MAX_CACHE_AGE_MINUTES) return null

        // adjustedAt = teď, protože minuty už jsou posunuté na aktuální okamžik.
        // Kdyby se sem dalo fetchedAt, konzument by stáří odečetl podruhé.
        return DeparturesResult(
            stopId = stopId,
            departures = adjustForAge(stored, ageMinutes),
            fetchedAt = fetchedAt,
            adjustedAt = now,
            isStale = true,
        )
    }

    private fun keyData(stopId: String) = "departures_$stopId"
    private fun keyTime(stopId: String) = "departures_time_$stopId"

    companion object {
        private const val PREFS_NAME = "dpmb_departures_cache"

        /**
         * Posune uložené odjezdy o stáří cache a zahodí ty, které už odjely.
         *
         * Uložené minuty platily v okamžiku stažení. Bez tohoto posunu by cache
         * tvrdila "za 3 min" i deset minut poté, co spoj odjel – a vypadalo by
         * to naprosto důvěryhodně.
         *
         * Vytažené ven jako čistá funkce, aby šlo otestovat bez SharedPreferences.
         */
        fun adjustForAge(departures: List<Departure>, ageMinutes: Int): List<Departure> =
            departures
                .map { it.copy(timeMinutes = it.timeMinutes - ageMinutes) }
                .filter { it.timeMinutes >= 0 }

        /** Starší data už nemají cenu zobrazovat, spoje dávno odjely. */
        private const val MAX_CACHE_AGE_MINUTES = 30

        /**
         * Bez přiděleného klíče od KORDISu nemá ostrý zdroj co dělat – API
         * pouští jen smluvní partnery a vracelo by chybu.
         */
        fun defaultSource(): DeparturesSource =
            if (NetworkClient.hasApiKey) IdsJmkDeparturesSource() else FakeDeparturesSource()
    }
}
