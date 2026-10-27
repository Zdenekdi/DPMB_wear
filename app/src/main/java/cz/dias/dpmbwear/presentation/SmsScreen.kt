package cz.dias.dpmbwear.presentation

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import cz.dias.dpmbwear.presentation.theme.LiveGreen
import kotlinx.coroutines.delay
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import cz.dias.dpmbwear.R
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cz.dias.dpmbwear.presentation.theme.AmbientOn

private const val SMS_NUMBER = "90206"
private const val PREFS_NAME = "dpmb_prefs"
private const val KEY_SMS_EXPIRY = "sms_expiry"

/** Minimální doporučený dotykový cíl na Wear OS. */
private val TouchTargetHeight = 48.dp

/**
 * Nabídka SMS jízdenek. Dřív to byly tři téměř identické 25řádkové bloky –
 * teď je to jeden seznam dat a jedna komponenta.
 */
private data class Ticket(
    val code: String,
    @StringRes val durationRes: Int,
    @StringRes val categoryRes: Int,
    val price: String,
    val validMinutes: Int,
)

private val Tickets = listOf(
    Ticket(code = "BRNO20", durationRes = R.string.sms_ticket_20, categoryRes = R.string.sms_category_basic, price = "20 Kč", validMinutes = 20),
    Ticket(code = "BRNO", durationRes = R.string.sms_ticket_75, categoryRes = R.string.sms_category_basic, price = "29 Kč", validMinutes = 75),
    Ticket(code = "BRNOD", durationRes = R.string.sms_ticket_day, categoryRes = R.string.sms_category_daily, price = "99 Kč", validMinutes = 1440),
)

@Composable
fun SmsScreen() {
    val context = LocalContext.current
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    val sharedPrefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var expiryTime by remember { mutableLongStateOf(sharedPrefs.getLong(KEY_SMS_EXPIRY, 0L)) }
    var currentTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val isAmbient = LocalAmbientState.current

    // Bez tohohle tikal odpočet každou sekundu i po zhasnutí displeje –
    // celou hodinu a čtvrt platnosti jízdenky.
    var isResumed by remember { mutableStateOf(true) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE -> isResumed = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // V ambientu se zobrazují jen minuty, takže sekundový tik nemá co dělat –
    // systém si o překreslení řekne sám přes LocalAmbientTick.
    val ambientTick = LocalAmbientTick.current
    LaunchedEffect(expiryTime, isResumed, isAmbient, ambientTick) {
        currentTime = System.currentTimeMillis()
        if (!isResumed || isAmbient) return@LaunchedEffect

        while (System.currentTimeMillis() < expiryTime) {
            currentTime = System.currentTimeMillis()
            delay(1000)
        }
        currentTime = System.currentTimeMillis()
    }

    fun buy(ticket: Ticket) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$SMS_NUMBER")
            putExtra("sms_body", ticket.code)
        }

        // Hodinky bez SIM nemusí mít žádnou SMS aplikaci – bez odchycení
        // by to spadlo na ActivityNotFoundException.
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            return
        }

        // Odpočet se nastavuje až po otevření SMS aplikace. Dřív se zapisoval
        // předem, takže když uživatel odeslání zrušil, aplikace mu i tak
        // odpočítávala platnost jízdenky, kterou nikdy nekoupil.
        //
        // Ani tohle není důkaz odeslání – ACTION_SENDTO výsledek nevrací.
        // Je to nejlepší dostupná aproximace.
        val newExpiry = System.currentTimeMillis() + ticket.validMinutes * 60_000L
        sharedPrefs.edit().putLong(KEY_SMS_EXPIRY, newExpiry).apply()
        expiryTime = newExpiry
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = contentPadding
        ) {
            item {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    TicketHeader(
                        remainingMillis = (expiryTime - currentTime).coerceAtLeast(0L),
                        isAmbient = isAmbient,
                    )
                }
            }

            // V ambientu se nákupní tlačítka nekreslí. Jsou stejně needitovatelná
            // a tři sytě červené plochy na always-on displeji jsou to poslední,
            // co má svítit.
            if (!isAmbient) {
                items(Tickets.size) { index ->
                    TicketButton(
                        ticket = Tickets[index],
                        onClick = { buy(Tickets[index]) },
                        modifier = Modifier.transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec)
                    )
                }
            }
        }
    }
}

@Composable
private fun TicketHeader(remainingMillis: Long, isAmbient: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.sms_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isAmbient) AmbientOn else MaterialTheme.colorScheme.onSurface
        )

        // Zbývající platnost je jediná informace, kterou revizor chce vidět –
        // dostává proto největší typografii na obrazovce.
        if (remainingMillis > 0) {
            val totalSeconds = remainingMillis / 1000
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isAmbient) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = LiveGreen,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    // V ambientu se sekundy nezobrazují – překreslení chodí
                    // jen jednou za minutu, takže by stejně ukazovaly nesmysl.
                    text = when {
                        isAmbient -> String.format("%d min", totalSeconds / 60)
                        totalSeconds >= 3600 -> String.format(
                            "%d:%02d:%02d",
                            totalSeconds / 3600,
                            (totalSeconds % 3600) / 60,
                            totalSeconds % 60,
                        )

                        else -> String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60)
                    },
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isAmbient) AmbientOn else LiveGreen
                )
            }
        }
    }
}

@Composable
private fun TicketButton(
    ticket: Ticket,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    transformation: SurfaceTransformation? = null,
) {
    Button(
        onClick = onClick,
        // Ne primaryDim – ta je shodná s barvou tramvajových linek a tlačítko
        // by vypadalo jako pilulka linky.
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .defaultMinSize(minHeight = TouchTargetHeight),
        transformation = transformation,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    text = stringResource(ticket.durationRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                // Odlišeno velikostí a řezem, ne barvou – světlejší růžová
                // by na červené měla jen 3,3:1.
                Text(
                    text = stringResource(ticket.categoryRes),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                text = ticket.price,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
