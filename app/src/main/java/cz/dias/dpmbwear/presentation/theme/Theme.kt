package cz.dias.dpmbwear.presentation.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme

/**
 * Barevné schéma aplikace.
 *
 * Wear OS má jen tmavý režim – displej je vždy na černém pozadí, takže se
 * nedefinuje světlá varianta.
 *
 * Modrá (0xFF35659C), která se dřív používala pro SMS jízdenky a QR, byla
 * odstraněna z ovládacích prvků. Kolidovala s barvou autobusových linek, kde
 * má stejný odstín konkrétní význam. Všechny hlavní akce jsou nyní červené.
 */
private val DpmbColorScheme = ColorScheme(
    primary = DpmbRed,
    primaryDim = DpmbRedDim,
    primaryContainer = DpmbRedContainer,
    onPrimary = OnSurface,
    onPrimaryContainer = DpmbOnRedContainer,

    // Sekundární akce jsou záměrně nebarevné – veškerý barevný důraz
    // patří datům (barvy linek) a primární akci.
    secondary = SurfaceHigh,
    secondaryDim = Surface,
    secondaryContainer = SurfaceLow,
    onSecondary = OnSurface,
    onSecondaryContainer = OnSurfaceVariant,

    background = Black,
    onBackground = OnSurface,

    surfaceContainerLow = SurfaceLow,
    surfaceContainer = Surface,
    surfaceContainerHigh = SurfaceHigh,
    onSurface = OnSurface,
    onSurfaceVariant = OnSurfaceVariant,

    outline = Outline,
    outlineVariant = SurfaceHigh,

    error = ErrorRed,
    onError = Black,
)

@Composable
fun DPMBWearTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DpmbColorScheme,
        content = content
    )
}
