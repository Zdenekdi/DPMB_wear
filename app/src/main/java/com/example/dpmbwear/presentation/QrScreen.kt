package com.example.dpmbwear.presentation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text

@Composable
fun QrScreen() {
    val context = LocalContext.current
    val sharedPrefs = context.getSharedPreferences("dpmb_prefs", Context.MODE_PRIVATE)
    var qrContent by remember { mutableStateOf(sharedPrefs.getString("qr_content", "") ?: "") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val results: Bundle? = android.app.RemoteInput.getResultsFromIntent(result.data)
            val input = results?.getCharSequence("qr_input")?.toString()
            if (!input.isNullOrBlank()) {
                qrContent = input
                sharedPrefs.edit().putString("qr_content", input).apply()
            }
        }
    }

    fun launchTextInput() {
        val intent = Intent("android.support.wearable.input.action.REMOTE_INPUT")
        intent.putExtra(
            "android.support.wearable.input.extra.REMOTE_INPUTS",
            arrayOf(
                android.app.RemoteInput.Builder("qr_input")
                    .setLabel("Zadejte URL nebo kód")
                    .build()
            )
        )
        launcher.launch(intent)
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (qrContent.isBlank()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "BRNO iD",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text("Žádný QR kód", color = Color.Gray, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { launchTextInput() },
                    colors = androidx.wear.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF35659C))
                ) {
                    Text("Nahrát", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            val qrBitmap = QrCodeGenerator.generateQrCode(qrContent)
            if (qrBitmap != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "BRNO iD",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = "QR Kód",
                        modifier = Modifier.size(110.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { launchTextInput() },
                        colors = androidx.wear.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF35659C)),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("Změnit", color = Color.White, fontSize = 12.sp)
                    }
                }
            } else {
                Text("Chyba generování QR", color = Color.Red)
            }
        }
    }
}
