package dev.agentknock.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.agentknock.subscription.AiReviewAccess
import dev.agentknock.subscription.GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_ID
import dev.agentknock.subscription.PlaySubscriptionOffer
import dev.agentknock.subscription.PlaySubscriptionOfferId
import dev.agentknock.ui.theme.AgentknockTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SubscriptionScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun anUnavailableStatusDoesNotClaimTheSubscriptionNeedsAttentionOrOfferAnotherPurchase() {
        compose.setContent {
            AgentknockTheme {
                SubscriptionAndBillingScreen(
                    state =
                        SubscriptionUiState(
                            access = AiReviewAccess.UNAVAILABLE,
                            googlePlayPurchase = GooglePlayPurchaseState.PURCHASED,
                        ),
                    onBack = {},
                    onRefresh = {},
                    onSubscribe = {},
                    onManageSubscription = {},
                    onOpenSecrets = {},
                )
            }
        }
        compose.onNodeWithText("Status unavailable").assertIsDisplayed()
        compose.onNodeWithText("Subscription needs attention").assertDoesNotExist()
        compose.onNodeWithText("Subscribe").assertDoesNotExist()
    }

    @Test
    fun purchaseActivationShowsProgressUntilAccessIsConfirmed() {
        val state =
            mutableStateOf(
                SubscriptionUiState(
                    access = AiReviewAccess.INACTIVE,
                    googlePlayPurchase = GooglePlayPurchaseState.PURCHASED,
                    refreshing = true,
                )
            )
        compose.setContent {
            AgentknockTheme {
                SubscriptionAndBillingScreen(
                    state = state.value,
                    onBack = {},
                    onRefresh = {},
                    onSubscribe = {},
                    onManageSubscription = {},
                    onOpenSecrets = {},
                )
            }
        }
        compose.onNodeWithText("Activating").assertIsDisplayed()
        compose.onNodeWithText("Activating AI review…").assertIsDisplayed()
        compose.onNodeWithText("Subscription needs attention").assertDoesNotExist()
        compose.onNodeWithText("Not active").assertDoesNotExist()

        compose.runOnIdle {
            state.value = state.value.copy(access = AiReviewAccess.ACTIVE, refreshing = false)
        }
        compose.onNodeWithText("Active").assertIsDisplayed()
        compose.onNodeWithText("Activating AI review…").assertDoesNotExist()
        compose.onNodeWithText("Subscription needs attention").assertDoesNotExist()
    }

    @Test
    fun purchaseWithoutAccessShowsAttentionOnlyAfterCheckingFinishes() {
        val state =
            mutableStateOf(
                SubscriptionUiState(
                    access = AiReviewAccess.UNAVAILABLE,
                    googlePlayPurchase = GooglePlayPurchaseState.PURCHASED,
                    refreshing = true,
                )
            )
        compose.setContent {
            AgentknockTheme {
                SubscriptionAndBillingScreen(
                    state = state.value,
                    onBack = {},
                    onRefresh = {},
                    onSubscribe = {},
                    onManageSubscription = {},
                    onOpenSecrets = {},
                )
            }
        }
        compose.onNodeWithText("Activating AI review…").assertIsDisplayed()
        compose.onNodeWithText("Subscription needs attention").assertDoesNotExist()
        compose.onNodeWithText("Status unavailable").assertDoesNotExist()

        compose.runOnIdle {
            state.value = state.value.copy(access = AiReviewAccess.INACTIVE, refreshing = false)
        }
        compose.onNodeWithText("Subscription needs attention").assertIsDisplayed()
        compose.onNodeWithText("Activating AI review…").assertDoesNotExist()
    }

    @Test
    fun activeAccessOffersSecretSettingsWithoutInventingAPlayPurchase() {
        var opened = 0
        compose.setContent {
            AgentknockTheme {
                SubscriptionAndBillingScreen(
                    state = SubscriptionUiState(access = AiReviewAccess.ACTIVE),
                    onBack = {},
                    onRefresh = {},
                    onSubscribe = {},
                    onManageSubscription = {},
                    onOpenSecrets = { opened++ },
                )
            }
        }
        compose.onNodeWithText("Choose secrets for AI review").performClick()
        assertEquals(1, opened)
        compose.onNodeWithText("Manage in Google Play").assertDoesNotExist()
        compose.onNodeWithText("Subscribe").assertDoesNotExist()
    }

    @Test
    fun freePlanShowsPriceTermsAndStartsTheSelectedOffer() {
        val id = PlaySubscriptionOfferId(GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_ID, "monthly", null)
        var selected: PlaySubscriptionOfferId? = null
        compose.setContent {
            AgentknockTheme {
                SubscriptionAndBillingScreen(
                    state =
                        SubscriptionUiState(
                            access = AiReviewAccess.INACTIVE,
                            playStore = PlayStoreAvailability.AVAILABLE,
                            offers =
                                listOf(
                                    PlaySubscriptionOffer(
                                        id,
                                        "€4.99/month",
                                        "Renews automatically until canceled. Manage or cancel in Google Play.",
                                        true,
                                        null,
                                    )
                                ),
                        ),
                    onBack = {},
                    onRefresh = {},
                    onSubscribe = { selected = it },
                    onManageSubscription = {},
                    onOpenSecrets = {},
                )
            }
        }
        compose.onNodeWithText("€4.99/month").assertIsDisplayed()
        compose
            .onNodeWithText("Renews automatically until canceled. Manage or cancel in Google Play.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Subscribe").performScrollTo().performClick()
        assertEquals(id, selected)
    }

    @Test
    fun eligibleTrialShowsDurationRenewalAndCancellationAndStartsTheTrial() {
        val id =
            PlaySubscriptionOfferId(
                GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_ID,
                "monthly",
                "free-trial-14-days",
            )
        val terms =
            "14 days free, then €4.99/month. " +
                "You will be charged automatically unless you cancel before the trial ends. " +
                "Renews automatically until canceled. Manage or cancel in Google Play."
        var selected: PlaySubscriptionOfferId? = null
        compose.setContent {
            AgentknockTheme {
                SubscriptionAndBillingScreen(
                    state =
                        SubscriptionUiState(
                            access = AiReviewAccess.INACTIVE,
                            playStore = PlayStoreAvailability.AVAILABLE,
                            offers =
                                listOf(
                                    PlaySubscriptionOffer(id, "€4.99/month", terms, true, "14 days")
                                ),
                        ),
                    onBack = {},
                    onRefresh = {},
                    onSubscribe = { selected = it },
                    onManageSubscription = {},
                    onOpenSecrets = {},
                )
            }
        }
        compose.onNodeWithText("14 days free").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("€4.99/month").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(terms).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Subscribe").assertDoesNotExist()
        compose.onNodeWithText("Start free trial").performScrollTo().performClick()
        assertEquals(id, selected)
    }

    @Test
    fun activePlaySubscriptionProvidesCancellationManagement() {
        var managed: String? = null
        compose.setContent {
            AgentknockTheme {
                SubscriptionAndBillingScreen(
                    state =
                        SubscriptionUiState(
                            access = AiReviewAccess.ACTIVE,
                            googlePlayPurchase = GooglePlayPurchaseState.PURCHASED,
                            googlePlayProductId = GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_ID,
                        ),
                    onBack = {},
                    onRefresh = {},
                    onSubscribe = {},
                    onManageSubscription = { managed = it },
                    onOpenSecrets = {},
                )
            }
        }
        compose.onNodeWithText("Manage in Google Play").performScrollTo().performClick()
        assertEquals(GOOGLE_PLAY_SUBSCRIPTION_PRODUCT_ID, managed)
        compose.onNodeWithText("Start free trial").assertDoesNotExist()
    }
}
