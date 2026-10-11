package com.tripguard.app.data.dataset

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire shapes for the versioned visa-requirements dataset (schema 1.0.1). These mirror the
 * JSON exactly and are mapped to domain models by [DatasetMapper]. Unknown keys are ignored
 * so minor schema additions never crash the app.
 */
@Serializable
data class DatasetDto(
    @SerialName("dataset_id") val datasetId: String,
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("destination_country") val destinationCountry: String = "",
    @SerialName("destination_emirate") val destinationEmirate: String = "",
    @SerialName("initial_applicant_profile") val initialApplicantProfile: ApplicantProfileDto? = null,
    @SerialName("research_metadata") val researchMetadata: ResearchMetadataDto? = null,
    @SerialName("visa_types") val visaTypes: List<VisaTypeDto> = emptyList(),
    val sources: List<SourceDto> = emptyList(),
    val limitations: List<String> = emptyList(),
    @SerialName("supported_applicant_scope") val supportedApplicantScope: ApplicantScopeDto? = null,
    @SerialName("verification_policy") val verificationPolicy: VerificationPolicyDto? = null,
)

@Serializable
data class ApplicantProfileDto(
    val nationality: String? = null,
    @SerialName("passport_type") val passportType: String? = null,
    val purpose: String? = null,
    @SerialName("scope_note") val scopeNote: String? = null,
)

@Serializable
data class ResearchMetadataDto(
    @SerialName("last_verified_at") val lastVerifiedAt: String? = null,
    @SerialName("research_status") val researchStatus: String? = null,
)

@Serializable
data class ApplicantScopeDto(
    val nationality: String? = null,
    @SerialName("passport_type") val passportType: String? = null,
    val purpose: String? = null,
    @SerialName("destination_country") val destinationCountry: String? = null,
    @SerialName("destination_emirate") val destinationEmirate: String? = null,
    @SerialName("unsupported_applicants") val unsupportedApplicants: List<String> = emptyList(),
    @SerialName("scope_enforcement") val scopeEnforcement: String? = null,
)

@Serializable
data class VerificationPolicyDto(
    val mode: String? = null,
    @SerialName("automatic_checks_allowed") val automaticChecksAllowed: List<String> = emptyList(),
    @SerialName("never_automatically_decide") val neverAutomaticallyDecide: List<String> = emptyList(),
    @SerialName("low_confidence_action") val lowConfidenceAction: String? = null,
    @SerialName("official_decision_disclaimer") val officialDecisionDisclaimer: String? = null,
)

@Serializable
data class VisaTypeDto(
    val id: String,
    @SerialName("official_name") val officialName: String = "",
    @SerialName("display_name") val displayName: String = "",
    val category: String = "tourism",
    @SerialName("product_status") val productStatus: String = "",
    @SerialName("entry_type") val entryType: String = "",
    @SerialName("permitted_stay") val permittedStay: StaySpecDto? = null,
    @SerialName("visa_validity") val visaValidity: StaySpecDto? = null,
    val eligibility: EligibilityDto? = null,
    @SerialName("application_route") val applicationRoute: ApplicationRouteDto? = null,
    @SerialName("financial_requirements") val financialRequirements: List<FinancialRequirementDto> = emptyList(),
    val documents: List<DocumentDto> = emptyList(),
    val fees: List<FeeDto> = emptyList(),
    @SerialName("processing_time") val processingTime: ProcessingTimeDto? = null,
    @SerialName("extension_rules") val extensionRules: List<ExtensionRuleDto> = emptyList(),
    val restrictions: List<String> = emptyList(),
    val sources: List<String> = emptyList(),
    @SerialName("last_verified_at") val lastVerifiedAt: String? = null,
    @SerialName("human_review_required") val humanReviewRequired: Boolean = false,
    @SerialName("applicant_scope") val applicantScope: ApplicantScopeDto? = null,
    @SerialName("conditional_documents") val conditionalDocuments: List<ConditionalDocumentDto> = emptyList(),
)

@Serializable
data class StaySpecDto(
    val value: Int? = null,
    val unit: String = "",
    val conditions: List<String> = emptyList(),
)

@Serializable
data class EligibilityDto(
    val nationality: String? = null,
    val conditions: List<String> = emptyList(),
    val verified: Boolean = false,
    @SerialName("source_ids") val sourceIds: List<String> = emptyList(),
)

@Serializable
data class ApplicationRouteDto(
    @SerialName("sponsor_required") val sponsorRequired: Boolean = false,
    @SerialName("authorized_channels") val authorizedChannels: List<String> = emptyList(),
    val steps: List<String> = emptyList(),
    @SerialName("route_notes") val routeNotes: List<String> = emptyList(),
)

@Serializable
data class FinancialRequirementDto(
    val id: String,
    val type: String = "",
    val amount: Double? = null,
    val currency: String? = null,
    val period: String? = null,
    val conditions: List<String> = emptyList(),
    @SerialName("requirement_status") val requirementStatus: String = "",
    @SerialName("evidence_notes") val evidenceNotes: List<String> = emptyList(),
    @SerialName("machine_check_allowed") val machineCheckAllowed: Boolean = false,
)

@Serializable
data class DocumentDto(
    val id: String,
    val name: String = "",
    val description: String? = null,
    @SerialName("requirement_status") val requirementStatus: String = "",
    val applicability: ApplicabilityDto? = null,
    val mandatory: Boolean = false,
    val specifications: SpecificationsDto? = null,
    @SerialName("verification_rules") val verificationRules: List<VerificationRuleDto> = emptyList(),
    @SerialName("source_ids") val sourceIds: List<String> = emptyList(),
    @SerialName("evidence_notes") val evidenceNotes: List<String> = emptyList(),
    @SerialName("human_review_required") val humanReviewRequired: Boolean = false,
    @SerialName("route_specific_requirements") val routeSpecificRequirements: List<RouteSpecificDto> = emptyList(),
)

@Serializable
data class ConditionalDocumentDto(
    val id: String,
    val name: String = "",
    @SerialName("requirement_status") val requirementStatus: String = "",
    val applicability: ApplicabilityDto? = null,
    @SerialName("mandatory_for_current_scope") val mandatoryForCurrentScope: Boolean = false,
    @SerialName("mandatory_for_all_routes") val mandatoryForAllRoutes: Boolean = false,
    val specifications: SpecificationsDto? = null,
    @SerialName("source_ids") val sourceIds: List<String> = emptyList(),
    @SerialName("human_review_required") val humanReviewRequired: Boolean = false,
)

@Serializable
data class ApplicabilityDto(
    val scope: String? = null,
    val conditions: List<String> = emptyList(),
)

@Serializable
data class SpecificationsDto(
    @SerialName("minimum_validity_days") val minimumValidityDays: Int? = null,
    @SerialName("accepted_formats") val acceptedFormats: List<String> = emptyList(),
    @SerialName("image_requirements") val imageRequirements: List<String> = emptyList(),
    @SerialName("translation_requirements") val translationRequirements: List<String> = emptyList(),
    @SerialName("attestation_requirements") val attestationRequirements: List<String> = emptyList(),
    @SerialName("other_conditions") val otherConditions: List<String> = emptyList(),
)

@Serializable
data class VerificationRuleDto(
    val id: String = "",
    @SerialName("rule_type") val ruleType: String = "",
    val description: String? = null,
    val parameters: JsonObject? = null,
    val severity: String? = null,
    @SerialName("source_ids") val sourceIds: List<String> = emptyList(),
    @SerialName("human_review_required") val humanReviewRequired: Boolean = false,
)

@Serializable
data class RouteSpecificDto(
    val route: String = "",
    val condition: String = "",
)

@Serializable
data class FeeDto(
    val amount: Double? = null,
    val currency: String? = null,
    val description: String = "",
    @SerialName("source_ids") val sourceIds: List<String> = emptyList(),
)

@Serializable
data class ProcessingTimeDto(
    val value: Int? = null,
    val unit: String = "",
    val conditions: List<String> = emptyList(),
    @SerialName("source_ids") val sourceIds: List<String> = emptyList(),
)

@Serializable
data class ExtensionRuleDto(
    val status: String = "",
    val description: String = "",
    @SerialName("source_ids") val sourceIds: List<String> = emptyList(),
)

@Serializable
data class SourceDto(
    val id: String,
    val title: String = "",
    val organization: String = "",
    val url: String = "",
    @SerialName("source_type") val sourceType: String = "",
)
