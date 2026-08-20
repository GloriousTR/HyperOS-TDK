package com.glorious.hyperostdk.data

import android.content.Context
import android.os.Environment
import com.glorious.hyperostdk.model.FrameworkArtifactExportResult
import com.glorious.hyperostdk.model.FrameworkArtifactInfo
import dalvik.system.DexFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.LinkedHashSet
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FrameworkArtifactExporter {
    private const val THEME_MANAGER_PACKAGE = "com.android.thememanager"
    private const val DEFAULT_DESCRIPTOR = "miui.content.res.IThemeService"
    private const val MAX_DISCOVERED_FILES = 40

    private val archiveTimestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    private val knownPaths = listOf(
        "/system_ext/app/miuisystem/miuisystem.apk",
        "/system/app/miuisystem/miuisystem.apk",
        "/system_ext/app/miui/miui.apk",
        "/system/app/miui/miui.apk",
        "/system_ext/framework/miui-framework.jar",
        "/system/framework/miui-framework.jar",
        "/system_ext/framework/miui.jar",
        "/system/framework/miui.jar",
        "/product/framework/miui-framework.jar"
    )

    private val discoveryRoots = listOf(
        "/system_ext/app",
        "/system_ext/priv-app",
        "/system/app",
        "/system/priv-app",
        "/product/app",
        "/product/priv-app",
        "/system_ext/framework",
        "/system/framework",
        "/product/framework"
    )

    suspend fun probeAndExport(
        context: Context,
        descriptor: String = DEFAULT_DESCRIPTOR
    ): FrameworkArtifactExportResult = withContext(Dispatchers.IO) {
        val targetStub = "$descriptor\$Stub"
        val themeManagerPaths = themeManagerSourcePaths(context)
        val candidatePaths = discoverCandidatePaths(themeManagerPaths)

        val scanned = candidatePaths.map { path ->
            inspectArtifact(File(path), descriptor, targetStub)
        }

        val readableByPath = scanned
            .filter { it.exists && it.readable }
            .associateBy { it.path }

        val selectedPaths = LinkedHashSet<String>()

        scanned.filter { it.containsInterface == true || it.containsStub == true }
            .forEach { selectedPaths += it.path }

        scanned.filter { File(it.path).name.equals("miuisystem.apk", ignoreCase = true) && it.readable }
            .forEach { selectedPaths += it.path }

        themeManagerPaths.filter { readableByPath[it]?.readable == true }
            .forEach { selectedPaths += it }

        if (selectedPaths.isEmpty()) {
            return@withContext FrameworkArtifactExportResult(
                descriptor = descriptor,
                artifacts = scanned,
                archivePath = null,
                archiveName = null,
                archiveSizeBytes = null,
                exportedFiles = emptyList(),
                error = "No readable MIUI framework or Theme Manager artifact was found to export."
            )
        }

        val selectedSet = selectedPaths.toSet()
        val enriched = scanned.map { info ->
            if (info.path in selectedSet && info.sha256 == null) {
                info.copy(sha256 = runCatching { sha256(File(info.path)) }.getOrNull())
            } else {
                info
            }
        }

        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val directory = File(root, "framework-artifacts").apply { mkdirs() }
        val archive = File(
            directory,
            "hyperos-tdk-framework-${LocalDateTime.now().format(archiveTimestamp)}.zip"
        )

        val exportedNames = mutableListOf<String>()
        var archiveError: String? = null

        runCatching {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(archive))).use { zip ->
                val metadata = buildMetadata(descriptor, enriched, selectedPaths)
                zip.putNextEntry(ZipEntry("hyperos-tdk-artifacts.txt"))
                zip.write(metadata.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                selectedPaths.forEachIndexed { index, path ->
                    val source = File(path)
                    if (!source.isFile || !source.canRead()) return@forEachIndexed

                    val parent = source.parentFile?.name.orEmpty().ifBlank { "system" }
                    val safeParent = parent.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val safeName = source.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val entryName = "%02d-%s-%s".format(index + 1, safeParent, safeName)

                    zip.putNextEntry(ZipEntry(entryName))
                    BufferedInputStream(FileInputStream(source)).use { input ->
                        input.copyTo(zip, bufferSize = 64 * 1024)
                    }
                    zip.closeEntry()
                    exportedNames += entryName
                }
            }
        }.onFailure {
            archiveError = "Archive creation failed: ${it.javaClass.simpleName}: ${it.message}"
        }

        if (archiveError != null || !archive.exists()) {
            runCatching { archive.delete() }
            return@withContext FrameworkArtifactExportResult(
                descriptor = descriptor,
                artifacts = enriched,
                archivePath = null,
                archiveName = null,
                archiveSizeBytes = null,
                exportedFiles = exportedNames,
                error = archiveError ?: "Archive was not created."
            )
        }

        FrameworkArtifactExportResult(
            descriptor = descriptor,
            artifacts = enriched,
            archivePath = archive.absolutePath,
            archiveName = archive.name,
            archiveSizeBytes = archive.length(),
            exportedFiles = exportedNames,
            error = null
        )
    }

    private fun themeManagerSourcePaths(context: Context): List<String> = runCatching {
        val appInfo = context.packageManager.getApplicationInfo(THEME_MANAGER_PACKAGE, 0)
        buildList {
            appInfo.sourceDir?.let(::add)
            appInfo.splitSourceDirs?.forEach(::add)
        }
    }.getOrDefault(emptyList())

    private fun discoverCandidatePaths(themeManagerPaths: List<String>): List<String> {
        val paths = LinkedHashSet<String>()
        knownPaths.forEach(paths::add)
        themeManagerPaths.forEach(paths::add)

        discoveryRoots.forEach { rootPath ->
            if (paths.size >= MAX_DISCOVERED_FILES) return@forEach
            val root = File(rootPath)
            val firstLevel = runCatching { root.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())

            firstLevel.forEach { item ->
                if (paths.size >= MAX_DISCOVERED_FILES) return@forEach
                if (item.isFile && isInterestingArtifact(item)) {
                    paths += item.absolutePath
                } else if (item.isDirectory) {
                    val secondLevel = runCatching { item.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
                    secondLevel.filter(::isInterestingArtifact).forEach { artifact ->
                        if (paths.size < MAX_DISCOVERED_FILES) paths += artifact.absolutePath
                    }
                }
            }
        }

        return paths.toList()
    }

    private fun isInterestingArtifact(file: File): Boolean {
        if (!file.isFile) return false
        val name = file.name.lowercase()
        val supported = name.endsWith(".apk") || name.endsWith(".jar")
        if (!supported) return false

        val path = file.absolutePath.lowercase()
        return name.contains("miui") ||
            name.contains("theme") ||
            path.contains("/miui/") ||
            path.contains("/miuisystem/") ||
            path.contains("thememanager")
    }

    private fun inspectArtifact(
        file: File,
        descriptor: String,
        stubDescriptor: String
    ): FrameworkArtifactInfo {
        val exists = file.exists() && file.isFile
        val readable = exists && file.canRead()

        if (!readable) {
            return FrameworkArtifactInfo(
                path = file.absolutePath,
                exists = exists,
                readable = false,
                sizeBytes = if (exists) file.length() else null,
                containsInterface = null,
                containsStub = null,
                sha256 = null,
                scanError = if (exists) "File exists but is not readable." else null
            )
        }

        var interfaceFound = false
        var stubFound = false
        var scanError: String? = null

        runCatching {
            val dexFile = DexFile(file.absolutePath)
            try {
                val entries = dexFile.entries()
                while (entries.hasMoreElements() && (!interfaceFound || !stubFound)) {
                    when (entries.nextElement()) {
                        descriptor -> interfaceFound = true
                        stubDescriptor -> stubFound = true
                    }
                }
            } finally {
                runCatching { dexFile.close() }
            }
        }.onFailure {
            scanError = "Dex scan failed: ${it.javaClass.simpleName}: ${it.message}"
        }

        return FrameworkArtifactInfo(
            path = file.absolutePath,
            exists = true,
            readable = true,
            sizeBytes = file.length(),
            containsInterface = if (scanError == null) interfaceFound else null,
            containsStub = if (scanError == null) stubFound else null,
            sha256 = null,
            scanError = scanError
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun buildMetadata(
        descriptor: String,
        artifacts: List<FrameworkArtifactInfo>,
        selectedPaths: Set<String>
    ): String = buildString {
        appendLine("HyperOS TDK Framework Artifact Export")
        appendLine("====================================")
        appendLine("Descriptor: $descriptor")
        appendLine()
        artifacts.forEach { artifact ->
            appendLine("Path: ${artifact.path}")
            appendLine("Selected: ${artifact.path in selectedPaths}")
            appendLine("Exists: ${artifact.exists}")
            appendLine("Readable: ${artifact.readable}")
            appendLine("Size: ${artifact.sizeBytes ?: -1}")
            appendLine("Contains interface: ${artifact.containsInterface ?: "unknown"}")
            appendLine("Contains Stub: ${artifact.containsStub ?: "unknown"}")
            appendLine("SHA-256: ${artifact.sha256 ?: "not calculated"}")
            artifact.scanError?.let { appendLine("Scan error: $it") }
            appendLine()
        }
    }
}
