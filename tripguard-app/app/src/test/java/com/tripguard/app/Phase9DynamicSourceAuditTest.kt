package com.tripguard.app

import com.tripguard.app.data.dataset.AssetDatasetSource
import com.tripguard.app.data.document.DocumentFileStore
import com.tripguard.app.data.local.LocalStore
import com.tripguard.app.data.ocr.DocumentFieldParser
import com.tripguard.app.data.ocr.LocalOcrEngine
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
import com.tripguard.app.domain.model.RuleVerification
import com.tripguard.app.domain.model.VisaCatalog
import com.tripguard.app.domain.model.VisaType
import com.tripguard.app.domain.rules.EvidenceExplanation
import com.tripguard.app.domain.rules.RequirementEvaluator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Dynamic-source audit. These tests prove that every displayed extracted field is derived from the
 * actual selected file (PDF text / OCR / plain text → [DocumentFieldParser]) and that every
 * displayed requirement status is read from the loaded local JSON — never from a fixed sample
 * object or a hardcoded outcome. They intentionally use only temporary documents and temporary
 * datasets; the production JSON under `src/main/assets` is never modified.
 */
class Phase9DynamicSourceAuditTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val assetJson = File(
        "src/main/assets/tripguard_uae_visa_requirements_indian_passports_v1.0.1.json",
    )
    private val datasetSource = AssetDatasetSource(context = object : android.content.ContextWrapper(null) {})

    private lateinit var catalog: VisaCatalog

    @Before
    fun setUp() {
        catalog = (datasetSource.parse(assetJson.readText()) as AppResult.Success).data
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
    ) = DocumentUpload(
        documentId = id,
        documentName = id,
        state = state,
        localPath = "/tmp/$id.jpg",
        fileName = "$id.jpg",
        fileSizeBytes = 1000L,
        extraction = extraction,
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

    // ---- Item 4: two different documents with different values ------------------------------

    @Test
    fun twoDifferentDocumentsExtractDifferentFieldValues() = runBlocking {
        val docA = tempFolder.newFile("passport_a.txt").apply {
            writeText("Passport Number: Z1234567\nDate of Issue: 12 Jan 2020\nDate of Expiry: 05 July 2026")
        }
        val docB = tempFolder.newFile("passport_b.txt").apply {
            writeText("Passport Number: X7654321\nDate of Issue: 20 Mar 2021\nDate of Expiry: 25 December 2035")
        }

        val resultA = LocalOcrEngine().process(docA, "passport_copy")
        val resultB = LocalOcrEngine().process(docB, "passport_copy")
        assertTrue("First synthetic document must extract", resultA is AppResult.Success)
        assertTrue("Second synthetic document must extract", resultB is AppResult.Success)

        val fieldsA = (resultA as AppResult.Success).data.fields.associateBy { it.key }
        val fieldsB = (resultB as AppResult.Success).data.fields.associateBy { it.key }

        assertEquals("2026-07-05", fieldsA["expiry_date"]?.value)
        assertEquals("2035-12-25", fieldsB["expiry_date"]?.value)
        assertEquals("Z1234567", fieldsA["passport_number"]?.value)
        assertEquals("X7654321", fieldsB["passport_number"]?.value)
        assertFalse(
            "Extracted fields must change with the input, not a fixed sample object",
            fieldsA["expiry_date"]?.value == fieldsB["expiry_date"]?.value,
        )
        assertEquals(OcrMethod.PLAIN_TEXT, (resultA).data.method)
    }

    // ---- Item 4: a blank document produces nothing, never fabricated values ----------------

    @Test
    fun blankDocumentProducesNoFabricatedValues() = runBlocking {
        val blank = tempFolder.newFile("blank_passport.txt").apply { writeText("   \n\n  ") }

        val result = LocalOcrEngine().process(blank, "passport_copy")
        assertTrue("Blank input must fail, not produce invented fields", result is AppResult.Failure)

        val fields = DocumentFieldParser.parse("passport_copy", "   \n  ", OcrMethod.PLAIN_TEXT)
        assertTrue("A known category still yields its field template", fields.isNotEmpty())
        assertTrue("No value may be fabricated from blank input", fields.all { it.value.isBlank() })
        assertTrue(fields.all { it.warning == null })
    }

    // ---- Item 4: replacing a document invalidates stale evidence ---------------------------

    private fun repository() = runBlocking {
        val targetFilesDir = tempFolder.newFolder("files")
        val context = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = targetFilesDir
        }
        ApplicationRepositoryImpl(
            store = LocalStore(context),
            api = com.tripguard.app.data.remote.TripGuardApi(baseUrlProvider = { "http://localhost/" }),
            fileStore = DocumentFileStore(context),
            catalogProvider = { catalog },
            scope = CoroutineScope(Dispatchers.Unconfined),
            ocrEngine = LocalOcrEngine(),
            defaultPolicy = DocumentPolicy(acceptedExtensions = listOf("pdf", "jpg", "jpeg", "png", "txt")),
        )
    }

    private suspend fun passportEvaluation(
        repository: ApplicationRepositoryImpl,
        applicationId: String,
        visaType: VisaType,
    ) = RequirementEvaluator.evaluate(catalog, visaType, current(repository, applicationId))
        .first { it.requirementId == "passport_copy" }

    private suspend fun current(repository: ApplicationRepositoryImpl, applicationId: String): Application =
        repository.observeApplication(applicationId).first()!!

    @Test
    fun replacingDocumentInvalidatesStaleEvidence() = runBlocking {
        val repository = repository()
        val visaType = visa("tourist_30_day_single_entry")
        val created = (repository.createApplication(visaType) as AppResult.Success).data

        val early = tempFolder.newFile("passport_early.txt").apply {
            writeText("Passport Number: Z1234567\nDate of Expiry: 01 August 2026")
        }
        repository.stageDocument(created.id, "passport_copy", "Passport Copy", early.toURI().toString())
        var evaluation = passportEvaluation(repository, created.id, visaType)
        assertEquals("01 August 2026 must normalise from the first file", "2026-08-01", evaluation.extractedValue)

        val late = tempFolder.newFile("passport_late.txt").apply {
            writeText("Passport Number: X7654321\nDate of Expiry: 10 January 2032")
        }
        repository.stageDocument(created.id, "passport_copy", "Passport Copy", late.toURI().toString())
        evaluation = passportEvaluation(repository, created.id, visaType)

        assertEquals("Replacing the file must feed the new expiry into the evaluation", "2032-01-10", evaluation.extractedValue)
        assertTrue(evaluation.evidenceFields.any { it.key == "expiry_date" && it.value == "2032-01-10" })
        assertTrue(
            "Stale expiry from the replaced file must be absent",
            evaluation.evidenceFields.none { it.value == "2026-08-01" },
        )
    }

    @Test
    fun replacingWithBlankFileLeavesNoStaleValues() = runBlocking {
        val repository = repository()
        val visaType = visa("tourist_30_day_single_entry")
        val created = (repository.createApplication(visaType) as AppResult.Success).data

        val original = tempFolder.newFile("passport_stale.txt").apply {
            writeText("Passport Number: Z1234567\nDate of Expiry: 01 August 2026")
        }
        repository.stageDocument(created.id, "passport_copy", "Passport Copy", original.toURI().toString())
        assertEquals("2026-08-01", passportEvaluation(repository, created.id, visaType).extractedValue)

        val blank = tempFolder.newFile("passport_blank.txt").apply { writeText("   \n  ") }
        repository.stageDocument(created.id, "passport_copy", "Passport Copy", blank.toURI().toString())
        val evaluation = passportEvaluation(repository, created.id, visaType)

        assertEquals(DocumentPresence.PRESENT, evaluation.documentPresence)
        assertNull("The failed blank replacement must not keep any extracted value", evaluation.extractedValue)
        assertTrue("No evidence may survive from the stale file", evaluation.evidenceFields.isEmpty())
        assertFalse(evaluation.outcome == EvaluationOutcome.MISSING)
    }

    // ---- Item 5: a temporary JSON fixture changes the evaluator result ---------------------

    @Test
    fun temporaryJsonRuleStatusChangesEvaluatorResult() {
        val original = assetJson.readText()
        val bytesBefore = assetJson.readBytes().toList()

        // Build a temporary dataset in a temp file. The production JSON is never touched.
        val flipped = original
            .replace("\"minimum_validity_days\": null", "\"minimum_validity_days\": 180")
            .replace("\"human_review_required\": true", "\"human_review_required\": false")
        assertFalse("The temporary fixture must actually differ from production", flipped == original)
        val fixtureFile = tempFolder.newFile("rule_flip_dataset.json")
        fixtureFile.writeText(flipped)
        val flippedCatalog = (datasetSource.parse(fixtureFile.readText()) as AppResult.Success).data

        val passportEvidence = app(
            catalog.visaTypes.first { it.id == "tourist_30_day_single_entry" },
            mapOf("passport_copy" to uploaded("passport_copy", extraction = extraction("expiry_date" to "2032-01-10"))),
        )

        val productionResult = RequirementEvaluator.evaluate(
            catalog,
            visa("tourist_30_day_single_entry"),
            passportEvidence,
        ).first { it.requirementId == "passport_copy" }

        val flippedVisa = flippedCatalog.visaTypes.first { it.id == "tourist_30_day_single_entry" }
        val flippedResult = RequirementEvaluator.evaluate(
            flippedCatalog,
            flippedVisa,
            passportEvidence,
        ).first { it.requirementId == "passport_copy" }

        // Same document evidence, only the JSON's rule status changed.
        assertEquals("2032-01-10", productionResult.extractedValue)
        assertEquals("2032-01-10", flippedResult.extractedValue)
        assertEquals(RuleVerification.UNVERIFIED, productionResult.ruleVerification)
        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, productionResult.outcome)
        assertEquals(RuleVerification.VERIFIED, flippedResult.ruleVerification)
        assertEquals(EvaluationOutcome.SATISFIED, flippedResult.outcome)
        assertTrue(flippedResult.reason.contains("at least 180 days"))

        // Production dataset is byte-identical — the audit never modified it.
        assertEquals(bytesBefore, assetJson.readBytes().toList())
    }

    // ---- Item 6: unverified passport rule + accurate no-evaluator reporting ----------------

    @Test
    fun passportExpiryRuleStaysUnverifiedUnderProductionDataset() {
        val evidence = app(
            visa("tourist_30_day_single_entry"),
            mapOf("passport_copy" to uploaded("passport_copy", extraction = extraction("expiry_date" to "2032-01-10"))),
        )
        val result = RequirementEvaluator.evaluate(catalog, visa("tourist_30_day_single_entry"), evidence)
            .first { it.requirementId == "passport_copy" }

        assertEquals(RuleVerification.UNVERIFIED, result.ruleVerification)
        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, result.outcome)
        assertTrue(result.reason.contains("does not authorise"))
        assertFalse(result.outcome == EvaluationOutcome.SATISFIED)
    }

    @Test
    fun ticketAndInsuranceWithoutEvaluatorsAreReportedAccurately() {
        val visa30 = visa("tourist_30_day_single_entry")
        val evidence = app(
            visa30,
            mapOf(
                "travel_ticket" to uploaded(
                    "travel_ticket",
                    extraction = extraction("travel_date" to "15 December 2026", "return_date" to "05 January 2027"),
                ),
                "medical_insurance" to uploaded(
                    "medical_insurance",
                    extraction = extraction("coverage_end" to "05 January 2027"),
                ),
            ),
        )
        val results = RequirementEvaluator.evaluate(catalog, visa30, evidence)

        val ticket = results.first { it.requirementId == "travel_ticket" }
        assertEquals(RuleVerification.NO_RULE, ticket.ruleVerification)
        assertNull("No rule may be invented for the ticket", ticket.ruleType)
        assertNull("No rule id may be invented for the ticket", ticket.ruleId)
        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, ticket.outcome)
        assertTrue(ticket.reason.contains("no automated check"))

        val insurance = results.first { it.requirementId == "medical_insurance" }
        assertEquals(RuleVerification.NO_RULE, insurance.ruleVerification)
        assertNull("No rule may be invented for the insurance", insurance.ruleType)
        assertNull("No rule id may be invented for the insurance", insurance.ruleId)
        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, insurance.outcome)
        assertTrue(insurance.reason.contains("no automated check"))
    }

    // ---- Item 7: readable explanations instead of data-class dumps -------------------------

    @Test
    fun explanationsShowCandidateValueMethodReviewStatusAndRuleOutcome() {
        val evidence = app(
            visa("tourist_30_day_single_entry"),
            mapOf("passport_copy" to uploaded("passport_copy", extraction = extraction("expiry_date" to "2032-01-10"))),
        )
        val result = RequirementEvaluator.evaluate(catalog, visa("tourist_30_day_single_entry"), evidence)
            .first { it.requirementId == "passport_copy" }

        val text = EvidenceExplanation.explanation(result)
        assertTrue("Candidate value must be shown", text.contains("2032-01-10"))
        assertTrue("Extraction method must be shown", text.contains("On-device ML Kit OCR"))
        assertTrue("Review status must be shown", text.contains("Rule unverified (dataset)"))
        assertTrue("Rule outcome must be shown", text.contains("Manual review required"))
        assertTrue("Requirement name must be shown", text.contains("Passport Copy"))

        // No internal data-class dump may reach the explanation.
        assertFalse("Unexpected 'key=' dump in:\n$text", text.contains("key="))
        assertFalse(text.contains("ExtractedField("))
        assertFalse(text.contains("EvidenceField("))
        assertFalse(text.contains("ExtractedField@"))

        val line = EvidenceExplanation.evidenceFieldLine(result.evidenceFields.first { it.key == "expiry_date" })
        assertTrue("Field line must carry the label", line.contains("expiry_date"))
        assertTrue("Field line must carry the candidate value", line.contains("2032-01-10"))
        assertTrue("Field line must carry the review status", line.contains("auto-detected"))
    }
}