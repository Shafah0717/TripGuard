package com.tripguard.app

import com.tripguard.app.domain.model.DocumentRequirement
import com.tripguard.app.domain.model.EvaluationOutcome
import com.tripguard.app.domain.model.PhotoMeasurements
import com.tripguard.app.domain.model.VerificationRule
import com.tripguard.app.domain.rules.DateNormalizer
import com.tripguard.app.domain.rules.DateParse
import com.tripguard.app.domain.rules.PhotoRequirementEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure, framework-free tests for the two rules that can be exercised without Android runtime:
 * photograph requirement evaluation and date normalisation. They assert the central promise
 * that no rule is invented and no ambiguous value is silently interpreted.
 */
class Phase4PhotographAndDateRulesTest {

    private fun photoRequirement(
        verificationRules: List<VerificationRule> = emptyList(),
        acceptedFormats: List<String> = emptyList(),
    ) = DocumentRequirement(
        id = "personal_photo",
        name = "Personal Photo",
        description = "A recent passport-style photograph.",
        requirementStatus = "required",
        applicabilityScope = null,
        applicabilityConditions = emptyList(),
        mandatory = true,
        specificationNotes = emptyList(),
        acceptedFormats = acceptedFormats,
        imageRequirements = emptyList(),
        minimumValidityDays = null,
        verificationNotes = emptyList(),
        verificationRules = verificationRules,
        evidenceNotes = emptyList(),
        sourceIds = emptyList(),
        humanReviewRequired = true,
    )

    private fun measurements(
        width: Int = 600,
        height: Int = 800,
        size: Long = 50_000L,
        decodable: Boolean = true,
        extension: String? = "jpg",
    ) = PhotoMeasurements(
        widthPx = width,
        heightPx = height,
        sizeBytes = size,
        decodable = decodable,
        mimeType = "image/jpeg",
        extension = extension,
    )

    @Test
    fun photoEvaluatorInventsNoRulesWhenDatasetConfiguresNone() {
        val result = PhotoRequirementEvaluator.evaluate(photoRequirement(), measurements())

        // The real dataset states no photo dimension / size rule, so nothing may be satisfied.
        assertEquals(EvaluationOutcome.RULE_UNVERIFIED, result.summaryOutcome)
        assertTrue("No configured check may pass without a dataset rule", result.checks.none { it.configured && it.outcome == EvaluationOutcome.SATISFIED })
        assertFalse(result.checks.any { it.configured })
    }

    @Test
    fun photoEvaluatorReportsMeasuredAspectRatioWithoutInventing() {
        val result = PhotoRequirementEvaluator.evaluate(photoRequirement(), measurements(width = 600, height = 800))
        assertEquals("3:4", result.measurements.aspectRatioLabel)
        assertEquals(600, result.measurements.widthPx)
        assertEquals(800, result.measurements.heightPx)
        assertTrue(result.measurements.isUsable)
    }

    @Test
    fun photoEvaluatorAppliesOnlyConfiguredBounds() {
        val rule = VerificationRule(
            id = "photo_dimensions",
            ruleType = "image_dimensions",
            description = "Photo must be at least 600x800.",
            parameters = mapOf("min_width_px" to "600", "min_height_px" to "800"),
            severity = "high",
            sourceIds = emptyList(),
            humanReviewRequired = false,
        )
        val ok = PhotoRequirementEvaluator.evaluate(photoRequirement(listOf(rule)), measurements(600, 800))
        assertEquals(EvaluationOutcome.SATISFIED, ok.summaryOutcome)

        val tooSmall = PhotoRequirementEvaluator.evaluate(photoRequirement(listOf(rule)), measurements(300, 400))
        assertEquals(EvaluationOutcome.POTENTIAL_ISSUE, tooSmall.summaryOutcome)
    }

    @Test
    fun photoEvaluatorWithholdsUnverifiedDimensionRule() {
        val rule = VerificationRule(
            id = "photo_dimensions",
            ruleType = "image_dimensions",
            description = "Dimensions stated but not verified.",
            parameters = mapOf("min_width_px" to "600"),
            severity = null,
            sourceIds = emptyList(),
            humanReviewRequired = true,
        )
        val result = PhotoRequirementEvaluator.evaluate(photoRequirement(listOf(rule)), measurements(1200, 1600))
        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, result.summaryOutcome)
        assertTrue(result.checks.none { it.outcome == EvaluationOutcome.SATISFIED })
    }

    @Test
    fun photoEvaluatorNeverFabricatesMeasurementsForUnreadableFile() {
        val result = PhotoRequirementEvaluator.evaluate(
            photoRequirement(),
            measurements(width = 0, height = 0, size = 12L, decodable = false, extension = "jpg"),
        )
        assertEquals(EvaluationOutcome.COULD_NOT_VERIFY, result.summaryOutcome)
        assertFalse(result.measurements.isUsable)
        assertEquals(0, result.measurements.widthPx)
        assertEquals(0, result.measurements.heightPx)
    }

    @Test
    fun dateNormalizerAcceptsUnambiguousIsoDates() {
        val parsed = DateNormalizer.normalize("2030-12-31")
        assertTrue(parsed is DateParse.Success)
        assertEquals("2030-12-31", (parsed as DateParse.Success).iso)
    }

    @Test
    fun dateNormalizerAcceptsDayGreaterThanTwelve() {
        val parsed = DateNormalizer.normalize("25/06/2026")
        assertTrue(parsed is DateParse.Success)
        assertEquals("2026-06-25", (parsed as DateParse.Success).iso)
    }

    @Test
    fun dateNormalizerFlagsAmbiguousDayMonthWithoutGuessing() {
        val parsed = DateNormalizer.normalize("05/06/2026")
        assertTrue("Ambiguous dates must not be interpreted", parsed is DateParse.Ambiguous)
    }

    @Test
    fun dateNormalizerFlagsTwoDigitYearsWithoutGuessing() {
        val parsed = DateNormalizer.normalize("15/06/26")
        assertTrue(parsed is DateParse.Ambiguous)
    }

    @Test
    fun dateNormalizerRejectsUnparseableInput() {
        assertTrue(DateNormalizer.normalize("not a date") is DateParse.Unparseable)
        assertTrue(DateNormalizer.normalize("2026-13-40") is DateParse.Unparseable)
        assertTrue(DateNormalizer.normalize("") is DateParse.Unparseable)
    }
}
