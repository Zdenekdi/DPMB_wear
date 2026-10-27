package cz.dias.dpmbwear.shared.transit

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Odhad zpoždění z polohy vozidla.
 *
 * Feed KORDISu zpoždění neobsahuje, jen polohy. Zpoždění se proto odhaduje:
 * vozidlo v půli cesty mezi zastávkami tam podle jízdního řádu mělo být
 * v půli mezi odjezdem z první a příjezdem do druhé. Rozdíl je zpoždění.
 *
 * Meze, které z odhadu vyřazují nesmysly:
 *  - polohy starší 15 min se ignorují,
 *  - "náskok" přes 8 min = vozidlo má spoj jen předpřiřazený a ještě
 *    dojíždí předchozí; zpoždění přes 3 h = chyba přiřazení,
 *  - stejné vozidlo hlášené na víc spojích (varianty kalendáře): vyhrává
 *    spoj, na který poloha nejlépe sedí.
 *
 * Stejná logika jako v server/dpmb_server/realtime.py.
 */
class LiveTrip(
    /** Sekundy, záporné = náskok. */
    val delay: Int,
    /** Index zastávky ve spoji, na které vozidlo stojí nebo ke které jede; -1 = neznámý. */
    val position: Int,
    val stopped: Boolean,
    val timestamp: Long,
    /** True = odhad z polohy, false = zpoždění přímo z feedu. */
    val estimated: Boolean,
) {
    /** Spoj už zastávkou na pozici [pos] projel. */
    fun hasPassed(pos: Int): Boolean = position >= 0 && pos < position
}

class RealtimeIndex internal constructor(private val trips: Map<Long, LiveTrip>) {
    fun live(trip: Int, day: LocalDate): LiveTrip? = trips[key(trip, day)]
    val size: Int get() = trips.size

    internal companion object {
        fun key(trip: Int, day: LocalDate): Long = (trip.toLong() shl 32) or (day.toEpochDay() and 0xFFFFFFFFL)
    }
}

object RealtimeEstimator {

    const val MAX_POSITION_AGE_S = 15 * 60
    const val MIN_DELAY_S = -8 * 60
    const val MAX_DELAY_S = 3 * 3600
    private const val AT_STOP_RADIUS_M = 35.0

    private val PADDED_STOP = Regex("^U0*(\\d+)Z0*(\\d+)$")

    /** Realtime feed posílá ID s nulami navíc: U01472Z02 → U1472Z2. */
    fun normalizeStopId(id: String): String =
        PADDED_STOP.matchEntire(id)?.let { "U${it.groupValues[1]}Z${it.groupValues[2]}" } ?: id

    /**
     * @param onlyTrips když je zadané, počítá se jen pro vozidla, která mají
     *   aspoň jeden z těchto spojů. Telefon tak při dotazu z hodinek nepočítá
     *   celé město, jen spoje, které odjíždějí z požadovaných zastávek.
     */
    fun build(tt: Timetable, feed: FeedSnapshot, now: Long, onlyTrips: Set<Int>? = null): RealtimeIndex {
        val vehicles = feed.vehicles.groupBy { it.vehicleLabel }
        val best = HashMap<String, Triple<Long, Long, LiveTrip>>() // vozidlo → (fit, key, live)

        for ((label, observations) in vehicles) {
            if (onlyTrips != null && observations.none { o -> tt.tripIndex(o.tripId)?.let { it in onlyTrips } == true }) continue
            for (o in observations) {
                val ts = if (o.timestamp > 0) o.timestamp else if (feed.timestamp > 0) feed.timestamp else now
                if (now - ts > MAX_POSITION_AGE_S) continue
                val trip = tt.tripIndex(o.tripId) ?: continue
                val stop = o.stopId?.let { tt.stopIndex(normalizeStopId(it)) } ?: -1
                val stopped = o.status == VehicleObservation.STOPPED_AT && stop >= 0
                val fix = locate(tt, trip, ts, o.lat, o.lon, stop, stopped, ServiceTime.parseGtfsDate(o.startDate)) ?: continue
                val delay = estimateDelay(tt, trip, fix, ts) ?: continue
                if (delay < MIN_DELAY_S || delay > MAX_DELAY_S) continue
                // Rozhoduje, jak dobře poloha sedí na řád, ne výsledné zpoždění:
                // vozidlo jedoucí na výchozí zastávku dalšího spoje má zpoždění 0
                // z definice, a přesto je to špatný kandidát.
                val fit = abs(ts - fix.scheduled).toLong()
                val prev = best[label]
                if (prev == null || fit < prev.first) {
                    best[label] = Triple(fit, RealtimeIndex.key(trip, fix.day), LiveTrip(delay, fix.position, fix.stopped, ts, true))
                }
            }
        }

        val trips = HashMap<Long, LiveTrip>()
        // Spřažené tramvaje = dvě vozidla na jednom spoji. Stačí první.
        for ((_, key, live) in best.values) trips.putIfAbsent(key, live)

        for (u in feed.tripUpdates) {
            val trip = tt.tripIndex(u.tripId) ?: continue
            if (onlyTrips != null && trip !in onlyTrips) continue
            val delay = u.delay ?: continue
            val ts = if (u.timestamp > 0) u.timestamp else feed.timestamp
            val day = ServiceTime.parseGtfsDate(u.startDate) ?: ServiceTime.localDate(if (ts > 0) ts else now)
            val key = RealtimeIndex.key(trip, day)
            val old = trips[key]
            trips[key] = LiveTrip(delay, old?.position ?: -1, old?.stopped ?: false, ts, estimated = false)
        }
        return RealtimeIndex(trips)
    }

    internal class Fix(val day: LocalDate, val position: Int, val stopped: Boolean, val scheduled: Double)

    internal fun locate(
        tt: Timetable, trip: Int, ts: Long, lat: Double, lon: Double,
        stop: Int, stopped: Boolean, startDate: LocalDate?,
    ): Fix? {
        val days = startDate?.let { listOf(it) } ?: ServiceTime.neighbours(ServiceTime.localDate(ts))
        var best: Fix? = null
        for (day in days) {
            if (!tt.activeServices(day)[tt.tripService(trip)]) continue
            val fix = if (stop >= 0) fixByStop(tt, trip, day, stop, ts, stopped, lat, lon)
            else fixByGeometry(tt, trip, day, ts, lat, lon)
            if (fix != null && (best == null || abs(ts - fix.scheduled) < abs(ts - best.scheduled))) best = fix
        }
        if (best == null || abs(ts - best.scheduled) > 6 * 3600) return null
        return best
    }

    private fun fixByStop(
        tt: Timetable, trip: Int, day: LocalDate, stop: Int, ts: Long,
        stopped: Boolean, lat: Double, lon: Double,
    ): Fix? {
        val r0 = tt.tripRowStart(trip)
        val r1 = tt.tripRowEnd(trip)
        val base = ServiceTime.dayStart(day)
        var best: Fix? = null
        for (r in r0 until r1) {
            if (tt.rowStop(r) != stop) continue
            val p = r - r0
            val arr = base + tt.rowArr(r)
            val dep = base + tt.rowDep(r)
            val sched: Double = if (stopped || p == 0) {
                ts.coerceIn(arr, dep).toDouble()
            } else {
                val prev = r - 1
                val ps = tt.rowStop(prev)
                val f = project(lat, lon, tt.stopLat(ps), tt.stopLon(ps), tt.stopLat(stop), tt.stopLon(stop)).first
                val prevDep = base + tt.rowDep(prev)
                prevDep + f * (arr - prevDep)
            }
            val fix = Fix(day, p, stopped, sched)
            if (best == null || abs(ts - fix.scheduled) < abs(ts - best.scheduled)) best = fix
        }
        return best
    }

    /** Bez ID zastávky: najde úsek trasy, ke kterému má vozidlo nejblíž. */
    private fun fixByGeometry(tt: Timetable, trip: Int, day: LocalDate, ts: Long, lat: Double, lon: Double): Fix? {
        val r0 = tt.tripRowStart(trip)
        val r1 = tt.tripRowEnd(trip)
        if (r1 - r0 < 2) return null
        val base = ServiceTime.dayStart(day)
        var best: Fix? = null
        var bestKey: Pair<Long, Double>? = null
        for (r in r0 + 1 until r1) {
            val p = r - r0
            val a = tt.rowStop(r - 1)
            val b = tt.rowStop(r)
            val (f, dist) = project(lat, lon, tt.stopLat(a), tt.stopLon(a), tt.stopLat(b), tt.stopLon(b))
            val segLen = segmentLength(tt.stopLat(a), tt.stopLon(a), tt.stopLat(b), tt.stopLon(b))
            val prevArr = base + tt.rowArr(r - 1)
            val prevDep = base + tt.rowDep(r - 1)
            val arr = base + tt.rowArr(r)
            val dep = base + tt.rowDep(r)
            val fix = when {
                dist <= AT_STOP_RADIUS_M && f * segLen <= AT_STOP_RADIUS_M ->
                    Fix(day, p - 1, true, ts.coerceIn(prevArr, prevDep).toDouble())
                dist <= AT_STOP_RADIUS_M && (1 - f) * segLen <= AT_STOP_RADIUS_M ->
                    Fix(day, p, true, ts.coerceIn(arr, dep).toDouble())
                else -> Fix(day, p, false, prevDep + f * (arr - prevDep))
            }
            // Vzdálenost s tolerancí 50 m, při shodě rozhoduje čas.
            val key = (dist / 50.0).roundToInt().toLong() to abs(ts - fix.scheduled)
            if (bestKey == null || key.first < bestKey.first ||
                (key.first == bestKey.first && key.second < bestKey.second)
            ) {
                best = fix; bestKey = key
            }
        }
        return best
    }

    internal fun estimateDelay(tt: Timetable, trip: Int, fix: Fix, ts: Long): Int? {
        val r0 = tt.tripRowStart(trip)
        val n = tt.tripRowEnd(trip) - r0
        val base = ServiceTime.dayStart(fix.day)
        val p = fix.position
        val arr = base + tt.rowArr(r0 + p)
        val dep = base + tt.rowDep(r0 + p)
        if (fix.stopped) {
            // Stojí na konečné dlouho po příjezdu: spoj skončil, zpoždění nevíme.
            if (p == n - 1 && ts > arr + 120) return null
            return when {
                ts < arr -> (ts - arr).toInt()
                ts <= dep -> 0
                else -> (ts - dep).toInt()
            }
        }
        // Jede na výchozí zastávku: zpožděný je, až když už měl odjet.
        if (p == 0) return maxOf(0L, ts - dep).toInt()
        return (ts - fix.scheduled).roundToInt()
    }

    // ---------- geometrie v lokální rovině (na vzdálenost dvou zastávek stačí) ----------

    private fun xy(lat: Double, lon: Double, lat0: Double, lon0: Double): Pair<Double, Double> =
        (lon - lon0) * cos(Math.toRadians(lat0)) * 111_320.0 to (lat - lat0) * 110_540.0

    /** Promítne bod na úsečku a→b. Vrací (podíl 0..1, vzdálenost od úsečky v m). */
    internal fun project(lat: Double, lon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Pair<Double, Double> {
        val (bx, by) = xy(bLat, bLon, aLat, aLon)
        val (px, py) = xy(lat, lon, aLat, aLon)
        val seg2 = bx * bx + by * by
        val f = if (seg2 == 0.0) 0.0 else ((px * bx + py * by) / seg2).coerceIn(0.0, 1.0)
        return f to hypot(px - f * bx, py - f * by)
    }

    private fun segmentLength(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val (x, y) = xy(bLat, bLon, aLat, aLon)
        return hypot(x, y)
    }
}
