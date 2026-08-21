package com.glorious.hyperostdk

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.glorious.hyperostdk.privileged.HyperThemeCompatEngine
import com.glorious.hyperostdk.privileged.PrivilegedThemeEngine
import com.glorious.hyperostdk.privileged.ShizukuBridge
import com.glorious.hyperostdk.ui.theme.HyperOSTDKTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val privilegedImportScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

class PrivilegedThemeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagnosticsSessionClient.append(this, "NAVIGATION", "Privileged Theme Engine açıldı • build30 HyperTheme reference mode")
        setContent {
            HyperOSTDKTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PrivilegedThemeScreen()
                }
            }
        }
    }
}

private data class PrivilegedSelectedMtz(
    val displayName: String,
    val uri: Uri
)

@Composable
private fun PrivilegedThemeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bridgeState by remember { mutableStateOf(ShizukuBridge.inspect(context)) }
    var capability by remember { mutableStateOf<PrivilegedThemeEngine.CapabilityReport?>(null) }
    var selectedMtz by remember { mutableStateOf<PrivilegedSelectedMtz?>(null) }
    var status by remember {
        mutableStateOf("Shevery/Shizuku durumunu kontrol edin, ardından Capability Test çalıştırın.")
    }
    var busy by remember { mutableStateOf(false) }
    var showApplyConfirmation by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            bridgeState = ShizukuBridge.inspect(context)
            delay(1_000)
        }
    }

    val mtzPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val displayName = queryPrivilegedDisplayName(context.contentResolver, uri)
            ?: uri.lastPathSegment
            ?: "selected.mtz"
        if (!displayName.endsWith(".mtz", ignoreCase = true)) {
            selectedMtz = null
            status = "Seçilen dosya MTZ değil: $displayName"
        } else {
            selectedMtz = PrivilegedSelectedMtz(displayName, uri)
            status = "MTZ hazır: $displayName"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("HyperOS TDK • v${BuildConfig.VERSION_NAME} • build 30", style = MaterialTheme.typography.headlineSmall)
        Text("HyperTheme Direct Apply Test", style = MaterialTheme.typography.titleLarge)
        Text(
            "Build 30, HyperTheme 1.1.17 (38) içindeki Global-ROM direct apply yolunu izole eder: seçili MTZ snapshot.mtz olarak Theme Manager alanına kopyalanır ve ApplyThemeForScreenshot doğrudan çağrılır. ThemeDetailActivity kullanılmaz.",
            style = MaterialTheme.typography.bodyMedium
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Shevery / Shizuku", style = MaterialTheme.typography.titleMedium)
                Text("Shevery: ${if (bridgeState.sheveryInstalled) "bulundu" else "görünmüyor"}")
                Text("Binder: ${if (bridgeState.binderAlive) "hazır" else "hazır değil"}")
                Text("Yetki: ${if (bridgeState.permissionGranted) "verildi" else "gerekli"}")
                Text("Backend: ${bridgeState.backend}")
                Text("UID: ${bridgeState.serverUid ?: "-"}")
                Text("API: ${bridgeState.serverVersion ?: "-"}")
                bridgeState.selinuxContext?.let {
                    Text("SELinux: $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }

        if (bridgeState.binderAlive && !bridgeState.permissionGranted) {
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                onClick = {
                    ShizukuBridge.requestPermission()
                        .onSuccess { status = "Shevery/Shizuku yetki isteği gönderildi." }
                        .onFailure { error -> status = "Yetki isteği başarısız: ${error.message}" }
                }
            ) {
                Text("1. Shevery / Shizuku Yetkisi Ver")
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && bridgeState.permissionGranted,
            onClick = {
                scope.launch {
                    busy = true
                    status = "Theme Manager .data capability testi çalışıyor…"
                    runCatching { PrivilegedThemeEngine.probe(context) }
                        .onSuccess { report ->
                            capability = report
                            status = report.detail
                        }
                        .onFailure { error ->
                            status = "Capability testi başarısız: ${error.javaClass.simpleName}: ${error.message}"
                        }
                    busy = false
                }
            }
        ) {
            Text("2. Capability Test")
        }

        capability?.let { report ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        if (report.ready) "Direct Apply: HAZIR" else "Direct Apply: HAZIR DEĞİL",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(report.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            onClick = { mtzPicker.launch(arrayOf("*/*")) }
        ) {
            Text("3. MTZ Seç")
        }

        selectedMtz?.let { selected ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("Seçili MTZ", style = MaterialTheme.typography.labelLarge)
                    Text(selected.displayName)
                    Text(selected.uri.toString(), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && selectedMtz != null && capability?.ready == true,
            onClick = { showApplyConfirmation = true }
        ) {
            Text(if (busy) "İşlem sürüyor…" else "4. HyperTheme Direct Apply")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Text(status, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Build 30 izolasyon testi: ThemeKit LocalResource/ThemeDetailActivity açılmaz. HyperTheme'den çıkarılan snapshot + ApplyThemeForScreenshot yolu tek başına denenir. Rights/trial kontrollerine müdahale edilmez.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (showApplyConfirmation) {
        AlertDialog(
            onDismissRequest = { showApplyConfirmation = false },
            title = { Text("HyperTheme Direct Apply") },
            text = {
                Text(
                    "Seçili MTZ, Shevery/Shizuku üzerinden Theme Manager'ın snapshot/snapshot.mtz dosyasına kopyalanacak. Ardından Xiaomi'nin ApplyThemeForScreenshot Activity'si HyperTheme 1.1.17 ile aynı extra değerleri kullanılarak çağrılacak. Devam edilsin mi?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showApplyConfirmation = false
                        val selected = selectedMtz ?: return@TextButton
                        val appContext = context.applicationContext
                        busy = true
                        status = "HyperTheme reference apply hazırlanıyor…"
                        DiagnosticsSessionClient.append(
                            appContext,
                            "PRIVILEGED_IMPORT_SCOPE",
                            "processScope=true • applyMode=hypertheme-direct • build=30"
                        )
                        privilegedImportScope.launch {
                            runCatching {
                                HyperThemeCompatEngine.apply(
                                    context = appContext,
                                    displayName = selected.displayName,
                                    sourceUri = selected.uri
                                )
                            }.onSuccess { result ->
                                DiagnosticsSessionClient.append(
                                    appContext,
                                    "HYPERTHEME_REFERENCE_APPLY_COMPLETED",
                                    "bytes=${result.snapshotBytes} • sha1=${result.sha1} • build=30"
                                )
                                status = "HyperTheme Direct Apply çağrısı gönderildi. snapshot=${result.snapshotBytes} bayt. Tema uygulanmışsa sonucu kontrol edin; uygulanmadıysa Live Diagnostics'i paylaşın."
                            }.onFailure { error ->
                                DiagnosticsSessionClient.append(
                                    appContext,
                                    "HYPERTHEME_REFERENCE_APPLY_FAILED",
                                    "${error.javaClass.simpleName}: ${error.message}",
                                    level = "ERROR"
                                )
                                status = "HyperTheme Direct Apply başarısız: ${error.javaClass.simpleName}: ${error.message}. Live Diagnostics kaydını paylaşın."
                            }
                            busy = false
                        }
                    }
                ) {
                    Text("Snapshot'a Yaz ve Uygula")
                }
            },
            dismissButton = {
                TextButton(onClick = { showApplyConfirmation = false }) {
                    Text("İptal")
                }
            }
        )
    }
}

private fun queryPrivilegedDisplayName(contentResolver: ContentResolver, uri: Uri): String? = runCatching {
    contentResolver.query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0) cursor.getString(index) else null
    }
}.getOrNull()
