package com.tripguard.app.data.local

import com.tripguard.app.domain.model.AgencyComment
import com.tripguard.app.domain.model.AgencyHandoff
import com.tripguard.app.domain.model.ApplicantDetails
import com.tripguard.app.domain.model.Application
import com.tripguard.app.domain.model.ApplicationStatus
import com.tripguard.app.domain.model.AppointmentInvitation
import com.tripguard.app.domain.model.AppointmentSlot
import com.tripguard.app.domain.model.AppointmentStatus
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.DocumentUpload
import com.tripguard.app.domain.model.EvaluationOutcome
import com.tripguard.app.domain.model.ExtractedField
import com.tripguard.app.domain.model.ExtractionResult
import com.tripguard.app.domain.model.HandoffStatus
import com.tripguard.app.domain.model.ImageValidation
import com.tripguard.app.domain.model.OcrMethod
import com.tripguard.app.domain.model.PhotoMeasurements
import com.tripguard.app.domain.model.PhotoRequirementCheck
import com.tripguard.app.domain.model.TimelineEvent
import com.tripguard.app.domain.model.TimelineKind
import kotlinx.serialization.Serializable

@Serializable
data class PersistedState(
    val applications: List<ApplicationRecord> = emptyList(),
    val activeApplicationId: String? = null,
    val baseUrlOverride: String? = null,
    /** Locally retained agency-inquiry reference + token, if any. */
    val agencyInquiry: AgencyInquiryRecord? = null,
    /** Latest known booking for that inquiry, if any. */
    val agencyBooking: AgencyBookingRecord? = null,
)

@Serializable
data class ApplicationRecord(
    val id: String,
    val reference: String,
    val visaTypeId: String,
    val visaTypeName: String,
    val applicant: ApplicantRecord = ApplicantRecord(),
    val status: String = ApplicationStatus.DRAFT.name,
    val createdAt: Long,
    val updatedAt: Long,
    val documents: List<DocumentRecord> = emptyList(),
    val timeline: List<TimelineRecord> = emptyList(),
    val agencyComments: List<CommentRecord> = emptyList(),
    val handoff: HandoffRecord? = null,
    val appointment: AppointmentRecord? = null,
    val remoteId: String? = null,
)

@Serializable
data class ApplicantRecord(
    val fullName: String = "",
    val nationality: String = ApplicantDetails.SUPPORTED_NATIONALITY,
    val passportType: String = ApplicantDetails.SUPPORTED_PASSPORT_TYPE,
    val purpose: String = ApplicantDetails.SUPPORTED_PURPOSE,
    val contactEmail: String = "",
    val contactPhone: String = "",
)

@Serializable
data class DocumentRecord(
    val documentId: String,
    val documentName: String,
    val state: String = DocumentState.NOT_UPLOADED.name,
    val localPath: String? = null,
    val fileName: String? = null,
    val fileSizeBytes: Long = 0L,
    val mimeType: String? = null,
    val uploadedAt: Long? = null,
    val extraction: ExtractionRecord? = null,
    val imageValidation: ImageValidationRecord? = null,
    val issueNote: String? = null,
    val agencyNote: String? = null,
    val remoteDocumentId: String? = null,
    val lastError: String? = null,
)

@Serializable
data class ExtractionRecord(
    val fields: List<FieldRecord> = emptyList(),
    val processedAt: Long,
    val provider: String? = null,
    val overallConfidence: Float? = null,
    val method: String = OcrMethod.UNKNOWN.code,
    val rawText: String? = null,
    val pageCount: Int? = null,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class FieldRecord(
    val key: String,
    val label: String,
    val value: String,
    val confidence: Float? = null,
    val flaggedIncorrect: Boolean = false,
    val sourceDocumentId: String? = null,
    val method: String? = null,
    val userReviewed: Boolean = false,
    val originalValue: String? = null,
    val warning: String? = null,
)

@Serializable
data class ImageValidationRecord(
    val widthPx: Int,
    val heightPx: Int,
    val sizeBytes: Long,
    val decodable: Boolean,
    val mimeType: String? = null,
    val extension: String? = null,
    val checks: List<PhotoCheckRecord> = emptyList(),
    val summaryOutcome: String = EvaluationOutcome.COULD_NOT_VERIFY.name,
    val reason: String = "",
    val validatedAt: Long,
)

@Serializable
data class PhotoCheckRecord(
    val name: String,
    val configured: Boolean,
    val outcome: String,
    val detail: String,
)

@Serializable
data class TimelineRecord(
    val id: String,
    val timestamp: Long,
    val title: String,
    val description: String? = null,
    val kind: String = TimelineKind.CREATED.name,
)

@Serializable
data class CommentRecord(
    val id: String,
    val documentId: String? = null,
    val documentName: String? = null,
    val message: String,
    val requestedAt: Long,
    val resolved: Boolean = false,
)

@Serializable
data class HandoffRecord(
    val id: String,
    val agencyName: String,
    val purpose: String,
    val requestedDocumentIds: List<String> = emptyList(),
    val status: String = HandoffStatus.AWAITING_CONSENT.name,
    val note: String? = null,
    val confirmedDocumentIds: List<String> = emptyList(),
)

@Serializable
data class SlotRecord(
    val id: String,
    val startAt: Long,
    val endAt: Long? = null,
)

@Serializable
data class AppointmentRecord(
    val id: String,
    val agencyName: String? = null,
    val purpose: String? = null,
    val proposedStartAt: Long? = null,
    val proposedEndAt: Long? = null,
    val location: String? = null,
    val alternatives: List<SlotRecord> = emptyList(),
    val status: String = AppointmentStatus.AWAITING_RESPONSE.name,
    val message: String? = null,
)

/** Locally retained agency inquiry so the traveller can revisit after restart. */
@Serializable
data class AgencyInquiryRecord(
    val inquiryId: Long,
    val reference: String,
    val status: String,
    val visaRoute: String,
    val travellerName: String,
    val travellerToken: String,
    val createdAt: String,
    val updatedAt: String,
)

/** Latest known booking state for the retained inquiry. */
@Serializable
data class AgencyBookingRecord(
    val reference: String,
    val status: String,
    val inquiryReference: String,
    val slotStartAt: String? = null,
    val slotEndAt: String? = null,
    val slotTimezone: String? = null,
    val slotLocation: String? = null,
    val createdAt: String,
    val cancelledAt: String? = null,
    val agencyNote: String? = null,
)

// ---- Domain -> record -------------------------------------------------------

fun Application.toRecord(): ApplicationRecord = ApplicationRecord(
    id = id,
    reference = reference,
    visaTypeId = visaTypeId,
    visaTypeName = visaTypeName,
    applicant = ApplicantRecord(
        fullName = applicant.fullName,
        nationality = applicant.nationality,
        passportType = applicant.passportType,
        purpose = applicant.purpose,
        contactEmail = applicant.contactEmail,
        contactPhone = applicant.contactPhone,
    ),
    status = status.name,
    createdAt = createdAt,
    updatedAt = updatedAt,
    documents = documents.values.map { it.toRecord() },
    timeline = timeline.map { it.toRecord() },
    agencyComments = agencyComments.map { it.toRecord() },
    handoff = handoff?.toRecord(),
    appointment = appointment?.toRecord(),
    remoteId = remoteId,
)

fun DocumentUpload.toRecord(): DocumentRecord = DocumentRecord(
    documentId = documentId,
    documentName = documentName,
    state = state.name,
    localPath = localPath,
    fileName = fileName,
    fileSizeBytes = fileSizeBytes,
    mimeType = mimeType,
    uploadedAt = uploadedAt,
    extraction = extraction?.toRecord(),
    imageValidation = imageValidation?.toRecord(),
    issueNote = issueNote,
    agencyNote = agencyNote,
    remoteDocumentId = remoteDocumentId,
    lastError = lastError,
)

fun ExtractionResult.toRecord(): ExtractionRecord = ExtractionRecord(
    fields = fields.map { it.toRecord() },
    processedAt = processedAt,
    provider = provider,
    overallConfidence = overallConfidence,
    method = method.code,
    rawText = rawText,
    pageCount = pageCount,
    warnings = warnings,
)

fun ExtractedField.toRecord(): FieldRecord = FieldRecord(
    key = key,
    label = label,
    value = value,
    confidence = confidence,
    flaggedIncorrect = flaggedIncorrect,
    sourceDocumentId = sourceDocumentId,
    method = method?.code,
    userReviewed = userReviewed,
    originalValue = originalValue,
    warning = warning,
)

fun ImageValidation.toRecord(): ImageValidationRecord = ImageValidationRecord(
    widthPx = measurements.widthPx,
    heightPx = measurements.heightPx,
    sizeBytes = measurements.sizeBytes,
    decodable = measurements.decodable,
    mimeType = measurements.mimeType,
    extension = measurements.extension,
    checks = checks.map { PhotoCheckRecord(it.name, it.configured, it.outcome.name, it.detail) },
    summaryOutcome = summaryOutcome.name,
    reason = reason,
    validatedAt = validatedAt,
)

fun TimelineEvent.toRecord(): TimelineRecord =
    TimelineRecord(id, timestamp, title, description, kind.name)

fun AgencyComment.toRecord(): CommentRecord =
    CommentRecord(id, documentId, documentName, message, requestedAt, resolved)

fun AgencyHandoff.toRecord(): HandoffRecord =
    HandoffRecord(id, agencyName, purpose, requestedDocumentIds, status.name, note, confirmedDocumentIds)

fun AppointmentInvitation.toRecord(): AppointmentRecord =
    AppointmentRecord(
        id = id,
        agencyName = agencyName,
        purpose = purpose,
        proposedStartAt = proposedStartAt,
        proposedEndAt = proposedEndAt,
        location = location,
        alternatives = alternatives.map { SlotRecord(it.id, it.startAt, it.endAt) },
        status = status.name,
        message = message,
    )

// ---- Record -> domain -------------------------------------------------------

fun ApplicationRecord.toDomain(): Application = Application(
    id = id,
    reference = reference,
    visaTypeId = visaTypeId,
    visaTypeName = visaTypeName,
    applicant = ApplicantDetails(
        fullName = applicant.fullName,
        nationality = applicant.nationality,
        passportType = applicant.passportType,
        purpose = applicant.purpose,
        contactEmail = applicant.contactEmail,
        contactPhone = applicant.contactPhone,
    ),
    status = runCatching { ApplicationStatus.valueOf(status) }.getOrDefault(ApplicationStatus.DRAFT),
    createdAt = createdAt,
    updatedAt = updatedAt,
    documents = documents.associate { it.documentId to it.toDomain() },
    timeline = timeline.map { it.toDomain() }.sortedByDescending { it.timestamp },
    agencyComments = agencyComments.map { it.toDomain() },
    handoff = handoff?.toDomain(),
    appointment = appointment?.toDomain(),
    remoteId = remoteId,
)

fun DocumentRecord.toDomain(): DocumentUpload = DocumentUpload(
    documentId = documentId,
    documentName = documentName,
    state = runCatching { DocumentState.valueOf(state) }.getOrDefault(DocumentState.NOT_UPLOADED),
    localPath = localPath,
    fileName = fileName,
    fileSizeBytes = fileSizeBytes,
    mimeType = mimeType,
    uploadedAt = uploadedAt,
    extraction = extraction?.toDomain(),
    imageValidation = imageValidation?.toDomain(),
    issueNote = issueNote,
    agencyNote = agencyNote,
    remoteDocumentId = remoteDocumentId,
    lastError = lastError,
)

fun ExtractionRecord.toDomain(): ExtractionResult = ExtractionResult(
    fields = fields.map { it.toDomain() },
    processedAt = processedAt,
    provider = provider,
    overallConfidence = overallConfidence,
    method = OcrMethod.from(method),
    rawText = rawText,
    pageCount = pageCount,
    warnings = warnings,
)

fun FieldRecord.toDomain(): ExtractedField = ExtractedField(
    key = key,
    label = label,
    value = value,
    confidence = confidence,
    flaggedIncorrect = flaggedIncorrect,
    sourceDocumentId = sourceDocumentId,
    method = method?.let(OcrMethod::from),
    userReviewed = userReviewed,
    originalValue = originalValue,
    warning = warning,
)

fun ImageValidationRecord.toDomain(): ImageValidation = ImageValidation(
    measurements = PhotoMeasurements(
        widthPx = widthPx,
        heightPx = heightPx,
        sizeBytes = sizeBytes,
        decodable = decodable,
        mimeType = mimeType,
        extension = extension,
    ),
    checks = checks.map {
        PhotoRequirementCheck(
            name = it.name,
            configured = it.configured,
            outcome = runCatching { EvaluationOutcome.valueOf(it.outcome) }
                .getOrDefault(EvaluationOutcome.COULD_NOT_VERIFY),
            detail = it.detail,
        )
    },
    summaryOutcome = runCatching { EvaluationOutcome.valueOf(summaryOutcome) }
        .getOrDefault(EvaluationOutcome.COULD_NOT_VERIFY),
    reason = reason,
    validatedAt = validatedAt,
)

fun TimelineRecord.toDomain(): TimelineEvent = TimelineEvent(
    id = id,
    timestamp = timestamp,
    title = title,
    description = description,
    kind = runCatching { TimelineKind.valueOf(kind) }.getOrDefault(TimelineKind.CREATED),
)

fun CommentRecord.toDomain(): AgencyComment = AgencyComment(
    id = id,
    documentId = documentId,
    documentName = documentName,
    message = message,
    requestedAt = requestedAt,
    resolved = resolved,
)

fun HandoffRecord.toDomain(): AgencyHandoff = AgencyHandoff(
    id = id,
    agencyName = agencyName,
    purpose = purpose,
    requestedDocumentIds = requestedDocumentIds,
    status = runCatching { HandoffStatus.valueOf(status) }.getOrDefault(HandoffStatus.AWAITING_CONSENT),
    note = note,
    confirmedDocumentIds = confirmedDocumentIds,
)

fun AppointmentRecord.toDomain(): AppointmentInvitation = AppointmentInvitation(
    id = id,
    agencyName = agencyName,
    purpose = purpose,
    proposedStartAt = proposedStartAt,
    proposedEndAt = proposedEndAt,
    location = location,
    alternatives = alternatives.map { AppointmentSlot(it.id, it.startAt, it.endAt) },
    status = runCatching { AppointmentStatus.valueOf(status) }.getOrDefault(AppointmentStatus.AWAITING_RESPONSE),
    message = message,
)
