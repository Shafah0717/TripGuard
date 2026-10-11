package com.tripguard.app

import com.tripguard.app.data.remote.ConsentPayloadDto
import com.tripguard.app.data.remote.CreateBookingRequestDto
import com.tripguard.app.data.remote.CreateInquiryRequestDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 7 — agency-assistance flow. These tests pin the wire contract and,
 * above all, the privacy boundary: nothing document-shaped may ever appear in
 * an outgoing agency payload.
 */
class Phase7AgencyAssistanceTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `inquiry request carries only consented fields - no document data`() {
        val request = CreateInquiryRequestDto(
            travellerName = "Test Traveller",
            contactEmail = "test@example.com",
            contactPhone = null,
            visaRoute = "tourist_30_day_single_entry",
            message = "Please share available appointment times.",
            prepSummary = null,
            missingDocCount = 2,
            issueCount = 1,
            includePrepSummary = false,
            consent = ConsentPayloadDto(
                version = "1.0",
                categories = listOf("CONTACT_DETAILS", "INQUIRY_DETAILS"),
            ),
        )

        // Serialise with the same settings the production client uses.
        val payload = json.encodeToString(CreateInquiryRequestDto.serializer(), request)

        // The privacy boundary: none of these substrings may ever appear in an
        // agency payload.
        for (banned in listOf(
            "passport", "photo", "ticket", "insurance", "bank",
            "ocr", "raw_text", "file_path", "local_path", ".pdf", ".jpg", ".png",
            "full_name", "expiry_date", "date_of_birth",
        )) {
            assertFalse("Agency payload must not mention '$banned'", payload.lowercase().contains(banned))
        }

        // Only consented categories are transmitted.
        assertTrue(payload.contains("\"contactEmail\""))
        assertTrue(payload.contains("\"consent\""))
        assertTrue(payload.contains("\"INQUIRY_DETAILS\""))
        assertFalse("Summary must not be included without consent", payload.contains("prepSummary"))
        assertFalse("include_prep_summary must be false when no summary is consented", request.includePrepSummary)
    }

    @Test
    fun `booking request fields`() {
        val request = CreateBookingRequestDto(
            travellersToken = "T0K3N",
            slotId = 12,
            idempotencyKey = "retry-safe-key",
        )
        assertTrue(request.slotId == 12L)
        assertEquals("retry-safe-key", request.idempotencyKey)
        assertEquals("T0K3N", request.travellersToken)
    }
}
