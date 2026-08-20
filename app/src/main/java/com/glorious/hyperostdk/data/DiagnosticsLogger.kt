package com.glorious.hyperostdk.data

import android.content.Context
import android.os.Environment
import com.glorious.hyperostdk.BuildConfig
import com.glorious.hyperostdk.model.DeviceInfo
import com.glorious.hyperostdk.model.FrameworkArtifactExportResult
import com.glorious.hyperostdk.model.IntentProbeResult
import com.glorious.hyperostdk.model.MtzInfo
import com.glorious.hyperostdk.model.ThemeInterfaceReflectionResult
import com.glorious.hyperostdk.model.ThemeManagerInfo
import com.glorious.hyperostdk.model.ThemeServiceProbeResult
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object DiagnosticsLogger {
    private val fileTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val displayTimestamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun writeReport(
        context: Context,
        deviceInfo: DeviceInfo,
        themeManagerInfo: ThemeManagerInfo,
        mtzInfo: MtzInfo?,
        intentProbeResults: List<IntentProbeResult> = emptyList(),
        themeServiceProbeResult: ThemeServiceProbeResult? = null,
        themeInterfaceReflectionResult: ThemeInterfaceReflectionResult? = null,
        frameworkArtifactExportResult: FrameworkArtifactExportResult? = null
    ): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val directory = File(root, "diagnostics").apply { mkdirs() }
        val now = LocalDateTime.now()
        val file = File(directory, "hyperos-tdk-${now.format(fileTimestamp)}.txt")

        file.writeText(
            buildReport(
                generatedAt = now.format(displayTimestamp),
                deviceInfo = deviceInfo,
                themeManagerInfo = themeManagerInfo,
                mtzInfo = mtzInfo,
                intentProbeResults = intentProbeResults,
                themeServiceProbeResult = themeServiceProbeResult,
                themeInterfaceReflectionResult = themeInterfaceReflectionResult,
                frameworkArtifactExportResult = frameworkArtifactExportResult
            )
        )
        return file
    }

    private fun buildReport(
        generatedAt: String,
        deviceInfo: DeviceInfo,
        themeManagerInfo: ThemeManagerInfo,
        mtzInfo: MtzInfo?,
        intentProbeResults: List<IntentProbeResult>,
        themeServiceProbeResult: ThemeServiceProbeResult?,
        themeInterfaceReflectionResult: ThemeInterfaceReflectionResult?,
        frameworkArtifactExportResult: FrameworkArtifactExportResult?
    ): String = buildString {
        appendLine("HyperOS TDK Diagnostics")
        appendLine("======================")
        appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Generated: $generatedAt")
        appendLine()

        appendLine("[DEVICE]")
        appendLine("Manufacturer: ${deviceInfo.manufacturer}")
        appendLine("Brand: ${deviceInfo.brand}")
        appendLine("Model: ${deviceInfo.model}")
        appendLine("Device: ${deviceInfo.device}")
        appendLine("Android: ${deviceInfo.androidVersion} / API ${deviceInfo.sdkInt}")
        appendLine("Build display: ${deviceInfo.buildDisplay}")
        appendLine("Incremental: ${deviceInfo.incremental}")
        appendLine("HyperOS property: ${deviceInfo.hyperOsVersion ?: "not exposed"}")
        appendLine("MIUI property: ${deviceInfo.miuiVersion ?: "not exposed"}")
        appendLine("Mod device: ${deviceInfo.modDevice ?: "not exposed"}")
        appendLine()

        appendLine("[THEME MANAGER]")
        appendLine("Installed: ${themeManagerInfo.installed}")
        appendLine("Package: ${themeManagerInfo.packageName}")
        appendLine("Version: ${themeManagerInfo.versionName ?: "unknown"}")
        appendLine("Version code: ${themeManagerInfo.versionCode ?: "unknown"}")
        appendLine("Launch activity: ${themeManagerInfo.launchActivity ?: "unknown"}")
        themeManagerInfo.error?.let { appendLine("Error: $it") }
        appendLine("Components: ${themeManagerInfo.components.size}")
        themeManagerInfo.components.forEach { component ->
            appendLine("- ${component.type}: ${component.name} | exported=${component.exported} | permission=${component.permission ?: "none"}")
        }
        appendLine()

        appendLine("[MTZ]")
        if (mtzInfo == null) {
            appendLine("No MTZ selected.")
        } else {
            appendLine("Name: ${mtzInfo.displayName}")
            appendLine("URI: ${mtzInfo.uri}")
            appendLine("MIME: ${mtzInfo.mimeType ?: "unknown"}")
            appendLine("Size: ${mtzInfo.sizeBytes ?: -1}")
            appendLine("SHA-256: ${mtzInfo.sha256}")
            appendLine("ZIP container: ${mtzInfo.isZipContainer}")
            mtzInfo.warning?.let { appendLine("Warning: $it") }
            appendLine("Entries: ${mtzInfo.entries.size}")
            mtzInfo.entries.forEach { appendLine("- $it") }
        }
        appendLine()

        appendLine("[INTENT PROBE]")
        if (intentProbeResults.isEmpty()) {
            appendLine("Probe not run.")
        } else {
            val totalMatches = intentProbeResults.sumOf { it.matches.size }
            appendLine("Candidates: ${intentProbeResults.size}")
            appendLine("Total matches: $totalMatches")
            intentProbeResults.forEach { result ->
                appendLine("- ${result.label} | action=${result.action} | mime=${result.mimeType ?: "none"} | matches=${result.matches.size}")
                result.matches.forEach { match ->
                    appendLine("  -> ${match.componentName} | exported=${match.exported} | permission=${match.permission ?: "none"} | priority=${match.priority} | match=${match.match}")
                }
            }
        }
        appendLine()

        appendLine("[THEME SERVICE PROBE]")
        if (themeServiceProbeResult == null) {
            appendLine("Probe not run.")
        } else {
            appendLine("Component: ${themeServiceProbeResult.componentName}")
            appendLine("Bind requested: ${themeServiceProbeResult.bindRequested}")
            appendLine("Connected: ${themeServiceProbeResult.connected}")
            appendLine("Interface descriptor: ${themeServiceProbeResult.interfaceDescriptor ?: "unknown"}")
            appendLine("Binder class: ${themeServiceProbeResult.binderClass ?: "unknown"}")
            appendLine("Binder alive: ${themeServiceProbeResult.binderAlive ?: "unknown"}")
            appendLine("Ping binder: ${themeServiceProbeResult.pingBinder ?: "unknown"}")
            themeServiceProbeResult.error?.let { appendLine("Error: $it") }
        }
        appendLine()

        appendLine("[THEME INTERFACE REFLECTION]")
        if (themeInterfaceReflectionResult == null) {
            appendLine("Probe not run.")
        } else {
            appendLine("Descriptor: ${themeInterfaceReflectionResult.descriptor}")
            appendLine("Interface class loaded: ${themeInterfaceReflectionResult.interfaceClassLoaded}")
            appendLine("Stub class loaded: ${themeInterfaceReflectionResult.stubClassLoaded}")
            appendLine("Interface methods: ${themeInterfaceReflectionResult.interfaceMethods.size}")
            themeInterfaceReflectionResult.interfaceMethods.forEach { appendLine("- method: $it") }
            appendLine("Stub methods: ${themeInterfaceReflectionResult.stubMethods.size}")
            themeInterfaceReflectionResult.stubMethods.forEach { appendLine("- stub: $it") }
            appendLine("Transaction fields: ${themeInterfaceReflectionResult.transactionFields.size}")
            themeInterfaceReflectionResult.transactionFields.forEach { appendLine("- field: $it") }
            appendLine("Transaction names: ${themeInterfaceReflectionResult.transactionNames.size}")
            themeInterfaceReflectionResult.transactionNames.forEach { transaction ->
                appendLine("- transaction: ${transaction.code} -> ${transaction.name}")
            }
            appendLine("Reflection errors: ${themeInterfaceReflectionResult.errors.size}")
            themeInterfaceReflectionResult.errors.forEach { appendLine("- error: $it") }
        }
        appendLine()

        appendLine("[FRAMEWORK ARTIFACT EXPORT]")
        if (frameworkArtifactExportResult == null) {
            appendLine("Probe not run.")
        } else {
            appendLine("Descriptor: ${frameworkArtifactExportResult.descriptor}")
            appendLine("Artifacts checked: ${frameworkArtifactExportResult.artifacts.size}")
            appendLine("Archive: ${frameworkArtifactExportResult.archiveName ?: "not created"}")
            appendLine("Archive size: ${frameworkArtifactExportResult.archiveSizeBytes ?: -1}")
            appendLine("Exported files: ${frameworkArtifactExportResult.exportedFiles.size}")
            frameworkArtifactExportResult.exportedFiles.forEach { appendLine("- exported: $it") }
            frameworkArtifactExportResult.error?.let { appendLine("Error: $it") }
            frameworkArtifactExportResult.artifacts.forEach { artifact ->
                appendLine(
                    "- artifact: ${artifact.path} | exists=${artifact.exists} | readable=${artifact.readable} | size=${artifact.sizeBytes ?: -1} | interface=${artifact.containsInterface ?: "unknown"} | stub=${artifact.containsStub ?: "unknown"} | sha256=${artifact.sha256 ?: "n/a"}"
                )
                artifact.scanError?.let { appendLine("  scan error: $it") }
            }
        }
    }
}
