package dev.agentknock.ui.auth

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticationPolicyTest {
    @Test
    fun `one authentication request owns the typed result until completion`() = runTest {
        val attempt = DeviceAuthenticationCoordinator()
        val first = async(start = CoroutineStart.UNDISPATCHED) { attempt.authenticate("First") }
        assertTrue(attempt.request.value != null)
        assertEquals("First", attempt.request.value?.title)
        assertEquals(
            DeviceAuthenticationResult.Error("Another authentication is already in progress"),
            attempt.authenticate("Second"),
        )

        attempt.succeed(checkNotNull(attempt.request.value).id)
        assertEquals(DeviceAuthenticationResult.Success, first.await())
        assertEquals(null, attempt.request.value)
        val third = async(start = CoroutineStart.UNDISPATCHED) { attempt.authenticate("Third") }
        attempt.fail(checkNotNull(attempt.request.value).id, "cancelled")
        assertEquals(DeviceAuthenticationResult.Error("cancelled"), third.await())
        assertEquals(null, attempt.request.value)
    }

    @Test
    fun `authentication launches once and duplicate callbacks are harmless`() = runTest {
        val coordinator = DeviceAuthenticationCoordinator()
        val result =
            async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.authenticate("Reveal secret")
            }
        val request = checkNotNull(coordinator.request.value)
        assertTrue(coordinator.claimForLaunch(request))
        assertFalse(coordinator.claimForLaunch(request))

        coordinator.succeed(request.id)
        coordinator.succeed(request.id)

        assertEquals(DeviceAuthenticationResult.Success, result.await())
        assertEquals(null, coordinator.request.value)
    }

    @Test
    fun `late callback cannot complete a newer authentication`() = runTest {
        val coordinator = DeviceAuthenticationCoordinator()
        val abandoned =
            async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.authenticate("Reveal secret")
            }
        val oldRequest = checkNotNull(coordinator.request.value)
        abandoned.cancelAndJoin()
        val replacement =
            async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.authenticate("Copy secret")
            }
        val newRequest = checkNotNull(coordinator.request.value)

        coordinator.succeed(oldRequest.id)

        assertTrue(coordinator.request.value != null)
        assertFalse(replacement.isCompleted)
        coordinator.succeed(newRequest.id)
        assertEquals(DeviceAuthenticationResult.Success, replacement.await())
    }

    @Test
    fun `cancelling the owner releases authentication for a new host`() = runTest {
        val coordinator = DeviceAuthenticationCoordinator()
        val abandoned =
            async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.authenticate("Reveal secret")
            }
        val abandonedRequest = checkNotNull(coordinator.request.value)
        assertTrue(coordinator.claimForLaunch(abandonedRequest))

        abandoned.cancelAndJoin()

        assertEquals(null, coordinator.request.value)
        val replacement =
            async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.authenticate("Copy secret")
            }
        coordinator.succeed(checkNotNull(coordinator.request.value).id)
        assertEquals(DeviceAuthenticationResult.Success, replacement.await())
    }

    @Test
    fun `device lock mode never adds an Agentknock gate`() {
        assertTrue(DeviceAuthenticationMode.DEVICE_LOCK.contentAvailable(authenticated = false))
        assertTrue(
            DeviceAuthenticationMode.DEVICE_LOCK.protectedActionAvailable(authenticated = false)
        )
    }

    @Test
    fun `sensitive values mode leaves content visible but gates protected actions`() {
        assertTrue(
            DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING.contentAvailable(
                authenticated = false
            )
        )
        assertFalse(
            DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING.protectedActionAvailable(
                authenticated = false
            )
        )
        assertTrue(
            DeviceAuthenticationMode.SENSITIVE_VALUES_AND_PAIRING.protectedActionAvailable(
                authenticated = true
            )
        )
    }

    @Test
    fun `app lock gates content until the session is authenticated`() {
        assertFalse(DeviceAuthenticationMode.APP_LOCK.contentAvailable(authenticated = false))
        assertTrue(DeviceAuthenticationMode.APP_LOCK.contentAvailable(authenticated = true))
        assertFalse(
            DeviceAuthenticationMode.APP_LOCK.protectedActionAvailable(authenticated = false)
        )
        assertTrue(DeviceAuthenticationMode.APP_LOCK.protectedActionAvailable(authenticated = true))
    }
}
