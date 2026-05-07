package com.example.dpmbwear.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Colors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.example.dpmbwear.data.DeparturesRepository
import com.example.dpmbwear.presentation.MainActivity
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

class DeparturesTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val lastStopId = getSharedPreferences("dpmb_prefs", Context.MODE_PRIVATE)
            .getStringSet("favorite_stops", emptySet())?.firstOrNull() ?: "1017"
            
        val stop = DeparturesRepository.staticStops.find { it.id == lastStopId } ?: DeparturesRepository.staticStops[0]
        val departures = DeparturesRepository.getFakeDepartures(stop.id).take(3)

        return Futures.immediateFuture(
            TileBuilders.Tile.Builder()
                .setResourcesVersion("1")
                .setTileTimeline(
                    TimelineBuilders.Timeline.fromLayoutElement(
                        layout(stop.name, departures, requestParams.deviceConfiguration)
                    )
                ).build()
        )
    }

    private fun layout(stopName: String, departures: List<com.example.dpmbwear.presentation.Departure>, deviceParameters: DeviceParameters): LayoutElement {
        return PrimaryLayout.Builder(deviceParameters)
            .setContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(
                        Text.Builder(this, stopName.uppercase())
                            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                            .setColor(argb(0xFF35659C.toInt()))
                            .build()
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(4f)).build())
                    .apply {
                        departures.forEach { dep ->
                            addContent(departureRow(dep))
                            addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(2f)).build())
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
                                            ).build()
                                    ).build()
                            ).build()
                    )
                    .build()
            )
            .build()
    }

    private fun departureRow(dep: com.example.dpmbwear.presentation.Departure): LayoutElement {
        return LayoutElementBuilders.Row.Builder()
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(
                LayoutElementBuilders.Box.Builder()
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setBackground(
                                ModifiersBuilders.Background.Builder()
                                    .setColor(argb(dep.color.value.toInt()))
                                    .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(4f)).build())
                                    .build()
                            )
                            .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(2f)).build())
                            .build()
                    )
                    .addContent(
                        Text.Builder(this, dep.line)
                            .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                            .setColor(argb(0xFFFFFFFF.toInt()))
                            .build()
                    )
                    .build()
            )
            .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(4f)).build())
            .addContent(
                Text.Builder(this, dep.destination)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION2)
                    .setModifiers(ModifiersBuilders.Modifiers.Builder().setPadding(ModifiersBuilders.Padding.Builder().setEnd(dp(4f)).build()).build())
                    .build()
            )
            .addContent(
                Text.Builder(this, "${dep.timeMinutes}m")
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setWeight(LayoutElementBuilders.FONT_WEIGHT_BOLD)
                    .build()
            )
            .build()
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<androidx.wear.protolayout.ResourceBuilders.Resources> {
        return Futures.immediateFuture(
            androidx.wear.protolayout.ResourceBuilders.Resources.Builder()
                .setVersion("1")
                .build()
        )
    }
}
