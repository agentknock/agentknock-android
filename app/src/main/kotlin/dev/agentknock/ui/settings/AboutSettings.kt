package dev.agentknock.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.agentknock.BuildConfig
import dev.agentknock.R
import dev.agentknock.storage.device.DeviceIdentity

@Composable
internal fun AboutSettings(
    identity: DeviceIdentity?,
    onBack: () -> Unit,
    report: (String) -> Unit,
    modifier: Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showLicenses) { showLicenses = false }
    if (showLicenses) {
        OpenSourceLicensesSettings(
            onBack = { showLicenses = false },
            report = report,
            modifier = modifier,
        )
    } else {
        AboutSettingsContent(
            identity = identity,
            onBack = onBack,
            onOpenLicenses = { showLicenses = true },
            report = report,
            modifier = modifier,
            listState = listState,
        )
    }
}

@Composable
private fun AboutSettingsContent(
    identity: DeviceIdentity?,
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    report: (String) -> Unit,
    modifier: Modifier,
    listState: LazyListState,
) {
    val context = LocalContext.current
    fun open(label: String, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            .onFailure { report("Could not open $label") }
    }
    fun shareFeedback() {
        val subject =
            "Agentknock Android feedback — ${BuildConfig.VERSION_NAME} " +
                "(${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR})"
        val uri = "mailto:agentknock@fulldisclosure.fi?subject=${Uri.encode(subject)}".toUri()
        try {
            context.startActivity(Intent(Intent.ACTION_SENDTO, uri))
        } catch (_: ActivityNotFoundException) {
            report("No email app available")
        }
    }
    Column(modifier) {
        PageTopBar("About Agentknock", onBack)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            color = Color.Black,
                            shape = CircleShape,
                            modifier = Modifier.size(56.dp),
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = null,
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                        Text("Agentknock", style = MaterialTheme.typography.headlineMedium)
                    }
                    Text(
                        "Developer secrets on your phone, provided only to approved commands. " +
                            "Supply environment variables, sign Git commits and tags, or authenticate " +
                            "SSH connections without exposing private keys.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SettingsGroup {
                    SettingsRow(
                        title = "Share feedback",
                        onClick = ::shareFeedback,
                        trailing = {
                            Icon(
                                Icons.Outlined.Email,
                                contentDescription = "Opens email app",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                    )
                }
            }
            item {
                Column {
                    SettingsSectionLabel("Links")
                    SettingsGroup {
                        SettingsRow(
                            title = "Website",
                            summary = "agentknock.dev",
                            onClick = { open("website", "https://agentknock.dev/") },
                            external = true,
                        )
                        SettingsGroupDivider()
                        SettingsRow(
                            title = "Privacy notice",
                            summary = "agentknock.dev/privacy",
                            onClick = { open("privacy notice", "https://agentknock.dev/privacy") },
                            external = true,
                        )
                        SettingsGroupDivider()
                        SettingsRow(
                            title = "Terms of service",
                            summary = "agentknock.dev/terms",
                            onClick = { open("terms of service", "https://agentknock.dev/terms/") },
                            external = true,
                        )
                        SettingsGroupDivider()
                        SettingsRow(
                            title = "Source code",
                            summary = "github.com/agentknock/agentknock-android",
                            onClick = {
                                open(
                                    "source code",
                                    "https://github.com/agentknock/agentknock-android",
                                )
                            },
                            external = true,
                        )
                    }
                }
            }
            item {
                Column {
                    SettingsSectionLabel("App information")
                    SettingsGroup {
                        SettingsValueRow(
                            "Version",
                            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        )
                        SettingsGroupDivider()
                        SettingsValueRow(
                            "Source revision",
                            if (BuildConfig.SOURCE_REVISION == "unverified")
                                "Not recorded in this build"
                            else BuildConfig.SOURCE_REVISION,
                            monospace = BuildConfig.SOURCE_REVISION != "unverified",
                        )
                        SettingsGroupDivider()
                        SettingsValueRow("Developer", "Full Disclosure")
                        SettingsGroupDivider()
                        SettingsRow(
                            title = "Open source licenses",
                            onClick = onOpenLicenses,
                            trailing = { SettingsNavigationChevron() },
                        )
                    }
                }
            }
            item {
                Column {
                    SettingsSectionLabel("This device")
                    SettingsGroup {
                        identity?.let { device ->
                            SettingsValueRow(
                                "Device ID",
                                device.deviceId,
                                monospace = true,
                                trailing = {
                                    IconButton(
                                        onClick = {
                                            context
                                                .getSystemService(ClipboardManager::class.java)
                                                .setPrimaryClip(
                                                    ClipData.newPlainText(
                                                        "Agentknock device ID",
                                                        device.deviceId,
                                                    )
                                                )
                                            report("Device ID copied")
                                        }
                                    ) {
                                        Icon(
                                            Icons.Outlined.ContentCopy,
                                            contentDescription = "Copy device ID",
                                        )
                                    }
                                },
                            )
                            SettingsGroupDivider()
                        }
                        SettingsValueRow("Relay", "relay.agentknock.dev", monospace = true)
                    }
                }
            }
        }
    }
}
