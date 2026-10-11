package com.tripguard.app

import com.tripguard.app.data.ocr.DocumentFieldParser
import com.tripguard.app.data.ocr.PdfTextExtractor
import com.tripguard.app.domain.model.OcrMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verifies the real, offline PDF pipeline against the supplied synthetic fixtures: selectable
 * text is extracted locally, structured fields are parsed from that actual text, and no value is
 * invented when a field is absent or a date is ambiguous.
 */
class Phase7PdfTextExtractionTest {

    private fun fixture(name: String): File {
        val file = File("src/test/resources/fixtures/$name")
        assertTrue("Missing fixture $name", file.exists())
        return file
    }

    private fun parsed(name: String, documentId: String): Map<String, String> {
        val result = PdfTextExtractor.extract(fixture(name))
        assertNotNull("Expected selectable text in $name", result)
        return DocumentFieldParser.parse(documentId, result!!.text, OcrMethod.PDF_TEXT_EXTRACTION)
            .associate { it.key to it.value }
    }

    // ---- extractor ----------------------------------------------------------

    @Test
    fun selectablePassportPdfIsExtractedLocally() {
        val result = PdfTextExtractor.extract(fixture("02_passport_copy_synthetic.pdf"))
        assertNotNull("The synthetic PDF has selectable text and must be extracted", result)
        assertTrue(result!!.text.contains("Full name"))
        assertTrue(result.text.contains("AARAV DEMO SAMPLE"))
        assertTrue(result.text.contains("Date of expiry"))
        assertEquals(1, result.pageCount)
    }

    @Test
    fun imageIsNotTreatedAsAPdf() {
        assertNull(PdfTextExtractor.extract(fixture("01_synthetic_personal_photo.png")))
    }

    // ---- fields -------------------------------------------------------------

    @Test
    fun passportFieldsAreParsedFromRealText() {
        val fields = parsed("02_passport_copy_synthetic.pdf", "passport_copy")
        assertEquals("AARAV DEMO SAMPLE", fields["full_name"])
        assertEquals("TEST-ONLY-000000", fields["passport_number"])
        assertTrue(fields["nationality"]!!.contains("INDIAN"))
        assertEquals("1998-02-14", fields["date_of_birth"])
        assertEquals("2024-01-10", fields["issue_date"])
        assertEquals("2032-01-10", fields["expiry_date"])
        assertEquals("FICTIONAL TEST AUTHORITY", fields["issuing_authority"])
    }

    @Test
    fun passportExpiryEdgeCaseIsParsed() {
        val fields = parsed("07_passport_expiry_edge_case_synthetic.pdf", "passport_copy")
        assertEquals("2027-02-15", fields["expiry_date"])
        assertEquals("TEST-ONLY-000001", fields["passport_number"])
    }

    @Test
    fun bankStatementFieldsAreParsedFromRealText() {
        val fields = parsed("05_bank_statement_6_months_synthetic.pdf", "bank_statement")
        assertEquals("AARAV DEMO SAMPLE", fields["account_holder"])
        assertEquals("TEST-ACCOUNT-0001", fields["account_number"])
        assertEquals("USD 5,250.00", fields["closing_balance"])
        assertTrue(fields["statement_period"]!!.contains("2026"))
    }

    @Test
    fun ticketFieldsAreParsedFromRealText() {
        val fields = parsed("03_return_ticket_synthetic.pdf", "travel_ticket")
        assertEquals("AARAV DEMO SAMPLE", fields["passenger_name"])
        assertEquals("DEMO-TRIP-4821", fields["booking_reference"])
        assertEquals("2026-12-15", fields["travel_date"])
        assertEquals("2027-01-05", fields["return_date"])
    }

    @Test
    fun insuranceFieldsAreParsedFromRealText() {
        val fields = parsed("04_medical_insurance_synthetic.pdf", "medical_insurance")
        assertEquals("AARAV DEMO SAMPLE", fields["insured_name"])
        assertEquals("DEMO-POLICY-2026-001", fields["policy_number"])
        assertEquals("2026-12-15", fields["coverage_start"])
        assertEquals("2027-01-05", fields["coverage_end"])
    }

    @Test
    fun accommodationFieldsAreParsedFromRealText() {
        val fields = parsed("06_accommodation_proof_conditional_synthetic.pdf", "proof_of_residence_during_stay")
        assertEquals("AARAV DEMO SAMPLE", fields["guest_name"])
        assertEquals("2026-12-15", fields["checkin_date"])
        assertEquals("2027-01-05", fields["checkout_date"])
        assertEquals("DEMO-STAY-2026-77", fields["reference"])
    }

    // ---- honesty guards -----------------------------------------------------

    @Test
    fun missingFieldIsReportedAsNotDetectedNotEmpty() {
        val result = PdfTextExtractor.extract(fixture("02_passport_copy_synthetic.pdf"))!!
        val withoutExpiry = result.text.replace("Date of expiry", "Unrelated heading")
            .replace("10 January 2032", "")
        val fields = DocumentFieldParser.parse("passport_copy", withoutExpiry, OcrMethod.PDF_TEXT_EXTRACTION)
        val expiry = fields.first { it.key == "expiry_date" }
        assertTrue("Absent fields must be blank, never invented", expiry.value.isBlank())
    }

    @Test
    fun ambiguousDateIsFlaggedForReviewAndNotInterpreted() {
        val text = "Full name\nAARAV DEMO SAMPLE\nDate of expiry\n05/06/2026"
        val fields = DocumentFieldParser.parse("passport_copy", text, OcrMethod.PDF_TEXT_EXTRACTION)
        val expiry = fields.first { it.key == "expiry_date" }
        assertEquals("05/06/2026", expiry.value)
        assertTrue("Ambigous dates must be flagged", expiry.warning?.contains("Needs review") == true)
    }

    @Test
    fun emptyTextProducesNoDetectedValues() {
        val fields = DocumentFieldParser.parse("passport_copy", "", OcrMethod.PDF_TEXT_EXTRACTION)
        assertTrue(fields.isNotEmpty())
        assertTrue("No field may be populated from empty text", fields.all { it.value.isBlank() })
    }

    @Test
    fun extractionMethodIsRecordedOnEveryField() {
        val result = PdfTextExtractor.extract(fixture("02_passport_copy_synthetic.pdf"))!!
        val fields = DocumentFieldParser.parse("passport_copy", result.text, OcrMethod.PDF_TEXT_EXTRACTION)
        assertTrue(fields.all { it.method == OcrMethod.PDF_TEXT_EXTRACTION })
        assertFalse(fields.isEmpty())
    }
}
