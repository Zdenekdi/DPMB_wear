package cz.dias.dpmbwear.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import cz.dias.dpmbwear.R
import cz.dias.dpmbwear.data.DeparturesRepository
import cz.dias.dpmbwear.data.ServerClient
import cz.dias.dpmbwear.data.StopsRepository
import cz.dias.dpmbwear.data.model.Departure
import cz.dias.dpmbwear.data.model.Stop
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Jak často se odjezdy obnoví, dokud je obrazovka vpředu. */
private const val AUTO_REFRESH_INTERVAL_MS = 30_000L

/** Mezi kolika nejbližšími zastávkami lze přepínat. */
private const val NEARBY_STOPS_COUNT = 3

class DeparturesViewModel(application: Application) : AndroidViewModel(application) {

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(application)
    private val prefs = application.getSharedPreferences("dpmb_prefs", Context.MODE_PRIVATE)
    private val repository = DeparturesRepository(application)
    private val stops = StopsRepository(application)

    private val _uiState = MutableStateFlow<DeparturesUiState>(DeparturesUiState.Loading)
    val uiState: StateFlow<DeparturesUiState> = _uiState.asStateFlow()

    private var nearbyStops: List<Stop> = emptyList()
    private var currentStopIndex = 0
    private var autoRefreshJob: Job? = null

    /** True, pokud běžíme na ukázkových datech, protože chybí přístupový klíč. */
    val isDemoData: Boolean = !repository.isUsingRealData

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    fun loadDepartures() {
        viewModelScope.launch {
            if (nearbyStops.isEmpty()) {
                _uiState.value = DeparturesUiState.Loading
                val location = resolveLocation()
                try {
                    selectNearbyStops(location)
                } catch (e: Exception) {
                    // Se serverem a bez signálu při úplně prvním spuštění
                    // nemáme ani zapamatované zastávky.
                    _uiState.value = DeparturesUiState.Error(e.toUserMessage())
                    return@launch
                }
            }
            fetchCurrentStop(showLoading = true)
        }
    }

    /**
     * Obnovuje odjezdy, dokud je obrazovka vpředu.
     *
     * Bez tohohle zůstanou čísla zmrazená na hodnotě z okamžiku otevření –
     * a "za 3 min" po pěti minutách koukání lže. Volá se z lifecycle observeru,
     * takže na pozadí ani v ambientu nic neběží.
     */
    fun startAutoRefresh() {
        if (autoRefreshJob?.isActive == true) return
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(AUTO_REFRESH_INTERVAL_MS)
                if (nearbyStops.isNotEmpty()) fetchCurrentStop(showLoading = false)
            }
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    fun nextStop() = switchStop(1)

    fun previousStop() = switchStop(-1)

    private fun switchStop(delta: Int) {
        if (nearbyStops.isEmpty()) return
        currentStopIndex = (currentStopIndex + delta + nearbyStops.size) % nearbyStops.size
        viewModelScope.launch { fetchCurrentStop(showLoading = true) }
    }

    fun toggleFavorite() {
        if (nearbyStops.isEmpty()) return
        val stop = nearbyStops[currentStopIndex]
        val favourites = favouriteStopIds().toMutableSet()
        if (!favourites.remove(stop.id)) favourites.add(stop.id)
        prefs.edit().putStringSet(KEY_FAVOURITES, favourites).apply()

        val current = _uiState.value as? DeparturesUiState.Success ?: return
        _uiState.value = current.copy(isFavorite = favourites.contains(stop.id))
    }

    /**
     * Nejdřív poslední známá poloha, teprve když chybí, tak nový fix.
     *
     * Původní kód sahal při každém načtení po PRIORITY_HIGH_ACCURACY, což je
     * plný GPS fix – na hodinkách nejdražší operace, jakou lze udělat. Ve městě
     * mezi zastávkami vzdálenými stovky metrů stačí hrubší poloha z cache.
     */
    @SuppressLint("MissingPermission")
    private suspend fun resolveLocation(): Location? {
        if (!hasLocationPermission()) return null

        return try {
            lastKnownLocation() ?: currentLocation()
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun lastKnownLocation(): Location? =
        suspendCancellableCoroutine { continuation ->
            fusedLocationClient.lastLocation
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resume(null) }
        }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? =
        suspendCancellableCoroutine { continuation ->
            val cancellation = CancellationTokenSource()
            continuation.invokeOnCancellation { cancellation.cancel() }
            fusedLocationClient
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellation.token)
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resume(null) }
        }

    private suspend fun selectNearbyStops(location: Location?) {
        // Bez polohy začínáme na České – je to přestupní uzel v centru, takže
        // je to nejmíň špatný odhad.
        val lat = location?.latitude ?: StopsRepository.staticStops[0].lat
        val lon = location?.longitude ?: StopsRepository.staticStops[0].lon

        val candidates = stops.nearest(lat, lon, limit = 5).toMutableList()

        // Oblíbenou zastávku v okolí posuneme na první místo.
        val favourites = favouriteStopIds()
        candidates.indexOfFirst { it.id in favourites }
            .takeIf { it > 0 }
            ?.let { candidates.add(0, candidates.removeAt(it)) }

        nearbyStops = candidates.take(NEARBY_STOPS_COUNT)
        currentStopIndex = 0
    }

    private suspend fun fetchCurrentStop(showLoading: Boolean) {
        val stop = nearbyStops.getOrNull(currentStopIndex) ?: return
        if (showLoading) _uiState.value = DeparturesUiState.Loading

        try {
            val result = repository.load(stop.id)
            stops.lastStopId = stop.id
            _uiState.value = DeparturesUiState.Success(
                stopName = stop.name,
                departures = result.departures.take(10),
                isFavorite = favouriteStopIds().contains(stop.id),
                indexText = "${currentStopIndex + 1}/${nearbyStops.size}",
                isStale = result.isStale,
                isDemo = isDemoData,
                showAttribution = ServerClient.isConfigured,
                fetchedAt = result.fetchedAt,
                adjustedAt = result.adjustedAt,
            )
        } catch (e: Exception) {
            // Automatické obnovení nesmí shodit obrazovku s platnými daty.
            if (!showLoading && _uiState.value is DeparturesUiState.Success) return
            _uiState.value = DeparturesUiState.Error(e.toUserMessage())
        }
    }

    private fun Exception.toUserMessage(): String = getApplication<Application>().getString(
        when (this) {
            // UnknownHost i SocketTimeout jsou potomci IOException, takže
            // stačí jedna větev – ale vypsané zůstávají kvůli čitelnosti.
            is java.io.IOException -> R.string.departures_error_network
            else -> R.string.departures_error_generic
        }
    )

    private fun favouriteStopIds(): Set<String> =
        prefs.getStringSet(KEY_FAVOURITES, emptySet()) ?: emptySet()

    override fun onCleared() {
        stopAutoRefresh()
        super.onCleared()
    }

    private companion object {
        const val KEY_FAVOURITES = "favorite_stops"
    }
}

sealed interface DeparturesUiState {
    data object Loading : DeparturesUiState

    data class Success(
        val stopName: String,
        val departures: List<Departure>,
        val isFavorite: Boolean,
        val indexText: String,
        /** Data pocházejí z cache, protože síť selhala. */
        val isStale: Boolean,
        /** Chybí přístupový klíč, jde o ukázková data. */
        val isDemo: Boolean,
        /**
         * Data jdou z otevřených dat KORDIS JMK pod CC BY 4.0, která vyžaduje
         * uvedení zdroje. Zároveň upozorňuje, že zpoždění je odhad.
         */
        val showAttribution: Boolean,
        /** Kdy data přišla ze sítě. */
        val fetchedAt: Long,
        /** Okamžik, ke kterému platí minuty v [departures]. Viz DeparturesResult. */
        val adjustedAt: Long,
    ) : DeparturesUiState {

        /** Stáří dat v minutách, pro popisek "Bez signálu · před X min". */
        fun staleMinutes(): Int =
            ((System.currentTimeMillis() - fetchedAt) / 60_000L).toInt().coerceAtLeast(0)

        /**
         * Kolik minut uplynulo od okamžiku, ke kterému platí zobrazené časy.
         * Používá obrazovka k dopočtu v ambientu, kdy se data neobnovují.
         */
        fun minutesSinceAdjustment(): Int =
            ((System.currentTimeMillis() - adjustedAt) / 60_000L).toInt().coerceAtLeast(0)
    }

    data class Error(val message: String) : DeparturesUiState
}
