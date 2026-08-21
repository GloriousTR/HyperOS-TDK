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
 * Build 33 strict local-resource route.
 *
 * The existing local-resource installer is kept intact so we can compare device behavior.
 * After its first launch, this route rewrites only the generated local metadata owned by
 * HyperOS-TDK to match the stricter Theme Manager schema observed in the working reference:
 * version=1, price=-1, platform-17 adapter=4.0 and builtInPreviews=builtInThumbnails.
 * It then relaunches the same localId and captures a short activity/process timeline.
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
            "displayName=$displayName • build=33 • strategy=metadata-patch-and-relaunch"
        )

        // First create/stage the local resource using the proven build-32 storage pipeline.
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

        // Relaunch after the metadata rewrite. This is the launch we care about in build 33.
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
            "localId=${initial.localId} • REQUEST_APPLY_EVENT=false • build=33"
        )

        capturePostLaunchTimeline(appContext, initial.localId)
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
            "cat ${shellQuote(mainRemote)} | grep -o '\"version\":\"1\"\|\"price\":-1\|\"miuiAdapterVersion\":\"4.0\"' | tr '\\n' ' '"
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

        // On the target MTZ no filename contains "small", therefore the reference's
        // small-preview list is empty. Copying builtInThumbnails exactly preserves that rule
        // without guessing preview names.
        val thumbnails = json.optJSONObject("builtInThumbnails")
            ?: JSONObject().put("fallback", JSONArray())
        json.put("builtInPreviews", JSONObject(thumbnails.toString()))

        val localDir = context.getExternalFilesDir("strict-local-build33") ?: return false
        localDir.mkdirs()
        val localFile = File(localDir, "$resourceCode-$localId.mrm")
        localFile.writeText(json.toString(), Charsets.UTF_8)

        val tmpRemote = "$remotePath.hyperos-tdk-build33.tmp"
        val write = ShizukuBridge.exec(
            context,
            "set -e; cp -f ${shellQuote(localFile.absolutePath)} ${shellQuote(tmpRemote)}; " +
                "mv -f ${shellQuote(tmpRemote)} ${shellQuote(remotePath)}; test -s ${shellQuote(remotePath)}"
        )
        return write.success
    }

    private suspend fun capturePostLaunchTimeline(context: Context, localId: String) {
        val targetsMs = listOf(100L, 300L, 700L, 1_500L, 3_000L)
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
                "t=${target}ms • localId=$localId • ${sample?.output?.replace('\n', ' ')?.trim()?.take(1600) ?: "probe-unavailable"}"
            )
        }

        val logs = runCatching {
            ShizukuBridge.exec(
                context,
                "logcat -d -v threadtime -t 350 2>/dev/null | " +
                    "grep -E 'AndroidRuntime|ThemeDetailActivity|com.android.thememanager|ViewLocalResource' | tail -n 120"
            )
        }.getOrNull()
        DiagnosticsSessionClient.append(
            context,
            "STRICT_LOCAL_POST_LAUNCH_LOGCAT",
            logs?.output?.replace('\n', ' ')?.trim()?.take(7000).orEmpty().ifBlank { "no-output" }
        )
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private const val THEME_MANAGER_PACKAGE = "com.android.thememanager"
    private const val REMOTE_DATA_ROOT = "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/theme/.data"
    private val SAFE_LOCAL_ID = Regex("[A-Za-z0-9._-]{1,128}")
    private val SAFE_RESOURCE_CODE = Regex("[A-Za-z0-9._-]{1,128}")
}
