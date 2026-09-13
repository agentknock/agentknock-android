package dev.agentknock.ui.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.agentknock.subscription.AiReviewAccess
import dev.agentknock.ui.auth.DeviceAuthenticationMode
import kotlinx.coroutines.launch

internal enum class SettingsPage {
    OVERVIEW,
    SECURITY_BACKUP,
    NOTIFICATIONS,
    SUBSCRIPTION,
    AUDIT,
    FACTORY_RESET,
    ABOUT,
}

@Composable
internal fun SettingsScreen(
    onClose: () -> Unit,
    onOpenSecrets: () -> Unit,
    authenticationMode: DeviceAuthenticationMode,
    notificationStateGeneration: Long,
    requestNotificationPermission: () -> Unit,
    openPlanInitially: Boolean,
    returnToCaller: Boolean,
    onPlanOpened: () -> Unit,
    subscriptionViewModel: SubscriptionViewModel,
    auditViewModel: AuditViewModel,
    viewModel: SettingsViewModel,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.OVERVIEW) }
    val configuration by viewModel.configuration.collectAsStateWithLifecycle()
    val pushState by viewModel.pushRegistrationState.collectAsStateWithLifecycle()
    val protection by viewModel.vaultProtection.collectAsStateWithLifecycle()
    val factoryReset by viewModel.factoryReset.collectAsStateWithLifecycle()
    val subscription by subscriptionViewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(openPlanInitially) {
        if (openPlanInitially) {
            page = SettingsPage.SUBSCRIPTION
            onPlanOpened()
        }
    }
    LaunchedEffect(returnToCaller, subscription.aiReviewAccess) {
        if (returnToCaller && subscription.aiReviewAccess == AiReviewAccess.ACTIVE) {
            onClose()
        }
    }

    fun back() {
        when (page) {
            SettingsPage.OVERVIEW -> onClose()
            SettingsPage.FACTORY_RESET ->
                when (factoryReset) {
                    FactoryResetUiState.Working -> Unit
                    FactoryResetUiState.ConfirmLocalClear -> {
                        viewModel.cancelLocalClear()
                        page = SettingsPage.OVERVIEW
                    }
                    FactoryResetUiState.Idle,
                    FactoryResetUiState.ClearFailed -> page = SettingsPage.OVERVIEW
                }
            SettingsPage.SUBSCRIPTION -> {
                subscriptionViewModel.dismissNotice()
                if (returnToCaller) onClose() else page = SettingsPage.OVERVIEW
            }
            else -> page = SettingsPage.OVERVIEW
        }
    }
    BackHandler(onBack = ::back)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.navigationBars,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val modifier =
                if (page == SettingsPage.AUDIT) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxHeight().widthIn(max = 720.dp).align(Alignment.TopCenter)
                }
            when (page) {
                SettingsPage.OVERVIEW ->
                    SettingsOverview(
                        authenticationMode = authenticationMode,
                        protection = protection,
                        notificationStateGeneration = notificationStateGeneration,
                        pushState = pushState,
                        subscription = subscription,
                        onBack = onClose,
                        onOpen = { page = it },
                        modifier = modifier,
                    )
                SettingsPage.SECURITY_BACKUP ->
                    SecuritySettings(
                        protection = protection,
                        authenticationMode = authenticationMode,
                        onAuthenticationModeChange = viewModel::changeAuthenticationMode,
                        onBack = ::back,
                        modifier = modifier,
                    )
                SettingsPage.NOTIFICATIONS ->
                    NotificationsSettings(
                        pushState = pushState,
                        refreshGeneration = notificationStateGeneration,
                        requestNotificationPermission = requestNotificationPermission,
                        onBack = ::back,
                        modifier = modifier,
                    )
                SettingsPage.SUBSCRIPTION ->
                    SubscriptionAndBillingScreen(
                        state = subscription,
                        onBack = ::back,
                        onRefresh = {
                            subscriptionViewModel.dismissNotice()
                            subscriptionViewModel.refresh()
                        },
                        onSubscribe = { offerId ->
                            activity?.let { subscriptionViewModel.subscribe(it, offerId) }
                        },
                        onOpenSecrets = {
                            subscriptionViewModel.dismissNotice()
                            onOpenSecrets()
                        },
                        onManageSubscription = { productId ->
                            val uri =
                                "https://play.google.com/store/account/subscriptions"
                                    .toUri()
                                    .buildUpon()
                                    .apply {
                                        if (productId != null) {
                                            appendQueryParameter("sku", productId)
                                            appendQueryParameter("package", context.packageName)
                                        }
                                    }
                                    .build()
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            }
                                .onFailure {
                                    scope.launch {
                                        snackbar.showSnackbar("Google Play could not be opened")
                                    }
                                }
                        },
                        modifier = modifier,
                    )
                SettingsPage.AUDIT ->
                    AuditSettings(
                        viewModel = auditViewModel,
                        onBack = ::back,
                        report = { message -> scope.launch { snackbar.showSnackbar(message) } },
                        modifier = modifier,
                    )
                SettingsPage.FACTORY_RESET ->
                    FactoryResetSettings(
                        onBack = ::back,
                        state = factoryReset,
                        startReset = viewModel::startFactoryReset,
                        confirmLocalClear = viewModel::confirmLocalClear,
                        cancelLocalClear = viewModel::cancelLocalClear,
                        consumeClearFailure = viewModel::consumeClearFailure,
                        report = { message -> scope.launch { snackbar.showSnackbar(message) } },
                        modifier = modifier,
                    )
                SettingsPage.ABOUT ->
                    AboutSettings(
                        identity = configuration?.active,
                        onBack = ::back,
                        report = { message -> scope.launch { snackbar.showSnackbar(message) } },
                        modifier = modifier,
                    )
            }
        }
    }
}
