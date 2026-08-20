package com.glorious.hyperostdk.data

import android.os.Build
import com.glorious.hyperostdk.model.DeviceInfo

object DeviceInfoProvider {
    fun read(): DeviceInfo = DeviceInfo(
        manufacturer = Build.MANUFACTURER.orUnknown(),
        brand = Build.BRAND.orUnknown(),
        model = Build.MODEL.orUnknown(),
        device = Build.DEVICE.orUnknown(),
        androidVersion = Build.VERSION.RELEASE.orUnknown(),
        sdkInt = Build.VERSION.SDK_INT,
        buildDisplay = Build.DISPLAY.orUnknown(),
        incremental = Build.VERSION.INCREMENTAL.orUnknown(),
        hyperOsVersion = getProp("ro.mi.os.version.name")
            ?: getProp("ro.mi.os.version.incremental"),
        miuiVersion = getProp("ro.miui.ui.version.name"),
        modDevice = getProp("ro.product.mod_device")
    )

    private fun getProp(key: String): String? = runCatching {
        val process = ProcessBuilder("getprop", key)
            .redirectErrorStream(true)
            .start()
        val value = process.inputStream.bufferedReader().use { it.readLine() }
        process.waitFor()
        value?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun String?.orUnknown(): String = this?.takeIf { it.isNotBlank() } ?: "Unknown"
}
