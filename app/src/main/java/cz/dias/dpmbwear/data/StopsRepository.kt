package cz.dias.dpmbwear.data

import android.content.Context
import cz.dias.dpmbwear.data.model.Stop
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Zastávky.
 *
 * Pořadí: telefon (zná všechny zastávky IDS JMK z jízdního řádu) → server,
 * je-li nastavený → zapamatované zastávky → pět natvrdo zadaných zastávek
 * pro ukázková data.
 *
 * Každou zastávku z telefonu nebo serveru si repository zapamatuje. Dlaždice a komplikace
 * znají jen ID oblíbené zastávky a potřebují k ní název, aniž by kvůli tomu
 * budily rádio.
 */
class StopsRepository(
    context: Context,
    private val api: DpmbServerApi? = if (ServerClient.isConfigured) ServerClient.api else null,
    private val phone: PhoneBridge? = PhoneBridge.get(context),
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Zastávky seřazené podle vzdálenosti.
     *
     * @param lat poloha hodinek, nebo null. Telefonu se null pošle tak, jak
     *   je – použije svou polohu. Výchozí bod (Česká) platí jen pro server,
     *   paměť a statický seznam; telefonu by vnutil zastávky kolem České.
     */
    suspend fun nearest(lat: Double?, lon: Double?, limit: Int): List<Stop> {
        phone?.let { bridge ->
            try {
                // Telefon v téže odpovědi pošle i odjezdy těchto zastávek, takže
                // následné načtení odjezdů už přes Bluetooth nejde.
                val found = bridge.request(lat, lon, stopId = null, maxStops = limit).payload.stops
                    .map { Stop(it.id, it.name, it.lat, it.lon) }
                if (found.isNotEmpty()) {
                    found.forEach(::remember)
                    return found.take(limit)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Telefon není po ruce nebo Wearable API chybí – zkusí se
                // server nebo paměť.
            }
        }
        return nearestWithoutPhone(lat ?: staticStops[0].lat, lon ?: staticStops[0].lon, limit)
    }

    private suspend fun nearestWithoutPhone(lat: Double, lon: Double, limit: Int): List<Stop> {
        val server = api ?: return rememberedStops()
            .sortedBy { distanceKm(lat, lon, it.lat, it.lon) }
            .take(limit)
            .ifEmpty { nearestStatic(lat, lon, limit) }

        return try {
            val found = server.nearbyStops(lat, lon, limit).stops.orEmpty().mapNotNull { it.toStop() }
            if (found.isEmpty()) throw IOException("Server nevrátil žádné zastávky")
            found.forEach(::remember)
            found
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Bez signálu: zastávky, které už jsme ze serveru někdy dostali.
            // Statický seznam tu použít nejde – má ID ze starého API, která
            // server nezná.
            val known = rememberedStops()
            if (known.isEmpty()) throw e
            known.sortedBy { distanceKm(lat, lon, it.lat, it.lon) }.take(limit)
        }
    }

    /**
     * Zastávka podle ID: nejdřív zapamatovaná ze serveru, pak statický seznam.
     *
     * Statický seznam jen bez serveru: má ID ze starého API ("1017"), na která
     * server odpoví 404, a dlaždice by pak navždy ukazovala prázdno.
     */
    fun byId(id: String): Stop? =
        rememberedStop(id) ?: staticStops.find { it.id == id }.takeIf { staticAllowed() }

    /** Poslední zobrazená zastávka. Dlaždice ji použije, když není žádná oblíbená. */
    var lastStopId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) {
            prefs.edit().putString(KEY_LAST, value).apply()
        }

    /**
     * Zastávka pro dlaždici a komplikaci.
     *
     * Pořadí: poslední zobrazená, pokud je oblíbená → jiná oblíbená → poslední
     * zobrazená → (jen bez serveru) první statická. Oblíbené jsou v HashSetu,
     * takže "první oblíbená" je náhodná; poslední zobrazená oblíbená je
     * nejlepší odhad, kterou uživatel chce.
     *
     * Null = se serverem a před prvním otevřením aplikace zatím žádnou
     * zastávku neznáme. Volající ukáže "otevři aplikaci".
     */
    fun preferredStop(favouriteIds: Set<String>): Stop? {
        val last = lastStopId
        if (last != null && last in favouriteIds) byId(last)?.let { return it }
        favouriteIds.asSequence().mapNotNull(::byId).firstOrNull()?.let { return it }
        last?.let(::byId)?.let { return it }
        return staticStops[0].takeIf { staticAllowed() }
    }

    /**
     * Statická ID ("1017") jsou ze starého API a telefon ani server je neznají.
     * Použijí se, jen dokud aplikace nikdy nedostala skutečné zastávky
     * (ukázková data bez telefonu i serveru).
     */
    private fun staticAllowed(): Boolean = api == null && prefs.all.keys.none { it.startsWith(KEY_STOP) }

    private fun remember(stop: Stop) {
        prefs.edit().putString(KEY_STOP + stop.id, encode(stop)).apply()
    }

    private fun rememberedStop(id: String): Stop? =
        prefs.getString(KEY_STOP + id, null)?.let { decode(id, it) }

    private fun rememberedStops(): List<Stop> =
        prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(KEY_STOP) && value is String) decode(key.removePrefix(KEY_STOP), value) else null
        }

    companion object {
        private const val PREFS_NAME = "dpmb_stops"
        private const val KEY_STOP = "stop_"
        private const val KEY_LAST = "last_stop"

        val staticStops = listOf(
            Stop("1017", "Česká", 49.196, 16.606),
            Stop("1084", "Hlavní nádraží", 49.190, 16.611),
            Stop("1133", "Mendlovo náměstí", 49.191, 16.593),
            Stop("1269", "Uzlová", 49.180, 16.630),
            Stop("1100", "Královo Pole, nádraží", 49.231, 16.595),
        )

        fun nearestStatic(lat: Double, lon: Double, limit: Int): List<Stop> =
            staticStops.sortedBy { distanceKm(lat, lon, it.lat, it.lon) }.take(limit)

        fun ServerStop.toStop(): Stop? {
            val stopId = this.id?.takeIf { it.isNotBlank() } ?: return null
            val stopLat = this.lat ?: return null
            val stopLon = this.lon ?: return null
            return Stop(stopId, this.name.orEmpty(), stopLat, stopLon)
        }

        /** Tabulátor se v názvu zastávky nevyskytuje, takže stačí jako oddělovač. */
        internal fun encode(stop: Stop): String = "${stop.name}\t${stop.lat}\t${stop.lon}"

        internal fun decode(id: String, value: String): Stop? {
            val parts = value.split('\t')
            if (parts.size != 3) return null
            val lat = parts[1].toDoubleOrNull() ?: return null
            val lon = parts[2].toDoubleOrNull() ?: return null
            return Stop(id, parts[0], lat, lon)
        }

        /** Haversine. Na městské vzdálenosti bohatě stačí. */
        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earthRadiusKm = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            return earthRadiusKm * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
