package com.glorious.hyperostdk

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.glorious.hyperostdk.ui.theme.HyperOSTDKTheme

private const val THEME_MANAGER_PACKAGE = "com.android.thememanager"

class ModuleSystemActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperOSTDKTheme {
                ModuleSystemScreen(
                    onOpenThemeManager = {
                        packageManager.getLaunchIntentForPackage(THEME_MANAGER_PACKAGE)?.let {
                            startActivity(it)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ModuleSystemScreen(onOpenThemeManager: () -> Unit) {
    val context = LocalContext.current
    val themeManagerInstalled = remember {
        runCatching {
            context.packageManager.getApplicationInfo(THEME_MANAGER_PACKAGE, 0)
        }.isSuccess
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Module & System") }) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                StatusCard(
                    title = "Theme Manager",
                    value = if (themeManagerInstalled) "Bulundu" else "Bulunamadı",
                    detail = THEME_MANAGER_PACKAGE
                )
            }
            item {
                StatusCard(
                    title = "LSPosed / libxposed",
                    value = "API 102 hedefi",
                    detail = "HyperOS TDK modülü yalnızca Theme Manager kapsamı için tasarlanmıştır."
                )
            }
            item {
                StatusCard(
                    title = "Import köprüsü",
                    value = "v0.2.5 motoru korunuyor",
                    detail = "v0.3.0 arayüz yeniden düzenlemesidir; çalışan MTZ import ve sonuç izleme hattı değiştirilmez."
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Modül hazırlığı", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "LSPosed tarafında modülün etkin ve kapsamın com.android.thememanager olarak seçili olması gerekir. Theme Manager prosesi başladıktan sonra hook'lar yüklenir.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = themeManagerInstalled,
                            onClick = onOpenThemeManager
                        ) {
                            Text("Theme Manager'ı Aç")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, value: String, detail: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
