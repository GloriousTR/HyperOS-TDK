package com.glorious.hyperostdk

import android.content.Context
import android.os.Bundle
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsSessionClient {
    data class Snapshot(
        val sessionId: String,
        val startedAt: Long,
        val eventCount: Int,
        val text: String
    )

    fun ensureStarted(context: Context): Snapshot? {
        val reply = context.contentResolver.call(
            ImportControlProvider.AUTHORITY,
            ImportControlProvider.METHOD_DIAG_START,
            null,
            null
        ) ?: return null
        return reply.toSnapshot()
    }

    fun append(
        context: Context,
        event: String,
        detail: String = "",
        level: String = "INFO",
        source: String = "HyperOS-TDK"
    ): Boolean {
        val extras = Bundle().apply {
            putString(ImportControlProvider.KEY_DIAG_SOURCE, source)
            putString(ImportControlProvider.KEY_DIAG_EVENT, event)
            putString(ImportControlProvider.KEY_DIAG_DETAIL, detail)
            putString(ImportControlProvider.KEY_DIAG_LEVEL, level)
            putLong(ImportControlProvider.KEY_DIAG_EVENT_AT, System.currentTimeMillis())
        }
        return context.contentResolver.call(
            ImportControlProvider.AUTHORITY,
            ImportControlProvider.METHOD_DIAG_APPEND,
            null,
            extras
        )?.getBoolean("accepted", false) == true
    }

    fun snapshot(context: Context): Snapshot? {
        val reply = context.contentResolver.call(
            ImportControlProvider.AUTHORITY,
            ImportControlProvider.METHOD_DIAG_SNAPSHOT,
            null,
            null
        ) ?: return null
        return reply.toSnapshot()
    }

    fun clearAndRestart(context: Context): Snapshot? {
        val reply = context.contentResolver.call(
            ImportControlProvider.AUTHORITY,
            ImportControlProvider.METHOD_DIAG_CLEAR,
            null,
            null
        ) ?: return null
        append(context, "DIAGNOSTICS_CLEARED", "Yeni otomatik tanılama oturumu başlatıldı.")
        return reply.toSnapshot()
    }

    fun export(context: Context): File {
        val snapshot = snapshot(context)
            ?: ensureStarted(context)
            ?: error("Diagnostics session is unavailable")

        val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(reportsDir, "hyperos-tdk-live-diagnostics-$stamp.txt")
        file.writeText(
            buildString {
                appendLine("HyperOS TDK Live Diagnostics Export")
                appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Session: ${snapshot.sessionId}")
                appendLine("Started at: ${formatTime(snapshot.startedAt)}")
                appendLine("Events: ${snapshot.eventCount}")
                appendLine("============================================================")
                append(snapshot.text)
            }
        )
        return file
    }

    private fun Bundle.toSnapshot(): Snapshot? {
        if (!getBoolean(ImportControlProvider.KEY_PRESENT, false)) return null
        val sessionId = getString(ImportControlProvider.KEY_SESSION_ID).orEmpty()
        val startedAt = getLong(ImportControlProvider.KEY_SESSION_STARTED_AT, 0L)
        if (sessionId.isBlank() || startedAt <= 0L) return null
        return Snapshot(
            sessionId = sessionId,
            startedAt = startedAt,
            eventCount = getInt(ImportControlProvider.KEY_DIAG_COUNT, 0),
            text = getString(ImportControlProvider.KEY_DIAG_TEXT).orEmpty()
        )
    }

    private fun formatTime(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(epochMs))
}
