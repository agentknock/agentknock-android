package dev.agentknock.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
internal fun AgentKnockScreen(
    authenticate: (
        title: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) -> Unit,
    requestNavigation: StateFlow<RequestNavigation>,
    vaultViewModel: VaultViewModel = viewModel(),
    requestsViewModel: RequestsViewModel = viewModel(),
) {
    val configuration by vaultViewModel.configuration.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(MainSection.REQUESTS) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAddressEditor by rememberSaveable { mutableStateOf(false) }
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
            viewModel = vaultViewModel,
        )
        showAddressEditor -> VaultScreen(
            configuration = current,
            authenticate = authenticate,
            onDone = {
                showAddressEditor = false
                showSettings = true
            },
            viewModel = vaultViewModel,
        )
        showSettings -> SettingsScreen(
            onClose = { showSettings = false },
            onChangeAddress = {
                showSettings = false
                showAddressEditor = true
            },
            authenticate = authenticate,
        )
        else -> Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = section == MainSection.REQUESTS,
                        onClick = { section = MainSection.REQUESTS },
                        icon = { Icon(Icons.Outlined.Inbox, contentDescription = null) },
                        label = { Text(stringResource(R.string.requests)) },
                    )
                    NavigationBarItem(
                        selected = section == MainSection.PROFILES,
                        onClick = { section = MainSection.PROFILES },
                        icon = { Icon(Icons.Outlined.Key, contentDescription = null) },
                        label = { Text(stringResource(R.string.profiles)) },
                    )
                    NavigationBarItem(
                        selected = section == MainSection.CLIENTS,
                        onClick = { section = MainSection.CLIENTS },
                        icon = { Icon(Icons.Outlined.Computer, contentDescription = null) },
                        label = { Text("Clients") },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (section) {
                    MainSection.REQUESTS -> RequestsScreen(
                        authenticate = authenticate,
                        onOpenSettings = { showSettings = true },
                        viewModel = requestsViewModel,
                    )
                    MainSection.PROFILES -> ProfilesScreen(
                        authenticate = authenticate,
                        onOpenSettings = { showSettings = true },
                    )
                    MainSection.CLIENTS -> ClientsScreen(onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}

private enum class MainSection {
    REQUESTS,
    PROFILES,
    CLIENTS,
}
