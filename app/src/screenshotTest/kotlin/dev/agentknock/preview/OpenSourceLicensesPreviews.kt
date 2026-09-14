package dev.agentknock.preview

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import dev.agentknock.ui.settings.OpenSourceLicensesSettingsContent

@PreviewTest
@Preview(
    name = "Light",
    group = "open-source-licenses",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
fun OpenSourceLicensesLightPreview() = OpenSourceLicensesPreview()

@PreviewTest
@Preview(
    name = "Dark",
    group = "open-source-licenses",
    widthDp = 360,
    heightDp = 800,
    locale = "en",
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
fun OpenSourceLicensesDarkPreview() = OpenSourceLicensesPreview()

@Composable
private fun OpenSourceLicensesPreview() = PreviewScreen {
    OpenSourceLicensesSettingsContent(previewLibraries, {}, {}, Modifier.fillMaxSize())
}

// Keep preview data independent of Gradle's generated catalog so previews render without IO.
private val previewApacheLicense =
    License(
        name = "Apache License 2.0",
        url = "https://www.apache.org/licenses/LICENSE-2.0",
        spdxId = "Apache-2.0",
        licenseContent = "Apache License\nVersion 2.0, January 2004",
        hash = "preview-apache",
    )
private val previewLibraries =
    Libs(
        libraries =
            listOf(
                    "AndroidX Activity" to "1.13.0",
                    "AndroidX Biometric" to "1.1.0",
                    "AndroidX Compose Foundation" to "1.12.0",
                    "AndroidX Compose Material 3" to "1.4.0",
                    "AndroidX Lifecycle" to "2.11.0",
                    "AndroidX Room" to "3.0.1",
                    "Kotlin Standard Library" to "2.4.10",
                    "OkHttp" to "5.4.0",
                    "Okio" to "3.17.0",
                )
                .map { (name, version) ->
                    Library(
                        uniqueId = name,
                        artifactVersion = version,
                        name = name,
                        description = null,
                        website = null,
                        developers = emptyList(),
                        organization = null,
                        scm = null,
                        licenses = setOf(previewApacheLicense),
                    )
                },
        licenses = setOf(previewApacheLicense),
    )
