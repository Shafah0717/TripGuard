package com.tripguard.app.ui.screens.visa

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.VisaType
import com.tripguard.app.ui.base.TripGuardViewModel
import com.tripguard.app.ui.components.ErrorStateView
import com.tripguard.app.ui.components.InfoBanner
import com.tripguard.app.ui.components.LoadingState
import com.tripguard.app.ui.components.PrimaryButton
import com.tripguard.app.ui.components.StatusChip
import com.tripguard.app.ui.components.TripGuardCard
import com.tripguard.app.ui.components.TripGuardTopBar
import com.tripguard.app.ui.theme.BorderColor
import com.tripguard.app.ui.theme.Emerald
import com.tripguard.app.ui.theme.EmeraldContainer
import com.tripguard.app.ui.theme.EmeraldDark
import com.tripguard.app.ui.theme.Navy
import com.tripguard.app.ui.theme.SoftAmberBg
import com.tripguard.app.ui.theme.SoftAmberFg
import com.tripguard.app.ui.theme.SoftSlateBg
import com.tripguard.app.ui.theme.SoftSlateFg
import com.tripguard.app.ui.theme.Spacing
import com.tripguard.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VisaSelectionUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val visaTypes: List<VisaType> = emptyList(),
    val selectedId: String? = null,
    val isCreating: Boolean = false,
    val createError: String? = null,
    val createdApplicationId: String? = null,
)

class VisaSelectionViewModel(application: Application) : TripGuardViewModel(application) {

    private val _uiState = MutableStateFlow(VisaSelectionUiState())
    val uiState: StateFlow<VisaSelectionUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            when (val result = container.catalog.load(force)) {
                is AppResult.Success -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    visaTypes = result.data.visaTypes,
                    error = null,
                )

                is AppResult.Failure -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result.error.message,
                )
            }
        }
    }

    fun select(id: String) {
        _uiState.value = _uiState.value.copy(selectedId = id, createError = null)
    }

    fun createApplication() {
        val selected = _uiState.value.visaTypes.firstOrNull { it.id == _uiState.value.selectedId } ?: return
        if (_uiState.value.isCreating) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreating = true, createError = null)
            when (val result = container.applications.createApplication(selected)) {
                is AppResult.Success -> _uiState.value = _uiState.value.copy(
                    isCreating = false,
                    createdApplicationId = result.data.id,
                )

                is AppResult.Failure -> _uiState.value = _uiState.value.copy(
                    isCreating = false,
                    createError = result.error.message,
                )
            }
        }
    }
}

@Composable
fun VisaSelectionScreen(
    onBack: () -> Unit,
    onApplicationCreated: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VisaSelectionViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.createdApplicationId) {
        state.createdApplicationId?.let(onApplicationCreated)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TripGuardTopBar(title = "Choose a visa", onBack = onBack)

        when {
            state.isLoading -> LoadingState(message = "Loading visa categories…")
            state.error != null -> ErrorStateView(
                message = state.error ?: "Could not load visa categories.",
                onRetry = { viewModel.load(force = true) },
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.screenHorizontal,
                    end = Spacing.screenHorizontal,
                    top = Spacing.md,
                    bottom = Spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item {
                    InfoBanner(
                        text = "These categories are shown for Indian ordinary passport holders travelling for tourism. " +
                            "Eligibility is confirmed by the authorised issuing channel, not by TripGuard.",
                        containerColor = SoftSlateBg,
                        contentColor = SoftSlateFg,
                        icon = Icons.Outlined.Info,
                    )
                }

                items(state.visaTypes, key = { it.id }) { visa ->
                    VisaOptionCard(
                        visa = visa,
                        selected = visa.id == state.selectedId,
                        onClick = { viewModel.select(visa.id) },
                    )
                }

                item {
                    state.createError?.let {
                        InfoBanner(
                            text = it,
                            containerColor = com.tripguard.app.ui.theme.SoftRedBg,
                            contentColor = com.tripguard.app.ui.theme.SoftRedFg,
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(Spacing.xs))
                    PrimaryButton(
                        text = "Continue",
                        onClick = { viewModel.createApplication() },
                        enabled = state.selectedId != null,
                        loading = state.isCreating,
                    )
                }
            }
        }
    }
}

@Composable
private fun VisaOptionCard(visa: VisaType, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) BorderStroke(2.dp, Emerald) else BorderStroke(1.dp, BorderColor)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) EmeraldContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surface,
            )
            .border(border, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(Spacing.md),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = visa.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = Navy,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = visa.officialName,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (selected) Emerald else Color.Transparent)
                        .then(if (selected) Modifier else Modifier.background(SoftSlateBg, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Icon(Icons.Outlined.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(Modifier.height(Spacing.sm))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                MetaPill(Icons.Outlined.CalendarMonth, visa.permittedStay.display)
                MetaPill(Icons.AutoMirrored.Outlined.Login, visa.entryTypeLabel)
                visa.processingTime?.display?.let { MetaPill(Icons.Outlined.Schedule, it) }
            }

            Spacer(Modifier.height(Spacing.sm))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${visa.checklist.count { it.isActionable }} documents to prepare",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.width(Spacing.xs))
                if (!visa.isVerifiedEligibility) {
                    StatusChip(
                        text = "Confirm eligibility",
                        background = SoftAmberBg,
                        foreground = SoftAmberFg,
                    )
                }
            }

            if (visa.fees.firstOrNull()?.display != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "Official fee reference: ${visa.fees.first().display} · ${visa.fees.first().description}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun MetaPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = EmeraldDark, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = Navy, fontWeight = FontWeight.Medium)
    }
}
