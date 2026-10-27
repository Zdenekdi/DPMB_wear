package cz.dias.dpmbwear.data

import cz.dias.dpmbwear.data.model.Stop
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Seznam zastávek.
 *
 * DOČASNÉ: pět natvrdo zadaných zastávek. IDS JMK jich má v Brně přes tisíc.
 * Po napojení na smluvní data se sem načte stops.txt z GTFS (data.brno.cz,
 * aktualizace každou neděli) – nejspíš do Room, aby šlo hledat podle polohy
 * bez procházení celého seznamu.
 */
object StopsRepository {

    val staticStops = listOf(
        Stop("1017", "Česká", 49.196, 16.606),
        Stop("1084", "Hlavní nádraží", 49.190, 16.611),
        Stop("1133", "Mendlovo náměstí", 49.191, 16.593),
        Stop("1269", "Uzlová", 49.180, 16.630),
        Stop("1100", "Královo Pole, nádraží", 49.231, 16.595),
    )

    /** Zastávky seřazené podle vzdálenosti od zadané polohy. */
    fun nearest(lat: Double, lon: Double, limit: Int): List<Stop> =
        staticStops.sortedBy { distanceKm(lat, lon, it.lat, it.lon) }.take(limit)

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
