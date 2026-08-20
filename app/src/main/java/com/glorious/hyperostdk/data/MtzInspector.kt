package com.glorious.hyperostdk.data

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.glorious.hyperostdk.model.MtzInfo
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object MtzInspector {
    private const val MAX_ENTRY_COUNT = 500

    fun inspect(contentResolver: ContentResolver, uri: Uri): MtzInfo {
        val metadata = readMetadata(contentResolver, uri)
        val sha256 = contentResolver.openInputStream(uri)?.use(::sha256)
            ?: error("Selected file could not be opened.")

        val zipEntries = mutableListOf<String>()
        var isZip = false
        var warning: String? = null

        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null && zipEntries.size < MAX_ENTRY_COUNT) {
                        isZip = true
                        zipEntries += entry.name
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    if (entry != null) {
                        warning = "Archive contains more than $MAX_ENTRY_COUNT entries; list was truncated."
                    }
                }
            }
        }.onFailure {
            warning = "The file does not appear to be a readable ZIP/MTZ container: ${it.message}"
        }

        if (!metadata.displayName.endsWith(".mtz", ignoreCase = true)) {
            warning = listOfNotNull(
                warning,
                "Selected file name does not end with .mtz."
            ).joinToString(" ")
        }

        return MtzInfo(
            uri = uri.toString(),
            displayName = metadata.displayName,
            mimeType = contentResolver.getType(uri),
            sizeBytes = metadata.sizeBytes,
            sha256 = sha256,
            isZipContainer = isZip,
            entries = zipEntries,
            warning = warning
        )
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readMetadata(contentResolver: ContentResolver, uri: Uri): Metadata {
        var name = uri.lastPathSegment ?: "selected.mtz"
        var size: Long? = null

        val cursor: Cursor? = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !it.isNull(nameIndex)) name = it.getString(nameIndex)
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
            }
        }

        return Metadata(name, size)
    }

    private data class Metadata(
        val displayName: String,
        val sizeBytes: Long?
    )
}
