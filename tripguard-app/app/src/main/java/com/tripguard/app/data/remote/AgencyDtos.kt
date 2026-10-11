package com.tripguard.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for the TripGuard Agency API (inquiries / slots / bookings).
 *
 * PRIVACY BOUNDARY: these DTOs describe everything the agency backend may ever
 * receive from the app. There is deliberately no field for documents, file
 * paths, raw OCR text, or OCR-derived personal fields (passport number, dates
 * of birth, etc.). The optional preparation summary carries only aggregate
 * counts the traveller explicitly consents to share.
 */

// ---- Request bodies ----------------------------------------------------------

@Serializable
data class ConsentPayloadDto(
    val version: String = "1.0",
    val categories: List<String>,
)

@Serializable
data class CreateInquiryRequestDto(
    val travellerName: String,
    val contactEmail: String? = null,
    val contactPhone: String? = null,
    val visaRoute: String,
    val message: String,
    val prepSummary: String? = null,
    val missingDocCount: Int = 0,
    val issueCount: Int = 0,
    val includePrepSummary: Boolean = false,
    val consent: ConsentPayloadDto,
)

@Serializable
data class CreateBookingRequestDto(
    val travellersToken: String,
    val slotId: Long,
    val idempotencyKey: String,
)

// ---- Response bodies (API keys are snake_case) --------------------------------

@Serializable
data class InquiryCreatedDto(
    val id: Long,
    val reference: String,
    val status: String,
    @SerialName("traveller_token") val travellerToken: String,
)

@Serializable
data class InquiryStatusDto(
    val id: Long,
    val reference: String,
    val status: String,
    @SerialName("visa_route") val visaRoute: String,
    @SerialName("traveller_name") val travellerName: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class AgencySlotDto(
    val id: Long,
    val reference: String,
    /** Naive-UTC ISO datetime, e.g. "2026-11-20T09:00:00". */
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    @SerialName("timezone_name") val timezoneName: String,
    val location: String,
    val capacity: Int,
    val published: Boolean,
    val closed: Boolean,
    val notes: String? = null,
    @SerialName("inquiry_id") val inquiryId: Long? = null,
    @SerialName("inquiry_reference") val inquiryReference: String? = null,
    val status: String = "AVAILABLE",
    @SerialName("bookings_count") val bookingsCount: Int = 0,
    @SerialName("remaining_capacity") val remainingCapacity: Int = 0,
)

@Serializable
data class BookingDto(
    val id: Long,
    val reference: String,
    val status: String,
    @SerialName("inquiry_id") val inquiryId: Long,
    @SerialName("slot_id") val slotId: Long,
    @SerialName("created_at") val createdAt: String,
    @SerialName("cancelled_at") val cancelledAt: String? = null,
    @SerialName("agency_note") val agencyNote: String? = null,
    @SerialName("inquiry_reference") val inquiryReference: String = "",
    @SerialName("slot_start_at") val slotStartAt: String? = null,
    @SerialName("slot_end_at") val slotEndAt: String? = null,
    @SerialName("slot_timezone") val slotTimezone: String? = null,
    @SerialName("slot_location") val slotLocation: String? = null,
)
