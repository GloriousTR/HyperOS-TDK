package com.glorious.hyperostdk

import android.content.ContentResolver
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.glorious.hyperostdk.ui.theme.HyperOSTDKTheme
import kotlinx.coroutines.delay
import java.util.UUID

private const val TAG = "HyperOS-TDK-App"
private const val TARGET_PACKAGE = "com.android.thememanager"
private const val URI_GRANT_REVOKE_DELAY_MS = 60_000L

private data class SelectedMtz(
    val displayName: String,
    val uri: Uri
)

private data class ImportResult(
    val requestId: String,
    val status: String,
    val message: String,
    val resultAt: Long
)

class ModuleStatusActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HyperOSTDKTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ControlledImportScreen(
                        onOpenThemeManager = {
                            val launchIntent = packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
                            if (launchIntent != null) {
                                startActivity(launchIntent)
                                true
                            } else {
                                false
                            }
                        },
                        onSendImport = { selected -> publishImportCommand(selected) },
                        onGetResult = { requestId -> readImportResult(requestId) },
                        onOpenDiagnostics = {
                            startActivity(Intent(this, MainActivity::class.java))
                        }
                    )
                }
            }
        }
    }

    private fun publishImportCommand(selected: SelectedMtz): Result<String> = runCatching {
        val requestId = UUID.randomUUID().toString()
        val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION

        grantUriPermission(TARGET_PACKAGE, selected.uri, readFlag)
        val targetUid = packageManager.getApplicationInfo(TARGET_PACKAGE, 0).uid
        val grantCheck = checkUriPermission(selected.uri, -1, targetUid, readFlag)
        if (grantCheck != PackageManager.PERMISSION_GRANTED) {
            revokeUriPermission(TARGET_PACKAGE, selected.uri, readFlag)
            throw SecurityException("Theme Manager URI grant verification failed")
        }

        Log.i(
            TAG,
            "CONTROLLED IMPORT sender URI grant verified: request=$requestId targetUid=$targetUid uri=${selected.uri}"
        )

        val command = Bundle().apply {
            putString(ImportControlProvider.KEY_REQUEST_ID, requestId)
            putString(ImportControlProvider.KEY_DISPLAY_NAME, selected.displayName)
            putString(ImportControlProvider.KEY_URI, selected.uri.toString())
            putLong(ImportControlProvider.KEY_CREATED_AT, System.currentTimeMillis())
        }

        val reply = contentResolver.call(
            ImportControlProvider.AUTHORITY,
            ImportControlProvider.METHOD_PUBLISH,
            null,
            command
        )
        if (reply?.getBoolean("accepted", false) != true) {
            revokeUriPermission(TARGET_PACKAGE, selected.uri, readFlag)
            throw IllegalStateException("Import control provider rejected the command")
        }

        Log.i(TAG, "CONTROLLED IMPORT provider command published: request=$requestId")

        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                revokeUriPermission(TARGET_PACKAGE, selected.uri, readFlag)
                Log.i(TAG, "CONTROLLED IMPORT sender URI grant revoked: request=$requestId")
            }.onFailure {
                Log.w(TAG, "Unable to revoke Theme Manager URI grant: request=$requestId", it)
            }
        }, URI_GRANT_REVOKE_DELAY_MS)

        requestId
    }

    private fun readImportResult(requestId: String): ImportResult? {
        return runCatching {
            val reply = contentResolver.call(
                ImportControlProvider.AUTHORITY,
                ImportControlProvider.METHOD_GET_RESULT,
                requestId,
                null
            ) ?: return@runCatching null

            if (!reply.getBoolean(ImportControlProvider.KEY_PRESENT, false)) {
                return@runCatching null
            }
            ImportResult(
                requestId = reply.getString(ImportControlProvider.KEY_REQUEST_ID).orEmpty(),
                status = reply.getString(ImportControlProvider.KEY_STATUS).orEmpty(),
                message = reply.getString(ImportControlProvider.KEY_MESSAGE).orEmpty(),
                resultAt = reply.getLong(ImportControlProvider.KEY_RESULT_AT, 0L)
            )
        }.onFailure {
            Log.w(TAG, "Unable to read import result: request=$requestId", it)
        }.getOrNull()
    }
}

@Composable
private fun ControlledImportScreen(
    onOpenThemeManager: () -> Boolean,
    onSendImport: (SelectedMtz) -> Result<String>,
    onGetResult: (String) -> ImportResult?,
    onOpenDiagnostics: () -> Unit
) {
    val context = LocalContext.current
    var selectedMtz by remember { mutableStateOf<SelectedMtz?>(null) }
    var showConfirmation by remember { mutableStateOf(false) }
    var activeRequestId by remember { mutableStateOf<String?>(null) }
    var status by remember {
        mutableStateOf(
            "v${BuildConfig.VERSION_NAME} hazır. Theme Manager import yaşam döngüsü doğrudan izlenir ve sonuç bu ekranda gösterilir."
        )
    }

    LaunchedEffect(activeRequestId) {
        val requestId = activeRequestId ?: return@LaunchedEffect
        repeat(60) {
            delay(500)
            val result = onGetResult(requestId) ?: return@repeat
            status = importResultText(result)
            if (result.status in setOf(
                    ImportControlProvider.STATUS_COMPLETE,
                    ImportControlProvider.STATUS_FAIL,
                    ImportControlProvider.STATUS_QUEUE_ERROR
                )
            ) {
                activeRequestId = null
                return@LaunchedEffect
            }
        }
        if (activeRequestId == requestId) {
            status = "Import isteği gönderildi fakat 30 saniye içinde terminal sonuç alınamadı. Vector logu ile kontrol edin. Request ID: $requestId"
            activeRequestId = null
        }
    }

    val mtzPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val displayName = queryDisplayName(context.contentResolver, uri)
            ?: uri.lastPathSegment
            ?: "seçili dosya"

        when {
            !displayName.endsWith(".mtz", ignoreCase = true) -> {
                selectedMtz = null
                status = "Seçilen dosya .mtz uzantılı değil: $displayName"
            }
            uri.scheme != ContentResolver.SCHEME_CONTENT -> {
                selectedMtz = null
                status = "Bu test için Android belge sağlayıcısından gelen content:// MTZ URI'si gerekiyor."
            }
            else -> {
                selectedMtz = SelectedMtz(displayName, uri)
                status = "MTZ hazır. Tema Yöneticisini açın; sonra geri dönüp Kontrollü Import Başlat'a basın."
            }
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
        Text(
            text = "HyperOS TDK • v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Theme Tools • MTZ Import",
            style = MaterialTheme.typography.titleLarge
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Çalışan v0.2.5 import motoru korunmuştur. v0.3.0 bu özelliği HyperOS TDK içindeki Theme Tools bölümüne taşır.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Theme Manager'ın start / complete / fail yaşam döngüsü doğrudan izlenir ve terminal sonuç uygulamaya geri taşınır.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "'dispatch error not online resource' yerel MTZ'nin onlineId taşımamasına ait online-dispatch mesajıdır; tek başına import başarısızlığı değildir.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                status = if (onOpenThemeManager()) {
                    "Tema Yöneticisi açıldı. Provider observer ve lifecycle hook'ları hazırlandıktan sonra HyperOS TDK'ya geri dönün."
                } else {
                    "Tema Yöneticisi launcher activity bulunamadı."
                }
            }
        ) {
            Text("1. Tema Yöneticisini Aç")
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = { mtzPicker.launch(arrayOf("*/*")) }
        ) {
            Text("2. MTZ Seç")
        }

        selectedMtz?.let { selected ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Seçilen MTZ", style = MaterialTheme.typography.labelLarge)
                    Text(selected.displayName)
                    Text(
                        selected.uri.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = selectedMtz != null && activeRequestId == null,
            onClick = { showConfirmation = true }
        ) {
            Text(if (activeRequestId == null) "3. Kontrollü Import Başlat" else "Import sonucu bekleniyor…")
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = status,
                modifier = Modifier.padding(14.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }

        Spacer(Modifier.height(2.dp))
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onOpenDiagnostics
        ) {
            Text("Diagnostics'i Aç")
        }
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("Gerçek MTZ import denemesi") },
            text = {
                Text(
                    "Seçili MTZ için Theme Manager'a geçici URI okuma izni verilecek. Tek-seferlik provider komutu tüketildikten sonra private import kuyruğu gerçek olarak çağrılacak ve sonuç bu ekranda gösterilecek. Devam etmek istiyor musunuz?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmation = false
                        selectedMtz?.let { selected ->
                            onSendImport(selected)
                                .onSuccess { requestId ->
                                    activeRequestId = requestId
                                    status = "Provider komutu yayınlandı. Theme Manager sonucu bekleniyor… Request ID: $requestId"
                                }
                                .onFailure { error ->
                                    status = "Import komutu yayınlanamadı: ${error.javaClass.simpleName}: ${error.message}"
                                }
                        }
                    }
                ) {
                    Text("Import Et")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) {
                    Text("İptal")
                }
            }
        )
    }
}

private fun importResultText(result: ImportResult): String {
    return when (result.status) {
        ImportControlProvider.STATUS_QUEUED ->
            "MTZ Theme Manager import kuyruğuna alındı. İşlem sonucu bekleniyor…"
        ImportControlProvider.STATUS_START ->
            "Theme Manager MTZ import işlemini başlattı. Sonuç bekleniyor…"
        ImportControlProvider.STATUS_COMPLETE ->
            "Import tamamlandı. Theme Manager yerel kaynağı başarıyla oluşturdu. Request ID: ${result.requestId}"
        ImportControlProvider.STATUS_FAIL ->
            "Theme Manager import işlemini başarısız olarak tamamladı. ${result.message} Request ID: ${result.requestId}"
        ImportControlProvider.STATUS_QUEUE_ERROR ->
            "Import kuyruğa alınamadı: ${result.message} Request ID: ${result.requestId}"
        else -> "Import durumu: ${result.status}. ${result.message}"
    }
}

private fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String? {
    return runCatching {
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
}
