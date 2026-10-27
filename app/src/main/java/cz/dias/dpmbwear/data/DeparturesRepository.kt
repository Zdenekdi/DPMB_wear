package cz.dias.dpmbwear.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import cz.dias.dpmbwear.data.model.Departure
import cz.dias.dpmbwear.data.model.DeparturesResult
import kotlinx.coroutines.CancellationException

/**
 * Načítání odjezdů s cache.
 *
 * Na hodinkách se se ztrátou signálu počítá jako s normálním stavem, ne jako
 * s chybou. Když síť selže, vrátí se poslední známý stav označený jako
 * zastaralý – prázdná obrazovka je horší než odjezdy s poznámkou "před 3 min".
 */
class DeparturesRepository(
    context: Context,
    private val sources: List<DeparturesSource> = defaultSources(context),
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    @Volatile
    private var lastWasDemo = sources.firstOrNull()?.isDemo ?: true

    /**
     * False znamená, že uživatel kouká na ukázková data a UI to musí přiznat.
     * Mění se podle toho, který zdroj naposledy odpověděl.
     */
    val isUsingRealData: Boolean get() = !lastWasDemo

    /**
     * Zkouší zdroje v pořadí: telefon → server (je-li nastavený).
     *
     * Než dojde na ukázková data, má přednost poslední známý stav z cache,
     * označený jako zastaralý. Odjezdy z telefonu před pěti minutami jsou
     * užitečnější než vymyšlené – a cache už minuty sama posune.
     */
    suspend fun load(stopId: String): DeparturesResult {
        var lastError: Exception? = null
        for (source in sources) {
            if (source.isDemo) {
                readCache(stopId)?.let {
                    lastWasDemo = false
                    return it
                }
            }
            try {
                val fresh = source.getDepartures(stopId)
                val now = System.currentTimeMillis()
                // Ukázková data se do cache nikdy nedostanou, jinak by se
                // později tvářila jako zastaralá ostrá data.
                if (!source.isDemo) writeCache(stopId, fresh, now)
                lastWasDemo = source.isDemo
                return DeparturesResult(stopId, fresh, fetchedAt = now, adjustedAt = now, isStale = false)
            } catch (e: CancellationException) {
                // Zrušení (obrazovka odešla do pozadí) není výpadek sítě. Bez
                // tohohle by se ukázalo "Bez signálu" při plně funkční síti.
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        readCache(stopId)?.let {
            lastWasDemo = false
            return it
        }
        throw lastError ?: IllegalStateException("Žádný zdroj odjezdů")
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
         * Pořadí zdrojů:
         *  1. telefon jako proxy (aplikace :mobile) – hlavní cesta,
         *  2. vlastní server (DPMB_SERVER_URL) nebo API KORDIS s klíčem
         *     (IDS_API_KEY), pokud jsou nastavené – pro hodinky na Wi-Fi/LTE
         *     bez telefonu,
         *  3. ukázková data, aby šla aplikace vyzkoušet i bez telefonu.
         */
        fun defaultSources(context: Context): List<DeparturesSource> = buildList {
            add(PhoneDeparturesSource(PhoneBridge.get(context)))
            when {
                ServerClient.isConfigured -> add(ServerDeparturesSource())
                NetworkClient.hasApiKey -> add(IdsJmkDeparturesSource())
            }
            add(FakeDeparturesSource())
        }
    }
}
