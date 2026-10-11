package com.tripguard.app

import com.tripguard.app.data.dataset.AssetDatasetSource
import com.tripguard.app.data.document.DocumentFileStore
import com.tripguard.app.data.local.LocalStore
import com.tripguard.app.data.ocr.DocumentFieldParser
import com.tripguard.app.data.ocr.LocalOcrEngine
import com.tripguard.app.data.ocr.PdfTextExtractor
import com.tripguard.app.data.repository.ApplicationRepositoryImpl
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.Application
import com.tripguard.app.domain.model.DocumentPolicy
import com.tripguard.app.domain.model.DocumentPresence
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.DocumentUpload
import com.tripguard.app.domain.model.EvaluationOutcome
import com.tripguard.app.domain.model.ExtractedField
import com.tripguard.app.domain.model.ExtractionResult
import com.tripguard.app.domain.model.ExtractionStatus
import com.tripguard.app.domain.model.OcrMethod
import com.tripguard.app.domain.model.PhotoMeasurements
import com.tripguard.app.domain.model.RequirementApplicability
import com.tripguard.app.domain.model.RuleVerification
import com.tripguard.app.domain.model.VerificationRule
import com.tripguard.app.domain.model.VisaCatalog
import com.tripguard.app.domain.model.VisaType
import com.tripguard.app.domain.rules.PhotoRequirementEvaluator
import com.tripguard.app.domain.rules.RequirementEvaluator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Proves the requirement-to-evidence mapping and the independent outcomes (presence, extraction,
 * rule evaluation) against the real dataset and the synthetic fixtures. Nothing in these tests
 * may invent a pass, a rule, or an extracted value.
 */
class Phase8RequirementAndEvidenceMappingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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

    private fun extraction(vararg pairs: Pair<String, String>): ExtractionResult = ExtractionResult(
        fields = pairs.map { (key, value) ->
            ExtractedField(key = key, label = key, value = value, confidence = 0.9f, method = OcrMethod.ML_KIT_OCR)
        },
        processedAt = 2_000L,
        provider = "mlkit",
        overallConfidence = 0.9f,
        method = OcrMethod.ML_KIT_OCR,
        rawText = null,
        pageCount = 1,
    )

    private fun evaluate(visa: VisaType, application: Application) =
        RequirementEvaluator.evaluate(catalog, visa, application)

    // 1. Each document category maps to the correct requirement (stable ids, not display names).
    @Test
    fun eachDocumentCategoryMapsToItsRequirement() {
        for (visaType in catalog.visaTypes) {
            val knownDocumentIds = visaType.documents.map { it.id }.toSet()
            val results = evaluate(visaType, app(visaType))

            for (doc in visaType.documents) {
                val result = results.firstOrNull { it.requirementId == doc.id }
                assertNotNull("Requirement ${doc.id} must be evaluated for ${visaType.id}", result)
                assertEquals(doc.id, result!!.documentCategoryId)
                assertEquals("photo mapping", doc.isPhotograph, result.documentCategoryId.contains("photo"))
            }
            // No stray categories: every evaluation belongs to a declared requirement.
            val declaredIds = visaType.documents.map { it.id } +
                visaType.conditionalDocuments.map { it.id } +
                visaType.financialRequirements.map { it.id }
            assertTrue(
                "Evaluations must map to declared requirements only (${visaType.id})",
                results.all { it.requirementId in declaredIds },
            )
            assertTrue(knownDocumentIds.isNotEmpty())
        }
    }

    // 2. The selected visa route controls applicability.
    @Test
    fun selectedVisaRouteControlsApplicability() {
        val results30 = evaluate(visa("tourist_30_day_single_entry"), app(visa("tourist_30_day_single_entry")))
        val results5y = evaluate(visa("tourist_5_year_multiple_entry"), app(visa("tourist_5_year_multiple_entry")))

        assertTrue(results30.any { it.requirementId == "medical_insurance" })
        assertFalse("medical_insurance is not a 5-year requirement", results5y.any { it.requirementId == "medical_insurance" })

        assertTrue(results5y.any { it.requirementId == "health_insurance" })
        assertTrue(results5y.any { it.requirementId == "bank_statement" })
        assertTrue(results5y.any { it.requirementId == "proof_of_residence_during_stay" })
        assertFalse("proof of residence is ICP-route specific, not a 30-day requirement",
            results30.any { it.requirementId == "proof_of_residence_during_stay" })

        assertTrue(results30.any { it.requirementId == "travel_ticket" })
        assertEquals(RequirementApplicability.CONDITIONAL,
            results5y.first { it.requirementId == "proof_of_residence_during_stay" }.applicability)
    }

    // 3. An absent document is reported missing.
    @Test
    fun absentDocumentIsReportedMissing() {
        val visaType = visa("tourist_30_day_single_entry")
        val results = evaluate(visaType, app(visaType))
        results.forEach {
            assertEquals(EvaluationOutcome.MISSING, it.outcome)
            assertEquals(DocumentPresence.MISSING, it.documentPresence)
            assertEquals(ExtractionStatus.NOT_PROCESSED, it.extractionStatus)
            assertTrue(it.evidenceFields.isEmpty())
            assertFalse(it.evidenceFound)
        }
    }

    // 4. A present but unreadable document is not reported missing.
    @Test
    fun presentButUnreadableDocumentIsNotReportedMissing() {
        val visaType = visa("tourist_30_day_single_entry")
        val result = evaluate(
            visaType,
            app(
                visaType,
                mapOf("passport_copy" to uploaded("passport_copy", state = DocumentState.NEEDS_REVIEW, extraction = null)),
            ),
        ).first { it.requirementId == "passport_copy" }

        assertEquals(DocumentPresence.PRESENT, result.documentPresence)
        assertEquals(ExtractionStatus.FAILED, result.extractionStatus)
        assertEquals(EvaluationOutcome.COULD_NOT_VERIFY, result.outcome)
        assertNotEquals(result.outcome, EvaluationOutcome.MISSING)
    }

    // 5. Successful extraction does not automatically satisfy a substantive requirement.
    @Test
    fun successfulExtractionDoesNotSatisfySubstantiveRequirement() {
        val visaType = visa("tourist_30_day_single_entry")
        val result = evaluate(
            visaType,
            app(
                visaType,
                mapOf(
                    "travel_ticket" to uploaded(
                        "travel_ticket",
                        extraction = extraction(
                            "passenger_name" to "AARAV DEMO SAMPLE",
                            "booking_reference" to "DEMO-TRIP-4821",
                            "travel_date" to "2026-12-15",
                            "return_date" to "2027-01-05",
                        ),
                    ),
                ),
            ),
        ).first { it.requirementId == "travel_ticket" }

        assertEquals(ExtractionStatus.SUCCESS, result.extractionStatus)
        assertTrue("Evidence must be surfaced, not only counted", result.evidenceFields.isNotEmpty())
        assertEquals("2026-12-15", result.evidenceFields.first { it.key == "travel_date" }.value)
        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, result.outcome)
        assertFalse(result.outcome == EvaluationOutcome.SATISFIED)
    }

    // 6. Unsupported evaluators produce an explicit manual-review result.
    @Test
    fun unsupportedEvaluatorProducesExplicitManualReview() {
        val unsupported = VerificationRule(
            id = "widget_rule",
            ruleType = "widget_authenticity_check",
            description = "Placeholder rule type with no product evaluator.",
            parameters = mapOf("x" to "1"),
            severity = "review",
            sourceIds = listOf("SRC_001"),
            humanReviewRequired = false,
        )
        val visaType = visa("tourist_30_day_single_entry").let { v ->
            v.copy(documents = v.documents.map { d ->
                if (d.id == "medical_insurance") d.copy(verificationRules = listOf(unsupported)) else d
            })
        }
        val result = evaluate(
            visaType,
            app(
                visaType,
                mapOf(
                    "medical_insurance" to uploaded(
                        "medical_insurance",
                        extraction = extraction("coverage_end" to "2027-01-05"),
                    ),
                ),
            ),
        ).first { it.requirementId == "medical_insurance" }

        assertEquals(RuleVerification.UNSUPPORTED, result.ruleVerification)
        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, result.outcome)
        assertTrue("Reason must explain the missing evaluator", result.reason.contains("does not implement"))
        assertFalse("Must not be labelled as no rule or verified", result.outcome == EvaluationOutcome.SATISFIED)
    }

    // 7. Unverified rules never automatically pass.
    @Test
    fun unverifiedRulesNeverAutomaticallyPass() {
        val visaType = visa("tourist_30_day_single_entry")
        val result = evaluate(
            visaType,
            app(
                visaType,
                mapOf(
                    "passport_copy" to uploaded(
                        "passport_copy",
                        extraction = extraction(
                            "expiry_date" to "2032-01-10",
                            "passport_number" to "TEST-ONLY-000000",
                        ),
                    ),
                ),
            ),
        ).first { it.requirementId == "passport_copy" }

        assertEquals("2032-01-10", result.extractedValue)
        assertEquals(RuleVerification.UNVERIFIED, result.ruleVerification)
        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, result.outcome)
        assertTrue("Reason must explain the unverified rule", result.reason.contains("does not authorise"))
        assertFalse(result.outcome == EvaluationOutcome.SATISFIED)
    }

    // 8. A missing photo rule does not cause invented image constraints.
    @Test
    fun missingPhotoRuleDoesNotInventImageConstraints() {
        val visaType = visa("tourist_30_day_single_entry")
        val photoRequirement = visaType.documents.first { it.id == "personal_photo" }
        val validation = PhotoRequirementEvaluator.evaluate(
            photoRequirement,
            PhotoMeasurements(600, 800, 50_000L, decodable = true, mimeType = "image/jpeg", extension = "jpg"),
        )
        val result = evaluate(
            visaType,
            app(visaType, mapOf("personal_photo" to uploaded("personal_photo", imageValidation = validation))),
        ).first { it.requirementId == "personal_photo" }

        assertEquals(RuleVerification.NO_RULE, result.ruleVerification)
        assertEquals(EvaluationOutcome.RULE_UNVERIFIED, result.outcome)
        assertTrue(result.extractedValue!!.contains("600"))
        assertTrue(
            "No invented dimension pass/fail may be claimed",
            validation.checks.none { it.outcome == EvaluationOutcome.SATISFIED && it.configured } &&
                validation.checks.none { it.outcome == EvaluationOutcome.POTENTIAL_ISSUE && it.configured },
        )
        assertTrue(result.evidenceFields.first { it.key == "photo_dimensions" }.value.contains("600"))
    }

    // 9. Editing or replacing a document recalculates dependent results.
    @Test
    fun editingOrReplacingDocumentRecalculatesDependentResults() = runBlocking {
        val targetFilesDir = tempFolder.newFolder("files")
        val context = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = targetFilesDir
        }
        val store = LocalStore(context)
        val fileStore = DocumentFileStore(context)
        val repository = ApplicationRepositoryImpl(
            store = store,
            api = com.tripguard.app.data.remote.TripGuardApi(baseUrlProvider = { "http://localhost/" }),
            fileStore = fileStore,
            catalogProvider = { catalog },
            scope = CoroutineScope(Dispatchers.Unconfined),
            ocrEngine = LocalOcrEngine(),
            defaultPolicy = DocumentPolicy(acceptedExtensions = listOf("pdf", "jpg", "jpeg", "png", "txt")),
        )
        val visaType = visa("tourist_30_day_single_entry")
        val created = (repository.createApplication(visaType) as AppResult.Success).data

        val first = tempFolder.newFile("passport_early.txt").apply {
            writeText("Passport Number: Z1234567\nDate of Expiry: 2026-08-01")
        }
        repository.stageDocument(created.id, "passport_copy", "Passport Copy", first.toURI().toString())
        var result = evaluate(visaType, current(repository, created.id))
            .first { it.requirementId == "passport_copy" }
        assertEquals("2026-08-01", result.extractedValue)

        val second = tempFolder.newFile("passport_late.txt").apply {
            writeText("Passport Number: Z1234567\nDate of Expiry: 2032-01-10")
        }
        repository.stageDocument(created.id, "passport_copy", "Passport Copy", second.toURI().toString())
        result = evaluate(visaType, current(repository, created.id))
            .first { it.requirementId == "passport_copy" }
        assertEquals("Replacing the file must feed the new expiry into the evaluation", "2032-01-10", result.extractedValue)
    }

    private suspend fun current(repository: ApplicationRepositoryImpl, applicationId: String): Application =
        repository.observeApplication(applicationId).first()!!

    // 10. The actual synthetic fixtures produce evidence derived from their content.
    @Test
    fun syntheticFixturesProduceContentDerivedEvidence() {
        val visa30 = visa("tourist_30_day_single_entry")
        val visa5y = visa("tourist_5_year_multiple_entry")

        val passportExtraction = extractionFrom("02_passport_copy_synthetic.pdf", "passport_copy")
        val passport = evaluate(
            visa30,
            app(visa30, mapOf("passport_copy" to uploaded("passport_copy", extraction = passportExtraction))),
        ).first { it.requirementId == "passport_copy" }
        assertEquals("2032-01-10", passport.extractedValue)
        assertEquals(ExtractionStatus.SUCCESS, passport.extractionStatus)
        assertEquals("PDF text extraction", passport.extractionMethod)
        assertTrue(passport.evidenceFields.any { it.key == "expiry_date" && it.value == "2032-01-10" })
        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, passport.outcome)

        val ticketExtraction = extractionFrom("03_return_ticket_synthetic.pdf", "travel_ticket")
        val ticket = evaluate(
            visa30,
            app(visa30, mapOf("travel_ticket" to uploaded("travel_ticket", extraction = ticketExtraction))),
        ).first { it.requirementId == "travel_ticket" }
        assertEquals("2026-12-15", ticket.evidenceFields.first { it.key == "travel_date" }.value)
        assertEquals("2027-01-05", ticket.evidenceFields.first { it.key == "return_date" }.value)
        assertTrue("Extraction must verify, not fabricate", ticket.evidenceFields.size >= 4)
        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, ticket.outcome)
        assertFalse(ticket.outcome == EvaluationOutcome.SATISFIED)

        val insuranceExtraction = extractionFrom("04_medical_insurance_synthetic.pdf", "medical_insurance")
        val insurance = evaluate(
            visa30,
            app(visa30, mapOf("medical_insurance" to uploaded("medical_insurance", extraction = insuranceExtraction))),
        ).first { it.requirementId == "medical_insurance" }
        assertEquals("2027-01-05", insurance.evidenceFields.first { it.key == "coverage_end" }.value)
        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, insurance.outcome)

        val bankExtraction = extractionFrom("05_bank_statement_6_months_synthetic.pdf", "bank_statement")
        val bankEval = evaluate(
            visa5y,
            app(visa5y, mapOf("bank_statement" to uploaded("bank_statement", extraction = bankExtraction))),
        ).first { it.requirementId == "bank_statement" }
        assertTrue(bankEval.extractionStatus != ExtractionStatus.FAILED)
        assertTrue("Closing balance must come from the fixture text",
            bankEval.evidenceFields.firstOrNull { it.key == "closing_balance" }?.value?.contains("5,250.00") == true)

        // The published financial threshold must map to the same bank-statement category.
        val balanceRequirement = evaluate(
            visa5y,
            app(visa5y, mapOf("bank_statement" to uploaded("bank_statement", extraction = bankExtraction))),
        ).first { it.requirementId == "minimum_bank_balance" }
        assertEquals("bank_statement", balanceRequirement.documentCategoryId)
        assertEquals("USD 5,250.00", balanceRequirement.extractedValue)
        assertTrue(balanceRequirement.evidenceFields.any { it.key == "closing_balance" })
        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, balanceRequirement.outcome)
    }

    private fun extractionFrom(fixture: String, documentId: String): ExtractionResult {
        val file = File("src/test/resources/fixtures/$fixture")
        assertTrue("Missing fixture $fixture", file.exists())
        val extracted = PdfTextExtractor.extract(file)
        assertNotNull("Selectable text expected in $fixture", extracted)
        val fields = DocumentFieldParser.parse(documentId, extracted!!.text, OcrMethod.PDF_TEXT_EXTRACTION)
        return ExtractionResult(
            fields = fields,
            processedAt = 3_000L,
            provider = OcrMethod.PDF_TEXT_EXTRACTION.label,
            overallConfidence = fields.mapNotNull { it.confidence }.average().toFloat(),
            method = OcrMethod.PDF_TEXT_EXTRACTION,
            rawText = extracted.text,
            pageCount = extracted.pageCount,
            warnings = extracted.warnings,
        )
    }

    private fun assertNotEquals(a: EvaluationOutcome, b: EvaluationOutcome) = assertFalse(a == b)
}