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
 * Build 37 strict local-resource route.
 *
 * Build 36's JsonReader tracer identified the exact Theme Manager parser failure:
 * builtInThumbnails was encountered as BEGIN_ARRAY where the local adapter requires
 * BEGIN_OBJECT. Build 37 normalizes builtInThumbnails/builtInPreviews for every MRM owned
 * by the current import, verifies the complete owned resource tree before launch, and emits
 * a read-only global scan for any other malformed MRM left in Theme Manager storage.
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
            "displayName=$displayName • build=37 • strategy=normalize-thumbnail-containers-before-final-launch"
        )

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

        val mainAudit = auditMetadataTypes(appContext, initial.localId)
        DiagnosticsSessionClient.append(
            appContext,
            "STRICT_METADATA_TYPE_AUDIT",
            "localId=${initial.localId} • $mainAudit"
        )

        val ownedAudit = auditOwnedTreeTypes(appContext, initial.localId)
        DiagnosticsSessionClient.append(
            appContext,
            if (ownedAudit.invalidCount == 0) "STRICT_OWNED_TREE_TYPE_AUDIT_SUCCESS" else "STRICT_OWNED_TREE_TYPE_AUDIT_FAILED",
            "localId=${initial.localId} • checked=${ownedAudit.checkedCount} • invalid=${ownedAudit.invalidCount} • ${ownedAudit.detail}",
            level = if (ownedAudit.invalidCount == 0) "INFO" else "ERROR"
        )
        check(ownedAudit.invalidCount == 0) {
            "Owned metadata tree still contains non-object builtInThumbnails/builtInPreviews: ${ownedAudit.detail}"
        }

        val globalScan = scanGlobalMalformedThumbnailContainers(appContext)
        DiagnosticsSessionClient.append(
            appContext,
            if (globalScan.isBlank()) "STRICT_GLOBAL_SCHEMA_SCAN_CLEAN" else "STRICT_GLOBAL_SCHEMA_SCAN_WARNING",
            if (globalScan.isBlank()) {
                "No builtInThumbnails/builtInPreviews top-level ARRAY signatures found under Theme Manager meta storage."
            } else {
                "Other malformed metadata candidates exist outside/alongside the current tree • paths=${globalScan.take(7000)}"
            },
            level = if (globalScan.isBlank()) "INFO" else "WARN"
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
            "localId=${initial.localId} • REQUEST_APPLY_EVENT=false • build=37 • metadataFrozen=true • thumbnailContainers=OBJECT"
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

    private data class TreeAuditResult(
        val checkedCount: Int,
        val invalidCount: Int,
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
            detail = "mainVerify=${verify.output.replace('\n', ' ').trim().take(500)} • mainAdapter=null • subAdapter=4.0 • builtInThumbnailContainersNormalized=true • metadataFrozenBeforeLaunch=true"
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

        // Build 36 proved Theme Manager calls beginObject() for these two fields. Normalize
        // them unconditionally so an old ARRAY representation cannot survive in any owned MRM.
        val normalizedThumbnails = normalizeLocalizedArrayContainer(json.opt("builtInThumbnails"))
        val normalizedPreviews = normalizeLocalizedArrayContainer(json.opt("builtInPreviews"))
        json.put("builtInThumbnails", normalizedThumbnails)
        json.put("builtInPreviews", normalizedPreviews)

        if (isMain) {
            json.put("miuiAdapterVersion", JSONObject.NULL)
        } else {
            json.put("miuiAdapterVersion", "4.0")
            json.put("wallpaperStyle", 0)
            json.put("isSingleResource", false)

            val source = normalizedThumbnails.optJSONArray("fallback") ?: JSONArray()
            val small = JSONArray()
            for (i in 0 until source.length()) {
                val name = source.optString(i)
                if (name.contains("small", ignoreCase = true)) small.put(name)
            }
            // Keep the required OBJECT container even when the resulting small list is empty.
            json.put("builtInPreviews", JSONObject().put("fallback", small))
        }

        val localDir = context.getExternalFilesDir("strict-local-build37") ?: return false
        localDir.mkdirs()
        val localFile = File(localDir, "$resourceCode-$localId.mrm")
        localFile.writeText(json.toString(), Charsets.UTF_8)

        val tmpRemote = "$remotePath.hyperos-tdk-build37.tmp"
        val write = ShizukuBridge.exec(
            context,
            "set -e; cp -f ${shellQuote(localFile.absolutePath)} ${shellQuote(tmpRemote)}; " +
                "mv -f ${shellQuote(tmpRemote)} ${shellQuote(remotePath)}; test -s ${shellQuote(remotePath)}"
        )
        return write.success
    }

    private fun normalizeLocalizedArrayContainer(value: Any?): JSONObject {
        return when (value) {
            is JSONObject -> JSONObject(value.toString())
            is JSONArray -> JSONObject().put("fallback", JSONArray(value.toString()))
            else -> JSONObject().put("fallback", JSONArray())
        }
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

    private suspend fun auditOwnedTreeTypes(context: Context, mainLocalId: String): TreeAuditResult {
        val mainRemote = "$REMOTE_DATA_ROOT/meta/theme/$mainLocalId.mrm"
        val mainRead = ShizukuBridge.exec(context, "cat ${shellQuote(mainRemote)}")
        val main = runCatching { JSONObject(mainRead.output.trim()) }.getOrNull()
            ?: return TreeAuditResult(0, 1, "main-json-unavailable")

        val targets = ArrayList<Pair<String, String>>()
        targets += "theme" to mainRemote
        val refs = main.optJSONArray("subResources") ?: JSONArray()
        for (index in 0 until refs.length()) {
            val ref = refs.optJSONObject(index) ?: continue
            val localId = ref.optString("localId")
            val resourceCode = ref.optString("resourceCode")
            if (!SAFE_LOCAL_ID.matches(localId) || !SAFE_RESOURCE_CODE.matches(resourceCode)) continue
            targets += resourceCode to "$REMOTE_DATA_ROOT/meta/$resourceCode/$localId.mrm"
        }

        var invalid = 0
        val details = ArrayList<String>()
        for ((resourceCode, remote) in targets) {
            val read = ShizukuBridge.exec(context, "cat ${shellQuote(remote)}")
            val json = runCatching { JSONObject(read.output.trim()) }.getOrNull()
            if (json == null) {
                invalid++
                details += "$resourceCode=json-unavailable"
                continue
            }
            val thumbnailsType = jsonType(json.opt("builtInThumbnails"))
            val previewsType = jsonType(json.opt("builtInPreviews"))
            if (thumbnailsType != "OBJECT" || previewsType != "OBJECT") {
                invalid++
                details += "$resourceCode(thumbnails=$thumbnailsType,previews=$previewsType)"
            }
        }

        return TreeAuditResult(
            checkedCount = targets.size,
            invalidCount = invalid,
            detail = details.joinToString(" • ").ifBlank { "all-owned-MRM-thumbnail-containers=OBJECT" }
        )
    }

    private suspend fun scanGlobalMalformedThumbnailContainers(context: Context): String {
        val scan = runCatching {
            ShizukuBridge.exec(
                context,
                "grep -REIl '\"builtIn(Thumbnails|Previews)\"[[:space:]]*:[[:space:]]*\\[' " +
                    "${shellQuote("$REMOTE_DATA_ROOT/meta")} 2>/dev/null | head -n 60"
            )
        }.getOrNull() ?: return "scan-unavailable"

        if (!scan.success) return "scan-exit=${scan.exitCode}:${scan.output.replace('\n', ' ').take(1000)}"
        return scan.output
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map { it.removePrefix("$REMOTE_DATA_ROOT/meta/") }
            .joinToString(" • ")
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
                    "grep -E 'FATAL EXCEPTION|AndroidRuntime|Process: com.android.thememanager|Caused by:|Expected BEGIN_|builtInThumbnails|builtInPreviews|ThemeDetailActivity|ViewLocalResource|com.android.thememanager' | tail -n 360"
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
