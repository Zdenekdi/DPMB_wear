package com.example.dpmbwear.presentation

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

@Composable
fun DeparturesScreen(viewModel: DeparturesViewModel = viewModel()) {
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    val uiState by viewModel.uiState.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Ať už uživatel povolí nebo ne, zkusíme načíst data (při zamítnutí to spadne na fallback)
        viewModel.loadDepartures()
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        when (uiState) {
            is DeparturesUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Hledám polohu...", color = Color.Gray)
                }
            }
            is DeparturesUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        (uiState as DeparturesUiState.Error).message,
                        color = Color.Red,
                        textAlign = TextAlign.Center,
                        fontSize = 12.sp
                    )
                }
            }
            is DeparturesUiState.Success -> {
                val successState = uiState as DeparturesUiState.Success
                val mockData = successState.departures
                val isAmbient = LocalAmbientState.current

                TransformingLazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp, start = 8.dp, end = 8.dp)
                ) {
                    item {
                        ListHeader(
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Previous Stop (Hidden in ambient)
                                if (!isAmbient) {
                                    androidx.wear.compose.material3.IconButton(
                                        onClick = { viewModel.previousStop() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                            contentDescription = "Předchozí",
                                            tint = Color.Gray
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.size(32.dp))
                                }

                                // Center Name and Location info
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = successState.stopName.uppercase(),
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = androidx.compose.material.icons.Icons.Default.LocationOn,
                                            contentDescription = "Location",
                                            tint = if (isAmbient) Color.DarkGray else Color.Gray,
                                            modifier = Modifier.size(10.dp)
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(successState.indexText, fontSize = 10.sp, color = if (isAmbient) Color.DarkGray else Color.Gray)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        
                                        // Favorite Icon (Clickable only when not ambient)
                                        if (!isAmbient) {
                                            Icon(
                                                imageVector = if (successState.isFavorite) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                                                contentDescription = "Oblíbené",
                                                tint = if (successState.isFavorite) Color(0xFFFFD700) else Color.Gray,
                                                modifier = Modifier.size(14.dp).clickable { viewModel.toggleFavorite() }
                                            )
                                        } else if (successState.isFavorite) {
                                            Icon(
                                                imageVector = Icons.Outlined.FavoriteBorder,
                                                contentDescription = "Oblíbené",
                                                tint = Color.White,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }

                                // Next Stop (Hidden in ambient)
                                if (!isAmbient) {
                                    androidx.wear.compose.material3.IconButton(
                                        onClick = { viewModel.nextStop() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                            contentDescription = "Další",
                                            tint = Color.Gray
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.size(32.dp))
                                }
                            }
                        }
                    }

                    if (mockData.isEmpty()) {
                        item {
                            Text("Žádné odjezdy", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = Color.White)
                        }
                    } else {
                        items(mockData.size) { index ->
                            val dep = mockData[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp, horizontal = 8.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(if (isAmbient) Color.Black else Color(0xFF1C1C1E))
                                    .padding(8.dp)
                                    .transformedHeight(this, transformationSpec),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Line Number Pill
                                Box(
                                    modifier = Modifier
                                        .width(40.dp)
                                        .height(28.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (isAmbient) Color.DarkGray else dep.color),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = dep.line,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Destination
                                Text(
                                    text = dep.destination,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    lineHeight = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                Spacer(modifier = Modifier.width(4.dp))

                                // Time and Live Indicator
                                Column(horizontalAlignment = Alignment.End) {
                                    if (dep.isLive && !isAmbient) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(Color.Green))
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text("LIVE", color = Color.Green, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                                        }
                                    } else {
                                        Spacer(modifier = Modifier.height(10.dp))
                                    }
                                    Row(verticalAlignment = Alignment.Bottom) {
                                        Text(
                                            text = dep.timeMinutes.toString(),
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(
                                            text = "m",
                                            color = if (isAmbient) Color.White else Color.Gray,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(bottom = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!isAmbient) {
                        item {
                            Button(
                                onClick = { viewModel.loadDepartures() },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF992224)),
                                modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                                transformation = SurfaceTransformation(transformationSpec),
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh",
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("AKTUALIZOVAT", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
