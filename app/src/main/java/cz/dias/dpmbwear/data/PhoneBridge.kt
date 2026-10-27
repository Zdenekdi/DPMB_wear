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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

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

    private val _updates = MutableSharedFlow<ReceivedPayload>(extraBufferCapacity = 4)

    /** Každá odpověď z telefonu, i ta, na kterou už nikdo nečeká (dorazila po timeoutu). */
    val updates: SharedFlow<ReceivedPayload> = _updates.asSharedFlow()

    /**
     * Pošle dotaz a počká na odpověď.
     *
     * @throws PhoneUnavailableException telefon není spárovaný, nemá aplikaci,
     *   je mimo dosah nebo neodpověděl do [TIMEOUT_MS]
     */
    suspend fun request(lat: Double?, lon: Double?, stopId: String?): ReceivedPayload {
        val node = phoneNode() ?: throw PhoneUnavailableException("Telefon s aplikací DPMB Wear není připojen")
        val request = DeparturesRequest(
            id = UUID.randomUUID().toString().take(8),
            lat = lat,
            lon = lon,
            stopId = stopId,
        )

        val answer = CompletableDeferred<ReceivedPayload>()
        val listener = DataClient.OnDataChangedListener { events ->
            for (event in events) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                if (event.dataItem.uri.path != DataLayer.PATH_PAYLOAD) continue
                // Buffer událostí se po návratu z callbacku uvolní – data se
                // musí přečíst hned tady.
                val received = accept(event.dataItem.data ?: continue) ?: continue
                if (received.payload.requestId == request.id) answer.complete(received)
            }
        }

        val dataClient = Wearable.getDataClient(appContext)
        // Listener se registruje PŘED odesláním, jinak by rychlá odpověď mohla proletět.
        dataClient.addListener(listener).await()
        try {
            try {
                Wearable.getMessageClient(appContext)
                    .sendMessage(node, DataLayer.PATH_REQUEST, PayloadCodec.encodeRequest(request))
                    .await()
            } catch (e: Exception) {
                cachedNode = null
                throw PhoneUnavailableException("Dotaz se nepodařilo odeslat: ${e.message}")
            }
            return withTimeoutOrNull(TIMEOUT_MS) { answer.await() }
                ?: throw PhoneUnavailableException("Telefon neodpověděl")
        } finally {
            dataClient.removeListener(listener)
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

    private fun accept(bytes: ByteArray): ReceivedPayload? {
        val payload = runCatching { PayloadCodec.decode(bytes) }.getOrNull() ?: return null
        val received = ReceivedPayload(payload, System.currentTimeMillis())
        // Chybová odpověď (telefon nemá jízdní řád) nesmí přepsat poslední dobrá data.
        if (payload.error == null) {
            latest = received
            prefs.edit()
                .putString(KEY_PAYLOAD, Base64.encodeToString(bytes, Base64.NO_WRAP))
                .putLong(KEY_RECEIVED_AT, received.receivedAtMs)
                .apply()
        }
        _updates.tryEmit(received)
        return received
    }

    /** Spárovaný telefon s naší aplikací; přednost má ten v dosahu Bluetooth. */
    private suspend fun phoneNode(): String? {
        cachedNode?.let { (id, at) -> if (System.currentTimeMillis() - at < NODE_CACHE_MS) return id }
        val nodes = try {
            Wearable.getCapabilityClient(appContext)
                .getCapability(DataLayer.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
        } catch (e: Exception) {
            return null
        }
        val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: return null
        cachedNode = node.id to System.currentTimeMillis()
        return node.id
    }

    companion object {
        /** Telefon počítá pár ms, ale stažení živých dat přes mobilní síť trvá. */
        const val TIMEOUT_MS = 8_000L
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
