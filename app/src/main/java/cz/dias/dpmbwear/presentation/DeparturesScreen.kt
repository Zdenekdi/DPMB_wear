package cz.dias.dpmbwear.presentation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
// Ne androidx.compose.ui.platform.LocalLifecycleOwner – ten je od Compose 1.7
// zastaralý a přestěhoval se do lifecycle-runtime-compose.
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.compose.runtime.remember
import cz.dias.dpmbwear.R
import cz.dias.dpmbwear.data.DeparturesRepository
import cz.dias.dpmbwear.data.model.Departure
import cz.dias.dpmbwear.presentation.theme.AmbientDim
import cz.dias.dpmbwear.presentation.theme.AmbientOn
import cz.dias.dpmbwear.presentation.theme.FavoriteGold
import cz.dias.dpmbwear.presentation.theme.LineColors
import cz.dias.dpmbwear.presentation.theme.LiveGreen

/** Minimální doporučený dotykový cíl na Wear OS. */
private val TouchTargetHeight = 48.dp

/** Šipky jsou užší než 48dp – na 192dp obrazovce by dva čtvercové cíle sežraly polovinu šířky. */
private val ArrowTouchWidth = 36.dp

@Composable
fun DeparturesScreen(viewModel: DeparturesViewModel = viewModel()) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val uiState by viewModel.uiState.collectAsState()
    val isAmbient = LocalAmbientState.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Ať už uživatel povolí nebo ne, zkusíme načíst data – bez polohy
        // se spadne na výchozí zastávku v centru.
        viewModel.loadDepartures()
    }

    LaunchedEffect(Unit) {
        // Dřív se dialog vyvolával při každém startu. Systém ho sice po zamítnutí
        // přestane zobrazovat, ale zbytečné volání to bylo pokaždé.
        if (viewModel.hasLocationPermission()) {
            viewModel.loadDepartures()
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            )
        }
    }

    // Obnovování běží jen dokud je obrazovka vpředu a mimo ambient.
    //
    // Ambient je potřeba hlídat zvlášť: always-on aktivita zůstává RESUMED,
    // takže ON_PAUSE nikdy nepřijde a bez podmínky by se každých 30 s budilo
    // rádio po celou dobu, co má uživatel ruku dole. Čísla se v ambientu
    // dopočítávají z už stažených dat přes LocalAmbientTick.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, isAmbient) {
        if (isAmbient) viewModel.stopAutoRefresh()

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (!isAmbient) viewModel.startAutoRefresh()
                Lifecycle.Event.ON_PAUSE -> viewModel.stopAutoRefresh()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.stopAutoRefresh()
        }
    }

    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            // EdgeButton je M3 pattern pro hlavní akci na konci seznamu –
            // kopíruje zaoblení displeje a nedá se odscrollovat pryč.
            EdgeButton(
                onClick = { viewModel.loadDepartures() },
                colors = ButtonDefaults.buttonColors(
                    // V ambientu nesmí svítit sytá plocha (baterie + burn-in).
                    containerColor = if (isAmbient) Color.Transparent else MaterialTheme.colorScheme.primary,
                    contentColor = if (isAmbient) AmbientDim else MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.departures_refresh),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
    ) { contentPadding ->
        when (val state = uiState) {
            is DeparturesUiState.Loading -> LoadingState(contentPadding)

            // Opakování pokusu řeší EdgeButton "Aktualizovat" – je vidět
            // ve všech stavech, takže chybová obrazovka nepotřebuje vlastní tlačítko.
            is DeparturesUiState.Error -> ErrorState(state.message, contentPadding)

            is DeparturesUiState.Success -> {
                // Ambientní tik chodí ze systému zhruba jednou za minutu.
                // Bez něj by čísla zamrzla na hodnotě z okamžiku uspání displeje.
                val ambientTick = LocalAmbientTick.current

                // Počítá se od adjustedAt, ne od fetchedAt. U dat z cache už
                // repository minuty jednou posunula – od fetchedAt by se stáří
                // odečetlo podruhé a odjezdy by vyšly o polovinu bližší.
                //
                // Přepočet používá stejnou (otestovanou) funkci jako cache,
                // takže se obě cesty nemohou rozejít.
                val elapsedMinutes = state.minutesSinceAdjustment()
                val visibleDepartures = remember(state.departures, elapsedMinutes, ambientTick) {
                    DeparturesRepository.adjustForAge(state.departures, elapsedMinutes)
                }

                TransformingLazyColumn(
                    state = listState,
                    contentPadding = contentPadding,
                ) {
                    item {
                        ListHeader(
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            StopHeader(
                                state = state,
                                isAmbient = isAmbient,
                                onPrevious = viewModel::previousStop,
                                onNext = viewModel::nextStop,
                                onToggleFavorite = viewModel::toggleFavorite,
                            )
                        }
                    }

                    // Uživatel musí vědět, když nekouká na živá data. Bez toho
                    // by mu aplikace s klidem ukázala odjezd, který neexistuje.
                    if (state.isDemo || state.isStale) {
                        item { DataQualityBadge(state = state, isAmbient = isAmbient) }
                    }

                    if (visibleDepartures.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.departures_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                            )
                        }
                    } else {
                        items(visibleDepartures.size) { index ->
                            DepartureRow(
                                departure = visibleDepartures[index],
                                isAmbient = isAmbient,
                                modifier = Modifier.transformedHeight(this, transformationSpec),
                            )
                        }
                    }

                    // Licence CC BY 4.0 vyžaduje uvedení zdroje. Zároveň tu
                    // přiznáváme, že zpoždění je odhad, ne hlášení dopravce.
                    if (state.showAttribution && !isAmbient) {
                        item {
                            Text(
                                text = stringResource(R.string.departures_attribution),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DataQualityBadge(state: DeparturesUiState.Success, isAmbient: Boolean) {
    val text = when {
        state.isDemo -> stringResource(R.string.departures_demo)
        else -> stringResource(R.string.departures_stale, state.staleMinutes())
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            tint = if (isAmbient) AmbientDim else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(11.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (isAmbient) AmbientDim else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LoadingState(contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.departures_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, contentPadding: PaddingValues) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Bez odsazení scaffoldu by text zaběhl pod EdgeButton.
            .padding(contentPadding)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun StopHeader(
    state: DeparturesUiState.Success,
    isAmbient: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StopArrow(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                description = stringResource(R.string.departures_previous_stop),
                visible = !isAmbient,
                onClick = onPrevious,
            )

            Text(
                text = state.stopName.uppercase(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (isAmbient) AmbientOn else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            StopArrow(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                description = stringResource(R.string.departures_next_stop),
                visible = !isAmbient,
                onClick = onNext,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = if (isAmbient) AmbientDim else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp),
            )
            Spacer(modifier = Modifier.width(3.dp))
            Text(
                text = state.indexText,
                style = MaterialTheme.typography.labelSmall,
                color = if (isAmbient) AmbientDim else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (isAmbient) {
                if (state.isFavorite) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = stringResource(R.string.departures_favorite),
                        tint = AmbientOn,
                        modifier = Modifier.size(14.dp),
                    )
                }
            } else {
                // Dřív to byla 14dp ikona s clickable – nedalo se to trefit.
                // Teď plnohodnotné IconButton s vlastním dotykovým cílem.
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (state.isFavorite) {
                            Icons.Default.Favorite
                        } else {
                            Icons.Outlined.FavoriteBorder
                        },
                        contentDescription = stringResource(
                            if (state.isFavorite) {
                                R.string.departures_favorite_remove
                            } else {
                                R.string.departures_favorite_add
                            }
                        ),
                        tint = if (state.isFavorite) FavoriteGold else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StopArrow(
    icon: ImageVector,
    description: String,
    visible: Boolean,
    onClick: () -> Unit,
) {
    // Pevný Box drží stejnou šířku v obou větvích. IconButton si vnitřně
    // vynucuje 48dp minimální dotykový cíl a bez obalu by se název zastávky
    // při přechodu do ambientu posunul.
    Box(
        modifier = Modifier.size(width = ArrowTouchWidth, height = TouchTargetHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (visible) {
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = icon,
                    contentDescription = description,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun DepartureRow(
    departure: Departure,
    isAmbient: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp, horizontal = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(if (isAmbient) Color.Black else MaterialTheme.colorScheme.surfaceContainer)
            .defaultMinSize(minHeight = TouchTargetHeight)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pilulka s číslem linky – barva nese význam (tramvaj / trolejbus / bus).
        Box(
            modifier = Modifier
                .defaultMinSize(minWidth = 40.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (isAmbient) Color.Transparent else Color(LineColors.forLine(departure.line))
                )
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = departure.line,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (isAmbient) AmbientOn else Color.White,
                maxLines = 1,
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = departure.destination,
            style = MaterialTheme.typography.bodySmall,
            color = if (isAmbient) AmbientOn else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(6.dp))

        Column(horizontalAlignment = Alignment.End) {
            if (departure.isLive && !isAmbient) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(CircleShape)
                            .background(LiveGreen),
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    // Zpoždění ukazujeme jen kladné. Náskok se do minut
                    // nepromítá (server počítá s odjezdem podle řádu), takže
                    // "−2" by jen mátlo.
                    val delay = departure.delayMinutes?.takeIf { it > 0 }
                    Text(
                        text = if (delay != null) {
                            stringResource(R.string.departures_live_delay, delay)
                        } else {
                            stringResource(R.string.departures_live)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = LiveGreen,
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = departure.timeMinutes.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isAmbient) AmbientOn else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = stringResource(R.string.departures_minutes_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isAmbient) AmbientDim else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}
