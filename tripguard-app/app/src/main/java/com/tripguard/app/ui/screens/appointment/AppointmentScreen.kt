package com.tripguard.app.ui.screens.appointment

import android.app.Application
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.AppointmentInvitation
import com.tripguard.app.domain.model.AppointmentResponse
import com.tripguard.app.domain.model.AppointmentSlot
import com.tripguard.app.domain.model.AppointmentStatus
import com.tripguard.app.navigation.Screen
import com.tripguard.app.ui.base.TripGuardViewModel
import com.tripguard.app.ui.components.EmptyStateView
import com.tripguard.app.ui.components.InfoBanner
import com.tripguard.app.ui.components.LoadingState
import com.tripguard.app.ui.components.PrimaryButton
import com.tripguard.app.ui.components.SecondaryButton
import com.tripguard.app.ui.components.StatusChip
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
import com.tripguard.app.ui.theme.SoftSlateBg
import com.tripguard.app.ui.theme.SoftSlateFg
import com.tripguard.app.ui.theme.Spacing
import com.tripguard.app.ui.theme.TextSecondary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppointmentUiState(
    val applicationId: String = "",
    val isLoading: Boolean = true,
    val appointment: AppointmentInvitation? = null,
    val selectedSlotId: String? = null,
    val isSubmitting: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
)

class AppointmentViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : TripGuardViewModel(application) {

    private val applicationId: String =
        savedStateHandle.get<String>(Screen.Appointment.ARG_APPLICATION_ID).orEmpty()

    private val _uiState = MutableStateFlow(AppointmentUiState(applicationId = applicationId))
    val uiState: StateFlow<AppointmentUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.applications.observeApplication(applicationId)
                .collect { app ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = app == null,
                        appointment = app?.appointment,
                    )
                }
        }
    }

    fun selectSlot(slotId: String) {
        _uiState.value = _uiState.value.copy(selectedSlotId = slotId, message = null)
    }

    fun accept() = submit(AppointmentResponse.ACCEPT, slotId = null, reason = null)

    fun requestChange() {
        val slot = _uiState.value.selectedSlotId
        if (slot == null) {
            _uiState.value = _uiState.value.copy(
                message = "Choose one of the alternative slots first.",
                messageIsError = true,
            )
            return
        }
        submit(AppointmentResponse.CHANGE, slotId = slot, reason = null)
    }

    fun decline(reason: String?) = submit(AppointmentResponse.DECLINE, slotId = null, reason = reason?.takeIf { it.isNotBlank() })

    private fun submit(response: AppointmentResponse, slotId: String?, reason: String?) {
        val appointment = _uiState.value.appointment ?: return
        if (_uiState.value.isSubmitting) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubmitting = true, message = null)
            val result = container.applications.respondToAppointment(
                applicationId = applicationId,
                appointmentId = appointment.id,
                response = response,
                slotId = slotId,
                reason = reason,
            )
            _uiState.value = when (result) {
                is AppResult.Success -> _uiState.value.copy(
                    isSubmitting = false,
                    selectedSlotId = null,
                    message = "Your response was sent to the agency.",
                    messageIsError = false,
                )

                is AppResult.Failure -> _uiState.value.copy(
                    isSubmitting = false,
                    message = "We couldn't send your response: ${result.error.message}",
                    messageIsError = true,
                )
            }
        }
    }
}

@Composable
fun AppointmentScreen(
    onBack: () -> Unit,
    onViewApplication: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppointmentViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeclineDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TripGuardTopBar(title = "Appointment", onBack = onBack)

        val appointment = state.appointment
        when {
            state.isLoading -> LoadingState(message = "Loading appointment…")

            appointment == null -> EmptyStateView(
                icon = Icons.Outlined.EventAvailable,
                title = "No appointment proposed",
                description = "When your agency proposes an appointment for your application, it will appear here.",
                action = { PrimaryButton(text = "Back to application", onClick = { onViewApplication(state.applicationId) }) },
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.screenHorizontal,
                    end = Spacing.screenHorizontal,
                    top = Spacing.md,
                    bottom = Spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item { AppointmentHeader(appointment) }

                appointment.message?.let { message ->
                    item {
                        InfoBanner(
                            text = message,
                            containerColor = SoftBlueBg,
                            contentColor = SoftBlueFg,
                            icon = Icons.Outlined.Info,
                        )
                    }
                }

                state.message?.let { message ->
                    item {
                        InfoBanner(
                            text = message,
                            containerColor = if (state.messageIsError) SoftRedBg else SoftBlueBg,
                            contentColor = if (state.messageIsError) SoftRedFg else SoftBlueFg,
                        )
                    }
                }

                if (appointment.needsResponse) {
                    if (appointment.alternatives.isNotEmpty()) {
                        item {
                            Text(
                                text = "CHOOSE ANOTHER SLOT",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextSecondary,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        items(appointment.alternatives, key = { it.id }) { slot ->
                            SlotRow(
                                slot = slot,
                                selected = state.selectedSlotId == slot.id,
                                onSelect = { viewModel.selectSlot(slot.id) },
                            )
                        }
                        item {
                            SecondaryButton(
                                text = "Request selected slot",
                                onClick = viewModel::requestChange,
                                enabled = state.selectedSlotId != null && !state.isSubmitting,
                            )
                        }
                    }

                    item {
                        PrimaryButton(
                            text = "Accept proposed appointment",
                            onClick = viewModel::accept,
                            loading = state.isSubmitting,
                            icon = Icons.Outlined.EventAvailable,
                        )
                    }
                    item {
                        SecondaryButton(
                            text = "Decline appointment",
                            onClick = { showDeclineDialog = true },
                            enabled = !state.isSubmitting,
                        )
                    }
                } else {
                    item {
                        PrimaryButton(
                            text = "Back to application",
                            onClick = { onViewApplication(state.applicationId) },
                        )
                    }
                }

                item {
                    InfoBanner(
                        text = "Appointment changes are only confirmed once the agency's system accepts them. " +
                            "TripGuard will show the updated status here.",
                        containerColor = SoftSlateBg,
                        contentColor = SoftSlateFg,
                        icon = Icons.Outlined.Info,
                    )
                }
            }
        }
    }

    if (showDeclineDialog) {
        DeclineDialog(
            onDismiss = { showDeclineDialog = false },
            onConfirm = { reason ->
                showDeclineDialog = false
                viewModel.decline(reason)
            },
        )
    }
}

@Composable
private fun AppointmentHeader(appointment: AppointmentInvitation) {
    TripGuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = appointment.agencyName ?: "Visa agency",
                    style = MaterialTheme.typography.titleMedium,
                    color = Navy,
                )
                appointment.purpose?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
            AppointmentStatusChip(appointment.status)
        }
        Spacer(Modifier.height(Spacing.md))
        InfoRow(Icons.Outlined.CalendarMonth, "Proposed time", formatSlot(appointment.proposedStartAt, appointment.proposedEndAt))
        appointment.location?.let { InfoRow(Icons.Outlined.LocationOn, "Location", it) }
    }
}

@Composable
private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.sm))
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.width(110.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = Navy, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SlotRow(slot: AppointmentSlot, selected: Boolean, onSelect: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
    ) {
        Row(modifier = Modifier.padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = formatSlot(slot.startAt, slot.endAt),
                style = MaterialTheme.typography.bodyMedium,
                color = Navy,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun AppointmentStatusChip(status: AppointmentStatus) {
    val (bg, fg) = when (status) {
        AppointmentStatus.AWAITING_RESPONSE -> SoftAmberBg to SoftAmberFg
        AppointmentStatus.ACCEPTED -> SoftBlueBg to SoftBlueFg
        AppointmentStatus.CHANGE_REQUESTED -> SoftAmberBg to SoftAmberFg
        AppointmentStatus.DECLINED -> SoftRedBg to SoftRedFg
        AppointmentStatus.CONFIRMED -> SoftEmeraldBg to SoftEmeraldFg
        AppointmentStatus.CANCELLED -> SoftSlateBg to SoftSlateFg
    }
    StatusChip(text = status.label, background = bg, foreground = fg)
}

@Composable
private fun DeclineDialog(onDismiss: () -> Unit, onConfirm: (String?) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Decline this appointment?") },
        text = {
            Column {
                Text(
                    text = "The agency will be told you declined. You can optionally add a reason.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(Spacing.md))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason.ifBlank { null }) }) {
                Text("Decline", color = SoftRedFg)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private val slotFormat = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault())

private fun formatSlot(startAt: Long?, endAt: Long?): String {
    if (startAt == null) return "Time to be confirmed"
    val start = slotFormat.format(Date(startAt))
    if (endAt == null) return start
    val endTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(endAt))
    return "$start – $endTime"
}
