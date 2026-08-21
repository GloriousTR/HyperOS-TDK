package com.glorious.hyperostdk.privileged

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.glorious.hyperostdk.DiagnosticsSessionClient
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object ThemeKitCompatInstaller {
    private data class ArchiveManifest(
        val platform: Int,
        val version: String,
        val miuiAdapterVersion: String?,
        val author: String,
        val designer: String,
        val title: String,
        val description: String,
        val authors: LinkedHashMap<String, String>,
        val designers: LinkedHashMap<String, String>,
        val titles: LinkedHashMap<String, String>,
        val descriptions: LinkedHashMap<String, String>,
        val screenRatio: String?,
        val supportHomeSearchBar: Boolean,
        val fontWeight: String?,
        val isBackUpVersion: Boolean,
        val isSingleResource: Boolean,
        val wallpaperStyle: Int?,
        val officialIcons: Boolean,
        val themeType: Int
    )

    private data class SubResource(
        val resourceCode: String,
        val localId: String,
        val contentFile: File,
        val metaFile: File
    )

    suspend fun installAndOpen(
        context: Context,
        displayName: String,
        sourceUri: Uri,
        requestAutomaticApply: Boolean = true
    ): PrivilegedThemeEngine.InstallResult {
        val capability = PrivilegedThemeEngine.probe(context)
        check(capability.ready) { capability.detail }

        val mainLocalId = UUID.randomUUID().toString()
        DiagnosticsSessionClient.append(
            context,
            "PRIVILEGED_IMPORT_STARTED",
            "name=$displayName • localId=$mainLocalId • backend=${capability.state.backend} • schema=themekit-compat-v2"
        )

        val externalBase = context.getExternalFilesDir("imports")
            ?: error("External files directory is unavailable")
        val jobRoot = File(externalBase, mainLocalId).apply {
            deleteRecursively()
            mkdirs()
        }
        val sourceFile = File(jobRoot, "source.mtz")
        val stageRoot = File(jobRoot, "remote").apply { mkdirs() }

        var installedRemoteFiles: List<String> = emptyList()
        try {
            DiagnosticsSessionClient.append(context, "MRC_BUILD_STARTED", "localId=$mainLocalId • schema=themekit-compat-v2")
            val sourceHash = copyUriAndSha1(context, sourceUri, sourceFile)
            val sourceSize = sourceFile.length()

            lateinit var manifest: ArchiveManifest
            lateinit var subResources: List<SubResource>
            lateinit var previewNames: List<String>

            ZipFile(sourceFile).use { archive ->
                manifest = parseManifest(archive, displayName)
                previewNames = collectPreviewNames(archive)

                val mainMrc = File(stageRoot, "content/theme/$mainLocalId.mrc")
                mainMrc.parentFile?.mkdirs()
                if (!mainMrc.exists()) mainMrc.createNewFile()

                extractPreviews(archive, stageRoot, mainLocalId, previewNames)
                subResources = extractSubResources(
                    archive = archive,
                    stageRoot = stageRoot,
                    mainLocalId = mainLocalId,
                    manifest = manifest,
                    previewNames = previewNames
                )
            }

            DiagnosticsSessionClient.append(
                context,
                "MRC_BUILD_SUCCESS",
                "main=$mainLocalId.mrc • sourceSha1=$sourceHash • subResources=${subResources.size} • previews=${previewNames.size}"
            )
            DiagnosticsSessionClient.append(
                context,
                "PREVIEW_STAGE_SUCCESS",
                "localId=$mainLocalId • previewFiles=${previewNames.size} • thumbnails=${previewNames.count { it.contains("small", ignoreCase = true) }}"
            )

            DiagnosticsSessionClient.append(context, "MRM_BUILD_STARTED", "localId=$mainLocalId • schema=themekit-compat-v2")
            val mainMrm = File(stageRoot, "meta/theme/$mainLocalId.mrm")
            mainMrm.parentFile?.mkdirs()
            val mainJson = buildMainMetadata(mainLocalId, sourceHash, sourceSize, manifest, subResources, previewNames)
            mainMrm.writeText(mainJson.toString(), Charsets.UTF_8)

            val expectedContentPath = "$THEMEKIT_CONTENT_THEME_ROOT$mainLocalId.mrc"
            check(mainMrm.length() > 0L) { "Main MRM is empty" }
            check(mainJson.optString("contentPath") == expectedContentPath) { "ThemeKit contentPath mismatch" }
            check(mainJson.opt("builtInPreviews") is JSONObject) { "builtInPreviews must be a localized object" }
            check(mainJson.opt("builtInThumbnails") is JSONObject) { "builtInThumbnails must be a localized object" }

            DiagnosticsSessionClient.append(
                context,
                "MRM_BUILD_SUCCESS",
                "main=$mainLocalId.mrm • bytes=${mainMrm.length()} • platform=${manifest.platform} • adapter=${manifest.miuiAdapterVersion} • schema=themekit-compat-v2"
            )
            DiagnosticsSessionClient.append(
                context,
                "MRM_VALIDATION_SUCCESS",
                "subResources=${subResources.size} • previews=${previewNames.size} • contentPath=$expectedContentPath • localizedPreviewSchema=true"
            )

            val stageFiles = stageRoot.walkTopDown().filter { it.isFile }.toList()
            check(stageFiles.isNotEmpty()) { "No staged Theme Manager files were generated" }
            installedRemoteFiles = stageFiles.map { "$REMOTE_DATA_ROOT/${it.relativeTo(stageRoot).invariantSeparatorsPath}" }
            DiagnosticsSessionClient.append(
                context,
                "STAGING_WRITE_SUCCESS",
                "files=${stageFiles.size} • previews=${previewNames.size} • source=${stageRoot.absolutePath}"
            )

            val installResult = ShizukuBridge.exec(context, buildInstallScript(stageRoot, stageFiles, mainLocalId))
            if (!installResult.success) {
                cleanupRemote(context, installedRemoteFiles, mainLocalId)
                error("Theme Manager storage install failed (exit=${installResult.exitCode}): ${installResult.output.take(1000)}")
            }
            DiagnosticsSessionClient.append(
                context,
                "THEME_STORAGE_INSTALL_SUCCESS",
                "localId=$mainLocalId • files=${stageFiles.size} • previews=${previewNames.size} • backend=${capability.state.backend}"
            )

            val remoteMrm = "$REMOTE_DATA_ROOT/meta/theme/$mainLocalId.mrm"
            val remoteVerify = ShizukuBridge.exec(
                context,
                "set -e; test -s ${shellQuote(remoteMrm)}; grep -F ${shellQuote(expectedContentPath)} ${shellQuote(remoteMrm)} >/dev/null"
            )
            check(remoteVerify.success) { "Remote metadata verification failed: ${remoteVerify.output.take(1000)}" }
            DiagnosticsSessionClient.append(
                context,
                "REMOTE_METADATA_VERIFY_SUCCESS",
                "localId=$mainLocalId • exactContentPath=true • localizedPreviewSchema=true"
            )

            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("ViewLocalResource://view.local.resource#$mainLocalId")).apply {
                setPackage(THEME_MANAGER_PACKAGE)
                putExtra("REQUEST_RESOURCE_CODE", "theme")
                putExtra("REQUEST_APPLY_EVENT", requestAutomaticApply)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            context.startActivity(intent)
            DiagnosticsSessionClient.append(
                context,
                "LOCAL_RESOURCE_INTENT_SENT",
                "localId=$mainLocalId • REQUEST_RESOURCE_CODE=theme • REQUEST_APPLY_EVENT=$requestAutomaticApply • flags=ThemeKitExact"
            )

            delay(1_200)
            val launchProbe = runCatching {
                ShizukuBridge.exec(context, "dumpsys activity activities | grep -m 8 -E 'com.android.thememanager|ViewLocalResource'")
            }.getOrNull()
            DiagnosticsSessionClient.append(
                context,
                "LOCAL_RESOURCE_ACTIVITY_PROBE",
                "localId=$mainLocalId • ${launchProbe?.output?.replace('\n', ' ')?.take(1800) ?: "probe unavailable"}"
            )

            val applyTriggered = if (requestAutomaticApply) tryTriggerApply(context) else false
            val message = if (applyTriggered) {
                "Theme Manager local resource açıldı ve Apply düğmesi tetiklendi."
            } else {
                "ThemeKit uyumlu local resource gönderildi. Tema detay ekranı görünürse Uygula'ya dokunabilirsiniz."
            }
            DiagnosticsSessionClient.append(
                context,
                if (applyTriggered) "APPLY_TRIGGERED" else "APPLY_AWAITING_USER",
                "localId=$mainLocalId • $message"
            )

            jobRoot.deleteRecursively()
            return PrivilegedThemeEngine.InstallResult(mainLocalId, subResources.size, true, applyTriggered, message)
        } catch (error: Throwable) {
            DiagnosticsSessionClient.append(
                context,
                "PRIVILEGED_IMPORT_FAILED",
                "localId=$mainLocalId • schema=themekit-compat-v2 • ${error.javaClass.simpleName}: ${error.message}",
                level = "ERROR"
            )
            if (installedRemoteFiles.isNotEmpty()) runCatching { cleanupRemote(context, installedRemoteFiles, mainLocalId) }
            throw error
        }
    }

    private fun collectPreviewNames(archive: ZipFile): List<String> = archive.entries().toList()
        .asSequence()
        .filter { !it.isDirectory }
        .map { it.name.replace('\\', '/').trimStart('/') }
        .filter { it.startsWith("preview/", ignoreCase = true) }
        .map { it.substringAfter("preview/") }
        .filter { it.isNotBlank() && !it.split('/').contains("..") }
        .distinct()
        .toList()

    private fun extractPreviews(archive: ZipFile, stageRoot: File, mainLocalId: String, previewNames: List<String>) {
        if (previewNames.isEmpty()) return
        val previewBase = File(stageRoot, "preview/theme/$mainLocalId").apply { mkdirs() }
        val baseCanonical = previewBase.canonicalFile
        previewNames.forEach { relative ->
            val entry = archive.entries().toList().firstOrNull {
                it.name.replace('\\', '/').trimStart('/').equals("preview/$relative", ignoreCase = true)
            } ?: return@forEach
            val target = File(previewBase, relative).canonicalFile
            check(target.path.startsWith(baseCanonical.path + File.separator)) { "Unsafe preview path: $relative" }
            target.parentFile?.mkdirs()
            archive.getInputStream(entry).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
        }
    }

    private fun extractSubResources(
        archive: ZipFile,
        stageRoot: File,
        mainLocalId: String,
        manifest: ArchiveManifest,
        previewNames: List<String>
    ): List<SubResource> {
        val entries = archive.entries().toList()
            .filter { !it.isDirectory }
            .mapNotNull { entry -> resourceCodeFor(entry.name)?.let { code -> entry to code } }
            .sortedBy { (entry, _) -> if (entry.name.contains('/')) 1 else 0 }
        val selected = LinkedHashMap<String, ZipEntry>()
        entries.forEach { (entry, code) -> if (!selected.containsKey(code)) selected[code] = entry }

        return selected.map { (resourceCode, entry) ->
            val localId = UUID.randomUUID().toString()
            val contentFile = File(stageRoot, "content/$resourceCode/$localId.mrc")
            contentFile.parentFile?.mkdirs()
            val digest = MessageDigest.getInstance("SHA-1")
            archive.getInputStream(entry).use { input ->
                FileOutputStream(contentFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                }
            }
            val metaFile = File(stageRoot, "meta/$resourceCode/$localId.mrm")
            metaFile.parentFile?.mkdirs()
            metaFile.writeText(
                buildSubResourceMetadata(resourceCode, localId, mainLocalId, digest.digest().toHex(), contentFile.length(), manifest, previewNames).toString(),
                Charsets.UTF_8
            )
            SubResource(resourceCode, localId, contentFile, metaFile)
        }
    }

    private fun buildMainMetadata(
        mainLocalId: String,
        sourceHash: String,
        sourceSize: Long,
        manifest: ArchiveManifest,
        subResources: List<SubResource>,
        previewNames: List<String>
    ): JSONObject = commonMetadata(mainLocalId, sourceHash, sourceSize, manifest).apply {
        put("builtInThumbnails", localizedList(previewNames.filter { it.contains("small", ignoreCase = true) }))
        put("builtInPreviews", localizedList(previewNames))
        put("thumbnails", JSONArray())
        put("previews", JSONArray())
        put("parentResources", JSONArray())
        val refs = JSONArray()
        subResources.forEach { refs.put(resourceRef(it.localId, it.resourceCode)) }
        put("subResources", refs)
        put("extraMeta", JSONObject())
        put("metaPath", JSONObject.NULL)
        put("contentPath", "$THEMEKIT_CONTENT_THEME_ROOT$mainLocalId.mrc")
        addTailMetadata(manifest, false)
    }

    private fun buildSubResourceMetadata(
        resourceCode: String,
        localId: String,
        mainLocalId: String,
        hash: String,
        size: Long,
        manifest: ArchiveManifest,
        previewNames: List<String>
    ): JSONObject = commonMetadata(localId, hash, size, manifest).apply {
        val resourcePreviews = filterSubResourcePreviews(resourceCode, previewNames)
        put("builtInThumbnails", localizedList(resourcePreviews))
        put("builtInPreviews", localizedList(resourcePreviews))
        put("thumbnails", JSONArray())
        put("previews", JSONArray())
        put("parentResources", JSONArray().put(resourceRef(mainLocalId, "theme")))
        put("subResources", JSONArray())
        put("extraMeta", JSONObject())
        put("metaPath", JSONObject.NULL)
        put("contentPath", JSONObject.NULL)
        addTailMetadata(manifest, true, resourceCode)
    }

    private fun commonMetadata(localId: String, hash: String, size: Long, manifest: ArchiveManifest): JSONObject = JSONObject().apply {
        put("localId", localId)
        put("onlineId", JSONObject.NULL)
        put("assemblyId", JSONObject.NULL)
        put("productId", JSONObject.NULL)
        put("hash", hash)
        put("platform", manifest.platform)
        put("size", size)
        put("updatedTime", 0)
        put("version", manifest.version.ifBlank { "1" })
        put("authors", localizedJson(manifest.author, manifest.authors))
        put("designers", localizedJson(manifest.designer, manifest.designers))
        put("titles", localizedJson(manifest.title, manifest.titles))
        put("descriptions", localizedJson(manifest.description, manifest.descriptions))
    }

    private fun JSONObject.addTailMetadata(manifest: ArchiveManifest, isSubResource: Boolean, resourceCode: String = "theme") {
        if (isSubResource) put("resourceCode", resourceCode)
        put("rightsPath", JSONObject.NULL)
        put("screenRatio", manifest.screenRatio ?: JSONObject.NULL)
        put("supportHomeSearchBar", manifest.supportHomeSearchBar)
        put("packageVersion", JSONObject.NULL)
        put("packageName", JSONObject.NULL)
        put("officialIcons", manifest.officialIcons)
        if (isSubResource) put("wallpaperStyle", manifest.wallpaperStyle ?: JSONObject.NULL)
        if (isSubResource) put("isSingleResource", manifest.isSingleResource)
        put("iconsCount", JSONObject.NULL)
        put("fontWeight", manifest.fontWeight ?: JSONObject.NULL)
        put("price", 0)
        put("isBackUpVersion", manifest.isBackUpVersion)
        put("themeType", manifest.themeType)
        put("miuiAdapterVersion", manifest.miuiAdapterVersion ?: JSONObject.NULL)
    }

    private fun localizedList(values: List<String>): JSONObject = JSONObject().apply { put("fallback", JSONArray(values)) }

    private fun localizedJson(fallback: String, values: LinkedHashMap<String, String>): JSONObject = JSONObject().apply {
        put("fallback", fallback)
        values.forEach { (locale, value) -> if (locale.isNotBlank() && locale != "fallback" && value.isNotBlank()) put(locale, value) }
    }

    private fun resourceRef(localId: String, resourceCode: String): JSONObject = JSONObject().apply {
        put("localId", localId)
        put("resourceCode", resourceCode)
        put("extraMeta", JSONObject())
        put("metaPath", JSONObject.NULL)
        put("contentPath", JSONObject.NULL)
    }

    private fun filterSubResourcePreviews(resourceCode: String, previewNames: List<String>): List<String> {
        if (resourceCode == "fonts") return previewNames.filter { it.equals("preview_fonts_small_0.jpg", true) }
        val key = when (resourceCode) {
            "bootanimation" -> "animation"
            "lockstyle" -> "lockscreen"
            else -> resourceCode.lowercase()
        }
        return previewNames.filter { it.lowercase().contains(key) }
    }

    private fun parseManifest(archive: ZipFile, displayName: String): ArchiveManifest {
        val entry = archive.getEntry("description.xml") ?: archive.entries().toList().firstOrNull { it.name.endsWith("/description.xml", true) }
        val xml = entry?.let { archive.getInputStream(it).bufferedReader().use { r -> r.readText() } }.orEmpty()
        val authors = localizedValues(xml, "author")
        val designers = localizedValues(xml, "designer")
        val titles = localizedValues(xml, "title")
        val descriptions = localizedValues(xml, "description")
        val fallbackTitle = fallbackValue(titles).ifBlank { displayName.removeSuffix(".mtz") }
        val fallbackAuthor = fallbackValue(authors).ifBlank { "Unknown" }
        val fallbackDesigner = fallbackValue(designers).ifBlank { fallbackAuthor }
        val platform = firstTagValue(xml, "uiVersion")?.toDoubleOrNull()?.toInt()
            ?: firstTagValue(xml, "platform")?.toDoubleOrNull()?.toInt()
            ?: DEFAULT_PLATFORM
        return ArchiveManifest(
            platform,
            firstTagValue(xml, "version") ?: "1",
            firstTagValue(xml, "miuiAdapterVersion") ?: defaultAdapterFor(platform),
            fallbackAuthor,
            fallbackDesigner,
            fallbackTitle,
            fallbackValue(descriptions),
            authors,
            designers,
            titles,
            descriptions,
            firstTagValue(xml, "screenRatio"),
            firstTagValue(xml, "supportHomeSearchBar").toBooleanCompat(),
            firstTagValue(xml, "fontWeight"),
            firstTagValue(xml, "isBackUpVersion").toBooleanCompat(),
            firstTagValue(xml, "isSingleResource").toBooleanCompat(),
            firstTagValue(xml, "wallpaperStyle")?.toIntOrNull(),
            firstTagValue(xml, "officialIcons").toBooleanCompat(),
            firstTagValue(xml, "themeType")?.toIntOrNull() ?: 0
        )
    }

    private fun localizedValues(xml: String, tag: String): LinkedHashMap<String, String> {
        val values = LinkedHashMap<String, String>()
        if (xml.isBlank()) return values
        val regex = Regex("<$tag(?:\\s+[^>]*)?>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val localeRegex = Regex("locale\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        regex.findAll(xml).forEach { m ->
            val locale = localeRegex.find(m.value.substringBefore('>'))?.groupValues?.getOrNull(1).orEmpty()
            val value = decodeXmlText(m.groupValues[1])
            if (value.isNotBlank()) values[if (locale.isBlank()) "fallback" else locale] = value
        }
        return values
    }

    private fun firstTagValue(xml: String, tag: String): String? {
        if (xml.isBlank()) return null
        val regex = Regex("<$tag(?:\\s+[^>]*)?>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.find(xml)?.groupValues?.getOrNull(1)?.let(::decodeXmlText)?.takeIf { it.isNotBlank() }
    }

    private fun fallbackValue(values: LinkedHashMap<String, String>): String = values["fallback"] ?: values.values.firstOrNull().orEmpty()

    private fun decodeXmlText(raw: String): String = raw
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("\\s+"), " ").trim()

    private fun resourceCodeFor(name: String): String? {
        val normalized = name.replace('\\', '/').trimStart('/')
        if (normalized.equals("description.xml", true) || normalized.endsWith("/description.xml", true)) return null
        if (normalized.startsWith("preview/", true) || normalized.startsWith("preview_", true)) return null
        LEGACY_ENTRY_CODES[normalized]?.let { return it }
        if (!normalized.contains('/')) return normalized.takeIf { it.isNotBlank() }
        return null
    }

    private fun buildInstallScript(stageRoot: File, stageFiles: List<File>, requestId: String): String = buildString {
        appendLine("set -e")
        stageFiles.forEach { localFile ->
            val relative = localFile.relativeTo(stageRoot).invariantSeparatorsPath
            val remoteFile = "$REMOTE_DATA_ROOT/$relative"
            val tmpFile = "$remoteFile.hyperos-tdk-$requestId.tmp"
            append("mkdir -p ").append(shellQuote(remoteFile.substringBeforeLast('/'))).appendLine()
            append("cp -f ").append(shellQuote(localFile.absolutePath)).append(' ').append(shellQuote(tmpFile)).appendLine()
            append("mv -f ").append(shellQuote(tmpFile)).append(' ').append(shellQuote(remoteFile)).appendLine()
        }
        append("test -f ").append(shellQuote("$REMOTE_DATA_ROOT/content/theme/$requestId.mrc")).appendLine()
        append("test -s ").append(shellQuote("$REMOTE_DATA_ROOT/meta/theme/$requestId.mrm")).appendLine()
    }

    private suspend fun cleanupRemote(context: Context, remoteFiles: List<String>, requestId: String) {
        if (remoteFiles.isEmpty()) return
        val script = buildString {
            append("rm -f")
            remoteFiles.forEach { remote ->
                append(' ').append(shellQuote(remote))
                append(' ').append(shellQuote("$remote.hyperos-tdk-$requestId.tmp"))
            }
        }
        ShizukuBridge.exec(context, script)
        DiagnosticsSessionClient.append(context, "PRIVILEGED_IMPORT_ROLLBACK", "localId=$requestId • files=${remoteFiles.size}")
    }

    private suspend fun tryTriggerApply(context: Context): Boolean {
        delay(1_000)
        repeat(6) {
            val dump = runCatching { ShizukuBridge.exec(context, "uiautomator dump $UI_DUMP_PATH >/dev/null 2>&1 && cat $UI_DUMP_PATH") }.getOrNull()
            if (dump?.success == true) {
                val m = APPLY_BOUNDS_REGEX.find(dump.output)
                if (m != null) {
                    val x = (m.groupValues[1].toInt() + m.groupValues[3].toInt()) / 2
                    val y = (m.groupValues[2].toInt() + m.groupValues[4].toInt()) / 2
                    if (ShizukuBridge.exec(context, "input tap $x $y").success) return true
                }
            }
            delay(600)
        }
        return false
    }

    private fun copyUriAndSha1(context: Context, uri: Uri, destination: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected MTZ" }
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String?.toBooleanCompat(): Boolean = when (this?.trim()?.lowercase()) { "true", "1", "yes" -> true; else -> false }
    private fun defaultAdapterFor(platform: Int): String? = when (platform) { 15 -> "3.0"; 16 -> "3.1"; 17 -> "3.2"; else -> null }
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private const val THEME_MANAGER_PACKAGE = "com.android.thememanager"
    private const val REMOTE_DATA_ROOT = "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/theme/.data"
    private const val THEMEKIT_CONTENT_THEME_ROOT = "/system/../storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/theme/.data/content/theme/"
    private const val UI_DUMP_PATH = "/data/local/tmp/hyperos-tdk-theme-manager-ui.xml"
    private const val DEFAULT_PLATFORM = 17

    private val APPLY_BOUNDS_REGEX = Regex("<node[^>]*resource-id=\"com\\.android\\.thememanager:id/operation_btn_apply\"[^>]*bounds=\"\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]\"[^>]*>")

    private val LEGACY_ENTRY_CODES = mapOf(
        "com.miui.home" to "launcher",
        "com.android.systemui" to "statusbar",
        "com.android.contacts" to "contact",
        "com.android.mms" to "mms",
        "framework-res" to "framework",
        "fonts/roboto-regular.ttf" to "fonts",
        "fonts/Roboto-Regular.ttf" to "fonts",
        "fonts/droidsansfallback.ttf" to "fonts_fallback",
        "fonts/DroidSansFallback.ttf" to "fonts_fallback",
        "wallpaper/default_wallpaper.jpg" to "wallpaper",
        "wallpaper/default_lock_wallpaper.jpg" to "lockscreen",
        "ringtones/alarm.mp3" to "alarm",
        "ringtones/ringtone.mp3" to "ringtone",
        "ringtones/notification.mp3" to "notification",
        "lockscreen" to "lockstyle",
        "boots/bootanimation.zip" to "bootanimation",
        "boots/bootaudio.mp3" to "bootaudio"
    )
}
