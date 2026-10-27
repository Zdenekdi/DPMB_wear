package cz.dias.dpmbwear.mobile

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import cz.dias.dpmbwear.shared.DataLayer
import cz.dias.dpmbwear.shared.PayloadCodec
import cz.dias.dpmbwear.shared.PayloadError
import cz.dias.dpmbwear.shared.WatchPayload
import java.util.concurrent.TimeUnit

/**
 * Přijímá dotazy z hodinek a posílá odpovědi.
 *
 * Systém službu spustí sám, když z hodinek přijde zpráva na /dpmb/request –
 * aplikace na telefonu nemusí běžet. Callbacky WearableListenerService běží
 * na vlákně na pozadí, takže tu lze blokovat (síť, poloha).
 *
 * Odpověď jde přes DataClient, ne MessageClient: DataItem si hodinky
 * uchovají i po odpojení a Data Layer ho doručí, jakmile se spojení obnoví.
 */
class PhoneListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != DataLayer.PATH_REQUEST) return
        val request = try {
            PayloadCodec.decodeRequest(event.data)
        } catch (e: Exception) {
            Log.w(TAG, "Nečitelný dotaz z hodinek", e)
            return
        }

        val payload = try {
            DeparturesResponder(this).answer(request)
        } catch (e: Exception) {
            Log.e(TAG, "Výpočet odjezdů selhal", e)
            WatchPayload(request.id, System.currentTimeMillis() / 1000, -1, PayloadError.INTERNAL)
        }
        publish(this, payload)
    }

    companion object {
        private const val TAG = "PhoneListenerService"

        fun publish(context: Context, payload: WatchPayload) {
            val bytes = PayloadCodec.encode(payload)
            val request = PutDataRequest.create(DataLayer.PATH_PAYLOAD)
                .setData(bytes)
                // Bez setUrgent může Data Layer doručení odložit až o desítky
                // minut kvůli úspoře baterie. Tady na odpověď čeká člověk.
                .setUrgent()
            try {
                Tasks.await(Wearable.getDataClient(context).putDataItem(request), 5, TimeUnit.SECONDS)
                Log.d(TAG, "Odesláno ${bytes.size} B, ${payload.stops.size} zastávek")
            } catch (e: Exception) {
                Log.w(TAG, "Odeslání do hodinek selhalo: ${e.message}")
            }
        }
    }
}
