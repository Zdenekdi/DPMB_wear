package cz.dias.dpmbwear.presentation.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Testy přiřazení barvy k označení linky.
 *
 * Pořadí podmínek uvnitř [LineColors.forLine] je křehké – dřív tu byla mrtvá
 * větev, kdy "S8" spolklo obecné pravidlo pro "S*" a lodní linka se kreslila
 * jako vlak. Tyhle testy to pořadí drží.
 */
class LineColorsTest {

    private fun assertColor(expected: Long, line: String) =
        assertEquals("linka \"$line\"", expected, LineColors.forLine(line))

    @Test
    fun `tramvaje jsou linky 1 az 12`() {
        assertColor(LineColors.Tram, "1")
        assertColor(LineColors.Tram, "12")
    }

    @Test
    fun `trolejbusy jsou linky 20 az 39`() {
        assertColor(LineColors.Trolleybus, "25")
        assertColor(LineColors.Trolleybus, "39")
    }

    @Test
    fun `autobusy jsou linky 40 az 99`() {
        assertColor(LineColors.Bus, "44")
        assertColor(LineColors.Bus, "99")
    }

    @Test
    fun `expresni linka s pismenem se ridi cislem`() {
        assertColor(LineColors.Bus, "X60")
    }

    @Test
    fun `nocni linky podle prefixu N`() {
        assertColor(LineColors.Night, "N89")
        assertColor(LineColors.Night, "n95")
    }

    @Test
    fun `lodni linka ma prednost pred vlakem`() {
        // Regrese: dokud bylo pravidlo pro "S*" první, "S8" se vykreslilo
        // jako vlak a lodní barva byla nedosažitelná.
        assertColor(LineColors.Boat, "S8")
        assertColor(LineColors.Boat, "Š1")
    }

    @Test
    fun `vlaky podle prefixu S a R`() {
        assertColor(LineColors.Train, "S2")
        assertColor(LineColors.Train, "R8")
    }

    @Test
    fun `regionalni linky`() {
        assertColor(LineColors.Regional, "E75")
        assertColor(LineColors.Regional, "101")
    }

    @Test
    fun `neznama cisla spadnou do ostatnich`() {
        assertColor(LineColors.Other, "13")
        assertColor(LineColors.Other, "999")
    }

    @Test
    fun `oznaceni bez cislice spadne na autobus`() {
        assertColor(LineColors.Bus, "")
    }

    @Test
    fun `nocni barva se nesmi shodovat s pozadim karty`() {
        // Pilulka noční linky se kreslí na surfaceContainer. Kdyby měly stejnou
        // hodnotu, číslo linky by viselo ve vzduchu bez podkladu.
        assertEquals(false, LineColors.Night == 0xFF1C1C1EL)
    }
}
