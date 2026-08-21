package com.glorious.hyperostdk

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glorious.hyperostdk.ui.theme.HyperOSTDKTheme

class InformationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperOSTDKTheme {
                InformationScreen()
            }
        }
    }
}

@Composable
private fun InformationScreen() {
    Scaffold(topBar = { TopAppBar(title = { Text("Bilgi & Ayarlar") }) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                InfoSection(
                    title = "HyperOS TDK",
                    lines = listOf(
                        "Sürüm: ${BuildConfig.VERSION_NAME}",
                        "Paket: ${BuildConfig.APPLICATION_ID}",
                        "Amaç: Xiaomi HyperOS Theme Manager davranışlarını incelemek ve güvenli tema araçları sunmak."
                    )
                )
            }
            item {
                InfoSection(
                    title = "Cihaz",
                    lines = listOf(
                        "Üretici: ${Build.MANUFACTURER}",
                        "Model: ${Build.MODEL}",
                        "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
                    )
                )
            }
            item {
                InfoSection(
                    title = "v0.3.0 mimarisi",
                    lines = listOf(
                        "Theme Tools — MTZ ve Theme Manager işlemleri",
                        "Diagnostics — probe ve tanılama araçları",
                        "Module & System — LSPosed ve sistem entegrasyonu",
                        "Bilgi & Ayarlar — uygulama ve cihaz bilgileri"
                    )
                )
            }
            item {
                InfoSection(
                    title = "Ayarlar",
                    lines = listOf(
                        "v0.3.0'da kalıcı kullanıcı tercihi gerektiren bir ayar bulunmuyor.",
                        "Yeni ayarlar eklendikçe bu bölümden yönetilecek."
                    )
                )
            }
        }
    }
}

@Composable
private fun InfoSection(title: String, lines: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
