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
