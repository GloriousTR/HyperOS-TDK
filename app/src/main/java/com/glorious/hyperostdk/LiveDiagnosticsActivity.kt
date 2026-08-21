package com.glorious.hyperostdk

import android.content.Intent
import android.os.Bundle
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
                    onOpenAdvanced = { startActivity(Intent(this, MainActivity::class.java)) }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveDiagnosticsScreen(
    onShare: (File) -> Unit,
    onOpenAdvanced: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var snapshot by remember { mutableStateOf<DiagnosticsSessionClient.Snapshot?>(null) }
    var status by remember { mutableStateOf("Canlı tanılama oturumu hazırlanıyor…") }

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
