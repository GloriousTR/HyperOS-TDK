package com.glorious.hyperostdk.privileged

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import com.glorious.hyperostdk.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

object ShizukuBridge {
    const val PERMISSION_REQUEST_CODE = 4101

    data class State(
        val sheveryInstalled: Boolean,
        val officialShizukuInstalled: Boolean,
        val binderAlive: Boolean,
        val permissionGranted: Boolean,
        val serverUid: Int?,
        val serverVersion: Int?,
        val selinuxContext: String?,
        val backend: String,
        val detail: String
    ) {
        val ready: Boolean
            get() = binderAlive && permissionGranted && serverUid != null
    }

    data class ShellResult(
        val exitCode: Int,
        val output: String
    ) {
        val success: Boolean get() = exitCode == 0
    }

    fun inspect(context: Context): State {
        val sheveryInstalled = isPackageInstalled(context, SHEVERY_PACKAGE)
        val officialInstalled = isPackageInstalled(context, OFFICIAL_SHIZUKU_PACKAGE)
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val permissionGranted = binderAlive && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val uid = if (binderAlive) runCatching { Shizuku.getUid() }.getOrNull()?.takeIf { it >= 0 } else null
        val version = if (binderAlive) runCatching { Shizuku.getVersion() }.getOrNull()?.takeIf { it >= 0 } else null
        val selinux = if (binderAlive) runCatching { Shizuku.getSELinuxContext() }.getOrNull() else null
        val backend = when (uid) {
            0 -> "ROOT"
            2000 -> "SHELL"
            null -> "NONE"
            else -> "UID_$uid"
        }
        val manager = when {
            sheveryInstalled -> "Shevery"
            officialInstalled -> "Shizuku"
            else -> "Shizuku-compatible manager not visible"
        }
        val detail = buildString {
            append(manager)
            append(" • binder=").append(binderAlive)
            append(" • permission=").append(permissionGranted)
            if (uid != null) append(" • uid=").append(uid)
            if (version != null) append(" • api=").append(version)
        }
        return State(
            sheveryInstalled = sheveryInstalled,
            officialShizukuInstalled = officialInstalled,
            binderAlive = binderAlive,
            permissionGranted = permissionGranted,
            serverUid = uid,
            serverVersion = version,
            selinuxContext = selinux,
            backend = backend,
            detail = detail
        )
    }

    fun requestPermission(): Result<Unit> = runCatching {
        check(Shizuku.pingBinder()) { "Shevery/Shizuku binder is not available" }
        check(!Shizuku.isPreV11()) { "Shizuku API pre-v11 is not supported" }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }
    }

    suspend fun exec(context: Context, command: String): ShellResult =
        withUserService(context) { service ->
            withContext(Dispatchers.IO) {
                parseShellResult(service.exec(command))
            }
        }

    suspend fun privilegedUid(context: Context): Int =
        withUserService(context) { service ->
            withContext(Dispatchers.IO) { service.uid() }
        }

    private suspend fun <T> withUserService(
        context: Context,
        block: suspend (IPrivilegedThemeService) -> T
    ): T {
        val state = inspect(context)
        check(state.binderAlive) { "Shevery/Shizuku binder is not alive" }
        check(state.permissionGranted) { "Shevery/Shizuku permission is not granted" }
        check((state.serverVersion ?: 0) >= 10) { "Shizuku UserService requires API 10+" }

        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, PrivilegedThemeUserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("theme_engine")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
            .tag("hyperos-tdk-theme-engine")

        val deferred = CompletableDeferred<IPrivilegedThemeService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null || !binder.pingBinder()) {
                    if (!deferred.isCompleted) {
                        deferred.completeExceptionally(IllegalStateException("Invalid UserService binder"))
                    }
                    return
                }
                if (!deferred.isCompleted) {
                    deferred.complete(IPrivilegedThemeService.Stub.asInterface(binder))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(IllegalStateException("UserService disconnected before ready"))
                }
            }
        }

        withContext(Dispatchers.Main.immediate) {
            Shizuku.bindUserService(args, connection)
        }
        val service = try {
            withTimeout(USER_SERVICE_TIMEOUT_MS) { deferred.await() }
        } catch (error: Throwable) {
            runCatching {
                withContext(Dispatchers.Main.immediate) {
                    Shizuku.unbindUserService(args, connection, true)
                }
            }
            throw error
        }

        return try {
            block(service)
        } finally {
            runCatching {
                withContext(Dispatchers.Main.immediate) {
                    Shizuku.unbindUserService(args, connection, true)
                }
            }
        }
    }

    private fun parseShellResult(raw: String): ShellResult {
        val firstLine = raw.lineSequence().firstOrNull().orEmpty()
        val exitCode = firstLine.substringAfter("exitCode=", "-1").toIntOrNull() ?: -1
        val output = raw.substringAfter('\n', "")
        return ShellResult(exitCode, output.trim())
    }

    @Suppress("DEPRECATION")
    private fun isPackageInstalled(context: Context, packageName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            context.packageManager.getPackageInfo(packageName, 0)
        }
        true
    }.getOrDefault(false)

    private const val SHEVERY_PACKAGE = "com.hamondev.shevery"
    private const val OFFICIAL_SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val USER_SERVICE_TIMEOUT_MS = 10_000L
}
