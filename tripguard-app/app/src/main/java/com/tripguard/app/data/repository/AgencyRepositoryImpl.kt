package com.tripguard.app.data.repository

import com.tripguard.app.data.local.AgencyBookingRecord
import com.tripguard.app.data.local.AgencyInquiryRecord
import com.tripguard.app.data.local.LocalStore
import com.tripguard.app.data.remote.AgencySlotDto
import com.tripguard.app.data.remote.BookingDto
import com.tripguard.app.data.remote.CreateBookingRequestDto
import com.tripguard.app.data.remote.CreateInquiryRequestDto
import com.tripguard.app.data.remote.ConsentPayloadDto
import com.tripguard.app.data.remote.TripGuardApi
import com.tripguard.app.domain.model.AppError
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.AgencyBookingUi
import com.tripguard.app.domain.model.AgencyInquiryUi
import com.tripguard.app.domain.model.AgencySlotUi
import com.tripguard.app.domain.model.ConsentCategory
import com.tripguard.app.domain.model.ErrorKind
import com.tripguard.app.domain.repository.AgencyRepository
import com.tripguard.app.domain.repository.AgencySyncResult
import com.tripguard.app.domain.repository.LocalAgencyState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Agency-assistance repository. Talks to the shared Agency API; persists only
 * the inquiry/booking references and consented display data so the traveller
 * can revisit them offline. Documents and OCR data never reach this layer.
 */
class AgencyRepositoryImpl(
    private val api: TripGuardApi,
    private val store: LocalStore,
    private val baseUrlProvider: () -> String,
) : AgencyRepository {

    private val _localState = MutableStateFlow(
        LocalAgencyState(
            inquiry = store.state.value.agencyInquiry?.toUi(),
            booking = store.state.value.agencyBooking?.toUi(),
        ),
    )
    override val localState: StateFlow<LocalAgencyState> = _localState.asStateFlow()

    private val _unseenUpdate = MutableStateFlow(false)
    override val hasUnseenAgencyUpdate: StateFlow<Boolean> = _unseenUpdate.asStateFlow()

    /** Slot ids from the last sync, used to notice freshly published times. */
    private var lastKnownSlotIds: Set<Long>? = null

    override fun markAgencyUpdateSeen() {
        _unseenUpdate.value = false
    }

    override suspend fun syncAgency(): AgencySyncResult {
        val state = _localState.value
        val inquiry = state.inquiry ?: return AgencySyncResult()
        val messages = mutableListOf<String>()

        val freshInquiry = when (val result = refreshInquiry()) {
            is AppResult.Success -> result.data.also { fresh ->
                if (fresh.status != state.inquiry.status) {
                    messages += "Your inquiry moved to ${fresh.statusLabel}."
                }
            }
            is AppResult.Failure -> state.inquiry
        }

        var slots: List<AgencySlotUi>? = null
        var booking: AgencyBookingUi? = null

        if (_localState.value.booking == null) {
            when (val result = fetchAvailableSlots()) {
                is AppResult.Success -> {
                    slots = result.data
                    val ids = slots.map { it.id }.toSet()
                    val known = lastKnownSlotIds
                    lastKnownSlotIds = ids
                    if (known != null && ids.isNotEmpty() && ids != known) {
                        messages += "New appointment times are available for you to book."
                    }
                }
                is AppResult.Failure -> Unit
            }
        } else {
            booking = when (val result = refreshBooking()) {
                is AppResult.Success -> result.data.also { fresh ->
                    if (state.booking == null || fresh.reference != state.booking.reference) {
                        messages += "Booking ${fresh.reference} is now ${fresh.statusLabel}."
                    } else if (fresh.status != state.booking.status) {
                        messages += "Your booking is now ${fresh.statusLabel}."
                    }
                }
                is AppResult.Failure -> state.booking
            }
        }

        val message = messages.joinToString("\n").ifBlank { null }
        if (message != null) _unseenUpdate.value = true
        return AgencySyncResult(inquiry = freshInquiry, slots = slots, booking = booking, message = message)
    }

    override suspend fun submitInquiry(
        travellerName: String,
        contactEmail: String?,
        contactPhone: String?,
        visaRoute: String,
        message: String,
        prepSummary: String?,
        missingDocCount: Int,
        issueCount: Int,
    ): AppResult<AgencyInquiryUi> {
        val includeSummary = prepSummary != null
        val categories = buildList {
            addAll(ConsentCategory.REQUIRED.map { it.code })
            if (includeSummary) add(ConsentCategory.PREP_SUMMARY.code)
        }
        val request = CreateInquiryRequestDto(
            travellerName = travellerName.trim(),
            contactEmail = contactEmail?.trim()?.takeIf { it.isNotEmpty() },
            contactPhone = contactPhone?.trim()?.takeIf { it.isNotEmpty() },
            visaRoute = visaRoute.trim(),
            message = message.trim(),
            prepSummary = prepSummary?.trim(),
            missingDocCount = missingDocCount,
            issueCount = issueCount,
            includePrepSummary = includeSummary,
            consent = ConsentPayloadDto(version = "1.0", categories = categories),
        )

        return when (val result = api.createAgencyInquiry(request)) {
            is AppResult.Failure -> AppResult.Failure(result.error)
            is AppResult.Success -> {
                val created = result.data
                val nowIso = java.time.Instant.now().toString()
                val ui = AgencyInquiryUi(
                    inquiryId = created.id,
                    reference = created.reference,
                    status = created.status,
                    visaRoute = request.visaRoute,
                    travellerName = request.travellerName,
                    travellerToken = created.travellerToken,
                    createdAt = nowIso,
                    updatedAt = nowIso,
                )
                persistInquiry(
                    AgencyInquiryRecord(
                        inquiryId = ui.inquiryId,
                        reference = ui.reference,
                        status = ui.status,
                        visaRoute = ui.visaRoute,
                        travellerName = ui.travellerName,
                        travellerToken = ui.travellerToken,
                        createdAt = ui.createdAt,
                        updatedAt = ui.updatedAt,
                    ),
                )
                AppResult.Success(ui)
            }
        }
    }

    override suspend fun fetchAvailableSlots(): AppResult<List<AgencySlotUi>> {
        val token = _localState.value.inquiry?.travellerToken
            ?: return AppResult.Failure(AppError(ErrorKind.NOT_FOUND, "No active inquiry."))
        return when (val result = api.fetchAgencySlots(token)) {
            is AppResult.Failure -> AppResult.Failure(result.error)
            is AppResult.Success ->
                AppResult.Success(
                    result.data
                        .filter { it.status == "AVAILABLE" }
                        .map { it.toUi() },
                )
        }
    }

    override suspend fun refreshInquiry(): AppResult<AgencyInquiryUi> {
        val token = _localState.value.inquiry?.travellerToken
            ?: return AppResult.Failure(AppError(ErrorKind.NOT_FOUND, "No active inquiry."))
        return when (val result = api.fetchAgencyInquiry(token)) {
            is AppResult.Failure -> {
                // The agency (or a previous device) removed this inquiry; drop
                // our copy so the traveller is asked for a fresh one.
                if (result.error.kind == ErrorKind.NOT_FOUND) clearLocal()
                AppResult.Failure(result.error)
            }
            is AppResult.Success -> {
                val dto = result.data
                val ui = AgencyInquiryUi(
                    inquiryId = dto.id,
                    reference = dto.reference,
                    status = dto.status,
                    visaRoute = dto.visaRoute,
                    travellerName = dto.travellerName,
                    travellerToken = token,
                    createdAt = dto.createdAt,
                    updatedAt = dto.updatedAt,
                )
                persistInquiry(
                    AgencyInquiryRecord(
                        inquiryId = ui.inquiryId,
                        reference = ui.reference,
                        status = ui.status,
                        visaRoute = ui.visaRoute,
                        travellerName = ui.travellerName,
                        travellerToken = ui.travellerToken,
                        createdAt = ui.createdAt,
                        updatedAt = ui.updatedAt,
                    ),
                )
                AppResult.Success(ui)
            }
        }
    }

    override suspend fun confirmBooking(
        slotId: Long,
        idempotencyKey: String,
    ): AppResult<AgencyBookingUi> {
        val state = _localState.value
        val token = state.inquiry?.travellerToken
            ?: return AppResult.Failure(AppError(ErrorKind.NOT_FOUND, "No active inquiry."))

        val key = idempotencyKey.ifBlank { UUID.randomUUID().toString() }
        val request = CreateBookingRequestDto(
            travellersToken = token,
            slotId = slotId,
            idempotencyKey = key,
        )
        return when (val result = api.createAgencyBooking(token, request)) {
            is AppResult.Failure -> AppResult.Failure(result.error)
            is AppResult.Success -> {
                persistBooking(result.data)
                AppResult.Success(result.data.toUi())
            }
        }
    }

    override suspend fun refreshBooking(): AppResult<AgencyBookingUi> {
        val state = _localState.value
        val token = state.inquiry?.travellerToken
            ?: return AppResult.Failure(AppError(ErrorKind.NOT_FOUND, "No active inquiry."))
        val reference = state.booking?.reference
            ?: return AppResult.Failure(AppError(ErrorKind.NOT_FOUND, "No booking to refresh."))

        return when (val result = api.fetchAgencyBooking(reference, token)) {
            is AppResult.Failure -> AppResult.Failure(result.error)
            is AppResult.Success -> {
                persistBooking(result.data)
                AppResult.Success(result.data.toUi())
            }
        }
    }

    override suspend fun cancelBooking(): AppResult<AgencyBookingUi> {
        val state = _localState.value
        val token = state.inquiry?.travellerToken
            ?: return AppResult.Failure(AppError(ErrorKind.NOT_FOUND, "No active inquiry."))
        val reference = state.booking?.reference
            ?: return AppResult.Failure(AppError(ErrorKind.NOT_FOUND, "No booking to cancel."))

        return when (val result = api.cancelAgencyBooking(reference, token)) {
            is AppResult.Failure -> AppResult.Failure(result.error)
            is AppResult.Success -> {
                persistBooking(result.data)
                AppResult.Success(result.data.toUi())
            }
        }
    }

    override suspend fun withdrawInquiry(): AppResult<Unit> {
        val token = _localState.value.inquiry?.travellerToken
            ?: return AppResult.Failure(AppError(ErrorKind.NOT_FOUND, "No active inquiry."))
        val result = api.withdrawInquiry(token)
        // Clear local state regardless: the traveller asked for a clean slate.
        clearLocal()
        return result
    }

    override suspend fun clearLocal() {
        store.update { it.copy(agencyInquiry = null, agencyBooking = null) }
        _localState.value = LocalAgencyState()
        _unseenUpdate.value = false
        lastKnownSlotIds = null
    }

    private suspend fun persistInquiry(record: AgencyInquiryRecord) {
        store.update { it.copy(agencyInquiry = record) }
        _localState.value = _localState.value.copy(inquiry = record.toUi())
    }

    private suspend fun persistBooking(record: BookingDto) {
        val stored = AgencyBookingRecord(
            reference = record.reference,
            status = record.status,
            inquiryReference = record.inquiryReference,
            slotStartAt = record.slotStartAt,
            slotEndAt = record.slotEndAt,
            slotTimezone = record.slotTimezone,
            slotLocation = record.slotLocation,
            createdAt = record.createdAt,
            cancelledAt = record.cancelledAt,
            agencyNote = record.agencyNote,
        )
        store.update { it.copy(agencyBooking = stored) }
        _localState.value = _localState.value.copy(booking = stored.toUi())
    }
}

private fun AgencyInquiryRecord.toUi(): AgencyInquiryUi = AgencyInquiryUi(
    inquiryId = inquiryId,
    reference = reference,
    status = status,
    visaRoute = visaRoute,
    travellerName = travellerName,
    travellerToken = travellerToken,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun AgencyBookingRecord.toUi(): AgencyBookingUi = AgencyBookingUi(
    reference = reference,
    status = status,
    inquiryReference = inquiryReference,
    slotStartAt = slotStartAt,
    slotEndAt = slotEndAt,
    slotTimezone = slotTimezone,
    slotLocation = slotLocation,
    createdAt = createdAt,
    cancelledAt = cancelledAt,
    agencyNote = agencyNote,
)

private fun BookingDto.toUi(): AgencyBookingUi = AgencyBookingUi(
    reference = reference,
    status = status,
    inquiryReference = inquiryReference,
    slotStartAt = slotStartAt,
    slotEndAt = slotEndAt,
    slotTimezone = slotTimezone,
    slotLocation = slotLocation,
    createdAt = createdAt,
    cancelledAt = cancelledAt,
    agencyNote = agencyNote,
)

private fun AgencySlotDto.toUi(): AgencySlotUi = AgencySlotUi(
    id = id,
    reference = reference,
    startAt = startAt,
    endAt = endAt,
    timezoneName = timezoneName,
    location = location,
    notes = notes,
    remainingCapacity = remainingCapacity,
    status = status,
)
