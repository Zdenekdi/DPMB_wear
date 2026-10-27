package cz.dias.dpmbwear.mobile

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Aktualizace jízdního řádu.
 *
 * Pravidelně jednou denně, jen na Wi-Fi a při nevybité baterii. Dotaz posílá
 * If-Modified-Since, takže když se nic nezměnilo, přenese se pár bajtů.
 * Data.brno.cz uvádí aktualizace jednou týdně.
 */
class TimetableUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val updated = TimetableStore.get(applicationContext).update(force = inputData.getBoolean(KEY_FORCE, false))
            Log.i(TAG, if (updated) "Jízdní řád aktualizován" else "Jízdní řád beze změny")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Aktualizace jízdního řádu selhala", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "TimetableUpdate"
        private const val KEY_FORCE = "force"
        private const val PERIODIC = "timetable_periodic"
        const val ONE_TIME = "timetable_now"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TimetableUpdateWorker>(1, TimeUnit.DAYS)
                // První stažení řeší runNow(); bez odkladu by se obě úlohy
                // spustily hned po instalaci zároveň.
                .setInitialDelay(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * Hned, na jakékoli síti. Při prvním spuštění bez jízdního řádu
         * nemá smysl čekat na Wi-Fi – hodinky by nic neukázaly.
         */
        fun runNow(context: Context, force: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<TimetableUpdateWorker>()
                .setInputData(workDataOf(KEY_FORCE to force))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // Ruční "stáhnout teď" (force) nahradí úlohu, která třeba ještě čeká na síť.
            val policy = if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, policy, request)
        }
    }
}
