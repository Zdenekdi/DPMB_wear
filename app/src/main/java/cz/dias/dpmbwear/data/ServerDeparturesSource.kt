package cz.dias.dpmbwear.data

import cz.dias.dpmbwear.data.model.Departure

/**
 * Odjezdy z vlastního serveru nad otevřenými daty KORDIS JMK.
 *
 * Aktivní, když je v local.properties vyplněné DPMB_SERVER_URL.
 */
class ServerDeparturesSource(
    private val api: DpmbServerApi = ServerClient.api,
) : DeparturesSource {

    override suspend fun getDepartures(stopId: String): List<Departure> =
        api.departures(stopId).departures
            .orEmpty()
            .mapNotNull { it.toDeparture() }
            .sortedBy { it.timeMinutes }

    companion object {

        /**
         * Převod odpovědi serveru na model aplikace.
         *
         * Vytažené jako čistá funkce kvůli testům. Řádek bez linky nebo bez
         * času se zahodí – odjezd bez času by na hodinkách vypadal jako "0 min".
         */
        fun ServerDeparture.toDeparture(): Departure? {
            val lineName = this.line?.trim().orEmpty()
            val mins = this.minutes ?: return null
            if (lineName.isEmpty()) return null
            val live = this.isLive == true

            return Departure(
                line = lineName,
                destination = this.destination?.trim().orEmpty(),
                timeMinutes = mins.coerceAtLeast(0),
                isLive = live,
                // Zpoždění bez živých dat nedává smysl; server ho v tom případě
                // neposílá, ale jistota je jistota.
                delayMinutes = if (live) this.delayMinutes else null,
            )
        }
    }
}
