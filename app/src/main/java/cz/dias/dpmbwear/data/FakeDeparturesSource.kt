package cz.dias.dpmbwear.data

import cz.dias.dpmbwear.data.model.Departure
import kotlinx.coroutines.delay

/**
 * Ukázková data pro vývoj a pro stav, kdy ještě není přístupový klíč.
 *
 * Čísla se odvozují od aktuální minuty, takže odjezdy "ubíhají" a UI se dá
 * vyzkoušet. Nejsou to reálné jízdní řády a nikdy nesmí skončit u uživatele
 * jako by byly – proto [DeparturesRepository.isUsingRealData].
 */
class FakeDeparturesSource : DeparturesSource {

    override val isDemo: Boolean get() = true

    override suspend fun getDepartures(stopId: String): List<Departure> {
        delay(400) // Simuluje latenci sítě, ať jde vyzkoušet stav načítání.

        val currentTimeMinutes = (System.currentTimeMillis() / 60000).toInt()
        val lines = linesForStop(stopId)

        return buildList {
            lines.forEachIndexed { index, (line, destination) ->
                val seed = currentTimeMinutes + index * 13 + (stopId.toIntOrNull() ?: 0)
                val first = seed % 15
                val second = first + 5 + (seed % 10)
                add(Departure(line, destination, first, isLive = first <= 2))
                add(Departure(line, destination, second, isLive = false))
            }
        }.sortedBy { it.timeMinutes }
    }

    private fun linesForStop(stopId: String): List<Pair<String, String>> = when (stopId) {
        "1017" -> listOf(
            "1" to "Bystrc, Ečerova",
            "6" to "Královo Pole, nádraží",
            "X60" to "Univerzitní kampus",
            "5" to "Štefánikova čtvrť",
            "84" to "Stará osada",
        )

        "1084" -> listOf(
            "1" to "Řečkovice",
            "2" to "Modřice, smyčka",
            "8" to "Starý Lískovec",
            "9" to "Juliánov",
            "12" to "Technologický park",
        )

        "1133" -> listOf(
            "1" to "Bystrc, Ečerova",
            "5" to "Ústřední hřbitov",
            "6" to "Starý Lískovec",
            "25" to "Osová",
            "26" to "Kamenný vrch",
            "44" to "Okružní linka",
        )

        "1100" -> listOf(
            "6" to "Starý Lískovec",
            "44" to "Okružní linka",
            "84" to "Stará osada",
        )

        else -> listOf("1" to "Hlavní nádraží")
    }
}
