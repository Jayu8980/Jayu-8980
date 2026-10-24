package com.pianoscribe.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val basicPitch = remember { runCatching { ctx.assets.open("licenses/BASIC_PITCH_LICENSE.txt").bufferedReader().readText() }.getOrDefault("") }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Licenses & credits") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Basic Pitch", style = MaterialTheme.typography.titleMedium)
            Text("Polyphonic note transcription model by Spotify (ICASSP 2022), bundled as TensorFlow Lite (nmp.tflite from the basic-pitch 0.4.0 package). Licensed under the Apache License 2.0.")
            Text("PianoScribe Grand SoundFont", style = MaterialTheme.typography.titleMedium)
            Text("Piano samples synthesised by PianoScribe's own generator (core/PianoSampleGenerator.kt). Released with the app under the Apache License 2.0.")
            Text("Open-source libraries", style = MaterialTheme.typography.titleMedium)
            Text("AndroidX, Jetpack Compose, Material 3, Room, Navigation, Kotlin coroutines (Apache 2.0); TensorFlow Lite (Apache 2.0).")
            Text("Basic Pitch license", style = MaterialTheme.typography.titleMedium)
            Text(basicPitch, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
    }
}
