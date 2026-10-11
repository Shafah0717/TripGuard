package com.tripguard.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for the TripGuard FastAPI backend. These mirror the documented contract in
 * `docs/BACKEND_API_CONTRACT.md`. When the backend is not reachable the repositories fall
 * back to bundled/local data and surface an honest offline state.
 */
@Serializable
data class ApplicantDto(
    val fullName: String,
    val nationality: String,
    val passportType: String,
    val purpose: String,
    val contactEmail: String? = null,
    val contactPhone: String? = null,
)

@Serializable
data class CreateApplicationRequest(
    val visaTypeId: String,
    val applicant: ApplicantDto,
)

@Serializable
data class RemoteExtractedFieldDto(
    val key: String,
    val label: String,
    val value: String,
    val confidence: Float? = null,
)

@Serializable
data class RemoteDocumentDto(
    val documentId: String,
    val state: String,
    val extractedFields: List<RemoteExtractedFieldDto> = emptyList(),
    val issueNote: String? = null,
    val agencyNote: String? = null,
    @SerialName("remote_document_id") val remoteDocumentId: String? = null,
)

@Serializable
data class RemoteTimelineDto(
    val id: String,
    val timestamp: Long,
    val title: String,
    val description: String? = null,
    val kind: String = "STATUS_CHANGED",
)

@Serializable
data class RemoteCommentDto(
    val id: String,
    val documentId: String? = null,
    val documentName: String? = null,
    val message: String,
    val requestedAt: Long,
    val resolved: Boolean = false,
)

@Serializable
data class RemoteHandoffDto(
    val id: String,
    val agencyName: String,
    val purpose: String,
    val requestedDocumentIds: List<String> = emptyList(),
    val status: String = "AWAITING_CONSENT",
    val note: String? = null,
    val confirmedDocumentIds: List<String> = emptyList(),
)

@Serializable
data class RemoteAppointmentSlotDto(
    val id: String,
    val startAt: Long,
    val endAt: Long? = null,
)

@Serializable
data class RemoteAppointmentDto(
    val id: String,
    val agencyName: String? = null,
    val purpose: String? = null,
    val proposedStartAt: Long? = null,
    val proposedEndAt: Long? = null,
    val location: String? = null,
    val alternatives: List<RemoteAppointmentSlotDto> = emptyList(),
    val status: String = "AWAITING_RESPONSE",
    val message: String? = null,
)

@Serializable
data class RemoteApplicationDto(
    val id: String,
    val reference: String,
    val visaTypeId: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val documents: List<RemoteDocumentDto> = emptyList(),
    val timeline: List<RemoteTimelineDto> = emptyList(),
    val agencyComments: List<RemoteCommentDto> = emptyList(),
    val handoff: RemoteHandoffDto? = null,
    val appointment: RemoteAppointmentDto? = null,
)

@Serializable
data class ShareConsentRequest(
    val selectedDocumentIds: List<String>,
    val consent: Boolean = true,
)

@Serializable
data class AppointmentResponseRequest(
    val action: String,
    val slotId: String? = null,
    val reason: String? = null,
)

@Serializable
data class ManualReviewRequest(
    val reason: String? = null,
)

@Serializable
data class DocumentPolicyDto(
    val acceptedExtensions: List<String> = listOf("pdf", "jpg", "jpeg", "png"),
    val maxFileSizeBytes: Long = 10L * 1024 * 1024,
    val source: String = "backend",
)

@Serializable
data class ApiErrorDto(
    val detail: String? = null,
    val message: String? = null,
)
