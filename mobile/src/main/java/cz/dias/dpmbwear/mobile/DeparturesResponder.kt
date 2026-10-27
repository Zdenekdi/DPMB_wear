package cz.dias.dpmbwear.mobile

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import cz.dias.dpmbwear.shared.DeparturesRequest
import cz.dias.dpmbwear.shared.PayloadError
import cz.dias.dpmbwear.shared.WatchPayload
import cz.dias.dpmbwear.shared.transit.DepartureBoard
import java.util.concurrent.TimeUnit

/**
 * Odpověď na dotaz z hodinek: jízdní řád + živá data → hotový payload.
 *
 * Blokující (síť, poloha) – volat mimo hlavní vlákno.
 */
class DeparturesResponder(context: Context) {

    private val appContext = context.applicationContext
    private val store = TimetableStore.get(appContext)

    fun answer(request: DeparturesRequest): WatchPayload {
        val now = System.currentTimeMillis() / 1000
        val tt = store.timetable()
        if (tt == null) {
            // Úplně první spuštění: jízdní řád se teprve stahuje. Hodinky
            // ukážou poslední známá data nebo hlášku.
            TimetableUpdateWorker.runNow(appContext)
            return WatchPayload(request.id, now, -1, PayloadError.NO_TIMETABLE)
        }

        // Hodinky polohu poslat nemusí (oprávnění, úspora baterie). Telefon je
        // u uživatele, takže jeho poloha poslouží stejně dobře – ale jen
        // best-effort: na pozadí ji Android 10+ bez ACCESS_BACKGROUND_LOCATION
        // obvykle nevydá. Hlavní cesta je poloha z hodinek.
        val req = if (request.lat == null || request.lon == null) {
            phoneLocation()?.let { request.copy(lat = it.latitude, lon = it.longitude) } ?: request
        } else {
            request
        }

        val live = RealtimeSource.snapshot(now)
        return DepartureBoard.answer(tt, req, now, live?.first, live?.second ?: 0)
    }

    @SuppressLint("MissingPermission")
    private fun phoneLocation(): Location? {
        val granted = appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        return try {
            Tasks.await(LocationServices.getFusedLocationProviderClient(appContext).lastLocation, 2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w("DeparturesResponder", "Poloha telefonu nedostupná: ${e.message}")
            null
        }
    }
}
