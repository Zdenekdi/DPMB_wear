package cz.dias.dpmbwear.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.wear.ambient.AmbientLifecycleObserver
// Wear vlastní pager – HorizontalPageIndicator přijímá jen
// androidx.wear.compose.foundation.pager.PagerState, ne ten z compose-foundation.
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.HorizontalPageIndicator
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.ui.tooling.preview.WearPreviewFontScales
import cz.dias.dpmbwear.presentation.theme.DPMBWearTheme
import kotlinx.coroutines.delay

val LocalAmbientState = staticCompositionLocalOf { false }

/**
 * Zvýší se pokaždé, když systém v ambientu požádá o překreslení (zhruba
 * jednou za minutu). Obrazovky, které zobrazují ubíhající čas, si tím vynutí
 * přepočet, aniž by musely sahat na síť.
 */
val LocalAmbientTick = staticCompositionLocalOf { 0 }

/** Jak dlouho zůstane indikátor stránek viditelný po dokončení swipu. */
private const val PAGE_INDICATOR_TIMEOUT_MS = 2_000L

class MainActivity : ComponentActivity(), AmbientLifecycleObserver.AmbientLifecycleCallback {
    private var isAmbient by mutableStateOf(false)
    private var ambientTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Musí být před super.onCreate. Bez tohoto volání se nikdy neuplatní
        // postSplashScreenTheme ze styles.xml a aktivita zůstane celý život
        // na Theme.SplashScreen – závislost core-splashscreen byla mrtvá.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        lifecycle.addObserver(AmbientLifecycleObserver(this, this))

        setContent {
            CompositionLocalProvider(
                LocalAmbientState provides isAmbient,
                LocalAmbientTick provides ambientTick,
            ) {
                WearApp()
            }
        }
    }

    override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
        isAmbient = true
    }

    override fun onExitAmbient() {
        isAmbient = false
    }

    /**
     * Systém sem volá zhruba jednou za minutu, dokud jsme v ambientu.
     *
     * Metoda byla prázdná, takže minuty do odjezdu na always-on displeji
     * zamrzly na hodnotě z okamžiku uspání – po deseti minutách koukání
     * aplikace pořád tvrdila "za 3 min".
     *
     * Nesahá se na síť, jen se přepočítá už stažený stav.
     */
    override fun onUpdateAmbient() {
        ambientTick++
    }
}

@Composable
fun WearApp() {
    DPMBWearTheme {
        AppScaffold {
            val pagerState = rememberPagerState(pageCount = { 3 })
            val isAmbient = LocalAmbientState.current

            // Bez indikátoru nebylo poznat, že za odjezdy jsou ještě dvě
            // obrazovky. Zobrazuje se při swipu a po chvíli zmizí, aby
            // nepřekrýval spodní tlačítko a nesvítil zbytečně na OLEDu.
            var indicatorVisible by remember { mutableStateOf(true) }
            LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
                indicatorVisible = true
                if (!pagerState.isScrollInProgress) {
                    delay(PAGE_INDICATOR_TIMEOUT_MS)
                    indicatorVisible = false
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> DeparturesScreen()
                        1 -> SmsScreen()
                        2 -> QrScreen()
                    }
                }

                AnimatedVisibility(
                    visible = indicatorVisible && !isAmbient,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    // Indikátor si oblouk počítá vůči vlastním hranicím –
                    // bez zarovnání dolů vykreslí tečky mimo displej.
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    HorizontalPageIndicator(pagerState = pagerState)
                }
            }
        }
    }
}

@WearPreviewDevices
@WearPreviewFontScales
@Composable
fun DefaultPreview() {
    WearApp()
}
