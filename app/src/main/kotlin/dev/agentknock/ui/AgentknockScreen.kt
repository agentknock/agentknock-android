package dev.agentknock.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
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
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.agentknock.R
import dev.agentknock.RequestNavigation
import dev.agentknock.SubscriptionNavigation
import dev.agentknock.ui.clients.ClientsScreen
import dev.agentknock.ui.clients.ClientsViewModel
import dev.agentknock.ui.secrets.SecretsScreen
import dev.agentknock.ui.secrets.SecretsViewModel
import dev.agentknock.ui.requests.RequestsScreen
import dev.agentknock.ui.requests.RequestsViewModel
import dev.agentknock.ui.settings.SettingsScreen
import dev.agentknock.ui.settings.SettingsViewModel
import dev.agentknock.ui.settings.SubscriptionViewModel
import dev.agentknock.ui.settings.SubscriptionAccess
import dev.agentknock.ui.device.DeviceSetupScreen
import dev.agentknock.ui.device.DeviceSetupViewModel
import dev.agentknock.ui.auth.AuthenticationSession
import dev.agentknock.ui.auth.DeviceAuthenticationMode
import dev.agentknock.push.RequestNotifications
import dev.agentknock.storage.request.InboxRequestKind
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun AgentknockScreen(
    authenticate: (
        title: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) -> Unit,
    authentication: AuthenticationSession,
    requestNavigation: StateFlow<RequestNavigation?>,
    consumeRequestNavigation: (RequestNavigation) -> Unit,
    subscriptionNavigation: StateFlow<SubscriptionNavigation?>,
    consumeSubscriptionNavigation: (SubscriptionNavigation) -> Unit,
    notificationStateGeneration: StateFlow<Long>,
    requestNotificationPermission: () -> Unit,
    viewModelFactory: ViewModelProvider.Factory,
    deviceSetupViewModel: DeviceSetupViewModel = viewModel(factory = viewModelFactory),
    requestsViewModel: RequestsViewModel = viewModel(factory = viewModelFactory),
    secretsViewModel: SecretsViewModel = viewModel(factory = viewModelFactory),
    clientsViewModel: ClientsViewModel = viewModel(factory = viewModelFactory),
    subscriptionViewModel: SubscriptionViewModel = viewModel(factory = viewModelFactory),
    settingsViewModel: SettingsViewModel = viewModel(factory = viewModelFactory),
) {
    val authenticationMode by authentication.mode.collectAsStateWithLifecycle()
    val sessionAuthenticated by authentication.authenticated.collectAsStateWithLifecycle()
    val configuration by deviceSetupViewModel.configuration.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(MainSection.REQUESTS) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showAddressEditor by rememberSaveable { mutableStateOf(false) }
    var openPlanInitially by remember { mutableStateOf(false) }
    var showNavigation by rememberSaveable { mutableStateOf(true) }
    var offerNotifications by rememberSaveable { mutableStateOf(false) }
    val requestNavigationTarget by requestNavigation.collectAsStateWithLifecycle()
    val subscriptionNavigationTarget by subscriptionNavigation.collectAsStateWithLifecycle()
    val notificationRefreshGeneration by notificationStateGeneration.collectAsStateWithLifecycle()
    val requestSummaries by requestsViewModel.allRequests.collectAsStateWithLifecycle()
    val subscription by subscriptionViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val notificationsEnabled = remember(notificationRefreshGeneration) {
        RequestNotifications.actionNotificationsEnabled(context)
    }
    val actionRequiredCounts = MainSection.entries.associateWith { section ->
        when (section) {
            MainSection.REQUESTS -> requestSummaries.actionRequiredCount(
                InboxRequestKind.SECRET_USE,
                InboxRequestKind.GIT_SIGN,
                InboxRequestKind.SSH_AUTHENTICATE,
            )
            MainSection.SECRETS -> requestSummaries.actionRequiredCount(InboxRequestKind.SECRET_UPLOAD)
            MainSection.CLIENTS -> requestSummaries.actionRequiredCount(InboxRequestKind.PAIRING)
        }
    }
    val current = configuration

    LaunchedEffect(current?.active?.deviceId, subscriptionViewModel) {
        if (current?.active?.credentialsAvailable == true) subscriptionViewModel.refresh()
    }

    fun authorizeProtectedAction(
        title: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        authentication.authorizeProtectedAction(title, authenticate, onSuccess, onError)
    }

    LaunchedEffect(requestNavigationTarget, requestSummaries) {
        val target = requestNavigationTarget ?: return@LaunchedEffect
        val requestId = target.requestId
        val targetSection = if (requestId == null) {
            MainSection.REQUESTS
        } else {
            when (requestSummaries.firstOrNull { it.id == requestId }?.kind ?: return@LaunchedEffect) {
                InboxRequestKind.SECRET_USE -> MainSection.REQUESTS
                InboxRequestKind.GIT_SIGN -> MainSection.REQUESTS
                InboxRequestKind.SSH_AUTHENTICATE -> MainSection.REQUESTS
                InboxRequestKind.SECRET_UPLOAD -> MainSection.SECRETS
                InboxRequestKind.PAIRING -> MainSection.CLIENTS
            }
        }
        section = targetSection
        showSettings = false
        showAddressEditor = false
        when (targetSection) {
            MainSection.REQUESTS -> requestsViewModel.selectRequest(requestId)
            MainSection.SECRETS -> secretsViewModel.selectUpload(requestId)
            MainSection.CLIENTS -> clientsViewModel.selectPairing(requestId)
        }
        consumeRequestNavigation(target)
    }

    LaunchedEffect(
        subscriptionNavigationTarget,
        current,
        authenticationMode,
        sessionAuthenticated,
    ) {
        val target = subscriptionNavigationTarget ?: return@LaunchedEffect
        if (
            current?.active?.credentialsAvailable != true ||
            (authenticationMode == DeviceAuthenticationMode.APP_LOCK && !sessionAuthenticated)
        ) {
            return@LaunchedEffect
        }
        showSettings = true
        showAddressEditor = false
        openPlanInitially = true
        when (target) {
            is SubscriptionNavigation.Redemption -> subscriptionViewModel.redeem(target.token)
            SubscriptionNavigation.InvalidLink -> subscriptionViewModel.reportInvalidLink()
        }
        consumeSubscriptionNavigation(target)
    }

    when {
        authenticationMode == DeviceAuthenticationMode.APP_LOCK && !sessionAuthenticated ->
            AgentknockLockedScreen(
                onUnlock = { onSuccess, onError ->
                    authentication.authorizeProtectedAction(
                        "Unlock Agentknock",
                        authenticate,
                        onSuccess,
                        onError,
                    )
                },
            )
        current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        showSettings -> SettingsScreen(
            onClose = { showSettings = false },
            authenticate = authenticate,
            authenticationMode = authenticationMode,
            onAuthenticationModeChange = { mode, onError ->
                authentication.changeMode(mode, authenticate, onError)
            },
            notificationStateGeneration = notificationRefreshGeneration,
            requestNotificationPermission = requestNotificationPermission,
            openPlanInitially = openPlanInitially,
            onPlanOpened = { openPlanInitially = false },
            subscriptionViewModel = subscriptionViewModel,
            viewModel = settingsViewModel,
        )
        current.active == null || !current.active.credentialsAvailable -> DeviceSetupScreen(
            configuration = current,
            onDone = current.active?.takeIf { it.credentialsAvailable }?.let {
                { section = MainSection.REQUESTS }
            },
            onDeviceClaimed = { offerNotifications = true },
            onOpenSettings = { showSettings = true },
            viewModel = deviceSetupViewModel,
        )
        showAddressEditor -> DeviceSetupScreen(
            configuration = current,
            onDone = {
                showAddressEditor = false
            },
            changeAddressInitially = true,
            onDeviceClaimed = {},
            viewModel = deviceSetupViewModel,
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
                            actionRequiredCounts = actionRequiredCounts,
                            onSelect = { section = it },
                        )
                    }
                    MainContent(
                        section = section,
                        authorizeProtectedAction = ::authorizeProtectedAction,
                        onOpenSettings = { showSettings = true },
                        onChangePairingAddress = { showAddressEditor = true },
                        notificationsEnabled = notificationsEnabled,
                        aiReviewActive = subscription.access == SubscriptionAccess.ACTIVE,
                        onTopLevelChanged = { showNavigation = true },
                        requestsViewModel = requestsViewModel,
                        secretsViewModel = secretsViewModel,
                        clientsViewModel = clientsViewModel,
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
                                actionRequiredCounts = actionRequiredCounts,
                                onSelect = { section = it },
                            )
                        }
                    },
                ) { padding ->
                    MainContent(
                        section = section,
                        authorizeProtectedAction = ::authorizeProtectedAction,
                        onOpenSettings = { showSettings = true },
                        onChangePairingAddress = { showAddressEditor = true },
                        notificationsEnabled = notificationsEnabled,
                        aiReviewActive = subscription.access == SubscriptionAccess.ACTIVE,
                        onTopLevelChanged = { showNavigation = it },
                        requestsViewModel = requestsViewModel,
                        secretsViewModel = secretsViewModel,
                        clientsViewModel = clientsViewModel,
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
                }
            }
        }
    }

    if (
        offerNotifications &&
        (authenticationMode != DeviceAuthenticationMode.APP_LOCK || sessionAuthenticated)
    ) {
        AlertDialog(
            onDismissRequest = { offerNotifications = false },
            title = { Text("Stay informed about requests?") },
            text = {
                Text(
                    "Agentknock can notify you when a pairing, secret upload, secret use, or Git signature needs attention. You control notification privacy in Android settings.",
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
private fun AgentknockLockedScreen(
    onUnlock: (onSuccess: () -> Unit, onError: (String) -> Unit) -> Unit,
) {
    var error by remember { mutableStateOf<String?>(null) }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text(
                "Agentknock is locked",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Button(
                onClick = { onUnlock({ error = null }, { error = it }) },
                modifier = Modifier.padding(top = 20.dp),
            ) {
                Text("Unlock Agentknock")
            }
        }
    }
}

@Composable
private fun MainContent(
    section: MainSection,
    authorizeProtectedAction: (String, () -> Unit, (String) -> Unit) -> Unit,
    onOpenSettings: () -> Unit,
    onChangePairingAddress: () -> Unit,
    notificationsEnabled: Boolean,
    aiReviewActive: Boolean,
    onTopLevelChanged: (Boolean) -> Unit,
    requestsViewModel: RequestsViewModel,
    secretsViewModel: SecretsViewModel,
    clientsViewModel: ClientsViewModel,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        when (section) {
            MainSection.REQUESTS -> RequestsScreen(
                onOpenSettings = onOpenSettings,
                notificationsEnabled = notificationsEnabled,
                viewModel = requestsViewModel,
                onTopLevelChanged = onTopLevelChanged,
            )
            MainSection.SECRETS -> SecretsScreen(
                authorizeProtectedAction = authorizeProtectedAction,
                onOpenSettings = onOpenSettings,
                onTopLevelChanged = onTopLevelChanged,
                aiReviewActive = aiReviewActive,
                viewModel = secretsViewModel,
            )
            MainSection.CLIENTS -> ClientsScreen(
                authorizeProtectedAction = authorizeProtectedAction,
                onOpenSettings = onOpenSettings,
                onChangePairingAddress = onChangePairingAddress,
                onTopLevelChanged = onTopLevelChanged,
                viewModel = clientsViewModel,
            )
        }
    }
}

@Composable
private fun MainNavigationBar(
    section: MainSection,
    actionRequiredCounts: Map<MainSection, Int>,
    onSelect: (MainSection) -> Unit,
) {
    NavigationBar {
        MainSection.entries.forEach { item ->
            NavigationBarItem(
                selected = section == item,
                onClick = { onSelect(item) },
                icon = { MainSectionIcon(item, actionRequiredCounts[item] ?: 0) },
                label = { Text(item.label()) },
            )
        }
    }
}

@Composable
private fun MainNavigationRail(
    section: MainSection,
    actionRequiredCounts: Map<MainSection, Int>,
    onSelect: (MainSection) -> Unit,
) {
    NavigationRail {
        MainSection.entries.forEach { item ->
            NavigationRailItem(
                selected = section == item,
                onClick = { onSelect(item) },
                icon = { MainSectionIcon(item, actionRequiredCounts[item] ?: 0) },
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
            },
            contentDescription = null,
        )
    }
    if (actionRequiredCount > 0) {
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
}

private enum class MainSection {
    REQUESTS,
    SECRETS,
    CLIENTS,
}
