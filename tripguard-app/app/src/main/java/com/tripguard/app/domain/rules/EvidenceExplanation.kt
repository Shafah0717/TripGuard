package com.tripguard.app.domain.rules

import com.tripguard.app.domain.model.DocumentPresence
import com.tripguard.app.domain.model.EvaluationOutcome
import com.tripguard.app.domain.model.EvidenceField
import com.tripguard.app.domain.model.ExtractionStatus
import com.tripguard.app.domain.model.FieldEvidenceStatus
import com.tripguard.app.domain.model.RequirementApplicability
import com.tripguard.app.domain.model.RequirementEvaluation
import com.tripguard.app.domain.model.RuleVerification

/**
 * Renders the evidence held against a requirement as readable, human-facing explanations. No
 * data-class `toString()` dumps (for example `ExtractedField(key=expiry_date, ...)`) are ever
 * shown: every value is displayed with its label, the actual candidate value, the extraction
 * method, the review status and the rule outcome.
 *
 * Pure Kotlin so the wording can be unit tested end to end.
 */
object EvidenceExplanation {

    /** One readable line per extracted field: label, candidate value and review status. */
    fun evidenceFieldLine(field: EvidenceField): String {
        val value = field.value.ifBlank { "Not detected." }
        val suffix = if (field.value.isBlank()) "" else " (${fieldStatusHint(field.status)})"
        return "${field.label}: $value$suffix"
    }

    /**
     * A complete, plain-language explanation of one requirement evaluation: the requirement id,
     * the selected visa route, applicability, document presence, extraction method and status,
     * the live evidence values with their review status, the rule type/status from the dataset
     * and the resulting outcome with the next action.
     */
    fun explanation(evaluation: RequirementEvaluation): String = buildString {
        appendLine("${evaluation.requirementName} (${evaluation.requirementId})")
        appendLine("Visa route: ${evaluation.visaRouteName}")
        appendLine("Applicability: ${applicabilityLabel(evaluation.applicability)}")
        appendLine("Document: ${presenceLabel(evaluation.documentPresence)}")
        appendLine(
            "Extraction: ${extractionStatusLabel(evaluation.extractionStatus)}" +
                (evaluation.extractionMethod?.let { " · $it" } ?: ""),
        )
        if (evaluation.evidenceFields.isNotEmpty()) {
            appendLine("Extracted evidence:")
            evaluation.evidenceFields.forEach { appendLine("  - ${evidenceFieldLine(it)}") }
        } else {
            evaluation.extractedValue?.let { appendLine("Extracted value: $it") }
        }
        evaluation.ruleType?.let { appendLine("Rule: $it (id: ${evaluation.ruleId})") }
        appendLine("Rule status: ${ruleVerificationLabel(evaluation.ruleVerification)}")
        appendLine("Outcome: ${evaluation.outcome.label}")
        appendLine("Reason: ${evaluation.reason}")
        appendLine("Next action: ${evaluation.nextAction}")
    }

    fun ruleVerificationLabel(verification: RuleVerification): String = when (verification) {
        RuleVerification.VERIFIED -> "Verified — matched automatically"
        RuleVerification.UNVERIFIED -> "Rule unverified (dataset)"
        RuleVerification.NO_RULE -> "No rule configured (dataset)"
        RuleVerification.UNSUPPORTED -> "Evaluator not implemented (auto check unavailable)"
    }

    /**
     * A concise, honest one-liner for the collapsed requirement card. It summarises the outcome
     * without inventing a pass/fail and never downplays a manual-review or missing requirement;
     * the full [RequirementEvaluation.reason] is always shown in the expanded details.
     */
    fun shortReason(evaluation: RequirementEvaluation): String = when (evaluation.outcome) {
        EvaluationOutcome.SATISFIED -> "Matches the configured rule from the extracted values."
        EvaluationOutcome.MISSING -> "This item has not been supplied yet."
        EvaluationOutcome.POTENTIAL_ISSUE -> "Conflicts with a configured requirement — review the details."
        EvaluationOutcome.COULD_NOT_VERIFY -> "Not enough reliable evidence on this device to compare."
        EvaluationOutcome.MANUAL_REVIEW_REQUIRED -> when (evaluation.ruleVerification) {
            RuleVerification.VERIFIED -> "A human reviewer must confirm this item."
            RuleVerification.UNVERIFIED -> "Not machine-verifiable with the current dataset — a human reviewer decides."
            RuleVerification.NO_RULE -> "The dataset defines no automated rule — a human reviewer decides."
            RuleVerification.UNSUPPORTED -> "The configured rule cannot run in this build — a human reviewer decides."
        }

        EvaluationOutcome.RULE_UNVERIFIED -> "The dataset defines no verified rule for this item."
    }

    private fun fieldStatusHint(status: FieldEvidenceStatus): String = when (status) {
        FieldEvidenceStatus.DETECTED -> "auto-detected"
        FieldEvidenceStatus.USER_CONFIRMED -> "you confirmed this value"
        FieldEvidenceStatus.AMBIGUOUS -> "needs review"
        FieldEvidenceStatus.UNAVAILABLE -> "not detected"
    }

    private fun applicabilityLabel(app: RequirementApplicability): String = when (app) {
        RequirementApplicability.REQUIRED -> "Required"
        RequirementApplicability.CONDITIONAL -> "Conditional"
        RequirementApplicability.NOT_APPLICABLE -> "Not applicable"
    }

    private fun presenceLabel(presence: DocumentPresence): String = when (presence) {
        DocumentPresence.MISSING -> "Missing"
        DocumentPresence.PRESENT -> "Present (on device)"
    }

    private fun extractionStatusLabel(status: ExtractionStatus): String = when (status) {
        ExtractionStatus.NOT_PROCESSED -> "Not processed yet"
        ExtractionStatus.PROCESSING -> "Processing"
        ExtractionStatus.SUCCESS -> "Success"
        ExtractionStatus.PARTIAL -> "Partial"
        ExtractionStatus.FAILED -> "Failed"
    }
}