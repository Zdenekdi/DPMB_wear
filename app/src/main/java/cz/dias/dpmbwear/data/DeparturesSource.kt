package cz.dias.dpmbwear.data

import cz.dias.dpmbwear.data.model.Departure

/**
 * Zdroj odjezdů.
 *
 * Existuje proto, aby se dal po podpisu smlouvy s KORDISem vyměnit
 * [FakeDeparturesSource] za [IdsJmkDeparturesSource] beze změny zbytku
 * aplikace. Volba probíhá v [DeparturesRepository] podle toho, jestli je
 * vyplněný přístupový klíč.
 */
interface DeparturesSource {

    /** Vrátí odjezdy pro zastávku, nebo vyhodí výjimku při selhání sítě. */
    suspend fun getDepartures(stopId: String): List<Departure>
}
