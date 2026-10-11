package com.tripguard.app.domain.model

/**
 * Whether a rule defined in the dataset authorizes an automated conclusion. These are NOT
 * interchangeable: an [UNVERIFIED], [NO_RULE] or [UNSUPPORTED] state must never be presented as
 * [VERIFIED].
 *
 * The three non-verified states have distinct meaning:
 *  - [NO_RULE]: the dataset defines no verification rule for the requirement.
 *  - [UNVERIFIED]: the dataset defines a rule but flags it for human review or omits the
 *    parameters an evaluator needs.
 *  - [UNSUPPORTED]: the dataset defines a rule whose type this build does not implement, so even
 *    a "verified-looking" rule cannot run and requires explicit manual review.
 */
enum class RuleVerification {
    /** The dataset marks the rule as verified and provides machine-usable parameters. */
    VERIFIED,

    /** A rule exists but is flagged for human review or lacks the parameters an evaluator needs. */
    UNVERIFIED,

    /** The dataset defines no rule for this requirement at all. */
    NO_RULE,

    /** The dataset defines a rule, but this build has no evaluator for its rule type. */
    UNSUPPORTED,
}

/** Whether the requirement has any uploaded evidence at all. */
enum class DocumentPresence { MISSING, PRESENT }

/** Independent outcome of the local extraction step, separate from requirement evaluation. */
enum class ExtractionStatus {
    NOT_PROCESSED,
    PROCESSING,
    SUCCESS,
    PARTIAL,
    FAILED,
}

/** The reliability state of one extracted field. */
enum class FieldEvidenceStatus {
    DETECTED,
    USER_CONFIRMED,
    AMBIGUOUS,
    UNAVAILABLE,
}

/** A single piece of extracted evidence surfaced against a requirement. */
data class EvidenceField(
    val key: String,
    val label: String,
    val value: String,
    val status: FieldEvidenceStatus,
)

/**
 * Outcome of evaluating one requirement against extracted evidence. Every value is distinct and
 * carries a different meaning; the UI must not map them onto a single pass/fail.
 */
enum class EvaluationOutcome {
    /** Evidence is present, readable and met the configured, verified rule (if any). */
    SATISFIED,

    /** The requirement applies and no evidence has been supplied. */
    MISSING,

    /** Evidence is present and readable but conflicts with a configured requirement. */
    POTENTIAL_ISSUE,

    /** Evidence is present but could not be read or mapped to the requirement. */
    COULD_NOT_VERIFY,

    /** A human must inspect this item; automation is intentionally withheld. */
    MANUAL_REVIEW_REQUIRED,

    /** The dataset defines no machine-evaluable rule, so no automatic conclusion is possible. */
    RULE_UNVERIFIED,
    ;

    val label: String
        get() = when (this) {
            SATISFIED -> "Satisfied"
            MISSING -> "Missing document"
            POTENTIAL_ISSUE -> "Potential issue"
            COULD_NOT_VERIFY -> "Could not verify"
            MANUAL_REVIEW_REQUIRED -> "Manual review required"
            RULE_UNVERIFIED -> "Rule unverified"
        }
}

/**
 * A machine-check definition attached to a document requirement in the dataset. Parameters are
 * flattened to non-null primitive strings; the absence of a required parameter is meaningful and
 * forces the evaluator to withhold an automatic conclusion.
 */
data class VerificationRule(
    val id: String,
    val ruleType: String,
    val description: String?,
    val parameters: Map<String, String>,
    val severity: String?,
    val sourceIds: List<String>,
    val humanReviewRequired: Boolean,
)

/**
 * The result of matching one documented requirement against the evidence held for an application.
 * It is produced only by the deterministic [com.tripguard.app.domain.rules.RequirementEvaluator]
 * and never by the UI.
 *
 * Document presence, extraction status and requirement evaluation are deliberately independent:
 * a requirement may be [DocumentPresence.PRESENT] with an [ExtractionStatus.SUCCESS] while its
 * substantive evaluation still ends in [EvaluationOutcome.MANUAL_REVIEW_REQUIRED].
 */
data class RequirementEvaluation(
    val requirementId: String,
    val requirementName: String,
    val visaRouteId: String,
    val visaRouteName: String,
    val documentCategoryId: String,
    val applicability: RequirementApplicability,
    val evidenceFound: Boolean,
    val evidenceSummary: String,
    val extractedValue: String? = null,
    val ruleId: String? = null,
    val ruleType: String? = null,
    val ruleVerification: RuleVerification,
    val outcome: EvaluationOutcome,
    val reason: String,
    val nextAction: String,
    val sourceIds: List<String> = emptyList(),
    val sourceLabels: List<String> = emptyList(),
    val documentPresence: DocumentPresence = DocumentPresence.MISSING,
    val extractionStatus: ExtractionStatus = ExtractionStatus.NOT_PROCESSED,
    val extractionMethod: String? = null,
    val evidenceFields: List<EvidenceField> = emptyList(),
)
