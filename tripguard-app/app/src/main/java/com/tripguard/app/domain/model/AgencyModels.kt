package com.tripguard.app.domain.model

/**
 * Domain models for the agency-assistance flow. Kept separate from the core
 * visa-preparation models; these describe only consented contact data, slots
 * and bookings. No document or OCR-derived information appears here.
 */

/** The exact data categories shown on the consent screen, in display order. */
enum class ConsentCategory(val code: String, val description: String) {
    CONTACT("CONTACT_DETAILS", "Your name and chosen contact details (email or phone)"),
    INQUIRY("INQUIRY_DETAILS", "Your selected visa route and inquiry message"),
    PREP_SUMMARY("PREP_SUMMARY", "Optional preparation summary (counts of missing documents / flagged issues)"),
    ;

    companion object {
        /** Categories always included when the traveller consents. */
        val REQUIRED = listOf(CONTACT, INQUIRY)
    }
}

/** Current consent flow state for the contact-agency screen. */
enum class InquiryConsentState {
    NOT_REQUESTED,
    REVIEWING,
    GRANTED,
    DECLINED,
}

data class AgencyInquiryUi(
    val inquiryId: Long,
    val reference: String,
    val status: String,
    val visaRoute: String,
    val travellerName: String,
    val travellerToken: String,
    val createdAt: String,
    val updatedAt: String,
) {
    /** Human-readable status for chips; unknown statuses stay visible as-is. */
    val statusLabel: String
        get() = when (status) {
            "NEW" -> "Received by agency"
            "CONTACTED" -> "Agency contacted you"
            "SLOT_OFFERED" -> "Appointment slots offered"
            "BOOKED" -> "Appointment booked"
            "CLOSED" -> "Closed"
            else -> status
        }
}

data class AgencySlotUi(
    val id: Long,
    val reference: String,
    val startAt: String,
    val endAt: String,
    val timezoneName: String,
    val location: String,
    val notes: String?,
    val remainingCapacity: Int,
    val status: String,
) {
    val bookable: Boolean get() = status == "AVAILABLE"
}

data class AgencyBookingUi(
    val reference: String,
    val status: String,
    val inquiryReference: String,
    val slotStartAt: String?,
    val slotEndAt: String?,
    val slotTimezone: String?,
    val slotLocation: String?,
    val createdAt: String,
    val cancelledAt: String?,
    val agencyNote: String?,
) {
    val statusLabel: String
        get() = when (status) {
            "CONFIRMED" -> "Confirmed"
            "PENDING_CONFIRMATION" -> "Awaiting agency confirmation"
            "CANCELLED" -> "Cancelled"
            "COMPLETED" -> "Completed"
            else -> status
        }
}

/** Result of the submit step, distinct from local persistence. */
sealed interface InquirySubmission {
    data class Submitted(val created: AgencyInquiryUi) : InquirySubmission
    data object Declined : InquirySubmission
    data class Offline(val reason: String) : InquirySubmission
    data class Failed(val reason: String) : InquirySubmission
}
