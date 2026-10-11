package com.tripguard.app.ui.screens.settings

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.WifiTetheringOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tripguard.app.BuildConfig
import com.tripguard.app.domain.repository.BackendStatus
import com.tripguard.app.ui.base.TripGuardViewModel
import com.tripguard.app.ui.components.InfoBanner
import com.tripguard.app.ui.components.LabeledTextField
import com.tripguard.app.ui.components.PrimaryButton
import com.tripguard.app.ui.components.SecondaryButton
import com.tripguard.app.ui.components.TripGuardCard
import com.tripguard.app.ui.components.TripGuardTopBar
import com.tripguard.app.ui.theme.Navy
import com.tripguard.app.ui.theme.SoftAmberBg
import com.tripguard.app.ui.theme.SoftAmberFg
import com.tripguard.app.ui.theme.SoftBlueBg
import com.tripguard.app.ui.theme.SoftBlueFg
import com.tripguard.app.ui.theme.SoftEmeraldBg
import com.tripguard.app.ui.theme.SoftEmeraldFg
import com.tripguard.app.ui.theme.SoftRedBg
import com.tripguard.app.ui.theme.SoftRedFg
import com.tripguard.app.ui.theme.Spacing
import com.tripguard.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class SettingsUiState(
    val baseUrlInput: String = "",
    val savedBaseUrl: String = "",
    val defaultBaseUrl: String = BuildConfig.API_BASE_URL,
    val backendStatus: BackendStatus = BackendStatus.UNKNOWN,
    val isSaving: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

class SettingsViewModel(application: Application) : TripGuardViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState(defaultBaseUrl = BuildConfig.API_BASE_URL))
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(container.settings.baseUrl, container.settings.backendStatus) { url, status ->
                url to status
            }.collect { (url, status) ->
                _uiState.value = _uiState.value.copy(
                    savedBaseUrl = url,
                    baseUrlInput = if (_uiState.value.baseUrlInput.isBlank()) url else _uiState.value.baseUrlInput,
                    backendStatus = status,
                )
            }
        }
    }

    fun onBaseUrlChange(value: String) {
        _uiState.value = _uiState.value.copy(baseUrlInput = value, message = null)
    }

    fun save() {
        val value = _uiState.value.baseUrlInput.trim()
        if (value.isBlank() || !value.startsWith("http")) {
            _uiState.value = _uiState.value.copy(
                message = "Enter a full URL beginning with http:// or https://",
                messageIsError = true,
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, message = null)
            container.settings.setBaseUrl(value)
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                message = "Backend address saved.",
                messageIsError = false,
            )
        }
    }

    fun resetToDefault() {
        viewModelScope.launch {
            container.settings.setBaseUrl(_uiState.value.defaultBaseUrl)
            _uiState.value = _uiState.value.copy(
                baseUrlInput = _uiState.value.defaultBaseUrl,
                message = "Reverted to the build default.",
                messageIsError = false,
            )
        }
    }

    fun checkConnection() {
        viewModelScope.launch {
            val status = container.settings.pingBackend()
            _uiState.value = _uiState.value.copy(
                message = when (status) {
                    BackendStatus.REACHABLE -> "Backend reachable."
                    BackendStatus.UNREACHABLE -> "Couldn't reach the backend. Offline mode stays active."
                    else -> null
                },
                messageIsError = status == BackendStatus.UNREACHABLE,
            )
        }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TripGuardTopBar(title = "Settings", onBack = onBack)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.screenHorizontal,
                end = Spacing.screenHorizontal,
                top = Spacing.md,
                bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            item { BackendStatusCard(status = state.backendStatus) }

            item {
                TripGuardCard {
                    Text("Backend address", style = MaterialTheme.typography.titleMedium, color = Navy)
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = "TripGuard works offline using data saved on this device. When a backend is " +
                            "reachable it syncs applications and document processing for your agency.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    LabeledTextField(
                        label = "Base URL",
                        value = state.baseUrlInput,
                        onValueChange = viewModel::onBaseUrlChange,
                        placeholder = "http://10.0.2.2:8000/",
                    )
                    state.message?.let { message ->
                        Spacer(Modifier.height(Spacing.sm))
                        InfoBanner(
                            text = message,
                            containerColor = if (state.messageIsError) SoftRedBg else SoftBlueBg,
                            contentColor = if (state.messageIsError) SoftRedFg else SoftBlueFg,
                        )
                    }
                    Spacer(Modifier.height(Spacing.md))
                    PrimaryButton(
                        text = "Save address",
                        onClick = viewModel::save,
                        loading = state.isSaving,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    SecondaryButton(
                        text = "Reset to build default",
                        onClick = viewModel::resetToDefault,
                    )
                }
            }

            item {
                PrimaryButton(
                    text = "Check backend connection",
                    onClick = viewModel::checkConnection,
                    icon = Icons.Outlined.Refresh,
                )
            }

            item {
                InfoBanner(
                    text = "Document processing and agency review require the backend. Until then, uploaded " +
                        "files are stored locally and clearly marked as pending.",
                    containerColor = SoftAmberBg,
                    contentColor = SoftAmberFg,
                    icon = Icons.Outlined.Info,
                )
            }
        }
    }
}

@Composable
private fun BackendStatusCard(status: BackendStatus) {
    val (label, bg, fg, icon) = when (status) {
        BackendStatus.REACHABLE ->
            StatusVisual("Backend reachable", SoftEmeraldBg, SoftEmeraldFg, Icons.Outlined.CheckCircle)

        BackendStatus.UNREACHABLE ->
            StatusVisual("Backend unreachable", SoftRedBg, SoftRedFg, Icons.Outlined.CloudOff)

        BackendStatus.CHECKING ->
            StatusVisual("Checking…", SoftBlueBg, SoftBlueFg, Icons.Outlined.Refresh)

        BackendStatus.UNKNOWN ->
            StatusVisual("Not checked yet", SoftBlueBg, SoftBlueFg, Icons.Outlined.WifiTetheringOff)
    }

    TripGuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(bg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = Navy, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Offline-first: local data always available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
    }
}

private data class StatusVisual(val label: String, val bg: Color, val fg: Color, val icon: androidx.compose.ui.graphics.vector.ImageVector)
