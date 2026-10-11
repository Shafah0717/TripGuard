package com.tripguard.app.data.repository

import android.net.Uri
import com.tripguard.app.data.document.DocumentFileStore
import com.tripguard.app.data.image.ImageInspector
import com.tripguard.app.data.local.LocalStore
import com.tripguard.app.domain.model.EvaluationOutcome
import com.tripguard.app.domain.rules.PhotoRequirementEvaluator
import com.tripguard.app.data.local.toDomain
import com.tripguard.app.data.local.toRecord
import com.tripguard.app.data.remote.ApplicantDto
import com.tripguard.app.data.remote.AppointmentResponseRequest
import com.tripguard.app.data.remote.CreateApplicationRequest
import com.tripguard.app.data.remote.RemoteApplicationDto
import com.tripguard.app.data.remote.RemoteAppointmentDto
import com.tripguard.app.data.remote.RemoteAppointmentSlotDto
import com.tripguard.app.data.remote.RemoteHandoffDto
import com.tripguard.app.data.remote.TripGuardApi
import com.tripguard.app.domain.model.AgencyComment
import com.tripguard.app.domain.model.AgencyHandoff
import com.tripguard.app.domain.model.AppError
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.ApplicantDetails
import com.tripguard.app.domain.model.Application
import com.tripguard.app.domain.model.ApplicationStatus
import com.tripguard.app.domain.model.AppointmentInvitation
import com.tripguard.app.domain.model.AppointmentResponse
import com.tripguard.app.domain.model.AppointmentSlot
import com.tripguard.app.domain.model.AppointmentStatus
import com.tripguard.app.domain.model.DocumentPolicy
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.DocumentUpload
import com.tripguard.app.domain.model.ErrorKind
import com.tripguard.app.domain.model.ExtractedField
import com.tripguard.app.domain.model.ExtractionResult
import com.tripguard.app.domain.model.FileValidation
import com.tripguard.app.domain.model.HandoffStatus
import com.tripguard.app.domain.model.TimelineEvent
import com.tripguard.app.domain.model.TimelineKind
import com.tripguard.app.domain.model.VisaCatalog
import com.tripguard.app.domain.model.VisaType
import com.tripguard.app.domain.readiness.ReadinessCalculator
import com.tripguard.app.domain.rules.RequirementEvaluator
import com.tripguard.app.domain.repository.ApplicationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Offline-first application store. Every change is persisted locally first (so a live demo is
 * never blocked by the backend), then synchronised with the FastAPI service when reachable.
 * The UI observes a single [Application] stream combining persisted state and live progress.
 */
class ApplicationRepositoryImpl(
    private val store: LocalStore,
    private val api: TripGuardApi,
    private val fileStore: DocumentFileStore,
    private val catalogProvider: () -> VisaCatalog?,
    private val scope: CoroutineScope,
    defaultPolicy: DocumentPolicy = DocumentPolicy(),
    private val ocrEngine: com.tripguard.app.data.ocr.LocalOcrEngine = com.tripguard.app.data.ocr.LocalOcrEngine(),
    private val imageInspector: ImageInspector = ImageInspector(),
    private val onApplicationDeleted: suspend (String) -> Unit = {},
) : ApplicationRepository {

    private val _documentPolicy = MutableStateFlow(defaultPolicy)
    override val documentPolicy: StateFlow<DocumentPolicy> = _documentPolicy.asStateFlow()

    private val uploadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())

    init {
        scope.launch {
            when (val result = api.fetchDocumentPolicy()) {
                is AppResult.Success -> _documentPolicy.value = DocumentPolicy(
                    acceptedExtensions = result.data.acceptedExtensions,
                    maxFileSizeBytes = result.data.maxFileSizeBytes,
                    source = result.data.source,
                )

                is AppResult.Failure -> Unit
            }
        }
    }

    override fun observeApplications(): Flow<List<Application>> =
        combine(store.state, uploadProgress) { state, progress ->
            state.applications
                .map { it.toDomain() }
                .map { applyProgress(it, progress) }
                .sortedByDescending { it.updatedAt }
        }.distinctUntilChanged()

    override fun observeApplication(id: String): Flow<Application?> =
        combine(store.state, uploadProgress) { state, progress ->
            state.applications.firstOrNull { it.id == id }
                ?.toDomain()
                ?.let { applyProgress(it, progress) }
        }.distinctUntilChanged()

    override fun observeActiveApplicationId(): Flow<String?> =
        store.state.map { it.activeApplicationId }.distinctUntilChanged()

    override suspend fun setActiveApplication(id: String?) {
        store.update { it.copy(activeApplicationId = id) }
    }

    override suspend fun createApplication(visaType: VisaType): AppResult<Application> {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val application = Application(
            id = id,
            reference = generateReference(now),
            visaTypeId = visaType.id,
            visaTypeName = visaType.displayName,
            status = ApplicationStatus.DRAFT,
            createdAt = now,
            updatedAt = now,
            timeline = listOf(
                TimelineEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = now,
                    title = "Application started",
                    description = "${visaType.displayName} selected.",
                    kind = TimelineKind.CREATED,
                ),
            ),
        )
        store.update {
            it.copy(applications = it.applications + application.toRecord(), activeApplicationId = id)
        }
        scope.launch { syncCreate(application) }
        return AppResult.Success(application)
    }

    override suspend fun updateApplicant(id: String, applicant: ApplicantDetails): AppResult<Application> {
        val updated = mutate(id) { app ->
            recomputeStatus(app.copy(applicant = applicant, updatedAt = System.currentTimeMillis()))
        } ?: return notFound()

        scope.launch { syncApplicant(updated) }
        return AppResult.Success(updated)
    }

    override suspend fun stageDocument(
        applicationId: String,
        documentId: String,
        documentName: String,
        sourceUri: String,
    ): AppResult<Application> {
        val policy = _documentPolicy.value
        when (val validation = fileStore.validate(sourceUri, policy)) {
            is FileValidation.Invalid -> return AppResult.Failure(
                AppError(ErrorKind.VALIDATION, validation.reason),
            )

            FileValidation.Valid -> Unit
        }

        // Delete old local file if replacing an existing document
        current(applicationId)?.documents?.get(documentId)?.localPath?.let { oldPath ->
            fileStore.delete(oldPath)
        }

        mutate(applicationId) { app ->
            app.withDocument(documentId, documentName) {
                it.copy(state = DocumentState.UPLOADING, uploadProgress = 0f, lastError = null)
            }
        }

        val key = progressKey(applicationId, documentId)
        val staged = fileStore.stage(sourceUri, applicationId, documentId) { progress ->
            uploadProgress.update { it + (key to progress) }
        }
        uploadProgress.update { it - key }

        return when (staged) {
            is AppResult.Failure -> {
                mutate(applicationId) { app ->
                    app.withDocument(documentId, documentName) {
                        it.copy(
                            state = DocumentState.UPLOAD_FAILED,
                            uploadProgress = null,
                            lastError = staged.error.message,
                        )
                    }
                }
                staged
            }

            is AppResult.Success -> {
                val file = staged.data
                val now = System.currentTimeMillis()
                val updated = mutate(applicationId) { app ->
                    app.withDocument(documentId, documentName) {
                        it.copy(
                            state = DocumentState.UPLOADED,
                            localPath = file.path,
                            fileName = file.fileName,
                            fileSizeBytes = file.sizeBytes,
                            mimeType = file.mimeType,
                            uploadedAt = now,
                            uploadProgress = null,
                            lastError = null,
                            extraction = null, // reset extraction when document replaced
                        )
                    }.copy(
                        updatedAt = now,
                        timeline = app.timeline + TimelineEvent(
                            id = UUID.randomUUID().toString(),
                            timestamp = now,
                            title = "$documentName saved locally",
                            description = "Stored securely in private local storage.",
                            kind = TimelineKind.DOCUMENT_UPLOADED,
                        ),
                    ).let(::recomputeStatus)
                }
                processStagedDocument(applicationId, documentId)
                current(applicationId)?.let { AppResult.Success(it) } ?: notFound()
            }
        }
    }

    private suspend fun processStagedDocument(applicationId: String, documentId: String) {
        reprocessDocument(applicationId, documentId)
    }

    override suspend fun reprocessDocument(applicationId: String, documentId: String): AppResult<Application> {
        val app = current(applicationId) ?: return notFound()
        return if (isPhotographDocument(app, documentId)) {
            validatePhotograph(applicationId, documentId)
        } else {
            processLocalOcr(applicationId, documentId)
        }
    }

    private fun isPhotographDocument(app: Application, documentId: String): Boolean {
        val catalog = catalogProvider()
        val visa = catalog?.visaTypes?.firstOrNull { it.id == app.visaTypeId }
        val requirement = visa?.documents?.firstOrNull { it.id == documentId }
        return requirement?.isPhotograph ?: documentId.contains("photo", ignoreCase = true)
    }

    /**
     * Runs the configured rule check for the content of [documentId] after parsing/editing and
     * derives the document state from it. A rule outcome of [EvaluationOutcome.POTENTIAL_ISSUE]
     * surfaces as [DocumentState.POTENTIAL_ISSUE]; every other outcome keeps the document
     * readable as [DocumentState.EXTRACTED] so manual reviews and dataset-forbidden conclusions
     * are not conflated with a machine pass/fail.
     */
    private fun ruleBackedState(doc: DocumentUpload, app: Application, documentId: String): DocumentState {
        val catalog = catalogProvider()
        val visa = catalog?.visaTypes?.firstOrNull { it.id == app.visaTypeId } ?: return DocumentState.EXTRACTED
        val outcome = RequirementEvaluator.evaluateForDocument(
            catalog = catalog,
            visaType = visa,
            application = app.copy(documents = app.documents + (documentId to doc)),
            documentId = documentId,
        )?.outcome
        return if (outcome == EvaluationOutcome.POTENTIAL_ISSUE) DocumentState.POTENTIAL_ISSUE else DocumentState.EXTRACTED
    }

    override suspend fun validatePhotograph(applicationId: String, documentId: String): AppResult<Application> {
        val app = current(applicationId) ?: return notFound()
        val doc = app.documents[documentId] ?: return notFound()
        val file = fileStore.resolve(doc.localPath)
            ?: return AppResult.Failure(AppError(ErrorKind.NOT_FOUND, "No local file available to measure."))

        mutate(applicationId) { currentApp ->
            currentApp.withDocument(documentId, doc.documentName) {
                it.copy(state = DocumentState.PROCESSING, lastError = null)
            }
        }

        val catalog = catalogProvider()
        val visa = catalog?.visaTypes?.firstOrNull { it.id == app.visaTypeId }
        val requirement = visa?.documents?.firstOrNull { it.id == documentId }
        val measurements = imageInspector.measure(file)
        val validation = PhotoRequirementEvaluator.evaluate(requirement, measurements)

        val state = when {
            !measurements.isUsable -> DocumentState.NEEDS_REVIEW
            validation.summaryOutcome == EvaluationOutcome.POTENTIAL_ISSUE -> DocumentState.POTENTIAL_ISSUE
            else -> DocumentState.EXTRACTED
        }

        val updated = mutate(applicationId) { currentApp ->
            currentApp.withDocument(documentId, doc.documentName) {
                it.copy(
                    state = state,
                    imageValidation = validation,
                    lastError = if (measurements.isUsable) null else "The image could not be decoded.",
                )
            }.copy(
                updatedAt = System.currentTimeMillis(),
                timeline = currentApp.timeline + TimelineEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    title = "${doc.documentName}: measured on-device",
                    description = if (measurements.isUsable) {
                        "${measurements.widthPx}×${measurements.heightPx}px measured locally."
                    } else {
                        "The image could not be decoded."
                    },
                    kind = TimelineKind.DOCUMENT_PROCESSED,
                ),
            ).let(::recomputeStatus)
        }
        return updated?.let { AppResult.Success(it) } ?: notFound()
    }

    override suspend fun processLocalOcr(applicationId: String, documentId: String): AppResult<Application> {
        val app = current(applicationId) ?: return notFound()
        val doc = app.documents[documentId] ?: return notFound()
        val localPath = doc.localPath ?: return AppResult.Failure(AppError(ErrorKind.NOT_FOUND, "No local file available for OCR."))
        val file = fileStore.resolve(localPath) ?: return AppResult.Failure(AppError(ErrorKind.NOT_FOUND, "File does not exist on disk."))

        // Set state to PROCESSING
        mutate(applicationId) { currentApp ->
            currentApp.withDocument(documentId, doc.documentName) {
                it.copy(state = DocumentState.PROCESSING, lastError = null)
            }
        }

        return when (val ocrResult = ocrEngine.process(file, documentId)) {
            is AppResult.Success -> {
                val extraction = ocrResult.data
                val now = System.currentTimeMillis()
                val candidate = doc.copy(extraction = extraction)
                val newState = if (extraction.fields.isEmpty()) {
                    DocumentState.NEEDS_REVIEW
                } else {
                    ruleBackedState(candidate, app, documentId)
                }
                val updated = mutate(applicationId) { currentApp ->
                    currentApp.withDocument(documentId, doc.documentName) {
                        it.copy(
                            state = newState,
                            extraction = extraction,
                            lastError = if (extraction.fields.isEmpty()) "No structured fields detected." else null,
                        )
                    }.copy(
                        updatedAt = now,
                        timeline = currentApp.timeline + TimelineEvent(
                            id = UUID.randomUUID().toString(),
                            timestamp = now,
                            title = "${doc.documentName}: Offline OCR complete",
                            description = "${extraction.fields.size} field(s) extracted locally.",
                            kind = TimelineKind.DOCUMENT_PROCESSED,
                        ),
                    ).let(::recomputeStatus)
                }
                updated?.let { AppResult.Success(it) } ?: notFound()
            }

            is AppResult.Failure -> {
                val updated = mutate(applicationId) { currentApp ->
                    currentApp.withDocument(documentId, doc.documentName) {
                        it.copy(
                            state = DocumentState.UPLOADED,
                            lastError = ocrResult.error.message,
                        )
                    }
                }
                AppResult.Failure(ocrResult.error)
            }
        }
    }

    override suspend fun deleteDocument(applicationId: String, documentId: String): AppResult<Application> {
        val app = current(applicationId) ?: return notFound()
        val doc = app.documents[documentId]
        if (doc?.localPath != null) {
            fileStore.delete(doc.localPath)
        }
        val now = System.currentTimeMillis()
        val updated = mutate(applicationId) { currentApp ->
            val newDocs = currentApp.documents - documentId
            currentApp.copy(
                documents = newDocs,
                updatedAt = now,
                timeline = currentApp.timeline + TimelineEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = now,
                    title = "${doc?.documentName ?: documentId} deleted",
                    description = "Local file copy and metadata removed.",
                    kind = TimelineKind.STATUS_CHANGED,
                ),
            ).let(::recomputeStatus)
        } ?: return notFound()
        return AppResult.Success(updated)
    }

    override suspend fun markUploading(applicationId: String, documentId: String, progress: Float?): AppResult<Application> {
        val updated = mutate(applicationId) { app ->
            app.withDocument(documentId, app.documents[documentId]?.documentName ?: documentId) {
                it.copy(state = DocumentState.UPLOADING, uploadProgress = progress)
            }
        } ?: return notFound()
        return AppResult.Success(updated)
    }

    override suspend fun markUploadFailed(applicationId: String, documentId: String, error: String): AppResult<Application> {
        val updated = mutate(applicationId) { app ->
            app.withDocument(documentId, app.documents[documentId]?.documentName ?: documentId) {
                it.copy(state = DocumentState.UPLOAD_FAILED, uploadProgress = null, lastError = error)
            }
        } ?: return notFound()
        return AppResult.Success(updated)
    }

    override suspend fun requestManualReview(
        applicationId: String,
        documentId: String,
        reason: String?,
    ): AppResult<Application> {
        val now = System.currentTimeMillis()
        val updated = mutate(applicationId) { app ->
            app.withDocument(documentId, app.documents[documentId]?.documentName ?: documentId) {
                it.copy(state = DocumentState.NEEDS_REVIEW, issueNote = reason, uploadProgress = null)
            }.copy(
                updatedAt = now,
                timeline = app.timeline + TimelineEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = now,
                    title = "Manual review requested",
                    description = reason,
                    kind = TimelineKind.DOCUMENT_NEEDS_REVIEW,
                ),
            ).let(::recomputeStatus)
        } ?: return notFound()

        scope.launch {
            val app = current(applicationId) ?: return@launch
            api.requestManualReview(app.remoteId ?: app.id, documentId, reason)
        }
        return AppResult.Success(updated)
    }

    override suspend fun updateDocumentState(
        applicationId: String,
        documentId: String,
        state: DocumentState,
        issueNote: String?,
        agencyNote: String?,
    ): AppResult<Application> {
        val updated = mutate(applicationId) { app ->
            app.withDocument(documentId, app.documents[documentId]?.documentName ?: documentId) {
                it.copy(state = state, issueNote = issueNote, agencyNote = agencyNote)
            }.let(::recomputeStatus)
        } ?: return notFound()
        return AppResult.Success(updated)
    }

    override suspend fun flagFieldIncorrect(
        applicationId: String,
        documentId: String,
        fieldKey: String,
        incorrect: Boolean,
    ): AppResult<Application> {
        val updated = mutate(applicationId) { app ->
            app.withDocument(documentId, app.documents[documentId]?.documentName ?: documentId) { doc ->
                val extraction = doc.extraction ?: return@withDocument doc
                val fields = extraction.fields.map { field ->
                    if (field.key == fieldKey) field.copy(flaggedIncorrect = incorrect) else field
                }
                val anyFlagged = fields.any { it.flaggedIncorrect }
                val clearedDoc = doc.copy(extraction = extraction.copy(fields = fields))
                doc.copy(
                    extraction = extraction.copy(fields = fields),
                    state = if (anyFlagged) {
                        DocumentState.NEEDS_REVIEW
                    } else {
                        ruleBackedState(clearedDoc, app, documentId)
                    },
                )
            }.let(::recomputeStatus)
        } ?: return notFound()
        return AppResult.Success(updated)
    }

    override suspend fun updateExtractedField(
        applicationId: String,
        documentId: String,
        fieldKey: String,
        newValue: String,
    ): AppResult<Application> {
        val updated = mutate(applicationId) { app ->
            app.withDocument(documentId, app.documents[documentId]?.documentName ?: documentId) { doc ->
                val extraction = doc.extraction ?: return@withDocument doc
                val fields = extraction.fields.map { field ->
                    if (field.key != fieldKey) {
                        field
                    } else {
                        field.copy(
                            originalValue = field.originalValue ?: field.value,
                            value = newValue,
                            userReviewed = true,
                            flaggedIncorrect = false,
                        )
                    }
                }
                val editedDoc = doc.copy(extraction = extraction.copy(fields = fields))
                editedDoc.copy(state = ruleBackedState(editedDoc, app, documentId))
            }.let(::recomputeStatus)
        } ?: return notFound()
        return AppResult.Success(updated)
    }

    override suspend fun confirmField(
        applicationId: String,
        documentId: String,
        fieldKey: String,
        confirmed: Boolean,
    ): AppResult<Application> {
        val updated = mutate(applicationId) { app ->
            app.withDocument(documentId, app.documents[documentId]?.documentName ?: documentId) { doc ->
                val extraction = doc.extraction ?: return@withDocument doc
                val fields = extraction.fields.map { field ->
                    if (field.key == fieldKey) field.copy(userReviewed = confirmed) else field
                }
                doc.copy(extraction = extraction.copy(fields = fields))
            }.let(::recomputeStatus)
        } ?: return notFound()
        return AppResult.Success(updated)
    }

    override suspend fun resolveAgencyComment(applicationId: String, commentId: String): AppResult<Application> {
        val now = System.currentTimeMillis()
        val updated = mutate(applicationId) { app ->
            app.copy(
                agencyComments = app.agencyComments.map {
                    if (it.id == commentId) it.copy(resolved = true) else it
                },
                updatedAt = now,
                timeline = app.timeline + TimelineEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = now,
                    title = "Correction submitted",
                    description = "You marked the agency request as addressed.",
                    kind = TimelineKind.APPLICANT_CORRECTION,
                ),
            ).let(::recomputeStatus)
        } ?: return notFound()
        return AppResult.Success(updated)
    }

    override suspend fun confirmDocumentSharing(
        applicationId: String,
        selectedDocumentIds: List<String>,
    ): AppResult<Application> {
        val app = current(applicationId) ?: return notFound()
        val remoteKey = app.remoteId ?: app.id
        return when (val remote = api.confirmDocumentSharing(remoteKey, selectedDocumentIds)) {
            is AppResult.Failure -> AppResult.Failure(remote.error)
            is AppResult.Success -> {
                mergeRemote(applicationId, remote.data)
                current(applicationId)?.let { AppResult.Success(it) } ?: notFound()
            }
        }
    }

    override suspend fun respondToAppointment(
        applicationId: String,
        appointmentId: String,
        response: AppointmentResponse,
        slotId: String?,
        reason: String?,
    ): AppResult<Application> {
        val app = current(applicationId) ?: return notFound()
        val remoteKey = app.remoteId ?: app.id
        val request = AppointmentResponseRequest(
            action = response.name,
            slotId = slotId,
            reason = reason,
        )
        return when (val remote = api.respondToAppointment(remoteKey, appointmentId, request)) {
            is AppResult.Failure -> AppResult.Failure(remote.error)
            is AppResult.Success -> {
                mergeRemote(applicationId, remote.data)
                current(applicationId)?.let { AppResult.Success(it) } ?: notFound()
            }
        }
    }

    override suspend fun refresh(applicationId: String): AppResult<Application> {
        val app = current(applicationId) ?: return notFound()
        val remoteKey = app.remoteId ?: app.id
        return when (val remote = api.fetchApplication(remoteKey)) {
            is AppResult.Failure -> AppResult.Failure(remote.error)
            is AppResult.Success -> {
                mergeRemote(applicationId, remote.data)
                current(applicationId)?.let { AppResult.Success(it) } ?: notFound()
            }
        }
    }

    override suspend fun deleteApplication(id: String) {
        store.update { state ->
            state.copy(
                applications = state.applications.filterNot { it.id == id },
                activeApplicationId = state.activeApplicationId.takeIf { it != id },
            )
        }
        fileStore.deleteDirectory(id)
        // Deleting the application starts the traveller over, so also drop any
        // agency inquiry raised from it (best-effort server-side withdrawal).
        onApplicationDeleted(id)
    }

    // ---- sync helpers -------------------------------------------------------

    private suspend fun syncCreate(application: Application) {
        val request = CreateApplicationRequest(
            visaTypeId = application.visaTypeId,
            applicant = application.applicant.toDto(),
        )
        when (val result = api.createApplication(request)) {
            is AppResult.Success -> mutate(application.id) { app ->
                app.copy(remoteId = result.data.id, reference = result.data.reference)
            }

            is AppResult.Failure -> Unit
        }
    }

    private suspend fun syncApplicant(application: Application) {
        // PATCH is documented but optional; creation already carries applicant data.
        if (application.remoteId == null) return
    }

    private suspend fun syncUpload(applicationId: String, documentId: String) {
        val app = current(applicationId) ?: return
        val doc = app.documents[documentId] ?: return
        val file = fileStore.resolve(doc.localPath) ?: return
        val key = progressKey(applicationId, documentId)

        val result = api.uploadDocument(
            applicationId = app.remoteId ?: app.id,
            documentId = documentId,
            file = file,
            mimeType = doc.mimeType,
        ) { progress -> uploadProgress.update { it + (key to progress) } }
        uploadProgress.update { it - key }

        when (result) {
            is AppResult.Success -> {
                val remote = result.data
                val now = System.currentTimeMillis()
                val state = parseDocumentState(remote.state)
                val extraction = remote.extractedFields.takeIf { it.isNotEmpty() }?.let { fields ->
                    ExtractionResult(
                        fields = fields.map { ExtractedField(it.key, it.label, it.value, it.confidence) },
                        processedAt = now,
                        provider = "backend",
                    )
                }
                mutate(applicationId) { current ->
                    current.withDocument(documentId, doc.documentName) {
                        it.copy(
                            state = state,
                            remoteDocumentId = remote.remoteDocumentId,
                            extraction = extraction ?: it.extraction,
                            issueNote = remote.issueNote ?: it.issueNote,
                            agencyNote = remote.agencyNote ?: it.agencyNote,
                            uploadProgress = null,
                            lastError = null,
                        )
                    }.copy(
                        updatedAt = now,
                        timeline = current.timeline + TimelineEvent(
                            id = UUID.randomUUID().toString(),
                            timestamp = now,
                            title = "${doc.documentName}: ${state.label}",
                            description = "Updated from the backend.",
                            kind = if (state == DocumentState.EXTRACTED) {
                                TimelineKind.DOCUMENT_PROCESSED
                            } else {
                                TimelineKind.STATUS_CHANGED
                            },
                        ),
                    ).let(::recomputeStatus)
                }
            }

            is AppResult.Failure -> mutate(applicationId) { current ->
                current.withDocument(documentId, doc.documentName) {
                    it.copy(
                        state = DocumentState.UPLOADED,
                        uploadProgress = null,
                        lastError = "Saved on this device. Processing will continue when the backend is reachable.",
                    )
                }
            }
        }
    }

    private suspend fun mergeRemote(applicationId: String, remote: RemoteApplicationDto) {
        mutate(applicationId) { app ->
            val documents = app.documents.toMutableMap()
            remote.documents.forEach { rd ->
                val existing = documents[rd.documentId]
                    ?: DocumentUpload(rd.documentId, rd.documentId)
                documents[rd.documentId] = existing.copy(
                    state = parseDocumentState(rd.state),
                    extraction = rd.extractedFields.takeIf { it.isNotEmpty() }?.let { fields ->
                        ExtractionResult(
                            fields = fields.map { ExtractedField(it.key, it.label, it.value, it.confidence) },
                            processedAt = System.currentTimeMillis(),
                            provider = "backend",
                        )
                    } ?: existing.extraction,
                    issueNote = rd.issueNote ?: existing.issueNote,
                    agencyNote = rd.agencyNote ?: existing.agencyNote,
                    remoteDocumentId = rd.remoteDocumentId ?: existing.remoteDocumentId,
                )
            }

            val timeline = (app.timeline + remote.timeline.map {
                TimelineEvent(
                    id = it.id,
                    timestamp = it.timestamp,
                    title = it.title,
                    description = it.description,
                    kind = runCatching { TimelineKind.valueOf(it.kind) }.getOrDefault(TimelineKind.STATUS_CHANGED),
                )
            }).distinctBy { it.id }.sortedByDescending { it.timestamp }

            val comments = remote.agencyComments.map {
                AgencyComment(
                    id = it.id,
                    documentId = it.documentId,
                    documentName = it.documentName,
                    message = it.message,
                    requestedAt = it.requestedAt,
                    resolved = it.resolved,
                )
            }

            app.copy(
                status = runCatching { ApplicationStatus.valueOf(remote.status) }
                    .getOrDefault(app.status),
                documents = documents,
                timeline = timeline,
                agencyComments = comments.ifEmpty { app.agencyComments },
                handoff = remote.handoff?.toDomain() ?: app.handoff,
                appointment = remote.appointment?.toDomain() ?: app.appointment,
                remoteId = remote.id,
                reference = remote.reference,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    // ---- helpers ------------------------------------------------------------

    private suspend fun mutate(id: String, block: (Application) -> Application): Application? {
        var result: Application? = null
        store.update { state ->
            val records = state.applications.map { record ->
                if (record.id == id) {
                    val updated = block(record.toDomain())
                    result = updated
                    updated.toRecord()
                } else {
                    record
                }
            }
            state.copy(applications = records)
        }
        return result
    }

    private fun current(id: String): Application? =
        store.state.value.applications.firstOrNull { it.id == id }?.toDomain()

    private fun recomputeStatus(app: Application): Application {
        if (app.applicant.fullName.isBlank()) return app.copy(status = ApplicationStatus.DRAFT)
        val visa = catalogProvider()?.visaTypes?.firstOrNull { it.id == app.visaTypeId } ?: return app
        return app.copy(status = ReadinessCalculator.calculate(visa, app).overall)
    }

    private fun applyProgress(app: Application, progress: Map<String, Float>): Application {
        if (progress.isEmpty()) return app
        val documents = app.documents.mapValues { (docId, doc) ->
            val value = progress[progressKey(app.id, docId)]
            if (value != null) doc.copy(state = DocumentState.UPLOADING, uploadProgress = value) else doc
        }
        return app.copy(documents = documents)
    }

    private fun progressKey(applicationId: String, documentId: String) = "$applicationId|$documentId"

    private fun generateReference(now: Long): String {
        val suffix = now.toString(36).takeLast(5).uppercase().padStart(5, '0')
        return "TG-$suffix-${(100..999).random()}"
    }

    private fun parseDocumentState(raw: String): DocumentState =
        runCatching { DocumentState.valueOf(raw) }.getOrDefault(DocumentState.UPLOADED)

    private fun notFound(): AppResult.Failure =
        AppResult.Failure(AppError(ErrorKind.NOT_FOUND, "This application is no longer available."))
}

private fun RemoteHandoffDto.toDomain(): AgencyHandoff = AgencyHandoff(
    id = id,
    agencyName = agencyName,
    purpose = purpose,
    requestedDocumentIds = requestedDocumentIds,
    status = runCatching { HandoffStatus.valueOf(status) }.getOrDefault(HandoffStatus.AWAITING_CONSENT),
    note = note,
    confirmedDocumentIds = confirmedDocumentIds,
)

private fun RemoteAppointmentSlotDto.toDomain(): AppointmentSlot =
    AppointmentSlot(id = id, startAt = startAt, endAt = endAt)

private fun RemoteAppointmentDto.toDomain(): AppointmentInvitation = AppointmentInvitation(
    id = id,
    agencyName = agencyName,
    purpose = purpose,
    proposedStartAt = proposedStartAt,
    proposedEndAt = proposedEndAt,
    location = location,
    alternatives = alternatives.map { it.toDomain() },
    status = runCatching { AppointmentStatus.valueOf(status) }.getOrDefault(AppointmentStatus.AWAITING_RESPONSE),
    message = message,
)

private fun ApplicantDetails.toDto(): ApplicantDto = ApplicantDto(
    fullName = fullName,
    nationality = nationality,
    passportType = passportType,
    purpose = purpose,
    contactEmail = contactEmail.takeIf { it.isNotBlank() },
    contactPhone = contactPhone.takeIf { it.isNotBlank() },
)

private fun Application.withDocument(
    documentId: String,
    documentName: String,
    transform: (DocumentUpload) -> DocumentUpload,
): Application {
    val existing = documents[documentId] ?: DocumentUpload(documentId = documentId, documentName = documentName)
    return copy(documents = documents + (documentId to transform(existing)))
}
