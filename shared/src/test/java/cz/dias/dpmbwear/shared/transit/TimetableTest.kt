package cz.dias.dpmbwear.shared.transit

import cz.dias.dpmbwear.shared.DeparturesRequest
import cz.dias.dpmbwear.shared.PayloadError
import cz.dias.dpmbwear.shared.transit.Fixtures.local
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableTest {

    private val tt = Fixtures.timetable

    private fun board(now: Long, stop: String = "U100", feed: FeedSnapshot? = null) =
        DepartureBoard.answer(tt, DeparturesRequest(id = "r", stopId = stop, maxStops = 1), now, feed)
            .stops.single().departures

    private fun minutes(epoch: Long, now: Long) = ((epoch - now) / 60).toInt()

    @Test
    fun platformsMergeIntoGroup() {
        val g = tt.groupIndex("U100")!!
        assertEquals("Česká", tt.groupName(g))
        assertNull("nepoužitá zastávka se nenačítá", tt.groupIndex("U999"))
        val term = tt.groupIndex("U400")!!
        assertEquals("z konečné se neodjíždí", 0, tt.depEnd(term) - tt.depStart(term))
    }

    @Test
    fun scheduledDepartures() {
        val now = local(2026, 10, 27, 8, 2)
        val deps = board(now)
        assertEquals(listOf("1" to "Konečná", "6" to "Hlavní nádraží"), deps.map { it.line to it.destination })
        assertEquals(listOf(4, 18), deps.map { minutes(it.epoch, now) })
        assertTrue(deps.all { it.delayMin == null })
    }

    @Test
    fun calendarDatesAddService() {
        val deps = board(local(2026, 10, 28, 8, 15))
        assertTrue(deps.any { it.destination == "Zrušený" })
    }

    @Test
    fun afterMidnightBelongsToPreviousDay() {
        val now = local(2026, 10, 28, 0, 5)
        val deps = board(now)
        assertEquals(listOf("N1"), deps.map { it.line })
        assertEquals(5, minutes(deps[0].epoch, now))
        assertEquals("prázdný headsign → konečná", "Hlavní nádraží", deps[0].destination)
    }

    @Test
    fun departedIsHidden() {
        assertTrue(board(local(2026, 10, 27, 8, 7)).none { it.line == "1" })
    }

    @Test
    fun dstDays() {
        for (d in listOf(LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 25))) {
            assertEquals(local(d.year, d.monthValue, d.dayOfMonth, 8, 0), ServiceTime.dayStart(d) + 8 * 3600)
        }
    }

    @Test
    fun nearbyAndRequestedStopFirst() {
        val req = DeparturesRequest(id = "x", lat = 49.1961, lon = 16.6061, stopId = "U300", maxStops = 3)
        val p = DepartureBoard.answer(tt, req, local(2026, 10, 27, 7, 55), null)
        assertEquals(listOf("U300", "U100", "U200"), p.stops.map { it.id })
        assertTrue(p.stops[1].distanceM in 0..50)
        assertEquals(-1, p.realtimeAgeS)
    }

    @Test
    fun unknownStopWithoutPosition() {
        val p = DepartureBoard.answer(tt, DeparturesRequest(id = "x", stopId = "U12345"), 0, null)
        assertEquals(PayloadError.STOP_NOT_FOUND, p.error)
    }
}
