package com.glorious.hyperostdk.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.glorious.hyperostdk.model.PackageComponentInfo
import com.glorious.hyperostdk.model.ThemeManagerInfo

object ThemeManagerInspector {
    private val candidatePackages = listOf(
        "com.android.thememanager",
        "com.android.thememanager.module"
    )

    fun inspect(context: Context): ThemeManagerInfo {
        val packageManager = context.packageManager

        for (packageName in candidatePackages) {
            val result = runCatching {
                val packageInfo = getPackageInfo(packageManager, packageName)
                packageInfo.toThemeManagerInfo(packageManager)
            }
            if (result.isSuccess) return result.getOrThrow()
        }

        return ThemeManagerInfo(
            installed = false,
            packageName = candidatePackages.first(),
            versionName = null,
            versionCode = null,
            launchActivity = null,
            components = emptyList(),
            error = "Known Xiaomi Theme Manager packages were not visible or installed."
        )
    }

    private fun getPackageInfo(pm: PackageManager, packageName: String): PackageInfo {
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or
            PackageManager.GET_PROVIDERS

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, flags)
        }
    }

    private fun PackageInfo.toThemeManagerInfo(pm: PackageManager): ThemeManagerInfo {
        val allComponents = buildList {
            activities?.forEach { info ->
                add(PackageComponentInfo("activity", info.name, info.exported, info.permission))
            }
            services?.forEach { info ->
                add(PackageComponentInfo("service", info.name, info.exported, info.permission))
            }
            receivers?.forEach { info ->
                add(PackageComponentInfo("receiver", info.name, info.exported, info.permission))
            }
            providers?.forEach { info ->
                add(PackageComponentInfo("provider", info.name, info.exported, info.readPermission ?: info.writePermission))
            }
        }.sortedWith(compareBy({ it.type }, { it.name }))

        val launchActivity = pm.getLaunchIntentForPackage(packageName)
            ?.component
            ?.className

        return ThemeManagerInfo(
            installed = true,
            packageName = packageName,
            versionName = versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                longVersionCode
            } else {
                @Suppress("DEPRECATION")
                versionCode.toLong()
            },
            launchActivity = launchActivity,
            components = allComponents
        )
    }
}
