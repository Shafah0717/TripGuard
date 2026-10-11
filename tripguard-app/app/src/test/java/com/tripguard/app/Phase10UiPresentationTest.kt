package com.tripguard.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Image

import com.tripguard.app.domain.model.DocumentPresence
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.DocumentUpload
import com.tripguard.app.domain.model.EvaluationOutcome
import com.tripguard.app.domain.model.EvidenceField
import com.tripguard.app.domain.model.ExtractedField
import com.tripguard.app.domain.model.ExtractionResult
import com.tripguard.app.domain.model.ExtractionStatus
import com.tripguard.app.domain.model.OcrMethod
import com.tripguard.app.domain.model.RequirementApplicability
import com.tripguard.app.domain.model.RequirementEvaluation
import com.tripguard.app.domain.model.RuleVerification
import com.tripguard.app.domain.rules.EvidenceExplanation
import com.tripguard.app.ui.screens.common.DocumentSummaryFactory
import com.tripguard.app.ui.screens.common.documentIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 10 — UI presentation summaries. These tests pin down the compact, honest strings shown by
 * the redesigned document list, extraction, comparison and "preparation summary" screens. They
 * prove the summaries derive from persisted runtime data (document state + extracted fields), that
 * no overview or status label ever implies "this document is valid/verified", and that ISO dates
 * are the only values reformatted for display.
 */
class Phase10UiPresentationTest {

    private fun field(key: String, label: String, value: String) = ExtractedField(
        key = key,
        label = label,
        value = value,
    )

    private fun passportUpload(
        state: DocumentState = DocumentState.EXTRACTED,
        fields: List<ExtractedField> = listOf(
            field("full_name", "Full name", "AARAV DEMO SAMPLE"),
            field("passport_number", "Passport number", "TEST-ONLY-000000"),
            field("expiry_date", "Expiry date", "2032-01-10"),
        ),
    ) = DocumentUpload(
        documentId = "passport_main",
        documentName = "Passport",
        state = state,
        localPath = "file:///tmp/passport.pdf",
        extraction = ExtractionResult(
            fields = fields,
            processedAt = 0L,
            method = OcrMethod.PDF_TEXT_EXTRACTION,
        ),
    )

    @Test
    fun compactStatusLabelsNeverClaimAValidResult() {
        assertEquals("Missing", DocumentSummaryFactory.statusLabel(DocumentState.NOT_UPLOADED).label)
        listOf(DocumentState.UPLOADING, DocumentState.UPLOADED, DocumentState.PROCESSING).forEach {
            assertEquals("Processing", DocumentSummaryFactory.statusLabel(it).label)
        }
        assertEquals("Could not read", DocumentSummaryFactory.statusLabel(DocumentState.UPLOAD_FAILED).label)
        assertEquals("Extracted", DocumentSummaryFactory.statusLabel(DocumentState.EXTRACTED).label)
        assertEquals("Needs review", DocumentSummaryFactory.statusLabel(DocumentState.NEEDS_REVIEW).label)
        assertEquals("Needs review", DocumentSummaryFactory.statusLabel(DocumentState.POTENTIAL_ISSUE).label)
        assertEquals("Accepted", DocumentSummaryFactory.statusLabel(DocumentState.ACCEPTED).label)

        listOf(
            DocumentState.NOT_UPLOADED,
            DocumentState.UPLOADING,
            DocumentState.UPLOADED,
            DocumentState.PROCESSING,
            DocumentState.UPLOAD_FAILED,
            DocumentState.EXTRACTED,
            DocumentState.NEEDS_REVIEW,
            DocumentState.POTENTIAL_ISSUE,
            DocumentState.ACCEPTED,
        ).forEach { state ->
            assertFalse(
                "status for $state must never claim the document is valid/verified",
                DocumentSummaryFactory.statusLabel(state).label.contains("valid", ignoreCase = true),
            )
            assertEquals(
                "status for $state must never claim the document is verified",
                false,
                DocumentSummaryFactory.statusLabel(state).label.contains("verified", ignoreCase = true),
            )
        }
    }

    @Test
    fun resultLinePrefersTheMostUsefulDetectedFieldAndFormatsIsoDates() {
        val summary = DocumentSummaryFactory.summarize(passportUpload())
        assertEquals("Extracted", summary.statusLabel)
        assertEquals("Expiry date: 10 Jan 2032", summary.resultLine)
        assertEquals("Expiry date" to "10 Jan 2032", summary.headline)
    }

    @Test
    fun headlineReturnsNullWhenEveryValueIsBlank() {
        val blank = passportUpload(fields = listOf(field("expiry_date", "Expiry date", "")))
        assertNull(DocumentSummaryFactory.headlineValue(blank.extraction!!.fields))
        val summary = DocumentSummaryFactory.summarize(blank)
        assertEquals("Read on device · no fields detected", summary.resultLine)
    }

    @Test
    fun aDocumentWithoutAFileIsNeverShownAsExtracted() {
        val notUploaded = passportUpload(state = DocumentState.NOT_UPLOADED).copy(localPath = null)
        val summary = DocumentSummaryFactory.summarize(notUploaded)
        assertEquals("Missing", summary.statusLabel)
        assertEquals("Not uploaded yet", summary.resultLine)
    }

    @Test
    fun displayFormattingOnlyReformatsIsoDatesNeverGuesses() {
        assertEquals("10 Jan 2032", DocumentSummaryFactory.formatDisplayValue("2032-01-10"))
        assertEquals("USD 5,250.00", DocumentSummaryFactory.formatDisplayValue("USD 5,250.00"))
        assertEquals("12/03/2026", DocumentSummaryFactory.formatDisplayValue("12/03/2026"))
        assertEquals("25 Dec 2035", DocumentSummaryFactory.formatDisplayValue("2035-12-25"))
    }

    @Test
    fun photographResultComesFromMeasuredDimensionsOnly() {
        val photo = DocumentUpload(
            documentId = "personal_photograph",
            documentName = "Personal photograph",
            state = DocumentState.EXTRACTED,
            localPath = "file:///tmp/photo.jpg",
            imageValidation = com.tripguard.app.domain.model.ImageValidation(
                measurements = com.tripguard.app.domain.model.PhotoMeasurements(
                    widthPx = 600,
                    heightPx = 750,
                    sizeBytes = 204800,
                    decodable = true,
                ),
                checks = emptyList(),
                summaryOutcome = EvaluationOutcome.MANUAL_REVIEW_REQUIRED,
                reason = "The dataset configures no automated decision for photographs.",
                validatedAt = 0L,
            ),
        )
        val summary = DocumentSummaryFactory.summarize(photo)
        assertEquals("Extracted", summary.statusLabel)
        assertEquals("Image checked · 600 × 750 px · 4:5", summary.resultLine)
    }

    @Test
    fun shortReasonIsHonestForEachOutcome() {
        assertEquals(
            "Matches the configured rule from the extracted values.",
            EvidenceExplanation.shortReason(evaluation(EvaluationOutcome.SATISFIED, RuleVerification.VERIFIED)),
        )
        assertTrue(
            EvidenceExplanation.shortReason(evaluation(EvaluationOutcome.MISSING, RuleVerification.NO_RULE))
                .contains("has not been supplied yet"),
        )
        assertTrue(
            EvidenceExplanation.shortReason(evaluation(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, RuleVerification.UNVERIFIED))
                .contains("a human reviewer decides"),
        )
        assertTrue(
            EvidenceExplanation.shortReason(evaluation(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, RuleVerification.NO_RULE))
                .contains("a human reviewer decides"),
        )
        assertTrue(
            EvidenceExplanation.shortReason(evaluation(EvaluationOutcome.COULD_NOT_VERIFY, RuleVerification.UNVERIFIED))
                .contains("reliable evidence"),
        )
        assertFalse(
            "short reason must never claim satisfaction for a manual-review outcome",
            EvidenceExplanation.shortReason(
                evaluation(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, RuleVerification.UNVERIFIED),
            ).contains("Matches", ignoreCase = true),
        )
    }

    @Test
    fun documentIconsMapToTheirCategories() {
        assertEquals(Icons.Outlined.Image, documentIcon("passport_photograph"))
        assertEquals(Icons.Outlined.Badge, documentIcon("passport_main"))
        assertEquals(Icons.Outlined.Flight, documentIcon("travel_ticket"))
        assertEquals(Icons.Outlined.HealthAndSafety, documentIcon("medical_insurance"))
        assertEquals(Icons.Outlined.AccountBalance, documentIcon("bank_statement"))
    }

    private fun evaluation(outcome: EvaluationOutcome, verification: RuleVerification) = RequirementEvaluation(
        requirementId = "passport_expiry",
        requirementName = "Passport expiry",
        visaRouteId = "tourist_30_day_single_entry",
        visaRouteName = "Tourist (30-day single entry)",
        documentCategoryId = "passport_main",
        applicability = RequirementApplicability.REQUIRED,
        evidenceFound = true,
        evidenceSummary = "Document on file · PDF text extraction (1 field(s) with values)",
        ruleId = "rule_passport_expiry",
        ruleType = "passport_expiry_check",
        ruleVerification = verification,
        outcome = outcome,
        reason = "Full evaluation reason that stays visible in the expanded section.",
        nextAction = if (outcome == EvaluationOutcome.SATISFIED) "No action needed." else "Confirm with your agency.",
        sourceIds = listOf("src_1"),
        documentPresence = DocumentPresence.PRESENT,
        extractionStatus = ExtractionStatus.SUCCESS,
        extractionMethod = "PDF text extraction",
        evidenceFields = listOf(
            EvidenceField("expiry_date", "Expiry date", "2032-01-10", com.tripguard.app.domain.model.FieldEvidenceStatus.DETECTED),
        ),
    )
}