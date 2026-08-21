package com.glorious.hyperostdk.privileged

import android.content.Context
import android.system.Os
import androidx.annotation.Keep

class PrivilegedThemeUserService : IPrivilegedThemeService.Stub() {
    constructor()

    @Keep
    constructor(context: Context) {
        @Suppress("UNUSED_VARIABLE")
        val keepContextConstructor = context.packageName
    }

    override fun destroy() {
        System.exit(0)
    }

    override fun uid(): Int = Os.getuid()

    override fun exec(command: String): String {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        val bounded = if (output.length > MAX_OUTPUT_CHARS) {
            output.take(MAX_OUTPUT_CHARS) + "\n[output truncated]"
        } else {
            output
        }
        return "exitCode=$exitCode\n$bounded"
    }

    private companion object {
        const val MAX_OUTPUT_CHARS = 256_000
    }
}
