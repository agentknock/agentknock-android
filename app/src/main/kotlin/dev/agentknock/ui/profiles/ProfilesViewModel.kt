package dev.agentknock.ui.profiles

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.agentknock.AgentKnockApplication
import dev.agentknock.storage.profile.CreateEnvironmentVariableResult
import dev.agentknock.storage.profile.CreateProfileResult
import dev.agentknock.storage.profile.EnvironmentVariableValue
import dev.agentknock.storage.profile.ProfileDetails
import dev.agentknock.storage.profile.ProfileSummary
import dev.agentknock.storage.profile.SaveEnvironmentVariableResult
import dev.agentknock.storage.profile.SaveProfileResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi

@OptIn(ExperimentalCoroutinesApi::class)
internal class ProfilesViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as AgentKnockApplication).container.profiles
    private val selectedProfileId = MutableStateFlow<String?>(null)

    val selection: StateFlow<String?> = selectedProfileId.asStateFlow()

    val profiles: StateFlow<List<ProfileSummary>> = repository.observeProfiles().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    val selectedProfile: StateFlow<ProfileDetails?> = selectedProfileId
        .flatMapLatest { id -> id?.let(repository::observeProfile) ?: flowOf(null) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    fun selectProfile(id: String?) {
        selectedProfileId.value = id
    }

    suspend fun createProfile(name: String, description: String): CreateProfileResult =
        repository.createProfile(name, description)

    suspend fun saveProfile(
        id: String,
        name: String,
        description: String,
    ): SaveProfileResult = repository.saveProfile(id, name, description)

    suspend fun deleteProfile(id: String): Boolean {
        val deleted = repository.deleteProfile(id)
        if (deleted && selectedProfileId.value == id) selectedProfileId.value = null
        return deleted
    }

    suspend fun createEnvironmentVariable(
        profileId: String,
        name: String,
        value: String,
        sensitive: Boolean,
        notes: String,
    ): CreateEnvironmentVariableResult = repository.createEnvironmentVariable(
        profileId = profileId,
        name = name,
        value = value,
        sensitive = sensitive,
        notes = notes,
    )

    suspend fun saveEnvironmentVariable(
        id: String,
        name: String,
        sensitive: Boolean,
        notes: String,
        replacementValue: String?,
    ): SaveEnvironmentVariableResult = repository.saveEnvironmentVariable(
        id = id,
        name = name,
        sensitive = sensitive,
        notes = notes,
        replacementValue = replacementValue,
    )

    suspend fun deleteEnvironmentVariable(id: String): Boolean =
        repository.deleteEnvironmentVariable(id)

    suspend fun readEnvironmentVariableValue(id: String): EnvironmentVariableValue =
        repository.readEnvironmentVariableValue(id)
}
