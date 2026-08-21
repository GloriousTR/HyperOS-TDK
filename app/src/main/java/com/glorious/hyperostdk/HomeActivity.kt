package com.glorious.hyperostdk

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glorious.hyperostdk.ui.theme.HyperOSTDKTheme

class HomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperOSTDKTheme {
                HomeScreen(
                    onThemeTools = { startActivity(Intent(this, ModuleStatusActivity::class.java)) },
                    onDiagnostics = { startActivity(Intent(this, MainActivity::class.java)) },
                    onModuleSystem = { startActivity(Intent(this, ModuleSystemActivity::class.java)) },
                    onInformation = { startActivity(Intent(this, InformationActivity::class.java)) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    onThemeTools: () -> Unit,
    onDiagnostics: () -> Unit,
    onModuleSystem: () -> Unit,
    onInformation: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("HyperOS TDK")
                        Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text(
                    "HyperOS Theme Development Kit",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            item {
                Text(
                    "Tema araçları, tanılama, modül durumu ve uygulama bilgilerini ayrı bölümlerde yönetin.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HomeMenuCard(
                        modifier = Modifier.weight(1f),
                        badge = "MTZ",
                        title = "Theme Tools",
                        description = "MTZ import ve Theme Manager işlemleri",
                        onClick = onThemeTools
                    )
                    HomeMenuCard(
                        modifier = Modifier.weight(1f),
                        badge = "D",
                        title = "Diagnostics",
                        description = "Probe, inceleme ve tanılama raporları",
                        onClick = onDiagnostics
                    )
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    HomeMenuCard(
                        modifier = Modifier.weight(1f),
                        badge = "M",
                        title = "Module & System",
                        description = "LSPosed, kapsam ve sistem uyumluluğu",
                        onClick = onModuleSystem
                    )
                    HomeMenuCard(
                        modifier = Modifier.weight(1f),
                        badge = "i",
                        title = "Bilgi & Ayarlar",
                        description = "Sürüm, cihaz ve proje bilgileri",
                        onClick = onInformation
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeMenuCard(
    modifier: Modifier,
    badge: String,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(170.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(badge, style = MaterialTheme.typography.headlineMedium)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
