package dev.agentknock.ui.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceCredentialResultTest {
    @Test
    fun recreatedHostCompletesCredentialAuthenticationBeforeResuming() = runTest {
        withContext(Dispatchers.Main) {
            val coordinator = DeviceAuthenticationCoordinator()
            val result =
                async(start = CoroutineStart.UNDISPATCHED) { coordinator.authenticate("Unlock") }
            val original = CredentialHost(coordinator)
            original.start()
            original.launch(checkNotNull(coordinator.request.value))
            val requestCode = original.requestCode

            val recreated = CredentialHost(coordinator, original.destroyForRecreation())
            recreated.registry.dispatchResult(requestCode, Activity.RESULT_OK, null)
            assertFalse(result.isCompleted)

            // AndroidX delivers a restored pending result at ON_START. MainActivity only collects
            // new
            // authentication requests at RESUMED, so the callback must already know the launched
            // ID.
            recreated.start()

            assertEquals(Lifecycle.State.STARTED, recreated.lifecycle.currentState)
            assertEquals(null, coordinator.request.value)
            assertEquals(DeviceAuthenticationResult.Success, result.await())
            recreated.destroyForRecreation()
        }
    }

    @Test
    fun recreatedHostDoesNotUseAnAbandonedCredentialResultForAnotherAction() = runTest {
        withContext(Dispatchers.Main) {
            val coordinator = DeviceAuthenticationCoordinator()
            val abandoned =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.authenticate("Reveal secret")
                }
            val original = CredentialHost(coordinator)
            original.start()
            original.launch(checkNotNull(coordinator.request.value))
            val requestCode = original.requestCode
            val savedState = original.destroyForRecreation()
            abandoned.cancelAndJoin()
            val replacement =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coordinator.authenticate("Copy secret")
                }
            val replacementRequest = checkNotNull(coordinator.request.value)

            val recreated = CredentialHost(coordinator, savedState)
            recreated.registry.dispatchResult(requestCode, Activity.RESULT_OK, null)
            recreated.start()

            assertFalse(replacement.isCompleted)
            assertEquals(replacementRequest, coordinator.request.value)

            recreated.launch(replacementRequest)
            recreated.registry.dispatchResult(recreated.requestCode, Activity.RESULT_OK, null)
            assertEquals(DeviceAuthenticationResult.Success, replacement.await())
            recreated.destroyForRecreation()
        }
    }

    /**
     * Uses AndroidX's real saved-state/result delivery; only the external credential UI is fake.
     */
    private class CredentialHost(
        private val coordinator: DeviceAuthenticationCoordinator,
        savedState: Bundle? = null,
    ) : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
        var requestCode = 0
            private set

        val registry =
            object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    this@CredentialHost.requestCode = requestCode
                }
            }

        init {
            registry.onRestoreInstanceState(savedState)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        private val launcher =
            registry.register(
                "deviceCredential",
                this,
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                coordinator.completeDeviceCredential(result.resultCode)
            }

        fun start() {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        fun launch(request: DeviceAuthenticationRequest) {
            assertTrue(coordinator.claimForLaunch(request))
            assertTrue(coordinator.claimDeviceCredential(request))
            launcher.launch(Intent())
        }

        fun destroyForRecreation(): Bundle {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            return Bundle().also { state ->
                registry.onSaveInstanceState(state)
                lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
        }
    }
}
