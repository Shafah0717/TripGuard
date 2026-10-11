package com.tripguard.app

import com.tripguard.app.data.dataset.AssetDatasetSource
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.Application
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.DocumentUpload
import com.tripguard.app.domain.model.EvaluationOutcome
import com.tripguard.app.domain.model.ExtractedField
import com.tripguard.app.domain.model.ExtractionResult
import com.tripguard.app.domain.model.OcrMethod
import com.tripguard.app.domain.model.PhotoMeasurements
import com.tripguard.app.domain.model.RequirementApplicability
import com.tripguard.app.domain.model.RuleVerification
import com.tripguard.app.domain.model.VisaCatalog
import com.tripguard.app.domain.model.VisaType
import com.tripguard.app.domain.rules.PhotoRequirementEvaluator
import com.tripguard.app.domain.rules.RequirementEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Evaluates requirements from the *actual* bundled dataset. Every expectation is derived from
 * what the JSON declares, so the tests fail if the app ever starts inventing passes.
 */
class Phase5RequirementEvaluationTest {

    private lateinit var catalog: VisaCatalog

    @Before
    fun setUp() {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val source = AssetDatasetSource(context = dummyContext)
        val json = File("src/main/assets/tripguard_uae_visa_requirements_indian_passports_v1.0.1.json").readText()
        catalog = (source.parse(json) as AppResult.Success).data
    }

    private fun visa(id: String): VisaType = catalog.visaTypes.first { it.id == id }

    private fun app(visa: VisaType, documents: Map<String, DocumentUpload> = emptyMap()): Application =
        Application(
            id = "app_test",
            reference = "TG-TEST",
            visaTypeId = visa.id,
            visaTypeName = visa.displayName,
            createdAt = 1_000L,
            updatedAt = 1_000L,
            documents = documents,
        )

    private fun extraction(vararg pairs: Pair<String, String>): ExtractionResult = ExtractionResult(
        fields = pairs.map { (key, value) -> ExtractedField(key = key, label = key, value = value, confidence = 0.9f) },
        processedAt = 2_000L,
        provider = "mlkit",
        overallConfidence = 0.9f,
        method = OcrMethod.ML_KIT_OCR,
        rawText = null,
        pageCount = 1,
    )

    private fun uploaded(
        id: String,
        state: DocumentState = DocumentState.EXTRACTED,
        extraction: ExtractionResult? = null,
        imageValidation: com.tripguard.app.domain.model.ImageValidation? = null,
    ) = DocumentUpload(
        documentId = id,
        documentName = id,
        state = state,
        localPath = "/tmp/$id.jpg",
        fileName = "$id.jpg",
        fileSizeBytes = 1000L,
        extraction = extraction,
        imageValidation = imageValidation,
    )

    @Test
    fun missingEvidenceIsReportedMissingForEachRequiredDocument() {
        val visa = visa("tourist_30_day_single_entry")
        val results = RequirementEvaluator.evaluate(catalog, visa, app(visa))

        val requiredIds = visa.documents.map { it.id }.toSet()
        assertEquals(requiredIds, results.map { it.requirementId }.toSet())
        results.forEach {
            assertFalse("Nothing was uploaded, so ${it.requirementId} cannot hold evidence", it.evidenceFound)
            assertEquals(EvaluationOutcome.MISSING, it.outcome)
        }
    }

    @Test
    fun uploadedButUnreadablePassportIsNotSatisfied() {
        val visa = visa("tourist_30_day_single_entry")
        val documents = mapOf(
            "passport_copy" to uploaded("passport_copy", state = DocumentState.NEEDS_REVIEW, extraction = null),
        )
        val result = RequirementEvaluator.evaluate(catalog, visa, app(visa, documents))
            .first { it.requirementId == "passport_copy" }

        assertTrue(result.evidenceFound)
        assertEquals(EvaluationOutcome.COULD_NOT_VERIFY, result.outcome)
        assertFalse(result.outcome == EvaluationOutcome.SATISFIED)
    }

    @Test
    fun passportExpiryRuleIsWithheldBecauseDatasetMarksHumanReview() {
        val visa = visa("tourist_30_day_single_entry")
        val documents = mapOf(
            "passport_copy" to uploaded(
                "passport_copy",
                extraction = extraction("expiry_date" to "2030-12-31", "passport_number" to "Z1234567"),
            ),
        )
        val result = RequirementEvaluator.evaluate(catalog, visa, app(visa, documents))
            .first { it.requirementId == "passport_copy" }

        // The dataset's passport_expiry_check has human_review_required = true, so no automatic pass.
        assertEquals(RuleVerification.UNVERIFIED, result.ruleVerification)
        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, result.outcome)
        assertFalse(result.outcome == EvaluationOutcome.SATISFIED)
    }

    @Test
    fun personalPhotoWithoutRulesIsNeverSatisfied() {
        val visa = visa("tourist_30_day_single_entry")
        val photoRequirement = visa.documents.first { it.id == "personal_photo" }
        val validation = PhotoRequirementEvaluator.evaluate(
            photoRequirement,
            PhotoMeasurements(600, 800, 50_000L, decodable = true, mimeType = "image/jpeg", extension = "jpg"),
        )

        // Measured but unconfigured.
        val measured = RequirementEvaluator.evaluate(
            catalog,
            visa,
            app(visa, mapOf("personal_photo" to uploaded("personal_photo", imageValidation = validation))),
        ).first { it.requirementId == "personal_photo" }
        assertEquals(EvaluationOutcome.RULE_UNVERIFIED, measured.outcome)
        assertEquals(RuleVerification.NO_RULE, measured.ruleVerification)
        assertTrue(measured.extractedValue!!.contains("600"))

        // Present but not even measured yet.
        val unmeasured = RequirementEvaluator.evaluate(
            catalog,
            visa,
            app(visa, mapOf("personal_photo" to uploaded("personal_photo", imageValidation = null))),
        ).first { it.requirementId == "personal_photo" }
        assertEquals(EvaluationOutcome.COULD_NOT_VERIFY, unmeasured.outcome)
    }

    @Test
    fun forbiddenFinancialMachineCheckBecomesManualReviewButSurfacesValue() {
        val visa = visa("tourist_5_year_multiple_entry")
        val documents = mapOf(
            "bank_statement" to uploaded(
                "bank_statement",
                extraction = extraction("closing_balance" to "USD 15,450.00", "statement_period" to "2026-01-01 to 2026-06-30"),
            ),
        )
        val result = RequirementEvaluator.evaluate(catalog, visa, app(visa, documents))
            .first { it.requirementId == "bank_statement" }

        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, result.outcome)
        assertEquals(RuleVerification.UNVERIFIED, result.ruleVerification)
        assertEquals("USD 15,450.00", result.extractedValue)
    }

    @Test
    fun outOfScopeConditionalDocumentIsExcluded() {
        val visa = visa("tourist_30_day_single_entry")
        val results = RequirementEvaluator.evaluate(catalog, visa, app(visa))
        assertFalse(
            "The nationality-specific identity document is not applicable to the Indian scope",
            results.any { it.requirementId == "national_identity_document_for_specific_nationalities" },
        )
    }

    @Test
    fun routeSpecificConditionalDocumentIsShownAsUnverified() {
        val visa = visa("tourist_5_year_multiple_entry")
        val result = RequirementEvaluator.evaluate(catalog, visa, app(visa))
            .firstOrNull { it.requirementId == "proof_of_residence_during_stay" }
        assertNotNull("Route-specific conditional documents must be surfaced", result)
        assertEquals(RuleVerification.UNVERIFIED, result!!.ruleVerification)
        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, result.outcome)
        assertEquals(RequirementApplicability.CONDITIONAL, result.applicability)
    }

    @Test
    fun documentWithNoMachineRuleNeverBecomesSatisfied() {
        val visa = visa("tourist_30_day_single_entry")
        val documents = mapOf(
            "travel_ticket" to uploaded("travel_ticket", extraction = extraction("travel_date" to "2026-08-01")),
        )
        val result = RequirementEvaluator.evaluate(catalog, visa, app(visa, documents))
            .first { it.requirementId == "travel_ticket" }
        assertFalse(result.outcome == EvaluationOutcome.SATISFIED)
        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, result.outcome)
    }
}
