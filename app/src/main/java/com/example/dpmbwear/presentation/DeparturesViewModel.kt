package com.example.dpmbwear.presentation

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.*

data class Stop(val id: String, val name: String, val lat: Double, val lon: Double)

class DeparturesViewModel(application: Application) : AndroidViewModel(application) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    private val sharedPrefs = application.getSharedPreferences("dpmb_prefs", Context.MODE_PRIVATE)

    // Statická databáze větších brněnských zastávek (pro MVP zkrácená)
    private val staticStops = com.example.dpmbwear.data.DeparturesRepository.staticStops

    private val _uiState = MutableStateFlow<DeparturesUiState>(DeparturesUiState.Loading)
    val uiState: StateFlow<DeparturesUiState> = _uiState

    // Držíme aktuální výběr blízkych zastávek a index
    private var nearbyStops: List<Stop> = emptyList()
    private var currentStopIndex = 0

    @SuppressLint("MissingPermission")
    fun loadDepartures(useCache: Boolean = false) {
        if (!useCache || nearbyStops.isEmpty()) {
            _uiState.value = DeparturesUiState.Loading
            val cancellationTokenSource = CancellationTokenSource()

            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                .addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        calculateNearbyStops(location.latitude, location.longitude)
                    } else {
                        calculateNearbyStops(staticStops[0].lat, staticStops[0].lon) // Fallback (Česká)
                    }
                }
                .addOnFailureListener {
                    calculateNearbyStops(staticStops[0].lat, staticStops[0].lon)
                }
        } else {
            // Jen načteme data pro existující index
            _uiState.value = DeparturesUiState.Loading
            fetchDataForStop(nearbyStops[currentStopIndex])
        }
    }

    private fun calculateNearbyStops(lat: Double, lon: Double) {
        // Seřadit podle vzdálenosti
        val sortedByDistance = staticStops.sortedBy { distance(lat, lon, it.lat, it.lon) }
        val favoritesIds = getFavoriteStopIds()

        // Pokud je nějaká oblíbená blízko (v top 5), posuneme ji na první místo
        val topStops = sortedByDistance.take(5).toMutableList()
        val favoriteNearby = topStops.find { it.id in favoritesIds }
        if (favoriteNearby != null) {
            topStops.remove(favoriteNearby)
            topStops.add(0, favoriteNearby)
        }

        nearbyStops = topStops.take(3) // Necháme přepínat jen mezi top 3
        currentStopIndex = 0
        fetchDataForStop(nearbyStops[currentStopIndex])
    }

    fun nextStop() {
        if (nearbyStops.isEmpty()) return
        currentStopIndex = (currentStopIndex + 1) % nearbyStops.size
        _uiState.value = DeparturesUiState.Loading
        fetchDataForStop(nearbyStops[currentStopIndex])
    }

    fun previousStop() {
        if (nearbyStops.isEmpty()) return
        currentStopIndex = if (currentStopIndex - 1 < 0) nearbyStops.size - 1 else currentStopIndex - 1
        _uiState.value = DeparturesUiState.Loading
        fetchDataForStop(nearbyStops[currentStopIndex])
    }

    fun toggleFavorite() {
        if (nearbyStops.isEmpty()) return
        val stop = nearbyStops[currentStopIndex]
        val favs = getFavoriteStopIds().toMutableSet()
        if (favs.contains(stop.id)) {
            favs.remove(stop.id)
        } else {
            favs.add(stop.id)
        }
        sharedPrefs.edit().putStringSet("favorite_stops", favs).apply()
        // Překreslení s novým stavem hvězdičky
        val currentSuccess = _uiState.value as? DeparturesUiState.Success
        if (currentSuccess != null) {
            _uiState.value = currentSuccess.copy(isFavorite = favs.contains(stop.id))
        }
    }

    private fun getFavoriteStopIds(): Set<String> {
        return sharedPrefs.getStringSet("favorite_stops", emptySet()) ?: emptySet()
    }

    private fun fetchDataForStop(stop: Stop) {
        viewModelScope.launch {
            try {
                kotlinx.coroutines.delay(600) // Fake zpoždění
                val sortedDepartures = com.example.dpmbwear.data.DeparturesRepository.getFakeDepartures(stop.id).take(10)
                val isFav = getFavoriteStopIds().contains(stop.id)
                
                val stopIndexText = "${currentStopIndex + 1}/${nearbyStops.size}"
                _uiState.value = DeparturesUiState.Success(stop.name, sortedDepartures, isFav, stopIndexText)
            } catch (e: Exception) {
                _uiState.value = DeparturesUiState.Error("Chyba: ${e.message}")
            }
        }
    }

    private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371 // km
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}

sealed class DeparturesUiState {
    object Loading : DeparturesUiState()
    data class Success(val stopName: String, val departures: List<Departure>, val isFavorite: Boolean, val indexText: String) : DeparturesUiState()
    data class Error(val message: String) : DeparturesUiState()
}

data class Departure(
    val line: String,
    val destination: String,
    val timeMinutes: Int,
    val isLive: Boolean,
    val color: androidx.compose.ui.graphics.Color
)
