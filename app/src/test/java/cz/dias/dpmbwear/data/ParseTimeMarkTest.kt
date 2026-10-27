package cz.dias.dpmbwear.data

import cz.dias.dpmbwear.data.IdsJmkDeparturesSource.Companion.parseTimeMark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Testy převodu TimeMark z API na počet minut.
 *
 * Tahle funkce selhává tiše: špatný výsledek se zobrazí jako důvěryhodné číslo
 * a nikdo si toho nevšimne, dokud si někdo v noci nepočká na tramvaj.
 */
class ParseTimeMarkTest {

    private fun minutes(hour: Int, minute: Int) = hour * 60 + minute

    // --- tvar "počet minut" ------------------------------------------------

    @Test
    fun `relativni cas se vrati beze zmeny`() {
        assertEquals(2, parseTimeMark("2", nowMinutesOfDay = minutes(10, 0)))
        assertEquals(0, parseTimeMark("0", nowMinutesOfDay = minutes(10, 0)))
        assertEquals(45, parseTimeMark("45", nowMinutesOfDay = minutes(10, 0)))
    }

    @Test
    fun `zaporny relativni cas se orizne na nulu`() {
        assertEquals(0, parseTimeMark("-3", nowMinutesOfDay = minutes(10, 0)))
    }

    // --- tvar "HH:mm" ------------------------------------------------------

    @Test
    fun `absolutni cas se prevede na minuty od ted`() {
        assertEquals(45, parseTimeMark("15:45", nowMinutesOfDay = minutes(15, 0)))
        assertEquals(1, parseTimeMark("15:01", nowMinutesOfDay = minutes(15, 0)))
    }

    @Test
    fun `spoj tesne po pulnoci se nepocita jako minulost`() {
        // 23:50 -> 00:10 je 20 minut dopředu, ne -1420.
        assertEquals(20, parseTimeMark("00:10", nowMinutesOfDay = minutes(23, 50)))
    }

    @Test
    fun `spoj pred pulnoci videny po pulnoci`() {
        // 00:05, spoj 23:58 odjel před 7 minutami – spadá do tolerance,
        // takže se ořízne na 0, ne aby se posunul o celý den dopředu.
        assertEquals(0, parseTimeMark("23:58", nowMinutesOfDay = minutes(0, 5)))
    }

    @Test
    fun `spoj vice nez tri hodiny v minulosti patri az zitra`() {
        // 20:00, mark 15:00 = -300 min. Za hranicí tolerance,
        // takže jde o zítřejší spoj: +1440 -> 1140.
        assertEquals(1140, parseTimeMark("15:00", nowMinutesOfDay = minutes(20, 0)))
    }

    @Test
    fun `spoj v ramci tolerance se orizne na nulu`() {
        // -60 min je uvnitř tolerance 180 min, takže "právě odjel".
        assertEquals(0, parseTimeMark("14:00", nowMinutesOfDay = minutes(15, 0)))
    }

    @Test
    fun `pulnoc jako cilovy cas`() {
        assertEquals(10, parseTimeMark("00:00", nowMinutesOfDay = minutes(23, 50)))
    }

    // --- neplatné vstupy ---------------------------------------------------

    @Test
    fun `prazdny a null vstup vraci null`() {
        assertNull(parseTimeMark(null, nowMinutesOfDay = minutes(10, 0)))
        assertNull(parseTimeMark("", nowMinutesOfDay = minutes(10, 0)))
        assertNull(parseTimeMark("   ", nowMinutesOfDay = minutes(10, 0)))
    }

    @Test
    fun `nesmyslny tvar vraci null`() {
        assertNull(parseTimeMark("abc", nowMinutesOfDay = minutes(10, 0)))
        assertNull(parseTimeMark("15:45:30", nowMinutesOfDay = minutes(10, 0)))
        assertNull(parseTimeMark("15:xx", nowMinutesOfDay = minutes(10, 0)))
    }

    @Test
    fun `hodina nebo minuta mimo rozsah vraci null`() {
        assertNull(parseTimeMark("25:00", nowMinutesOfDay = minutes(10, 0)))
        assertNull(parseTimeMark("15:70", nowMinutesOfDay = minutes(10, 0)))
    }

    @Test
    fun `okoli mezer se ignoruje`() {
        assertEquals(5, parseTimeMark("  5  ", nowMinutesOfDay = minutes(10, 0)))
        assertEquals(45, parseTimeMark(" 15:45 ", nowMinutesOfDay = minutes(15, 0)))
    }
}
