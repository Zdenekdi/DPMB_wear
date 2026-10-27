package cz.dias.dpmbwear.data

import cz.dias.dpmbwear.data.DeparturesRepository.Companion.adjustForAge
import cz.dias.dpmbwear.data.model.Departure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testy přepočtu odjezdů z cache podle jejího stáří.
 *
 * Když se tohle rozbije, aplikace ukáže odjezd, který už neexistuje – a bude
 * vypadat úplně věrohodně. Přesně ten druh chyby, který uživatele nechá stát
 * na zastávce.
 */
class CacheAdjustmentTest {

    private fun departure(line: String, minutes: Int) =
        Departure(line = line, destination = "Cíl", timeMinutes = minutes, isLive = false)

    @Test
    fun `cerstva cache nic nemeni`() {
        val stored = listOf(departure("1", 3), departure("6", 8))
        assertEquals(stored, adjustForAge(stored, ageMinutes = 0))
    }

    @Test
    fun `minuty se posunou o stari cache`() {
        val stored = listOf(departure("1", 10), departure("6", 15))
        val result = adjustForAge(stored, ageMinutes = 4)

        assertEquals(listOf(6, 11), result.map { it.timeMinutes })
    }

    @Test
    fun `spoje ktere uz odjely se zahodi`() {
        val stored = listOf(departure("1", 2), departure("6", 5), departure("9", 12))
        val result = adjustForAge(stored, ageMinutes = 6)

        // "1" (2-6=-4) a "6" (5-6=-1) odjely, zbývá jen "9".
        assertEquals(1, result.size)
        assertEquals("9", result[0].line)
        assertEquals(6, result[0].timeMinutes)
    }

    @Test
    fun `spoj presne v nule zustava`() {
        // 0 = "odjíždí právě teď", to je pořád užitečná informace.
        val result = adjustForAge(listOf(departure("1", 5)), ageMinutes = 5)

        assertEquals(1, result.size)
        assertEquals(0, result[0].timeMinutes)
    }

    @Test
    fun `stara cache vrati prazdny seznam`() {
        val stored = listOf(departure("1", 3), departure("6", 8))
        assertTrue(adjustForAge(stored, ageMinutes = 30).isEmpty())
    }

    @Test
    fun `prazdny vstup vrati prazdny vystup`() {
        assertTrue(adjustForAge(emptyList(), ageMinutes = 5).isEmpty())
    }

    @Test
    fun `dvojity prepocet posune casy dvakrat`() {
        // Regrese: obrazovka v ambientu si minuty dopočítává znovu. Kdyby
        // počítala od fetchedAt místo od adjustedAt, stáří cache by se odečetlo
        // podruhé a odjezdy by vyšly výrazně bližší, než ve skutečnosti jsou.
        // Proto DeparturesResult nese obě značky času zvlášť.
        val stored = listOf(departure("1", 20))

        val jednou = adjustForAge(stored, ageMinutes = 5)
        val dvakrat = adjustForAge(jednou, ageMinutes = 5)

        assertEquals(15, jednou[0].timeMinutes)
        assertEquals(10, dvakrat[0].timeMinutes)
    }

    @Test
    fun `ostatni vlastnosti odjezdu zustavaji`() {
        val stored = listOf(
            Departure(line = "X60", destination = "Univerzitní kampus", timeMinutes = 9, isLive = true)
        )
        val result = adjustForAge(stored, ageMinutes = 3)

        assertEquals("X60", result[0].line)
        assertEquals("Univerzitní kampus", result[0].destination)
        assertTrue(result[0].isLive)
        assertEquals(6, result[0].timeMinutes)
    }
}
