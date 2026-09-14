package dev.agentknock.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** Adds original distribution notices to the resolved AboutLibraries catalogue. */
object DependencyLicenses {
    private val noticeName = Regex("^(licen[cs]e|notice|copyright)(?:$|[._-])")

    fun generate(catalogueFile: File, artifacts: List<Map<String, String>>, root: File) {
        val catalogue = readJson(catalogueFile).asObject()
        val supplements =
            readJson(root.resolve("licenses/supplements.json")).asArray().map { it.asObject() }
        val result = enrich(catalogue, artifacts, supplements, root)
        catalogueFile.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(result), true) + "\n")
    }

    private fun enrich(
        catalogue: Map<String, Any?>,
        artifacts: List<Map<String, String>>,
        supplements: List<Map<String, Any?>>,
        root: File,
    ): Map<String, Any?> {
        val standardTexts = readJson(root.resolve("licenses/standard-texts.json")).asObject()
        val sourceLibraries =
            catalogue
                .getValue("libraries")
                .asArray()
                .map { it.asObject() }
                .associateBy { it.string("uniqueId") }
        val licenses = catalogue.getValue("licenses").asObject().toMutableMap()
        val runtime = artifacts.groupBy { artifact ->
            val coordinate = artifact.getValue("coordinate")
            require(coordinate.split(':').size == 3) { "Invalid runtime coordinate: $coordinate" }
            coordinate.substringBeforeLast(':')
        }
        for ((identifier, files) in runtime) {
            require(files.map { it.getValue("coordinate") }.distinct().size == 1) {
                "Multiple runtime versions of $identifier"
            }
        }

        val additions = mutableListOf<Map<String, Any?>>()
        val replacementTexts = mutableMapOf<Pair<String, String>, String>()
        for (supplement in supplements) {
            val targets = supplement.getValue("artifacts").strings()
            val parents = targets.toSet().intersect(runtime.keys).sorted()
            if (targets.isNotEmpty() && parents.isEmpty()) continue
            for (parent in parents) {
                val version =
                    runtime.getValue(parent).first().getValue("coordinate").substringAfterLast(':')
                require(supplement.getValue("auditedVersions").asObject()[parent] == version) {
                    "Re-audit ${supplement.string("id")} for $parent:$version"
                }
            }
            val content = readNotice(root, supplement.string("licenseFile"))
            val references =
                mutableSetOf(
                    licenseRecord(
                        licenses,
                        supplement.string("license"),
                        content,
                        supplement.optionalString("url"),
                    )
                )
            supplement.optionalString("noticeFile")?.let { path ->
                references +=
                    licenseRecord(licenses, "Copyright and notices", readNotice(root, path))
            }
            for (parent in parents) {
                for (identifier in supplement["licenseIds"]?.strings().orEmpty()) {
                    replacementTexts[parent to identifier] = content
                }
            }
            val description =
                if (parents.isEmpty()) {
                    "Included in Agentknock."
                } else {
                    "Included in " +
                        parents.joinToString(", ") {
                            runtime.getValue(it).first().getValue("coordinate")
                        }
                }
            additions +=
                componentLibrary(
                    "supplement:${supplement.string("id")}",
                    supplement.string("name"),
                    references,
                    description,
                    supplement.optionalString("url"),
                    supplement.optionalString("version"),
                    supplement.optionalString("sourceUrl"),
                )
        }

        val libraries = mutableListOf<Map<String, Any?>>()
        val components = mutableMapOf<Pair<String, String>, MutableSet<String>>()
        for ((identifier, files) in runtime.toSortedMap()) {
            val coordinate = files.first().getValue("coordinate")
            val sourceLibrary = sourceLibraries[identifier]
            require(sourceLibrary != null) {
                "Runtime dependency missing from catalogue: $coordinate"
            }
            val library = sourceLibrary.toMutableMap()
            library["name"] = library.optionalString("name") ?: identifier
            library["artifactVersion"] = coordinate.substringAfterLast(':')
            val originalFiles = mutableListOf<Pair<String, String>>()
            val bundled = mutableListOf<Pair<String, String>>()
            for (path in files.map { it.getValue("path") }.distinct().sorted()) {
                val notices = archiveNotices(File(path))
                originalFiles += notices.originals
                bundled += notices.bundled
            }
            val originalLicenses =
                originalFiles
                    .filter { (name, _) ->
                        val basename =
                            name
                                .substringAfterLast('!')
                                .substringAfterLast('/')
                                .lowercase(Locale.ROOT)
                        basename.startsWith("license") || basename.startsWith("licence")
                    }
                    .map { it.second }
            val references = mutableSetOf<String>()
            for (reference in library["licenses"]?.strings().orEmpty()) {
                val record = licenses[reference]?.asObject()
                require(record != null) { "Unknown license $reference for $coordinate" }
                var resolvedReference = reference
                if (record.optionalString("content").isNullOrBlank()) {
                    val identifiers = listOfNotNull(reference, record.optionalString("spdxId"))
                    var content = identifiers.firstNotNullOfOrNull {
                        replacementTexts[identifier to it]
                    }
                    val standard = identifiers.firstNotNullOfOrNull { standardTexts[it] as? String }
                    if (content == null && standard != null) content = readNotice(root, standard)
                    if (content == null && originalLicenses.isNotEmpty()) {
                        content = originalLicenses.joinToString("\n\n")
                    }
                    require(content != null) {
                        "Missing full license text for $coordinate: ${record.string("name")}"
                    }
                    // An artifact's copyright must not replace a shared POM license record.
                    resolvedReference =
                        licenseRecord(
                            licenses,
                            record.string("name"),
                            content,
                            record.optionalString("url"),
                            record.optionalString("spdxId"),
                        )
                }
                references += resolvedReference
            }
            val seenContents =
                references.mapTo(mutableSetOf()) {
                    licenses.getValue(it).asObject().string("content")
                }
            for ((_, content) in originalFiles) {
                if (seenContents.add(content)) {
                    references += licenseRecord(licenses, "Copyright and notices", content)
                }
            }
            require(
                !library["licenses"]?.strings().isNullOrEmpty() || originalLicenses.isNotEmpty()
            ) {
                "No licenses for runtime dependency: $coordinate"
            }
            library["licenses"] = references.sorted()
            libraries += library
            for (component in bundled) {
                components.getOrPut(component) { mutableSetOf() } += coordinate
            }
        }

        val componentOrder = compareBy<Pair<String, String>>({ it.first }, { it.second })
        for ((component, parents) in components.toSortedMap(componentOrder)) {
            val (name, content) = component
            val reference = licenseRecord(licenses, "$name — bundled license and notices", content)
            additions +=
                componentLibrary(
                    "bundled:$reference",
                    name,
                    listOf(reference),
                    "Notice supplied by " + parents.sorted().joinToString(", "),
                )
        }
        libraries += additions
        val usedLicenses = libraries.flatMap { it.getValue("licenses").strings() }.toSortedSet()
        return linkedMapOf(
            "libraries" to
                libraries.sortedWith(
                    compareBy(
                        { it.string("name").lowercase(Locale.ROOT) },
                        { it.string("uniqueId") },
                    )
                ),
            "licenses" to usedLicenses.associateWith { licenses.getValue(it) },
        )
    }

    private data class ArchiveNotices(
        val originals: MutableList<Pair<String, String>> = mutableListOf(),
        val bundled: MutableList<Pair<String, String>> = mutableListOf(),
    )

    private fun archiveNotices(path: File): ArchiveNotices {
        val notices = ArchiveNotices()

        fun scan(entries: Map<String, ByteArray>, prefix: String) {
            for ((name, data) in entries.toSortedMap()) {
                val basename = name.substringAfterLast('/')
                if (basename in listOf("third_party_licenses.json", "third_party_licenses.txt")) {
                    val directory = name.removeSuffix(basename)
                    val metadataName = directory + "third_party_licenses.json"
                    val textName = directory + "third_party_licenses.txt"
                    require(metadataName in entries && textName in entries) {
                        "Incomplete Google license bundle: $path!$prefix$name"
                    }
                    if (name != metadataName) continue
                    val metadata =
                        JsonSlurper().parseText(textContent(data, "$path!$prefix$name")).asObject()
                    val contents = entries.getValue(textName)
                    for ((component, value) in metadata) {
                        val span = value.asObject()
                        val start = span["start"].integer()
                        val length = span["length"].integer()
                        // Google's ranges count UTF-8 bytes, not decoded characters.
                        require(
                            start != null &&
                                length != null &&
                                start >= 0 &&
                                length > 0 &&
                                start <= contents.size.toLong() - length
                        ) {
                            "Invalid Google license span: $path!$name: $component"
                        }
                        notices.bundled +=
                            component to
                                textContent(
                                    contents.copyOfRange(start.toInt(), (start + length).toInt()),
                                    "$path!$name: $component",
                                )
                    }
                } else if (name.lowercase(Locale.ROOT).endsWith(".jar")) {
                    scan(nestedEntries(data, "$path!$prefix$name"), "$prefix$name!")
                } else {
                    notices.originals += "$prefix$name" to textContent(data, "$path!$prefix$name")
                }
            }
        }

        ZipFile(path).use { archive ->
            val entries = linkedMapOf<String, ByteArray>()
            for (entry in archive.entries().asSequence()) {
                if (isNoticeEntry(entry.name)) {
                    entries[entry.name] = archive.getInputStream(entry).use { it.readBytes() }
                }
            }
            scan(entries, "")
        }
        return notices
    }

    private fun nestedEntries(data: ByteArray, source: String): Map<String, ByteArray> {
        // ZipInputStream otherwise accepts an arbitrary non-ZIP payload as an empty archive.
        require(
            data.size >= 4 &&
                data[0] == 0x50.toByte() &&
                data[1] == 0x4b.toByte() &&
                ((data[2] == 3.toByte() && data[3] == 4.toByte()) ||
                    (data[2] == 5.toByte() && data[3] == 6.toByte()))
        ) {
            "Invalid nested archive: $source"
        }
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(data)).use { archive ->
            var entry = archive.nextEntry
            while (entry != null) {
                if (isNoticeEntry(entry.name)) entries[entry.name] = archive.readBytes()
                archive.closeEntry()
                entry = archive.nextEntry
            }
        }
        return entries
    }

    private fun isNoticeEntry(name: String): Boolean {
        if (name.endsWith('/')) return false
        val basename = name.substringAfterLast('/')
        val lower = basename.lowercase(Locale.ROOT)
        return basename in listOf("third_party_licenses.json", "third_party_licenses.txt") ||
            lower.endsWith(".jar") ||
            (noticeName.containsMatchIn(lower) &&
                !lower.endsWith(".class") &&
                !lower.endsWith(".kotlin_module"))
    }

    private fun licenseRecord(
        licenses: MutableMap<String, Any?>,
        name: String,
        content: String,
        url: String? = null,
        spdxId: String? = null,
    ): String {
        val identifier =
            HexFormat.of()
                .formatHex(
                    MessageDigest.getInstance("SHA-256")
                        .digest("$name\u0000$content".toByteArray(Charsets.UTF_8))
                )
        licenses[identifier] =
            linkedMapOf<String, Any?>(
                    "name" to name,
                    "content" to content,
                    "hash" to identifier,
                )
                .apply {
                    if (url != null) put("url", url)
                    if (spdxId != null) put("spdxId", spdxId)
                }
        return identifier
    }

    private fun componentLibrary(
        identifier: String,
        name: String,
        references: Collection<String>,
        description: String,
        url: String? = null,
        version: String? = null,
        sourceUrl: String? = null,
    ): Map<String, Any?> =
        linkedMapOf<String, Any?>(
                "uniqueId" to identifier,
                "name" to name,
                "description" to description,
                "developers" to emptyList<Any>(),
                "licenses" to references.sorted(),
            )
            .apply {
                if (url != null) put("website", url)
                if (version != null) put("artifactVersion", version)
                if (sourceUrl != null) put("scm", mapOf("url" to sourceUrl))
            }

    private fun readNotice(root: File, path: String): String =
        textContent(root.resolve(path).readBytes(), path)

    private fun textContent(data: ByteArray, source: String): String {
        val text =
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
        require(text.isNotBlank()) { "Empty license or notice: $source" }
        return text
    }

    private fun readJson(file: File): Any? =
        JsonSlurper().parseText(textContent(file.readBytes(), file.toString()))

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObject(): Map<String, Any?> = this as Map<String, Any?>

    private fun Any?.asArray(): List<*> = this as List<*>

    private fun Any?.strings(): List<String> = asArray().map { it as String }

    private fun Map<String, Any?>.string(key: String): String = getValue(key) as String

    private fun Map<String, Any?>.optionalString(key: String): String? =
        (get(key) as? String)?.takeIf { it.isNotEmpty() }

    private fun Any?.integer(): Long? =
        when (this) {
            is Int -> toLong()
            is Long -> this
            else -> null
        }
}
