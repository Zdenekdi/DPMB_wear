package com.example.dpmbwear.presentation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.wear.compose.material3.ScreenScaffold
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.delay

@Composable
fun SmsScreen() {
    val context = LocalContext.current
    val listState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    val sharedPrefs = context.getSharedPreferences("dpmb_prefs", Context.MODE_PRIVATE)
    
    var expiryTime by remember { mutableStateOf(sharedPrefs.getLong("sms_expiry", 0L)) }
    var currentTime by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(expiryTime) {
        if (expiryTime > 0) {
            while (currentTime < expiryTime) {
                delay(1000)
                currentTime = System.currentTimeMillis()
            }
        }
    }

    fun sendSms(phoneNumber: String, message: String, durationMinutes: Int) {
        val newExpiry = System.currentTimeMillis() + (durationMinutes * 60 * 1000L)
        sharedPrefs.edit().putLong("sms_expiry", newExpiry).apply()
        expiryTime = newExpiry

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$phoneNumber")
            putExtra("sms_body", message)
        }
        context.startActivity(intent)
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp, start = 8.dp, end = 8.dp)
        ) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "SMS JÍZDENKY",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        if (expiryTime > currentTime) {
                            val diff = expiryTime - currentTime
                            val minutes = (diff / 1000) / 60
                            val seconds = (diff / 1000) % 60
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = String.format("%02d:%02d", minutes, seconds),
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.Green
                            )
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = { sendSms("90206", "BRNO20", 20) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF35659C)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("20 min", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Základní", color = Color.Gray, fontSize = 12.sp)
                        }
                        Text("20 Kč", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
            item {
                Button(
                    onClick = { sendSms("90206", "BRNO", 75) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF35659C)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("75 min", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Základní", color = Color.Gray, fontSize = 12.sp)
                        }
                        Text("29 Kč", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
            item {
                Button(
                    onClick = { sendSms("90206", "BRNOD", 1440) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF35659C)),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("24 hod", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Text("Denní", color = Color.Gray, fontSize = 12.sp)
                        }
                        Text("99 Kč", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}
