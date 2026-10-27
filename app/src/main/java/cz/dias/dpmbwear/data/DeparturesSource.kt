package cz.dias.dpmbwear.data

import cz.dias.dpmbwear.data.model.Departure

/**
 * Zdroj odjezdů.
 *
 * Existuje proto, aby šlo měnit odkud data jdou beze změny zbytku aplikace:
 * [ServerDeparturesSource] (vlastní server nad otevřenými daty),
 * [IdsJmkDeparturesSource] (API KORDIS s klíčem) nebo [FakeDeparturesSource].
 * Volba probíhá v [DeparturesRepository.defaultSource] podle local.properties.
 */
interface DeparturesSource {

    /** Vrátí odjezdy pro zastávku, nebo vyhodí výjimku při selhání sítě. */
    suspend fun getDepartures(stopId: String): List<Departure>
}
