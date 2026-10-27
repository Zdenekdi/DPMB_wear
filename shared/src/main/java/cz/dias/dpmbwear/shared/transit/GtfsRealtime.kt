package cz.dias.dpmbwear.shared.transit

/**
 * Čtení GTFS-Realtime feedu KORDIS JMK (gtfsReal.dat).
 *
 * Vlastní dekodér protobufu místo knihovny gtfs-realtime-bindings: potřebujeme
 * jen pár polí, odpadne závislost na protobuf-java a celé to jde otestovat na
 * čistém JVM. Čísla polí podle
 * https://gtfs.org/realtime/reference/ (gtfs-realtime.proto).
 *
 * Feed KORDISu obsahuje polohy vozidel a výluky, zpoždění NE. TripUpdate
 * přesto čteme – kdyby ho KORDIS přidal, použije se přímo.
 */
data class FeedSnapshot(
    val timestamp: Long,
    val vehicles: List<VehicleObservation>,
    val tripUpdates: List<TripUpdateObservation>,
)

data class VehicleObservation(
    val entityId: String,
    val tripId: String,
    /** YYYYMMDD nebo null. */
    val startDate: String?,
    val lat: Double,
    val lon: Double,
    /** Epoch sekundy; 0 = neuvedeno (použije se čas hlavičky). */
    val timestamp: Long,
    val stopId: String?,
    val status: Int,
    val vehicleLabel: String,
) {
    companion object {
        const val INCOMING_AT = 0
        const val STOPPED_AT = 1

        /** Výchozí hodnota podle specifikace, když pole chybí. */
        const val IN_TRANSIT_TO = 2
    }
}

data class TripUpdateObservation(
    val tripId: String,
    val startDate: String?,
    /** Sekundy; null = feed zpoždění neuvádí. */
    val delay: Int?,
    val timestamp: Long,
)

object GtfsRealtimeDecoder {

    fun decode(bytes: ByteArray): FeedSnapshot {
        var headerTs = 0L
        val vehicles = ArrayList<VehicleObservation>()
        val updates = ArrayList<TripUpdateObservation>()

        val feed = ProtoReader(bytes)
        while (feed.next()) {
            when (feed.field) {
                1 -> headerTs = header(feed.message())
                2 -> entity(feed.message(), vehicles, updates)
                else -> feed.skip()
            }
        }
        return FeedSnapshot(headerTs, vehicles, updates)
    }

    private fun header(r: ProtoReader): Long {
        var ts = 0L
        while (r.next()) if (r.field == 3) ts = r.varint() else r.skip()
        return ts
    }

    private fun entity(
        r: ProtoReader,
        vehicles: MutableList<VehicleObservation>,
        updates: MutableList<TripUpdateObservation>,
    ) {
        var id = ""
        var deleted = false
        var vehicle: ProtoReader? = null
        var update: ProtoReader? = null
        while (r.next()) {
            when (r.field) {
                1 -> id = r.string()
                2 -> deleted = r.varint() != 0L
                3 -> update = r.message()
                4 -> vehicle = r.message()
                else -> r.skip()
            }
        }
        if (deleted) return
        vehicle?.let { vehiclePosition(id, it)?.let(vehicles::add) }
        update?.let { tripUpdate(it)?.let(updates::add) }
    }

    private class Trip(var tripId: String = "", var startDate: String? = null)

    private fun trip(r: ProtoReader): Trip {
        val t = Trip()
        while (r.next()) {
            when (r.field) {
                1 -> t.tripId = r.string()
                3 -> t.startDate = r.string().ifEmpty { null }
                else -> r.skip()
            }
        }
        return t
    }

    private fun vehiclePosition(entityId: String, r: ProtoReader): VehicleObservation? {
        var trip: Trip? = null
        var lat = Double.NaN
        var lon = Double.NaN
        var status = VehicleObservation.IN_TRANSIT_TO
        var ts = 0L
        var stopId: String? = null
        var label = ""
        var vehicleId = ""
        while (r.next()) {
            when (r.field) {
                1 -> trip = trip(r.message())
                2 -> {
                    val p = r.message()
                    while (p.next()) {
                        when (p.field) {
                            1 -> lat = p.float().toDouble()
                            2 -> lon = p.float().toDouble()
                            else -> p.skip()
                        }
                    }
                }
                4 -> status = r.varint().toInt()
                5 -> ts = r.varint()
                7 -> stopId = r.string().ifEmpty { null }
                8 -> {
                    val v = r.message()
                    while (v.next()) {
                        when (v.field) {
                            1 -> vehicleId = v.string()
                            2 -> label = v.string()
                            else -> v.skip()
                        }
                    }
                }
                else -> r.skip()
            }
        }
        val t = trip ?: return null
        if (t.tripId.isEmpty() || lat.isNaN() || lon.isNaN()) return null
        return VehicleObservation(
            entityId = entityId,
            tripId = t.tripId,
            startDate = t.startDate,
            lat = lat,
            lon = lon,
            timestamp = ts,
            stopId = stopId,
            status = status,
            vehicleLabel = label.ifEmpty { vehicleId.ifEmpty { entityId } },
        )
    }

    private fun tripUpdate(r: ProtoReader): TripUpdateObservation? {
        var trip: Trip? = null
        var ts = 0L
        var tripDelay: Int? = null
        var firstStopDelay: Int? = null
        while (r.next()) {
            when (r.field) {
                1 -> trip = trip(r.message())
                2 -> if (firstStopDelay == null) firstStopDelay = stopTimeUpdateDelay(r.message()) else r.skip()
                4 -> ts = r.varint()
                5 -> tripDelay = r.varint().toInt()
                else -> r.skip()
            }
        }
        val t = trip ?: return null
        val delay = firstStopDelay ?: tripDelay ?: return null
        return TripUpdateObservation(t.tripId, t.startDate, delay, ts)
    }

    /** Zpoždění odjezdu, jinak příjezdu; null, když chybí obojí. */
    private fun stopTimeUpdateDelay(r: ProtoReader): Int? {
        var arrival: Int? = null
        var departure: Int? = null
        while (r.next()) {
            when (r.field) {
                2 -> arrival = eventDelay(r.message())
                3 -> departure = eventDelay(r.message())
                else -> r.skip()
            }
        }
        return departure ?: arrival
    }

    private fun eventDelay(r: ProtoReader): Int? {
        var delay: Int? = null
        while (r.next()) if (r.field == 1) delay = r.varint().toInt() else r.skip()
        return delay
    }
}

/** Čtení protobuf wire formátu. Jen to, co GTFS-RT potřebuje. */
class ProtoReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {

    var field: Int = 0
        private set
    private var wire: Int = 0

    /** Načte další tag. False = konec zprávy. */
    fun next(): Boolean {
        if (pos >= end) return false
        val tag = varint()
        field = (tag ushr 3).toInt()
        wire = (tag and 7).toInt()
        if (field == 0) throw IllegalArgumentException("Neplatný protobuf: pole 0")
        return true
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            if (pos >= end) throw IllegalArgumentException("Neplatný protobuf: useknutý varint")
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("Neplatný protobuf: příliš dlouhý varint")
    }

    fun float(): Float {
        check(wire == 5) { "Pole $field není fixed32" }
        return java.lang.Float.intBitsToFloat(fixed32())
    }

    private fun fixed32(): Int {
        need(4)
        val v = (buf[pos].toInt() and 0xFF) or
            ((buf[pos + 1].toInt() and 0xFF) shl 8) or
            ((buf[pos + 2].toInt() and 0xFF) shl 16) or
            ((buf[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    private fun lengthDelimited(): Pair<Int, Int> {
        check(wire == 2) { "Pole $field není length-delimited" }
        val len = varint().toInt()
        if (len < 0) throw IllegalArgumentException("Neplatný protobuf: záporná délka")
        need(len)
        val start = pos
        pos += len
        return start to len
    }

    fun string(): String {
        val (start, len) = lengthDelimited()
        return String(buf, start, len, Charsets.UTF_8)
    }

    fun message(): ProtoReader {
        val (start, len) = lengthDelimited()
        return ProtoReader(buf, start, start + len)
    }

    fun skip() {
        when (wire) {
            0 -> varint()
            1 -> { need(8); pos += 8 }
            2 -> lengthDelimited()
            5 -> { need(4); pos += 4 }
            else -> throw IllegalArgumentException("Nepodporovaný wire type $wire")
        }
    }

    private fun need(n: Int) {
        if (pos + n > end) throw IllegalArgumentException("Neplatný protobuf: useknutá data")
    }
}
