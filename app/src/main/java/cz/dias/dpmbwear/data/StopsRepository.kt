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
 * Se serverem se nejbližší zastávky ptají serveru – ten zná všechny zastávky
 * IDS JMK ze stops.txt. Bez serveru (ukázková data, API s klíčem) zůstává
 * pět natvrdo zadaných zastávek.
 *
 * Každou zastávku ze serveru si repository zapamatuje. Dlaždice a komplikace
 * znají jen ID oblíbené zastávky a potřebují k ní název, aniž by kvůli tomu
 * budily rádio.
 */
class StopsRepository(
    context: Context,
    private val api: DpmbServerApi? = if (ServerClient.isConfigured) ServerClient.api else null,
) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Zastávky seřazené podle vzdálenosti. Bez serveru ze statického seznamu. */
    suspend fun nearest(lat: Double, lon: Double, limit: Int): List<Stop> {
        val server = api ?: return nearestStatic(lat, lon, limit)

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

    /** Zastávka podle ID: nejdřív zapamatované ze serveru, pak statický seznam. */
    fun byId(id: String): Stop? =
        rememberedStop(id) ?: staticStops.find { it.id == id }

    /** Poslední zobrazená zastávka. Dlaždice ji použije, když není žádná oblíbená. */
    var lastStopId: String?
        get() = prefs.getString(KEY_LAST, null)
        set(value) {
            prefs.edit().putString(KEY_LAST, value).apply()
        }

    /**
     * Zastávka pro dlaždici a komplikaci: oblíbená, jinak poslední zobrazená,
     * jinak první ze statického seznamu.
     */
    fun preferredStop(favouriteIds: Set<String>): Stop {
        favouriteIds.asSequence().mapNotNull(::byId).firstOrNull()?.let { return it }
        lastStopId?.let(::byId)?.let { return it }
        return staticStops[0]
    }

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
