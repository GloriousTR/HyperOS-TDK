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

        const val KEY_PRESENT = "present"
        const val KEY_REQUEST_ID = "request_id"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_URI = "uri"
        const val KEY_CREATED_AT = "created_at"

        private const val TARGET_PACKAGE = "com.android.thememanager"
        private const val PREFS = "import_control"
        private const val MAX_COMMAND_AGE_MS = 120_000L
        private const val TAG = "HyperOS-TDK-IPC"
    }

    private val lock = Any()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        return when (method) {
            METHOD_PUBLISH -> publish(extras)
            METHOD_CONSUME -> consume()
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
            prefs().edit()
                .putString(KEY_REQUEST_ID, requestId)
                .putString(KEY_DISPLAY_NAME, displayName)
                .putString(KEY_URI, uriText)
                .putLong(KEY_CREATED_AT, createdAt)
                .commit()
        }

        context?.contentResolver?.notifyChange(CONTROL_URI, null)
        Log.i(TAG, "Provider command published: request=$requestId uri=$uri")
        return Bundle().apply {
            putBoolean("accepted", true)
        }
    }

    private fun consume(): Bundle {
        enforceThemeManagerCaller()
        synchronized(lock) {
            val prefs = prefs()
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

    private fun enforceOwnAppCaller() {
        val callerUid = Binder.getCallingUid()
        if (callerUid != Process.myUid()) {
            throw SecurityException("Only HyperOS TDK can publish import commands")
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
                "Only Theme Manager can consume import commands: uid=$callerUid package=$reportedPackage"
            )
        }
    }

    private fun prefs() = requireNotNull(context)
        .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

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
