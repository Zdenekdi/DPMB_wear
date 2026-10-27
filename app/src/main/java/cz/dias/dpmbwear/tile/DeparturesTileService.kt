package cz.dias.dpmbwear.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import cz.dias.dpmbwear.R
import cz.dias.dpmbwear.data.DeparturesRepository
import cz.dias.dpmbwear.data.StopsRepository
import cz.dias.dpmbwear.data.model.Departure
import cz.dias.dpmbwear.presentation.MainActivity
import cz.dias.dpmbwear.presentation.theme.LineColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TEXT_SECONDARY = 0xFFB0B0B8
private const val TEXT_PRIMARY = 0xFFFFFFFF
private const val MAX_ROWS = 3

/** Jak dlouho smí systém dlaždici považovat za aktuální. */
private const val FRESHNESS_INTERVAL_MS = 60_000L

class DeparturesTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val repository by lazy { DeparturesRepository(applicationContext) }
    private val stops by lazy { StopsRepository(applicationContext) }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        // Oblíbená zastávka, jinak poslední zobrazená v aplikaci. Název se
        // bere z paměti zastávek, takže kvůli němu nejde žádný dotaz na síť.
        val favourites = getSharedPreferences("dpmb_prefs", Context.MODE_PRIVATE)
            .getStringSet("favorite_stops", emptySet())
            .orEmpty()
        val stop = stops.preferredStop(favourites)

        // Dlaždice musí vrátit future, ne blokovat. Načtení jde přes stejné
        // repository jako aplikace, takže sdílí cache i výběr zdroje.
        val future = SettableFuture.create<TileBuilders.Tile>()
        val job = scope.launch {
            try {
                val departures = if (stop == null) {
                    emptyList()
                } else {
                    try {
                        repository.load(stop.id).departures
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        repository.cached(stop.id)?.departures.orEmpty()
                    }
                }

                future.set(
                    TileBuilders.Tile.Builder()
                        .setResourcesVersion("1")
                        // Bez tohohle by dlaždice držela minuty z okamžiku
                        // posledního otevření, dokud ji uživatel neotevře znovu.
                        .setFreshnessIntervalMillis(FRESHNESS_INTERVAL_MS)
                        .setTileTimeline(
                            TimelineBuilders.Timeline.fromLayoutElement(
                                layout(
                                    stopName = stop?.name ?: getString(R.string.tile_open_app),
                                    departures = departures.take(MAX_ROWS),
                                    deviceParameters = requestParams.deviceConfiguration,
                                )
                            )
                        )
                        .build()
                )
            } catch (e: Throwable) {
                // Nedokončený SettableFuture nechá systém čekat donekonečna.
                // Jakákoli chyba se musí propsat ven.
                future.setException(e)
            }
        }
        // Když systém o dlaždici přestane stát, ať doběhne i coroutina.
        future.addListener({ if (future.isCancelled) job.cancel() }, Runnable::run)
        return future
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun layout(
        stopName: String,
        departures: List<Departure>,
        deviceParameters: DeviceParameters,
    ): LayoutElement = PrimaryLayout.Builder(deviceParameters)
        .setContent(
            LayoutElementBuilders.Column.Builder()
                .addContent(
                    Text.Builder(this, stopName.uppercase())
                        .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                        // Byla tu modrá 0xFF35659C – na černé dlaždici jen 3,5:1
                        // a navíc stejný odstín, jakým se značí autobusové linky.
                        // Odpovídá tokenu OnSurfaceVariant (9,6:1).
                        .setColor(argb(TEXT_SECONDARY.toInt()))
                        .build()
                )
                .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
                .apply {
                    departures.forEach { departure ->
                        addContent(departureRow(departure))
                        addContent(
                            LayoutElementBuilders.Spacer.Builder().setHeight(dp(2f)).build()
                        )
                    }
                }
                .setModifiers(
                    ModifiersBuilders.Modifiers.Builder()
                        .setClickable(
                            ModifiersBuilders.Clickable.Builder()
                                .setId("open_app")
                                .setOnClick(
                                    ActionBuilders.LaunchAction.Builder()
                                        .setAndroidActivity(
                                            ActionBuilders.AndroidActivity.Builder()
                                                .setPackageName(packageName)
                                                .setClassName(MainActivity::class.java.name)
                                                .build()
                                        )
                                        .build()
                                )
                                .build()
                        )
                        .build()
                )
                .build()
        )
        .build()

    private fun departureRow(departure: Departure): LayoutElement =
        LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setBackground(
                                ModifiersBuilders.Background.Builder()
                                    // Barva se odvozuje ze stejného tokenu jako
                                    // v aplikaci, takže dlaždice a obrazovka
                                    // nemohou rozejít.
                                    .setColor(argb(LineColors.forLine(departure.line).toInt()))
                                    .setCorner(
                                        ModifiersBuilders.Corner.Builder()
                                            .setRadius(dp(4f))
                                            .build()
                                    )
                                    .build()
                            )
                            .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(2f)).build())
                            .build()
                    )
                    .addContent(
                        Text.Builder(this, departure.line)
                            .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                            .setColor(argb(TEXT_PRIMARY.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(4f)).build())
            .addContent(
                Text.Builder(this, departure.destination)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setPadding(
                                ModifiersBuilders.Padding.Builder().setEnd(dp(4f)).build()
                            )
                            .build()
                    )
                    .build()
            )
            .addContent(
                Text.Builder(this, "${departure.timeMinutes}m")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .build()
            )
            .build()

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = com.google.common.util.concurrent.Futures
        .immediateFuture(
            ResourceBuilders.Resources.Builder().setVersion("1").build()
        )
}
