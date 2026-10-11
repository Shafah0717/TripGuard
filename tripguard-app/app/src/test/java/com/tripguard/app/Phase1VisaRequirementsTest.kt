package com.tripguard.app

import com.tripguard.app.data.dataset.AssetDatasetSource
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.Application
import com.tripguard.app.domain.model.ApplicationStatus
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.DocumentUpload
import com.tripguard.app.domain.model.ErrorKind
import com.tripguard.app.domain.model.RequirementApplicability
import com.tripguard.app.domain.readiness.ReadinessCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class Phase1VisaRequirementsTest {

    private lateinit var assetSource: AssetDatasetSource
    private lateinit var rawJsonContent: String

    @Before
    fun setUp() {
        val dummyContext = object : android.content.ContextWrapper(null) {}
        assetSource = AssetDatasetSource(context = dummyContext)
        val jsonFile = File("src/main/assets/tripguard_uae_visa_requirements_indian_passports_v1.0.1.json")
        assertTrue("JSON file must exist in assets", jsonFile.exists())
        rawJsonContent = jsonFile.readText()
    }

    @Test
    fun testActualUAEVisaRequirementsJSONLoadsSuccessfully() {
        val result = assetSource.parse(rawJsonContent)
        assertTrue("Parsing actual JSON asset should succeed", result is AppResult.Success)
        val catalog = (result as AppResult.Success).data
        assertEquals("tripguard_uae_visa_requirements", catalog.datasetId)
        assertEquals("1.0.1", catalog.schemaVersion)
        assertEquals("United Arab Emirates", catalog.destinationCountry)
        assertEquals("Dubai", catalog.destinationEmirate)
    }

    @Test
    fun testAllSupportedVisaCategoriesDisplayRealIDsAndRequirements() {
        val catalog = (assetSource.parse(rawJsonContent) as AppResult.Success).data
        val expectedIds = setOf(
            "tourist_30_day_single_entry",
            "tourist_60_day_single_entry",
            "tourist_5_year_multiple_entry"
        )
        val actualIds = catalog.visaTypes.map { it.id }.toSet()
        assertEquals(expectedIds, actualIds)

        // Verify requirements for 30-day single entry
        val visa30 = catalog.visaTypes.first { it.id == "tourist_30_day_single_entry" }
        assertEquals("30-Day Tourist Visa", visa30.displayName)
        assertTrue(visa30.documents.any { it.id == "passport_copy" })
        assertTrue(visa30.documents.any { it.id == "personal_photo" })
        assertTrue(visa30.documents.any { it.id == "travel_ticket" })
        assertTrue(visa30.documents.any { it.id == "medical_insurance" })

        // Verify requirements for 5-year multiple entry
        val visa5y = catalog.visaTypes.first { it.id == "tourist_5_year_multiple_entry" }
        assertEquals("Five-Year Multiple-Entry Tourist Visa", visa5y.displayName)
        assertTrue(visa5y.documents.any { it.id == "bank_statement" })
        assertTrue(visa5y.financialRequirements.any { it.id == "minimum_bank_balance" })
    }

    @Test
    fun testUnverifiedRulesAreNeverDisplayedAsVerified() {
        val catalog = (assetSource.parse(rawJsonContent) as AppResult.Success).data
        for (visa in catalog.visaTypes) {
            // Check eligibility verified status matches real dataset (which has verified = false)
            assertFalse("Eligibility should not be marked verified for ${visa.id}", visa.isVerifiedEligibility)
            assertFalse("Eligibility object should have verified = false for ${visa.id}", visa.eligibility.verified)
        }
    }

    @Test
    fun testConditionalAndUnverifiedRulesDisplayedAccurately() {
        val catalog = (assetSource.parse(rawJsonContent) as AppResult.Success).data
        val visa30 = catalog.visaTypes.first { it.id == "tourist_30_day_single_entry" }

        val conditionalDoc = visa30.checklist.firstOrNull { it.documentId == "national_identity_document_for_specific_nationalities" }
        assertNotNull("Conditional doc should be present", conditionalDoc)
        assertEquals(RequirementApplicability.NOT_APPLICABLE, conditionalDoc?.applicability)
    }

    @Test
    fun testMalformedOrMissingJSONProducesClearError() {
        // Empty string
        val emptyResult = assetSource.parse("")
        assertTrue(emptyResult is AppResult.Failure)
        assertEquals(ErrorKind.PARSE, (emptyResult as AppResult.Failure).error.kind)

        // Invalid JSON
        val malformedResult = assetSource.parse("{ malformed json }")
        assertTrue(malformedResult is AppResult.Failure)
        assertEquals(ErrorKind.PARSE, (malformedResult as AppResult.Failure).error.kind)

        // Missing required fields
        val incompleteResult = assetSource.parse("""{"dataset_id": "test", "schema_version": "1.0"}""")
        assertTrue(incompleteResult is AppResult.Failure)
        assertEquals(ErrorKind.PARSE, (incompleteResult as AppResult.Failure).error.kind)
    }

    @Test
    fun testChecklistProgressBasedOnActualApplicationState() {
        val catalog = (assetSource.parse(rawJsonContent) as AppResult.Success).data
        val visa = catalog.visaTypes.first { it.id == "tourist_30_day_single_entry" }

        val emptyApp = Application(
            id = "app_1",
            reference = "TG-1001",
            visaTypeId = visa.id,
            visaTypeName = visa.displayName,
            createdAt = 1000L,
            updatedAt = 1000L,
            documents = emptyMap()
        )
        val initialReport = ReadinessCalculator.calculate(visa, emptyApp)
        assertEquals(4, initialReport.requiredTotal)
        assertEquals(0, initialReport.requiredUploaded)
        assertEquals(ApplicationStatus.IN_PROGRESS, initialReport.overall)

        // Upload 2 documents
        val partialApp = emptyApp.copy(
            documents = mapOf(
                "passport_copy" to DocumentUpload("passport_copy", "Passport Copy", state = DocumentState.UPLOADED),
                "personal_photo" to DocumentUpload("personal_photo", "Personal Photo", state = DocumentState.UPLOADED)
            )
        )
        val partialReport = ReadinessCalculator.calculate(visa, partialApp)
        assertEquals(4, partialReport.requiredTotal)
        assertEquals(2, partialReport.requiredUploaded)
        assertEquals(ApplicationStatus.IN_PROGRESS, partialReport.overall)

        // Upload all 4 required documents
        val completeApp = emptyApp.copy(
            documents = mapOf(
                "passport_copy" to DocumentUpload("passport_copy", "Passport Copy", state = DocumentState.UPLOADED),
                "personal_photo" to DocumentUpload("personal_photo", "Personal Photo", state = DocumentState.UPLOADED),
                "travel_ticket" to DocumentUpload("travel_ticket", "Travel Ticket", state = DocumentState.UPLOADED),
                "medical_insurance" to DocumentUpload("medical_insurance", "Medical Insurance", state = DocumentState.UPLOADED)
            )
        )
        val completeReport = ReadinessCalculator.calculate(visa, completeApp)
        assertEquals(4, completeReport.requiredTotal)
        assertEquals(4, completeReport.requiredUploaded)
        assertEquals(ApplicationStatus.UNDER_REVIEW, completeReport.overall)
    }
}
