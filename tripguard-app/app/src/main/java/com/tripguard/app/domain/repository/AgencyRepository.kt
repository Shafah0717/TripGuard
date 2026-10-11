package com.tripguard.app.domain.repository

import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.AgencyBookingUi
import com.tripguard.app.domain.model.AgencyInquiryUi
import com.tripguard.app.domain.model.AgencySlotUi
import kotlinx.coroutines.flow.StateFlow

/**
 * Stored local agency state. Persisted so the traveller can revisit the
 * inquiry and booking after restarting the app. Contains only references and
 * consented contact data — never documents.
 */
data class LocalAgencyState(
    val inquiry: AgencyInquiryUi? = null,
    val booking: AgencyBookingUi? = null,
) 

/** Result of one [AgencyRepository.syncAgency] pass. */
data class AgencySyncResult(
    val inquiry: AgencyInquiryUi? = null,
    val slots: List<AgencySlotUi>? = null,
    val booking: AgencyBookingUi? = null,
    val message: String? = null,
)

interface AgencyRepository {
    /** Locally retained inquiry/booking, surviving app restarts. */
    val localState: StateFlow<LocalAgencyState>

    /** True while a background refresh found something new the traveller has not seen. */
    val hasUnseenAgencyUpdate: StateFlow<Boolean>

    /** Clears the unseen-update flag (called when the traveller opens the tracker). */
    fun markAgencyUpdateSeen()

    /**
     * Pulls the latest inquiry status plus either offered slots (before a booking)
     * or the booking (after one) and returns what, if anything, changed. Never
     * throws: a temporary backend failure is reported as a null result.
     */
    suspend fun syncAgency(): AgencySyncResult

    /**
     * Submits a consented inquiry. The caller must have shown the consent screen
     * and obtained explicit confirmation; the repository merely transmits what
     * the user agreed to (contact details, visa route, message and optionally
     * the aggregate preparation summary).
     */
    suspend fun submitInquiry(
        travellerName: String,
        contactEmail: String?,
        contactPhone: String?,
        visaRoute: String,
        message: String,
        prepSummary: String?,
        missingDocCount: Int,
        issueCount: Int,
    ): AppResult<AgencyInquiryUi>

    /** Published, currently bookable slots from the agency. */
    suspend fun fetchAvailableSlots(): AppResult<List<AgencySlotUi>>

    /** Latest inquiry status (reference, status, timestamps) from the backend. */
    suspend fun refreshInquiry(): AppResult<AgencyInquiryUi>

    /**
     * Confirms a booking on [slotId]. [idempotencyKey] must be stable across
     * retries of the same confirmation attempt so duplicate taps or network
     * retries never create two bookings.
     */
    suspend fun confirmBooking(
        slotId: Long,
        idempotencyKey: String,
    ): AppResult<AgencyBookingUi>

    /** Latest booking state for the retained inquiry, from the backend. */
    suspend fun refreshBooking(): AppResult<AgencyBookingUi>

    /** Cancels the retained active booking, if the backend still allows it. */
    suspend fun cancelBooking(): AppResult<AgencyBookingUi>

    /**
     * Withdraws the retained inquiry from the backend, then clears local state
     * so the traveller is asked for a fresh inquiry next time. Best-effort: even
     * if the server is unreachable the local inquiry is still cleared.
     */
    suspend fun withdrawInquiry(): AppResult<Unit>

    /** Clears any locally retained inquiry/booking (e.g. after deletion). */
    suspend fun clearLocal()
}
