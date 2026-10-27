package cz.dias.dpmbwear.shared.transit

import cz.dias.dpmbwear.shared.DeparturesRequest
import cz.dias.dpmbwear.shared.PayloadDeparture
import cz.dias.dpmbwear.shared.PayloadError
import cz.dias.dpmbwear.shared.PayloadStop
import cz.dias.dpmbwear.shared.WatchPayload
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Sestaví odpověď pro hodinky: nejbližší zastávky a jejich odjezdy.
 *
 * Postup: nejdřív odjezdy podle jízdního řádu, pak živá data jen pro spoje,
 * které v nich jsou. Telefon tak nepočítá zpoždění celého kraje.
 */
object DepartureBoard {

    /** Spoje, které podle řádu odjely před chvílí, můžou být zpožděné a pořád před námi. */
    const val LOOKBEHIND_S = 30 * 60

    /** Odjezd, který „odjel“ před méně než 30 s, ještě ukážeme. */
    const val GRACE_S = 30

    const val WINDOW_S = 90 * 60

    internal class Candidate(val trip: Int, val day: LocalDate, val position: Int, val scheduled: Long)

    /**
     * @param feed živá data, nebo null (telefon offline / feed nedostupný)
     * @param feedAgeS stáří živých dat v sekundách
     */
    fun answer(
        tt: Timetable,
        request: DeparturesRequest,
        now: Long,
        feed: FeedSnapshot?,
        feedAgeS: Int = 0,
    ): WatchPayload {
        val groups = selectGroups(tt, request)
            ?: return WatchPayload(request.id, now, -1, PayloadError.STOP_NOT_FOUND)

        val candidates = groups.associate { (g, _) -> g to scheduled(tt, g, now) }
        val live = feed?.let { f ->
            val trips = candidates.values.flatten().mapTo(HashSet()) { it.trip }
            RealtimeEstimator.build(tt, f, now, onlyTrips = trips)
        }

        val stops = groups.map { (g, dist) ->
            PayloadStop(
                id = tt.groupId(g),
                name = tt.groupName(g),
                lat = tt.groupLat(g),
                lon = tt.groupLon(g),
                distanceM = dist?.roundToInt() ?: -1,
                departures = departures(tt, candidates.getValue(g), now, live).take(request.perStop),
            )
        }
        return WatchPayload(
            requestId = request.id,
            generatedAt = now,
            realtimeAgeS = if (feed != null) feedAgeS.coerceAtLeast(0) else -1,
            stops = stops,
        )
    }

    /** Pořadí: požadovaná zastávka, pak nejbližší. Null = nic nenalezeno. */
    internal fun selectGroups(tt: Timetable, r: DeparturesRequest): List<Pair<Int, Double?>>? {
        val out = ArrayList<Pair<Int, Double?>>()
        val hasPosition = r.lat != null && r.lon != null
        r.stopId?.let { id ->
            tt.groupIndex(id)?.let { g ->
                val dist = if (hasPosition) Timetable.distanceM(r.lat!!, r.lon!!, tt.groupLat(g), tt.groupLon(g)) else null
                out.add(g to dist)
            }
        }
        if (hasPosition) {
            for ((g, d) in tt.nearbyGroups(r.lat!!, r.lon!!, r.maxStops + 1)) {
                if (out.size >= r.maxStops) break
                if (out.none { it.first == g }) out.add(g to d)
            }
        }
        return out.takeIf { it.isNotEmpty() }
    }

    internal fun scheduled(tt: Timetable, group: Int, now: Long, windowS: Int = WINDOW_S): List<Candidate> {
        val start = tt.depStart(group)
        val end = tt.depEnd(group)
        val out = ArrayList<Candidate>()
        for (day in ServiceTime.neighbours(ServiceTime.localDate(now))) {
            val base = ServiceTime.dayStart(day)
            val active = tt.activeServices(day)
            // Index je seřazený podle času odjezdu, takže stačí binárně najít začátek okna.
            var i = lowerBound(tt, start, end, (now - LOOKBEHIND_S - base).toInt())
            while (i < end) {
                val sched = base + tt.depTime(i)
                if (sched > now + windowS) break
                val trip = tt.depTrip(i)
                if (active[tt.tripService(trip)]) out.add(Candidate(trip, day, tt.depPosition(i), sched))
                i++
            }
        }
        return out
    }

    private fun lowerBound(tt: Timetable, from: Int, to: Int, time: Int): Int {
        var lo = from
        var hi = to
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tt.depTime(mid) < time) lo = mid + 1 else hi = mid
        }
        return lo
    }

    internal fun departures(tt: Timetable, candidates: List<Candidate>, now: Long, live: RealtimeIndex?): List<PayloadDeparture> {
        val out = ArrayList<Pair<Long, PayloadDeparture>>()
        for (c in candidates) {
            val l = live?.live(c.trip, c.day)
            if (l != null && l.hasPassed(c.position)) continue
            // Náskok do času nepromítáme: vozidlo na zastávce obvykle počká a
            // hlášený náskok bývá spíš chyba přiřazení. Radši o minutu dřív na
            // zastávce než ujetý spoj.
            val predicted = if (l != null) c.scheduled + maxOf(0, l.delay) else c.scheduled
            if (predicted < now - GRACE_S || predicted > now + WINDOW_S) continue
            out.add(predicted to PayloadDeparture(
                line = tt.tripLine(c.trip),
                destination = tt.destination(c.trip),
                epoch = predicted,
                delayMin = l?.let { (it.delay / 60.0).roundToInt() },
            ))
        }
        return out.sortedWith(compareBy({ it.first }, { it.second.line })).map { it.second }
    }
}
