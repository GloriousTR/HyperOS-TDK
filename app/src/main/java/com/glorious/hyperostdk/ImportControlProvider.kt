package com.glorious.hyperostdk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log

class ImportControlProvider : ContentProvider() {
    companion object {
        const val AUTHORITY = "com.glorious.hyperostdk.control"
        val CONTROL_URI: Uri = Uri.parse("content://$AUTHORITY/command")

        const val METHOD_PUBLISH = "publish"
        const val METHOD_CONSUME = "consume"
        const val METHOD_REPORT_RESULT = "report_result"
        const val METHOD_GET_RESULT = "get_result"

        const val KEY_PRESENT = "present"
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_URI = "uri"
        const val KEY_CREATED_AT = "created_at"
        const val KEY_STATUS = "status"
        const val KEY_MESSAGE = "message"
        const val KEY_RESULT_AT = "result_at"

        const val STATUS_QUEUED = "queued"
        const val STATUS_START = "start"
        const val STATUS_COMPLETE = "complete"
        const val STATUS_FAIL = "fail"
        const val STATUS_QUEUE_ERROR = "queue_error"

        private const val TARGET_PACKAGE = "com.android.thememanager"
        private const val COMMAND_PREFS = "import_control"
        private const val RESULT_PREFS = "import_result"
        private const val MAX_COMMAND_AGE_MS = 120_000L
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
                Log.w(TAG, "Provider command expired: request=$requestId ageMs=$age")
                return Bundle().apply { putBoolean(KEY_PRESENT, false) }
            }

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

    private fun commandPrefs() = requireNotNull(context)
        .getSharedPreferences(COMMAND_PREFS, android.content.Context.MODE_PRIVATE)

    private fun resultPrefs() = requireNotNull(context)
        .getSharedPreferences(RESULT_PREFS, android.content.Context.MODE_PRIVATE)

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
