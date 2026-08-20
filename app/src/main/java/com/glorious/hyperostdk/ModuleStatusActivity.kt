package com.glorious.hyperostdk

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glorious.hyperostdk.ui.theme.HyperOSTDKTheme

class ModuleStatusActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperOSTDKTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ModuleStatusScreen(
                        onOpenDiagnostics = {
                            startActivity(Intent(this, MainActivity::class.java))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModuleStatusScreen(onOpenDiagnostics: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "HyperOS TDK • v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "LSPosed Readiness Probe",
            style = MaterialTheme.typography.titleLarge
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Bu sürüm Theme Manager prosesine modern LSPosed API ile yüklenmek üzere hazırlanmıştır.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Scope yalnızca com.android.thememanager paketidir. Modül, ThemeImportManager ve MTZ import zincirindeki private sınıf/metodları yalnızca resolve eder; henüz import çağrısı yapmaz.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Test: LSPosed yöneticisinde HyperOS TDK modülünü etkinleştirin, Theme Manager'ı zorla durdurup yeniden açın ve LSPosed loglarında 'HyperOS-TDK' etiketini arayın.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Beklenen son satır: READINESS RESULT: 10/10 checks passed.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenDiagnostics
        ) {
            Text("Eski Tanılama Araçlarını Aç")
        }
    }
}
