package com.tripguard.app.data.repository

import com.tripguard.app.data.local.LocalStore
import com.tripguard.app.data.remote.TripGuardApi
import com.tripguard.app.domain.repository.BackendStatus
import com.tripguard.app.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsRepositoryImpl(
    private val store: LocalStore,
    private val api: TripGuardApi,
    private val defaultBaseUrl: String,
    private val scope: CoroutineScope,
) : SettingsRepository {

    private val _baseUrl = MutableStateFlow(
        store.state.value.baseUrlOverride?.takeIf { it.isNotBlank() } ?: defaultBaseUrl,
    )
    override val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _backendStatus = MutableStateFlow(BackendStatus.UNKNOWN)
    override val backendStatus: StateFlow<BackendStatus> = _backendStatus.asStateFlow()

    init {
        scope.launch { pingBackend() }
    }

    override suspend fun setBaseUrl(url: String) {
        val normalized = normalize(url)
        store.update { it.copy(baseUrlOverride = normalized) }
        _baseUrl.value = normalized
        pingBackend()
    }

    override suspend fun pingBackend(): BackendStatus {
        _backendStatus.value = BackendStatus.CHECKING
        val reachable = api.ping()
        val status = if (reachable) BackendStatus.REACHABLE else BackendStatus.UNREACHABLE
        _backendStatus.value = status
        return status
    }

    private fun normalize(raw: String): String {
        val trimmed = raw.trim().ifBlank { defaultBaseUrl }
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }
}
