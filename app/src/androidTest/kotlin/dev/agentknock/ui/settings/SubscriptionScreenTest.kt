package dev.agentknock.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.agentknock.subscription.GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_ID
import dev.agentknock.subscription.PlaySubscriptionOffer
import dev.agentknock.subscription.PlaySubscriptionOfferId
import dev.agentknock.ui.theme.AgentknockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SubscriptionScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun purchaseActivationShowsProgressUntilAccessIsConfirmed() {
        val state = mutableStateOf(
            SubscriptionUiState(
                access = SubscriptionAccess.FREE,
                googlePlayPurchase = GooglePlayPurchaseState.PURCHASED,
                refreshing = true,
            ),
        )
        compose.setContent {
            AgentknockTheme {
                SubscriptionAndBillingScreen(
                    state = state.value,
                    onBack = {}, onRefresh = {}, onSubscribe = {}, onManageSubscription = {},
                    onOpenSecrets = {},
                )
            }
        }
        compose.onNodeWithText("Activating").assertIsDisplayed()
        compose.onNodeWithText("Activating AI review…").assertIsDisplayed()
        compose.onNodeWithText("Subscription needs attention").assertDoesNotExist()
        compose.onNodeWithText("Not active").assertDoesNotExist()

        compose.runOnIdle {
            state.value = state.value.copy(access = SubscriptionAccess.ACTIVE, refreshing = false)
        }
        compose.onNodeWithText("Active").assertIsDisplayed()
        compose.onNodeWithText("Activating AI review…").assertDoesNotExist()
        compose.onNodeWithText("Subscription needs attention").assertDoesNotExist()
    }

    @Test fun purchaseWithoutAccessShowsAttentionOnlyAfterCheckingFinishes() {
        val state = mutableStateOf(
            SubscriptionUiState(
                access = SubscriptionAccess.UNAVAILABLE,
                googlePlayPurchase = GooglePlayPurchaseState.PURCHASED,
                refreshing = true,
            ),
        )
        compose.setContent {
            AgentknockTheme {
                SubscriptionAndBillingScreen(
                    state = state.value,
                    onBack = {}, onRefresh = {}, onSubscribe = {}, onManageSubscription = {},
                    onOpenSecrets = {},
                )
            }
        }
        compose.onNodeWithText("Activating AI review…").assertIsDisplayed()
        compose.onNodeWithText("Subscription needs attention").assertDoesNotExist()
        compose.onNodeWithText("Status unavailable").assertDoesNotExist()

        compose.runOnIdle { state.value = state.value.copy(refreshing = false) }
        compose.onNodeWithText("Subscription needs attention").assertIsDisplayed()
        compose.onNodeWithText("Activating AI review…").assertDoesNotExist()
    }

    @Test fun activeAccessOffersSecretSettingsWithoutInventingAPlayPurchase() {
        var opened = 0
        compose.setContent {
            AgentknockTheme {
                SubscriptionAndBillingScreen(
                    state = SubscriptionUiState(access = SubscriptionAccess.ACTIVE),
                    onBack = {}, onRefresh = {}, onSubscribe = {}, onManageSubscription = {},
                    onOpenSecrets = { opened++ },
                )
            }
        }
        compose.onNodeWithText("Choose secrets for AI review").performClick()
        assertEquals(1, opened)
        compose.onNodeWithText("Manage in Google Play").assertDoesNotExist()
        compose.onNodeWithText("Subscribe").assertDoesNotExist()
    }

    @Test fun freePlanShowsPriceTermsAndStartsTheSelectedOffer() {
        val id = PlaySubscriptionOfferId(GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_ID, "monthly", null)
        var selected: PlaySubscriptionOfferId? = null
        compose.setContent {
            AgentknockTheme {
                SubscriptionAndBillingScreen(
                    state = SubscriptionUiState(access = SubscriptionAccess.FREE,
                        playStore = PlayStoreAvailability.AVAILABLE,
                        offers = listOf(PlaySubscriptionOffer(id, "€4.99/month",
                            "Renews automatically until canceled. Manage or cancel in Google Play.", true))),
                    onBack = {}, onRefresh = {}, onSubscribe = { selected = it },
                    onManageSubscription = {}, onOpenSecrets = {},
                )
            }
        }
        compose.onNodeWithText("€4.99/month").assertIsDisplayed()
        compose.onNodeWithText("Renews automatically until canceled. Manage or cancel in Google Play.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Subscribe").performScrollTo().performClick()
        assertEquals(id, selected)
    }
}
