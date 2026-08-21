package com.glorious.hyperostdk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ImportControlProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.glorious.hyperostdk.control"
        val CONTROL_URI: Uri = Uri.parse("content://$AUTHORITY/command")

        const val METHOD_PUBLISH = "publish"
        const val METHOD_CONSUME = "consume"
        const val METHOD_REPORT_RESULT = "report_result"
        const val METHOD_GET_RESULT = "get_result"

        const val METHOD_DIAG_START = "diag_start"
        const val METHOD_DIAG_APPEND = "diag_append"
        const val METHOD_DIAG_SNAPSHOT = "diag_snapshot"
        const val METHOD_DIAG_CLEAR = "diag_clear"

        const val KEY_PRESENT = "present"
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_URI = "uri"
        const val KEY_CREATED_AT = "created_at"
        const val KEY_STATUS = "status"
        const val KEY_MESSAGE = "message"
        const val KEY_RESULT_AT = "result_at"

        const val KEY_SESSION_ID = "session_id"
        const val KEY_SESSION_STARTED_AT = "session_started_at"
        const val KEY_DIAG_TEXT = "diag_text"
        const val KEY_DIAG_COUNT = "diag_count"
        const val KEY_DIAG_SOURCE = "diag_source"
        const val KEY_DIAG_EVENT = "diag_event"
        const val KEY_DIAG_DETAIL = "diag_detail"
        const val KEY_DIAG_LEVEL = "diag_level"
        const val KEY_DIAG_EVENT_AT = "diag_event_at"

        const val STATUS_QUEUED = "queued"
        const val STATUS_START = "start"
        const val STATUS_COMPLETE = "complete"
        const val STATUS_FAIL = "fail"
        const val STATUS_QUEUE_ERROR = "queue_error"

        private const val TARGET_PACKAGE = "com.android.thememanager"
        private const val COMMAND_PREFS = "import_control"
        private const val RESULT_PREFS = "import_result"
        private const val DIAG_PREFS = "diagnostics_session"
        private const val DIAG_FILE_NAME = "hyperos-tdk-live-diagnostics.txt"
        private const val MAX_COMMAND_AGE_MS = 120_000L
        private const val MAX_DIAG_FILE_BYTES = 2L * 1024L * 1024L
        private const val KEEP_DIAG_CHARS = 900_000
        private const val MAX_SNAPSHOT_CHARS = 350_000
        private const val TAG = "HyperOS-TDK-IPC"
    }

    private val lock = Any()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {
            METHOD_PUBLISH -> publish(extras)
            METHOD_CONSUME -> consume()
            METHOD_REPORT_RESULT -> reportResult(extras)
            METHOD_GET_RESULT -> getResult(arg)
            METHOD_DIAG_START -> startDiagnosticsSession()
            METHOD_DIAG_APPEND -> appendDiagnostics(extras)
            METHOD_DIAG_SNAPSHOT -> diagnosticsSnapshot()
            METHOD_DIAG_CLEAR -> clearDiagnosticsAndRestart()
            else -> throw IllegalArgumentException("Unsupported method: $method")
        }
    }

    private fun publish(extras: Bundle?): Bundle {
        enforceOwnAppCaller()
        val requestId = extras?.getString(KEY_REQUEST_ID).orEmpty()
        val displayName = extras?.getString(KEY_DISPLAY_NAME).orEmpty()
        val uriText = extras?.getString(KEY_URI).orEmpty()
        val createdAt = extras?.getLong(KEY_CREATED_AT, 0L) ?: 0L

        require(requestId.isNotBlank()) { "request_id is missing" }
        require(displayName.endsWith(".mtz", ignoreCase = true)) { "display_name is not .mtz" }
        val uri = Uri.parse(uriText)
        require(uri.scheme == "content") { "Only content:// URIs are accepted" }
        require(createdAt > 0L) { "created_at is missing" }

        synchronized(lock) {
            resultPrefs().edit().clear().commit()
            commandPrefs().edit()
                .putString(KEY_REQUEST_ID, requestId)
                .putString(KEY_DISPLAY_NAME, displayName)
                .putString(KEY_URI, uriText)
                .putLong(KEY_CREATED_AT, createdAt)
                .commit()

            appendDiagnosticInternal(
                source = "HyperOS-TDK",
                event = "IMPORT_COMMAND_PUBLISHED",
                detail = "request=$requestId displayName=$displayName uri=$uri",
                level = "INFO",
                eventAt = System.currentTimeMillis()
            )
        }

        context?.contentResolver?.notifyChange(CONTROL_URI, null)
        Log.i(TAG, "Provider command published: request=$requestId uri=$uri")
        return Bundle().apply { putBoolean("accepted", true) }
    }

    private fun consume(): Bundle {
        enforceThemeManagerCaller()
        synchronized(lock) {
            val prefs = commandPrefs()
            val requestId = prefs.getString(KEY_REQUEST_ID, null)
            val displayName = prefs.getString(KEY_DISPLAY_NAME, null)
            val uriText = prefs.getString(KEY_URI, null)
            val createdAt = prefs.getLong(KEY_CREATED_AT, 0L)

            if (requestId.isNullOrBlank() || displayName.isNullOrBlank() || uriText.isNullOrBlank()) {
                return Bundle().apply { putBoolean(KEY_PRESENT, false) }
            }

            prefs.edit().clear().commit()

            val age = System.currentTimeMillis() - createdAt
            if (createdAt <= 0L || age < 0L || age > MAX_COMMAND_AGE_MS) {
                appendDiagnosticInternal(
                    source = "ThemeManager",
                    event = "IMPORT_COMMAND_EXPIRED",
                    detail = "request=$requestId ageMs=$age",
                    level = "WARN",
                    eventAt = System.currentTimeMillis()
                )
                Log.w(TAG, "Provider command expired: request=$requestId ageMs=$age")
                return Bundle().apply { putBoolean(KEY_PRESENT, false) }
            }

            appendDiagnosticInternal(
                source = "ThemeManager",
                event = "IMPORT_COMMAND_CONSUMED",
                detail = "request=$requestId ageMs=$age displayName=$displayName",
                level = "INFO",
                eventAt = System.currentTimeMillis()
            )
            Log.i(TAG, "Provider command consumed by Theme Manager: request=$requestId ageMs=$age")
            return Bundle().apply {
                putBoolean(KEY_PRESENT, true)
                putString(KEY_REQUEST_ID, requestId)
                putString(KEY_DISPLAY_NAME, displayName)
                putString(KEY_URI, uriText)
                putLong(KEY_CREATED_AT, createdAt)
            }
        }
    }

    private fun reportResult(extras: Bundle?): Bundle {
        enforceThemeManagerCaller()
        val requestId = extras?.getString(KEY_REQUEST_ID).orEmpty()
        val status = extras?.getString(KEY_STATUS).orEmpty()
        val message = extras?.getString(KEY_MESSAGE).orEmpty()
        val resultAt = extras?.getLong(KEY_RESULT_AT, 0L) ?: 0L

        require(requestId.isNotBlank()) { "result request_id is missing" }
        require(status in setOf(STATUS_QUEUED, STATUS_START, STATUS_COMPLETE, STATUS_FAIL, STATUS_QUEUE_ERROR)) {
            "unsupported result status: $status"
        }
        require(resultAt > 0L) { "result_at is missing" }

        synchronized(lock) {
            resultPrefs().edit()
                .putString(KEY_REQUEST_ID, requestId)
                .putString(KEY_STATUS, status)
                .putString(KEY_MESSAGE, message)
                .putLong(KEY_RESULT_AT, resultAt)
                .commit()

            appendDiagnosticInternal(
                source = "ThemeManager",
                event = "IMPORT_LIFECYCLE_${status.uppercase(Locale.ROOT)}",
                detail = "request=$requestId message=$message",
                level = if (status in setOf(STATUS_FAIL, STATUS_QUEUE_ERROR)) "ERROR" else "INFO",
                eventAt = resultAt
            )
        }
        Log.i(TAG, "Provider import result: request=$requestId status=$status message=$message")
        return Bundle().apply { putBoolean("accepted", true) }
    }

    private fun getResult(requestId: String?): Bundle {
        enforceOwnAppCaller()
        synchronized(lock) {
            val prefs = resultPrefs()
            val storedRequestId = prefs.getString(KEY_REQUEST_ID, null)
            val status = prefs.getString(KEY_STATUS, null)
            if (storedRequestId.isNullOrBlank() || status.isNullOrBlank()) {
                return Bundle().apply { putBoolean(KEY_PRESENT, false) }
            }
            if (!requestId.isNullOrBlank() && requestId != storedRequestId) {
                return Bundle().apply { putBoolean(KEY_PRESENT, false) }
            }
            return Bundle().apply {
                putBoolean(KEY_PRESENT, true)
                putString(KEY_REQUEST_ID, storedRequestId)
                putString(KEY_STATUS, status)
                putString(KEY_MESSAGE, prefs.getString(KEY_MESSAGE, ""))
                putLong(KEY_RESULT_AT, prefs.getLong(KEY_RESULT_AT, 0L))
            }
        }
    }

    private fun startDiagnosticsSession(): Bundle {
        enforceOwnAppCaller()
        synchronized(lock) {
            val prefs = diagPrefs()
            var sessionId = prefs.getString(KEY_SESSION_ID, null)
            var startedAt = prefs.getLong(KEY_SESSION_STARTED_AT, 0L)
            if (sessionId.isNullOrBlank() || startedAt <= 0L) {
                sessionId = UUID.randomUUID().toString()
                startedAt = System.currentTimeMillis()
                prefs.edit()
                    .putString(KEY_SESSION_ID, sessionId)
                    .putLong(KEY_SESSION_STARTED_AT, startedAt)
                    .putInt(KEY_DIAG_COUNT, 0)
                    .commit()
                diagnosticsFile().writeText(
                    "HyperOS TDK Live Diagnostics\n" +
                        "Session: $sessionId\n" +
                        "Started: ${formatTimestamp(startedAt)}\n" +
                        "============================================================\n"
                )
            }
            return buildDiagnosticsSnapshot(sessionId, startedAt)
        }
    }

    private fun appendDiagnostics(extras: Bundle?): Bundle {
        enforceDiagnosticsWriterCaller()
        val source = extras?.getString(KEY_DIAG_SOURCE).orEmpty().ifBlank { "unknown" }
        val event = extras?.getString(KEY_DIAG_EVENT).orEmpty().ifBlank { "EVENT" }
        val detail = extras?.getString(KEY_DIAG_DETAIL).orEmpty()
        val level = extras?.getString(KEY_DIAG_LEVEL).orEmpty().ifBlank { "INFO" }
        val eventAt = extras?.getLong(KEY_DIAG_EVENT_AT, 0L)?.takeIf { it > 0L }
            ?: System.currentTimeMillis()

        synchronized(lock) {
            val accepted = appendDiagnosticInternal(source, event, detail, level, eventAt)
            return Bundle().apply { putBoolean("accepted", accepted) }
        }
    }

    private fun diagnosticsSnapshot(): Bundle {
        enforceOwnAppCaller()
        synchronized(lock) {
            val prefs = diagPrefs()
            val sessionId = prefs.getString(KEY_SESSION_ID, null)
            val startedAt = prefs.getLong(KEY_SESSION_STARTED_AT, 0L)
            if (sessionId.isNullOrBlank() || startedAt <= 0L) {
                return Bundle().apply { putBoolean(KEY_PRESENT, false) }
            }
            return buildDiagnosticsSnapshot(sessionId, startedAt)
        }
    }

    private fun clearDiagnosticsAndRestart(): Bundle {
        enforceOwnAppCaller()
        synchronized(lock) {
            diagPrefs().edit().clear().commit()
            diagnosticsFile().delete()
        }
        return startDiagnosticsSession()
    }

    private fun appendDiagnosticInternal(
        source: String,
        event: String,
        detail: String,
        level: String,
        eventAt: Long
    ): Boolean {
        val prefs = diagPrefs()
        val sessionId = prefs.getString(KEY_SESSION_ID, null)
        val startedAt = prefs.getLong(KEY_SESSION_STARTED_AT, 0L)
        if (sessionId.isNullOrBlank() || startedAt <= 0L) {
            return false
        }

        val safeSource = oneLine(source, 80)
        val safeEvent = oneLine(event, 120)
        val safeLevel = oneLine(level.uppercase(Locale.ROOT), 16)
        val safeDetail = oneLine(detail, 1800)
        val line = "${formatTimestamp(eventAt)} | $safeLevel | $safeSource | $safeEvent" +
            if (safeDetail.isBlank()) "\n" else " | $safeDetail\n"

        val file = diagnosticsFile()
        file.appendText(line)
        val nextCount = prefs.getInt(KEY_DIAG_COUNT, 0) + 1
        prefs.edit().putInt(KEY_DIAG_COUNT, nextCount).apply()
        trimDiagnosticsFileIfNeeded(file)
        return true
    }

    private fun buildDiagnosticsSnapshot(sessionId: String, startedAt: Long): Bundle {
        val file = diagnosticsFile()
        val text = if (file.exists()) file.readText() else ""
        val snapshot = if (text.length > MAX_SNAPSHOT_CHARS) {
            "[Earlier diagnostics omitted from live view; full rolling log is retained.]\n" +
                text.takeLast(MAX_SNAPSHOT_CHARS)
        } else {
            text
        }
        return Bundle().apply {
            putBoolean(KEY_PRESENT, true)
            putString(KEY_SESSION_ID, sessionId)
            putLong(KEY_SESSION_STARTED_AT, startedAt)
            putInt(KEY_DIAG_COUNT, diagPrefs().getInt(KEY_DIAG_COUNT, 0))
            putString(KEY_DIAG_TEXT, snapshot)
        }
    }

    private fun trimDiagnosticsFileIfNeeded(file: File) {
        if (!file.exists() || file.length() <= MAX_DIAG_FILE_BYTES) return
        val text = file.readText()
        val tail = text.takeLast(KEEP_DIAG_CHARS)
        file.writeText(
            "HyperOS TDK Live Diagnostics\n" +
                "[Rolling log trimmed; oldest entries were removed automatically.]\n" +
                "============================================================\n" +
                tail
        )
    }

    private fun diagnosticsFile(): File = File(requireNotNull(context).filesDir, DIAG_FILE_NAME)

    private fun formatTimestamp(epochMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(epochMs))

    private fun oneLine(value: String?, maxLength: Int): String {
        val normalized = value.orEmpty().replace('\n', ' ').replace('\r', ' ')
        return if (normalized.length > maxLength) normalized.take(maxLength) + "…" else normalized
    }

    private fun enforceOwnAppCaller() {
        val callerUid = Binder.getCallingUid()
        if (callerUid != Process.myUid()) {
            throw SecurityException("Only HyperOS TDK can publish/read import state")
        }
    }

    private fun enforceThemeManagerCaller() {
        val callerUid = Binder.getCallingUid()
        val packages = context?.packageManager?.getPackagesForUid(callerUid)?.toSet().orEmpty()
        val reportedPackage = callingPackage
        val ownsThemeManager = TARGET_PACKAGE in packages
        val packageMatches = reportedPackage == null || reportedPackage == TARGET_PACKAGE
        if (!ownsThemeManager || !packageMatches) {
            throw SecurityException(
                "Only Theme Manager can consume/report import state: uid=$callerUid package=$reportedPackage"
            )
        }
    }

    private fun enforceDiagnosticsWriterCaller() {
        val callerUid = Binder.getCallingUid()
        if (callerUid == Process.myUid()) return
        val packages = context?.packageManager?.getPackagesForUid(callerUid)?.toSet().orEmpty()
        val reportedPackage = callingPackage
        if (TARGET_PACKAGE !in packages || (reportedPackage != null && reportedPackage != TARGET_PACKAGE)) {
            throw SecurityException(
                "Only HyperOS TDK or Theme Manager can append diagnostics: uid=$callerUid package=$reportedPackage"
            )
        }
    }

    private fun commandPrefs() = requireNotNull(context)
        .getSharedPreferences(COMMAND_PREFS, android.content.Context.MODE_PRIVATE)

    private fun resultPrefs() = requireNotNull(context)
        .getSharedPreferences(RESULT_PREFS, android.content.Context.MODE_PRIVATE)

    private fun diagPrefs() = requireNotNull(context)
        .getSharedPreferences(DIAG_PREFS, android.content.Context.MODE_PRIVATE)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = throw UnsupportedOperationException("Use ContentProvider.call()")

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Use ContentProvider.call()")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use ContentProvider.call()")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("Use ContentProvider.call()")
}
