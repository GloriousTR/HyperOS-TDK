package com.glorious.hyperostdk.privileged

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.glorious.hyperostdk.DiagnosticsSessionClient
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Build 34 strict local-resource route.
 *
 * Keeps build 33 metadata behavior unchanged and adds post-crash diagnostics only.
 * This lets the next device test capture the actual Theme Manager exception/exit reason
 * instead of changing metadata again without evidence.
 */
object StrictLocalThemeRoute {
    suspend fun installAndOpen(
        context: Context,
        displayName: String,
        sourceUri: Uri
    ): PrivilegedThemeEngine.InstallResult {
        val appContext = context.applicationContext
        DiagnosticsSessionClient.append(
            appContext,
            "STRICT_LOCAL_ROUTE_STARTED",
            "displayName=$displayName • build=34 • strategy=build33-metadata+crash-capture"
        )

        val initial = ThemeKitCompatInstaller.installAndOpen(
            context = context,
            displayName = displayName,
            sourceUri = sourceUri,
            requestAutomaticApply = false
        )

        val patch = patchGeneratedMetadata(appContext, initial.localId)
        DiagnosticsSessionClient.append(
            appContext,
            if (patch.success) "STRICT_METADATA_PATCH_SUCCESS" else "STRICT_METADATA_PATCH_WARNING",
            "localId=${initial.localId} • patched=${patch.patchedCount} • subResources=${patch.subResourceCount} • ${patch.detail}",
            level = if (patch.success) "INFO" else "WARN"
        )

        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("ViewLocalResource://view.local.resource#${initial.localId}")
        ).apply {
            setPackage(THEME_MANAGER_PACKAGE)
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra("REQUEST_RESOURCE_CODE", "theme")
            putExtra("REQUEST_APPLY_EVENT", false)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
        DiagnosticsSessionClient.append(
            appContext,
            "STRICT_LOCAL_RESOURCE_RELAUNCHED",
            "localId=${initial.localId} • REQUEST_APPLY_EVENT=false • build=34"
        )

        capturePostLaunchTimeline(appContext, initial.localId)
        captureCrashEvidence(appContext, initial.localId)
        return initial
    }

    private data class PatchResult(
        val success: Boolean,
        val patchedCount: Int,
        val subResourceCount: Int,
        val detail: String
    )

    private suspend fun patchGeneratedMetadata(context: Context, mainLocalId: String): PatchResult {
        val mainRemote = "$REMOTE_DATA_ROOT/meta/theme/$mainLocalId.mrm"
        val mainRead = ShizukuBridge.exec(context, "cat ${shellQuote(mainRemote)}")
        if (!mainRead.success || mainRead.output.isBlank()) {
            return PatchResult(false, 0, 0, "main MRM read failed: ${mainRead.output.take(500)}")
        }

        val main = runCatching { JSONObject(mainRead.output.trim()) }.getOrElse {
            return PatchResult(false, 0, 0, "main MRM JSON parse failed: ${it.message}")
        }

        val refs = main.optJSONArray("subResources") ?: JSONArray()
        var patched = 0

        if (patchOneMetadata(context, mainRemote, main, mainLocalId, "theme")) {
            patched++
        }

        for (index in 0 until refs.length()) {
            val ref = refs.optJSONObject(index) ?: continue
            val localId = ref.optString("localId")
            val resourceCode = ref.optString("resourceCode")
            if (!SAFE_LOCAL_ID.matches(localId) || !SAFE_RESOURCE_CODE.matches(resourceCode)) continue

            val remote = "$REMOTE_DATA_ROOT/meta/$resourceCode/$localId.mrm"
            val read = ShizukuBridge.exec(context, "cat ${shellQuote(remote)}")
            if (!read.success || read.output.isBlank()) continue
            val json = runCatching { JSONObject(read.output.trim()) }.getOrNull() ?: continue
            if (patchOneMetadata(context, remote, json, localId, resourceCode)) patched++
        }

        val verify = ShizukuBridge.exec(
            context,
            "cat ${shellQuote(mainRemote)} | grep -o '\"version\":\"1\"' || true; " +
                "cat ${shellQuote(mainRemote)} | grep -o '\"price\":-1' || true; " +
                "cat ${shellQuote(mainRemote)} | grep -o '\"miuiAdapterVersion\":\"4.0\"' || true"
        )

        return PatchResult(
            success = patched > 0,
            patchedCount = patched,
            subResourceCount = refs.length(),
            detail = "mainVerify=${verify.output.replace('\n', ' ').trim().take(500)} • previewPolicy=builtInPreviews:=builtInThumbnails"
        )
    }

    private suspend fun patchOneMetadata(
        context: Context,
        remotePath: String,
        json: JSONObject,
        localId: String,
        resourceCode: String
    ): Boolean {
        json.put("version", "1")
        json.put("price", -1)
        json.put("updatedTime", 0)

        if (json.optInt("platform", -1) == 17) {
            json.put("miuiAdapterVersion", "4.0")
        }

        val thumbnails = json.optJSONObject("builtInThumbnails")
            ?: JSONObject().put("fallback", JSONArray())
        json.put("builtInPreviews", JSONObject(thumbnails.toString()))

        val localDir = context.getExternalFilesDir("strict-local-build34") ?: return false
        localDir.mkdirs()
        val localFile = File(localDir, "$resourceCode-$localId.mrm")
        localFile.writeText(json.toString(), Charsets.UTF_8)

        val tmpRemote = "$remotePath.hyperos-tdk-build34.tmp"
        val write = ShizukuBridge.exec(
            context,
            "set -e; cp -f ${shellQuote(localFile.absolutePath)} ${shellQuote(tmpRemote)}; " +
                "mv -f ${shellQuote(tmpRemote)} ${shellQuote(remotePath)}; test -s ${shellQuote(remotePath)}"
        )
        return write.success
    }

    private suspend fun capturePostLaunchTimeline(context: Context, localId: String) {
        val targetsMs = listOf(100L, 300L, 700L, 1_500L, 2_500L, 3_500L)
        var elapsed = 0L
        for (target in targetsMs) {
            delay(target - elapsed)
            elapsed = target
            val sample = runCatching {
                ShizukuBridge.exec(
                    context,
                    "printf 'pid='; pidof $THEME_MANAGER_PACKAGE 2>/dev/null || true; " +
                        "printf ' top='; dumpsys activity activities 2>/dev/null | grep -m 1 'topResumedActivity=' || true"
                )
            }.getOrNull()
            DiagnosticsSessionClient.append(
                context,
                "STRICT_LOCAL_ACTIVITY_SAMPLE",
                "t=${target}ms • localId=$localId • ${sample?.output?.replace('\n', ' ')?.trim()?.take(1800) ?: "probe-unavailable"}"
            )
        }
    }

    private suspend fun captureCrashEvidence(context: Context, localId: String) {
        // Give Android's crash/exit bookkeeping a short moment to settle after the 3.5 s sample.
        delay(700L)

        val crashBuffer = runCatching {
            ShizukuBridge.exec(
                context,
                "logcat -b crash -d -v threadtime -t 220 2>/dev/null | tail -n 180"
            )
        }.getOrNull()
        DiagnosticsSessionClient.append(
            context,
            "THEME_MANAGER_CRASH_BUFFER",
            "localId=$localId • " +
                crashBuffer?.output?.trim()?.take(14000).orEmpty().ifBlank { "no-output" }
        )

        val exitInfo = runCatching {
            ShizukuBridge.exec(
                context,
                "dumpsys activity exit-info $THEME_MANAGER_PACKAGE 2>/dev/null | head -n 260"
            )
        }.getOrNull()
        DiagnosticsSessionClient.append(
            context,
            "THEME_MANAGER_EXIT_INFO",
            "localId=$localId • " +
                exitInfo?.output?.trim()?.take(14000).orEmpty().ifBlank { "no-output" }
        )

        val fatalWindow = runCatching {
            ShizukuBridge.exec(
                context,
                "logcat -d -v threadtime -b main -b system -b crash -t 1200 2>/dev/null | " +
                    "grep -E 'FATAL EXCEPTION|AndroidRuntime|Process: com.android.thememanager|Caused by:|ThemeDetailActivity|ViewLocalResource|com.android.thememanager' | tail -n 260"
            )
        }.getOrNull()
        DiagnosticsSessionClient.append(
            context,
            "THEME_MANAGER_FATAL_WINDOW",
            "localId=$localId • " +
                fatalWindow?.output?.trim()?.take(16000).orEmpty().ifBlank { "no-output" }
        )
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private const val THEME_MANAGER_PACKAGE = "com.android.thememanager"
    private const val REMOTE_DATA_ROOT = "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/theme/.data"
    private val SAFE_LOCAL_ID = Regex("[A-Za-z0-9._-]{1,128}")
    private val SAFE_RESOURCE_CODE = Regex("[A-Za-z0-9._-]{1,128}")
}
