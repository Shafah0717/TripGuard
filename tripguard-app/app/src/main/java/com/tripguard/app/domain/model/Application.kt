package com.tripguard.app.domain.model

/** Applicant answers genuinely needed by the workflow. Nationality, passport type and
 *  purpose are fixed to the supported scope (Indian ordinary passport, tourism). */
data class ApplicantDetails(
    val fullName: String = "",
    val nationality: String = SUPPORTED_NATIONALITY,
    val passportType: String = SUPPORTED_PASSPORT_TYPE,
    val purpose: String = SUPPORTED_PURPOSE,
    val contactEmail: String = "",
    val contactPhone: String = "",
) {
    companion object {
        const val SUPPORTED_NATIONALITY = "Indian"
        const val SUPPORTED_PASSPORT_TYPE = "Ordinary passport"
        const val SUPPORTED_PURPOSE = "Tourism"
    }
}

enum class ApplicationStatus {
    DRAFT,
    IN_PROGRESS,
    ACTION_REQUIRED,
    UNDER_REVIEW,
    READY_FOR_AGENCY,
    ;

    val label: String
        get() = when (this) {
            DRAFT -> "Draft"
            IN_PROGRESS -> "In progress"
            ACTION_REQUIRED -> "Action required"
            UNDER_REVIEW -> "Under review"
            READY_FOR_AGENCY -> "Ready for agency processing"
        }
}

/**
 * Document lifecycle. OCR completion ([EXTRACTED]) is deliberately distinct from agency
 * acceptance ([ACCEPTED]); the UI must never conflate the two.
 */
enum class DocumentState {
    NOT_UPLOADED,
    UPLOADING,
    UPLOAD_FAILED,
    UPLOADED,
    PROCESSING,
    EXTRACTED,
    NEEDS_REVIEW,
    POTENTIAL_ISSUE,
    ACCEPTED,
    ;

    val label: String
        get() = when (this) {
            NOT_UPLOADED -> "Not uploaded"
            UPLOADING -> "Uploading"
            UPLOAD_FAILED -> "Upload failed"
            UPLOADED -> "Uploaded"
            PROCESSING -> "Processing"
            EXTRACTED -> "Extracted"
            NEEDS_REVIEW -> "Needs review"
            POTENTIAL_ISSUE -> "Potential issue"
            ACCEPTED -> "Accepted by agency"
        }
}

/** How the raw text behind an extraction was obtained. Never fabricated. */
enum class OcrMethod(val code: String, val label: String) {
    ML_KIT_OCR("ML_KIT_OCR", "On-device ML Kit OCR"),
    PDF_TEXT_EXTRACTION("PDF_TEXT_EXTRACTION", "PDF text extraction"),
    PLAIN_TEXT("PLAIN_TEXT", "Plain-text read"),
    UNKNOWN("UNKNOWN", "Unknown method"),
    ;

    companion object {
        fun from(code: String?): OcrMethod =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * One field read from a document. The three concepts — OCR extraction, user review, and
 * requirement evaluation — are kept separate: [value] is the current (possibly corrected)
 * candidate, [originalValue] preserves what OCR produced, and [userReviewed] records that a
 * human confirmed it. None of these establishes authenticity.
 */
data class ExtractedField(
    val key: String,
    val label: String,
    val value: String,
    val confidence: Float? = null,
    val flaggedIncorrect: Boolean = false,
    val sourceDocumentId: String? = null,
    val method: OcrMethod? = null,
    val userReviewed: Boolean = false,
    val originalValue: String? = null,
    val warning: String? = null,
)

data class ExtractionResult(
    val fields: List<ExtractedField>,
    val processedAt: Long,
    val provider: String? = null,
    val overallConfidence: Float? = null,
    val method: OcrMethod = OcrMethod.UNKNOWN,
    val rawText: String? = null,
    val pageCount: Int? = null,
    val warnings: List<String> = emptyList(),
)

/** Result of measuring a personal photograph locally. Illustrative values are never stored. */
data class ImageValidation(
    val measurements: PhotoMeasurements,
    val checks: List<PhotoRequirementCheck>,
    val summaryOutcome: EvaluationOutcome,
    val reason: String,
    val validatedAt: Long,
)

data class PhotoMeasurements(
    val widthPx: Int,
    val heightPx: Int,
    val sizeBytes: Long,
    val decodable: Boolean,
    val mimeType: String? = null,
    val extension: String? = null,
) {
    val isUsable: Boolean get() = decodable && widthPx > 0 && heightPx > 0

    val aspectRatio: Float get() = if (heightPx > 0) widthPx.toFloat() / heightPx.toFloat() else 0f

    /** Reduced aspect ratio such as "3:4"; falls back to the raw ratio when height is zero. */
    val aspectRatioLabel: String
        get() {
            if (widthPx <= 0 || heightPx <= 0) return "Unknown"
            val divisor = greatestCommonDivisor(widthPx, heightPx)
            return "${widthPx / divisor}:${heightPx / divisor}"
        }

    private fun greatestCommonDivisor(a: Int, b: Int): Int {
        var x = kotlin.math.abs(a)
        var y = kotlin.math.abs(b)
        while (y != 0) {
            val t = y
            y = x % y
            x = t
        }
        return if (x == 0) 1 else x
    }
}

/** A single photo-specific check derived from the dataset (or its absence). */
data class PhotoRequirementCheck(
    val name: String,
    val configured: Boolean,
    val outcome: EvaluationOutcome,
    val detail: String,
)

data class DocumentUpload(
    val documentId: String,
    val documentName: String,
    val state: DocumentState = DocumentState.NOT_UPLOADED,
    val localPath: String? = null,
    val fileName: String? = null,
    val fileSizeBytes: Long = 0L,
    val mimeType: String? = null,
    val uploadProgress: Float? = null,
    val uploadedAt: Long? = null,
    val extraction: ExtractionResult? = null,
    val imageValidation: ImageValidation? = null,
    val issueNote: String? = null,
    val agencyNote: String? = null,
    val remoteDocumentId: String? = null,
    val lastError: String? = null,
) {
    val hasFile: Boolean get() = localPath != null
}

enum class TimelineKind {
    CREATED,
    DOCUMENT_UPLOADED,
    DOCUMENT_PROCESSED,
    DOCUMENT_NEEDS_REVIEW,
    STATUS_CHANGED,
    AGENCY_COMMENT,
    CORRECTION_REQUESTED,
    APPLICANT_CORRECTION,
    SHARE_CONSENT_REQUESTED,
    SHARE_CONSENT_CONFIRMED,
    APPOINTMENT_PROPOSED,
    APPOINTMENT_RESPONDED,
    AGENCY_PROCESSING_STARTED,
    SYNCED,
}

data class TimelineEvent(
    val id: String,
    val timestamp: Long,
    val title: String,
    val description: String? = null,
    val kind: TimelineKind,
)

data class AgencyComment(
    val id: String,
    val documentId: String?,
    val documentName: String?,
    val message: String,
    val requestedAt: Long,
    val resolved: Boolean = false,
)

/**
 * Agency readiness handoff. When an agency marks an application ready for processing it may ask
 * the traveler to confirm which documents to share. Consent is never implied: only a backend
 * acknowledgement of an explicit selection moves this out of [HandoffStatus.AWAITING_CONSENT].
 */
enum class HandoffStatus {
    AWAITING_CONSENT,
    SUBMITTING,
    CONFIRMED,
    DECLINED,
    FAILED,
    ;

    val label: String
        get() = when (this) {
            AWAITING_CONSENT -> "Awaiting your confirmation"
            SUBMITTING -> "Submitting your choice…"
            CONFIRMED -> "Shared with agency"
            DECLINED -> "Sharing declined"
            FAILED -> "Sharing not completed"
        }
}

data class AgencyHandoff(
    val id: String,
    val agencyName: String,
    val purpose: String,
    val requestedDocumentIds: List<String>,
    val status: HandoffStatus,
    val note: String? = null,
    val confirmedDocumentIds: List<String> = emptyList(),
) {
    val awaitingConsent: Boolean get() = status == HandoffStatus.AWAITING_CONSENT
}

enum class AppointmentStatus {
    AWAITING_RESPONSE,
    ACCEPTED,
    CHANGE_REQUESTED,
    DECLINED,
    CONFIRMED,
    CANCELLED,
    ;

    val label: String
        get() = when (this) {
            AWAITING_RESPONSE -> "Awaiting your response"
            ACCEPTED -> "Accepted"
            CHANGE_REQUESTED -> "Change requested"
            DECLINED -> "Declined"
            CONFIRMED -> "Confirmed by agency"
            CANCELLED -> "Cancelled"
        }
}

data class AppointmentSlot(
    val id: String,
    val startAt: Long,
    val endAt: Long? = null,
)

enum class AppointmentResponse {
    ACCEPT,
    CHANGE,
    DECLINE,
}

data class AppointmentInvitation(
    val id: String,
    val agencyName: String?,
    val purpose: String?,
    val proposedStartAt: Long?,
    val proposedEndAt: Long? = null,
    val location: String? = null,
    val alternatives: List<AppointmentSlot> = emptyList(),
    val status: AppointmentStatus,
    val message: String? = null,
) {
    val needsResponse: Boolean get() = status == AppointmentStatus.AWAITING_RESPONSE
}

data class Application(
    val id: String,
    val reference: String,
    val visaTypeId: String,
    val visaTypeName: String,
    val applicant: ApplicantDetails = ApplicantDetails(),
    val status: ApplicationStatus = ApplicationStatus.DRAFT,
    val createdAt: Long,
    val updatedAt: Long,
    val documents: Map<String, DocumentUpload> = emptyMap(),
    val timeline: List<TimelineEvent> = emptyList(),
    val agencyComments: List<AgencyComment> = emptyList(),
    val handoff: AgencyHandoff? = null,
    val appointment: AppointmentInvitation? = null,
    val remoteId: String? = null,
)

/** Minimal summary used by the "My Applications" list. */
data class ApplicationSummary(
    val id: String,
    val reference: String,
    val visaTypeName: String,
    val status: ApplicationStatus,
    val createdAt: Long,
    val requiredTotal: Int,
    val requiredUploaded: Int,
)
