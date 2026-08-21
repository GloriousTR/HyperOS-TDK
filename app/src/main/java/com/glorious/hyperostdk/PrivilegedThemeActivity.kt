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
import com.glorious.hyperostdk.privileged.DirectThemeApplyEngine
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
        DiagnosticsSessionClient.append(this, "NAVIGATION", "Privileged Theme Engine açıldı • build33 strict local apply")
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
        Text("HyperOS TDK • v${BuildConfig.VERSION_NAME} • build 33", style = MaterialTheme.typography.headlineSmall)
        Text("Strict Local Theme Apply", style = MaterialTheme.typography.titleLarge)
        Text(
            "Build 33 yerel tema metadata'sını daha sıkı Theme Manager uyumluluğuna getirir. Bu ROM'da direct-apply activity yoksa version, price, adapter ve preview metadata alanları düzeltilerek aynı local resource tekrar açılır.",
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
                    status = "Theme Manager capability testi çalışıyor…"
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
                        if (report.ready) "Theme Apply: HAZIR" else "Theme Apply: HAZIR DEĞİL",
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
            Text(if (busy) "İşlem sürüyor…" else "4. Strict Local Theme Apply")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Text(status, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(4.dp))
        Text(
            "Build 33 testi: local resource oluşturulduktan sonra yalnız HyperOS-TDK'nin ürettiği metadata düzeltilir ve aynı localId yeniden açılır. Rights/trial kontrollerine müdahale edilmez.",
            style = MaterialTheme.typography.bodySmall
        )
    }

    if (showApplyConfirmation) {
        AlertDialog(
            onDismissRequest = { showApplyConfirmation = false },
            title = { Text("Strict Local Theme Apply") },
            text = {
                Text(
                    "Seçili MTZ Theme Manager yerel resource alanına hazırlanacak. Bu ROM direct-apply activity sunmuyorsa metadata strict uyumluluk değerleriyle yeniden yazılıp aynı tema detay ekranı tekrar açılacak. Devam edilsin mi?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showApplyConfirmation = false
                        val selected = selectedMtz ?: return@TextButton
                        val appContext = context.applicationContext
                        busy = true
                        status = "Strict Local Theme Apply hazırlanıyor…"
                        DiagnosticsSessionClient.append(
                            appContext,
                            "PRIVILEGED_IMPORT_SCOPE",
                            "processScope=true • applyMode=strict-local • build=33"
                        )
                        privilegedImportScope.launch {
                            runCatching {
                                DirectThemeApplyEngine.apply(
                                    context = context,
                                    displayName = selected.displayName,
                                    sourceUri = selected.uri
                                )
                            }.onSuccess { result ->
                                DiagnosticsSessionClient.append(
                                    appContext,
                                    "DIRECT_APPLY_COMPLETED",
                                    "route=${result.route} • bytes=${result.snapshotBytes} • sha1=${result.sha1} • component=${result.component} • fallbackLocalId=${result.fallbackLocalId} • build=33"
                                )
                                status = when (result.route) {
                                    DirectThemeApplyEngine.Route.DIRECT_COMPONENT ->
                                        "Direct Apply çağrısı gönderildi: ${result.component}. Tema sonucunu kontrol edin."
                                    DirectThemeApplyEngine.Route.LOCAL_RESOURCE_FALLBACK ->
                                        "Strict Local Resource akışı tamamlandı. localId=${result.fallbackLocalId}. Tema ekranı kapanırsa Live Diagnostics kaydını paylaşın."
                                }
                            }.onFailure { error ->
                                DiagnosticsSessionClient.append(
                                    appContext,
                                    "DIRECT_APPLY_FAILED",
                                    "${error.javaClass.simpleName}: ${error.message}",
                                    level = "ERROR"
                                )
                                status = "Strict Local Theme Apply başarısız: ${error.javaClass.simpleName}: ${error.message}. Live Diagnostics kaydını paylaşın."
                            }
                            busy = false
                        }
                    }
                ) {
                    Text("Hazırla ve Uygula")
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
