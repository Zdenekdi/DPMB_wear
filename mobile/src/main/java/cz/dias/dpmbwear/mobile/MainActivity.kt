package cz.dias.dpmbwear.mobile

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cz.dias.dpmbwear.shared.DeparturesRequest
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Jednoduchá stavová obrazovka. Telefonní aplikace je hlavně služba na
 * pozadí; tady jde jen vidět, jestli má jízdní řád, a ručně ho stáhnout.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var output: TextView
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refreshStatus()
            main.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this)
        output = TextView(this).apply { setTextIsSelectable(true) }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(status)
            addView(Button(this@MainActivity).apply {
                setText(R.string.update_now)
                setOnClickListener { TimetableUpdateWorker.runNow(this@MainActivity, force = true); refreshStatus() }
            })
            addView(Button(this@MainActivity).apply {
                setText(R.string.test_nearby)
                setOnClickListener { testNearby() }
            })
            addView(output)
        }
        // Android 15+ vynucuje kreslení pod stavovou lištu; bez tohohle by
        // první řádek stavu zmizel pod ní.
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(layout)
        })

        TimetableUpdateWorker.schedule(this)
        // Načtení jízdního řádu parsuje tabulky zastávek a spojů – ne na hlavním vlákně.
        io.execute {
            if (TimetableStore.get(this).timetable() == null) TimetableUpdateWorker.runNow(this)
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        main.post(poll)
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun refreshStatus() {
        io.execute {
            val store = TimetableStore.get(this)
            val tt = store.timetable()
            val work = runCatching {
                WorkManager.getInstance(this).getWorkInfosForUniqueWork(TimetableUpdateWorker.ONE_TIME).get()
            }.getOrNull().orEmpty()
            val running = work.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
            val text = buildString {
                if (tt == null) {
                    appendLine("Jízdní řád: zatím není stažený")
                } else {
                    appendLine("Jízdní řád: ${tt.groupCount} zastávek, ${tt.tripCount} spojů, ${store.sizeBytes / 1_000_000} MB")
                    appendLine("Verze: ${store.version}")
                }
                if (store.lastCheckMs > 0) {
                    appendLine("Kontrola: ${DateUtils.getRelativeTimeSpanString(store.lastCheckMs)}")
                }
                if (running) appendLine("Stahuji a převádím jízdní řád…")
                appendLine()
                append("Data: KORDIS JMK (CC BY 4.0). Zpoždění je odhad z polohy vozidla.")
            }
            main.post { status.text = text }
        }
    }

    /** Stejný výpočet, jaký dostanou hodinky – pro kontrolu přímo v telefonu. */
    private fun testNearby() {
        output.text = "Počítám…"
        io.execute {
            val text = try {
                val p = DeparturesResponder(this).answer(DeparturesRequest(id = UUID.randomUUID().toString().take(8)))
                val now = System.currentTimeMillis() / 1000
                buildString {
                    p.error?.let { appendLine("Chyba: $it") }
                    appendLine(if (p.realtimeAgeS >= 0) "Živá data: ${p.realtimeAgeS} s stará" else "Živá data: nejsou, platí jízdní řád")
                    p.stops.forEach { s ->
                        appendLine()
                        appendLine("${s.name} (${s.id}) – ${s.distanceM} m")
                        s.departures.forEach { d ->
                            val min = ((d.epoch - now) / 60).coerceAtLeast(0)
                            val delay = d.delayMin?.let { if (it > 0) " +$it" else " ✓" }.orEmpty()
                            appendLine("  ${d.line.padEnd(4)} ${d.destination.take(24).padEnd(24)} $min min$delay")
                        }
                    }
                    appendLine()
                    append("Payload pro hodinky: ${cz.dias.dpmbwear.shared.PayloadCodec.encode(p).size} B")
                }
            } catch (e: Exception) {
                "Chyba: ${e.message}"
            }
            main.post { output.text = text }
        }
    }
}
