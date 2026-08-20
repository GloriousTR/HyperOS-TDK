package com.glorious.hyperostdk.model

data class DeviceInfo(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val androidVersion: String,
    val sdkInt: Int,
    val buildDisplay: String,
    val incremental: String,
    val hyperOsVersion: String?,
    val miuiVersion: String?,
    val modDevice: String?
)

data class PackageComponentInfo(
    val type: String,
    val name: String,
    val exported: Boolean,
    val permission: String?
)

data class ThemeManagerInfo(
    val installed: Boolean,
    val packageName: String,
    val versionName: String?,
    val versionCode: Long?,
    val launchActivity: String?,
    val components: List<PackageComponentInfo>,
    val error: String? = null
)

data class MtzInfo(
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val sha256: String,
    val isZipContainer: Boolean,
    val entries: List<String>,
    val warning: String? = null
)

data class IntentProbeMatch(
    val componentName: String,
    val exported: Boolean,
    val permission: String?,
    val priority: Int,
    val match: Int
)

data class IntentProbeResult(
    val label: String,
    val action: String,
    val mimeType: String?,
    val matches: List<IntentProbeMatch>
)

data class ThemeServiceProbeResult(
    val componentName: String,
    val bindRequested: Boolean,
    val connected: Boolean,
    val interfaceDescriptor: String?,
    val binderClass: String?,
    val binderAlive: Boolean?,
    val pingBinder: Boolean?,
    val error: String? = null
)
