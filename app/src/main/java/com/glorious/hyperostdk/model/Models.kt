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

data class ThemeTransactionInfo(
    val code: Int,
    val name: String
)

data class ThemeInterfaceReflectionResult(
    val descriptor: String,
    val interfaceClassLoaded: Boolean,
    val stubClassLoaded: Boolean,
    val interfaceMethods: List<String>,
    val stubMethods: List<String>,
    val transactionFields: List<String>,
    val transactionNames: List<ThemeTransactionInfo>,
    val errors: List<String>
)

data class FrameworkArtifactInfo(
    val path: String,
    val exists: Boolean,
    val readable: Boolean,
    val sizeBytes: Long?,
    val containsInterface: Boolean?,
    val containsStub: Boolean?,
    val sha256: String?,
    val scanError: String? = null
)

data class FrameworkArtifactExportResult(
    val descriptor: String,
    val artifacts: List<FrameworkArtifactInfo>,
    val archivePath: String?,
    val archiveName: String?,
    val archiveSizeBytes: Long?,
    val exportedFiles: List<String>,
    val error: String? = null
)
