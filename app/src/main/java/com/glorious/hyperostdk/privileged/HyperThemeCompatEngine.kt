package com.glorious.hyperostdk.privileged

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.glorious.hyperostdk.DiagnosticsSessionClient
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Build 30 reference path reconstructed from HyperTheme 1.1.17 (38).
 *
 * HyperTheme does not need ThemeDetailActivity for its direct-apply path. It copies the
 * original MTZ to Theme Manager's snapshot slot, then launches ApplyThemeForScreenshot
 * with the internal extras Theme Manager expects from com.miui.themestore.
 */
object HyperThemeCompatEngine {
    data class ApplyResult(
        val snapshotBytes: Long,
        val sha1: String,
        val activityProbe: String
    )

    suspend fun apply(
        context: Context,
        displayName: String,
        sourceUri: Uri
    ): ApplyResult {
        val appContext = context.applicationContext
        val capability = PrivilegedThemeEngine.probe(appContext)
        check(capability.ready) { capability.detail }

        DiagnosticsSessionClient.append(
            appContext,
            "HYPERTHEME_REFERENCE_APPLY_STARTED",
            "name=$displayName • backend=${capability.state.backend} • uid=${capability.state.serverUid} • reference=HyperTheme-1.1.17(38)"
        )

        val localDir = appContext.getExternalFilesDir("hypertheme-build30")
            ?: error("External files directory is unavailable")
        localDir.mkdirs()
        val localMtz = File(localDir, "snapshot-source.mtz")
        val sha1 = copyUriAndSha1(appContext, sourceUri, localMtz)
        check(localMtz.length() > 0L) { "Selected MTZ is empty" }

        DiagnosticsSessionClient.append(
            appContext,
            "HYPERTHEME_SOURCE_STAGED",
            "bytes=${localMtz.length()} • sha1=$sha1 • source=${localMtz.absolutePath}"
        )

        val componentProbe = runCatching {
            ShizukuBridge.exec(
                appContext,
                "dumpsys package $THEME_MANAGER_PACKAGE 2>/dev/null | grep -m 4 -E 'ApplyThemeForScreenshot|ThemeTabActivity' || true"
            )
        }.getOrNull()
        DiagnosticsSessionClient.append(
            appContext,
            "HYPERTHEME_APPLY_COMPONENT_PROBE",
            componentProbe?.output?.replace('\n', ' ')?.trim()?.take(1600).orEmpty().ifBlank { "no-output" }
        )

        val copyResult = ShizukuBridge.exec(
            appContext,
            buildString {
                appendLine("set -e")
                append("mkdir -p ").append(shellQuote(SNAPSHOT_PATH.substringBeforeLast('/'))).appendLine()
                append("cp -f ").append(shellQuote(localMtz.absolutePath)).append(' ').append(shellQuote(SNAPSHOT_TMP_PATH)).appendLine()
                append("mv -f ").append(shellQuote(SNAPSHOT_TMP_PATH)).append(' ').append(shellQuote(SNAPSHOT_PATH)).appendLine()
                append("test -s ").append(shellQuote(SNAPSHOT_PATH)).appendLine()
                append("printf 'bytes='; wc -c < ").append(shellQuote(SNAPSHOT_PATH)).appendLine()
            }
        )
        check(copyResult.success) {
            "HyperTheme snapshot copy failed (exit=${copyResult.exitCode}): ${copyResult.output.take(1200)}"
        }

        val remoteBytes = copyResult.output
            .substringAfter("bytes=", "")
            .trim()
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.toLongOrNull()
            ?: localMtz.length()

        DiagnosticsSessionClient.append(
            appContext,
            "HYPERTHEME_SNAPSHOT_COPY_SUCCESS",
            "path=$SNAPSHOT_PATH • bytes=$remoteBytes • sha1=$sha1"
        )

        val intent = Intent().apply {
            setClassName(THEME_MANAGER_PACKAGE, APPLY_ACTIVITY)
            putExtra("theme_file_path", SNAPSHOT_PATH_FOR_INTENT)
            putExtra("ver2_step", "ver2_step_apply")
            putExtra("api_called_from", "com.miui.themestore")
            putExtra("theme_apply_flags", 1)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            appContext.startActivity(intent)
        } catch (error: Throwable) {
            DiagnosticsSessionClient.append(
                appContext,
                "HYPERTHEME_DIRECT_APPLY_LAUNCH_FAILED",
                "${error.javaClass.simpleName}: ${error.message}",
                level = "ERROR"
            )
            throw error
        }

        DiagnosticsSessionClient.append(
            appContext,
            "HYPERTHEME_DIRECT_APPLY_INTENT_SENT",
            "component=$THEME_MANAGER_PACKAGE/$APPLY_ACTIVITY • theme_file_path=$SNAPSHOT_PATH_FOR_INTENT • ver2_step=ver2_step_apply • api_called_from=com.miui.themestore • theme_apply_flags=1"
        )

        delay(1_500)
        val activityProbe = runCatching {
            ShizukuBridge.exec(
                appContext,
                "dumpsys activity activities 2>/dev/null | grep -m 10 -E 'ApplyThemeForScreenshot|com.android.thememanager|topResumedActivity' || true"
            )
        }.getOrNull()?.output.orEmpty().replace('\n', ' ').trim()

        DiagnosticsSessionClient.append(
            appContext,
            "HYPERTHEME_DIRECT_APPLY_ACTIVITY_PROBE",
            activityProbe.take(2400).ifBlank { "no-output" }
        )

        // Capture a short post-launch window. This is diagnostic only and never rolls back
        // the snapshot/theme if Theme Manager leaves the foreground.
        delay(3_500)
        val postApply = runCatching {
            ShizukuBridge.exec(
                appContext,
                "printf 'pid='; pidof $THEME_MANAGER_PACKAGE 2>/dev/null || true; " +
                    "printf ' top='; dumpsys activity activities 2>/dev/null | grep -m 1 'topResumedActivity' || true; " +
                    "printf ' exit='; dumpsys activity exit-info $THEME_MANAGER_PACKAGE 2>/dev/null | head -n 18 | tr '\\n' ' '"
            )
        }.getOrNull()
        DiagnosticsSessionClient.append(
            appContext,
            "HYPERTHEME_DIRECT_APPLY_POSTCHECK",
            postApply?.output?.replace('\n', ' ')?.trim()?.take(3200).orEmpty().ifBlank { "no-output" }
        )

        return ApplyResult(remoteBytes, sha1, activityProbe)
    }

    private fun copyUriAndSha1(context: Context, uri: Uri, destination: File): String {
        destination.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-1")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected MTZ" }
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private const val THEME_MANAGER_PACKAGE = "com.android.thememanager"
    private const val APPLY_ACTIVITY = "com.android.thememanager.ApplyThemeForScreenshot"
    private const val SNAPSHOT_PATH = "/storage/emulated/0/Android/data/com.android.thememanager/files/snapshot/snapshot.mtz"
    private const val SNAPSHOT_TMP_PATH = "$SNAPSHOT_PATH.hyperos-tdk-build30.tmp"
    private const val SNAPSHOT_PATH_FOR_INTENT = "/sdcard/Android/data/com.android.thememanager/files/snapshot/snapshot.mtz"
}
