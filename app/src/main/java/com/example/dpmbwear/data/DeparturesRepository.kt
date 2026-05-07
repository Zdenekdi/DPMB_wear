package com.example.dpmbwear.data

import android.graphics.Color
import com.example.dpmbwear.presentation.Departure
import com.example.dpmbwear.presentation.Stop

object DeparturesRepository {
    val staticStops = listOf(
        Stop("1017", "Česká", 49.196, 16.606),
        Stop("1084", "Hlavní nádraží", 49.190, 16.611),
        Stop("1133", "Mendlovo náměstí", 49.191, 16.593),
        Stop("1269", "Uzlová", 49.180, 16.630),
        Stop("1100", "Královo Pole, nádraží", 49.231, 16.595)
    )

    fun getFakeDepartures(stopId: String): List<Departure> {
        val currentTimeMinutes = (System.currentTimeMillis() / 60000).toInt()
        val possibleLines = when (stopId) {
            "1017" -> listOf(Pair("1", "Bystrc, Ečerova"), Pair("6", "Královo Pole, nádraží"), Pair("X60", "Univerzitní kampus"), Pair("5", "Štefánikova čtvrť"), Pair("84", "Stará osada"))
            "1084" -> listOf(Pair("1", "Řečkovice"), Pair("2", "Modřice, smyčka"), Pair("8", "Starý Lískovec"), Pair("9", "Juliánov"), Pair("12", "Technologický park"))
            "1133" -> listOf(Pair("1", "Bystrc, Ečerova"), Pair("5", "Ústřední hřbitov"), Pair("6", "Starý Lískovec"), Pair("25", "Osová"), Pair("26", "Kamenný vrch"), Pair("44", "Okružní linka"))
            "1100" -> listOf(Pair("6", "Starý Lískovec"), Pair("44", "Okružní linka"), Pair("84", "Stará osada"))
            else -> listOf(Pair("1", "Hlavní nádraží"))
        }

        val generatedDepartures = mutableListOf<Departure>()
        possibleLines.forEachIndexed { index, (line, dest) ->
            val seed = currentTimeMinutes + index * 13 + (stopId.toIntOrNull() ?: 0)
            val time1 = seed % 15
            val time2 = time1 + 5 + (seed % 10)
            val color = getColorForLine(line)
            
            generatedDepartures.add(Departure(line, dest, time1, time1 <= 2, androidx.compose.ui.graphics.Color(color.toInt())))
            generatedDepartures.add(Departure(line, dest, time2, false, androidx.compose.ui.graphics.Color(color.toInt())))
        }
        
        return generatedDepartures.sortedBy { it.timeMinutes }
    }

    fun getColorForLine(line: String): Long {
        val uppercaseLine = line.uppercase()
        if (uppercaseLine.startsWith("S") || uppercaseLine.startsWith("R")) return 0xFFF57C00
        if (uppercaseLine.startsWith("N")) return 0xFF1C1C1E
        if (uppercaseLine.startsWith("E")) return 0xFF6A1B9A
        if (uppercaseLine.startsWith("Š") || uppercaseLine.startsWith("S8")) return 0xFF00ACC1
        
        val lineInt = uppercaseLine.replace(Regex("[^0-9]"), "").toIntOrNull() ?: return 0xFF35659C
        
        return when (lineInt) {
            in 1..12 -> 0xFF992224
            in 20..39 -> 0xFF2E7D32
            in 40..99 -> 0xFF35659C
            in 100..109 -> 0xFF6A1B9A
            else -> 0xFF616161
        }
    }
}
