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
 * Build 35 strict local-resource route.
 *
 * The build-34 crash proved Theme Manager is failing while its local-theme adapter parses the
 * staged resource tree (Expected BEGIN_OBJECT but was BEGIN_ARRAY). Build 35 removes the
 * build-33/34 race where metadata was rewritten while Theme Manager was already alive:
 * 1) create/install the resource tree,
 * 2) stop Theme Manager before any rewrite,
 * 3) rewrite every owned MRM to the strict reference schema,
 * 4) launch the local resource once and never mutate it afterwards.
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
            "displayName=$displayName • build=35 • strategy=stop-patch-single-final-launch"
        )

        // Re-use the proven staging/install path. It opens Theme Manager once internally; we
        // immediately stop that process before touching metadata, well before the ~3 s crash
        // window observed on build 33/34. The final launch below is the only launch that sees
        // the strict metadata tree.
        val initial = ThemeKitCompatInstaller.installAndOpen(
            context = context,
            displayName = displayName,
            sourceUri = sourceUri,
            requestAutomaticApply = false
        )

        val stop = ShizukuBridge.exec(
            appContext,
            "am force-stop $THEME_MANAGER_PACKAGE >/dev/null 2>&1 || true; sleep 0.25; " +
                "printf 'pid='; pidof $THEME_MANAGER_PACKAGE 2>/dev/null || true"
        )
        DiagnosticsSessionClient.append(
            appContext,
            "STRICT_PREPATCH_THEME_MANAGER_STOPPED",
            "localId=${initial.localId} • exit=${stop.exitCode} • ${stop.output.replace('\n', ' ').trim()}"
        )

        val patch = patchGeneratedMetadata(appContext, initial.localId)
        DiagnosticsSessionClient.append(
            appContext,
            if (patch.success) "STRICT_METADATA_PATCH_SUCCESS" else "STRICT_METADATA_PATCH_WARNING",
            "localId=${initial.localId} • patched=${patch.patchedCount} • subResources=${patch.subResourceCount} • ${patch.detail}",
            level = if (patch.success) "INFO" else "WARN"
        )

        val audit = auditMetadataTypes(appContext, initial.localId)
        DiagnosticsSessionClient.append(
            appContext,
            "STRICT_METADATA_TYPE_AUDIT",
            "localId=${initial.localId} • $audit"
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
            "localId=${initial.localId} • REQUEST_APPLY_EVENT=false • build=35 • metadataFrozen=true"
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

        if (patchOneMetadata(context, mainRemote, main, mainLocalId, "theme", isMain = true)) {
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
            if (patchOneMetadata(context, remote, json, localId, resourceCode, isMain = false)) patched++
        }

        val verify = ShizukuBridge.exec(
            context,
            "cat ${shellQuote(mainRemote)} | grep -o '\"version\":\"1\"' || true; " +
                "cat ${shellQuote(mainRemote)} | grep -o '\"price\":-1' || true; " +
                "cat ${shellQuote(mainRemote)} | grep -o '\"miuiAdapterVersion\":null' || true"
        )

        return PatchResult(
            success = patched == refs.length() + 1,
            patchedCount = patched,
            subResourceCount = refs.length(),
            detail = "mainVerify=${verify.output.replace('\n', ' ').trim().take(500)} • mainAdapter=null • subAdapter=4.0 • metadataFrozenBeforeLaunch=true"
        )
    }

    private suspend fun patchOneMetadata(
        context: Context,
        remotePath: String,
        json: JSONObject,
        localId: String,
        resourceCode: String,
        isMain: Boolean
    ): Boolean {
        json.put("version", "1")
        json.put("price", -1)
        json.put("updatedTime", 0)
        json.put("screenRatio", JSONObject.NULL)
        json.put("supportHomeSearchBar", false)
        json.put("packageVersion", JSONObject.NULL)
        json.put("packageName", JSONObject.NULL)
        json.put("officialIcons", false)
        json.put("iconsCount", JSONObject.NULL)
        json.put("isBackUpVersion", false)
        json.put("themeType", 0)

        if (isMain) {
            // The reference main-theme object leaves adapter null. Sub-resources carry 4.0.
            json.put("miuiAdapterVersion", JSONObject.NULL)
            val thumbnails = json.optJSONObject("builtInThumbnails")
                ?: JSONObject().put("fallback", JSONArray())
            json.put("builtInPreviews", JSONObject(thumbnails.toString()))
        } else {
            json.put("miuiAdapterVersion", "4.0")
            json.put("wallpaperStyle", 0)
            json.put("isSingleResource", false)

            // Sub-resource previews are the "small" subset of the resource thumbnails.
            val thumbObject = json.optJSONObject("builtInThumbnails")
                ?: JSONObject().put("fallback", JSONArray())
            val source = thumbObject.optJSONArray("fallback") ?: JSONArray()
            val small = JSONArray()
            for (i in 0 until source.length()) {
                val name = source.optString(i)
                if (name.contains("small", ignoreCase = true)) small.put(name)
            }
            json.put("builtInPreviews", JSONObject().put("fallback", small))
        }

        val localDir = context.getExternalFilesDir("strict-local-build35") ?: return false
        localDir.mkdirs()
        val localFile = File(localDir, "$resourceCode-$localId.mrm")
        localFile.writeText(json.toString(), Charsets.UTF_8)

        val tmpRemote = "$remotePath.hyperos-tdk-build35.tmp"
        val write = ShizukuBridge.exec(
            context,
            "set -e; cp -f ${shellQuote(localFile.absolutePath)} ${shellQuote(tmpRemote)}; " +
                "mv -f ${shellQuote(tmpRemote)} ${shellQuote(remotePath)}; test -s ${shellQuote(remotePath)}"
        )
        return write.success
    }

    private suspend fun auditMetadataTypes(context: Context, mainLocalId: String): String {
        val mainRemote = "$REMOTE_DATA_ROOT/meta/theme/$mainLocalId.mrm"
        val read = ShizukuBridge.exec(context, "cat ${shellQuote(mainRemote)}")
        val json = runCatching { JSONObject(read.output.trim()) }.getOrNull() ?: return "main-json-unavailable"
        val fields = listOf(
            "authors", "designers", "titles", "descriptions",
            "builtInThumbnails", "builtInPreviews", "thumbnails", "previews",
            "parentResources", "subResources", "extraMeta", "contentPath", "miuiAdapterVersion"
        )
        return fields.joinToString(" • ") { key -> "$key=${jsonType(json.opt(key))}" }
    }

    private fun jsonType(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "NULL"
        is JSONObject -> "OBJECT"
        is JSONArray -> "ARRAY"
        is String -> "STRING"
        is Boolean -> "BOOLEAN"
        is Number -> "NUMBER"
        else -> value.javaClass.simpleName
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
        delay(700L)

        val crashBuffer = runCatching {
            ShizukuBridge.exec(
                context,
                "logcat -b crash -d -v threadtime -t 260 2>/dev/null | tail -n 220"
            )
        }.getOrNull()
        DiagnosticsSessionClient.append(
            context,
            "THEME_MANAGER_CRASH_BUFFER",
            "localId=$localId • " + crashBuffer?.output?.trim()?.take(16000).orEmpty().ifBlank { "no-output" }
        )

        val exitInfo = runCatching {
            ShizukuBridge.exec(
                context,
                "dumpsys activity exit-info $THEME_MANAGER_PACKAGE 2>/dev/null | head -n 300"
            )
        }.getOrNull()
        DiagnosticsSessionClient.append(
            context,
            "THEME_MANAGER_EXIT_INFO",
            "localId=$localId • " + exitInfo?.output?.trim()?.take(16000).orEmpty().ifBlank { "no-output" }
        )

        val fatalWindow = runCatching {
            ShizukuBridge.exec(
                context,
                "logcat -d -v threadtime -b main -b system -b crash -t 1400 2>/dev/null | " +
                    "grep -E 'FATAL EXCEPTION|AndroidRuntime|Process: com.android.thememanager|Caused by:|Expected BEGIN_|mine.local.adapter|ThemeDetailActivity|ViewLocalResource|com.android.thememanager' | tail -n 320"
            )
        }.getOrNull()
        DiagnosticsSessionClient.append(
            context,
            "THEME_MANAGER_FATAL_WINDOW",
            "localId=$localId • " + fatalWindow?.output?.trim()?.take(18000).orEmpty().ifBlank { "no-output" }
        )
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private const val THEME_MANAGER_PACKAGE = "com.android.thememanager"
    private const val REMOTE_DATA_ROOT = "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/theme/.data"
    private val SAFE_LOCAL_ID = Regex("[A-Za-z0-9._-]{1,128}")
    private val SAFE_RESOURCE_CODE = Regex("[A-Za-z0-9._-]{1,128}")
}
