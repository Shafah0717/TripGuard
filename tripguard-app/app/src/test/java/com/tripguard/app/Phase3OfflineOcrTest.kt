package com.tripguard.app

import com.tripguard.app.data.document.DocumentFileStore
import com.tripguard.app.data.local.LocalStore
import com.tripguard.app.data.ocr.LocalOcrEngine
import com.tripguard.app.data.repository.ApplicationRepositoryImpl
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.VisaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

class Phase3OfflineOcrTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var targetFilesDir: File
    private lateinit var ocrEngine: LocalOcrEngine
    private lateinit var fileStore: DocumentFileStore
    private lateinit var store: LocalStore
    private lateinit var repository: ApplicationRepositoryImpl

    @Before
    fun setUp() {
        targetFilesDir = tempFolder.newFolder("files")
        val context = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = targetFilesDir
        }
        ocrEngine = LocalOcrEngine()
        fileStore = DocumentFileStore(context)
        store = LocalStore(context)
        repository = ApplicationRepositoryImpl(
            store = store,
            api = mockApi(),
            fileStore = fileStore,
            catalogProvider = { null },
            scope = CoroutineScope(Dispatchers.Unconfined),
            ocrEngine = ocrEngine,
            // The synthetic mock-document pack is plain text; accept .txt in the test harness
            // only so the end-to-end import -> OCR -> retry path can be exercised offline.
            defaultPolicy = com.tripguard.app.domain.model.DocumentPolicy(
                acceptedExtensions = listOf("pdf", "jpg", "jpeg", "png", "txt"),
            ),
        )
    }

    private fun mockApi(): com.tripguard.app.data.remote.TripGuardApi {
        return com.tripguard.app.data.remote.TripGuardApi(baseUrlProvider = { "http://localhost/" })
    }

    @Test
    fun testOcrExtractsActualFieldsFromSyntheticPassportDocument() = runBlocking {
        val passportDocText = """
            PASSPORT REPUBLIC OF INDIA
            P<INDGUPTA<<RAHUL<<<<<<<<<<<<<<<<<<<<<<<<<<<
            Z1234567<4IND9005151M3012314<<<<<<<<<<<<<<02
            Name: RAHUL GUPTA
            Passport No: Z1234567
            Date of Birth: 1990-05-15
            Date of Expiry: 2030-12-31
            Nationality: Indian
        """.trimIndent()

        val syntheticFile = tempFolder.newFile("test_passport.txt").apply { writeText(passportDocText) }

        val result = ocrEngine.process(syntheticFile, "passport_copy")
        assertTrue("OCR processing synthetic passport must succeed", result is AppResult.Success)

        val fields = (result as AppResult.Success).data.fields
        assertTrue("Must extract structured fields from passport", fields.isNotEmpty())

        val fullNameField = fields.firstOrNull { it.key == "full_name" }
        assertNotNull("Full name field should be extracted", fullNameField)
        assertEquals("RAHUL GUPTA", fullNameField?.value)

        val passNoField = fields.firstOrNull { it.key == "passport_number" }
        assertNotNull("Passport number field should be extracted", passNoField)
        assertEquals("Z1234567", passNoField?.value)

        val expiryField = fields.firstOrNull { it.key == "expiry_date" }
        assertNotNull("Expiry date field should be extracted", expiryField)
        assertEquals("2030-12-31", expiryField?.value)
    }

    @Test
    fun testOcrExtractsBankStatementFields() = runBlocking {
        val bankDocText = """
            STATE BANK OF INDIA STATEMENT
            Account Holder: ANITA SHARMA
            Account Number: 30123456789
            Closing Balance: USD 15,450.00
            Statement Period: 2026-01-01 to 2026-06-30
        """.trimIndent()

        val syntheticFile = tempFolder.newFile("test_bank.txt").apply { writeText(bankDocText) }

        val result = ocrEngine.process(syntheticFile, "bank_statement")
        assertTrue(result is AppResult.Success)

        val fields = (result as AppResult.Success).data.fields
        val balanceField = fields.firstOrNull { it.key == "closing_balance" }
        assertNotNull("Closing balance must be extracted", balanceField)
        assertEquals("USD 15,450.00", balanceField?.value)
    }

    @Test
    fun testUnreadableDocumentDoesNotProduceFabricatedResults() = runBlocking {
        val unreadableFile = tempFolder.newFile("blank_unreadable.jpg").apply { writeBytes(byteArrayOf(0, 0, 0, 0, 0, 0)) }

        val result = ocrEngine.process(unreadableFile, "passport_copy")
        assertTrue("Unreadable document should return failure or 0 fields", result is AppResult.Failure || (result is AppResult.Success && (result as AppResult.Success).data.fields.isEmpty()))
        if (result is AppResult.Success) {
            assertTrue("No fabricated fields must be produced for unreadable document", result.data.fields.isEmpty())
        }
    }

    @Test
    fun testOcrIntegrationAndRetryInRepository() = runBlocking {
        val visaType = VisaType(
            id = "tourist_30_day_single_entry",
            officialName = "30 Day Tourist",
            displayName = "30-Day Tourist Visa",
            category = "tourism",
            productStatus = "active",
            entryType = "single",
            permittedStay = com.tripguard.app.domain.model.StaySpec(30, "days", emptyList()),
            visaValidity = com.tripguard.app.domain.model.StaySpec(60, "days", emptyList()),
            eligibility = com.tripguard.app.domain.model.Eligibility("Indian", emptyList(), false, emptyList()),
            applicationRoute = com.tripguard.app.domain.model.ApplicationRoute(false, emptyList(), emptyList(), emptyList()),
            financialRequirements = emptyList(),
            documents = emptyList(),
            conditionalDocuments = emptyList(),
            fees = emptyList(),
            processingTime = null,
            extensionNotes = emptyList(),
            restrictions = emptyList(),
            sourceIds = emptyList(),
            lastVerifiedAt = null,
            humanReviewRequired = false,
            applicantScope = null,
        )

        val app = (repository.createApplication(visaType) as AppResult.Success).data
        val passportDocText = """
            PASSPORT REPUBLIC OF INDIA
            P<INDGUPTA<<RAHUL<<<<<<<<<<<<<<<<<<<<<<<<<<<
            Z1234567<4IND9005151M3012314<<<<<<<<<<<<<<02
            Name: RAHUL GUPTA
            Passport No: Z1234567
            Date of Expiry: 2030-12-31
        """.trimIndent()
        val passportFile = tempFolder.newFile("my_passport.txt").apply { writeText(passportDocText) }

        val stageResult = repository.stageDocument(app.id, "passport_copy", "Passport Copy", passportFile.toURI().toString())
        assertTrue(stageResult is AppResult.Success)

        val stagedApp = (stageResult as AppResult.Success).data
        val doc = stagedApp.documents["passport_copy"]
        assertNotNull(doc)
        assertEquals(DocumentState.EXTRACTED, doc?.state)
        assertNotNull(doc?.extraction)
        assertFalse(doc!!.extraction!!.fields.isEmpty())

        // Test Retry OCR
        val retryResult = repository.processLocalOcr(app.id, "passport_copy")
        assertTrue("Retry OCR must succeed", retryResult is AppResult.Success)
        val retriedApp = (retryResult as AppResult.Success).data
        val retriedDoc = retriedApp.documents["passport_copy"]
        assertEquals(DocumentState.EXTRACTED, retriedDoc?.state)
    }
}
