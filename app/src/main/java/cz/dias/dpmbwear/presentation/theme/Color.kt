package cz.dias.dpmbwear.presentation.theme

import androidx.compose.ui.graphics.Color

/**
 * Barevné tokeny aplikace DPMB Wear.
 *
 * Paleta je rozdělena na dvě vrstvy, které se nikdy nemíchají:
 *
 *  1. CHROME – ovládací prvky aplikace. Odvozeno od firemní červené DPMB.
 *     Objevuje se jako pozadí tlačítek a akcentů.
 *  2. DATA (LineColors) – barvy linek podle konvence IDS JMK. Objevují se
 *     výhradně jako malá pilulka s číslem linky, nikdy jako pozadí ovládacího
 *     prvku. Díky tomu se barva "tramvaj" nepoplete s barvou "hlavní akce".
 *
 * Kontrastní poměry jsou počítané proti [Black] (pozadí) nebo [Surface] (karta)
 * a splňují WCAG AA pro velikost textu, ve které se používají.
 */

// ---------------------------------------------------------------- CHROME --

/** Hlavní akce. Světlejší varianta firemní červené – s bílým textem 5,7:1. */
val DpmbRed = Color(0xFFC4262B)

/** Firemní červená DPMB. Stisknutý stav, ambient, méně výrazné plochy. */
val DpmbRedDim = Color(0xFF992224)

/** Tlumené pozadí pro zvýrazněné bloky bez plné sytosti. */
val DpmbRedContainer = Color(0xFF3D0E10)

/** Text a ikony na [DpmbRedContainer] – 8,9:1. */
val DpmbOnRedContainer = Color(0xFFFFB3B0)

// -------------------------------------------------------------- POVRCHY --

/** Pozadí obrazovky. Na OLED hodinkách musí být čistě černá (nesvítí = šetří baterii). */
val Black = Color(0xFF000000)

val SurfaceLow = Color(0xFF131316)

/** Pozadí karty odjezdu. */
val Surface = Color(0xFF1C1C1E)

val SurfaceHigh = Color(0xFF26262A)

val Outline = Color(0xFF48484E)

// ---------------------------------------------------------------- OBSAH --

val OnSurface = Color(0xFFFFFFFF)

/**
 * Sekundární text. Nahrazuje [Color.Gray] (0xFF888888), který měl na [Surface]
 * jen 4,9:1 a při 10sp textu byl na slunci nečitelný. Tento má 7,9:1.
 */
val OnSurfaceVariant = Color(0xFFB0B0B8)

val OnSurfaceDisabled = Color(0xFF6E6E76)

// ------------------------------------------------------ SÉMANTICKÉ STAVY --

/** Vůz je sledován GPS (LIVE). Klidnější než čistě zelená, stále 9,6:1 na černé. */
val LiveGreen = Color(0xFF3ECF6B)

/** Oblíbená zastávka. */
val FavoriteGold = Color(0xFFFFD34E)

/** Chybový stav. Oproti [Color.Red] (5,3:1) má 7,6:1 na černé. */
val ErrorRed = Color(0xFFFF6B6B)

// --------------------------------------------------------------- AMBIENT --
// Always-on displej: smí svítit jen zlomek pixelů, žádné syté barvy.

val AmbientOn = Color(0xFFB8B8B8)
val AmbientDim = Color(0xFF5A5A5A)

/**
 * Barvy linek podle konvence IDS JMK.
 *
 * Pozor: [Tram] je záměrně firemní [DpmbRedDim], ne [DpmbRed]. Odlišení
 * odstínu drží pilulku linky vizuálně oddělenou od tlačítek aplikace.
 */
object LineColors {
    val Tram = 0xFF992224      // linky 1–12
    val Trolleybus = 0xFF2E7D32 // linky 20–39
    val Bus = 0xFF35659C        // linky 40–99
    // Nesmí být 0xFF1C1C1E – to je přesně barva karty odjezdu, pilulka
    // by na ní zmizela. Světlejší neutrál se od karty odliší 2:1.
    val Night = 0xFF4A4A57      // linky N
    val Regional = 0xFF6A1B9A   // linky 100+ a E
    val Train = 0xFFF57C00      // linky S, R
    val Boat = 0xFF00ACC1       // lodní doprava
    val Other = 0xFF616161

    /**
     * Barva pilulky pro označení linky.
     *
     * Vrací ARGB jako Long, ne Compose Color – volá to i dlaždice, která běží
     * mimo Compose a potřebuje prostý Int pro protolayout.
     */
    fun forLine(line: String): Long {
        val upper = line.uppercase()

        // Lodě musí být před vlaky – jinak by "S8" spolklo obecné pravidlo pro "S*".
        if (upper.startsWith("Š") || upper.startsWith("S8")) return Boat
        if (upper.startsWith("S") || upper.startsWith("R")) return Train
        if (upper.startsWith("N")) return Night
        if (upper.startsWith("E")) return Regional

        val number = upper.replace(Regex("[^0-9]"), "").toIntOrNull() ?: return Bus

        return when (number) {
            in 1..12 -> Tram
            in 20..39 -> Trolleybus
            in 40..99 -> Bus
            in 100..109 -> Regional
            else -> Other
        }
    }
}
