package cz.dias.dpmbwear.shared.transit

import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.time.LocalDate
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Jízdní řád načtený ze souboru, který vyrobil [GtfsCompiler].
 *
 * Malé tabulky (zastávky, spoje, kalendář) jdou do paměti. Velké tabulky
 * (řádky spojů a index odjezdů) zůstávají v [ByteBuffer] – na telefonu je to
 * MappedByteBuffer, takže se do RAM načte jen to, na co se sáhne.
 */
class Timetable private constructor(
    val version: String,
    private val strings: Array<String>,
    private val routeShort: IntArray,
    private val groupIdStr: IntArray,
    private val groupNameStr: IntArray,
    private val groupLat: FloatArray,
    private val groupLon: FloatArray,
    private val stopIdStr: IntArray,
    private val stopGroup: IntArray,
    private val stopLat: FloatArray,
    private val stopLon: FloatArray,
    private val stopPlatformStr: IntArray,
    private val services: Array<Service>,
    private val tripIdStr: IntArray,
    private val tripRoute: IntArray,
    private val tripHeadsignStr: IntArray,
    private val tripService: IntArray,
    private val tripOffset: IntArray,
    /** stop, arr, dep – tři inty na řádek, seřazeno podle spoje a pořadí. */
    private val rowsBuf: IntBuffer,
    private val groupOffset: IntArray,
    /** trip, pozice ve spoji, dep – tři inty na odjezd, seřazeno podle skupiny a času. */
    private val depBuf: IntBuffer,
) {
    private val groupIndex: Map<String, Int> = HashMap<String, Int>(groupIdStr.size * 2).apply {
        groupIdStr.forEachIndexed { g, s -> put(strings[s], g) }
    }
    private val stopIndex: Map<String, Int> = HashMap<String, Int>(stopIdStr.size * 2).apply {
        stopIdStr.forEachIndexed { i, s -> put(strings[s], i) }
    }
    private val tripIndex: Map<String, Int> = HashMap<String, Int>(tripIdStr.size * 2).apply {
        tripIdStr.forEachIndexed { t, s -> put(strings[s], t) }
    }

    val groupCount: Int get() = groupIdStr.size
    val tripCount: Int get() = tripIdStr.size

    fun groupIndex(id: String): Int? = groupIndex[id]
    fun groupId(g: Int): String = strings[groupIdStr[g]]
    fun groupName(g: Int): String = strings[groupNameStr[g]]
    fun groupLat(g: Int): Double = groupLat[g].toDouble()
    fun groupLon(g: Int): Double = groupLon[g].toDouble()

    fun stopIndex(platformId: String): Int? = stopIndex[platformId]
    fun stopGroup(s: Int): Int = stopGroup[s]
    fun stopLat(s: Int): Double = stopLat[s].toDouble()
    fun stopLon(s: Int): Double = stopLon[s].toDouble()
    fun stopPlatform(s: Int): String = strings[stopPlatformStr[s]]

    fun tripIndex(tripId: String): Int? = tripIndex[tripId]
    fun tripLine(t: Int): String = strings[routeShort[tripRoute[t]]]
    fun tripHeadsign(t: Int): String = strings[tripHeadsignStr[t]]
    fun tripService(t: Int): Int = tripService[t]
    fun tripRowStart(t: Int): Int = tripOffset[t]
    fun tripRowEnd(t: Int): Int = tripOffset[t + 1]

    fun rowStop(r: Int): Int = rowsBuf.get(r * 3)
    fun rowArr(r: Int): Int = rowsBuf.get(r * 3 + 1)
    fun rowDep(r: Int): Int = rowsBuf.get(r * 3 + 2)

    fun depStart(g: Int): Int = groupOffset[g]
    fun depEnd(g: Int): Int = groupOffset[g + 1]
    fun depTrip(i: Int): Int = depBuf.get(i * 3)
    fun depPosition(i: Int): Int = depBuf.get(i * 3 + 1)
    fun depTime(i: Int): Int = depBuf.get(i * 3 + 2)

    /** Cíl spoje: headsign, jinak název poslední zastávky. */
    fun destination(t: Int): String =
        tripHeadsign(t).ifEmpty { groupName(stopGroup(rowStop(tripRowEnd(t) - 1))) }

    private val activeCache = HashMap<LocalDate, BooleanArray>()

    fun activeServices(day: LocalDate): BooleanArray = synchronized(activeCache) {
        activeCache.getOrPut(day) {
            if (activeCache.size > 8) activeCache.clear()
            val d = ServiceTime.gtfsDate(day)
            val bit = 1 shl (day.dayOfWeek.value - 1) // pondělí = bit 0
            BooleanArray(services.size) { services[it].isActive(d, bit) }
        }
    }

    /** Skupiny zastávek seřazené podle vzdálenosti. Lineární průchod; skupin jsou tisíce. */
    fun nearbyGroups(lat: Double, lon: Double, limit: Int): List<Pair<Int, Double>> {
        val d = DoubleArray(groupCount) { distanceM(lat, lon, groupLat(it), groupLon(it)) }
        return (0 until groupCount).sortedBy { d[it] }.take(limit).map { it to d[it] }
    }

    internal class Service(
        private val weekdays: Int,
        private val start: Int,
        private val end: Int,
        private val added: IntArray,
        private val removed: IntArray,
    ) {
        fun isActive(date: Int, weekdayBit: Int): Boolean = when {
            removed.contains(date) -> false
            added.contains(date) -> true
            else -> date in start..end && weekdays and weekdayBit != 0
        }
    }

    companion object {

        fun read(buffer: ByteBuffer): Timetable {
            val b = buffer.duplicate().order(java.nio.ByteOrder.BIG_ENDIAN)
            val magic = ByteArray(GtfsCompiler.MAGIC.length).also { b.get(it) }
            require(String(magic, Charsets.US_ASCII) == GtfsCompiler.MAGIC) { "Neznámý formát jízdního řádu" }
            val version = b.str()

            val strings = Array(b.int) { b.str() }
            val routeShort = IntArray(b.int) { b.int }

            val nGroups = b.int
            val gId = IntArray(nGroups)
            val gName = IntArray(nGroups)
            val gLat = FloatArray(nGroups)
            val gLon = FloatArray(nGroups)
            for (g in 0 until nGroups) {
                gId[g] = b.int; gName[g] = b.int; gLat[g] = b.float; gLon[g] = b.float
            }

            val nStops = b.int
            val sId = IntArray(nStops)
            val sGroup = IntArray(nStops)
            val sLat = FloatArray(nStops)
            val sLon = FloatArray(nStops)
            val sPlat = IntArray(nStops)
            for (s in 0 until nStops) {
                sId[s] = b.int; sGroup[s] = b.int; sLat[s] = b.float; sLon[s] = b.float; sPlat[s] = b.int
            }

            val services = Array(b.int) {
                val weekdays = b.get().toInt() and 0xFF
                val start = b.int
                val end = b.int
                val added = IntArray(b.int) { b.int }
                val removed = IntArray(b.int) { b.int }
                Service(weekdays, start, end, added, removed)
            }

            val nTrips = b.int
            val tId = IntArray(nTrips)
            val tRoute = IntArray(nTrips)
            val tHead = IntArray(nTrips)
            val tSvc = IntArray(nTrips)
            for (t in 0 until nTrips) {
                tId[t] = b.int; tRoute[t] = b.int; tHead[t] = b.int; tSvc[t] = b.int
            }
            val tOffset = IntArray(nTrips + 1) { b.int }

            val rows = b.int
            val rowsBuf = b.sliceInts(rows * 3)

            val groupOffset = IntArray(nGroups + 1) { b.int }
            val nDep = b.int
            val depBuf = b.sliceInts(nDep * 3)

            return Timetable(
                version, strings, routeShort, gId, gName, gLat, gLon,
                sId, sGroup, sLat, sLon, sPlat, services,
                tId, tRoute, tHead, tSvc, tOffset, rowsBuf, groupOffset, depBuf,
            )
        }

        private fun ByteBuffer.str(): String {
            val len = int
            val bytes = ByteArray(len)
            get(bytes)
            return String(bytes, Charsets.UTF_8)
        }

        /** IntBuffer nad dalšími [count] inty bez kopírování; posune pozici za ně. */
        private fun ByteBuffer.sliceInts(count: Int): IntBuffer {
            val view = duplicate()
            view.limit(position() + count * 4)
            val ints = view.slice().order(order()).asIntBuffer()
            position(position() + count * 4)
            return ints
        }

        /** Haversine v metrech. */
        fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * 6_371_000.0 * asin(sqrt(a))
        }
    }
}
