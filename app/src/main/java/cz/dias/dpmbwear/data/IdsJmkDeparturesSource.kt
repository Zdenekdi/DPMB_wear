package cz.dias.dpmbwear.data

import cz.dias.dpmbwear.data.model.Departure
import java.util.Calendar

/**
 * Ostrý zdroj dat z API KORDIS JMK. Aktivní jen když je vyplněný přístupový klíč.
 */
class IdsJmkDeparturesSource(
    private val api: IdsJmkApi = NetworkClient.api,
    private val apiKey: String = NetworkClient.apiKey,
) : DeparturesSource {

    override suspend fun getDepartures(stopId: String): List<Departure> {
        val response = api.getDepartures(stopId = stopId, key = apiKey)

        return response.PostList
            .orEmpty()
            .flatMap { it.Departures.orEmpty() }
            .mapNotNull { it.toDeparture() }
            .sortedBy { it.timeMinutes }
    }

    private fun ApiDeparture.toDeparture(): Departure? {
        val line = LineName?.trim().orEmpty()
        if (line.isEmpty()) return null
        val minutes = parseTimeMark(TimeMark) ?: return null

        return Departure(
            line = line,
            destination = FinalStop?.trim().orEmpty(),
            timeMinutes = minutes,
            isLive = IsLive == true,
        )
    }

    companion object {

        /** Kolik minut do minulosti se ještě považuje za "právě odjel", ne za zítřek. */
        private const val PAST_TOLERANCE_MINUTES = 180

        private const val MINUTES_PER_DAY = 24 * 60

        /**
         * TimeMark chodí ve dvou tvarech: počet minut ("2") pro blízké spoje,
         * absolutní čas ("15:45") pro vzdálenější. Druhý se musí převést na
         * minuty od teď – a ošetřit půlnoc, jinak by spoj v 00:10 při 23:50
         * vyšel jako −1420 minut.
         *
         * @param nowMinutesOfDay minuty od půlnoci. Parametr existuje kvůli
         *   testům – uvnitř by se čas nedal zafixovat.
         */
        fun parseTimeMark(
            mark: String?,
            nowMinutesOfDay: Int = currentMinutesOfDay(),
        ): Int? {
            val value = mark?.trim().orEmpty()
            if (value.isEmpty()) return null

            value.toIntOrNull()?.let { return it.coerceAtLeast(0) }

            val parts = value.split(":")
            if (parts.size != 2) return null
            val hour = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
            val minute = parts[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return null

            var diff = hour * 60 + minute - nowMinutesOfDay

            // Přetok přes půlnoc funguje na obě strany:
            //  - ve 23:50 je značka "00:10" spoj za 20 minut, ne −1420,
            //  - v 00:05 je značka "23:58" včerejší zbytek, ne spoj za 1433 minut.
            // Rozhoduje, na které straně dne je značka blíž k aktuálnímu času.
            if (diff < -PAST_TOLERANCE_MINUTES) {
                diff += MINUTES_PER_DAY
            } else if (diff > MINUTES_PER_DAY - PAST_TOLERANCE_MINUTES) {
                diff -= MINUTES_PER_DAY
            }

            return diff.coerceAtLeast(0)
        }

        private fun currentMinutesOfDay(): Int = Calendar.getInstance().let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        }
    }
}
