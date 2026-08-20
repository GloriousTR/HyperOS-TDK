package com.glorious.hyperostdk.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.glorious.hyperostdk.model.IntentProbeMatch
import com.glorious.hyperostdk.model.IntentProbeResult
import com.glorious.hyperostdk.model.MtzInfo

object IntentProbe {
    private const val THEME_MANAGER_PACKAGE = "com.android.thememanager"

    private val fallbackMimeTypes = listOf(
        "application/octet-stream",
        "application/zip",
        "application/x-mtz",
        "application/vnd.miui.theme"
    )

    /**
     * Read-only probe. It asks PackageManager which Theme Manager activities match
     * candidate MTZ intents. It never starts an activity, service, receiver or provider.
     */
    fun probe(context: Context, mtzInfo: MtzInfo): List<IntentProbeResult> {
        val uri = Uri.parse(mtzInfo.uri)
        val mimeTypes = linkedSetOf<String>().apply {
            mtzInfo.mimeType?.takeIf { it.isNotBlank() }?.let(::add)
            addAll(fallbackMimeTypes)
        }

        val candidates = buildList {
            add(
                "VIEW / data only" to Intent(Intent.ACTION_VIEW).apply {
                    data = uri
                    setPackage(THEME_MANAGER_PACKAGE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )

            mimeTypes.forEach { mimeType ->
                add(
                    "VIEW / $mimeType" to Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mimeType)
                        setPackage(THEME_MANAGER_PACKAGE)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
                add(
                    "SEND / $mimeType" to Intent(Intent.ACTION_SEND).apply {
                        type = mimeType
                        setPackage(THEME_MANAGER_PACKAGE)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newUri(context.contentResolver, mtzInfo.displayName, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            }
        }

        return candidates.map { (label, intent) ->
            val matches = queryActivities(context.packageManager, intent)
                .map { resolveInfo ->
                    val info = resolveInfo.activityInfo
                    IntentProbeMatch(
                        componentName = info?.name ?: "unknown",
                        exported = info?.exported == true,
                        permission = info?.permission,
                        priority = resolveInfo.priority,
                        match = resolveInfo.match
                    )
                }
                .distinctBy { it.componentName }
                .sortedBy { it.componentName }

            IntentProbeResult(
                label = label,
                action = intent.action ?: "unknown",
                mimeType = intent.type,
                matches = matches
            )
        }
    }

    private fun queryActivities(pm: PackageManager, intent: Intent) =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_RESOLVED_FILTER.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.GET_RESOLVED_FILTER)
        }
}
