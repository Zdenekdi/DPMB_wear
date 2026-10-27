package cz.dias.dpmbwear.data

import android.content.Context
import android.util.Base64
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.Wearable
import cz.dias.dpmbwear.shared.DataLayer
import cz.dias.dpmbwear.shared.DeparturesRequest
import cz.dias.dpmbwear.shared.PayloadCodec
import cz.dias.dpmbwear.shared.WatchPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Telefon s aplikací DPMB Wear není dostupný nebo neodpověděl včas. */
class PhoneUnavailableException(message: String) : IOException(message)

/**
 * Odpověď telefonu a okamžik, kdy dorazila (hodiny HODINEK).
 *
 * Časy odjezdů v payloadu jsou podle hodin telefonu. Minuty se proto počítají
 * od [WatchPayload.generatedAt] plus doba, která od příjmu uběhla na hodinkách
 * – rozdíl hodin mezi zařízeními tak nehraje roli.
 */
data class ReceivedPayload(val payload: WatchPayload, val receivedAtMs: Long) {

    /** Odhad aktuálního času telefonu v epoch sekundách. */
    fun phoneNow(watchNowMs: Long): Long = payload.generatedAt + (watchNowMs - receivedAtMs) / 1000

    fun ageMs(watchNowMs: Long): Long = watchNowMs - receivedAtMs
}

/**
 * Komunikace s telefonem přes Wearable Data Layer.
 *
 * Dotaz:   hodinky → telefon, MessageClient na /dpmb/request (malý JSON).
 * Odpověď: telefon → hodinky, DataClient na /dpmb/payload (hotový JSON).
 *
 * Proč odpověď přes DataItem a ne zprávou: DataItem Data Layer doručí i se
 * zpožděním po obnovení spojení a hodinky ho mají uložený lokálně.
 */
class PhoneBridge private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("dpmb_phone", Context.MODE_PRIVATE)

    @Volatile
    private var latest: ReceivedPayload? = null

    @Volatile
    private var cachedNode: Pair<String, Long>? = null

    /** Dotazy, na které se čeká: ID → (odpověď, požadovaná zastávka). */
    private class Pending(val answer: CompletableDeferred<ReceivedPayload>, val stopId: String?)

    private val pending = ConcurrentHashMap<String, Pending>()

    /**
     * Kdy byl který dotaz odeslán (hodiny hodinek). Drží se i po timeoutu, aby
     * šla správně zpracovat odpověď, která dorazí pozdě.
     */
    private val sentAt = ConcurrentHashMap<String, Long>()

    private val _updates = MutableSharedFlow<ReceivedPayload>(extraBufferCapacity = 4)

    /** Každá odpověď z telefonu, i ta, na kterou už nikdo nečeká (dorazila po timeoutu). */
    val updates: SharedFlow<ReceivedPayload> = _updates.asSharedFlow()

    /**
     * Jeden trvalý posluchač pro celý proces (PhoneBridge je singleton).
     * Kdyby se registroval jen po dobu dotazu, odpověď po timeoutu by
     * propadla a souběžné dotazy by tutéž událost zpracovaly víckrát.
     */
    private val listener = DataClient.OnDataChangedListener { events ->
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            if (event.dataItem.uri.path != DataLayer.PATH_PAYLOAD) continue
            // Buffer událostí se po návratu z callbacku uvolní – data se
            // musí přečíst hned tady.
            accept(event.dataItem.data ?: continue)
        }
    }

    init {
        Wearable.getDataClient(appContext).addListener(listener)
    }

    /**
     * Pošle dotaz a počká na odpověď.
     *
     * Celý dotaz (vyhledání telefonu, odeslání, odpověď) má společný limit
     * [TIMEOUT_MS], aby dlaždice ani komplikace nevisely na pomalém spojení.
     *
     * @param lat poloha HODINEK; null = telefon použije svou (jen best-effort,
     *   na pozadí ji Android telefonu často nedá)
     * @throws PhoneUnavailableException telefon není spárovaný, nemá aplikaci,
     *   je mimo dosah nebo neodpověděl včas
     */
    suspend fun request(lat: Double?, lon: Double?, stopId: String?, maxStops: Int = 3): ReceivedPayload {
        val request = DeparturesRequest(
            id = UUID.randomUUID().toString().take(8),
            lat = lat,
            lon = lon,
            stopId = stopId,
            maxStops = maxStops.coerceIn(1, 5),
        )
        val answer = CompletableDeferred<ReceivedPayload>()
        try {
            return withTimeoutOrNull(TIMEOUT_MS) {
                val node = phoneNode() ?: throw PhoneUnavailableException("Telefon s aplikací DPMB Wear není připojen")
                pruneSent()
                sentAt[request.id] = System.currentTimeMillis()
                pending[request.id] = Pending(answer, stopId)
                try {
                    Wearable.getMessageClient(appContext)
                        .sendMessage(node, DataLayer.PATH_REQUEST, PayloadCodec.encodeRequest(request))
                        .await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    cachedNode = null
                    throw PhoneUnavailableException("Dotaz se nepodařilo odeslat: ${e.message}")
                }
                answer.await()
            } ?: throw PhoneUnavailableException("Telefon neodpověděl")
        } finally {
            pending.remove(request.id)
        }
    }

    /** Poslední odpověď, pokud není starší než [maxAgeMs]. */
    fun recent(maxAgeMs: Long, nowMs: Long = System.currentTimeMillis()): ReceivedPayload? =
        lastKnown()?.takeIf { it.ageMs(nowMs) in 0..maxAgeMs }

    /** Poslední odpověď bez ohledu na stáří; přežije i restart aplikace. */
    fun lastKnown(): ReceivedPayload? {
        latest?.let { return it }
        val stored = prefs.getString(KEY_PAYLOAD, null) ?: return null
        val at = prefs.getLong(KEY_RECEIVED_AT, 0L)
        val payload = runCatching { PayloadCodec.decode(Base64.decode(stored, Base64.NO_WRAP)) }.getOrNull() ?: return null
        return ReceivedPayload(payload, at).also { latest = it }
    }

    private fun accept(bytes: ByteArray) {
        val payload = runCatching { PayloadCodec.decode(bytes) }.getOrNull() ?: return
        // Odpověď na dotaz, který jsme neposlali nebo je starý (DataItem
        // doručený po dlouhém odpojení, předchozí běh aplikace), se ignoruje:
        // nevíme, k jakému okamžiku platí.
        val sent = sentAt[payload.requestId] ?: return
        val now = System.currentTimeMillis()
        if (now - sent > MAX_ANSWER_AGE_MS) return

        // Čas odpovědi = čas ODESLÁNÍ dotazu, ne příjmu. Když se DataItem
        // zdrží (výpadek Bluetooth), minuty pak nevycházejí pozdější, než jsou
        // – v nejhorším o dobu výpočtu na telefonu dřívější. To je bezpečnější
        // směr: člověk přijde na zastávku dřív, ne pozdě.
        val received = ReceivedPayload(payload, sent)

        // Chybová odpověď (telefon nemá jízdní řád) nesmí přepsat poslední dobrá data.
        if (payload.error == null) {
            val current = latest
            if (current == null || received.receivedAtMs >= current.receivedAtMs) {
                latest = received
                prefs.edit()
                    .putString(KEY_PAYLOAD, Base64.encodeToString(bytes, Base64.NO_WRAP))
                    .putLong(KEY_RECEIVED_AT, received.receivedAtMs)
                    .apply()
            }
        }

        // Odpovědi jdou do jednoho DataItemu, takže při dvou dotazech těsně
        // po sobě může první odpověď přepsat druhá dřív, než se synchronizuje.
        // Proto se čekající dotaz na konkrétní zastávku uspokojí i odpovědí,
        // která tu zastávku obsahuje.
        for ((id, p) in pending) {
            val matches = id == payload.requestId ||
                (payload.error == null && p.stopId != null && payload.stop(p.stopId) != null)
            if (matches) p.answer.complete(received)
        }
        _updates.tryEmit(received)
    }

    private fun pruneSent() {
        val limit = System.currentTimeMillis() - MAX_ANSWER_AGE_MS
        sentAt.entries.removeIf { it.value < limit }
    }

    /** Spárovaný telefon s naší aplikací; přednost má ten v dosahu Bluetooth. */
    private suspend fun phoneNode(): String? {
        cachedNode?.let { (id, at) -> if (System.currentTimeMillis() - at < NODE_CACHE_MS) return id }
        val nodes = try {
            Wearable.getCapabilityClient(appContext)
                .getCapability(DataLayer.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: return null
        cachedNode = node.id to System.currentTimeMillis()
        return node.id
    }

    companion object {
        /** Telefon počítá pár ms; živá data stahuje nejvýš 4 s, pak odpoví bez nich. */
        const val TIMEOUT_MS = 8_000L

        /** Pozdní odpověď se ještě použije, je-li dotaz mladší než 2 min. */
        private const val MAX_ANSWER_AGE_MS = 120_000L
        private const val NODE_CACHE_MS = 60_000L
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_RECEIVED_AT = "received_at"

        @Volatile
        private var instance: PhoneBridge? = null

        fun get(context: Context): PhoneBridge =
            instance ?: synchronized(this) {
                instance ?: PhoneBridge(context).also { instance = it }
            }
    }
}
