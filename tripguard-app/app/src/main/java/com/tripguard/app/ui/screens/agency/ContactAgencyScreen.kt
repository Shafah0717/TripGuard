package com.tripguard.app.ui.screens.agency

import android.app.Application
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.AgencyBookingUi
import com.tripguard.app.domain.model.AgencyInquiryUi
import com.tripguard.app.domain.model.AgencySlotUi
import com.tripguard.app.domain.model.ConsentCategory
import com.tripguard.app.domain.model.ErrorKind
import com.tripguard.app.domain.readiness.ReadinessCalculator
import com.tripguard.app.navigation.Screen
import com.tripguard.app.ui.base.TripGuardViewModel
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

data class ContactAgencyUiState(
    val isLoading: Boolean = true,
    val retainedInquiry: AgencyInquiryUi? = null,
    val retainedBooking: AgencyBookingUi? = null,
    val retainedSlots: List<AgencySlotUi> = emptyList(),
    val isSubmittingInquiry: Boolean = false,
    val submitError: String? = null,
    val isLoadingSlots: Boolean = false,
    val slotsError: String? = null,
    val selectedSlotId: Long? = null,
    val isConfirmingBooking: Boolean = false,
    val bookingError: String? = null,
    val bookingJustConfirmed: Boolean = false,
    val isCancelling: Boolean = false,
    val cancelError: String? = null,
    val showBookingConfirmation: Boolean = false,
    val isTracking: Boolean = false,
    val trackingUpdate: String? = null,
    val trackingPolledAt: String? = null,
    val isWithdrawing: Boolean = false,
    // Prefilled from the completed preparation, only ever shared with consent.
    val visaRoute: String = "tourist_30_day_single_entry",
    val missingDocCount: Int = 0,
    val issueCount: Int = 0,
    val prepSummaryText: String? = null,
    val prefillName: String = "",
    val prefillEmail: String? = null,
    val prefillPhone: String? = null,
)

class ContactAgencyViewModel(
    application: Application,
    savedStateHandle: SavedStateHandle,
) : TripGuardViewModel(application) {

    private val applicationId: String =
        savedStateHandle.get<String>(Screen.ContactAgency.ARG_APPLICATION_ID).orEmpty()

    private val _uiState = MutableStateFlow(ContactAgencyUiState())
    val uiState: StateFlow<ContactAgencyUiState> = _uiState.asStateFlow()

    /** Reference used for the inquiry's visa route (set by the caller). */
    var visaRoute: String = "tourist_30_day_single_entry"
        private set

    init {
        val retained = container.agency.localState.value
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            retainedInquiry = retained.inquiry,
            retainedBooking = retained.booking,
        )
        if (retained.inquiry != null) {
            container.agency.markAgencyUpdateSeen()
            refreshSlots(silent = true)
            startTracking()
        }
        observePreparation()

        // Keep the screen in step with the shared agency state: a background sync
        // (or an agency-side deletion surfacing as a 404) clears the retained
        // inquiry, which returns the traveller to the consent screen.
        viewModelScope.launch {
            container.agency.localState.collect { local ->
                _uiState.value = _uiState.value.copy(
                    retainedInquiry = local.inquiry,
                    retainedBooking = local.booking,
                )
            }
        }
    }

    /**
     * Polls the backend every few seconds while this screen is open so updates
     * from the agency (status changes, newly offered appointment slots and the
     * confirmed booking) arrive without the traveller doing anything. Errors are
     * silent: the app simply shows the last known state until connectivity or a
     * server response returns.
     */
    private fun startTracking() {
        if (_uiState.value.isTracking) return
        _uiState.value = _uiState.value.copy(isTracking = true)
        viewModelScope.launch {
            while (isActive && _uiState.value.retainedInquiry != null) {
                delay(5_000)
                val changes = mutableListOf<String>()
                syncFromServer(changes)
                if (changes.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(trackingUpdate = changes.joinToString("\n"))
                }
            }
        }
    }

    /** Manual "check now" action; same sync path as the tracker, presented instantly. */
    fun checkForUpdates() {        viewModelScope.launch {
            val changes = mutableListOf<String>()
            syncFromServer(changes)
            if (changes.isNotEmpty() || _uiState.value.trackingUpdate != null) {
                _uiState.value = _uiState.value.copy(
                    trackingUpdate = changes.joinToString("\n")
                        .ifEmpty { _uiState.value.trackingUpdate.orEmpty() },
                )
            }
        }
    }

    /**
     * Withdraws the inquiry (best-effort server-side) and clears local state so
     * the traveller can start a fresh inquiry. The shared state flow flips the
     * screen back to the consent gate once cleared.
     */
    fun withdrawInquiry() {
        if (_uiState.value.isWithdrawing) return
        _uiState.value = _uiState.value.copy(isWithdrawing = true)
        viewModelScope.launch {
            runCatching { container.agency.withdrawInquiry() }
            _uiState.value = _uiState.value.copy(isWithdrawing = false)
        }
    }

    /**
     * Fetch the latest inquiry, slots or booking and record what changed. Frame
     * the real-time tracking loop and the manual check share this one path.
     */
    private suspend fun syncFromServer(changes: MutableList<String>) {
        val beforeStatus = _uiState.value.retainedInquiry?.status
        container.agency.refreshInquiry().takeSuccess()?.let { latest ->
            _uiState.value = _uiState.value.copy(
                retainedInquiry = latest,
                trackingPolledAt = Instant.now().toString(),
            )
            if (beforeStatus != null && latest.status != beforeStatus) {
                changes += "Your inquiry moved to ${latest.statusLabel}."
            }
        }

        if (_uiState.value.retainedBooking == null) {
            val beforeCount = _uiState.value.retainedSlots.size
            pollSlotsSilently()?.let { slots ->
                _uiState.value = _uiState.value.copy(
                    retainedSlots = slots,
                    slotsError = null,
                    selectedSlotId = _uiState.value.selectedSlotId?.takeIf { id ->
                        slots.any { it.id == id }
                    },
                )
                if (slots.isNotEmpty() && beforeCount == 0) {
                    changes += "New appointment times are available for you to book."
                }
            }
        } else {
            val beforeRef = _uiState.value.retainedBooking?.reference
            val beforeBookingStatus = _uiState.value.retainedBooking?.status
            container.agency.refreshBooking().takeSuccess()?.let { booking ->
                _uiState.value = _uiState.value.copy(
                    retainedBooking = booking,
                    cancelError = null,
                )
                if (beforeRef == null || booking.reference != beforeRef) {
                    changes += "Booking ${booking.reference} is now ${booking.statusLabel}."
                } else if (beforeBookingStatus != null && booking.status != beforeBookingStatus) {
                    changes += "Your booking is now ${booking.statusLabel}."
                }
            }
        }
    }

    /** Silent slot refresh used by the tracker; returns the fresh list or null. */
    private suspend fun pollSlotsSilently(): List<AgencySlotUi>? = when (
        val result = container.agency.fetchAvailableSlots()
    ) {
        is AppResult.Success -> result.data
        is AppResult.Failure -> null
    }

    /**
     * Derives the visa route, applicant contact details and aggregate preparation
     * summary from the on-device application. Only the counts are ever offered for
     * sharing — never OCR text or document fields.
     */
    private fun observePreparation() {
        if (applicationId.isBlank()) return
        viewModelScope.launch {
            container.applications.observeApplication(applicationId).collect { app ->
                if (app == null) return@collect
                val visaType = container.catalog.visaType(app.visaTypeId)
                val report = visaType?.let { ReadinessCalculator.calculate(it, app) }
                val missing = report?.missingItems?.size ?: 0
                val issues = (report?.needsReviewItems?.size ?: 0) +
                    (report?.potentialIssueItems?.size ?: 0)
                visaRoute = app.visaTypeId
                _uiState.value = _uiState.value.copy(
                    visaRoute = app.visaTypeId,
                    missingDocCount = missing,
                    issueCount = issues,
                    prepSummaryText = if (report == null) null else
                        "Local preparation: $missing document(s) still missing, " +
                            "$issues item(s) flagged for review. Not a visa eligibility assessment.",
                    prefillName = _uiState.value.prefillName.ifBlank { app.applicant.fullName },
                    prefillEmail = app.applicant.contactEmail.ifBlank { null },
                    prefillPhone = app.applicant.contactPhone.ifBlank { null },
                )
            }
        }
    }

    fun submitInquiry(
        travellerName: String,
        contactEmail: String?,
        contactPhone: String?,
        message: String,
        shareSummary: Boolean,
        missingDocCount: Int,
        issueCount: Int,
        prepSummaryText: String?,
    ) {
        if (_uiState.value.isSubmittingInquiry) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSubmittingInquiry = true, submitError = null)
            val result = container.agency.submitInquiry(
                travellerName = travellerName,
                contactEmail = contactEmail,
                contactPhone = contactPhone,
                visaRoute = visaRoute,
                message = message,
                prepSummary = if (shareSummary) prepSummaryText else null,
                missingDocCount = missingDocCount,
                issueCount = issueCount,
            )
            _uiState.value = when (result) {
                is AppResult.Success -> _uiState.value.copy(
                    isSubmittingInquiry = false,
                    retainedInquiry = result.data,
                    retainedBooking = container.agency.localState.value.booking,
                )
                is AppResult.Failure -> {
                    val offline = result.error.kind == ErrorKind.OFFLINE ||
                        result.error.kind == ErrorKind.NETWORK ||
                        result.error.kind == ErrorKind.TIMEOUT
                    _uiState.value.copy(
                        isSubmittingInquiry = false,
                        submitError = when {
                            offline ->
                                "Couldn't reach the server at ${container.settings.baseUrl.value}. " +
                                    "Make sure the backend is running and the address is correct in Settings. " +
                                    "Your details stay on this device."
                            else -> result.error.message
                        },
                    )
                }
            }
        }
    }

    fun refreshSlots(silent: Boolean = false) {
        if (_uiState.value.isLoadingSlots) return
        viewModelScope.launch {
            if (!silent) _uiState.value = _uiState.value.copy(isLoadingSlots = true, slotsError = null)
            when (val result = container.agency.fetchAvailableSlots()) {
                is AppResult.Success -> _uiState.value = _uiState.value.copy(
                    isLoadingSlots = false,
                    retainedSlots = result.data,
                    slotsError = null,
                    selectedSlotId = _uiState.value.selectedSlotId?.takeIf { id ->
                        result.data.any { it.id == id }
                    },
                )
                is AppResult.Failure -> _uiState.value = _uiState.value.copy(
                    isLoadingSlots = false,
                    slotsError = result.error.message,
                )
            }
        }
    }

    fun selectSlot(id: Long) {
        _uiState.value = _uiState.value.copy(selectedSlotId = id, bookingError = null)
    }

    fun confirmSelectedBooking() {
        val slotId = _uiState.value.selectedSlotId ?: return
        if (_uiState.value.isConfirmingBooking) return
        // Stable idempotency key per selection round: repeated taps or network
        // retries reuse the same key so the backend never books twice.
        val key = "tg-" + UUID.randomUUID().toString()
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isConfirmingBooking = true, bookingError = null)
            when (val result = container.agency.confirmBooking(slotId, key)) {
                is AppResult.Success -> _uiState.value = _uiState.value.copy(
                    isConfirmingBooking = false,
                    retainedBooking = result.data,
                    selectedSlotId = null,
                    bookingJustConfirmed = true,
                    showBookingConfirmation = true,
                )
                is AppResult.Failure -> _uiState.value = _uiState.value.copy(
                    isConfirmingBooking = false,
                    bookingError = result.error.message,
                )
            }
        }
    }

    fun refreshBooking() {
        viewModelScope.launch {
            when (val result = container.agency.refreshBooking()) {
                is AppResult.Success -> _uiState.value = _uiState.value.copy(
                    retainedBooking = result.data,
                    cancelError = null,
                )
                is AppResult.Failure -> _uiState.value = _uiState.value.copy(
                    cancelError = result.error.message,
                )
            }
        }
    }

    fun cancelBooking() {
        if (_uiState.value.isCancelling) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCancelling = true, cancelError = null)
            when (val result = container.agency.cancelBooking()) {
                is AppResult.Success -> _uiState.value = _uiState.value.copy(
                    isCancelling = false,
                    retainedBooking = result.data,
                    bookingJustConfirmed = false,
                )
                is AppResult.Failure -> _uiState.value = _uiState.value.copy(
                    isCancelling = false,
                    cancelError = result.error.message,
                )
            }
        }
    }

    fun dismissConfirmation() {
        _uiState.value = _uiState.value.copy(showBookingConfirmation = false, bookingJustConfirmed = false)
    }
}

// ---------------------------------------------------------------------------
// Small shared helpers
// ---------------------------------------------------------------------------

/** Returns the successful payload, or null on failure (used by silent polling). */
private fun <T> AppResult<T>.takeSuccess(): T? = (this as? AppResult.Success)?.data

internal val AgencyIsoFormat: DateTimeFormatter =
    DateTimeFormatter.ISO_LOCAL_DATE_TIME

internal fun formatAgencyDateTime(value: String?): String {
    if (value.isNullOrBlank()) return "Time to be confirmed"
    return try {
        val parsed = OffsetDateTime.parse(
            value,
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss[.SSSSSS][.SSS][.SS]").withZone(ZoneOffset.UTC),
        )
        val formatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
        formatter.format(parsed)
    } catch (_: Exception) {
        try {
            val parsed = Instant.parse("${value}Z")
            val formatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm").withZone(ZoneOffset.UTC)
            formatter.format(parsed)
        } catch (_: Exception) {
            value
        }
    }
}

// ---------------------------------------------------------------------------
// ContactAgencyScreen — flow entry: consent review → inquiry form → status
// ---------------------------------------------------------------------------

@Composable
fun ContactAgencyScreen(
    onBack: () -> Unit,
    onBookingConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ContactAgencyViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TripGuardTopBar(title = "Contact an agency", onBack = onBack)

        val inquiry = state.retainedInquiry
        when {
            state.isLoading -> LoadingState(message = "Loading…")

            // Explicit consent gate: nothing is transmitted until the screen
            // below is confirmed. Consent must be an explicit user action.
            inquiry == null -> ConsentGate(
                isSubmitting = state.isSubmittingInquiry,
                error = state.submitError,
                prefillName = state.prefillName,
                prefillEmail = state.prefillEmail,
                prefillPhone = state.prefillPhone,
                hasPrepSummary = state.prepSummaryText != null,
                onConsentSubmit = { name, email, phone, message, shareSummary ->
                    viewModel.submitInquiry(
                        travellerName = name,
                        contactEmail = email,
                        contactPhone = phone,
                        message = message.ifBlank { "I would like to book an appointment." },
                        shareSummary = shareSummary,
                        missingDocCount = state.missingDocCount,
                        issueCount = state.issueCount,
                        prepSummaryText = state.prepSummaryText,
                    )
                },
            )

            else -> InquiryStatusSection(
                state = state,
                onCheckNow = viewModel::checkForUpdates,
                onRetrySlots = { viewModel.refreshSlots() },
                onSelectSlot = viewModel::selectSlot,
                onConfirmBooking = viewModel::confirmSelectedBooking,
                onRefreshBooking = viewModel::refreshBooking,
                onCancelBooking = viewModel::cancelBooking,
                onDismissConfirmation = viewModel::dismissConfirmation,
                onWithdraw = viewModel::withdrawInquiry,
            )
        }
    }
}

/** Step showing exactly what will be shared, plus the form + consent action. */
@Composable
private fun ConsentGate(
    isSubmitting: Boolean,
    error: String?,
    prefillName: String,
    prefillEmail: String?,
    prefillPhone: String?,
    hasPrepSummary: Boolean,
    onConsentSubmit: (
        name: String,
        email: String?,
        phone: String?,
        message: String,
        shareSummary: Boolean,
    ) -> Unit,
) {
    var name by remember(prefillName) { mutableStateOf(prefillName) }
    var email by remember(prefillEmail) { mutableStateOf(prefillEmail.orEmpty()) }
    var phone by remember(prefillPhone) { mutableStateOf(prefillPhone.orEmpty()) }
    var message by remember { mutableStateOf("") }
    var shareSummary by remember(hasPrepSummary) { mutableStateOf(false) }
    var showConsentDialog by remember { mutableStateOf(false) }

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
        item {
            InfoBanner(
                text = "All document preparation stays on your device. Only the details you explicitly agree to below are sent.",
                containerColor = SoftEmeraldBg,
                contentColor = SoftEmeraldFg,
                icon = Icons.Outlined.Shield,
            )
        }

        item {
            TripGuardCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("Your details", style = MaterialTheme.typography.titleMedium, color = Navy)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Full name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Phone (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        label = { Text("Message (optional but helpful)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            TripGuardCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text("Preparation summary (optional)", style = MaterialTheme.typography.titleMedium, color = Navy)
                    Text(
                        if (hasPrepSummary) {
                            "If you choose to share it, the agency sees a short informational note about your local document preparation, such as how many documents still need attention. It is not a visa eligibility assessment."
                        } else {
                            "No preparation summary is available yet. Add documents in the checklist to build one."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = shareSummary && hasPrepSummary,
                            onCheckedChange = { shareSummary = it },
                            enabled = hasPrepSummary,
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(
                            "Include my preparation summary",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (hasPrepSummary) Navy else TextSecondary,
                        )
                    }
                }
            }
        }

        if (error != null) {
            item {
                InfoBanner(
                    text = error,
                    containerColor = SoftRedBg,
                    contentColor = SoftRedFg,
                    icon = Icons.Outlined.Info,
                )
            }
        }

        item {
            PrimaryButton(
                text = if (isSubmitting) "Sending…" else "Review before sending",
                onClick = { showConsentDialog = true },
                enabled = !isSubmitting && name.isNotBlank() && (email.isNotBlank() || phone.isNotBlank()),
            )
        }
    }

    if (showConsentDialog) {
        ConsentDialog(
            name = name,
            email = email.takeIf { it.isNotBlank() },
            phone = phone.takeIf { it.isNotBlank() },
            shareSummary = shareSummary,
            message = message,
            onDismiss = { showConsentDialog = false },
            onDecline = {
                // Explicit decline: nothing is sent and the dialog just closes.
                showConsentDialog = false
            },
            onConsent = {
                showConsentDialog = false
                // Consent is explicit here. Only now does the app transmit.
                onConsentSubmit(name, email.takeIf { it.isNotBlank() }, phone.takeIf { it.isNotBlank() }, message, shareSummary)
            },
        )
    }
}

/** Explicit consent dialog listing each shared data category. */
@Composable
private fun ConsentDialog(
    name: String,
    email: String?,
    phone: String?,
    message: String,
    shareSummary: Boolean,
    onDismiss: () -> Unit,
    onDecline: () -> Unit,
    onConsent: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Consent to share") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    "By confirming, you agree the agency may store and use these details:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val lines = buildList {
                    add("• Your name: $name")
                    if (email != null) add("• Email: $email")
                    if (phone != null) add("• Phone: $phone")
                    add("• Selected visa route")
                    if (message.isNotBlank()) add("• Your inquiry message")
                    if (shareSummary) {
                        add("• Preparation summary (informational counts only)")
                    } else {
                        add("• No preparation summary will be shared")
                    }
                    add("")
                    add("Your documents, photos and any data extracted from them stay on your device and are never sent.")
                }
                lines.forEach { line ->
                    if (line.isEmpty()) {
                        Spacer(Modifier.height(Spacing.xs))
                    } else {
                        Text(line, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConsent) { Text("I consent — send") }
        },
        dismissButton = {
            TextButton(onClick = onDecline) { Text("Cancel — do not send") }
        },
    )
}

// ---------------------------------------------------------------------------
// Status section (after inquiry submitted)
// ---------------------------------------------------------------------------

@Composable
private fun InquiryStatusSection(
    state: ContactAgencyUiState,
    onCheckNow: () -> Unit,
    onRetrySlots: () -> Unit,
    onSelectSlot: (Long) -> Unit,
    onConfirmBooking: () -> Unit,
    onRefreshBooking: () -> Unit,
    onCancelBooking: () -> Unit,
    onDismissConfirmation: () -> Unit,
    onWithdraw: () -> Unit,
) {
    val inquiry = state.retainedInquiry ?: return
    var showWithdrawConfirm by remember { mutableStateOf(false) }

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
        item {
            TrackingHeader(
                inquiry = inquiry,
                booking = state.retainedBooking,
                update = state.trackingUpdate,
                polledAt = state.trackingPolledAt,
                isTracking = state.isTracking,
                onCheckNow = onCheckNow,
            )
        }

        item {
            TripGuardCard {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Text("Inquiry reference", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Text(inquiry.reference, style = MaterialTheme.typography.titleMedium, color = Navy, fontWeight = FontWeight.Bold)
                    Text(
                        "Status: ${inquiry.statusLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                    Text(
                        "Last updated ${formatAgencyDateTime(inquiry.updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
            }
        }

        state.slotsError?.let { error ->
            item {
                InfoBanner(
                    text = "Could not load appointment slots: $error",
                    containerColor = SoftRedBg,
                    contentColor = SoftRedFg,
                    icon = Icons.Outlined.Info,
                )
                Spacer(Modifier.height(Spacing.xs))
                SecondaryButton(text = "Retry loading slots", onClick = onRetrySlots)
            }
        }

        if (state.retainedBooking == null) {
            item {
                Text(
                    "AVAILABLE APPOINTMENT TIMES",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (state.isLoadingSlots) {
                item { LoadingState(message = "Loading appointment times…") }
            } else if (state.retainedSlots.isEmpty() && state.slotsError == null) {
                item {
                    InfoBanner(
                        text = "No appointment times offered yet. This page checks automatically — new times will appear here the moment the agency publishes them.",
                        containerColor = SoftSlateBg,
                        contentColor = SoftSlateFg,
                        icon = Icons.Outlined.Event,
                    )
                }
            } else {
                items(state.retainedSlots, key = { it.id }) { slot ->
                    SlotRow(
                        slot = slot,
                        selected = state.selectedSlotId == slot.id,
                        onSelect = { onSelectSlot(slot.id) },
                    )
                }
                item {
                    PrimaryButton(
                        text = if (state.isConfirmingBooking) "Confirming…" else "Review and confirm selected time",
                        onClick = onConfirmBooking,
                        enabled = state.selectedSlotId != null && !state.isConfirmingBooking,
                    )
                }
                state.bookingError?.let { err ->
                    item {
                        InfoBanner(
                            text = err,
                            containerColor = SoftRedBg,
                            contentColor = SoftRedFg,
                            icon = Icons.Outlined.Info,
                        )
                    }
                }
            }
        } else {
            items(listOf(state.retainedBooking), key = { it.reference }) { booking ->
                BookingCard(
                    booking = booking,
                    isCancelling = state.isCancelling,
                    cancelError = state.cancelError,
                    onRefresh = onRefreshBooking,
                    onCancel = onCancelBooking,
                )
            }
        }

        item {
            InfoBanner(
                text = "Times are shown in the agency's time zone.",
                containerColor = SoftSlateBg,
                contentColor = SoftSlateFg,
                icon = Icons.Outlined.Info,
            )
        }

        item {
            if (state.isWithdrawing) {
                LoadingState(message = "Closing inquiry…")
            } else {
                TextButton(
                    onClick = { showWithdrawConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Close this inquiry",
                        color = SoftRedFg,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    if (showWithdrawConfirm) {
        AlertDialog(
            onDismissRequest = { showWithdrawConfirm = false },
            title = { Text("Close this inquiry?") },
            text = {
                Text(
                    "The agency will no longer see ${inquiry.reference}. You can send a new " +
                        "inquiry afterwards.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWithdrawConfirm = false
                        onWithdraw()
                    },
                ) {
                    Text("Close inquiry", color = SoftRedFg)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWithdrawConfirm = false }) {
                    Text("Keep it")
                }
            },
        )
    }

    if (state.showBookingConfirmation && state.retainedBooking != null) {
        BookingConfirmationDialog(
            booking = state.retainedBooking,
            onDismiss = onDismissConfirmation,
        )
    }
}

@Composable
private fun TrackingHeader(
    inquiry: AgencyInquiryUi,
    booking: AgencyBookingUi?,
    update: String?,
    polledAt: String?,
    isTracking: Boolean,
    onCheckNow: () -> Unit,
) {
    val currentStep = when {
        booking != null || inquiry.status == "BOOKED" -> 4
        inquiry.status == "SLOT_OFFERED" -> 3
        inquiry.status == "CLOSED" -> 2
        else -> 2
    }
    val steps = listOf("Sent", "Under review", "Times offered", "Confirmed")

    TripGuardCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Tracking your inquiry", style = MaterialTheme.typography.titleMedium, color = Navy)
                    Text(
                        if (isTracking) "Updates automatically while you're here" else "Use 'Check now' to refresh",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                    )
                }
                StatusChip(
                    text = if (isTracking) "LIVE" else "PAUSED",
                    background = SoftEmeraldBg,
                    foreground = SoftEmeraldFg,
                )
            }

            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                steps.forEachIndexed { index, label ->
                    val reached = index < currentStep
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    if (reached) Navy else SoftSlateFg,
                                    CircleShape,
                                ),
                        )
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            color = if (reached) Navy else TextSecondary,
                        )
                    }
                }
            }

            if (update != null) {
                InfoBanner(
                    text = "Latest update from your agency:\n$update",
                    containerColor = SoftEmeraldBg,
                    contentColor = SoftEmeraldFg,
                    icon = Icons.Outlined.Mail,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(
                    text = "Check for updates",
                    onClick = onCheckNow,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.md))
                Text(
                    polledAt?.let { "Checked ${formatAgencyDateTime(it)}" } ?: "Not checked yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun SlotRow(
    slot: AgencySlotUi,
    selected: Boolean,
    onSelect: () -> Unit,
) {
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
        Row(modifier = Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect)
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    formatAgencyDateTime(slot.startAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Navy,
                    fontWeight = FontWeight.Medium,
                )
                Text(slot.location, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                slot.notes?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            }
            val (bg, fg) = if (slot.remainingCapacity <= 1) {
                SoftAmberBg to SoftAmberFg
            } else {
                SoftEmeraldBg to SoftEmeraldFg
            }
            StatusChip(
                text = "${slot.remainingCapacity} left",
                background = bg,
                foreground = fg,
            )
        }
    }
}

@Composable
private fun BookingCard(
    booking: AgencyBookingUi,
    isCancelling: Boolean,
    cancelError: String?,
    onRefresh: () -> Unit,
    onCancel: () -> Unit,
) {
    TripGuardCard {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    booking.statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = Navy,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                booking.reference,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                formatAgencyDateTime(booking.slotStartAt),
                style = MaterialTheme.typography.bodyMedium,
                color = Navy,
            )
            booking.slotTimezone?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            booking.slotLocation?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Spacer(Modifier.height(Spacing.sm))

            cancelError?.let {
                InfoBanner(
                    text = it,
                    containerColor = SoftRedBg,
                    contentColor = SoftRedFg,
                    icon = Icons.Outlined.Info,
                )
                Spacer(Modifier.height(Spacing.xs))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SecondaryButton(
                    text = "Refresh status",
                    onClick = onRefresh,
                    modifier = Modifier.weight(1f),
                )
                if (booking.status == "CONFIRMED") {
                    SecondaryButton(
                        text = if (isCancelling) "Cancelling…" else "Cancel",
                        onClick = onCancel,
                        enabled = !isCancelling,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BookingConfirmationDialog(booking: AgencyBookingUi, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Booking confirmed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(booking.reference, fontWeight = FontWeight.Bold, color = Navy)
                Text(formatAgencyDateTime(booking.slotStartAt), color = Navy)
                booking.slotTimezone?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                booking.slotLocation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    "The appointment syncs to the agency instantly. Use 'Refresh status' on the previous screen to check the latest state.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
