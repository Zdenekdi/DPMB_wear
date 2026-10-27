package cz.dias.dpmbwear.mobile

import android.util.Log
import cz.dias.dpmbwear.shared.transit.FeedSnapshot
import cz.dias.dpmbwear.shared.transit.GtfsRealtimeDecoder
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Živá data (polohy vozidel) z GTFS-RT KORDIS JMK.
 *
 * Stahují se JEN na dotaz z hodinek, nikdy na pozadí. Výsledek se drží 15 s:
 * hodinky se ptají nejvýš jednou za 30 s, ale dlaždice, komplikace a
 * přepínání zastávek můžou přijít těsně po sobě.
 */
object RealtimeSource {

    private const val TAG = "RealtimeSource"
    private const val CACHE_S = 15L

    /** Při výpadku feedu se starší polohy ještě chvíli použijí, ale ne déle. */
    private const val STALE_LIMIT_S = 120L

    /**
     * Vlastní klient s krátkým limitem: hodinky čekají na odpověď 8 s. Když
     * KORDIS odpovídá pomalu, je lepší poslat odjezdy podle jízdního řádu
     * včas než živá data pozdě.
     */
    private val client: OkHttpClient by lazy {
        Http.client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
    }

    private var last: FeedSnapshot? = null
    private var lastFetchedAt = 0L

    /**
     * @return (feed, stáří v sekundách), nebo null, když živá data nejsou.
     *   Bez živých dat telefon odpoví podle jízdního řádu.
     */
    @Synchronized
    fun snapshot(now: Long): Pair<FeedSnapshot, Int>? {
        last?.let { if (now - lastFetchedAt < CACHE_S) return it to age(it, now) }
        return try {
            val request = Request.Builder().url(Http.GTFS_RT_URL).build()
            val bytes = client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("GTFS-RT: HTTP ${resp.code}")
                resp.body?.bytes() ?: throw IOException("GTFS-RT: prázdná odpověď")
            }
            val feed = GtfsRealtimeDecoder.decode(bytes)
            last = feed
            lastFetchedAt = now
            feed to age(feed, now)
        } catch (e: Exception) {
            Log.w(TAG, "Živá data nedostupná: ${e.message}")
            val old = last
            if (old != null && now - lastFetchedAt < STALE_LIMIT_S) old to age(old, now) else null
        }
    }

    /** Stáří podle časové značky feedu; když chybí, podle okamžiku stažení. */
    private fun age(feed: FeedSnapshot, now: Long): Int {
        val ts = if (feed.timestamp > 0) feed.timestamp else lastFetchedAt
        return (now - ts).coerceAtLeast(0).toInt()
    }
}
