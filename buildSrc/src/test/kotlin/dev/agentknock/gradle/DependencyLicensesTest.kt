package dev.agentknock.gradle

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DependencyLicensesTest {
    @get:Rule val temporary = TemporaryFolder()

    private lateinit var root: File
    private lateinit var catalogue: MutableMap<String, Any?>
    private lateinit var sourceLibraries: MutableList<MutableMap<String, Any?>>

    @Before
    fun setUp() {
        root = temporary.newFolder("repository")
        root.resolve("licenses").mkdir()
        root.resolve("licenses/standard-texts.json").writeText("{}")
        sourceLibraries = mutableListOf()
        catalogue =
            mutableMapOf(
                "libraries" to sourceLibraries,
                "licenses" to mapOf("MIT" to mapOf("name" to "MIT License", "spdxId" to "MIT")),
            )
    }

    @Test
    fun filtersCompileOnlyDependenciesAndKeepsNestedOriginalNotices() {
        // AARs commonly put META-INF notices inside classes.jar. Losing them when
        // Android merges resources would discard the author's attribution.
        val copyright = "Copyright 2026 Alice Example\nPermission is hereby granted.\n"
        val notice = "This component includes work by Bob Example.\n"
        val nested =
            zip(
                mapOf(
                    "META-INF/LICENSE.md" to copyright.toByteArray(),
                    "META-INF/NOTICE" to notice.toByteArray(),
                )
            )
        val runtime = artifact("example:runtime:2.0", mapOf("classes.jar" to nested))
        artifact("example:compile-only:1.0", emptyMap())
        sourceLibraries.first()["artifactVersion"] = "1.0"

        val result = generate(listOf(runtime))

        assertEquals(listOf("example:runtime"), libraries(result).map { it["uniqueId"] })
        assertEquals("2.0", libraries(result).single()["artifactVersion"])
        val contents = licenses(result).values.map { it["content"] }
        assertTrue(contents.contains(copyright))
        assertTrue(contents.contains(notice))
    }

    @Test
    fun googleOffsetsUseBytesAndDuplicatesRetainAllParentAssociations() {
        // This is play-services-basement's metadata layout: component names mapped
        // to start/length byte ranges in a paired txt file. A multibyte prefix
        // catches accidental decoding before slicing.
        val prefix = "préface\n".toByteArray()
        val original = "Copyright © 2026 Example authors\nAll rights reserved.\n"
        val body = original.toByteArray()
        val entries =
            mapOf(
                "LICENSE" to "Example parent license\n".toByteArray(),
                "third_party_licenses.json" to
                    JsonOutput.toJson(
                            mapOf(
                                "Example component" to
                                    mapOf("start" to prefix.size, "length" to body.size)
                            )
                        )
                        .toByteArray(),
                "third_party_licenses.txt" to prefix + body + "trailing data".toByteArray(),
            )
        val first = artifact("example:first:1", entries)
        val second = artifact("example:second:2", entries)

        val result = generate(listOf(first, second))
        val bundled = libraries(result).filter { (it["uniqueId"] as String).startsWith("bundled:") }

        assertEquals(1, bundled.size)
        val description = bundled.single()["description"] as String
        assertTrue(description.contains(first.getValue("coordinate")))
        assertTrue(description.contains(second.getValue("coordinate")))
        val reference = (bundled.single()["licenses"] as List<*>).single() as String
        assertEquals(original, licenses(result).getValue(reference)["content"])
    }

    @Test
    fun allArtifactsOfOneRuntimeCoordinateRetainTheirNotices() {
        // One resolved Gradle module can contribute several archives. Keeping only
        // the last path loses the others' original redistribution notices.
        val firstText = "Copyright 2026 First component\nPermission is hereby granted.\n"
        val secondText = "Includes Second component, copyright 2025 Example.\n"
        val first =
            artifact("example:multi-artifact:1", mapOf("LICENSE" to firstText.toByteArray()))
        val secondary = root.resolve("secondary.jar")
        secondary.writeBytes(zip(mapOf("META-INF/NOTICE" to secondText.toByteArray())))
        val second =
            mapOf("coordinate" to first.getValue("coordinate"), "path" to secondary.absolutePath)

        val result = generate(listOf(first, second))

        assertEquals(1, libraries(result).size)
        val contents = licenses(result).values.map { it["content"] }
        assertTrue(contents.contains(firstText))
        assertTrue(contents.contains(secondText))
    }

    @Test
    fun incompleteGoogleBundlesFailInsteadOfSilentlyLosingNotices() {
        val invalidBundles =
            listOf(
                mapOf("third_party_licenses.json" to "{}"),
                mapOf("third_party_licenses.txt" to "notice"),
                mapOf(
                    "third_party_licenses.json" to """{"component":{"start":2,"length":99}}""",
                    "third_party_licenses.txt" to "notice",
                ),
            )
        for (bundle in invalidBundles) {
            sourceLibraries.clear()
            val entries =
                bundle.mapValues { it.value.toByteArray() } +
                    ("LICENSE" to "Example parent license\n".toByteArray())
            val artifact = artifact("example:broken:1", entries)

            assertThrows(IllegalArgumentException::class.java) { generate(listOf(artifact)) }
        }
    }

    @Test
    fun missingRuntimeMetadataOrFullLicenseTextBlocksGeneration() {
        val artifact = artifact("example:missing:1", emptyMap())

        val missingText =
            assertThrows(IllegalArgumentException::class.java) { generate(listOf(artifact)) }
        assertTrue(missingText.message.orEmpty().contains("Missing full license text"))

        sourceLibraries.clear()
        val missingMetadata =
            assertThrows(IllegalArgumentException::class.java) { generate(listOf(artifact)) }
        assertTrue(missingMetadata.message.orEmpty().contains("missing from catalogue"))
    }

    @Test
    fun noticeOnlyDoesNotStandInForAnUnknownLicense() {
        val artifact =
            artifact(
                "example:notice-only:1",
                mapOf("NOTICE" to "Copyright 2026 Example\n".toByteArray()),
            )
        sourceLibraries.single()["licenses"] = emptyList<String>()

        val error =
            assertThrows(IllegalArgumentException::class.java) { generate(listOf(artifact)) }

        assertTrue(error.message.orEmpty().contains("No licenses"))
    }

    @Test
    fun supplementIsVersionBoundAndCannotLeakIntoAnotherFlavor() {
        val artifact = artifact("example:play:1", emptyMap())
        val original = "Copyright 2026 Example authors\nPermission is hereby granted.\n"
        root.resolve("license.txt").writeText(original)
        val supplement =
            mapOf(
                "id" to "embedded-component",
                "name" to "Embedded component",
                "artifacts" to listOf("example:play"),
                "auditedVersions" to mapOf("example:play" to "1"),
                "license" to "MIT License",
                "licenseIds" to listOf("MIT"),
                "licenseFile" to "license.txt",
            )

        val result = generate(listOf(artifact), listOf(supplement))

        assertTrue(licenses(result).isNotEmpty())
        licenses(result).values.forEach { assertEquals(original, it["content"]) }
        assertTrue(libraries(generate(emptyList(), listOf(supplement))).isEmpty())
        val upgraded = artifact + ("coordinate" to "example:play:2")
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                generate(listOf(upgraded), listOf(supplement))
            }
        assertTrue(error.message.orEmpty().contains("Re-audit"))
    }

    private fun artifact(coordinate: String, entries: Map<String, ByteArray>): Map<String, String> {
        val path = root.resolve(coordinate.replace(':', '-') + ".aar")
        path.writeBytes(zip(entries))
        sourceLibraries.add(
            mutableMapOf(
                "uniqueId" to coordinate.substringBeforeLast(':'),
                "artifactVersion" to coordinate.substringAfterLast(':'),
                "name" to coordinate.substringBeforeLast(':'),
                "licenses" to listOf("MIT"),
            )
        )
        return mapOf("coordinate" to coordinate, "path" to path.absolutePath)
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { archive ->
            entries.forEach { (name, content) ->
                archive.putNextEntry(ZipEntry(name))
                archive.write(content)
                archive.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    @Suppress("UNCHECKED_CAST")
    private fun generate(
        artifacts: List<Map<String, String>>,
        supplements: List<Map<String, Any?>> = emptyList(),
    ): Map<String, Any?> {
        val file = root.resolve("aboutlibraries.json")
        file.writeText(JsonOutput.toJson(catalogue))
        root.resolve("licenses/supplements.json").writeText(JsonOutput.toJson(supplements))
        DependencyLicenses.generate(file, artifacts, root)
        return JsonSlurper().parse(file, "UTF-8") as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun libraries(result: Map<String, Any?>): List<Map<String, Any?>> =
        result.getValue("libraries") as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun licenses(result: Map<String, Any?>): Map<String, Map<String, Any?>> =
        result.getValue("licenses") as Map<String, Map<String, Any?>>
}
