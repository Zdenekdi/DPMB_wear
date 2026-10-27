package cz.dias.dpmbwear.shared.transit

import cz.dias.dpmbwear.shared.DeparturesRequest
import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeTest {

    private val tt = Fixtures.timetable
    private val day = LocalDate.of(2026, 10, 27)
    private val t1 get() = tt.tripIndex("T1")!!

    private fun live(feed: String, now: Long) =
        RealtimeEstimator.build(tt, Fixtures.feed(feed), now).live(t1, day)

    @Test
    fun decoderReadsReferenceFeed() {
        val f = Fixtures.feed(Fixtures.PADDED)
        assertEquals(Fixtures.PADDED_NOW, f.timestamp)
        val v = f.vehicles.single()
        assertEquals("T1", v.tripId)
        assertEquals("U0100Z01", v.stopId)
        assertEquals(VehicleObservation.IN_TRANSIT_TO, v.status)
        assertEquals(49.1935, v.lat, 1e-4)
        assertEquals("v0", v.vehicleLabel)
    }

    @Test
    fun negativeDelayInTripUpdate() {
        val f = Fixtures.feed(Fixtures.TRIP_UPDATE)
        assertEquals(listOf(240, -60), f.tripUpdates.map { it.delay })
        assertEquals("20261027", f.tripUpdates[0].startDate)
    }

    @Test
    fun delayFromPositionWithPaddedStopId() {
        // Podle řádu je v půli úseku v 08:02:30, ve skutečnosti ve 08:04:30 → 2 min.
        val l = live(Fixtures.PADDED, Fixtures.PADDED_NOW)
        assertNotNull(l)
        assertTrue("delay ${l!!.delay}", abs(l.delay - 120) <= 3)
        assertEquals(1, l.position)
        assertFalse(l.stopped)
        assertTrue(l.estimated)

        val now = Fixtures.PADDED_NOW
        val dep = DepartureBoard.answer(tt, DeparturesRequest("r", stopId = "U100", maxStops = 1), now,
            Fixtures.feed(Fixtures.PADDED)).stops.single().departures.first()
        assertEquals("1", dep.line)
        assertEquals(2, dep.delayMin)
        assertEquals(3, ((dep.epoch - now) / 60).toInt()) // 08:06 + 2 = 08:08, teď 08:04:30
    }

    @Test
    fun delayFromGeometry() {
        val l = live(Fixtures.GEOMETRY, Fixtures.GEOMETRY_NOW)!!
        assertTrue("delay ${l.delay}", abs(l.delay - 120) <= 5)
        assertEquals(1, l.position)
    }

    @Test
    fun stoppedAtStop() {
        val l = live(Fixtures.STOPPED, Fixtures.STOPPED_NOW)!!
        assertEquals(180, l.delay)
        assertTrue(l.stopped)
        assertEquals(1, l.position)
    }

    @Test
    fun earlyVehicleNotShownEarlier() {
        val now = Fixtures.EARLY_NOW
        val l = live(Fixtures.EARLY, now)!!
        assertTrue("delay ${l.delay}", abs(l.delay + 120) <= 3)
        val dep = DepartureBoard.answer(tt, DeparturesRequest("r", stopId = "U100", maxStops = 1), now,
            Fixtures.feed(Fixtures.EARLY)).stops.single().departures.first()
        assertEquals(Fixtures.local(2026, 10, 27, 8, 6), dep.epoch)
        assertEquals(-2, dep.delayMin)
    }

    @Test
    fun implausibleLeadDropped() {
        val idx = RealtimeEstimator.build(tt, Fixtures.feed(Fixtures.TOO_EARLY), Fixtures.TOO_EARLY_NOW)
        assertEquals(0, idx.size)
    }

    @Test
    fun stalePositionsDropped() {
        val idx = RealtimeEstimator.build(tt, Fixtures.feed(Fixtures.PADDED), Fixtures.PADDED_NOW + 20 * 60)
        assertEquals(0, idx.size)
    }

    @Test
    fun duplicateVehicleKeepsPlausibleTrip() {
        val idx = RealtimeEstimator.build(tt, Fixtures.feed(Fixtures.DUPLICATE), Fixtures.DUPLICATE_NOW)
        assertEquals(1, idx.size)
        assertNotNull(idx.live(t1, day))
    }

    @Test
    fun onlyTripsFilter() {
        val idx = RealtimeEstimator.build(tt, Fixtures.feed(Fixtures.PADDED), Fixtures.PADDED_NOW,
            onlyTrips = setOf(tt.tripIndex("T2")!!))
        assertEquals(0, idx.size)
    }

    @Test
    fun tripUpdateWins() {
        val idx = RealtimeEstimator.build(tt, Fixtures.feed(Fixtures.TRIP_UPDATE), Fixtures.TRIP_UPDATE_NOW)
        val l = idx.live(t1, day)!!
        assertEquals(240, l.delay)
        assertFalse(l.estimated)
    }

    @Test
    fun passedTripHidden() {
        // Vozidlo už jede k Hlavnímu nádraží (pozice 2): odjezd z České nesmí zůstat.
        val now = Fixtures.local(2026, 10, 27, 8, 10)
        val idx = RealtimeIndex(mapOf(RealtimeIndex.key(t1, day) to LiveTrip(600, 2, false, now, true)))
        val c = DepartureBoard.scheduled(tt, tt.groupIndex("U100")!!, now)
        assertTrue(DepartureBoard.departures(tt, c, now, idx).none { it.line == "1" })
    }

    @Test
    fun normalizeStopId() {
        assertEquals("U1472Z2", RealtimeEstimator.normalizeStopId("U01472Z02"))
        assertEquals("XYZ", RealtimeEstimator.normalizeStopId("XYZ"))
    }
}
