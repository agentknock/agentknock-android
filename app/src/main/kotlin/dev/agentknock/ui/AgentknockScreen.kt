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
import androidx.compose.material.icons.automirrored.outlined.Rule
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.agentknock.R
import dev.agentknock.RequestNavigation
import dev.agentknock.ui.clients.ClientsScreen
import dev.agentknock.ui.secrets.SecretsScreen
import dev.agentknock.ui.requests.RequestsScreen
import dev.agentknock.ui.requests.RequestsViewModel
import dev.agentknock.ui.settings.SettingsScreen
import dev.agentknock.ui.rules.RulesScreen
import dev.agentknock.ui.rules.RulesViewModel
import dev.agentknock.ui.device.DeviceSetupScreen
import dev.agentknock.ui.device.DeviceSetupViewModel
import dev.agentknock.push.RequestNotifications
import dev.agentknock.storage.request.InboxRequestState
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun AgentknockScreen(
    authenticate: (
        title: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) -> Unit,
    requestNavigation: StateFlow<RequestNavigation>,
    notificationStateGeneration: StateFlow<Long>,
    requestNotificationPermission: () -> Unit,
    deviceSetupViewModel: DeviceSetupViewModel = viewModel(),
    requestsViewModel: RequestsViewModel = viewModel(),
    rulesViewModel: RulesViewModel = viewModel(),
) {
    val configuration by deviceSetupViewModel.configuration.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(MainSection.REQUESTS) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAddressEditor by rememberSaveable { mutableStateOf(false) }
    var showNavigation by rememberSaveable { mutableStateOf(true) }
    var offerNotifications by rememberSaveable { mutableStateOf(false) }
    val requestNavigationTarget by requestNavigation.collectAsStateWithLifecycle()
    val notificationRefreshGeneration by notificationStateGeneration.collectAsStateWithLifecycle()
    val requestSummaries by requestsViewModel.requests.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationsEnabled = remember(notificationRefreshGeneration) {
        RequestNotifications.areEnabled(context)
    }
    val actionRequiredCount = requestSummaries.count {
        it.state == InboxRequestState.ACTION_REQUIRED
    }
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
        current.active == null || !current.active.credentialsAvailable -> DeviceSetupScreen(
            configuration = current,
            authenticate = authenticate,
            onDone = current.active?.takeIf { it.credentialsAvailable }?.let {
                { section = MainSection.REQUESTS }
            },
            onDeviceClaimed = { offerNotifications = true },
            viewModel = deviceSetupViewModel,
        )
        showAddressEditor -> DeviceSetupScreen(
            configuration = current,
            authenticate = authenticate,
            onDone = {
                showAddressEditor = false
                showSettings = true
            },
            changeAddressInitially = true,
            onDeviceClaimed = {},
            viewModel = deviceSetupViewModel,
        )
        showSettings -> SettingsScreen(
            onClose = { showSettings = false },
            onChangeAddress = {
                showSettings = false
                showAddressEditor = true
            },
            authenticate = authenticate,
            notificationStateGeneration = notificationRefreshGeneration,
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
                            actionRequiredCount = actionRequiredCount,
                            onSelect = { section = it },
                        )
                    }
                    MainContent(
                        section = section,
                        authenticate = authenticate,
                        onOpenSettings = { showSettings = true },
                        notificationsEnabled = notificationsEnabled,
                        onTopLevelChanged = { showNavigation = true },
                        requestsViewModel = requestsViewModel,
                        rulesViewModel = rulesViewModel,
                        onSelectSection = { section = it },
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
                                actionRequiredCount = actionRequiredCount,
                                onSelect = { section = it },
                            )
                        }
                    },
                ) { padding ->
                    MainContent(
                        section = section,
                        authenticate = authenticate,
                        onOpenSettings = { showSettings = true },
                        notificationsEnabled = notificationsEnabled,
                        onTopLevelChanged = { showNavigation = it },
                        requestsViewModel = requestsViewModel,
                        rulesViewModel = rulesViewModel,
                        onSelectSection = { section = it },
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
                    "Agentknock can notify you when a pairing, secret upload, or secret use request needs attention. You control notification privacy in Android settings.",
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
    notificationsEnabled: Boolean,
    onTopLevelChanged: (Boolean) -> Unit,
    requestsViewModel: RequestsViewModel,
    rulesViewModel: RulesViewModel,
    onSelectSection: (MainSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        when (section) {
            MainSection.REQUESTS -> RequestsScreen(
                authenticate = authenticate,
                onOpenSettings = onOpenSettings,
                notificationsEnabled = notificationsEnabled,
                viewModel = requestsViewModel,
                onTopLevelChanged = onTopLevelChanged,
                onCreateRule = { requestId ->
                    rulesViewModel.startRuleFromRequest(requestId)
                    onSelectSection(MainSection.RULES)
                },
            )
            MainSection.SECRETS -> SecretsScreen(
                authenticate = authenticate,
                onOpenSettings = onOpenSettings,
                onTopLevelChanged = onTopLevelChanged,
            )
            MainSection.CLIENTS -> ClientsScreen(
                onOpenSettings = onOpenSettings,
                onTopLevelChanged = onTopLevelChanged,
                authenticate = authenticate,
            )
            MainSection.RULES -> RulesScreen(
                authenticate = authenticate,
                onOpenSettings = onOpenSettings,
                onOpenRequest = { requestId ->
                    requestsViewModel.selectRequest(requestId)
                    onSelectSection(MainSection.REQUESTS)
                },
                onTopLevelChanged = onTopLevelChanged,
                viewModel = rulesViewModel,
            )
        }
    }
}

@Composable
private fun MainNavigationBar(
    section: MainSection,
    actionRequiredCount: Int,
    onSelect: (MainSection) -> Unit,
) {
    NavigationBar {
        MainSection.entries.forEach { item ->
            NavigationBarItem(
                selected = section == item,
                onClick = { onSelect(item) },
                icon = { MainSectionIcon(item, actionRequiredCount) },
                label = { Text(item.label()) },
            )
        }
    }
}

@Composable
private fun MainNavigationRail(
    section: MainSection,
    actionRequiredCount: Int,
    onSelect: (MainSection) -> Unit,
) {
    NavigationRail {
        MainSection.entries.forEach { item ->
            NavigationRailItem(
                selected = section == item,
                onClick = { onSelect(item) },
                icon = { MainSectionIcon(item, actionRequiredCount) },
                label = { Text(item.label()) },
            )
        }
    }
}

@Composable
private fun MainSectionIcon(section: MainSection, actionRequiredCount: Int) {
    val icon: @Composable () -> Unit = {
        Icon(
            when (section) {
                MainSection.REQUESTS -> Icons.Outlined.Inbox
                MainSection.SECRETS -> Icons.Outlined.Key
                MainSection.CLIENTS -> Icons.Outlined.Computer
                MainSection.RULES -> Icons.AutoMirrored.Outlined.Rule
            },
            contentDescription = null,
        )
    }
    if (section == MainSection.REQUESTS && actionRequiredCount > 0) {
        BadgedBox(
            badge = {
                Badge {
                    Text(if (actionRequiredCount > 99) "99+" else actionRequiredCount.toString())
                }
            },
        ) { icon() }
    } else {
        icon()
    }
}

@Composable
private fun MainSection.label(): String = when (this) {
    MainSection.REQUESTS -> stringResource(R.string.requests)
    MainSection.SECRETS -> stringResource(R.string.secrets)
    MainSection.CLIENTS -> "Clients"
    MainSection.RULES -> "Rules"
}

private enum class MainSection {
    REQUESTS,
    SECRETS,
    CLIENTS,
    RULES,
}
