package cz.dias.dpmbwear.data

import cz.dias.dpmbwear.data.model.Departure

/**
 * Zdroj odjezdů.
 *
 * Zdroje se zkoušejí v pořadí z [DeparturesRepository.defaultSources]:
 * [PhoneDeparturesSource] (telefon jako proxy, hlavní cesta),
 * [ServerDeparturesSource] nebo [IdsJmkDeparturesSource] (podle
 * local.properties) a nakonec [FakeDeparturesSource].
 */
interface DeparturesSource {

    /** Vrátí odjezdy pro zastávku, nebo vyhodí výjimku při selhání sítě. */
    suspend fun getDepartures(stopId: String): List<Departure>

    /** True = ukázková data; UI to musí přiznat a nesmí se ukládat do cache. */
    val isDemo: Boolean get() = false
}
