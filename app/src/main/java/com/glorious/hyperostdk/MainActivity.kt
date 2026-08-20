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
import com.glorious.hyperostdk.data.FrameworkArtifactExporter
import com.glorious.hyperostdk.data.IntentProbe
import com.glorious.hyperostdk.data.MtzInspector
import com.glorious.hyperostdk.data.ThemeInterfaceReflectionProbe
import com.glorious.hyperostdk.data.ThemeManagerInspector
import com.glorious.hyperostdk.data.ThemeServiceProbe
import com.glorious.hyperostdk.model.DeviceInfo
import com.glorious.hyperostdk.model.FrameworkArtifactExportResult
import com.glorious.hyperostdk.model.IntentProbeResult
import com.glorious.hyperostdk.model.MtzInfo
import com.glorious.hyperostdk.model.ThemeInterfaceReflectionResult
import com.glorious.hyperostdk.model.ThemeManagerInfo
import com.glorious.hyperostdk.model.ThemeServiceProbeResult
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
                DiagnosticsScreen(
                    onShareReport = { shareFile(it, "text/plain", "Tanılama raporunu paylaş") },
                    onShareArchive = { shareFile(it, "application/zip", "Framework paketini paylaş") }
                )
            }
        }
    }

    private fun shareFile(file: File, mimeType: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, chooserTitle))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(
    onShareReport: (File) -> Unit,
    onShareArchive: (File) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceInfo = remember { DeviceInfoProvider.read() }
    var themeManagerInfo by remember { mutableStateOf<ThemeManagerInfo?>(null) }
    var mtzInfo by remember { mutableStateOf<MtzInfo?>(null) }
    var probeResults by remember { mutableStateOf<List<IntentProbeResult>>(emptyList()) }
    var themeServiceProbeResult by remember { mutableStateOf<ThemeServiceProbeResult?>(null) }
    var themeInterfaceReflectionResult by remember { mutableStateOf<ThemeInterfaceReflectionResult?>(null) }
    var frameworkArtifactExportResult by remember { mutableStateOf<FrameworkArtifactExportResult?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf("v0.1.4 hazır. IThemeService sınıfını taşıyan MIUI framework artifact'ini bulup dışa aktarabilirsiniz.")
    }

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
                probeResults = emptyList()
                status = "MTZ incelendi: ${it.displayName}. Import Probe çalıştırılabilir."
            }.onFailure {
                status = "MTZ inceleme hatası: ${it.message}"
            }
            isBusy = false
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("HyperOS TDK • v0.1.4") }) }) { innerPadding ->
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
                                themeServiceProbeResult = null
                                themeInterfaceReflectionResult = null
                                frameworkArtifactExportResult = null
                                status = if (it.installed) {
                                    "Theme Manager bulundu: ${it.packageName}"
                                } else {
                                    "Theme Manager bulunamadı veya paket görünür değil."
                                }
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
                    OutlinedButton(enabled = !isBusy, onClick = { mtzPicker.launch(arrayOf("*/*")) }) {
                        Text("MTZ Seç")
                    }
                }
            }
            item {
                InfoCard("Import Probe") {
                    Text(
                        "Bu tarama temayı açmaz veya uygulamaz. Seçili MTZ URI'si için Theme Manager'ın hangi VIEW/SEND intent'lerini kabul ettiğini PackageManager üzerinden ölçer.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = !isBusy && mtzInfo != null,
                        onClick = {
                            val selectedMtz = mtzInfo ?: return@Button
                            scope.launch {
                                isBusy = true
                                status = "MTZ intent adayları taranıyor…"
                                runCatching {
                                    withContext(Dispatchers.IO) { IntentProbe.probe(context, selectedMtz) }
                                }.onSuccess { results ->
                                    probeResults = results
                                    val matchCount = results.sumOf { it.matches.size }
                                    status = "Import Probe tamamlandı: ${results.size} aday, $matchCount eşleşme."
                                }.onFailure {
                                    status = "Import Probe hatası: ${it.message}"
                                }
                                isBusy = false
                            }
                        }
                    ) { Text("MTZ Intent Probe") }

                    if (probeResults.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        IntentProbeContent(probeResults)
                    }
                }
            }
            item {
                InfoCard("ThemeService Binder Probe") {
                    Text(
                        "Bu test ThemeService'e yalnızca bağlanır, Binder kimliğini ve interface descriptor bilgisini okur, sonra bağlantıyı kapatır. Hiçbir Binder metodu çağrılmaz ve tema uygulanmaz.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = !isBusy && themeManagerInfo?.installed == true,
                        onClick = {
                            scope.launch {
                                isBusy = true
                                status = "ThemeService Binder bağlantısı test ediliyor…"
                                runCatching { ThemeServiceProbe.probe(context) }
                                    .onSuccess { result ->
                                        themeServiceProbeResult = result
                                        themeInterfaceReflectionResult = null
                                        frameworkArtifactExportResult = null
                                        status = if (result.connected) {
                                            "ThemeService bağlantısı başarılı: ${result.interfaceDescriptor ?: "descriptor alınamadı"}"
                                        } else {
                                            "ThemeService bağlantısı kurulamadı: ${result.error ?: "bilinmeyen neden"}"
                                        }
                                    }
                                    .onFailure {
                                        status = "ThemeService Probe hatası: ${it.message}"
                                    }
                                isBusy = false
                            }
                        }
                    ) { Text("ThemeService Binder Probe") }

                    Spacer(Modifier.height(12.dp))
                    ThemeServiceProbeContent(themeServiceProbeResult)
                }
            }
            item {
                InfoCard("IThemeService Reflection Probe") {
                    Text(
                        "Binder transaction çağrısı yapmaz. Cihazın runtime'ında descriptor sınıfını ve Stub sınıfını yüklemeyi; metod imzalarını ve statik TRANSACTION adlarını okumayı dener.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = !isBusy &&
                            themeServiceProbeResult?.connected == true &&
                            !themeServiceProbeResult?.interfaceDescriptor.isNullOrBlank(),
                        onClick = {
                            val descriptor = themeServiceProbeResult?.interfaceDescriptor ?: return@Button
                            scope.launch {
                                isBusy = true
                                status = "$descriptor runtime reflection ile inceleniyor…"
                                runCatching {
                                    withContext(Dispatchers.Default) {
                                        ThemeInterfaceReflectionProbe.probe(descriptor)
                                    }
                                }.onSuccess { result ->
                                    themeInterfaceReflectionResult = result
                                    status = "Reflection Probe tamamlandı: ${result.interfaceMethods.size} interface metodu, ${result.transactionNames.size} transaction adı."
                                }.onFailure {
                                    status = "Reflection Probe hatası: ${it.message}"
                                }
                                isBusy = false
                            }
                        }
                    ) { Text("IThemeService Reflection Probe") }

                    Spacer(Modifier.height(12.dp))
                    ThemeInterfaceReflectionContent(themeInterfaceReflectionResult)
                }
            }
            item {
                InfoCard("MIUI Framework Artifact") {
                    Text(
                        "Cihazın sistem bölümündeki MIUI framework APK/JAR adaylarını salt-okuma olarak tarar. IThemeService sınıfını taşıyan artifact'i ve Theme Manager APK'sını, değişiklik yapmadan paylaşılabilir bir ZIP içine kopyalar.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        enabled = !isBusy &&
                            themeServiceProbeResult?.connected == true &&
                            !themeServiceProbeResult?.interfaceDescriptor.isNullOrBlank(),
                        onClick = {
                            val descriptor = themeServiceProbeResult?.interfaceDescriptor ?: return@Button
                            scope.launch {
                                isBusy = true
                                status = "MIUI framework artifact'leri taranıyor ve paket hazırlanıyor…"
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        FrameworkArtifactExporter.probeAndExport(context, descriptor)
                                    }
                                }.onSuccess { result ->
                                    frameworkArtifactExportResult = result
                                    val matches = result.artifacts.count {
                                        it.containsInterface == true || it.containsStub == true
                                    }
                                    status = if (result.archivePath != null) {
                                        "Framework artifact taraması tamamlandı: $matches sınıf eşleşmesi, ${result.exportedFiles.size} dosya ZIP'e eklendi."
                                    } else {
                                        "Framework artifact taraması tamamlandı fakat ZIP oluşturulamadı: ${result.error ?: "bilinmeyen neden"}"
                                    }
                                }.onFailure {
                                    status = "Framework artifact tarama hatası: ${it.message}"
                                }
                                isBusy = false
                            }
                        }
                    ) { Text("Framework Artifact Bul ve Paketle") }

                    val archivePath = frameworkArtifactExportResult?.archivePath
                    if (!archivePath.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            enabled = !isBusy,
                            onClick = { onShareArchive(File(archivePath)) }
                        ) { Text("Artifact ZIP'ini Paylaş") }
                    }

                    Spacer(Modifier.height(12.dp))
                    FrameworkArtifactContent(frameworkArtifactExportResult)
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
                                    DiagnosticsLogger.writeReport(
                                        context = context,
                                        deviceInfo = deviceInfo,
                                        themeManagerInfo = managerInfo,
                                        mtzInfo = mtzInfo,
                                        intentProbeResults = probeResults,
                                        themeServiceProbeResult = themeServiceProbeResult,
                                        themeInterfaceReflectionResult = themeInterfaceReflectionResult,
                                        frameworkArtifactExportResult = frameworkArtifactExportResult
                                    )
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
                item {
                    Text(
                        "Theme Manager Bileşenleri (${components.size})",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                items(components) { component ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(component.type.uppercase(), style = MaterialTheme.typography.labelSmall)
                            Text(component.name, fontFamily = FontFamily.Monospace)
                            Text(
                                "exported=${component.exported} • permission=${component.permission ?: "none"}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IntentProbeContent(results: List<IntentProbeResult>) {
    results.forEach { result ->
        Text(result.label, style = MaterialTheme.typography.labelLarge)
        if (result.matches.isEmpty()) {
            Text("Eşleşme yok", style = MaterialTheme.typography.bodySmall)
        } else {
            result.matches.forEach { match ->
                Text(
                    "→ ${match.componentName}\nexported=${match.exported} • permission=${match.permission ?: "none"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ThemeServiceProbeContent(result: ThemeServiceProbeResult?) {
    if (result == null) {
        Text("Henüz çalıştırılmadı.", style = MaterialTheme.typography.bodySmall)
        return
    }
    KeyValue("Connected", result.connected.toString())
    KeyValue("Bind requested", result.bindRequested.toString())
    Text("Component\n${result.componentName}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    Spacer(Modifier.height(6.dp))
    Text(
        "Interface descriptor\n${result.interfaceDescriptor ?: "bilinmiyor"}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Binder class\n${result.binderClass ?: "bilinmiyor"}",
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace
    )
    KeyValue("Binder alive", result.binderAlive?.toString() ?: "bilinmiyor")
    KeyValue("Ping", result.pingBinder?.toString() ?: "bilinmiyor")
    result.error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ThemeInterfaceReflectionContent(result: ThemeInterfaceReflectionResult?) {
    if (result == null) {
        Text("Henüz çalıştırılmadı.", style = MaterialTheme.typography.bodySmall)
        return
    }

    Text("Descriptor\n${result.descriptor}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    KeyValue("Interface class", if (result.interfaceClassLoaded) "yüklendi" else "yüklenemedi")
    KeyValue("Stub class", if (result.stubClassLoaded) "yüklendi" else "yüklenemedi")
    KeyValue("Interface methods", result.interfaceMethods.size.toString())
    KeyValue("Transaction fields", result.transactionFields.size.toString())
    KeyValue("Transaction names", result.transactionNames.size.toString())

    if (result.interfaceMethods.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Interface metodları", style = MaterialTheme.typography.labelLarge)
        result.interfaceMethods.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }

    if (result.transactionNames.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Transaction adları", style = MaterialTheme.typography.labelLarge)
        result.transactionNames.forEach {
            Text("${it.code} → ${it.name}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }

    if (result.transactionFields.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("TRANSACTION alanları", style = MaterialTheme.typography.labelLarge)
        result.transactionFields.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }

    if (result.errors.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text("Reflection notları", style = MaterialTheme.typography.labelLarge)
        result.errors.forEach {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FrameworkArtifactContent(result: FrameworkArtifactExportResult?) {
    if (result == null) {
        Text("Henüz çalıştırılmadı.", style = MaterialTheme.typography.bodySmall)
        return
    }

    KeyValue("Kontrol edilen", result.artifacts.size.toString())
    KeyValue(
        "Sınıf eşleşmesi",
        result.artifacts.count { it.containsInterface == true || it.containsStub == true }.toString()
    )
    KeyValue("ZIP dosyası", result.archiveName ?: "oluşturulamadı")
    KeyValue("ZIP boyutu", formatBytes(result.archiveSizeBytes))
    KeyValue("Dışa aktarılan", result.exportedFiles.size.toString())

    result.artifacts
        .filter { it.exists || it.containsInterface == true || it.scanError != null }
        .forEach { artifact ->
            Spacer(Modifier.height(6.dp))
            Text(artifact.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text(
                "readable=${artifact.readable} • interface=${artifact.containsInterface ?: "?"} • stub=${artifact.containsStub ?: "?"}",
                style = MaterialTheme.typography.bodySmall
            )
            artifact.sha256?.let {
                Text("SHA-256: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            artifact.scanError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

    result.error?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error)
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
