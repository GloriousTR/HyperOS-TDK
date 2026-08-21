package com.glorious.hyperostdk.privileged

import android.content.Context
import android.net.Uri
import com.glorious.hyperostdk.DiagnosticsSessionClient
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object DirectThemeApplyEngine {
    enum class Route {
        DIRECT_COMPONENT,
        LOCAL_RESOURCE_FALLBACK
    }

    data class ApplyResult(
        val route: Route,
        val snapshotBytes: Long,
        val sha1: String,
        val component: String?,
        val fallbackLocalId: String?
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
            "DIRECT_APPLY_STARTED",
            "name=$displayName • backend=${capability.state.backend} • uid=${capability.state.serverUid} • build=33"
        )

        val localDir = appContext.getExternalFilesDir("direct-apply-build33")
            ?: error("External files directory is unavailable")
        localDir.mkdirs()
        val localMtz = File(localDir, "snapshot-source.mtz")
        val sha1 = copyUriAndSha1(appContext, sourceUri, localMtz)
        check(localMtz.length() > 0L) { "Selected MTZ is empty" }

        DiagnosticsSessionClient.append(
            appContext,
            "DIRECT_APPLY_SOURCE_STAGED",
            "bytes=${localMtz.length()} • sha1=$sha1 • source=${localMtz.absolutePath}"
        )

        val inventory = runCatching {
            ShizukuBridge.exec(
                appContext,
                "dumpsys package $THEME_MANAGER_PACKAGE 2>/dev/null | grep -i -m 80 -E 'ApplyTheme|Screenshot|ThemeTabActivity|ThemeDetailActivity' || true"
            )
        }.getOrNull()?.output.orEmpty()

        DiagnosticsSessionClient.append(
            appContext,
            "DIRECT_APPLY_COMPONENT_INVENTORY",
            inventory.replace('\n', ' ').trim().take(5000).ifBlank { "no-output" }
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
            "Snapshot copy failed (exit=${copyResult.exitCode}): ${copyResult.output.take(1200)}"
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
            "DIRECT_APPLY_SNAPSHOT_COPY_SUCCESS",
            "path=$SNAPSHOT_PATH • bytes=$remoteBytes • sha1=$sha1"
        )

        val candidates = buildCandidates(inventory)
        DiagnosticsSessionClient.append(
            appContext,
            "DIRECT_APPLY_CANDIDATES",
            candidates.joinToString(" • ").ifBlank { "none" }
        )

        for (component in candidates) {
            val launch = ShizukuBridge.exec(appContext, buildAmStartCommand(component))
            val combined = launch.output.replace('\n', ' ').trim()
            val accepted = launch.success &&
                !combined.contains("Error type", ignoreCase = true) &&
                !combined.contains("does not exist", ignoreCase = true) &&
                !combined.contains("unable to resolve", ignoreCase = true) &&
                !combined.contains("Activity class", ignoreCase = true)

            DiagnosticsSessionClient.append(
                appContext,
                "DIRECT_APPLY_CANDIDATE_RESULT",
                "component=$component • exit=${launch.exitCode} • accepted=$accepted • output=${combined.take(1800)}",
                level = if (accepted) "INFO" else "WARN"
            )

            if (accepted) {
                DiagnosticsSessionClient.append(
                    appContext,
                    "DIRECT_APPLY_COMPONENT_LAUNCHED",
                    "component=$component • theme_file_path=$SNAPSHOT_PATH_FOR_INTENT"
                )
                return ApplyResult(
                    route = Route.DIRECT_COMPONENT,
                    snapshotBytes = remoteBytes,
                    sha1 = sha1,
                    component = component,
                    fallbackLocalId = null
                )
            }
        }

        DiagnosticsSessionClient.append(
            appContext,
            "DIRECT_APPLY_COMPONENT_UNAVAILABLE",
            "No compatible direct-apply activity is exposed by this Theme Manager build; switching to strict Local Resource route.",
            level = "WARN"
        )

        DiagnosticsSessionClient.append(
            appContext,
            "DIRECT_APPLY_FALLBACK_CONTEXT",
            "context=${context.javaClass.name} • applicationContext=${appContext.javaClass.name} • build=33 • strictMetadata=true"
        )

        val fallback = StrictLocalThemeRoute.installAndOpen(
            context = context,
            displayName = displayName,
            sourceUri = sourceUri
        )

        DiagnosticsSessionClient.append(
            appContext,
            "DIRECT_APPLY_FALLBACK_OPENED",
            "localId=${fallback.localId} • subResources=${fallback.subResourceCount} • automaticApply=false • strictMetadata=true • build=33"
        )

        return ApplyResult(
            route = Route.LOCAL_RESOURCE_FALLBACK,
            snapshotBytes = remoteBytes,
            sha1 = sha1,
            component = null,
            fallbackLocalId = fallback.localId
        )
    }

    private fun buildCandidates(inventory: String): List<String> {
        val result = LinkedHashSet<String>()

        // Only spend time on the direct route if this Theme Manager actually advertises an
        // apply/screenshot surface. The target ROM has already shown that it does not.
        if (inventory.contains("ApplyTheme", ignoreCase = true) || inventory.contains("Screenshot", ignoreCase = true)) {
            result += "$THEME_MANAGER_PACKAGE/com.android.thememanager.ApplyThemeForScreenshot"
            result += "$THEME_MANAGER_PACKAGE/com.android.thememanager.activity.ApplyThemeForScreenshot"
            result += "$THEME_MANAGER_PACKAGE/com.android.thememanager.activity.ApplyThemeForScreenshotActivity"
            result += "$THEME_MANAGER_PACKAGE/com.android.thememanager.ApplyThemeForScreenshotActivity"
        }

        val fullClassRegex = Regex("com\\.android\\.thememanager(?:\\.[A-Za-z0-9_]+)+")
        fullClassRegex.findAll(inventory).forEach { match ->
            val className = match.value
            if (className.contains("apply", ignoreCase = true) || className.contains("screenshot", ignoreCase = true)) {
                result += "$THEME_MANAGER_PACKAGE/$className"
            }
        }

        val shortActivityRegex = Regex("\\.activity\\.[A-Za-z0-9_]+")
        shortActivityRegex.findAll(inventory).forEach { match ->
            val className = THEME_MANAGER_PACKAGE + match.value
            if (className.contains("apply", ignoreCase = true) || className.contains("screenshot", ignoreCase = true)) {
                result += "$THEME_MANAGER_PACKAGE/$className"
            }
        }

        return result.toList()
    }

    private fun buildAmStartCommand(component: String): String = buildString {
        append("am start -W -n ").append(shellQuote(component))
        append(" --es theme_file_path ").append(shellQuote(SNAPSHOT_PATH_FOR_INTENT))
        append(" --es ver2_step ").append(shellQuote("ver2_step_apply"))
        append(" --es api_called_from ").append(shellQuote("com.miui.themestore"))
        append(" --ei theme_apply_flags 1")
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
    private const val SNAPSHOT_PATH = "/storage/emulated/0/Android/data/com.android.thememanager/files/snapshot/snapshot.mtz"
    private const val SNAPSHOT_TMP_PATH = "$SNAPSHOT_PATH.hyperos-tdk-build33.tmp"
    private const val SNAPSHOT_PATH_FOR_INTENT = "/sdcard/Android/data/com.android.thememanager/files/snapshot/snapshot.mtz"
}
