package com.glorious.hyperostdk

import android.content.ClipData
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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.glorious.hyperostdk.ui.theme.HyperOSTDKTheme
import java.util.UUID

private const val TAG = "HyperOS-TDK-App"
private const val TARGET_PACKAGE = "com.android.thememanager"
private const val ACTION_IMPORT_MTZ = "com.glorious.hyperostdk.action.IMPORT_MTZ"
private const val EXTRA_DISPLAY_NAME = "mtz_display_name"
private const val EXTRA_REQUEST_ID = "request_id"
private const val URI_GRANT_REVOKE_DELAY_MS = 60_000L

private data class SelectedMtz(
    val displayName: String,
    val uri: Uri
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
                        onSendImport = { selected ->
                            runCatching {
                                val requestId = UUID.randomUUID().toString()
                                val readFlag = Intent.FLAG_GRANT_READ_URI_PERMISSION

                                grantUriPermission(TARGET_PACKAGE, selected.uri, readFlag)
                                val targetUid = packageManager
                                    .getApplicationInfo(TARGET_PACKAGE, 0)
                                    .uid
                                val grantCheck = checkUriPermission(
                                    selected.uri,
                                    -1,
                                    targetUid,
                                    readFlag
                                )
                                if (grantCheck != PackageManager.PERMISSION_GRANTED) {
                                    revokeUriPermission(TARGET_PACKAGE, selected.uri, readFlag)
                                    throw SecurityException("Theme Manager URI grant verification failed")
                                }

                                Log.i(
                                    TAG,
                                    "CONTROLLED IMPORT sender URI grant verified: request=$requestId targetUid=$targetUid uri=${selected.uri}"
                                )

                                val intent = Intent(ACTION_IMPORT_MTZ).apply {
                                    setPackage(TARGET_PACKAGE)
                                    data = selected.uri
                                    clipData = ClipData.newRawUri("mtz", selected.uri)
                                    addFlags(readFlag)
                                    putExtra(EXTRA_DISPLAY_NAME, selected.displayName)
                                    putExtra(EXTRA_REQUEST_ID, requestId)
                                }

                                try {
                                    sendBroadcast(intent)
                                    Log.i(
                                        TAG,
                                        "CONTROLLED IMPORT broadcast sent: request=$requestId package=$TARGET_PACKAGE"
                                    )
                                } catch (error: Throwable) {
                                    revokeUriPermission(TARGET_PACKAGE, selected.uri, readFlag)
                                    throw error
                                }

                                Handler(Looper.getMainLooper()).postDelayed({
                                    runCatching {
                                        revokeUriPermission(TARGET_PACKAGE, selected.uri, readFlag)
                                        Log.i(
                                            TAG,
                                            "CONTROLLED IMPORT sender URI grant revoked: request=$requestId"
                                        )
                                    }.onFailure {
                                        Log.w(TAG, "Unable to revoke Theme Manager URI grant: request=$requestId", it)
                                    }
                                }, URI_GRANT_REVOKE_DELAY_MS)

                                requestId
                            }
                        },
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
private fun ControlledImportScreen(
    onOpenThemeManager: () -> Boolean,
    onSendImport: (SelectedMtz) -> Result<String>,
    onOpenDiagnostics: () -> Unit
) {
    val context = LocalContext.current
    var selectedMtz by remember { mutableStateOf<SelectedMtz?>(null) }
    var showConfirmation by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(
            "v0.2.3, import isteğini sender kimliği doğrulanan IPC ile gönderir ve seçilen content:// URI için Theme Manager'a açık okuma izni verir."
        )
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
                status = "MTZ hazır. Tema Yöneticisini açın; sonra geri dönüp Kontrollü Import Başlat'a basın. URI izni göndermeden önce ayrıca doğrulanacak."
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "HyperOS TDK • v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            text = "Controlled MTZ Import • IPC Delivery",
            style = MaterialTheme.typography.titleLarge
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Önceki v0.2.2 testinde URI bridge hazırlandı fakat kullanıcı onayından sonra Theme Manager receiver'ına import isteği ulaşmadı.",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "v0.2.3 seçilen content:// URI için Theme Manager paketine açık okuma izni verir, izni göndermeden önce doğrular ve IPC alıcısında Android'in gerçek sender package/UID bilgisini kontrol eder.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Import hâlâ yalnızca sizin seçim ve ikinci onayınızdan sonra çalışır. URI izni 60 saniye sonra geri alınır.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                status = if (onOpenThemeManager()) {
                    "Tema Yöneticisi açıldı. Modül bridge'i proses içinde hazırlandıktan sonra HyperOS TDK'ya geri dönün."
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
            enabled = selectedMtz != null,
            onClick = { showConfirmation = true }
        ) {
            Text("3. Kontrollü Import Başlat")
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
            Text("Eski Tanılama Araçlarını Aç")
        }
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("Gerçek MTZ import denemesi") },
            text = {
                Text(
                    "Seçili MTZ için Theme Manager'a geçici URI okuma izni verilecek; dosya Theme Manager private cache alanına staging edildikten sonra private import kuyruğuna gerçek olarak gönderilecek. Devam etmek istiyor musunuz?"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirmation = false
                        val selected = selectedMtz
                        if (selected != null) {
                            onSendImport(selected)
                                .onSuccess { requestId ->
                                    status = "IPC isteği gönderildi ve URI izni sender tarafında doğrulandı. Request ID: $requestId. Tekrar denemeden önce Theme Manager'ı kontrol edin ve Vector logunu dışa aktarın."
                                }
                                .onFailure { error ->
                                    status = "Import isteği Theme Manager'a gönderilemedi: ${error.javaClass.simpleName}: ${error.message}"
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
