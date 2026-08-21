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

object PrivilegedThemeEngine {
    data class CapabilityReport(
        val state: ShizukuBridge.State,
        val remoteWriteSupported: Boolean,
        val privilegedUid: Int?,
        val detail: String
    ) {
        val ready: Boolean get() = state.ready && remoteWriteSupported
    }

    data class InstallResult(
        val localId: String,
        val subResourceCount: Int,
        val themeManagerOpened: Boolean,
        val applyTriggered: Boolean,
        val message: String
    )

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

    suspend fun probe(context: Context): CapabilityReport {
        val state = ShizukuBridge.inspect(context)
        logState(context, state)
        if (!state.ready) {
            val detail = when {
                !state.binderAlive -> "Shevery/Shizuku binder hazır değil. Shevery servisini başlatın."
                !state.permissionGranted -> "HyperOS TDK için Shevery/Shizuku yetkisi gerekli."
                else -> "Privileged backend hazır değil: ${state.detail}"
            }
            DiagnosticsSessionClient.append(context, "THEME_CAPABILITY_TEST", detail, level = "WARN")
            return CapabilityReport(state, false, state.serverUid, detail)
        }

        val probeId = UUID.randomUUID().toString().take(8)
        val probeDir = "$REMOTE_DATA_ROOT/.hyperos-tdk-probe-$probeId"
        val result = runCatching {
            ShizukuBridge.exec(
                context,
                "set -e; mkdir -p ${shellQuote(probeDir)}; test -d ${shellQuote(probeDir)}; rmdir ${shellQuote(probeDir)}"
            )
        }.getOrElse { error ->
            val detail = "Capability shell çağrısı başarısız: ${error.javaClass.simpleName}: ${error.message}"
            DiagnosticsSessionClient.append(context, "THEME_CAPABILITY_TEST", detail, level = "ERROR")
            return CapabilityReport(state, false, state.serverUid, detail)
        }
        val supported = result.success
        val detail = if (supported) {
            "Local Theme Engine hazır • backend=${state.backend} • uid=${state.serverUid} • Theme Manager .data yazılabilir."
        } else {
            "Theme Manager .data yazma testi başarısız • exit=${result.exitCode} • ${result.output.take(500)}"
        }
        DiagnosticsSessionClient.append(
            context,
            "THEME_CAPABILITY_TEST",
            detail,
            level = if (supported) "INFO" else "WARN"
        )
        return CapabilityReport(state, supported, state.serverUid, detail)
    }

    suspend fun installAndOpen(
        context: Context,
        displayName: String,
        sourceUri: Uri,
        requestAutomaticApply: Boolean = true
    ): InstallResult {
        val capability = probe(context)
        check(capability.ready) { capability.detail }

        val mainLocalId = UUID.randomUUID().toString()
        DiagnosticsSessionClient.append(
            context,
            "PRIVILEGED_IMPORT_STARTED",
            "name=$displayName • localId=$mainLocalId • backend=${capability.state.backend}"
        )

        val externalBase = context.getExternalFilesDir("imports")
            ?: error("External files directory is unavailable; shell-readable staging cannot be created")
        val jobRoot = File(externalBase, mainLocalId).apply {
            deleteRecursively()
            mkdirs()
        }
        val sourceFile = File(jobRoot, "source.mtz")
        val stageRoot = File(jobRoot, "remote").apply { mkdirs() }

        var installedRemoteFiles: List<String> = emptyList()
        try {
            DiagnosticsSessionClient.append(context, "MRC_BUILD_STARTED", "localId=$mainLocalId")
            val sourceHash = copyUriAndSha1(context, sourceUri, sourceFile)
            val sourceSize = sourceFile.length()
            val zip = ZipFile(sourceFile)
            val manifest = zip.use { parseManifest(it, displayName) }

            val contentThemeDir = File(stageRoot, "content/theme").apply { mkdirs() }
            val mainMrc = File(contentThemeDir, "$mainLocalId.mrc").apply {
                parentFile?.mkdirs()
                if (!exists()) createNewFile()
            }

            val subResources = ZipFile(sourceFile).use { archive ->
                extractSubResources(
                    archive = archive,
                    stageRoot = stageRoot,
                    mainLocalId = mainLocalId,
                    manifest = manifest
                )
            }
            DiagnosticsSessionClient.append(
                context,
                "MRC_BUILD_SUCCESS",
                "main=$mainLocalId.mrc • sourceSha1=$sourceHash • subResources=${subResources.size}"
            )

            DiagnosticsSessionClient.append(context, "MRM_BUILD_STARTED", "localId=$mainLocalId")
            val mainMetaDir = File(stageRoot, "meta/theme").apply { mkdirs() }
            val mainMrm = File(mainMetaDir, "$mainLocalId.mrm")
            val mainJson = buildMainMetadata(
                mainLocalId = mainLocalId,
                sourceHash = sourceHash,
                sourceSize = sourceSize,
                manifest = manifest,
                subResources = subResources
            )
            mainMrm.writeText(mainJson.toString(), Charsets.UTF_8)
            check(mainMrm.length() > 0L && mainMrc.exists()) { "Main MRM/MRC staging validation failed" }
            DiagnosticsSessionClient.append(
                context,
                "MRM_BUILD_SUCCESS",
                "main=$mainLocalId.mrm • bytes=${mainMrm.length()} • platform=${manifest.platform} • adapter=${manifest.miuiAdapterVersion}"
            )
            DiagnosticsSessionClient.append(
                context,
                "MRM_VALIDATION_SUCCESS",
                "subResources=${subResources.size} • contentPath=${mainJson.optString("contentPath")}"
            )

            val stageFiles = stageRoot.walkTopDown().filter { it.isFile }.toList()
            check(stageFiles.isNotEmpty()) { "No staged Theme Manager files were generated" }
            installedRemoteFiles = stageFiles.map {
                "$REMOTE_DATA_ROOT/${it.relativeTo(stageRoot).invariantSeparatorsPath}"
            }

            DiagnosticsSessionClient.append(
                context,
                "STAGING_WRITE_SUCCESS",
                "files=${stageFiles.size} • source=${stageRoot.absolutePath}"
            )

            val installScript = buildInstallScript(stageRoot, stageFiles, mainLocalId)
            val installResult = ShizukuBridge.exec(context, installScript)
            if (!installResult.success) {
                cleanupRemote(context, installedRemoteFiles, mainLocalId)
                error("Theme Manager storage install failed (exit=${installResult.exitCode}): ${installResult.output.take(1000)}")
            }
            DiagnosticsSessionClient.append(
                context,
                "THEME_STORAGE_INSTALL_SUCCESS",
                "localId=$mainLocalId • files=${stageFiles.size} • backend=${capability.state.backend}"
            )

            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("ViewLocalResource://view.local.resource#$mainLocalId")
            ).apply {
                setPackage(THEME_MANAGER_PACKAGE)
                addCategory(Intent.CATEGORY_DEFAULT)
                putExtra("REQUEST_RESOURCE_CODE", "theme")
                putExtra("REQUEST_APPLY_EVENT", requestAutomaticApply)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            DiagnosticsSessionClient.append(
                context,
                "LOCAL_RESOURCE_INTENT_SENT",
                "localId=$mainLocalId • REQUEST_RESOURCE_CODE=theme • REQUEST_APPLY_EVENT=$requestAutomaticApply"
            )

            val applyTriggered = if (requestAutomaticApply) {
                tryTriggerApply(context)
            } else {
                false
            }
            val message = if (applyTriggered) {
                "Theme Manager local resource açıldı ve Apply düğmesi tetiklendi."
            } else {
                "Theme Manager local resource açıldı. Apply otomasyonu düğmeyi bulamadıysa Tema Yöneticisi içinden Uygula'ya dokunun."
            }
            DiagnosticsSessionClient.append(
                context,
                if (applyTriggered) "APPLY_TRIGGERED" else "APPLY_AWAITING_USER",
                "localId=$mainLocalId • $message"
            )
            jobRoot.deleteRecursively()
            return InstallResult(
                localId = mainLocalId,
                subResourceCount = subResources.size,
                themeManagerOpened = true,
                applyTriggered = applyTriggered,
                message = message
            )
        } catch (error: Throwable) {
            DiagnosticsSessionClient.append(
                context,
                "PRIVILEGED_IMPORT_FAILED",
                "localId=$mainLocalId • ${error.javaClass.simpleName}: ${error.message}",
                level = "ERROR"
            )
            if (installedRemoteFiles.isNotEmpty()) {
                runCatching { cleanupRemote(context, installedRemoteFiles, mainLocalId) }
            }
            throw error
        }
    }

    private fun extractSubResources(
        archive: ZipFile,
        stageRoot: File,
        mainLocalId: String,
        manifest: ArchiveManifest
    ): List<SubResource> {
        val entries = archive.entries().toList()
            .filter { !it.isDirectory }
            .mapNotNull { entry -> resourceCodeFor(entry.name)?.let { code -> entry to code } }
            .sortedBy { (entry, _) -> if (entry.name.contains('/')) 1 else 0 }

        val selected = LinkedHashMap<String, ZipEntry>()
        entries.forEach { (entry, code) ->
            if (!selected.containsKey(code)) selected[code] = entry
        }

        val result = ArrayList<SubResource>()
        selected.forEach { (resourceCode, entry) ->
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
            val hash = digest.digest().toHex()
            val metaFile = File(stageRoot, "meta/$resourceCode/$localId.mrm")
            metaFile.parentFile?.mkdirs()
            metaFile.writeText(
                buildSubResourceMetadata(
                    resourceCode = resourceCode,
                    localId = localId,
                    mainLocalId = mainLocalId,
                    hash = hash,
                    size = contentFile.length(),
                    manifest = manifest
                ).toString(),
                Charsets.UTF_8
            )
            result += SubResource(resourceCode, localId, contentFile, metaFile)
        }
        return result
    }

    private fun buildMainMetadata(
        mainLocalId: String,
        sourceHash: String,
        sourceSize: Long,
        manifest: ArchiveManifest,
        subResources: List<SubResource>
    ): JSONObject = commonMetadata(
        localId = mainLocalId,
        hash = sourceHash,
        size = sourceSize,
        manifest = manifest
    ).apply {
        put("builtInThumbnails", JSONArray())
        put("builtInPreviews", JSONArray())
        put("thumbnails", JSONArray())
        put("previews", JSONArray())
        put("parentResources", JSONArray())
        put("contentPath", "/system/../$REMOTE_DATA_ROOT/content/theme/$mainLocalId.mrc")
        put("metaPath", JSONObject.NULL)
        put("extraMeta", JSONObject())
        val refs = JSONArray()
        subResources.forEach { sub -> refs.put(resourceRef(sub.localId, sub.resourceCode)) }
        put("subResources", refs)
        addTailMetadata(manifest, isSubResource = false)
    }

    private fun buildSubResourceMetadata(
        resourceCode: String,
        localId: String,
        mainLocalId: String,
        hash: String,
        size: Long,
        manifest: ArchiveManifest
    ): JSONObject = commonMetadata(
        localId = localId,
        hash = hash,
        size = size,
        manifest = manifest
    ).apply {
        put("builtInThumbnails", JSONArray())
        put("builtInPreviews", JSONArray())
        put("thumbnails", JSONArray())
        put("previews", JSONArray())
        put("parentResources", JSONArray().put(resourceRef(mainLocalId, "theme")))
        put("subResources", JSONArray())
        put("extraMeta", JSONObject())
        put("metaPath", JSONObject.NULL)
        put("contentPath", JSONObject.NULL)
        addTailMetadata(manifest, isSubResource = true, resourceCode = resourceCode)
    }

    private fun commonMetadata(
        localId: String,
        hash: String,
        size: Long,
        manifest: ArchiveManifest
    ): JSONObject = JSONObject().apply {
        put("localId", localId)
        put("onlineId", JSONObject.NULL)
        put("assemblyId", JSONObject.NULL)
        put("productId", JSONObject.NULL)
        put("hash", hash)
        put("platform", manifest.platform)
        put("size", size)
        put("updatedTime", (System.currentTimeMillis() / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        put("version", manifest.version.ifBlank { "1" })
        put("authors", localizedJson(manifest.author, manifest.authors))
        put("designers", localizedJson(manifest.designer, manifest.designers))
        put("titles", localizedJson(manifest.title, manifest.titles))
        put("descriptions", localizedJson(manifest.description, manifest.descriptions))
    }

    private fun JSONObject.addTailMetadata(
        manifest: ArchiveManifest,
        isSubResource: Boolean,
        resourceCode: String = "theme"
    ) {
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

    private fun resourceRef(localId: String, resourceCode: String): JSONObject = JSONObject().apply {
        put("localId", localId)
        put("resourceCode", resourceCode)
        put("extraMeta", JSONObject())
        put("metaPath", JSONObject.NULL)
        put("contentPath", JSONObject.NULL)
    }

    private fun localizedJson(
        fallback: String,
        values: LinkedHashMap<String, String>
    ): JSONObject = JSONObject().apply {
        put("fallback", fallback)
        values.forEach { (locale, value) ->
            if (locale.isNotBlank() && value.isNotBlank()) put(locale, value)
        }
    }

    private fun parseManifest(archive: ZipFile, displayName: String): ArchiveManifest {
        val entry = archive.getEntry("description.xml")
            ?: archive.entries().toList().firstOrNull { it.name.endsWith("/description.xml", ignoreCase = true) }
        val xml = entry?.let { archive.getInputStream(it).bufferedReader().use { reader -> reader.readText() } }.orEmpty()

        val authors = localizedValues(xml, "author")
        val designers = localizedValues(xml, "designer")
        val titles = localizedValues(xml, "title")
        val descriptions = localizedValues(xml, "description")
        val fallbackTitle = fallbackValue(titles).ifBlank { displayName.removeSuffix(".mtz") }
        val fallbackAuthor = fallbackValue(authors).ifBlank { "Unknown" }
        val fallbackDesigner = fallbackValue(designers).ifBlank { fallbackAuthor }
        val fallbackDescription = fallbackValue(descriptions)

        val uiVersion = firstTagValue(xml, "uiVersion")
        val explicitPlatform = firstTagValue(xml, "platform")?.toDoubleOrNull()?.toInt()
        val platform = uiVersion?.toDoubleOrNull()?.toInt()
            ?: explicitPlatform
            ?: DEFAULT_PLATFORM
        val adapter = firstTagValue(xml, "miuiAdapterVersion")
            ?: defaultAdapterFor(platform)

        return ArchiveManifest(
            platform = platform,
            version = firstTagValue(xml, "version") ?: "1",
            miuiAdapterVersion = adapter,
            author = fallbackAuthor,
            designer = fallbackDesigner,
            title = fallbackTitle,
            description = fallbackDescription,
            authors = authors,
            designers = designers,
            titles = titles,
            descriptions = descriptions,
            screenRatio = firstTagValue(xml, "screenRatio"),
            supportHomeSearchBar = firstTagValue(xml, "supportHomeSearchBar").toBooleanCompat(),
            fontWeight = firstTagValue(xml, "fontWeight"),
            isBackUpVersion = firstTagValue(xml, "isBackUpVersion").toBooleanCompat(),
            isSingleResource = firstTagValue(xml, "isSingleResource").toBooleanCompat(),
            wallpaperStyle = firstTagValue(xml, "wallpaperStyle")?.toIntOrNull(),
            officialIcons = firstTagValue(xml, "officialIcons").toBooleanCompat(),
            themeType = firstTagValue(xml, "themeType")?.toIntOrNull() ?: 0
        )
    }

    private fun localizedValues(xml: String, tag: String): LinkedHashMap<String, String> {
        val values = LinkedHashMap<String, String>()
        if (xml.isBlank()) return values
        val regex = Regex(
            "<$tag(?:\\s+[^>]*)?>(.*?)</$tag>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val localeRegex = Regex("locale\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
        regex.findAll(xml).forEach { match ->
            val opening = match.value.substringBefore('>')
            val locale = localeRegex.find(opening)?.groupValues?.getOrNull(1).orEmpty()
            val value = decodeXmlText(match.groupValues[1])
            if (value.isNotBlank()) {
                values[if (locale.isBlank()) "fallback" else locale] = value
            }
        }
        return values
    }

    private fun firstTagValue(xml: String, tag: String): String? {
        if (xml.isBlank()) return null
        val regex = Regex(
            "<$tag(?:\\s+[^>]*)?>(.*?)</$tag>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return regex.find(xml)?.groupValues?.getOrNull(1)?.let(::decodeXmlText)?.takeIf { it.isNotBlank() }
    }

    private fun fallbackValue(values: LinkedHashMap<String, String>): String =
        values["fallback"] ?: values.values.firstOrNull().orEmpty()

    private fun decodeXmlText(raw: String): String = raw
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun resourceCodeFor(name: String): String? {
        val normalized = name.trimStart('/')
        if (normalized.equals("description.xml", true) || normalized.endsWith("/description.xml", true)) return null
        if (normalized.startsWith("preview/", true) || normalized.startsWith("preview_", true)) return null
        LEGACY_ENTRY_CODES[normalized]?.let { return it }
        if (!normalized.contains('/')) {
            return normalized.takeIf { it.isNotBlank() }
        }
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
        val script = buildString {
            append("rm -f")
            remoteFiles.forEach { remote ->
                append(' ').append(shellQuote(remote))
                append(' ').append(shellQuote("$remote.hyperos-tdk-$requestId.tmp"))
            }
        }
        ShizukuBridge.exec(context, script)
        DiagnosticsSessionClient.append(
            context,
            "PRIVILEGED_IMPORT_ROLLBACK",
            "localId=$requestId • files=${remoteFiles.size}"
        )
    }

    private suspend fun tryTriggerApply(context: Context): Boolean {
        delay(1_200)
        repeat(5) {
            val dump = runCatching {
                ShizukuBridge.exec(
                    context,
                    "uiautomator dump $UI_DUMP_PATH >/dev/null 2>&1 && cat $UI_DUMP_PATH"
                )
            }.getOrNull()
            if (dump?.success == true) {
                val match = APPLY_BOUNDS_REGEX.find(dump.output)
                if (match != null) {
                    val left = match.groupValues[1].toInt()
                    val top = match.groupValues[2].toInt()
                    val right = match.groupValues[3].toInt()
                    val bottom = match.groupValues[4].toInt()
                    val x = (left + right) / 2
                    val y = (top + bottom) / 2
                    val tap = ShizukuBridge.exec(context, "input tap $x $y")
                    if (tap.success) return true
                }
            }
            delay(550)
        }
        return false
    }

    private fun copyUriAndSha1(context: Context, uri: Uri, destination: File): String {
        destination.parentFile?.mkdirs()
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

    private fun String?.toBooleanCompat(): Boolean = when (this?.trim()?.lowercase()) {
        "true", "1", "yes" -> true
        else -> false
    }

    private fun defaultAdapterFor(platform: Int): String? = when (platform) {
        15 -> "3.0"
        16 -> "3.1"
        17 -> "3.2"
        else -> null
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun logState(context: Context, state: ShizukuBridge.State) {
        DiagnosticsSessionClient.append(
            context,
            if (state.sheveryInstalled) "SHEVERY_DETECTED" else "SHIZUKU_MANAGER_STATE",
            state.detail
        )
        if (state.binderAlive) DiagnosticsSessionClient.append(context, "SHIZUKU_BINDER_RECEIVED", state.detail)
        if (state.permissionGranted) DiagnosticsSessionClient.append(context, "SHIZUKU_PERMISSION_GRANTED", state.detail)
        state.serverUid?.let { uid ->
            DiagnosticsSessionClient.append(
                context,
                "SHIZUKU_SERVER_UID",
                "uid=$uid • backend=${state.backend} • selinux=${state.selinuxContext ?: "unknown"}"
            )
        }
    }

    private const val THEME_MANAGER_PACKAGE = "com.android.thememanager"
    private const val REMOTE_DATA_ROOT = "/storage/emulated/0/Android/data/com.android.thememanager/files/MIUI/theme/.data"
    private const val UI_DUMP_PATH = "/data/local/tmp/hyperos-tdk-theme-manager-ui.xml"
    private const val DEFAULT_PLATFORM = 17

    private val APPLY_BOUNDS_REGEX = Regex(
        "<node[^>]*resource-id=\"com\\.android\\.thememanager:id/operation_btn_apply\"[^>]*bounds=\"\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]\"[^>]*>"
    )

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
