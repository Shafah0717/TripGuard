package com.tripguard.app.domain.readiness

import com.tripguard.app.domain.model.AgencyComment
import com.tripguard.app.domain.model.Application
import com.tripguard.app.domain.model.ApplicationStatus
import com.tripguard.app.domain.model.ChecklistItem
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.RequirementApplicability
import com.tripguard.app.domain.model.VisaType
import java.util.concurrent.TimeUnit

/** Aggregate readiness view derived purely from the dataset + persisted application state. */
data class ReadinessReport(
    val overall: ApplicationStatus,
    val requiredTotal: Int,
    val requiredUploaded: Int,
    val requiredAccepted: Int,
    val missingItems: List<ChecklistItem>,
    val processingItems: List<ChecklistItem>,
    val needsReviewItems: List<ChecklistItem>,
    val potentialIssueItems: List<ChecklistItem>,
    val acceptedItems: List<ChecklistItem>,
    val outstandingAgencyActions: List<AgencyComment>,
    val passportExpiryWarning: String?,
    val nextStep: String,
) {
    val hasBlockingIssues: Boolean
        get() = missingItems.isNotEmpty() || needsReviewItems.isNotEmpty() ||
            potentialIssueItems.isNotEmpty() || outstandingAgencyActions.isNotEmpty()

    val progressFraction: Float
        get() = if (requiredTotal == 0) 0f else requiredUploaded.toFloat() / requiredTotal.toFloat()
}

/**
 * Deterministic, side-effect-free calculator. A completed checklist is never presented as a
 * guarantee of visa eligibility or approval.
 */
object ReadinessCalculator {

    private const val MIN_PASSPORT_VALIDITY_DAYS = 180L

    fun calculate(visaType: VisaType, application: Application): ReadinessReport {
        val required = visaType.checklist.filter { it.applicability == RequirementApplicability.REQUIRED }

        val missing = mutableListOf<ChecklistItem>()
        val processing = mutableListOf<ChecklistItem>()
        val needsReview = mutableListOf<ChecklistItem>()
        val potentialIssues = mutableListOf<ChecklistItem>()
        val accepted = mutableListOf<ChecklistItem>()

        var uploadedCount = 0

        required.forEach { item ->
            val doc = application.documents[item.documentId]
            val state = doc?.state ?: DocumentState.NOT_UPLOADED

            if (state != DocumentState.NOT_UPLOADED && state != DocumentState.UPLOAD_FAILED) {
                uploadedCount++
            }

            when (state) {
                DocumentState.NOT_UPLOADED,
                DocumentState.UPLOAD_FAILED,
                -> missing += item

                DocumentState.UPLOADING,
                DocumentState.UPLOADED,
                DocumentState.PROCESSING,
                -> processing += item

                DocumentState.NEEDS_REVIEW -> needsReview += item
                DocumentState.POTENTIAL_ISSUE -> potentialIssues += item
                DocumentState.ACCEPTED -> accepted += item
                DocumentState.EXTRACTED -> {
                    val flagged = doc?.extraction?.fields?.any { it.flaggedIncorrect } == true
                    if (flagged) needsReview += item else processing += item
                }
            }
        }

        // Passport-expiry human-review flag derived from real extracted data only.
        val passportItem = visaType.checklist.firstOrNull { it.documentId.contains("passport") }
        val passportExpiryWarning = passportItem?.let {
            val expiry = application.documents[it.documentId]
                ?.extraction?.fields
                ?.firstOrNull { f -> f.key.contains("expiry", ignoreCase = true) }
                ?.value
            expiry?.let { value -> buildExpiryWarning(value) }
        }

        val outstanding = application.agencyComments.filter { !it.resolved }

        val overall = when {
            outstanding.isNotEmpty() || needsReview.isNotEmpty() || potentialIssues.isNotEmpty() ->
                ApplicationStatus.ACTION_REQUIRED

            missing.isNotEmpty() -> ApplicationStatus.IN_PROGRESS
            processing.isNotEmpty() -> ApplicationStatus.UNDER_REVIEW
            required.isNotEmpty() -> ApplicationStatus.READY_FOR_AGENCY
            else -> ApplicationStatus.DRAFT
        }

        return ReadinessReport(
            overall = overall,
            requiredTotal = required.size,
            requiredUploaded = uploadedCount,
            requiredAccepted = accepted.size,
            missingItems = missing,
            processingItems = processing,
            needsReviewItems = needsReview,
            potentialIssueItems = potentialIssues,
            acceptedItems = accepted,
            outstandingAgencyActions = outstanding,
            passportExpiryWarning = passportExpiryWarning,
            nextStep = nextStepFor(overall, missing.size, needsReview.size + potentialIssues.size, outstanding.size),
        )
    }

    private fun nextStepFor(
        overall: ApplicationStatus,
        missing: Int,
        review: Int,
        agency: Int,
    ): String = when (overall) {
        ApplicationStatus.ACTION_REQUIRED -> when {
            agency > 0 -> "Open the correction request and replace the document your agency flagged."
            review > 0 -> "Review the flagged document and replace it if the information is wrong."
            else -> "Resolve the flagged items before submitting to your agency."
        }

        ApplicationStatus.IN_PROGRESS -> "Upload the $missing remaining document(s) to complete your checklist."
        ApplicationStatus.UNDER_REVIEW -> "Documents are uploaded. Processing or agency review is in progress."
        ApplicationStatus.READY_FOR_AGENCY -> "Your checklist is complete. Your agency can now review the application."
        ApplicationStatus.DRAFT -> "Select a visa type and add your applicant details to begin."
    }

    private fun buildExpiryWarning(rawValue: String): String? {
        val parsed = runCatching { java.time.LocalDate.parse(rawValue.trim()) }.getOrNull() ?: return null
        val days = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), parsed)
        return when {
            days < 0 -> "The passport in this document appears expired. Confirm and replace it."
            days < MIN_PASSPORT_VALIDITY_DAYS ->
                "The extracted passport expiry is under six months away. Most routes require at least six months' validity — confirm with your agency."

            else -> null
        }
    }
}
