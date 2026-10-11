package com.tripguard.app.ui.screens.home

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tripguard.app.domain.model.Application
import com.tripguard.app.domain.model.ApplicationStatus
import com.tripguard.app.domain.model.AgencyInquiryUi
import com.tripguard.app.ui.base.TripGuardViewModel
import com.tripguard.app.ui.components.ApplicationStatusChip
import com.tripguard.app.ui.components.InfoBanner
import com.tripguard.app.ui.components.PrimaryButton
import com.tripguard.app.ui.components.SecondaryButton
import com.tripguard.app.ui.components.TripGuardCard
import com.tripguard.app.ui.components.TripGuardHomeTopBar
import com.tripguard.app.ui.theme.Emerald
import com.tripguard.app.ui.theme.Navy
import com.tripguard.app.ui.theme.NavyDeep
import com.tripguard.app.ui.theme.SoftBlueBg
import com.tripguard.app.ui.theme.SoftBlueFg
import com.tripguard.app.ui.theme.SoftEmeraldBg
import com.tripguard.app.ui.theme.SoftEmeraldFg
import com.tripguard.app.ui.theme.SoftRedBg
import com.tripguard.app.ui.theme.SoftRedFg
import com.tripguard.app.ui.theme.Spacing
import com.tripguard.app.ui.theme.TextOnNavy
import com.tripguard.app.ui.theme.TextOnNavyMuted
import com.tripguard.app.ui.theme.TextSecondary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class HomeNotificationKind { CORRECTION, SHARING, APPOINTMENT, READY, INFO }

data class HomeNotification(
    val id: String,
    val title: String,
    val message: String,
    val kind: HomeNotificationKind,
)

data class HomeUiState(
    val activeApplication: Application? = null,
    val notifications: List<HomeNotification> = emptyList(),
    val nextStep: String? = null,
)

/** Live agency-assistance summary for the home badge. */
data class HomeAgencyState(
    val inquiry: AgencyInquiryUi? = null,
    val hasUnseenUpdate: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(application: android.app.Application) : TripGuardViewModel(application) {
    val uiState: StateFlow<HomeUiState> =
        container.applications.observeActiveApplicationId()
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(HomeUiState())
                } else {
                    container.applications.observeApplication(id)
                        .map { app -> app.toHomeState() }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = HomeUiState(),
            )

    val agencyState: StateFlow<HomeAgencyState> =
        combine(
            container.agency.localState,
            container.agency.hasUnseenAgencyUpdate,
        ) { local, unseen -> HomeAgencyState(local.inquiry, unseen) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = HomeAgencyState(),
            )

    private fun Application?.toHomeState(): HomeUiState {
        if (this == null) return HomeUiState()
        val notifications = buildList {
            agencyComments.filter { !it.resolved }.forEach { comment ->
                add(
                    HomeNotification(
                        id = "comment-${comment.id}",
                        title = "Correction requested",
                        message = comment.documentName?.let { "$it: ${comment.message}" } ?: comment.message,
                        kind = HomeNotificationKind.CORRECTION,
                    ),
                )
            }
            handoff?.takeIf { it.awaitingConsent }?.let { pending ->
                add(
                    HomeNotification(
                        id = "share-${pending.id}",
                        title = "Confirm document sharing",
                        message = "${pending.agencyName} is ready to process your application and needs your consent.",
                        kind = HomeNotificationKind.SHARING,
                    ),
                )
            }
            appointment?.takeIf { it.needsResponse }?.let { invite ->
                add(
                    HomeNotification(
                        id = "appointment-${invite.id}",
                        title = "Appointment proposed",
                        message = invite.agencyName?.let { "$it proposed an appointment. Please respond." }
                            ?: "Your agency proposed an appointment. Please respond.",
                        kind = HomeNotificationKind.APPOINTMENT,
                    ),
                )
            }
            if (status == ApplicationStatus.READY_FOR_AGENCY && handoff == null) {
                add(
                    HomeNotification(
                        id = "ready-${id}",
                        title = "Ready for agency processing",
                        message = "Your checklist is complete. Your agency can now review the application.",
                        kind = HomeNotificationKind.READY,
                    ),
                )
            }
        }
        val nextStep = when {
            applicant.fullName.isBlank() -> "Add your applicant details to build the checklist."
            notifications.any { it.kind == HomeNotificationKind.CORRECTION } ->
                "Open the correction request and replace the flagged document."

            notifications.any { it.kind == HomeNotificationKind.SHARING } ->
                "Review and confirm which documents to share with your agency."

            notifications.any { it.kind == HomeNotificationKind.APPOINTMENT } ->
                "Respond to the appointment proposal from your agency."

            status == ApplicationStatus.READY_FOR_AGENCY ->
                "Your checklist is complete and ready for your agency."

            else -> "Continue preparing your documents."
        }
        return HomeUiState(activeApplication = this, notifications = notifications, nextStep = nextStep)
    }
}

@Composable
fun HomeScreen(
    onApply: () -> Unit,
    onContinueApplication: (String) -> Unit,
    onOpenSharing: (String) -> Unit,
    onOpenAppointment: (String) -> Unit,
    onTrackAgency: (String) -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val agencyState by viewModel.agencyState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TripGuardHomeTopBar(
            initials = "",
            onProfileClick = onProfileClick,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.screenHorizontal,
                end = Spacing.screenHorizontal,
                top = Spacing.md,
                bottom = Spacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            item { WelcomeHero(onApply = onApply) }

            agencyState.inquiry?.let { inquiry ->
                item {
                    AgencyTrackerCard(
                        inquiry = inquiry,
                        hasUnseenUpdate = agencyState.hasUnseenUpdate,
                        appId = state.activeApplication?.id,
                        onTrack = onTrackAgency,
                    )
                }
            }

            state.activeApplication?.let { app ->
                item {
                    ContinueCard(
                        application = app,
                        nextStep = state.nextStep,
                        onClick = { onContinueApplication(app.id) },
                    )
                }
            }

            if (state.notifications.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            text = "Action needed",
                            style = MaterialTheme.typography.titleMedium,
                            color = Navy,
                        )
                        state.notifications.forEach { notification ->
                            NotificationCard(
                                notification = notification,
                                onClick = {
                                    val appId = state.activeApplication?.id ?: return@NotificationCard
                                    when (notification.kind) {
                                        HomeNotificationKind.SHARING -> onOpenSharing(appId)
                                        HomeNotificationKind.APPOINTMENT -> onOpenAppointment(appId)
                                        else -> onContinueApplication(appId)
                                    }
                                },
                            )
                        }
                    }
                }
            }

            item { ReadinessExplanation() }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(
                        text = "How it works",
                        style = MaterialTheme.typography.titleMedium,
                        color = Navy,
                    )
                    StepRow(1, "Choose your visa", "Pick the 30-day, 60-day or five-year Dubai tourist visa.")
                    StepRow(2, "Upload your documents", "Add each required document and we extract the details.")
                    StepRow(3, "Track readiness", "See what's missing and what needs review before your agency processes it.")
                }
            }
        }
    }
}

@Composable
private fun AgencyTrackerCard(
    inquiry: AgencyInquiryUi,
    hasUnseenUpdate: Boolean,
    appId: String?,
    onTrack: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Emerald.copy(alpha = 0.3f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onTrack(appId.orEmpty()) },
    ) {
        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(SoftEmeraldBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.FactCheck,
                        contentDescription = null,
                        tint = SoftEmeraldFg,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Agency appointment",
                        style = MaterialTheme.typography.titleSmall,
                        color = Navy,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Ref ${inquiry.reference}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                if (hasUnseenUpdate) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(SoftRedBg)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(SoftRedFg, RoundedCornerShape(4.dp)),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "New",
                            style = MaterialTheme.typography.labelSmall,
                            color = SoftRedFg,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Text(
                inquiry.statusLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (hasUnseenUpdate) "Tap to see the latest update from your agency." else "Tap to view your inquiry, slots and booking.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NotificationCard(notification: HomeNotification, onClick: () -> Unit) {
    val (icon, bg, fg) = when (notification.kind) {
        HomeNotificationKind.CORRECTION -> Triple(Icons.Outlined.Info, SoftRedBg, SoftRedFg)
        HomeNotificationKind.SHARING -> Triple(Icons.Outlined.Info, SoftBlueBg, SoftBlueFg)
        HomeNotificationKind.APPOINTMENT -> Triple(Icons.Outlined.Info, SoftBlueBg, SoftBlueFg)
        HomeNotificationKind.READY -> Triple(Icons.Filled.CheckCircle, SoftEmeraldBg, SoftEmeraldFg)
        HomeNotificationKind.INFO -> Triple(Icons.Outlined.Info, SoftBlueBg, SoftBlueFg)
    }
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.25f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.padding(Spacing.md), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(bg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = notification.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Navy,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = notification.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = fg,
                modifier = Modifier
                    .align(Alignment.CenterVertically)
                    .size(18.dp),
            )
        }
    }
}

@Composable
private fun WelcomeHero(onApply: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Navy, NavyDeep))),
    ) {
        Column(modifier = Modifier.padding(Spacing.xl)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Emerald),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = "DUBAI TOURIST VISA · INDIAN PASSPORTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextOnNavyMuted,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = "Prepare your Dubai visa documents with confidence",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "Organise every required document for your Dubai tourist visa and follow your application readiness step by step.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextOnNavyMuted,
            )
            Spacer(Modifier.height(Spacing.xl))
            PrimaryButton(
                text = "Apply for a Dubai Visa",
                onClick = onApply,
                icon = Icons.Outlined.EditNote,
            )
        }
    }
}

@Composable
private fun ContinueCard(application: Application, nextStep: String?, onClick: () -> Unit) {
    TripGuardCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Continue application",
                    style = MaterialTheme.typography.titleMedium,
                    color = Navy,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${application.visaTypeName} · ${application.reference}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(Spacing.xs))
                ApplicationStatusChip(application.status)
            }
        }
        nextStep?.let {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        SecondaryButton(text = "Resume", onClick = onClick)
    }
}

@Composable
private fun ReadinessExplanation() {
    InfoBanner(
        text = "TripGuard helps you prepare and check documents for a Dubai tourist visa. " +
            "It does not approve visas, verify document authenticity, or submit applications to the UAE government.",
        containerColor = SoftBlueBg,
        contentColor = SoftBlueFg,
        icon = Icons.Outlined.Info,
    )
}

@Composable
private fun StepRow(index: Int, title: String, description: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SoftEmeraldBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = index.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = SoftEmeraldFg,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = Navy)
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
    }
}
