package cz.dias.dpmbwear.complication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import cz.dias.dpmbwear.R
import cz.dias.dpmbwear.data.DeparturesRepository
import cz.dias.dpmbwear.data.StopsRepository
import cz.dias.dpmbwear.presentation.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Komplikace na ciferník s nejbližším odjezdem.
 *
 * Na hodinkách je tohle nejcennější místo v celé aplikaci – uživatel vidí
 * "1 · 3m" bez jediného doteku. Systém si o data říká sám a šetří tím, jak
 * často se ptá, takže se tu nikdy nesmí vynucovat čerstvé načtení navíc.
 */
class NextDepartureComplicationService : SuspendingComplicationDataSourceService() {

    private val repository by lazy { DeparturesRepository(applicationContext) }
    private val stops by lazy { StopsRepository(applicationContext) }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.SHORT_TEXT) return null
        return shortText(
            title = "1",
            text = "3m",
            description = getString(R.string.complication_preview),
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null

        // Nejdřív cache. Ciferník se překresluje často a vytáhnout kvůli tomu
        // rádio pokaždé by bylo znát na baterii.
        //
        // takeIf { isNotEmpty() } je podstatné: cache po sobě odfiltruje spoje,
        // které už odjely, takže vrací neprázdný výsledek s prázdným seznamem.
        // Bez té podmínky by elvis nikdy nesepnul a komplikace by až půl hodiny
        // ukazovala "--" místo toho, aby zkusila síť.
        //
        // Repository sahá na SharedPreferences a Gson; onComplicationRequest
        // běží na hlavním vlákně, proto withContext.
        //
        // I výběr zastávky jde do IO: první čtení SharedPreferences a sestavení
        // síťového klienta nemají na hlavním vlákně co dělat.
        val departures = withContext(Dispatchers.IO) {
            val stopId = favouriteStopId() ?: return@withContext null
            repository.cached(stopId)?.departures?.takeIf { it.isNotEmpty() }
                ?: try {
                    repository.load(stopId).departures
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
        }

        val next = departures?.firstOrNull() ?: return shortText(
            title = null,
            text = getString(R.string.complication_no_data),
            description = getString(R.string.complication_unknown),
        )

        return shortText(
            title = next.line,
            text = "${next.timeMinutes}m",
            description = getString(R.string.complication_next, next.line, next.timeMinutes),
        )
    }

    /** Oblíbená zastávka, jinak poslední zobrazená. Null = aplikace ještě nebyla otevřena. */
    private fun favouriteStopId(): String? = stops.preferredStop(
        getSharedPreferences("dpmb_prefs", Context.MODE_PRIVATE)
            .getStringSet("favorite_stops", emptySet())
            .orEmpty()
    )?.id

    private fun shortText(title: String?, text: String, description: String): ComplicationData =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(text).build(),
            contentDescription = PlainComplicationText.Builder(description).build(),
        )
            .apply { title?.let { setTitle(PlainComplicationText.Builder(it).build()) } }
            .setTapAction(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
