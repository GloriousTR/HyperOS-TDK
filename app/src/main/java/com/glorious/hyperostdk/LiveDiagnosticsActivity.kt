package com.glorious.hyperostdk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.glorious.hyperostdk.ui.theme.HyperOSTDKTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TARGET_PACKAGE = "com.android.thememanager"

class LiveDiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            DiagnosticsSessionClient.ensureStarted(this)
            DiagnosticsSessionClient.append(
                this,
                event = "LIVE_DIAGNOSTICS_OPENED",
                detail = "Kullanıcı canlı tanılama ekranını açtı."
            )
        }

        setContent {
            HyperOSTDKTheme {
                LiveDiagnosticsScreen(
                    onShare = { shareDiagnostics(it) },
                    onOpenAdvanced = { startActivity(Intent(this, MainActivity::class.java)) },
                    onOpenThemeManagerSettings = { openThemeManagerSettings() }
                )
            }
        }
    }

    private fun shareDiagnostics(file: File) {
        runCatching {
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
            startActivity(Intent.createChooser(intent, "Canlı tanılama raporunu paylaş"))
            runCatching {
                DiagnosticsSessionClient.append(
                    this,
                    event = "LIVE_DIAGNOSTICS_SHARED",
                    detail = "Rapor paylaşım ekranı açıldı: ${file.name}"
                )
            }
        }.onFailure { error ->
            runCatching {
                DiagnosticsSessionClient.append(
                    this,
                    event = "LIVE_DIAGNOSTICS_SHARE_FAILED",
                    detail = "${error.javaClass.simpleName}: ${error.message}",
                    level = "ERROR"
                )
            }
            Toast.makeText(
                this,
                "Rapor paylaşılamadı: ${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun openThemeManagerSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$TARGET_PACKAGE")
                }
            )
            DiagnosticsSessionClient.append(
                this,
                event = "THEME_MANAGER_SETTINGS_OPENED",
                detail = "Eski LSPosed hook sürecini sonlandırmak için Theme Manager uygulama bilgisi açıldı."
            )
        }.onFailure {
            Toast.makeText(this, "Theme Manager uygulama bilgisi açılamadı.", Toast.LENGTH_LONG).show()
        }
    }
}

private data class HookRuntimeStatus(
    val state: State,
    val message: String
) {
    enum class State { READY, WAITING, STALE }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveDiagnosticsScreen(
    onShare: (File) -> Unit,
    onOpenAdvanced: () -> Unit,
    onOpenThemeManagerSettings: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var snapshot by remember { mutableStateOf<DiagnosticsSessionClient.Snapshot?>(null) }
    var status by remember { mutableStateOf("Canlı tanılama oturumu hazırlanıyor…") }
    var hookRuntimeStatus by remember {
        mutableStateOf(
            HookRuntimeStatus(
                HookRuntimeStatus.State.WAITING,
                "Theme Manager hook durumu bekleniyor…"
            )
        )
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            DiagnosticsSessionClient.ensureStarted(context)
        }
        while (true) {
            snapshot = withContext(Dispatchers.IO) {
                runCatching { DiagnosticsSessionClient.snapshot(context) }.getOrNull()
            }
            status = if (snapshot != null) {
                "Otomatik kayıt aktif • ${snapshot?.eventCount ?: 0} olay"
            } else {
                "Tanılama oturumu okunamadı; yeniden deneniyor…"
            }
            hookRuntimeStatus = evaluateHookRuntime(snapshot?.text.orEmpty())
            delay(750)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Live Diagnostics")
                        Text("v${BuildConfig.VERSION_NAME} • otomatik kayıt", style = MaterialTheme.typography.labelMedium)
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Always-on Diagnostics", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "HyperOS TDK açıldığında kayıt otomatik olarak aktif olur. Başlat/Durdur işlemi yoktur. Theme Tools, import yaşam döngüsü ve Theme Manager apply hook'ları aynı zaman çizelgesine yazılır.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(status, style = MaterialTheme.typography.labelLarge)
                        snapshot?.let {
                            Text("Oturum: ${it.sessionId}", style = MaterialTheme.typography.bodySmall)
                            Text("Başlangıç: ${formatTime(it.startedAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Theme Manager Hook Durumu", style = MaterialTheme.typography.titleMedium)
                        Text(hookRuntimeStatus.message, style = MaterialTheme.typography.bodyMedium)
                        if (hookRuntimeStatus.state == HookRuntimeStatus.State.STALE) {
                            Text(
                                "Yeni APK kurulsa bile çalışan Theme Manager süreci eski LSPosed kodunu bellekte tutabilir. Uygulama Bilgisi'nden Zorla Durdur yapıp Theme Manager'ı yeniden açın.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onOpenThemeManagerSettings
                            ) {
                                Text("Theme Manager Uygulama Bilgisini Aç")
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            runCatching { DiagnosticsSessionClient.export(context) }
                                .onSuccess(onShare)
                                .onFailure { status = "Rapor oluşturulamadı: ${it.message}" }
                        }
                    ) {
                        Text("Raporu Paylaş")
                    }
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            runCatching { DiagnosticsSessionClient.clearAndRestart(context) }
                                .onSuccess {
                                    snapshot = it
                                    hookRuntimeStatus = evaluateHookRuntime(it?.text.orEmpty())
                                    status = "Kayıt temizlendi; yeni oturum otomatik başladı."
                                }
                                .onFailure { status = "Kayıt temizlenemedi: ${it.message}" }
                        }
                    ) {
                        Text("Temizle")
                    }
                }
            }

            item {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onOpenAdvanced
                ) {
                    Text("Gelişmiş Diagnostics Araçları")
                }
            }

            item {
                Text("Canlı Zaman Çizelgesi", style = MaterialTheme.typography.titleMedium)
            }

            val visibleLines = snapshot?.text
                .orEmpty()
                .lineSequence()
                .filter { it.isNotBlank() }
                .takeLastLines(160)

            if (visibleLines.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Henüz olay yok. Uygulamayı kullanmaya devam edin; kayıt otomatik olarak burada görünecek.",
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(visibleLines) { line ->
                    SelectionContainer {
                        Text(
                            text = line,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

private fun evaluateHookRuntime(text: String): HookRuntimeStatus {
    if (text.isBlank()) {
        return HookRuntimeStatus(
            HookRuntimeStatus.State.WAITING,
            "Theme Manager henüz bu tanılama oturumuna bağlanmadı."
        )
    }

    val hasThemeManagerEvents = text.contains("ThemeManager.ApplyDiagnostics")
    val hasRightsPairHook =
        text.contains("HOOK_INSTALLED | com.android.thememanager.controller.online.a#b(") ||
            text.contains("RIGHTS_PAIR")
    val hasRightsMapHook =
        text.contains("HOOK_INSTALLED | com.android.thememanager.controller.online.a#c(") ||
            text.contains("RIGHTS_MAP")
    val hasDrmHook =
        text.contains("HOOK_INSTALLED | com.android.thememanager.controller.online.a#d(") ||
            text.contains("DRM_RESULT")

    return when {
        hasRightsPairHook && hasRightsMapHook && hasDrmHook -> HookRuntimeStatus(
            HookRuntimeStatus.State.READY,
            "Güncel rights/DRM hook seti aktif. RIGHTS_PAIR, RIGHTS_MAP ve DRM_RESULT izlenebilir."
        )
        hasThemeManagerEvents -> HookRuntimeStatus(
            HookRuntimeStatus.State.STALE,
            "Theme Manager çalışıyor fakat güncel rights/DRM hook seti yüklenmemiş. Hedef süreç eski LSPosed modül kodunu bellekte tutuyor."
        )
        else -> HookRuntimeStatus(
            HookRuntimeStatus.State.WAITING,
            "Theme Manager hook bağlantısı bekleniyor. Theme Manager açıldığında durum otomatik güncellenecek."
        )
    }
}

private fun Sequence<String>.takeLastLines(maxLines: Int): List<String> {
    val buffer = ArrayDeque<String>(maxLines)
    for (line in this) {
        if (buffer.size == maxLines) buffer.removeFirst()
        buffer.addLast(line)
    }
    return buffer.toList()
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(epochMs))
