package com.tripguard.app.domain.repository

import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.ApplicantDetails
import com.tripguard.app.domain.model.Application
import com.tripguard.app.domain.model.DocumentPolicy
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.VisaCatalog
import com.tripguard.app.domain.model.VisaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Connectivity to the backend, surfaced to the UI as an honest status indicator. */
enum class BackendStatus { UNKNOWN, CHECKING, REACHABLE, UNREACHABLE }

interface SettingsRepository {
    val baseUrl: StateFlow<String>
    val backendStatus: StateFlow<BackendStatus>
    suspend fun setBaseUrl(url: String)
    suspend fun pingBackend(): BackendStatus
}

interface VisaCatalogRepository {
    val catalog: StateFlow<VisaCatalog?>
    val isLoading: StateFlow<Boolean>

    /** Loads the catalog from the bundled versioned dataset; refreshes from backend when reachable. */
    suspend fun load(forceRefresh: Boolean = false): AppResult<VisaCatalog>

    fun visaType(id: String): VisaType?
}

interface ApplicationRepository {
    val documentPolicy: StateFlow<DocumentPolicy>

    fun observeApplications(): Flow<List<Application>>
    fun observeApplication(id: String): Flow<Application?>
    fun observeActiveApplicationId(): Flow<String?>

    suspend fun setActiveApplication(id: String?)

    suspend fun createApplication(visaType: VisaType): AppResult<Application>

    suspend fun updateApplicant(id: String, applicant: ApplicantDetails): AppResult<Application>

    /** Validates, stages a picked file locally with real byte-copy progress. */
    suspend fun stageDocument(
        applicationId: String,
        documentId: String,
        documentName: String,
        sourceUri: String,
    ): AppResult<Application>

    /** Deletes the local copy of a document and removes its derived metadata. */
    suspend fun deleteDocument(applicationId: String, documentId: String): AppResult<Application>

    /** Runs local on-device OCR on the staged document and updates extraction results. */
    suspend fun processLocalOcr(applicationId: String, documentId: String): AppResult<Application>

    /** Re-runs the appropriate on-device pipeline (photo measurement or OCR) for a document. */
    suspend fun reprocessDocument(applicationId: String, documentId: String): AppResult<Application>

    /**
     * Measures a staged personal photograph locally (dimensions, aspect ratio, file size,
     * readability) and evaluates only the photograph rules the dataset actually configures.
     */
    suspend fun validatePhotograph(applicationId: String, documentId: String): AppResult<Application>

    /**
     * Records a user correction to an extracted candidate value. The original OCR value is
     * preserved, the field is marked reviewed, and dependent results are recomputed.
     */
    suspend fun updateExtractedField(
        applicationId: String,
        documentId: String,
        fieldKey: String,
        newValue: String,
    ): AppResult<Application>

    /** Marks a candidate field as reviewed/confirmed (or clears the confirmation). */
    suspend fun confirmField(
        applicationId: String,
        documentId: String,
        fieldKey: String,
        confirmed: Boolean,
    ): AppResult<Application>

    suspend fun markUploading(applicationId: String, documentId: String, progress: Float?): AppResult<Application>
    suspend fun markUploadFailed(applicationId: String, documentId: String, error: String): AppResult<Application>

    suspend fun requestManualReview(
        applicationId: String,
        documentId: String,
        reason: String?,
    ): AppResult<Application>

    suspend fun updateDocumentState(
        applicationId: String,
        documentId: String,
        state: DocumentState,
        issueNote: String? = null,
        agencyNote: String? = null,
    ): AppResult<Application>

    suspend fun flagFieldIncorrect(
        applicationId: String,
        documentId: String,
        fieldKey: String,
        incorrect: Boolean,
    ): AppResult<Application>

    suspend fun resolveAgencyComment(applicationId: String, commentId: String): AppResult<Application>

    /**
     * Records the traveler's explicit document-sharing consent for the agency handoff. The
     * backend is authoritative: a [AppResult.Failure] means consent was NOT recorded and the UI
     * must say so.
     */
    suspend fun confirmDocumentSharing(
        applicationId: String,
        selectedDocumentIds: List<String>,
    ): AppResult<Application>

    /** Accepts, changes or declines an agency appointment proposal. Backend-confirmed. */
    suspend fun respondToAppointment(
        applicationId: String,
        appointmentId: String,
        response: com.tripguard.app.domain.model.AppointmentResponse,
        slotId: String? = null,
        reason: String? = null,
    ): AppResult<Application>

    /** Pulls the latest persisted OCR/validation/review data for one application. */
    suspend fun refresh(applicationId: String): AppResult<Application>

    suspend fun deleteApplication(id: String)
}
