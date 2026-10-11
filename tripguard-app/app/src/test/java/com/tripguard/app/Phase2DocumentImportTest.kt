package com.tripguard.app

import com.tripguard.app.data.document.DocumentFileStore
import com.tripguard.app.data.local.LocalStore
import com.tripguard.app.data.repository.ApplicationRepositoryImpl
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.DocumentPolicy
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.VisaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

class Phase2DocumentImportTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var targetFilesDir: File
    private lateinit var fileStore: DocumentFileStore
    private lateinit var store: LocalStore
    private lateinit var repository: ApplicationRepositoryImpl

    @Before
    fun setUp() {
        targetFilesDir = tempFolder.newFolder("files")
        val context = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = targetFilesDir
        }
        fileStore = DocumentFileStore(context)
        store = LocalStore(context)
        repository = ApplicationRepositoryImpl(
            store = store,
            api = mockApi(),
            fileStore = fileStore,
            catalogProvider = { null },
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private fun mockApi(): com.tripguard.app.data.remote.TripGuardApi {
        return com.tripguard.app.data.remote.TripGuardApi(baseUrlProvider = { "http://localhost/" })
    }

    @Test
    fun testFilePolicyValidationForPDFAndImages() {
        val policy = DocumentPolicy(
            acceptedExtensions = listOf("pdf", "jpg", "jpeg", "png", "webp"),
            maxFileSizeBytes = 10 * 1024 * 1024L
        )

        assertTrue(policy.isAcceptedExtension("pdf"))
        assertTrue(policy.isAcceptedExtension("jpg"))
        assertTrue(policy.isAcceptedExtension("png"))
        assertFalse(policy.isAcceptedExtension("exe"))
        assertFalse(policy.isAcceptedExtension("txt"))
    }

    @Test
    fun testStageDocumentCreatesPrivateCopyInAppStorage() = runBlocking {
        // Create source file to import
        val sourceFile = tempFolder.newFile("sample_passport.jpg").apply {
            writeText("dummy image bytes content for passport")
        }

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

        val createResult = repository.createApplication(visaType)
        assertTrue(createResult is AppResult.Success)
        val app = (createResult as AppResult.Success).data

        val stageResult = repository.stageDocument(
            applicationId = app.id,
            documentId = "passport_copy",
            documentName = "Passport Copy",
            sourceUri = sourceFile.toURI().toString()
        )
        assertTrue(stageResult is AppResult.Success)
        val updatedApp = (stageResult as AppResult.Success).data

        val doc = updatedApp.documents["passport_copy"]
        assertNotNull(doc)
        assertTrue(doc?.state == DocumentState.EXTRACTED || doc?.state == DocumentState.NEEDS_REVIEW || doc?.state == DocumentState.UPLOADED)
        assertNotNull(doc?.localPath)

        val localCopy = File(doc!!.localPath!!)
        assertTrue("Private copy must exist on disk", localCopy.exists())
        assertTrue("Private copy must be inside filesDir/documents", localCopy.absolutePath.startsWith(targetFilesDir.absolutePath))
        assertEquals(sourceFile.readText(), localCopy.readText())
    }

    @Test
    fun testDeleteDocumentRemovesLocalCopyAndDerivedData() = runBlocking {
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
        val sourceFile = tempFolder.newFile("ticket.pdf").apply { writeText("pdf bytes content") }

        repository.stageDocument(app.id, "travel_ticket", "Travel Ticket", sourceFile.toURI().toString())
        val stagedApp = store.state.value.applications.first { it.id == app.id }
        val localPath = stagedApp.documents.first { it.documentId == "travel_ticket" }.localPath
        assertNotNull(localPath)
        val fileOnDisk = File(localPath!!)
        assertTrue(fileOnDisk.exists())

        // Delete document
        val deleteResult = repository.deleteDocument(app.id, "travel_ticket")
        assertTrue(deleteResult is AppResult.Success)
        val afterDeleteApp = (deleteResult as AppResult.Success).data

        assertFalse("Document file copy should be deleted from disk", fileOnDisk.exists())
        assertNull("Document metadata should be removed from application", afterDeleteApp.documents["travel_ticket"])
    }

    @Test
    fun testPersistenceAcrossAppRestart() = runBlocking {
        val sourceFile = tempFolder.newFile("insurance.pdf").apply { writeText("insurance details") }
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
        repository.stageDocument(app.id, "medical_insurance", "Medical Insurance", sourceFile.toURI().toString())

        // Simulate app restart by creating a new LocalStore reading from the same filesDir
        val context = object : android.content.ContextWrapper(null) {
            override fun getFilesDir(): File = targetFilesDir
        }
        val restartedStore = LocalStore(context)
        val restartedApp = restartedStore.state.value.applications.firstOrNull { it.id == app.id }
        assertNotNull(restartedApp)
        val docRecord = restartedApp!!.documents.firstOrNull { it.documentId == "medical_insurance" }
        assertNotNull(docRecord)
        assertEquals("medical_insurance", docRecord?.documentId)
        assertTrue(File(docRecord!!.localPath!!).exists())
    }
}
