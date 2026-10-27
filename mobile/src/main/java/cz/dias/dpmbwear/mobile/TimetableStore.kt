package cz.dias.dpmbwear.mobile

import android.content.Context
import android.util.Log
import cz.dias.dpmbwear.shared.transit.GtfsCompiler
import cz.dias.dpmbwear.shared.transit.Timetable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Jízdní řád na telefonu.
 *
 * GTFS zip (desítky MB) se stáhne jednou za čas a převede na binární soubor,
 * který se při dotazu z hodinek jen namapuje do paměti. Dotaz tak nic
 * neparsuje a odpověď je hotová za pár milisekund.
 */
class TimetableStore private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "timetable.bin")
    private val prefs = appContext.getSharedPreferences("timetable", Context.MODE_PRIVATE)

    @Volatile
    private var cached: Timetable? = null

    /** Verze jízdního řádu (Last-Modified ze serveru KORDIS), nebo null. */
    val version: String? get() = prefs.getString(KEY_VERSION, null)

    /** Kdy se naposledy kontrolovalo, jestli je nový jízdní řád; epoch ms. */
    val lastCheckMs: Long get() = prefs.getLong(KEY_LAST_CHECK, 0L)

    val sizeBytes: Long get() = if (file.exists()) file.length() else 0L

    /** Načtený jízdní řád, nebo null, když ještě není stažený. */
    fun timetable(): Timetable? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            if (!file.exists()) return null
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    // Mapování zůstává platné i po zavření kanálu.
                    val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                    Timetable.read(buf)
                }.also { cached = it }
            } catch (e: Exception) {
                // Poškozený soubor (přerušený zápis, starší formát): smazat
                // a při příští aktualizaci stáhnout znovu.
                Log.w(TAG, "Jízdní řád nejde načíst, mažu ho", e)
                file.delete()
                prefs.edit().remove(KEY_LAST_MODIFIED).remove(KEY_VERSION).apply()
                null
            }
        }
    }

    /**
     * Stáhne a převede jízdní řád, pokud se na serveru změnil.
     * Blokující – volat jen z workeru.
     *
     * @return true = nainstalován nový jízdní řád
     */
    fun update(client: OkHttpClient = Http.client, force: Boolean = false): Boolean = synchronized(UPDATE_LOCK) {
        // Pravidelná a okamžitá aktualizace jsou různé úlohy WorkManageru a
        // můžou běžet zároveň. Dva převody naráz by si přepisovaly soubory
        // a každý potřebuje ~200 MB paměti. Druhý počká a díky
        // If-Modified-Since pak skončí odpovědí 304.
        updateLocked(client, force)
    }

    private fun updateLocked(client: OkHttpClient, force: Boolean): Boolean {
        val builder = Request.Builder().url(Http.GTFS_URL)
        if (!force && file.exists()) {
            prefs.getString(KEY_LAST_MODIFIED, null)?.let { builder.header("If-Modified-Since", it) }
        }
        client.newCall(builder.build()).execute().use { resp ->
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            if (resp.code == 304) return false
            if (!resp.isSuccessful) throw IOException("Jízdní řád: HTTP ${resp.code}")

            val zip = File.createTempFile("gtfs", ".zip", appContext.cacheDir)
            val part = File.createTempFile("timetable", ".part", appContext.filesDir)
            try {
                resp.body?.byteStream()?.use { input -> zip.outputStream().use { input.copyTo(it) } }
                    ?: throw IOException("Jízdní řád: prázdná odpověď")
                val lastModified = resp.header("Last-Modified")
                val version = lastModified ?: "staženo ${System.currentTimeMillis()}"

                val started = System.currentTimeMillis()
                part.outputStream().use { GtfsCompiler.compile(zip, it, version) }
                Log.i(TAG, "Jízdní řád převeden za ${System.currentTimeMillis() - started} ms, ${part.length() / 1_000_000} MB")

                // Ověření před nahrazením: rozbitý soubor nesmí přepsat funkční.
                RandomAccessFile(part, "r").use { raf ->
                    Timetable.read(raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length()))
                }

                synchronized(this) {
                    // Přejmenování je atomické; staré mapování zůstává platné,
                    // dokud ho nikdo nepoužívá.
                    if (!part.renameTo(file)) throw IOException("Nelze nahradit jízdní řád")
                    cached = null
                }
                prefs.edit()
                    .putString(KEY_LAST_MODIFIED, lastModified)
                    .putString(KEY_VERSION, version)
                    .apply()
                return true
            } finally {
                zip.delete()
                part.delete()
            }
        }
    }

    companion object {
        private const val TAG = "TimetableStore"
        private const val KEY_LAST_MODIFIED = "last_modified"
        private const val KEY_VERSION = "version"
        private const val KEY_LAST_CHECK = "last_check"
        private val UPDATE_LOCK = Any()

        @Volatile
        private var instance: TimetableStore? = null

        fun get(context: Context): TimetableStore =
            instance ?: synchronized(this) {
                instance ?: TimetableStore(context).also { instance = it }
            }
    }
}
