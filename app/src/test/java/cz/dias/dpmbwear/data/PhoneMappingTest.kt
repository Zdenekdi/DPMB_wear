package cz.dias.dpmbwear.data

import cz.dias.dpmbwear.shared.PayloadDeparture
import cz.dias.dpmbwear.shared.PayloadStop
import cz.dias.dpmbwear.shared.WatchPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneMappingTest {

    private val generatedAt = 1_793_170_000L
    private val stop = PayloadStop(
        "U100", "Česká", 49.196, 16.606, 45,
        listOf(
            PayloadDeparture("1", "Ečerova", generatedAt + 30, 2),        // za 0,5 min
            PayloadDeparture("6", "Královo Pole", generatedAt + 5 * 60, null),
            PayloadDeparture("4", "Obřany", generatedAt + 12 * 60 + 59, -1),
        ),
    )
    private val payload = WatchPayload("r", generatedAt, 10, null, listOf(stop))

    @Test
    fun `minutes count from phone time, not watch clock`() {
        // Hodinky jdou o 3 minuty napřed – na výsledku se to nesmí projevit.
        val watchReceivedAt = (generatedAt + 180) * 1000
        val received = ReceivedPayload(payload, watchReceivedAt)
        val deps = PhoneDeparturesSource.toDepartures(received, stop, watchReceivedAt)
        assertEquals(listOf(0, 5, 12), deps.map { it.timeMinutes })
        assertEquals(listOf(true, false, true), deps.map { it.isLive })
        assertEquals(listOf(2, null, -1), deps.map { it.delayMinutes })
    }

    @Test
    fun `cached payload ages and drops departed`() {
        val receivedAt = generatedAt * 1000
        val received = ReceivedPayload(payload, receivedAt)
        // O 6 minut později bez nové odpovědi: linka 1 i 6 odjely.
        val deps = PhoneDeparturesSource.toDepartures(received, stop, receivedAt + 6 * 60_000)
        assertEquals(listOf("4"), deps.map { it.line })
        assertEquals(6, deps.single().timeMinutes)
    }
}
