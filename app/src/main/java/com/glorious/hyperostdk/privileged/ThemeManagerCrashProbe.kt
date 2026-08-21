package com.glorious.hyperostdk.privileged

import android.content.Context
import com.glorious.hyperostdk.DiagnosticsSessionClient
import kotlinx.coroutines.delay

/**
 * Device-side diagnostics for the short-lived ThemeDetailActivity failure seen on HyperOS.
 * The probe is best-effort and never participates in import success/failure decisions.
 */
object ThemeManagerCrashProbe {
    suspend fun captureWindow(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            val baseline = ShizukuBridge.exec(
                appContext,
                "printf 'pid='; pidof com.android.thememanager 2>/dev/null || true"
            )
            DiagnosticsSessionClient.append(
                appContext,
                "THEME_MANAGER_CRASH_PROBE_ARMED",
                "${compact(baseline.output)} • mode=manual-apply-isolation"
            )
        }

        // Import/staging currently takes about four seconds on the reference device.
        // This first sample should land shortly after ThemeDetailActivity is launched.
        delay(5_500)
        val firstPid = runCatching {
            ShizukuBridge.exec(
                appContext,
                "printf 'pid='; pidof com.android.thememanager 2>/dev/null || true; " +
                    "printf ' top='; dumpsys activity activities 2>/dev/null | grep -m 1 'topResumedActivity' || true"
            )
        }.getOrNull()
        DiagnosticsSessionClient.append(
            appContext,
            "THEME_MANAGER_PROCESS_SAMPLE_1",
            compact(firstPid?.output.orEmpty())
        )

        // Existing device logs show the unexpected return/restart roughly 2–3 seconds later.
        delay(4_000)
        val secondPid = runCatching {
            ShizukuBridge.exec(
                appContext,
                "printf 'pid='; pidof com.android.thememanager 2>/dev/null || true; " +
                    "printf ' top='; dumpsys activity activities 2>/dev/null | grep -m 1 'topResumedActivity' || true"
            )
        }.getOrNull()
        DiagnosticsSessionClient.append(
            appContext,
            "THEME_MANAGER_PROCESS_SAMPLE_2",
            compact(secondPid?.output.orEmpty())
        )

        val exitInfo = runCatching {
            ShizukuBridge.exec(
                appContext,
                "dumpsys activity exit-info com.android.thememanager 2>/dev/null | head -n 120"
            )
        }.getOrNull()
        DiagnosticsSessionClient.append(
            appContext,
            "THEME_MANAGER_EXIT_INFO",
            compact(exitInfo?.output.orEmpty(), 5_500)
        )

        val crashBuffer = runCatching {
            ShizukuBridge.exec(
                appContext,
                "logcat -b crash -d -v threadtime -t 220 2>/dev/null | " +
                    "grep -A80 -B15 -E 'com.android.thememanager|FATAL EXCEPTION|AndroidRuntime' | tail -n 180"
            )
        }.getOrNull()
        DiagnosticsSessionClient.append(
            appContext,
            "THEME_MANAGER_CRASH_BUFFER",
            compact(crashBuffer?.output.orEmpty(), 7_500)
        )

        val relevantMain = runCatching {
            ShizukuBridge.exec(
                appContext,
                "logcat -d -v threadtime -t 700 2>/dev/null | " +
                    "grep -E 'AndroidRuntime|FATAL EXCEPTION|com.android.thememanager|ThemeDetailActivity|ClassCastException|NullPointerException|JSONException|IllegalStateException|Resources\\$NotFoundException' | tail -n 220"
            )
        }.getOrNull()
        DiagnosticsSessionClient.append(
            appContext,
            "THEME_MANAGER_RELEVANT_LOGCAT",
            compact(relevantMain?.output.orEmpty(), 9_000)
        )
    }

    private fun compact(value: String, max: Int = 2_500): String {
        val text = value
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (text.isBlank()) "no-output" else text.take(max)
    }
}
