package dev.agentknock.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.agentknock.R
import dev.agentknock.RequestNavigation
import dev.agentknock.ui.clients.ClientsScreen
import dev.agentknock.ui.profiles.ProfilesScreen
import dev.agentknock.ui.requests.RequestsScreen
import dev.agentknock.ui.requests.RequestsViewModel
import dev.agentknock.ui.settings.SettingsScreen
import dev.agentknock.ui.vault.VaultScreen
import dev.agentknock.ui.vault.VaultViewModel
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun AgentknockScreen(
    authenticate: (
        title: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) -> Unit,
    requestNavigation: StateFlow<RequestNavigation>,
    requestNotificationPermission: () -> Unit,
    vaultViewModel: VaultViewModel = viewModel(),
    requestsViewModel: RequestsViewModel = viewModel(),
) {
    val configuration by vaultViewModel.configuration.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(MainSection.REQUESTS) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAddressEditor by rememberSaveable { mutableStateOf(false) }
    var showNavigation by rememberSaveable { mutableStateOf(true) }
    var offerNotifications by rememberSaveable { mutableStateOf(false) }
    val requestNavigationTarget by requestNavigation.collectAsStateWithLifecycle()
    val current = configuration

    LaunchedEffect(requestNavigationTarget) {
        if (requestNavigationTarget.generation > 0) {
            section = MainSection.REQUESTS
            showSettings = false
            showAddressEditor = false
            requestsViewModel.selectRequest(requestNavigationTarget.requestId)
        }
    }

    when {
        current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        current.active == null || !current.active.secretsAvailable -> VaultScreen(
            configuration = current,
            authenticate = authenticate,
            onDone = current.active?.takeIf { it.secretsAvailable }?.let {
                { section = MainSection.REQUESTS }
            },
            onDeviceClaimed = { offerNotifications = true },
            viewModel = vaultViewModel,
        )
        showAddressEditor -> VaultScreen(
            configuration = current,
            authenticate = authenticate,
            onDone = {
                showAddressEditor = false
                showSettings = true
            },
            changeAddressInitially = true,
            onDeviceClaimed = {},
            viewModel = vaultViewModel,
        )
        showSettings -> SettingsScreen(
            onClose = { showSettings = false },
            onChangeAddress = {
                showSettings = false
                showAddressEditor = true
            },
            authenticate = authenticate,
            requestNotificationPermission = requestNotificationPermission,
        )
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val useNavigationRail = maxWidth >= 600.dp
            LaunchedEffect(useNavigationRail) {
                if (useNavigationRail) showNavigation = true
            }
            if (useNavigationRail) {
                Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
                    if (showNavigation) {
                        MainNavigationRail(
                            section = section,
                            onSelect = { section = it },
                        )
                    }
                    MainContent(
                        section = section,
                        authenticate = authenticate,
                        onOpenSettings = { showSettings = true },
                        onTopLevelChanged = { showNavigation = true },
                        requestsViewModel = requestsViewModel,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Scaffold(
                    contentWindowInsets = WindowInsets.navigationBars,
                    bottomBar = {
                        if (showNavigation) {
                            MainNavigationBar(
                                section = section,
                                onSelect = { section = it },
                            )
                        }
                    },
                ) { padding ->
                    MainContent(
                        section = section,
                        authenticate = authenticate,
                        onOpenSettings = { showSettings = true },
                        onTopLevelChanged = { showNavigation = it },
                        requestsViewModel = requestsViewModel,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                }
            }
        }
    }

    if (offerNotifications) {
        AlertDialog(
            onDismissRequest = { offerNotifications = false },
            title = { Text("Stay informed about requests?") },
            text = {
                Text(
                    "Agentknock can notify you when a pairing, profile proposal, or profile access request needs attention. You control notification privacy in Android settings.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        offerNotifications = false
                        requestNotificationPermission()
                    },
                ) { Text("Allow notifications") }
            },
            dismissButton = {
                TextButton(onClick = { offerNotifications = false }) { Text("Not now") }
            },
        )
    }
}

@Composable
private fun MainContent(
    section: MainSection,
    authenticate: (String, () -> Unit, (String) -> Unit) -> Unit,
    onOpenSettings: () -> Unit,
    onTopLevelChanged: (Boolean) -> Unit,
    requestsViewModel: RequestsViewModel,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        when (section) {
            MainSection.REQUESTS -> RequestsScreen(
                authenticate = authenticate,
                onOpenSettings = onOpenSettings,
                viewModel = requestsViewModel,
                onTopLevelChanged = onTopLevelChanged,
            )
            MainSection.PROFILES -> ProfilesScreen(
                authenticate = authenticate,
                onOpenSettings = onOpenSettings,
                onTopLevelChanged = onTopLevelChanged,
            )
            MainSection.CLIENTS -> ClientsScreen(
                onOpenSettings = onOpenSettings,
                onTopLevelChanged = onTopLevelChanged,
                authenticate = authenticate,
            )
        }
    }
}

@Composable
private fun MainNavigationBar(section: MainSection, onSelect: (MainSection) -> Unit) {
    NavigationBar {
        MainSection.entries.forEach { item ->
            NavigationBarItem(
                selected = section == item,
                onClick = { onSelect(item) },
                icon = { MainSectionIcon(item) },
                label = { Text(item.label()) },
            )
        }
    }
}

@Composable
private fun MainNavigationRail(section: MainSection, onSelect: (MainSection) -> Unit) {
    NavigationRail {
        MainSection.entries.forEach { item ->
            NavigationRailItem(
                selected = section == item,
                onClick = { onSelect(item) },
                icon = { MainSectionIcon(item) },
                label = { Text(item.label()) },
            )
        }
    }
}

@Composable
private fun MainSectionIcon(section: MainSection) {
    Icon(
        when (section) {
            MainSection.REQUESTS -> Icons.Outlined.Inbox
            MainSection.PROFILES -> Icons.Outlined.Key
            MainSection.CLIENTS -> Icons.Outlined.Computer
        },
        contentDescription = null,
    )
}

@Composable
private fun MainSection.label(): String = when (this) {
    MainSection.REQUESTS -> stringResource(R.string.requests)
    MainSection.PROFILES -> stringResource(R.string.profiles)
    MainSection.CLIENTS -> "Clients"
}

private enum class MainSection {
    REQUESTS,
    PROFILES,
    CLIENTS,
}
