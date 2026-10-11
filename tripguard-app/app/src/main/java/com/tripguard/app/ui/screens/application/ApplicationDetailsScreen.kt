package com.tripguard.app.ui.screens.application

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tripguard.app.domain.model.AgencyComment
import com.tripguard.app.domain.model.AgencyHandoff
import com.tripguard.app.domain.model.AppointmentInvitation
import com.tripguard.app.domain.model.AppointmentStatus
import com.tripguard.app.domain.model.Application
import com.tripguard.app.domain.model.DocumentUpload
import com.tripguard.app.domain.model.HandoffStatus
import com.tripguard.app.domain.model.TimelineEvent
import com.tripguard.app.domain.model.TimelineKind
import com.tripguard.app.domain.readiness.ReadinessCalculator
import com.tripguard.app.domain.readiness.ReadinessReport
import com.tripguard.app.navigation.Screen
import com.tripguard.app.ui.base.TripGuardViewModel
import com.tripguard.app.ui.components.ApplicationStatusChip
import com.tripguard.app.ui.components.EmptyStateView
import com.tripguard.app.ui.components.ErrorStateView
import com.tripguard.app.ui.components.InfoBanner
import com.tripguard.app.ui.components.InlineIconTextButton
import com.tripguard.app.ui.components.KeyValueRow
import com.tripguard.app.ui.components.LoadingState
import com.tripguard.app.ui.components.PrimaryButton
import com.tripguard.app.ui.components.SecondaryButton
import com.tripguard.app.ui.components.StatusChip
import com.tripguard.app.ui.components.TripGuardCard
import com.tripguard.app.ui.components.TripGuardTopBar
import com.tripguard.app.ui.screens.common.DocumentSummaryFactory
import com.tripguard.app.ui.screens.common.documentIcon
import com.tripguard.app.ui.screens.common.documentToneColors
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ApplicationDetailsUiState(
    val applicationId: String = "",
    val isLoading: Boolean = true,
    val error: String? = null,
    val application: Application? = null,
    val report: ReadinessReport? = null,
    val isDeleting: Boolean = false,
)

class ApplicationDetailsViewModel(
    application: android.app.Application,
    savedStateHandle: SavedStateHandle,
) : TripGuardViewModel(application) {

    private val applicationId: String =
        savedStateHandle.get<String>(Screen.ApplicationDetails.ARG_APPLICATION_ID).orEmpty()

    private val _uiState = MutableStateFlow(ApplicationDetailsUiState(applicationId = applicationId))
    val uiState: StateFlow<ApplicationDetailsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.catalog.load()
            container.applications.setActiveApplication(applicationId)
            combine(
                container.applications.observeApplication(applicationId),
                container.catalog.catalog,
            ) { app, catalog -> app to catalog }
                .collect { (app, catalog) ->
                    val visa = catalog?.visaTypes?.firstOrNull { it.id == app?.visaTypeId }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        application = app,
                        report = if (app != null && visa != null) ReadinessCalculator.calculate(visa, app) else null,
                    )
                }
        }
    }

    fun resolveComment(commentId: String) {
        viewModelScope.launch { container.applications.resolveAgencyComment(applicationId, commentId) }
    }

    fun delete(onDeleted: () -> Unit) {
        if (_uiState.value.isDeleting) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDeleting = true)
            container.applications.deleteApplication(applicationId)
            onDeleted()
        }
    }
}

@Composable
fun ApplicationDetailsScreen(
    onBack: () -> Unit,
    onEditApplicant: (String) -> Unit,
    onOpenChecklist: (String) -> Unit,
    onOpenDocument: (applicationId: String, documentId: String) -> Unit,
    onOpenReadiness: (String) -> Unit,
    onOpenCorrection: (applicationId: String, documentId: String) -> Unit,
    onOpenSharing: (String) -> Unit,
    onOpenAppointment: (String) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ApplicationDetailsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TripGuardTopBar(title = "Application", onBack = onBack)

        when {
            state.isLoading -> LoadingState(message = "Loading application…")

            state.application == null -> ErrorStateView(message = "This application could not be found.")

            else -> {
                val app = state.application!!
                val report = state.report

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
                    item { OverviewCard(app, report) }

                    if (report != null) {
                        item {
                            InfoBanner(text = report.nextStep, containerColor = SoftBlueBg, contentColor = SoftBlueFg)
                        }
                    }

                    if (app.agencyComments.any { !it.resolved }) {
                        item {
                            Text(
                                text = "CORRECTION REQUESTS",
                                style = MaterialTheme.typography.labelSmall,
                                color = SoftRedFg,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        items(app.agencyComments.filter { !it.resolved }, key = { it.id }) { comment ->
                            AgencyCommentCard(
                                comment = comment,
                                onOpenDocument = {
                                    comment.documentId?.let { onOpenCorrection(app.id, it) }
                                        ?: onOpenChecklist(app.id)
                                },
                                onResolve = { viewModel.resolveComment(comment.id) },
                            )
                        }
                    }

                    item { ApplicantCard(app) { onEditApplicant(app.id) } }

                    app.handoff?.let { handoff ->
                        item { HandoffCard(handoff) { onOpenSharing(app.id) } }
                    }

                    app.appointment?.let { invite ->
                        item { AppointmentCard(invite) { onOpenAppointment(app.id) } }
                    }

                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Documents",
                                style = MaterialTheme.typography.titleMedium,
                                color = Navy,
                                modifier = Modifier.weight(1f),
                            )
                            InlineIconTextButton(
                                text = "Checklist",
                                icon = Icons.AutoMirrored.Outlined.FactCheck,
                                onClick = { onOpenChecklist(app.id) },
                            )
                        }
                    }

                    val documents = app.documents.values.toList()
                    if (documents.isEmpty()) {
                        item {
                            InfoBanner(
                                text = "No documents uploaded yet. Open the checklist to start adding them.",
                                containerColor = SoftSlateBg,
                                contentColor = SoftSlateFg,
                            )
                        }
                    } else {
                        items(documents, key = { it.documentId }) { doc ->
                            DocumentCard(doc = doc) {
                                onOpenDocument(app.id, doc.documentId)
                            }
                        }
                    }

                    item {
                        Text(
                            text = "ACTIVITY",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    if (app.timeline.isEmpty()) {
                        item {
                            Text(
                                text = "No activity recorded yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary,
                            )
                        }
                    } else {
                        items(app.timeline.sortedByDescending { it.timestamp }, key = { it.id }) { event ->
                            TimelineRow(event)
                        }
                    }

                    item {
                        Spacer(Modifier.height(Spacing.xs))
                        PrimaryButton(
                            text = "Review readiness",
                            onClick = { onOpenReadiness(app.id) },
                        )
                    }
                    item {
                        SecondaryButton(
                            text = "Open full checklist",
                            onClick = { onOpenChecklist(app.id) },
                        )
                    }
                    item {
                        InlineIconTextButton(
                            text = "Delete application",
                            icon = Icons.Outlined.Delete,
                            onClick = { showDeleteDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this application?") },
            text = {
                Text(
                    "This removes the application and its uploaded files from this device. " +
                        "This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.delete(onDeleted)
                    },
                ) { Text("Delete", color = SoftRedFg) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun OverviewCard(app: Application, report: ReadinessReport?) {
    TripGuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = app.reference.takeLast(3),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.visaTypeName, style = MaterialTheme.typography.titleMedium, color = Navy)
                Spacer(Modifier.height(2.dp))
                Text(app.reference, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            ApplicationStatusChip(report?.overall ?: app.status)
        }
        Spacer(Modifier.height(Spacing.md))
        KeyValueRow("Applicant", app.applicant.fullName.ifBlank { "Not provided" })
        KeyValueRow("Created", formatDateTime(app.createdAt))
        KeyValueRow("Last updated", formatDateTime(app.updatedAt))
        if (report != null) {
            KeyValueRow("Required documents", "${report.requiredUploaded} of ${report.requiredTotal} prepared")
            KeyValueRow("Accepted by agency", "${report.requiredAccepted} of ${report.requiredTotal}")
        }
    }
}

@Composable
private fun ApplicantCard(app: Application, onEdit: () -> Unit) {
    TripGuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Applicant",
                style = MaterialTheme.typography.titleMedium,
                color = Navy,
                modifier = Modifier.weight(1f),
            )
            InlineIconTextButton(text = "Edit", icon = Icons.Outlined.Edit, onClick = onEdit)
        }
        Spacer(Modifier.height(Spacing.xs))
        KeyValueRow("Full name", app.applicant.fullName.ifBlank { "Not provided" })
        KeyValueRow("Nationality", app.applicant.nationality)
        KeyValueRow("Passport", app.applicant.passportType)
        KeyValueRow("Purpose", app.applicant.purpose)
        if (app.applicant.contactEmail.isNotBlank()) KeyValueRow("Email", app.applicant.contactEmail)
        if (app.applicant.contactPhone.isNotBlank()) KeyValueRow("Phone", app.applicant.contactPhone)
    }
}

@Composable
private fun AgencyCommentCard(
    comment: AgencyComment,
    onOpenDocument: () -> Unit,
    onResolve: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SoftRedBg),
        border = BorderStroke(1.dp, SoftRedFg.copy(alpha = 0.25f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = SoftRedFg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = comment.documentName ?: "Correction requested",
                    style = MaterialTheme.typography.titleSmall,
                    color = SoftRedFg,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(comment.message, style = MaterialTheme.typography.bodySmall, color = SoftRedFg)
            Spacer(Modifier.height(Spacing.md))
            PrimaryButton(text = "Replace document", onClick = onOpenDocument)
            Spacer(Modifier.height(Spacing.xs))
            InlineIconTextButton(
                text = "Mark as resolved",
                icon = Icons.AutoMirrored.Outlined.FactCheck,
                onClick = onResolve,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DocumentCard(doc: DocumentUpload, onClick: () -> Unit) {
    val summary = DocumentSummaryFactory.summarize(doc)
    val (bg, fg) = documentToneColors(summary.tone)
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = documentIcon(doc.documentId),
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = doc.documentName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Navy,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary.resultLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.Center,
            ) {
                StatusChip(text = summary.statusLabel, background = bg, foreground = fg)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "View details",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun HandoffCard(handoff: AgencyHandoff, onOpen: () -> Unit) {
    val awaiting = handoff.awaitingConsent
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (handoff.status == HandoffStatus.CONFIRMED) SoftEmeraldBg else SoftAmberBg,
        ),
        border = BorderStroke(1.dp, (if (handoff.status == HandoffStatus.CONFIRMED) SoftEmeraldFg else SoftAmberFg).copy(alpha = 0.25f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Share, contentDescription = null, tint = if (awaiting) SoftAmberFg else SoftEmeraldFg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = if (handoff.status == HandoffStatus.CONFIRMED) "Shared with agency" else "Document sharing",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (handoff.status == HandoffStatus.CONFIRMED) SoftEmeraldFg else SoftAmberFg,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = handoff.status.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (handoff.status == HandoffStatus.CONFIRMED) SoftEmeraldFg else SoftAmberFg,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = if (awaiting) {
                    "${handoff.agencyName} is ready to process your application and needs your consent to share documents."
                } else {
                    "${handoff.agencyName} · ${handoff.purpose}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            if (awaiting) {
                Spacer(Modifier.height(Spacing.md))
                PrimaryButton(text = "Review and share", onClick = onOpen, icon = Icons.Outlined.Share)
            }
        }
    }
}

@Composable
private fun AppointmentCard(invite: AppointmentInvitation, onOpen: () -> Unit) {
    val needsResponse = invite.needsResponse
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (needsResponse) SoftAmberBg else MaterialTheme.colorScheme.surface,
        ),
        border = BorderStroke(1.dp, if (needsResponse) SoftAmberFg.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.EventAvailable,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = "Appointment",
                    style = MaterialTheme.typography.titleSmall,
                    color = Navy,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = invite.status.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = appointmentStatusColor(invite.status).second,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            val proposed = invite.proposedStartAt
            Text(
                text = if (proposed != null) formatDateTime(proposed) else "Time to be confirmed",
                style = MaterialTheme.typography.bodyMedium,
                color = Navy,
                fontWeight = FontWeight.Medium,
            )
            invite.location?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Spacer(Modifier.height(Spacing.md))
            SecondaryButton(
                text = if (needsResponse) "Respond to appointment" else "View appointment",
                onClick = onOpen,
            )
        }
    }
}

private fun appointmentStatusColor(status: AppointmentStatus): Pair<Color, Color> = when (status) {
    AppointmentStatus.AWAITING_RESPONSE -> SoftAmberBg to SoftAmberFg
    AppointmentStatus.ACCEPTED -> SoftBlueBg to SoftBlueFg
    AppointmentStatus.CHANGE_REQUESTED -> SoftAmberBg to SoftAmberFg
    AppointmentStatus.DECLINED -> SoftRedBg to SoftRedFg
    AppointmentStatus.CONFIRMED -> SoftEmeraldBg to SoftEmeraldFg
    AppointmentStatus.CANCELLED -> SoftSlateBg to SoftSlateFg
}

@Composable
private fun TimelineRow(event: TimelineEvent) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(timelineColor(event.kind)),
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f).padding(bottom = Spacing.sm)) {
            Text(event.title, style = MaterialTheme.typography.bodyMedium, color = Navy, fontWeight = FontWeight.Medium)
            event.description?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Spacer(Modifier.height(2.dp))
            Text(formatDateTime(event.timestamp), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
    }
}

@Composable
private fun timelineColor(kind: TimelineKind): Color = when (kind) {
    TimelineKind.CORRECTION_REQUESTED,
    TimelineKind.DOCUMENT_NEEDS_REVIEW,
    -> SoftRedFg

    TimelineKind.DOCUMENT_UPLOADED,
    TimelineKind.DOCUMENT_PROCESSED,
    TimelineKind.SYNCED,
    -> SoftBlueFg

    TimelineKind.STATUS_CHANGED,
    TimelineKind.AGENCY_COMMENT,
    TimelineKind.APPLICANT_CORRECTION,
    TimelineKind.SHARE_CONSENT_REQUESTED,
    TimelineKind.APPOINTMENT_PROPOSED,
    TimelineKind.APPOINTMENT_RESPONDED,
    -> SoftAmberFg

    TimelineKind.SHARE_CONSENT_CONFIRMED,
    TimelineKind.AGENCY_PROCESSING_STARTED,
    -> SoftEmeraldFg

    TimelineKind.CREATED -> MaterialTheme.colorScheme.primary
}

private val dateTimeFormat = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())

private fun formatDateTime(millis: Long): String = dateTimeFormat.format(Date(millis))
