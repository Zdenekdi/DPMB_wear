package cz.dias.dpmbwear.presentation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.compose.ui.res.stringResource
import cz.dias.dpmbwear.R
import cz.dias.dpmbwear.presentation.theme.AmbientDim

private const val PREFS_NAME = "dpmb_prefs"
private const val KEY_QR_CONTENT = "qr_content"

/** Podíl šířky displeje, který zabere QR kód i s bílým okrajem. */
private const val QR_WIDTH_FRACTION = 0.66f

/** Minimální doporučený dotykový cíl na Wear OS. */
private val TouchTargetHeight = 48.dp

@Composable
fun QrScreen() {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var qrContent by remember { mutableStateOf(sharedPrefs.getString(KEY_QR_CONTENT, "") ?: "") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // Lokální kopie: result.data je platform type z Javy, Kotlin ho
        // uvnitř podmínky sám nesmart-castne.
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val results: Bundle? = android.app.RemoteInput.getResultsFromIntent(data)
            val input = results?.getCharSequence("qr_input")?.toString()
            if (!input.isNullOrBlank()) {
                qrContent = input
                sharedPrefs.edit().putString(KEY_QR_CONTENT, input).apply()
            }
        }
    }

    // stringResource nelze volat uvnitř lokální funkce mimo composable scope.
    val inputLabel = stringResource(R.string.qr_input_label)

    fun launchTextInput() {
        val intent = Intent("android.support.wearable.input.action.REMOTE_INPUT")
        intent.putExtra(
            "android.support.wearable.input.extra.REMOTE_INPUTS",
            arrayOf(
                android.app.RemoteInput.Builder("qr_input")
                    .setLabel(inputLabel)
                    .build()
            )
        )
        launcher.launch(intent)
    }

    val hasCode = qrContent.isNotBlank()
    val isAmbient = LocalAmbientState.current

    // Čtečky v tramvaji nezvládají tmavý OLED displej. Na téhle stránce
    // proto jedeme na plný jas a při odchodu ho vracíme zpět.
    //
    // LocalContext nemusí být přímo Activity – v Compose to bývá
    // ContextWrapper, proto se prochází řetěz obalů.
    DisposableEffect(hasCode, isAmbient) {
        val window = context.findActivity()?.window
        val previousBrightness = window?.attributes?.screenBrightness
        // V ambientu se jas nezvedá – hodinky by svítily naplno v kapse.
        if (window != null && hasCode && !isAmbient) {
            window.attributes = window.attributes.apply { screenBrightness = 1f }
        }
        onDispose {
            if (window != null && previousBrightness != null) {
                window.attributes = window.attributes.apply { screenBrightness = previousBrightness }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.qr_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (isAmbient) AmbientDim else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (!hasCode) {
                Text(
                    text = stringResource(R.string.qr_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isAmbient) AmbientDim else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                if (!isAmbient) {
                    Spacer(modifier = Modifier.height(12.dp))
                    QrActionButton(
                        text = stringResource(R.string.qr_load),
                        onClick = { launchTextInput() },
                    )
                }
            } else {
                // Generování je 512×512 setPixel v cyklu – bez remember se
                // přepočítávalo při každé rekompozici.
                val qrBitmap = remember(qrContent) { QrCodeGenerator.generateQrCode(qrContent) }

                if (qrBitmap != null) {
                    // Bílý rám je "quiet zone" – bez něj mají čtečky problém
                    // najít okraj kódu.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(QR_WIDTH_FRACTION)
                            .aspectRatio(1f)
                            // Malý rádius: větší zaoblení ukusuje z bílé klidové
                            // zóny v rozích, kde jsou zaměřovací čtverce.
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White)
                            .padding(4.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = stringResource(R.string.qr_content_description),
                            // Bitmapa 512px se zmenšuje na ~120dp. Výchozí
                            // bilineární filtrace rozmaže hrany modulů a čtečka
                            // pak kód nepřečte – nearest neighbour drží ostré čtverce.
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // V ambientu se tlačítko nekreslí – nejde stisknout a jen
                    // by zbytečně svítilo vedle bílého čtverce.
                    if (!isAmbient) {
                        Spacer(modifier = Modifier.height(8.dp))
                        QrActionButton(
                            text = stringResource(R.string.qr_change),
                            onClick = { launchTextInput() },
                            secondary = true,
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.qr_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    if (!isAmbient) {
                        Spacer(modifier = Modifier.height(12.dp))
                        QrActionButton(
                            text = stringResource(R.string.qr_retry),
                            onClick = { launchTextInput() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * V Compose je LocalContext často ContextWrapper, ne přímo Activity.
 * Přímé přetypování `context as? Activity` proto někdy vrátí null a
 * zesvětlení displeje by tiše neproběhlo.
 */
private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

@Composable
private fun QrActionButton(
    text: String,
    onClick: () -> Unit,
    secondary: Boolean = false,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (secondary) {
                MaterialTheme.colorScheme.secondary
            } else {
                MaterialTheme.colorScheme.primary
            },
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        modifier = Modifier.defaultMinSize(minHeight = TouchTargetHeight)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}
