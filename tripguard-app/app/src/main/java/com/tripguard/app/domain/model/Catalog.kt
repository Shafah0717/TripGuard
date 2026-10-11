package com.tripguard.app.domain.model

/**
 * Domain representation of the versioned visa-requirements dataset that ships with the app
 * and is also served by the backend. The UI reads every requirement from here — no checklist
 * is hardcoded in a screen.
 */
data class VisaCatalog(
    val datasetId: String,
    val schemaVersion: String,
    val destinationCountry: String,
    val destinationEmirate: String,
    val applicantScope: ApplicantScope,
    val visaTypes: List<VisaType>,
    val sources: Map<String, SourceRef>,
    val limitations: List<String>,
    val verificationPolicy: VerificationPolicy,
    val lastVerifiedAt: String?,
)

data class ApplicantScope(
    val nationality: String,
    val passportType: String,
    val purpose: String,
    val unsupportedApplicants: List<String>,
    val scopeEnforcement: String?,
)

data class SourceRef(
    val id: String,
    val title: String,
    val organization: String,
    val url: String,
    val sourceType: String,
)

data class VerificationPolicy(
    val mode: String,
    val automaticChecksAllowed: List<String>,
    val neverAutomaticallyDecide: List<String>,
    val lowConfidenceAction: String?,
    val officialDecisionDisclaimer: String?,
)

data class VisaType(
    val id: String,
    val officialName: String,
    val displayName: String,
    val category: String,
    val productStatus: String,
    val entryType: String,
    val permittedStay: StaySpec,
    val visaValidity: StaySpec,
    val eligibility: Eligibility,
    val applicationRoute: ApplicationRoute,
    val financialRequirements: List<FinancialRequirement>,
    val documents: List<DocumentRequirement>,
    val conditionalDocuments: List<ConditionalDocument>,
    val fees: List<Fee>,
    val processingTime: ProcessingTime?,
    val extensionNotes: List<String>,
    val restrictions: List<String>,
    val sourceIds: List<String>,
    val lastVerifiedAt: String?,
    val humanReviewRequired: Boolean,
    val applicantScope: ApplicantScope?,
) {
    /** Short human label for entry type, e.g. "Single entry". */
    val entryTypeLabel: String
        get() = when (entryType.lowercase()) {
            "single" -> "Single entry"
            "multiple" -> "Multiple entry"
            else -> entryType.replaceFirstChar { it.uppercase() }
        }

    /**
     * The complete, route-aware checklist for this visa type, combining mandatory documents
     * with conditional documents, each mapped to an unequivocal applicability.
     */
    val checklist: List<ChecklistItem>
        get() = documents.map { it.toChecklistItem() } +
            conditionalDocuments.map { it.toChecklistItem() }

    val isVerifiedEligibility: Boolean get() = eligibility.verified
}

data class StaySpec(
    val value: Int?,
    val unit: String,
    val conditions: List<String>,
) {
    val display: String
        get() = if (value == null) {
            unit.replaceFirstChar { it.uppercase() } + " to be confirmed"
        } else {
            "$value ${unit.lowercase()}"
        }
}

data class Eligibility(
    val nationality: String?,
    val conditions: List<String>,
    val verified: Boolean,
    val sourceIds: List<String>,
)

data class ApplicationRoute(
    val sponsorRequired: Boolean,
    val authorizedChannels: List<String>,
    val steps: List<String>,
    val routeNotes: List<String>,
)

data class FinancialRequirement(
    val id: String,
    val type: String,
    val amount: Double?,
    val currency: String?,
    val period: String?,
    val requirementStatus: String,
    val evidenceNotes: List<String>,
    val machineCheckAllowed: Boolean,
)

data class Fee(
    val amount: Double?,
    val currency: String?,
    val description: String,
) {
    val display: String?
        get() = if (amount == null) null else {
            val symbol = when (currency) {
                "AED" -> "AED "
                "USD" -> "USD "
                else -> (currency ?: "") + " "
            }
            val rounded = if (amount % 1.0 == 0.0) amount.toLong().toString() else amount.toString()
            "$symbol$rounded"
        }
}

data class ProcessingTime(
    val value: Int?,
    val unit: String,
    val conditions: List<String>,
) {
    val display: String?
        get() = if (value == null) null else "$value ${unit.lowercase()}"
}

/** A raw document requirement as declared in the dataset. */
data class DocumentRequirement(
    val id: String,
    val name: String,
    val description: String?,
    val requirementStatus: String,
    val applicabilityScope: String?,
    val applicabilityConditions: List<String>,
    val mandatory: Boolean,
    val specificationNotes: List<String>,
    val acceptedFormats: List<String>,
    val imageRequirements: List<String>,
    val minimumValidityDays: Int?,
    val verificationNotes: List<String>,
    val verificationRules: List<VerificationRule>,
    val evidenceNotes: List<String>,
    val sourceIds: List<String>,
    val humanReviewRequired: Boolean,
) {
    /** True when this requirement is the personal photograph. */
    val isPhotograph: Boolean get() = id.contains("photo", ignoreCase = true)
}

/** A conditional document declared in the dataset. */
data class ConditionalDocument(
    val id: String,
    val name: String,
    val requirementStatus: String,
    val applicabilityScope: String?,
    val applicabilityConditions: List<String>,
    val mandatoryForScope: Boolean,
    val specificationNotes: List<String>,
    val sourceIds: List<String>,
    val humanReviewRequired: Boolean,
)

enum class RequirementApplicability { REQUIRED, CONDITIONAL, NOT_APPLICABLE }

/**
 * A checklist entry presented to the traveler. [reason] is only populated from dataset
 * evidence, never invented.
 */
data class ChecklistItem(
    val documentId: String,
    val name: String,
    val reason: String?,
    val applicability: RequirementApplicability,
    val applicabilityNote: String?,
    val humanReviewRequired: Boolean,
    val sourceIds: List<String>,
) {
    val isActionable: Boolean get() = applicability != RequirementApplicability.NOT_APPLICABLE
}

private fun DocumentRequirement.toChecklistItem(): ChecklistItem = ChecklistItem(
    documentId = id,
    name = prettifyName(name),
    reason = (description ?: evidenceNotes.firstOrNull())?.takeIf { it.isNotBlank() },
    applicability = if (mandatory) RequirementApplicability.REQUIRED else RequirementApplicability.CONDITIONAL,
    applicabilityNote = applicabilityConditions.firstOrNull(),
    humanReviewRequired = humanReviewRequired,
    sourceIds = sourceIds,
)

private fun ConditionalDocument.toChecklistItem(): ChecklistItem {
    val applicability = when (requirementStatus) {
        "not_applicable_to_current_indian_scope" -> RequirementApplicability.NOT_APPLICABLE
        else -> RequirementApplicability.CONDITIONAL
    }
    return ChecklistItem(
        documentId = id,
        name = prettifyName(name),
        reason = applicabilityConditions.firstOrNull(),
        applicability = applicability,
        applicabilityNote = applicabilityConditions.firstOrNull(),
        humanReviewRequired = humanReviewRequired,
        sourceIds = sourceIds,
    )
}

/** Converts dataset identifiers like "Return_or_Onward_Ticket" into readable titles. */
fun prettifyName(raw: String): String =
    raw.replace('_', ' ').trim().replace(Regex("\\s+"), " ")
