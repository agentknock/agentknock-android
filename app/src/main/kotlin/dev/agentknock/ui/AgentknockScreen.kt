package dev.agentknock.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.agentknock.R
import dev.agentknock.ExternalNavigation
import dev.agentknock.ui.clients.ClientsScreen
import dev.agentknock.ui.clients.ClientsViewModel
import dev.agentknock.ui.secrets.SecretsScreen
import dev.agentknock.ui.secrets.SecretsEditor
import dev.agentknock.ui.secrets.SecretsViewModel
import dev.agentknock.ui.requests.RequestsScreen
import dev.agentknock.ui.requests.RequestsViewModel
import dev.agentknock.ui.settings.AuditViewModel
import dev.agentknock.ui.settings.SettingsScreen
import dev.agentknock.ui.settings.SettingsViewModel
import dev.agentknock.ui.settings.SubscriptionViewModel
import dev.agentknock.ui.settings.SubscriptionAccess
import dev.agentknock.ui.device.DeviceSetupScreen
import dev.agentknock.ui.device.DeviceSetupViewModel
import dev.agentknock.ui.auth.AuthenticationSession
import dev.agentknock.ui.auth.DeviceAuthenticationMode
import dev.agentknock.ui.auth.SensitiveDataBackgroundGuard
import dev.agentknock.ui.auth.DeviceAuthenticationRequest
import dev.agentknock.push.RequestNotifications
import dev.agentknock.storage.device.DeviceIdentity
import dev.agentknock.storage.request.InboxRequestKind
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun AgentknockScreen(
    authenticationRequest: StateFlow<DeviceAuthenticationRequest?>,
    sensitiveDataBackgroundGuard: SensitiveDataBackgroundGuard,
    authentication: AuthenticationSession,
    externalNavigation: StateFlow<ExternalNavigation?>,
    consumeExternalNavigation: (ExternalNavigation) -> Unit,
    notificationStateGeneration: StateFlow<Long>,
    requestNotificationPermission: () -> Unit,
    viewModelFactory: ViewModelProvider.Factory,
    agentknockViewModel: AgentknockViewModel = viewModel(factory = viewModelFactory),
    deviceSetupViewModel: @Composable () -> DeviceSetupViewModel = {
        viewModel(factory = viewModelFactory)
    },
    requestsViewModel: RequestsViewModel = viewModel(factory = viewModelFactory),
    secretsViewModel: SecretsViewModel = viewModel(factory = viewModelFactory),
    clientsViewModel: ClientsViewModel = viewModel(factory = viewModelFactory),
    subscriptionViewModel: SubscriptionViewModel = viewModel(factory = viewModelFactory),
    auditViewModel: @Composable () -> AuditViewModel = {
        viewModel(factory = viewModelFactory)
    },
    settingsViewModel: @Composable () -> SettingsViewModel = {
        viewModel(factory = viewModelFactory)
    },
) {
    val authenticationMode by authentication.mode.collectAsStateWithLifecycle()
    val sessionAuthenticated by authentication.authenticated.collectAsStateWithLifecycle()
    val unlockError by agentknockViewModel.unlockError.collectAsStateWithLifecycle()
    val configuration by agentknockViewModel.configuration.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(MainSection.REQUESTS) }
    var destination by rememberSaveable { mutableStateOf(RootDestination.MAIN) }
    var addressEditorIdentityId by rememberSaveable { mutableStateOf<String?>(null) }
    var addressEditorOriginalAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var waitingForInitialClaim by rememberSaveable { mutableStateOf(false) }
    val rootStateHolder = rememberSaveableStateHolder()
    var showNavigation by rememberSaveable { mutableStateOf(true) }
    var offerNotifications by rememberSaveable { mutableStateOf(false) }
    val externalNavigationTarget by externalNavigation.collectAsStateWithLifecycle()
    val notificationRefreshGeneration by notificationStateGeneration.collectAsStateWithLifecycle()
    val requestSummaries by agentknockViewModel.requests.collectAsStateWithLifecycle()
    val secretsEditor by secretsViewModel.editor.collectAsStateWithLifecycle()
    val subscription by subscriptionViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    val notificationsEnabled = remember(notificationRefreshGeneration) {
        RequestNotifications.actionNotificationsEnabled(context)
    }

    DisposableEffect(
        lifecycle,
        activity,
        secretsViewModel,
        authenticationRequest,
        sensitiveDataBackgroundGuard,
    ) {
        val observer = LifecycleEventObserver { _, event ->
            if (
                event == Lifecycle.Event.ON_STOP &&
                activity?.isChangingConfigurations != true
            ) {
                if (sensitiveDataBackgroundGuard.onStop(authenticationRequest.value != null)) {
                    secretsViewModel.clearSensitiveData()
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(
        authenticationRequest,
        lifecycle,
        secretsViewModel,
        sensitiveDataBackgroundGuard,
    ) {
        authenticationRequest.collect { request ->
            if (
                sensitiveDataBackgroundGuard.onAuthenticationChanged(
                    inProgress = request != null,
                    foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
                )
            ) {
                secretsViewModel.clearSensitiveData()
            }
        }
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

    fun openAddressEditor() {
        addressEditorIdentityId = current?.active?.id
        addressEditorOriginalAddress = current?.active?.address
        destination = RootDestination.ADDRESS_EDITOR
    }

    fun closeAddressEditor() {
        addressEditorIdentityId = null
        addressEditorOriginalAddress = null
        destination = RootDestination.MAIN
        rootStateHolder.removeState(ADDRESS_EDITOR_STATE_KEY)
    }

    fun closeSettings() {
        destination = RootDestination.MAIN
        rootStateHolder.removeState(SETTINGS_STATE_KEY)
    }

    LaunchedEffect(current?.active?.deviceId, subscriptionViewModel) {
        if (current?.active?.credentialsAvailable == true) subscriptionViewModel.refresh()
    }

    LaunchedEffect(current) {
        val active = current?.active
        val addressEditorTargetChanged = addressEditorCompleted(
            originalIdentityId = addressEditorIdentityId,
            originalAddress = addressEditorOriginalAddress,
            active = active,
        )
        if (current != null && active == null) {
            waitingForInitialClaim = true
        } else if (active?.credentialsAvailable == true && waitingForInitialClaim) {
            waitingForInitialClaim = false
            offerNotifications = true
        }
        if (
            destination == RootDestination.ADDRESS_EDITOR &&
            addressEditorTargetChanged
        ) {
            closeAddressEditor()
        }
    }

    LaunchedEffect(current?.active?.credentialsAvailable) {
        if (current?.active?.credentialsAvailable == true) {
            rootStateHolder.removeState(SETUP_STATE_KEY)
        }
    }

    LaunchedEffect(section, destination) {
        if (section != MainSection.SECRETS || destination != RootDestination.MAIN) {
            secretsViewModel.clearRevealedValues()
        }
    }

    LaunchedEffect(
        externalNavigationTarget,
        current,
        destination,
        authenticationMode,
        sessionAuthenticated,
        secretsEditor is SecretsEditor.None,
    ) {
        val target = externalNavigationTarget ?: return@LaunchedEffect
        if (
            destination == RootDestination.ADDRESS_EDITOR ||
            current?.active?.credentialsAvailable != true
        ) {
            return@LaunchedEffect
        }
        if (secretsEditor !is SecretsEditor.None) {
            section = MainSection.SECRETS
            destination = RootDestination.MAIN
            return@LaunchedEffect
        }
        if (
            authenticationMode == DeviceAuthenticationMode.APP_LOCK &&
            !sessionAuthenticated
        ) {
            return@LaunchedEffect
        }
        when (target) {
            is ExternalNavigation.Request -> {
                val requestId = target.requestId
                val targetSection = if (requestId == null) {
                    MainSection.REQUESTS
                } else {
                    when (agentknockViewModel.findRequestKind(requestId)) {
                        InboxRequestKind.SECRET_USE -> MainSection.REQUESTS
                        InboxRequestKind.GIT_SIGN -> MainSection.REQUESTS
                        InboxRequestKind.SSH_AUTHENTICATE -> MainSection.REQUESTS
                        InboxRequestKind.SECRET_UPLOAD -> MainSection.SECRETS
                        InboxRequestKind.PAIRING -> MainSection.CLIENTS
                        null -> {
                            consumeExternalNavigation(target)
                            return@LaunchedEffect
                        }
                    }
                }
                section = targetSection
                destination = RootDestination.MAIN
                when (targetSection) {
                    MainSection.REQUESTS -> requestsViewModel.selectRequest(requestId)
                    MainSection.SECRETS -> {
                        secretsViewModel.selectUpload(requestId, retainResolved = true)
                    }
                    MainSection.CLIENTS -> {
                        clientsViewModel.selectPairing(requestId, retainResolved = true)
                    }
                }
                consumeExternalNavigation(target)
            }
            is ExternalNavigation.SubscriptionRedemption -> {
                subscriptionViewModel.redeem(target.token)
                destination = RootDestination.PLAN
                consumeExternalNavigation(target)
            }
            ExternalNavigation.InvalidSubscriptionLink -> {
                subscriptionViewModel.reportInvalidLink()
                destination = RootDestination.PLAN
                consumeExternalNavigation(target)
            }
        }
    }

    when {
        authenticationMode == DeviceAuthenticationMode.APP_LOCK && !sessionAuthenticated ->
            AgentknockLockedScreen(
                error = unlockError,
                onUnlock = agentknockViewModel::unlock,
            )
        current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        destination == RootDestination.SETTINGS ||
            destination == RootDestination.PLAN -> rootStateHolder.SaveableStateProvider(
            SETTINGS_STATE_KEY,
        ) {
            SettingsScreen(
                onClose = ::closeSettings,
                authenticationMode = authenticationMode,
                notificationStateGeneration = notificationRefreshGeneration,
                requestNotificationPermission = requestNotificationPermission,
                openPlanInitially = destination == RootDestination.PLAN,
                onPlanOpened = {
                    if (destination == RootDestination.PLAN) {
                        destination = RootDestination.SETTINGS
                    }
                },
                subscriptionViewModel = subscriptionViewModel,
                auditViewModel = auditViewModel(),
                viewModel = settingsViewModel(),
            )
        }
        current.active == null || !current.active.credentialsAvailable ->
            rootStateHolder.SaveableStateProvider(SETUP_STATE_KEY) {
                DeviceSetupScreen(
                    configuration = current,
                    onDone = null,
                    onOpenSettings = { destination = RootDestination.SETTINGS },
                    viewModel = deviceSetupViewModel(),
                )
            }
        destination == RootDestination.ADDRESS_EDITOR ->
            rootStateHolder.SaveableStateProvider(ADDRESS_EDITOR_STATE_KEY) {
                DeviceSetupScreen(
                    configuration = current,
                    onDone = ::closeAddressEditor,
                    changeAddressInitially = true,
                    viewModel = deviceSetupViewModel(),
                )
            }
        else -> rootStateHolder.SaveableStateProvider("$MAIN_STATE_PREFIX${section.name}") {
            val navigationSuiteType = NavigationSuiteScaffoldDefaults.navigationSuiteType(
                currentWindowAdaptiveInfoV2(),
            )
            val navigationState = rememberNavigationSuiteScaffoldState(
                initialValue = if (showNavigation) {
                    NavigationSuiteScaffoldValue.Visible
                } else {
                    NavigationSuiteScaffoldValue.Hidden
                },
            )
            LaunchedEffect(showNavigation, navigationState) {
                if (showNavigation) navigationState.show() else navigationState.hide()
            }
            val navigationIsVertical =
                navigationSuiteType == NavigationSuiteType.WideNavigationRailCollapsed ||
                    navigationSuiteType == NavigationSuiteType.WideNavigationRailExpanded
            val contentNeedsBottomInset = navigationIsVertical ||
                (navigationState.currentValue == NavigationSuiteScaffoldValue.Hidden &&
                    !navigationState.isAnimating)

            NavigationSuiteScaffold(
                navigationItems = {
                    MainNavigationItems(
                        section = section,
                        actionRequiredCounts = actionRequiredCounts,
                        onSelect = { section = it },
                    )
                },
                navigationSuiteType = navigationSuiteType,
                state = navigationState,
                modifier = Modifier.fillMaxSize().windowInsetsPadding(
                    WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal),
                ),
            ) {
                MainContent(
                    section = section,
                    onOpenSettings = { destination = RootDestination.SETTINGS },
                    onChangePairingAddress = ::openAddressEditor,
                    notificationsEnabled = notificationsEnabled,
                    aiReviewActive = subscription.access == SubscriptionAccess.ACTIVE,
                    onTopLevelChanged = { showNavigation = it },
                    requestsViewModel = requestsViewModel,
                    secretsViewModel = secretsViewModel,
                    clientsViewModel = clientsViewModel,
                    modifier = Modifier.fillMaxSize().then(
                        if (contentNeedsBottomInset) {
                            Modifier.windowInsetsPadding(
                                WindowInsets.navigationBars.only(WindowInsetsSides.Bottom),
                            )
                        } else {
                            Modifier
                        },
                    ),
                )
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
    error: String?,
    onUnlock: () -> Unit,
) {
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
                onClick = onUnlock,
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
                onOpenSettings = onOpenSettings,
                onTopLevelChanged = onTopLevelChanged,
                aiReviewActive = aiReviewActive,
                viewModel = secretsViewModel,
            )
            MainSection.CLIENTS -> ClientsScreen(
                onOpenSettings = onOpenSettings,
                onChangePairingAddress = onChangePairingAddress,
                onTopLevelChanged = onTopLevelChanged,
                viewModel = clientsViewModel,
            )
        }
    }
}

@Composable
private fun MainNavigationItems(
    section: MainSection,
    actionRequiredCounts: Map<MainSection, Int>,
    onSelect: (MainSection) -> Unit,
) {
    MainSection.entries.forEach { item ->
        NavigationSuiteItem(
            selected = section == item,
            onClick = { onSelect(item) },
            icon = { MainSectionIcon(item, actionRequiredCounts[item] ?: 0) },
            label = {
                Text(
                    text = item.label(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )
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

private enum class RootDestination {
    MAIN,
    SETTINGS,
    PLAN,
    ADDRESS_EDITOR,
}

internal fun addressEditorCompleted(
    originalIdentityId: String?,
    originalAddress: String?,
    active: DeviceIdentity?,
): Boolean = originalIdentityId != null && active != null && (
    active.id != originalIdentityId ||
        active.address != originalAddress
    )

private const val SETUP_STATE_KEY = "setup"
private const val SETTINGS_STATE_KEY = "settings"
private const val ADDRESS_EDITOR_STATE_KEY = "address_editor"
private const val MAIN_STATE_PREFIX = "main:"
