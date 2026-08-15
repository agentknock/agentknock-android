package dev.agentknock.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import dev.agentknock.ui.profiles.ProfilesScreen
import dev.agentknock.ui.requests.RequestsScreen
import dev.agentknock.ui.requests.RequestsViewModel
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
    requestNavigation: StateFlow<Long>,
    vaultViewModel: VaultViewModel = viewModel(),
    requestsViewModel: RequestsViewModel = viewModel(),
) {
    val configuration by vaultViewModel.configuration.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(MainSection.REQUESTS) }
    val requestNavigationGeneration by requestNavigation.collectAsStateWithLifecycle()
    val current = configuration

    LaunchedEffect(requestNavigationGeneration) {
        if (requestNavigationGeneration > 0) {
            section = MainSection.REQUESTS
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
        else -> Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = section == MainSection.REQUESTS,
                        onClick = { section = MainSection.REQUESTS },
                        icon = { Text("⌂") },
                        label = { Text(stringResource(R.string.requests)) },
                    )
                    NavigationBarItem(
                        selected = section == MainSection.PROFILES,
                        onClick = { section = MainSection.PROFILES },
                        icon = { Text("≡") },
                        label = { Text(stringResource(R.string.profiles)) },
                    )
                    NavigationBarItem(
                        selected = section == MainSection.VAULT,
                        onClick = { section = MainSection.VAULT },
                        icon = { Text("◆") },
                        label = { Text(stringResource(R.string.vault)) },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (section) {
                    MainSection.REQUESTS -> RequestsScreen(viewModel = requestsViewModel)
                    MainSection.PROFILES -> ProfilesScreen(authenticate = authenticate)
                    MainSection.VAULT -> VaultScreen(
                        configuration = current,
                        authenticate = authenticate,
                        onDone = { section = MainSection.REQUESTS },
                        viewModel = vaultViewModel,
                    )
                }
            }
        }
    }
}

private enum class MainSection {
    REQUESTS,
    PROFILES,
    VAULT,
}
