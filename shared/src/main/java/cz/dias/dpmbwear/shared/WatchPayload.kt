package cz.dias.dpmbwear.shared

/**
 * Zprávy mezi telefonem a hodinkami.
 *
 * Telefon je jediný, kdo mluví s KORDIS JMK. Hodinky pošlou malý dotaz
 * ([DeparturesRequest]) a dostanou hotovou odpověď ([WatchPayload]) přes
 * Wearable Data Layer.
 *
 * Návrh payloadu:
 *  - Pole místo objektů: klíče se neopakují u každého odjezdu.
 *  - Časy odjezdu jako ABSOLUTNÍ epoch sekundy, ne "za 3 min". Hodinky si
 *    minuty přepočítávají samy, takže uložená odpověď zůstává pravdivá
 *    i po deseti minutách bez telefonu – jen ubývají odjeté spoje.
 *  - Žádná nástupiště, barvy ani dlouhé názvy linek: hodinky je neukazují.
 *    Barvu si hodinky odvodí z čísla linky.
 *
 * Tři zastávky po osmi odjezdech mají kolem 1 kB, což je pár paketů BLE.
 * Komprese se při téhle velikosti nevyplatí.
 */
data class DeparturesRequest(
    /** Náhodné ID; odpověď ho vrací, aby hodinky poznaly svou odpověď. */
    val id: String,
    val lat: Double? = null,
    val lon: Double? = null,
    /** Konkrétní zastávka (oblíbená, dlaždice). Pošle se jako první. */
    val stopId: String? = null,
    val maxStops: Int = 3,
    val perStop: Int = 8,
)

data class WatchPayload(
    /** ID dotazu, na který odpověď odpovídá. */
    val requestId: String,
    /** Kdy telefon odpověď sestavil, epoch sekundy podle hodin TELEFONU. */
    val generatedAt: Long,
    /** Stáří živých dat v sekundách; -1 = živá data nejsou, platí jízdní řád. */
    val realtimeAgeS: Int,
    /** Null = v pořádku. Jinak jeden z [PayloadError]. */
    val error: String? = null,
    val stops: List<PayloadStop> = emptyList(),
) {
    fun stop(id: String): PayloadStop? = stops.firstOrNull { it.id == id }
}

object PayloadError {
    /** Telefon ještě nemá stažený jízdní řád. */
    const val NO_TIMETABLE = "no_timetable"

    /** Požadovaná zastávka v jízdním řádu není a nebyla zadaná poloha. */
    const val STOP_NOT_FOUND = "stop_not_found"

    /** Chyba na telefonu (výjimka při výpočtu). Podrobnosti jsou v logu telefonu. */
    const val INTERNAL = "internal"
}

data class PayloadStop(
    /** Skupina nástupišť, např. "U100" (všechna nástupiště České). */
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    /** Vzdálenost od polohy v dotazu; -1 = neznámá. */
    val distanceM: Int,
    val departures: List<PayloadDeparture>,
)

data class PayloadDeparture(
    val line: String,
    val destination: String,
    /** Očekávaný odjezd (jízdní řád + kladné zpoždění), epoch sekundy. */
    val epoch: Long,
    /**
     * Zpoždění v minutách. Null = o spoji nejsou živá data. Je to ODHAD
     * z polohy vozidla – feed KORDISu zpoždění neobsahuje.
     */
    val delayMin: Int?,
)

/** Cesty v Data Layer a schopnost, kterou se telefon hlásí. */
object DataLayer {
    /** Hodinky → telefon (MessageClient). */
    const val PATH_REQUEST = "/dpmb/request"

    /** Telefon → hodinky (DataClient). DataItem se na hodinkách uchovává i offline. */
    const val PATH_PAYLOAD = "/dpmb/payload"

    /** Musí sedět s res/values/wear.xml v aplikaci pro telefon. */
    const val CAPABILITY_PHONE = "dpmb_phone_proxy"
}

/** Převod zpráv na JSON a zpět. Bez závislostí, aby šel otestovat i mimo Android. */
object PayloadCodec {

    private const val VERSION = 1

    // ---------- dotaz ----------

    fun encodeRequest(r: DeparturesRequest): ByteArray = Json.Writer().run {
        obj {
            field("v") { num(VERSION) }
            field("id") { str(r.id) }
            r.lat?.let { field("lat") { num(it, 5) } }
            r.lon?.let { field("lon") { num(it, 5) } }
            r.stopId?.let { field("stop") { str(it) } }
            field("n") { num(r.maxStops) }
            field("d") { num(r.perStop) }
        }
        bytes()
    }

    fun decodeRequest(bytes: ByteArray): DeparturesRequest {
        val o = Json.parse(bytes.decodeToString()) as? Map<*, *> ?: error("Dotaz není objekt")
        return DeparturesRequest(
            id = o["id"] as? String ?: "",
            lat = (o["lat"] as? Number)?.toDouble(),
            lon = (o["lon"] as? Number)?.toDouble(),
            stopId = o["stop"] as? String,
            maxStops = ((o["n"] as? Number)?.toInt() ?: 3).coerceIn(1, 5),
            perStop = ((o["d"] as? Number)?.toInt() ?: 8).coerceIn(1, 20),
        )
    }

    // ---------- odpověď ----------
    //
    // {"v":1,"id":"a1b2","t":1793170000,"rt":12,
    //  "s":[["U100","Česká",49.19612,16.60621,45,[["1","Ečerova",1793170120,2],["6","Kr. Pole",1793170300,null]]]]}

    fun encode(p: WatchPayload): ByteArray = Json.Writer().run {
        obj {
            field("v") { num(VERSION) }
            field("id") { str(p.requestId) }
            field("t") { num(p.generatedAt) }
            field("rt") { num(p.realtimeAgeS) }
            p.error?.let { field("e") { str(it) } }
            field("s") {
                arr(p.stops) { s ->
                    arr {
                        str(s.id); str(s.name); num(s.lat, 5); num(s.lon, 5); num(s.distanceM)
                        arr(s.departures) { d ->
                            arr {
                                str(d.line); str(d.destination); num(d.epoch)
                                if (d.delayMin == null) nul() else num(d.delayMin)
                            }
                        }
                    }
                }
            }
        }
        bytes()
    }

    fun decode(bytes: ByteArray): WatchPayload {
        val o = Json.parse(bytes.decodeToString()) as? Map<*, *> ?: error("Payload není objekt")
        val version = (o["v"] as? Number)?.toInt() ?: 0
        require(version == VERSION) { "Nepodporovaná verze payloadu $version" }
        val stops = (o["s"] as? List<*>).orEmpty().mapNotNull { raw ->
            val s = raw as? List<*> ?: return@mapNotNull null
            if (s.size < 6) return@mapNotNull null
            PayloadStop(
                id = s[0] as? String ?: return@mapNotNull null,
                name = s[1] as? String ?: "",
                lat = (s[2] as? Number)?.toDouble() ?: 0.0,
                lon = (s[3] as? Number)?.toDouble() ?: 0.0,
                distanceM = (s[4] as? Number)?.toInt() ?: -1,
                departures = (s[5] as? List<*>).orEmpty().mapNotNull { rawDep ->
                    val d = rawDep as? List<*> ?: return@mapNotNull null
                    if (d.size < 4) return@mapNotNull null
                    PayloadDeparture(
                        line = d[0] as? String ?: return@mapNotNull null,
                        destination = d[1] as? String ?: "",
                        epoch = (d[2] as? Number)?.toLong() ?: return@mapNotNull null,
                        delayMin = (d[3] as? Number)?.toInt(),
                    )
                },
            )
        }
        return WatchPayload(
            requestId = o["id"] as? String ?: "",
            generatedAt = (o["t"] as? Number)?.toLong() ?: 0L,
            realtimeAgeS = (o["rt"] as? Number)?.toInt() ?: -1,
            error = o["e"] as? String,
            stops = stops,
        )
    }
}
