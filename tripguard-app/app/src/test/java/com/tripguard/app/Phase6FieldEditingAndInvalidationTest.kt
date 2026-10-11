package com.tripguard.app

import com.tripguard.app.data.dataset.AssetDatasetSource
import com.tripguard.app.data.document.DocumentFileStore
import com.tripguard.app.data.local.LocalStore
import com.tripguard.app.data.ocr.LocalOcrEngine
import com.tripguard.app.data.repository.ApplicationRepositoryImpl
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.Application
import com.tripguard.app.domain.model.DocumentPolicy
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.EvaluationOutcome
import com.tripguard.app.domain.model.VisaCatalog
import com.tripguard.app.domain.model.VisaType
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
 * End-to-end repository tests proving corrections are preserved, dependent checks are recomputed
 * from the corrected value, replacement invalidates stale extraction, and deletion removes
 * derived evidence. Confirmation records review only — it never upgrades a requirement outcome.
 */
class Phase6FieldEditingAndInvalidationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var targetFilesDir: File
    private lateinit var store: LocalStore
    private lateinit var fileStore: DocumentFileStore
    private lateinit var repository: ApplicationRepositoryImpl
    private lateinit var catalog: VisaCatalog

    @Before
    fun setUp() {
        targetFilesDir = tempFolder.newFolder("files")
        val context = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = targetFilesDir
        }
        val dummyContext = object : android.content.ContextWrapper(null) {}
        val source = AssetDatasetSource(context = dummyContext)
        val json = File("src/main/assets/tripguard_uae_visa_requirements_indian_passports_v1.0.1.json").readText()
        catalog = (source.parse(json) as AppResult.Success).data

        store = LocalStore(context)
        fileStore = DocumentFileStore(context)
        repository = ApplicationRepositoryImpl(
            store = store,
            api = com.tripguard.app.data.remote.TripGuardApi(baseUrlProvider = { "http://localhost/" }),
            fileStore = fileStore,
            catalogProvider = { catalog },
            scope = CoroutineScope(Dispatchers.Unconfined),
            ocrEngine = LocalOcrEngine(),
            defaultPolicy = DocumentPolicy(
                acceptedExtensions = listOf("pdf", "jpg", "jpeg", "png", "txt"),
            ),
        )
    }

    private fun fiveYearVisa(): VisaType = catalog.visaTypes.first { it.id == "tourist_5_year_multiple_entry" }

    private fun bankStatementText() = """
        STATE BANK OF INDIA STATEMENT
        Account Holder: ANITA SHARMA
        Account Number: 30123456789
        Closing Balance: USD 15,450.00
        Statement Period: 2026-01-01 to 2026-06-30
    """.trimIndent()

    private suspend fun createAppWithBankStatement(): Application {
        val visa = fiveYearVisa()
        val app = (repository.createApplication(visa) as AppResult.Success).data
        val file = tempFolder.newFile("bank_statement.txt").apply { writeText(bankStatementText()) }
        val staged = repository.stageDocument(app.id, "bank_statement", "Bank Statement", file.toURI().toString())
        assertTrue(staged is AppResult.Success)
        return (staged as AppResult.Success).data
    }

    private suspend fun current(appId: String): Application =
        repository.observeApplication(appId).first()!!

    @Test
    fun editingFieldPreservesOriginalAndRecomputesDependentCheck() = runBlocking {
        val app = createAppWithBankStatement()
        val before = current(app.id)
        val originalValue = before.documents["bank_statement"]!!.extraction!!
            .fields.first { it.key == "closing_balance" }.value
        assertEquals("USD 15,450.00", originalValue)

        val edited = repository.updateExtractedField(app.id, "bank_statement", "closing_balance", "USD 9,000.00")
        assertTrue(edited is AppResult.Success)

        val field = current(app.id).documents["bank_statement"]!!.extraction!!
            .fields.first { it.key == "closing_balance" }
        assertEquals("USD 9,000.00", field.value)
        assertEquals("USD 15,450.00", field.originalValue)
        assertTrue(field.userReviewed)

        // The dependent requirement must now read the corrected value.
        val evaluation = RequirementEvaluator.evaluate(catalog, fiveYearVisa(), current(app.id))
            .first { it.requirementId == "bank_statement" }
        assertEquals("USD 9,000.00", evaluation.extractedValue)
    }

    @Test
    fun confirmingFieldRecordsReviewButNeverUpgradesOutcome() = runBlocking {
        val app = createAppWithBankStatement()
        val confirmed = repository.confirmField(app.id, "bank_statement", "closing_balance", true)
        assertTrue(confirmed is AppResult.Success)

        val field = current(app.id).documents["bank_statement"]!!.extraction!!
            .fields.first { it.key == "closing_balance" }
        assertTrue(field.userReviewed)

        // Confirmation is review only; the dataset forbids an automatic financial decision.
        val evaluation = RequirementEvaluator.evaluate(catalog, fiveYearVisa(), current(app.id))
            .first { it.requirementId == "bank_statement" }
        assertEquals(EvaluationOutcome.MANUAL_REVIEW_REQUIRED, evaluation.outcome)
        assertFalse(evaluation.outcome == EvaluationOutcome.SATISFIED)
    }

    @Test
    fun replacingDocumentInvalidatesAndRecomputesExtraction() = runBlocking {
        val visa = fiveYearVisa()
        val app = (repository.createApplication(visa) as AppResult.Success).data
        val first = tempFolder.newFile("bank_v1.txt").apply { writeText(bankStatementText()) }
        repository.stageDocument(app.id, "bank_statement", "Bank Statement", first.toURI().toString())

        repository.updateExtractedField(app.id, "bank_statement", "closing_balance", "USD 1.00")
        var field = current(app.id).documents["bank_statement"]!!.extraction!!
            .fields.first { it.key == "closing_balance" }
        assertEquals("USD 1.00", field.value)
        assertEquals("USD 15,450.00", field.originalValue)

        // Replacing the file must discard the earlier correction and re-extract from the new copy.
        val second = tempFolder.newFile("bank_v2.txt").apply { writeText(bankStatementText()) }
        repository.stageDocument(app.id, "bank_statement", "Bank Statement", second.toURI().toString())

        field = current(app.id).documents["bank_statement"]!!.extraction!!
            .fields.first { it.key == "closing_balance" }
        assertEquals("USD 15,450.00", field.value)
        assertNull("Stale user correction must not survive a document replacement", field.originalValue)
        assertFalse(field.userReviewed)
    }

    @Test
    fun deletingDocumentRemovesDerivedEvidence() = runBlocking {
        val app = createAppWithBankStatement()
        assertTrue(current(app.id).documents["bank_statement"]!!.hasFile)

        val deleted = repository.deleteDocument(app.id, "bank_statement")
        assertTrue(deleted is AppResult.Success)

        val doc = current(app.id).documents["bank_statement"]
        assertNull("Deleting must remove the document entry and all its derived data", doc)

        val evaluation = RequirementEvaluator.evaluate(catalog, fiveYearVisa(), current(app.id))
            .first { it.requirementId == "bank_statement" }
        assertEquals(EvaluationOutcome.MISSING, evaluation.outcome)
    }

    @Test
    fun photographPipelineUsesValidationNotOcrAndDoesNotFabricate() = runBlocking {
        val visa = fiveYearVisa()
        val app = (repository.createApplication(visa) as AppResult.Success).data
        val photo = tempFolder.newFile("personal_photo.png").apply { writeBytes(ByteArray(2048) { 7 }) }

        val staged = repository.stageDocument(app.id, "personal_photo", "Personal Photo", photo.toURI().toString())
        assertTrue(staged is AppResult.Success)

        val doc = current(app.id).documents["personal_photo"]!!
        // OCR must not run on a photograph; the measurement path owns it.
        assertNull("Photographs must not carry an OCR extraction", doc.extraction)
        val validation = doc.imageValidation
        assertNotNull("Photographs must be measured", validation)
        // On the JVM the image codec is unavailable, so no dimensions may be invented.
        assertFalse(validation!!.measurements.isUsable)
        assertEquals(EvaluationOutcome.COULD_NOT_VERIFY, validation.summaryOutcome)
        assertTrue(doc.state == DocumentState.NEEDS_REVIEW || doc.state == DocumentState.POTENTIAL_ISSUE)
    }
}
