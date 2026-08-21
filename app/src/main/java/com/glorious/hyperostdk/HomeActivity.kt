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

        runCatching {
            DiagnosticsSessionClient.ensureStarted(this)
            DiagnosticsSessionClient.append(
                this,
                event = "APP_OPEN",
                detail = "HyperOS TDK v${BuildConfig.VERSION_NAME} açıldı; always-on diagnostics aktif."
            )
        }

        setContent {
            HyperOSTDKTheme {
                HomeScreen(
                    onThemeTools = {
                        DiagnosticsSessionClient.append(this, "NAVIGATION", "Theme Tools açıldı")
                        startActivity(Intent(this, ModuleStatusActivity::class.java))
                    },
                    onPrivilegedTheme = {
                        DiagnosticsSessionClient.append(this, "NAVIGATION", "Privileged Theme Engine açıldı")
                        startActivity(Intent(this, PrivilegedThemeActivity::class.java))
                    },
                    onDiagnostics = {
                        DiagnosticsSessionClient.append(this, "NAVIGATION", "Live Diagnostics açıldı")
                        startActivity(Intent(this, LiveDiagnosticsActivity::class.java))
                    },
                    onModuleSystem = {
                        DiagnosticsSessionClient.append(this, "NAVIGATION", "Module & System açıldı")
                        startActivity(Intent(this, ModuleSystemActivity::class.java))
                    },
                    onInformation = {
                        DiagnosticsSessionClient.append(this, "NAVIGATION", "Bilgi & Ayarlar açıldı")
                        startActivity(Intent(this, InformationActivity::class.java))
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    onThemeTools: () -> Unit,
    onPrivilegedTheme: () -> Unit,
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
                    "Tema araçları, Shevery/Shizuku destekli privileged local engine, tanılama, modül durumu ve uygulama bilgilerini ayrı bölümlerde yönetin. Tanılama kaydı uygulama açıldığında otomatik başlar ve sürekli aktiftir.",
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
                        description = "Mevcut MTZ import ve Theme Manager işlemleri",
                        onClick = onThemeTools
                    )
                    HomeMenuCard(
                        modifier = Modifier.weight(1f),
                        badge = "LIVE",
                        title = "Diagnostics",
                        description = "Otomatik canlı kayıt, paylaşım ve gelişmiş tanılama",
                        onClick = onDiagnostics
                    )
                }
            }
            item {
                HomeMenuCard(
                    modifier = Modifier.fillMaxWidth(),
                    badge = "0.4.1",
                    title = "Privileged Theme Engine",
                    description = "Shevery/Shizuku • ThemeKit-benzeri MRC/MRM staging • LocalResource Apply",
                    onClick = onPrivilegedTheme
                )
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
