package cz.dias.dpmbwear.data

import cz.dias.dpmbwear.data.model.Departure
import cz.dias.dpmbwear.shared.PayloadError
import cz.dias.dpmbwear.shared.PayloadStop

/**
 * Odjezdy z telefonu (hlavní zdroj).
 *
 * Telefon v jedné odpovědi pošle až tři nejbližší zastávky. Přepnutí na další
 * zastávku proto obvykle nejde přes Bluetooth znovu – vezme se z odpovědi,
 * která je mladší než [REUSE_MS].
 */
class PhoneDeparturesSource(private val bridge: PhoneBridge) : DeparturesSource {

    override suspend fun getDepartures(stopId: String): List<Departure> {
        val received = bridge.recent(REUSE_MS)?.takeIf { it.payload.stop(stopId) != null }
            ?: bridge.request(lat = null, lon = null, stopId = stopId)

        when (received.payload.error) {
            null -> Unit
            // Telefon ještě stahuje jízdní řád: pro hodinky je to totéž jako
            // nedostupný telefon – použije se cache nebo záložní zdroj.
            PayloadError.NO_TIMETABLE -> throw PhoneUnavailableException("Telefon ještě stahuje jízdní řád")
            else -> throw PhoneUnavailableException("Telefon hlásí chybu ${received.payload.error}")
        }
        val stop = received.payload.stop(stopId)
            ?: throw PhoneUnavailableException("Telefon zastávku $stopId nezná")
        return toDepartures(received, stop, System.currentTimeMillis())
    }

    companion object {
        /** Automatické obnovení je po 30 s; odpověď do 20 s se použije znovu. */
        const val REUSE_MS = 20_000L

        /**
         * Převod na model aplikace. Minuty se počítají podle hodin telefonu
         * (viz [ReceivedPayload.phoneNow]); odjeté spoje se zahodí.
         */
        fun toDepartures(received: ReceivedPayload, stop: PayloadStop, watchNowMs: Long): List<Departure> {
            val now = received.phoneNow(watchNowMs)
            return stop.departures
                .map { d ->
                    Departure(
                        line = d.line,
                        destination = d.destination,
                        timeMinutes = Math.floorDiv(d.epoch - now, 60L).toInt(),
                        isLive = d.delayMin != null,
                        delayMinutes = d.delayMin,
                    )
                }
                .filter { it.timeMinutes >= 0 }
        }
    }
}
