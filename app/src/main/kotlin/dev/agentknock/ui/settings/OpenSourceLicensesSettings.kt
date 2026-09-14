package dev.agentknock.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.ui.compose.LibraryDefaults
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.ui.compose.m3.style.m3VariantColors
import com.mikepenz.aboutlibraries.ui.compose.style.LibraryActionBadges
import com.mikepenz.aboutlibraries.ui.compose.style.LicenseHueResolver
import com.mikepenz.aboutlibraries.ui.compose.variant.LibrariesDensity
import com.mikepenz.aboutlibraries.ui.compose.variant.LibrariesVariant
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryActionKind
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryBadges
import com.mikepenz.aboutlibraries.ui.compose.variant.LibraryDetailMode
import dev.agentknock.R

@Composable
internal fun OpenSourceLicensesSettings(
    onBack: () -> Unit,
    report: (String) -> Unit,
    modifier: Modifier,
) {
    val libraries by produceLibraries(R.raw.aboutlibraries)
    val uriHandler = LocalUriHandler.current
    OpenSourceLicensesSettingsContent(
        libraries = libraries,
        onBack = onBack,
        onOpenSource = { url ->
            runCatching { uriHandler.openUri(url) }
                .onFailure { report("Could not open source code") }
        },
        modifier = modifier,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun OpenSourceLicensesSettingsContent(
    libraries: Libs?,
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
    modifier: Modifier,
) {
    // Save the ID rather than the license text: some upstream notices are much larger than
    // Android's saved-state limit. The selected notice is restored from the bundled catalog.
    var selectedLibraryId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedLibrary = libraries?.libraries?.firstOrNull { it.uniqueId == selectedLibraryId }

    Column(modifier) {
        PageTopBar("Open source licenses", onBack)
        if (libraries == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LibrariesContainer(
                libraries = libraries,
                dialogLibrary = null,
                sheetLibrary = selectedLibrary,
                onDialogLibraryChange = { selectedLibraryId = it?.uniqueId },
                onSheetLibraryChange = { selectedLibraryId = it?.uniqueId },
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                variant = LibrariesVariant.Refined,
                variantColors =
                    LibraryDefaults.m3VariantColors(licenseHueResolver = LicenseHueResolver.None),
                density = LibrariesDensity.Compact,
                detailMode = LibraryDetailMode.Sheet,
                badges = LibraryBadges(version = false, author = false),
                // The sheet already contains the complete license. Keep its source link for
                // notices such as the Public Suffix List, without a second online license action.
                actionLabels =
                    LibraryActionBadges(
                        websiteEnabled = false,
                        sponsorEnabled = false,
                        licenseEnabled = false,
                    ),
                onActionClick = { library, action ->
                    if (action == LibraryActionKind.Source) {
                        onOpenSource(requireNotNull(library.scm?.url))
                        true
                    } else {
                        false
                    }
                },
            )
        }
    }
}
