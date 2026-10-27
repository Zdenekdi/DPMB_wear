package cz.dias.dpmbwear.data.model

/**
 * Zastávka. Zatím ze statického seznamu, po napojení na GTFS z stops.txt.
 */
data class Stop(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
)

/**
 * Jeden odjezd.
 *
 * Záměrně bez barvy – ta je věc vykreslení, ne dat. Datová vrstva tak nemusí
 * znát Compose a stejný model použije obrazovka, dlaždice i komplikace.
 * Barvu si každá z nich odvodí z [line] přes LineColors.forLine.
 */
data class Departure(
    val line: String,
    val destination: String,
    val timeMinutes: Int,
    val isLive: Boolean,
)

/**
 * Výsledek načtení odjezdů.
 *
 * @param fetchedAt kdy data přišla ze sítě. Slouží k popisku "před X min".
 * @param adjustedAt okamžik, ke kterému platí [Departure.timeMinutes].
 *   U čerstvých dat je shodný s [fetchedAt], u dat z cache je to teď —
 *   repository už minuty o stáří posunula. Kdo chce minuty přepočítat znovu
 *   (třeba obrazovka v ambientu), musí počítat od tohoto času, jinak by
 *   odečetl stáří cache podruhé.
 * @param isStale true = síť selhala a tohle je poslední známý stav z cache.
 *   UI to musí uživateli přiznat, jinak by na hodinkách koukal na odjezdy,
 *   které už dávno odjely.
 */
data class DeparturesResult(
    val stopId: String,
    val departures: List<Departure>,
    val fetchedAt: Long,
    val adjustedAt: Long,
    val isStale: Boolean,
)
