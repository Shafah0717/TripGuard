package com.tripguard.app.data.dataset

import com.tripguard.app.domain.model.ApplicantScope
import com.tripguard.app.domain.model.ApplicationRoute
import com.tripguard.app.domain.model.ConditionalDocument
import com.tripguard.app.domain.model.DocumentRequirement
import com.tripguard.app.domain.model.Eligibility
import com.tripguard.app.domain.model.Fee
import com.tripguard.app.domain.model.FinancialRequirement
import com.tripguard.app.domain.model.ProcessingTime
import com.tripguard.app.domain.model.SourceRef
import com.tripguard.app.domain.model.StaySpec
import com.tripguard.app.domain.model.VerificationPolicy
import com.tripguard.app.domain.model.VerificationRule
import com.tripguard.app.domain.model.VisaCatalog
import com.tripguard.app.domain.model.VisaType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Maps the wire dataset into domain models. Missing optional values become empty defaults. */
object DatasetMapper {

    fun toDomain(dto: DatasetDto): VisaCatalog {
        val scopeDto = dto.supportedApplicantScope ?: dto.initialApplicantProfile?.let {
            ApplicantScopeDto(nationality = it.nationality, passportType = it.passportType, purpose = it.purpose)
        }

        return VisaCatalog(
            datasetId = dto.datasetId,
            schemaVersion = dto.schemaVersion,
            destinationCountry = dto.destinationCountry,
            destinationEmirate = dto.destinationEmirate,
            applicantScope = ApplicantScope(
                nationality = scopeDto?.nationality ?: "Indian",
                passportType = scopeDto?.passportType ?: "ordinary",
                purpose = scopeDto?.purpose ?: "tourism",
                unsupportedApplicants = scopeDto?.unsupportedApplicants.orEmpty(),
                scopeEnforcement = scopeDto?.scopeEnforcement,
            ),
            visaTypes = dto.visaTypes.map(::toVisaType),
            sources = dto.sources.associate {
                it.id to SourceRef(it.id, it.title, it.organization, it.url, it.sourceType)
            },
            limitations = dto.limitations,
            verificationPolicy = toVerificationPolicy(dto.verificationPolicy),
            lastVerifiedAt = dto.researchMetadata?.lastVerifiedAt
                ?: dto.visaTypes.firstOrNull()?.lastVerifiedAt,
        )
    }

    private fun toVerificationPolicy(dto: VerificationPolicyDto?): VerificationPolicy = VerificationPolicy(
        mode = dto?.mode ?: "document_readiness_assistance_not_visa_decision",
        automaticChecksAllowed = dto?.automaticChecksAllowed.orEmpty(),
        neverAutomaticallyDecide = dto?.neverAutomaticallyDecide.orEmpty(),
        lowConfidenceAction = dto?.lowConfidenceAction,
        officialDecisionDisclaimer = dto?.officialDecisionDisclaimer,
    )

    private fun toVisaType(dto: VisaTypeDto): VisaType = VisaType(
        id = dto.id,
        officialName = dto.officialName,
        displayName = dto.displayName.ifBlank { dto.officialName },
        category = dto.category,
        productStatus = dto.productStatus,
        entryType = dto.entryType,
        permittedStay = toStaySpec(dto.permittedStay),
        visaValidity = toStaySpec(dto.visaValidity),
        eligibility = Eligibility(
            nationality = dto.eligibility?.nationality,
            conditions = dto.eligibility?.conditions.orEmpty(),
            verified = dto.eligibility?.verified == true,
            sourceIds = dto.eligibility?.sourceIds.orEmpty(),
        ),
        applicationRoute = ApplicationRoute(
            sponsorRequired = dto.applicationRoute?.sponsorRequired == true,
            authorizedChannels = dto.applicationRoute?.authorizedChannels.orEmpty(),
            steps = dto.applicationRoute?.steps.orEmpty(),
            routeNotes = dto.applicationRoute?.routeNotes.orEmpty(),
        ),
        financialRequirements = dto.financialRequirements.map {
            FinancialRequirement(
                id = it.id,
                type = it.type,
                amount = it.amount,
                currency = it.currency,
                period = it.period,
                requirementStatus = it.requirementStatus,
                evidenceNotes = it.evidenceNotes,
                machineCheckAllowed = it.machineCheckAllowed,
            )
        },
        documents = dto.documents.map(::toDocument),
        conditionalDocuments = dto.conditionalDocuments.map(::toConditionalDocument),
        fees = dto.fees.map { Fee(it.amount, it.currency, it.description) },
        processingTime = dto.processingTime?.let {
            ProcessingTime(it.value, it.unit, it.conditions)
        },
        extensionNotes = dto.extensionRules.mapNotNull { it.description.takeIf { d -> d.isNotBlank() } },
        restrictions = dto.restrictions,
        sourceIds = dto.sources,
        lastVerifiedAt = dto.lastVerifiedAt,
        humanReviewRequired = dto.humanReviewRequired,
        applicantScope = dto.applicantScope?.let {
            ApplicantScope(
                nationality = it.nationality ?: "",
                passportType = it.passportType ?: "",
                purpose = it.purpose ?: "",
                unsupportedApplicants = it.unsupportedApplicants,
                scopeEnforcement = it.scopeEnforcement,
            )
        },
    )

    private fun toDocument(dto: DocumentDto): DocumentRequirement = DocumentRequirement(
        id = dto.id,
        name = dto.name,
        description = dto.description,
        requirementStatus = dto.requirementStatus,
        applicabilityScope = dto.applicability?.scope,
        applicabilityConditions = dto.applicability?.conditions.orEmpty(),
        mandatory = dto.mandatory,
        specificationNotes = dto.specifications?.let(::specNotes).orEmpty(),
        acceptedFormats = dto.specifications?.acceptedFormats.orEmpty(),
        imageRequirements = dto.specifications?.imageRequirements.orEmpty(),
        minimumValidityDays = dto.specifications?.minimumValidityDays,
        verificationNotes = dto.verificationRules.mapNotNull { it.description?.takeIf { d -> d.isNotBlank() } },
        verificationRules = dto.verificationRules.map(::toVerificationRule),
        evidenceNotes = dto.evidenceNotes,
        sourceIds = dto.sourceIds,
        humanReviewRequired = dto.humanReviewRequired,
    )

    /**
     * Flattens rule parameters to non-null primitive strings. A null parameter (for example
     * `minimum_validity_days: null`) is intentionally absent so evaluators can detect that the
     * dataset does not supply the value needed for an automatic conclusion.
     */
    private fun toVerificationRule(dto: VerificationRuleDto): VerificationRule = VerificationRule(
        id = dto.id,
        ruleType = dto.ruleType,
        description = dto.description,
        parameters = flattenParameters(dto.parameters),
        severity = dto.severity,
        sourceIds = dto.sourceIds,
        humanReviewRequired = dto.humanReviewRequired,
    )

    private fun flattenParameters(parameters: JsonObject?): Map<String, String> {
        if (parameters == null) return emptyMap()
        return parameters.mapNotNull { (key, value) ->
            if (value is JsonNull) return@mapNotNull null
            val primitive = value as? JsonPrimitive ?: return@mapNotNull null
            if (primitive.isString && primitive.content.isBlank()) return@mapNotNull null
            key to primitive.content
        }.toMap()
    }

    private fun toConditionalDocument(dto: ConditionalDocumentDto): ConditionalDocument = ConditionalDocument(
        id = dto.id,
        name = dto.name,
        requirementStatus = dto.requirementStatus,
        applicabilityScope = dto.applicability?.scope,
        applicabilityConditions = dto.applicability?.conditions.orEmpty(),
        mandatoryForScope = dto.mandatoryForCurrentScope || dto.mandatoryForAllRoutes,
        specificationNotes = dto.specifications?.let(::specNotes).orEmpty(),
        sourceIds = dto.sourceIds,
        humanReviewRequired = dto.humanReviewRequired,
    )

    private fun specNotes(spec: SpecificationsDto): List<String> = buildList {
        addAll(spec.otherConditions)
        addAll(spec.imageRequirements)
        addAll(spec.translationRequirements)
        addAll(spec.attestationRequirements)
    }

    private fun toStaySpec(dto: StaySpecDto?): StaySpec =
        StaySpec(value = dto?.value, unit = dto?.unit ?: "days", conditions = dto?.conditions.orEmpty())
}
