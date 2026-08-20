package com.glorious.hyperostdk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.glorious.hyperostdk.data.DeviceInfoProvider
import com.glorious.hyperostdk.data.DiagnosticsLogger
import com.glorious.hyperostdk.data.MtzInspector
import com.glorious.hyperostdk.data.ThemeManagerInspector
import com.glorious.hyperostdk.model.DeviceInfo
import com.glorious.hyperostdk.model.MtzInfo
import com.glorious.hyperostdk.model.ThemeManagerInfo
import com.glorious.hyperostdk.ui.theme.HyperOSTDKTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperOSTDKTheme {
                DiagnosticsScreen(onShareReport = ::shareReport)
            }
        }
    }

    private fun shareReport(file: File) {
        val uri = FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Tanılama raporunu paylaş"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(onShareReport: (File) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceInfo = remember { DeviceInfoProvider.read() }
    var themeManagerInfo by remember { mutableStateOf<ThemeManagerInfo?>(null) }
    var mtzInfo by remember { mutableStateOf<MtzInfo?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Foundation hazır. Theme Manager taramasıyla başlayabilirsiniz.") }

    val mtzPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scope.launch {
            isBusy = true
            status = "MTZ inceleniyor…"
            runCatching {
                withContext(Dispatchers.IO) { MtzInspector.inspect(context.contentResolver, uri) }
            }.onSuccess {
                mtzInfo = it
                status = "MTZ incelendi: ${it.displayName}"
            }.onFailure {
                status = "MTZ inceleme hatası: ${it.message}"
            }
            isBusy = false
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("HyperOS TDK • v0.1.0") }) }) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { InfoCard("Cihaz Bilgileri") { DeviceInfoContent(deviceInfo) } }
            item {
                InfoCard("Theme Manager") {
                    ThemeManagerContent(themeManagerInfo)
                    Spacer(Modifier.height(12.dp))
                    Button(enabled = !isBusy, onClick = {
                        scope.launch {
                            isBusy = true
                            status = "Theme Manager taranıyor…"
                            runCatching {
                                withContext(Dispatchers.IO) { ThemeManagerInspector.inspect(context) }
                            }.onSuccess {
                                themeManagerInfo = it
                                status = if (it.installed) "Theme Manager bulundu: ${it.packageName}" else "Theme Manager bulunamadı veya paket görünür değil."
                            }.onFailure {
                                status = "Theme Manager tarama hatası: ${it.message}"
                            }
                            isBusy = false
                        }
                    }) { Text("Theme Manager Tara") }
                }
            }
            item {
                InfoCard("MTZ Dosyası") {
                    MtzContent(mtzInfo)
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(enabled = !isBusy, onClick = { mtzPicker.launch(arrayOf("*/*")) }) { Text("MTZ Seç") }
                }
            }
            item {
                InfoCard("Tanılama") {
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    if (isBusy) {
                        Spacer(Modifier.height(12.dp))
                        CircularProgressIndicator()
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(enabled = !isBusy, onClick = {
                        scope.launch {
                            isBusy = true
                            val managerInfo = themeManagerInfo ?: withContext(Dispatchers.IO) {
                                ThemeManagerInspector.inspect(context)
                            }.also { themeManagerInfo = it }
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    DiagnosticsLogger.writeReport(context, deviceInfo, managerInfo, mtzInfo)
                                }
                            }.onSuccess {
                                status = "Tanılama raporu oluşturuldu: ${it.name}"
                                onShareReport(it)
                            }.onFailure {
                                status = "Rapor oluşturma hatası: ${it.message}"
                            }
                            isBusy = false
                        }
                    }) { Text("Tanılama Raporu Oluştur") }
                }
            }
            themeManagerInfo?.components?.takeIf { it.isNotEmpty() }?.let { components ->
                item { Text("Theme Manager Bileşenleri (${components.size})", style = MaterialTheme.typography.titleMedium) }
                items(components) { component ->
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(component.type.uppercase(), style = MaterialTheme.typography.labelSmall)
                            Text(component.name, fontFamily = FontFamily.Monospace)
                            Text("exported=${component.exported} • permission=${component.permission ?: "none"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DeviceInfoContent(info: DeviceInfo) {
    KeyValue("Üretici", info.manufacturer)
    KeyValue("Model", "${info.brand} ${info.model}")
    KeyValue("Cihaz", info.device)
    KeyValue("Android", "${info.androidVersion} / API ${info.sdkInt}")
    KeyValue("Build", info.buildDisplay)
    KeyValue("HyperOS", info.hyperOsVersion ?: "property görünmüyor")
    KeyValue("MIUI", info.miuiVersion ?: "property görünmüyor")
    KeyValue("Mod device", info.modDevice ?: "property görünmüyor")
}

@Composable
private fun ThemeManagerContent(info: ThemeManagerInfo?) {
    if (info == null) {
        Text("Henüz taranmadı.")
        return
    }
    KeyValue("Kurulu", if (info.installed) "Evet" else "Hayır")
    KeyValue("Paket", info.packageName)
    KeyValue("Sürüm", info.versionName ?: "bilinmiyor")
    KeyValue("Version code", info.versionCode?.toString() ?: "bilinmiyor")
    KeyValue("Launch activity", info.launchActivity ?: "bulunamadı")
    KeyValue("Bileşen", info.components.size.toString())
    info.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun MtzContent(info: MtzInfo?) {
    if (info == null) {
        Text("Henüz MTZ seçilmedi.")
        return
    }
    KeyValue("Dosya", info.displayName)
    KeyValue("Boyut", formatBytes(info.sizeBytes))
    KeyValue("MIME", info.mimeType ?: "bilinmiyor")
    KeyValue("ZIP/MTZ", if (info.isZipContainer) "Okunabilir" else "Okunamadı")
    KeyValue("İçerik", "${info.entries.size} kayıt")
    Text("SHA-256\n${info.sha256}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    info.warning?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
    }
    Spacer(Modifier.height(4.dp))
}

private fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "bilinmiyor"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    return "%.2f MB".format(kb / 1024.0)
}
