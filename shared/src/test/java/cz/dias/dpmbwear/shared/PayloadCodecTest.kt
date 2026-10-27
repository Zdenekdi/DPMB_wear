package cz.dias.dpmbwear.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayloadCodecTest {

    private val payload = WatchPayload(
        requestId = "a1b2",
        generatedAt = 1793170000,
        realtimeAgeS = 12,
        stops = listOf(
            PayloadStop("U100", "Česká", 49.196123, 16.606210, 45, listOf(
                PayloadDeparture("1", "Ečerova", 1793170120, 2),
                PayloadDeparture("6", "Královo Pole, \"nádraží\"", 1793170300, null),
            )),
            PayloadStop("U200", "Hlavní nádraží", 49.19, 16.611, -1, emptyList()),
        ),
    )

    @Test
    fun roundTrip() {
        val decoded = PayloadCodec.decode(PayloadCodec.encode(payload))
        assertEquals(payload.copy(stops = payload.stops.map { it.copy(lat = 49.19612, lon = 16.60621) }.let {
            listOf(it[0], payload.stops[1])
        }), decoded)
    }

    @Test
    fun compactFormat() {
        val json = PayloadCodec.encode(payload).decodeToString()
        assertTrue(json, json.startsWith("""{"v":1,"id":"a1b2","t":1793170000,"rt":12,"s":[["U100","Česká",49.19612,16.60621,45,[["1","Ečerova",1793170120,2],"""))
        assertTrue(json, json.contains("""1793170300,null]"""))
    }

    @Test
    fun typicalPayloadIsSmall() {
        val stop = PayloadStop("U1472", "Královo Pole, nádraží", 49.23123, 16.59512, 120,
            List(8) { PayloadDeparture("12", "Technologický park", 1793170000L + it * 300, if (it % 2 == 0) 3 else null) })
        val bytes = PayloadCodec.encode(WatchPayload("abcd1234", 1793170000, 12, null, List(3) { stop })).size
        assertTrue("payload má $bytes B", bytes < 1500)
    }

    @Test
    fun errorPayload() {
        val p = WatchPayload("x", 1, -1, PayloadError.NO_TIMETABLE)
        assertEquals(p, PayloadCodec.decode(PayloadCodec.encode(p)))
    }

    @Test
    fun requestRoundTrip() {
        val r = DeparturesRequest("id1", 49.19612, 16.60621, "U100", 3, 8)
        assertEquals(r, PayloadCodec.decodeRequest(PayloadCodec.encodeRequest(r)))
        val minimal = DeparturesRequest("id2")
        assertEquals(minimal, PayloadCodec.decodeRequest(PayloadCodec.encodeRequest(minimal)))
    }

    @Test
    fun jsonParserHandlesEscapesAndWhitespace() {
        val v = Json.parse(""" { "a" : [1, -2.5e1, "x\"é\n", true, null] } """) as Map<*, *>
        assertEquals(listOf(1L, -25.0, "x\"é\n", true, null), v["a"])
    }
}
