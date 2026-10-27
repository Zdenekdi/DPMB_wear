package cz.dias.dpmbwear.data

import cz.dias.dpmbwear.data.ServerDeparturesSource.Companion.toDeparture
import cz.dias.dpmbwear.data.StopsRepository.Companion.toStop
import cz.dias.dpmbwear.data.model.Stop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerMappingTest {

    private fun dep(
        line: String? = "1",
        minutes: Int? = 3,
        delay: Int? = 2,
        live: Boolean? = true,
    ) = ServerDeparture(
        line = line,
        destination = " Ečerova ",
        platform = "1",
        scheduledTime = "08:06",
        minutes = minutes,
        delayMinutes = delay,
        isLive = live,
        isEstimate = true,
    )

    @Test
    fun `live departure keeps delay`() {
        val d = dep().toDeparture()!!
        assertEquals("1", d.line)
        assertEquals("Ečerova", d.destination)
        assertEquals(3, d.timeMinutes)
        assertEquals(true, d.isLive)
        assertEquals(2, d.delayMinutes)
    }

    @Test
    fun `delay without live data is dropped`() {
        assertNull(dep(live = false, delay = 5).toDeparture()!!.delayMinutes)
        assertNull(dep(live = null, delay = 5).toDeparture()!!.delayMinutes)
    }

    @Test
    fun `row without line or time is skipped`() {
        // Odjezd bez času by se zobrazil jako "0 min" a člověk by běžel na zastávku.
        assertNull(dep(minutes = null).toDeparture())
        assertNull(dep(line = " ").toDeparture())
    }

    @Test
    fun `negative minutes clamp to zero`() {
        assertEquals(0, dep(minutes = -1).toDeparture()!!.timeMinutes)
    }

    @Test
    fun `server stop needs id and coordinates`() {
        assertEquals(
            Stop("U100", "Česká", 49.196, 16.606),
            ServerStop("U100", "Česká", 49.196, 16.606, 12).toStop(),
        )
        assertNull(ServerStop("", "Česká", 49.196, 16.606, 12).toStop())
        assertNull(ServerStop("U100", "Česká", null, 16.606, 12).toStop())
    }

    @Test
    fun `remembered stop round trips`() {
        val stop = Stop("U1472", "Královo Pole, nádraží", 49.231, 16.595)
        assertEquals(stop, StopsRepository.decode(stop.id, StopsRepository.encode(stop)))
        assertNull(StopsRepository.decode("X", "rozbité"))
    }
}
