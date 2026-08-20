package com.glorious.hyperostdk.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.glorious.hyperostdk.model.ThemeServiceProbeResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object ThemeServiceProbe {
    private const val PACKAGE_NAME = "com.android.thememanager"
    private const val SERVICE_NAME = "com.android.thememanager.service.ThemeService"
    private const val TIMEOUT_MS = 5_000L

    suspend fun probe(context: Context): ThemeServiceProbeResult {
        val component = ComponentName(PACKAGE_NAME, SERVICE_NAME)
        val resultDeferred = CompletableDeferred<ThemeServiceProbeResult>()

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val descriptor = runCatching { service.interfaceDescriptor }.getOrNull()
                resultDeferred.complete(
                    ThemeServiceProbeResult(
                        componentName = name.flattenToShortString(),
                        bindRequested = true,
                        connected = true,
                        interfaceDescriptor = descriptor,
                        binderClass = service.javaClass.name,
                        binderAlive = runCatching { service.isBinderAlive }.getOrNull(),
                        pingBinder = runCatching { service.pingBinder() }.getOrNull()
                    )
                )
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit

            override fun onNullBinding(name: ComponentName) {
                resultDeferred.complete(
                    ThemeServiceProbeResult(
                        componentName = name.flattenToShortString(),
                        bindRequested = true,
                        connected = false,
                        interfaceDescriptor = null,
                        binderClass = null,
                        binderAlive = null,
                        pingBinder = null,
                        error = "Service returned a null binding."
                    )
                )
            }

            override fun onBindingDied(name: ComponentName) {
                resultDeferred.complete(
                    ThemeServiceProbeResult(
                        componentName = name.flattenToShortString(),
                        bindRequested = true,
                        connected = false,
                        interfaceDescriptor = null,
                        binderClass = null,
                        binderAlive = null,
                        pingBinder = null,
                        error = "Service binding died before the probe completed."
                    )
                )
            }
        }

        var bindError: Throwable? = null
        val bound = withContext(Dispatchers.Main.immediate) {
            runCatching {
                context.bindService(
                    Intent().setComponent(component),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            }.onFailure { bindError = it }
                .getOrDefault(false)
        }

        if (!bound) {
            return ThemeServiceProbeResult(
                componentName = component.flattenToShortString(),
                bindRequested = false,
                connected = false,
                interfaceDescriptor = null,
                binderClass = null,
                binderAlive = null,
                pingBinder = null,
                error = bindError?.let { "bindService failed: ${it.javaClass.simpleName}: ${it.message}" }
                    ?: "bindService returned false."
            )
        }

        return try {
            withTimeoutOrNull(TIMEOUT_MS) { resultDeferred.await() }
                ?: ThemeServiceProbeResult(
                    componentName = component.flattenToShortString(),
                    bindRequested = true,
                    connected = false,
                    interfaceDescriptor = null,
                    binderClass = null,
                    binderAlive = null,
                    pingBinder = null,
                    error = "Timed out after ${TIMEOUT_MS} ms waiting for onServiceConnected."
                )
        } finally {
            withContext(Dispatchers.Main.immediate) {
                runCatching { context.unbindService(connection) }
            }
        }
    }
}
