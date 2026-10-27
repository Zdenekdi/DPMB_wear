package cz.dias.dpmbwear.shared.transit

import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.Reader
import java.util.zip.ZipFile

/**
 * Převod GTFS zipu na kompaktní binární soubor, který čte [Timetable].
 *
 * Proč ne SQLite: dotaz "odjezdy ze zastávky" je jen binární vyhledání
 * v seřazeném poli. Binární soubor se na telefonu namapuje do paměti
 * (mmap), takže otevření trvá milisekundy a nic se neparsuje znovu, když
 * hodinky pošlou dotaz.
 *
 * Paměť při převodu: pro ~2,5 mil. řádků stop_times celého kraje zhruba
 * 60–80 MB. Převod proto běží ve WorkManageru, ne při dotazu z hodinek.
 */
object GtfsCompiler {

    internal const val MAGIC = "DPMBTT01"

    private val STOP_RE = Regex("^(U\\d+)Z(\\d+)$")

    /** Skupina nástupišť: U1472Z2 → U1472. Jiné tvary ID zůstávají, jak jsou. */
    fun groupKey(stopId: String): String = STOP_RE.matchEntire(stopId)?.groupValues?.get(1) ?: stopId

    fun compile(zipFile: File, out: OutputStream, version: String) {
        ZipFile(zipFile).use { zip ->
            fun table(name: String): CsvTable? = zip.getEntry(name)?.let {
                CsvTable(InputStreamReader(zip.getInputStream(it), Charsets.UTF_8))
            }
            fun required(name: String): CsvTable = table(name) ?: error("V GTFS chybí $name")

            val strings = StringPool()

            // ---------- routes ----------
            val routeIndex = HashMap<String, Int>()
            val routeShort = IntList()
            required("routes.txt").use { t ->
                val id = t.col("route_id")
                val short = t.colOrNull("route_short_name")
                val long = t.colOrNull("route_long_name")
                t.forEach { r ->
                    routeIndex[r[id]] = routeShort.size
                    val name = short?.let { r.getOrNull(it) }?.takeIf { it.isNotEmpty() }
                        ?: long?.let { r.getOrNull(it) }.orEmpty()
                    routeShort.add(strings.id(name))
                }
            }

            // ---------- services ----------
            val services = ArrayList<ServiceBuilder>()
            val serviceIndex = HashMap<String, Int>()
            fun service(id: String): Int = serviceIndex.getOrPut(id) {
                services.add(ServiceBuilder()); services.size - 1
            }
            table("calendar.txt")?.use { t ->
                val id = t.col("service_id")
                val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
                    .map { t.col(it) }
                val start = t.col("start_date")
                val end = t.col("end_date")
                t.forEach { r ->
                    val s = services[service(r[id])]
                    var mask = 0
                    days.forEachIndexed { bit, c -> if (r.getOrNull(c) == "1") mask = mask or (1 shl bit) }
                    s.weekdays = mask
                    s.start = r[start].toInt()
                    s.end = r[end].toInt()
                }
            }
            table("calendar_dates.txt")?.use { t ->
                val id = t.col("service_id")
                val date = t.col("date")
                val type = t.col("exception_type")
                t.forEach { r ->
                    val s = services[service(r[id])]
                    if (r[type] == "1") s.added.add(r[date].toInt()) else s.removed.add(r[date].toInt())
                }
            }

            // ---------- trips ----------
            val tripIndex = HashMap<String, Int>()
            val tripIdStr = IntList()
            val tripRoute = IntList()
            val tripHeadsign = IntList()
            val tripService = IntList()
            required("trips.txt").use { t ->
                val id = t.col("trip_id")
                val route = t.col("route_id")
                val svc = t.col("service_id")
                val head = t.colOrNull("trip_headsign")
                t.forEach { r ->
                    val ri = routeIndex[r[route]] ?: return@forEach
                    tripIndex[r[id]] = tripIdStr.size
                    tripIdStr.add(strings.id(r[id]))
                    tripRoute.add(ri)
                    tripHeadsign.add(strings.id(head?.let { r.getOrNull(it) }.orEmpty()))
                    tripService.add(service(r[svc]))
                }
            }

            // ---------- stop_times ----------
            val stTrip = IntList(1 shl 20)
            val stSeq = IntList(1 shl 20)
            val stStop = IntList(1 shl 20)
            val stArr = IntList(1 shl 20)
            val stDep = IntList(1 shl 20)
            val stNoPickup = BooleanList(1 shl 20)
            val tmpStopIndex = HashMap<String, Int>()
            val tmpStopIds = ArrayList<String>()
            required("stop_times.txt").use { t ->
                val trip = t.col("trip_id")
                val arr = t.colOrNull("arrival_time")
                val dep = t.colOrNull("departure_time")
                val stop = t.col("stop_id")
                val seq = t.col("stop_sequence")
                val pickup = t.colOrNull("pickup_type")
                t.forEach { r ->
                    val ti = tripIndex[r[trip]] ?: return@forEach
                    val a = arr?.let { r.getOrNull(it) }.orEmpty().ifEmpty { dep?.let { r.getOrNull(it) }.orEmpty() }
                    val d = dep?.let { r.getOrNull(it) }.orEmpty().ifEmpty { a }
                    if (a.isEmpty()) return@forEach
                    val sid = r[stop]
                    val si = tmpStopIndex.getOrPut(sid) { tmpStopIds.add(sid); tmpStopIds.size - 1 }
                    val at = parseTime(a)
                    stTrip.add(ti)
                    stSeq.add(r[seq].toInt())
                    stStop.add(si)
                    stArr.add(at)
                    stDep.add(maxOf(at, parseTime(d)))
                    stNoPickup.add(pickup?.let { r.getOrNull(it) } == "1")
                }
            }

            // ---------- stops (jen obsluhovaná nástupiště) ----------
            val groupIndex = HashMap<String, Int>()
            val groupKeys = ArrayList<String>()
            val groupVotes = ArrayList<HashMap<String, Int>>()
            val groupSum = ArrayList<DoubleArray>()
            val stopIndex = HashMap<String, Int>()
            val stopIdStr = IntList()
            val stopGroup = IntList()
            val stopLat = ArrayList<Float>()
            val stopLon = ArrayList<Float>()
            val stopPlatform = IntList()
            required("stops.txt").use { t ->
                val id = t.col("stop_id")
                val name = t.col("stop_name")
                val lat = t.col("stop_lat")
                val lon = t.col("stop_lon")
                val type = t.colOrNull("location_type")
                val platform = t.colOrNull("platform_code")
                t.forEach { r ->
                    val sid = r[id]
                    if (sid !in tmpStopIndex) return@forEach
                    val lt = type?.let { r.getOrNull(it) }.orEmpty()
                    if (lt.isNotEmpty() && lt != "0") return@forEach
                    val key = groupKey(sid)
                    val g = groupIndex.getOrPut(key) {
                        groupKeys.add(key); groupVotes.add(HashMap()); groupSum.add(DoubleArray(3)); groupKeys.size - 1
                    }
                    val y = r[lat].toDouble()
                    val x = r[lon].toDouble()
                    val n = r[name]
                    groupVotes[g][n] = (groupVotes[g][n] ?: 0) + 1
                    groupSum[g][0] += y; groupSum[g][1] += x; groupSum[g][2] += 1.0

                    stopIndex[sid] = stopIdStr.size
                    stopIdStr.add(strings.id(sid))
                    stopGroup.add(g)
                    stopLat.add(y.toFloat())
                    stopLon.add(x.toFloat())
                    val plat = platform?.let { r.getOrNull(it) }?.takeIf { it.isNotEmpty() }
                        ?: STOP_RE.matchEntire(sid)?.groupValues?.get(2).orEmpty()
                    stopPlatform.add(strings.id(plat))
                }
            }
            val remap = IntArray(tmpStopIds.size) { stopIndex[tmpStopIds[it]] ?: -1 }

            // ---------- seřazení řádků podle spoje a pořadí ----------
            val rows = stTrip.size
            require(rows < (1 shl 24)) { "Příliš mnoho řádků stop_times: $rows" }
            require(tripIdStr.size < (1 shl 23)) { "Příliš mnoho spojů" }
            val order = LongArray(rows) { i ->
                (stTrip[i].toLong() shl 40) or ((stSeq[i].toLong() and 0xFFFF) shl 24) or i.toLong()
            }
            order.sort()

            val nTrips = tripIdStr.size
            val tripOffset = IntArray(nTrips + 1)
            for (k in 0 until rows) tripOffset[stTrip[(order[k] and 0xFFFFFF).toInt()] + 1]++
            for (t in 0 until nTrips) tripOffset[t + 1] += tripOffset[t]

            // ---------- index odjezdů podle skupiny a času ----------
            // Bez poslední zastávky spoje (tam se jen vystupuje) a bez nástupišť,
            // kde není dovolen nástup.
            val depKeys = LongArray(rows)
            var nDep = 0
            for (k in 0 until rows) {
                val i = (order[k] and 0xFFFFFF).toInt()
                val trip = stTrip[i]
                val isLast = k == tripOffset[trip + 1] - 1
                val stop = remap[stStop[i]]
                if (isLast || stNoPickup[i] || stop < 0) continue
                val dep = stDep[i]
                require(dep < (1 shl 18)) { "Čas odjezdu mimo rozsah: $dep" }
                depKeys[nDep++] = (stopGroup[stop].toLong() shl 42) or (dep.toLong() shl 24) or k.toLong()
            }
            val dep = depKeys.copyOf(nDep)
            dep.sort()
            val nGroups = groupKeys.size
            val groupOffset = IntArray(nGroups + 1)
            for (key in dep) groupOffset[(key ushr 42).toInt() + 1]++
            for (g in 0 until nGroups) groupOffset[g + 1] += groupOffset[g]

            // Všechny řetězce musí být v poolu PŘED zápisem jeho tabulky.
            val groupNameIds = IntArray(nGroups) { g ->
                strings.id(groupVotes[g].maxByOrNull { it.value }!!.key)
            }
            val groupKeyIds = IntArray(nGroups) { g -> strings.id(groupKeys[g]) }

            // ---------- zápis ----------
            DataOutputStream(BufferedOutputStream(out, 1 shl 16)).run {
                write(MAGIC.toByteArray(Charsets.US_ASCII))
                writeStr(version)

                writeInt(strings.size)
                strings.values.forEach { writeStr(it) }

                writeInt(routeShort.size)
                for (i in 0 until routeShort.size) writeInt(routeShort[i])

                writeInt(nGroups)
                for (g in 0 until nGroups) {
                    writeInt(groupKeyIds[g])
                    writeInt(groupNameIds[g])
                    writeFloat((groupSum[g][0] / groupSum[g][2]).toFloat())
                    writeFloat((groupSum[g][1] / groupSum[g][2]).toFloat())
                }

                writeInt(stopIdStr.size)
                for (s in 0 until stopIdStr.size) {
                    writeInt(stopIdStr[s]); writeInt(stopGroup[s])
                    writeFloat(stopLat[s]); writeFloat(stopLon[s]); writeInt(stopPlatform[s])
                }

                writeInt(services.size)
                services.forEach { s ->
                    writeByte(s.weekdays); writeInt(s.start); writeInt(s.end)
                    writeInt(s.added.size); for (i in 0 until s.added.size) writeInt(s.added[i])
                    writeInt(s.removed.size); for (i in 0 until s.removed.size) writeInt(s.removed[i])
                }

                writeInt(nTrips)
                for (t in 0 until nTrips) {
                    writeInt(tripIdStr[t]); writeInt(tripRoute[t]); writeInt(tripHeadsign[t]); writeInt(tripService[t])
                }
                for (t in 0..nTrips) writeInt(tripOffset[t])

                writeInt(rows)
                for (k in 0 until rows) {
                    val i = (order[k] and 0xFFFFFF).toInt()
                    writeInt(remap[stStop[i]]); writeInt(stArr[i]); writeInt(stDep[i])
                }

                for (g in 0..nGroups) writeInt(groupOffset[g])
                writeInt(nDep)
                for (key in dep) {
                    val k = (key and 0xFFFFFF).toInt()
                    val trip = stTrip[(order[k] and 0xFFFFFF).toInt()]
                    writeInt(trip)
                    writeInt(k - tripOffset[trip]) // pozice zastávky ve spoji
                    writeInt(((key ushr 24) and 0x3FFFF).toInt())
                }
                flush()
            }
        }
    }

    private fun DataOutputStream.writeStr(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size)
        write(b)
    }

    internal fun parseTime(t: String): Int {
        val p = t.trim().split(':')
        require(p.size >= 2) { "Neplatný čas '$t'" }
        return p[0].toInt() * 3600 + p[1].toInt() * 60 + (p.getOrNull(2)?.toInt() ?: 0)
    }

    private class ServiceBuilder {
        var weekdays = 0
        var start = 0
        var end = 0
        val added = IntList(4)
        val removed = IntList(4)
    }
}

internal class StringPool {
    private val index = HashMap<String, Int>()
    val values = ArrayList<String>()
    val size get() = values.size
    fun id(s: String): Int = index.getOrPut(s) { values.add(s); values.size - 1 }
}

internal class IntList(capacity: Int = 16) {
    private var a = IntArray(capacity)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(maxOf(16, a.size * 2))
        a[size++] = v
    }

    operator fun get(i: Int): Int = a[i]
}

internal class BooleanList(capacity: Int = 16) {
    private var a = BooleanArray(capacity)
    var size = 0
        private set

    fun add(v: Boolean) {
        if (size == a.size) a = a.copyOf(maxOf(16, a.size * 2))
        a[size++] = v
    }

    operator fun get(i: Int): Boolean = a[i]
}

/** CSV podle RFC 4180 (uvozovky, zdvojené uvozovky, CRLF), čtené proudově. */
internal class CsvTable(reader: Reader) : AutoCloseable {
    private val r = reader.buffered(1 shl 16)
    private val field = StringBuilder()
    private val header: Map<String, Int>

    init {
        val h = readRow() ?: emptyList()
        header = h.mapIndexed { i, name -> name.trim().removePrefix("﻿") to i }.toMap()
    }

    fun col(name: String): Int = header[name] ?: error("Chybí sloupec $name")
    fun colOrNull(name: String): Int? = header[name]

    inline fun forEach(action: (List<String>) -> Unit) {
        while (true) {
            val row = readRow() ?: return
            if (row.size == 1 && row[0].isEmpty()) continue
            action(row)
        }
    }

    fun readRow(): List<String>? {
        var c = r.read()
        if (c == -1) return null
        val row = ArrayList<String>(12)
        field.setLength(0)
        var quoted = false
        while (true) {
            if (quoted) {
                when (c) {
                    -1 -> { row.add(field.toString()); return row }
                    '"'.code -> {
                        val n = r.read()
                        if (n == '"'.code) field.append('"') else { quoted = false; c = n; continue }
                    }
                    else -> field.append(c.toChar())
                }
            } else {
                when (c) {
                    -1 -> { row.add(field.toString()); return row }
                    '"'.code -> if (field.isEmpty()) quoted = true else field.append('"')
                    ','.code -> { row.add(field.toString()); field.setLength(0) }
                    '\r'.code -> {}
                    '\n'.code -> { row.add(field.toString()); return row }
                    else -> field.append(c.toChar())
                }
            }
            c = r.read()
        }
    }

    override fun close() = r.close()
}
