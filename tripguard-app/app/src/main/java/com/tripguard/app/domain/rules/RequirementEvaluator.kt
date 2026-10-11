package com.tripguard.app.domain.rules

import com.tripguard.app.domain.model.Application
import com.tripguard.app.domain.model.ConditionalDocument
import com.tripguard.app.domain.model.DocumentPresence
import com.tripguard.app.domain.model.DocumentRequirement
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.DocumentUpload
import com.tripguard.app.domain.model.EvaluationOutcome
import com.tripguard.app.domain.model.EvidenceField
import com.tripguard.app.domain.model.ExtractionResult
import com.tripguard.app.domain.model.ExtractionStatus
import com.tripguard.app.domain.model.FieldEvidenceStatus
import com.tripguard.app.domain.model.FinancialRequirement
import com.tripguard.app.domain.model.RequirementApplicability
import com.tripguard.app.domain.model.RequirementEvaluation
import com.tripguard.app.domain.model.RuleVerification
import com.tripguard.app.domain.model.VisaCatalog
import com.tripguard.app.domain.model.VerificationRule
import com.tripguard.app.domain.model.VisaType
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Deterministic, side-effect-free evaluator that matches the evidence held for an application
 * against the requirements declared in the dataset. It is entirely independent of Compose and of
 * OCR. The dataset remains the source of truth: no rule is invented, and a requirement is never
 * reported as satisfied merely because a file was uploaded.
 *
 * Three outcomes are kept independent:
 *  - document presence ([DocumentPresence]),
 *  - string extraction ([ExtractionStatus] + [EvidenceField]s), and
 *  - the requirement evaluation itself ([EvaluationOutcome]).
 * A present, readable document may therefore still end in
 * [EvaluationOutcome.MANUAL_REVIEW_REQUIRED] whenever the dataset does not authorise an automatic
 * conclusion (which is the case for every rule in the current dataset).
 */
object RequirementEvaluator {

    private const val NOT_APPLICABLE_STATUS = "not_applicable_to_current_indian_scope"

    /** Rule types for which this build actually implements an evaluator. */
    private val IMPLEMENTED_RULE_TYPES = setOf("passport_expiry_check")

    /** Dataset-declared marker that a requirement must never be auto-decided. */
    private const val HUMAN_REVIEW_RULE_TYPE = "human_review"

    fun evaluate(catalog: VisaCatalog?, visaType: VisaType, application: Application): List<RequirementEvaluation> {
        val results = mutableListOf<RequirementEvaluation>()

        visaType.documents.forEach { doc ->
            val applicability =
                if (doc.mandatory) RequirementApplicability.REQUIRED else RequirementApplicability.CONDITIONAL
            results += evaluateDocument(catalog, visaType, application, doc, applicability)
        }

        visaType.conditionalDocuments
            .filterNot { it.requirementStatus == NOT_APPLICABLE_STATUS }
            .forEach { cond -> results += evaluateConditional(catalog, visaType, application, cond) }

        visaType.financialRequirements.forEach { fin ->
            results += evaluateFinancial(catalog, visaType, application, fin)
        }

        return results
    }

    /**
     * Evaluates the requirement for a single main [documentId] of the application. Returns null
     * for photographs (which are judged by [PhotoRequirementEvaluator]) and for conditional or
     * financial requirements, which this evaluator handles at the full-application level.
     *
     * This is the entry point used to run the rule check immediately after a document has been
     * parsed, so an extraction can be confirmed against the configured rules before the user
     * leaves the review screen. It produces exactly the same result for that document as
     * [evaluate] would. Conditional documents are evaluated too, so a rule result is always
     * available for every checklist item this app parses.
     */
    fun evaluateForDocument(
        catalog: VisaCatalog?,
        visaType: VisaType,
        application: Application,
        documentId: String,
    ): RequirementEvaluation? {
        val doc = visaType.documents.firstOrNull { it.id == documentId }
        if (doc != null) {
            if (doc.isPhotograph) return null
            return evaluateDocument(
                catalog = catalog,
                visaType = visaType,
                application = application,
                doc = doc,
                applicability =
                    if (doc.mandatory) RequirementApplicability.REQUIRED else RequirementApplicability.CONDITIONAL,
            )
        }
        val conditional = visaType.conditionalDocuments.firstOrNull { it.id == documentId }
            ?: return null
        if (conditional.requirementStatus == NOT_APPLICABLE_STATUS) return null
        return evaluateConditional(catalog, visaType, application, conditional)
    }

    // ---- document requirements ---------------------------------------------

    private fun evaluateDocument(
        catalog: VisaCatalog?,
        visaType: VisaType,
        application: Application,
        doc: DocumentRequirement,
        applicability: RequirementApplicability,
    ): RequirementEvaluation {
        val upload = application.documents[doc.id]
        val present = upload?.hasFile == true

        if (!present) {
            return base(catalog, visaType, doc.id, doc.name, applicability, doc.sourceIds)
                .copy(
                    evidenceFound = false,
                    evidenceSummary = "No file has been supplied for this requirement.",
                    ruleVerification = verificationFor(doc),
                    outcome = EvaluationOutcome.MISSING,
                    reason = "This requirement applies to your selected visa but no document is present.",
                    nextAction = "Import the required document to continue.",
                    documentPresence = DocumentPresence.MISSING,
                    extractionStatus = ExtractionStatus.NOT_PROCESSED,
                )
        }

        return if (doc.isPhotograph) {
            evaluatePhotograph(catalog, visaType, doc, applicability, upload)
        } else {
            evaluateContentBearing(catalog, visaType, doc, applicability, upload)
        }
    }

    private fun evaluatePhotograph(
        catalog: VisaCatalog?,
        visaType: VisaType,
        doc: DocumentRequirement,
        applicability: RequirementApplicability,
        upload: DocumentUpload,
    ): RequirementEvaluation {
        val validation = upload.imageValidation
        val base = base(catalog, visaType, doc.id, doc.name, applicability, doc.sourceIds)
            .copy(
                evidenceFound = true,
                documentPresence = DocumentPresence.PRESENT,
                extractionStatus = if (validation == null) ExtractionStatus.NOT_PROCESSED else ExtractionStatus.SUCCESS,
            )

        if (validation == null) {
            return base.copy(
                evidenceSummary = "Photograph on file but not yet measured.",
                ruleVerification = verificationFor(doc),
                outcome = EvaluationOutcome.COULD_NOT_VERIFY,
                reason = "The photograph has not been measured locally yet.",
                nextAction = "Open the document to measure its dimensions.",
            )
        }

        val measured = "Measured ${validation.measurements.widthPx}×${validation.measurements.heightPx}px " +
            "(${validation.measurements.aspectRatioLabel}, ${validation.measurements.sizeBytes} bytes)."

        val configured = validation.checks.any { it.configured }
        val ruleVerification = when {
            !configured -> RuleVerification.NO_RULE
            validation.checks.any { it.configured && it.outcome == EvaluationOutcome.MANUAL_REVIEW_REQUIRED } ->
                RuleVerification.UNVERIFIED

            else -> RuleVerification.VERIFIED
        }

        return base.copy(
            evidenceSummary = measured,
            extractedValue = "${validation.measurements.widthPx} × ${validation.measurements.heightPx} px",
            ruleVerification = ruleVerification,
            outcome = validation.summaryOutcome,
            reason = validation.reason,
            nextAction = nextActionFor(validation.summaryOutcome),
            evidenceFields = listOf(
                EvidenceField(
                    key = "photo_dimensions",
                    label = "Measured dimensions",
                    value = "${validation.measurements.widthPx} × ${validation.measurements.heightPx} px",
                    status = FieldEvidenceStatus.DETECTED,
                ),
            ),
        )
    }

    private fun evaluateContentBearing(
        catalog: VisaCatalog?,
        visaType: VisaType,
        doc: DocumentRequirement,
        applicability: RequirementApplicability,
        upload: DocumentUpload,
    ): RequirementEvaluation {
        val extraction = upload.extraction
        val base = base(catalog, visaType, doc.id, doc.name, applicability, doc.sourceIds)
            .copy(
                evidenceFound = true,
                documentPresence = DocumentPresence.PRESENT,
                extractionStatus = extractionStatusOf(upload, extraction),
                extractionMethod = extraction?.method?.label,
                evidenceFields = evidenceFieldsOf(extraction),
            )

        val evidence = evidenceSummary(upload, extraction)
        val extractionStatus = extractionStatusOf(upload, extraction)

        if (extractionStatus != ExtractionStatus.SUCCESS && extractionStatus != ExtractionStatus.PARTIAL) {
            return base.copy(
                evidenceSummary = evidence,
                ruleVerification = verificationFor(doc),
                outcome = EvaluationOutcome.COULD_NOT_VERIFY,
                reason = when (extractionStatus) {
                    ExtractionStatus.PROCESSING -> "The document is present and is being processed on this device."
                    ExtractionStatus.FAILED -> "The document is present but could not be read into usable fields."
                    else -> "The document is present but has not been processed yet."
                },
                nextAction = if (extractionStatus == ExtractionStatus.FAILED) {
                    "Re-upload a clearer copy, or request manual review."
                } else {
                    "Re-open the document to read it."
                },
            )
        }

        val rules = doc.verificationRules
        if (rules.isEmpty()) {
            return if (doc.humanReviewRequired) {
                base.copy(
                    evidenceSummary = evidence,
                    ruleVerification = RuleVerification.NO_RULE,
                    outcome = EvaluationOutcome.MANUAL_REVIEW_REQUIRED,
                    reason = buildString {
                        append(requirementNote(doc))
                        append(" ")
                        append("The dataset configures no automated check for it, so its substantive validity must be confirmed by a reviewer.")
                    },
                    nextAction = "Ask your agency to confirm that this document satisfies the requirement.",
                )
            } else {
                base.copy(
                    evidenceSummary = evidence,
                    ruleVerification = RuleVerification.NO_RULE,
                    outcome = EvaluationOutcome.SATISFIED,
                    reason = "The dataset requires this document and it is present.",
                    nextAction = "No action needed.",
                )
            }
        }

        val ctx = extraction
            ?: return base.copy(
                evidenceSummary = evidence,
                ruleVerification = verificationFor(doc),
                outcome = EvaluationOutcome.COULD_NOT_VERIFY,
                reason = "The document is present but has not been read into usable fields.",
                nextAction = "Re-upload a clearer copy, or request manual review.",
            )

        val assessments = rules.map { assessRule(it, ctx) }
        val worst = assessments.maxByOrNull { it.severity } ?: rules.assumeManual("No evaluator produced a result.")
        val rule = worst.rule

        return base.copy(
            evidenceSummary = evidence,
            extractedValue = primaryEvidenceValue(doc, ctx),
            ruleId = rule.id,
            ruleType = rule.ruleType,
            ruleVerification = worst.verification,
            outcome = worst.outcome,
            reason = worst.reason,
            nextAction = nextActionFor(worst.outcome, worst.verification),
        )
    }

    // ---- rule assessment ----------------------------------------------------

    private data class RuleAssessment(
        val rule: VerificationRule,
        val verification: RuleVerification,
        val outcome: EvaluationOutcome,
        val reason: String,
        val severity: Int,
    )

    private fun assessRule(rule: VerificationRule, extraction: ExtractionResult): RuleAssessment {
        val datasetForbidsAuto = rule.humanReviewRequired || rule.ruleType == HUMAN_REVIEW_RULE_TYPE

        if (rule.ruleType !in IMPLEMENTED_RULE_TYPES) {
            return if (datasetForbidsAuto) {
                RuleAssessment(
                    rule = rule,
                    verification = RuleVerification.UNVERIFIED,
                    outcome = EvaluationOutcome.MANUAL_REVIEW_REQUIRED,
                    reason = ruleText(rule, "The dataset marks this rule for human review, so it is not applied automatically."),
                    severity = 3,
                )
            } else {
                RuleAssessment(
                    rule = rule,
                    verification = RuleVerification.UNSUPPORTED,
                    outcome = EvaluationOutcome.MANUAL_REVIEW_REQUIRED,
                    reason = "The dataset declares a rule of type \"${rule.ruleType}\" but this build does not implement " +
                        "an evaluator for it, so the document's content cannot be judged automatically and must be reviewed manually.",
                    severity = 3,
                )
            }
        }

        if (datasetForbidsAuto) {
            val candidate = expiryCandidate(extraction)
            return RuleAssessment(
                rule = rule,
                verification = RuleVerification.UNVERIFIED,
                outcome = EvaluationOutcome.MANUAL_REVIEW_REQUIRED,
                reason = buildString {
                    append(ruleText(rule, null))
                    candidate?.let {
                        append(" The extracted expiry date (${it.value}) is kept only as candidate evidence; the dataset does not authorise an automatic conclusion.")
                    }
                },
                severity = 3,
            )
        }

        if (!autoUsable(rule, extraction)) {
            return RuleAssessment(
                rule = rule,
                verification = RuleVerification.UNVERIFIED,
                outcome = EvaluationOutcome.COULD_NOT_VERIFY,
                reason = ruleText(rule, "No automatic conclusion can be drawn from the available evidence."),
                severity = 3,
            )
        }

        val result = evaluateRule(rule, extraction)
        return RuleAssessment(
            rule = rule,
            verification = RuleVerification.VERIFIED,
            outcome = result.outcome,
            reason = result.reason,
            severity = result.severity,
        )
    }

    private fun List<VerificationRule>.assumeManual(fallback: String): RuleAssessment {
        val rule = first()
        return RuleAssessment(
            rule = rule,
            verification = RuleVerification.UNVERIFIED,
            outcome = EvaluationOutcome.MANUAL_REVIEW_REQUIRED,
            reason = fallback,
            severity = 3,
        )
    }

    private data class OutcomeResult(val outcome: EvaluationOutcome, val reason: String, val severity: Int)

    private fun evaluateRule(rule: VerificationRule, extraction: ExtractionResult): OutcomeResult {
        return when (rule.ruleType) {
            "passport_expiry_check" -> evaluatePassportExpiry(rule, extraction)
            else -> OutcomeResult(
                EvaluationOutcome.MANUAL_REVIEW_REQUIRED,
                "No evaluator is implemented for rule \"${rule.ruleType}\".",
                3,
            )
        }
    }

    private fun evaluatePassportExpiry(rule: VerificationRule, extraction: ExtractionResult): OutcomeResult {
        val minimumDays = rule.parameters["minimum_validity_days"]?.toLongOrNull()
            ?: return OutcomeResult(
                EvaluationOutcome.MANUAL_REVIEW_REQUIRED,
                ruleText(rule, "The dataset states a validity expectation but supplies no verified minimum, " +
                    "so no automatic conclusion is drawn."),
                3,
            )
        val expiryField = expiryCandidate(extraction)
            ?: return OutcomeResult(
                EvaluationOutcome.COULD_NOT_VERIFY,
                "No passport expiry date could be read from the document.",
                3,
            )
        return when (val parsed = DateNormalizer.normalize(expiryField.value)) {
            is DateParse.Success -> {
                val expiry = runCatching { LocalDate.parse(parsed.iso) }.getOrNull()
                    ?: return OutcomeResult(EvaluationOutcome.COULD_NOT_VERIFY, "The expiry date could not be interpreted.", 3)
                val days = ChronoUnit.DAYS.between(LocalDate.now(), expiry)
                if (days >= minimumDays) {
                    OutcomeResult(
                        EvaluationOutcome.SATISFIED,
                        "Extracted expiry (${parsed.iso}) is at least $minimumDays days away.",
                        0,
                    )
                } else {
                    OutcomeResult(
                        EvaluationOutcome.POTENTIAL_ISSUE,
                        "Extracted expiry (${parsed.iso}) is only $days day(s) away, below the stated minimum of $minimumDays.",
                        2,
                    )
                }
            }

            is DateParse.Ambiguous -> OutcomeResult(
                EvaluationOutcome.COULD_NOT_VERIFY,
                "The expiry date is ambiguous and was not interpreted automatically.",
                3,
            )

            is DateParse.Unparseable -> OutcomeResult(
                EvaluationOutcome.COULD_NOT_VERIFY,
                "The expiry date could not be parsed reliably.",
                3,
            )
        }
    }

    /** A rule is auto-usable only when the dataset authorizes it and every parameter exists. */
    private fun autoUsable(rule: VerificationRule, extraction: ExtractionResult): Boolean = when (rule.ruleType) {
        "passport_expiry_check" ->
            rule.parameters["minimum_validity_days"]?.toLongOrNull() != null &&
                extraction.fields.any { it.key.contains("expiry", ignoreCase = true) || it.key == "expiry_date" }

        else -> false
    }

    private fun expiryCandidate(extraction: ExtractionResult): com.tripguard.app.domain.model.ExtractedField? =
        extraction.fields.firstOrNull { it.key.contains("expiry", ignoreCase = true) || it.key == "expiry_date" }
            ?.takeIf { it.value.isNotBlank() }

    private fun verificationFor(doc: DocumentRequirement): RuleVerification =
        if (doc.verificationRules.isEmpty()) RuleVerification.NO_RULE else RuleVerification.UNVERIFIED

    // ---- conditional documents ---------------------------------------------

    private fun evaluateConditional(
        catalog: VisaCatalog?,
        visaType: VisaType,
        application: Application,
        doc: ConditionalDocument,
    ): RequirementEvaluation {
        val workload = application.documents[doc.id]
        val present = workload?.hasFile == true
        val applicability =
            if (doc.mandatoryForScope) RequirementApplicability.REQUIRED else RequirementApplicability.CONDITIONAL
        val extraction = workload?.extraction

        val outcome = when {
            doc.mandatoryForScope && !present -> EvaluationOutcome.MISSING
            present -> EvaluationOutcome.MANUAL_REVIEW_REQUIRED
            else -> EvaluationOutcome.MANUAL_REVIEW_REQUIRED
        }

        val reason = when {
            doc.mandatoryForScope && !present ->
                "This conditional document is mandatory for your route but has not been supplied."

            present ->
                "A document is present. The dataset treats this as route-specific and requires confirmation."

            else ->
                doc.applicabilityConditions.firstOrNull()
                    ?: "This document is conditional for your route and requires confirmation."
        }

        return base(catalog, visaType, doc.id, doc.name, applicability, doc.sourceIds).copy(
            evidenceFound = present,
            evidenceSummary = if (present) "Document supplied." else "No file supplied.",
            ruleVerification = RuleVerification.UNVERIFIED,
            outcome = outcome,
            reason = reason,
            nextAction = "Confirm with your issuing channel whether this document is required.",
            documentPresence = if (present) DocumentPresence.PRESENT else DocumentPresence.MISSING,
            extractionStatus = if (present) extractionStatusOf(workload, extraction) else ExtractionStatus.NOT_PROCESSED,
            extractionMethod = extraction?.method?.label,
            evidenceFields = evidenceFieldsOf(extraction),
        )
    }

    // ---- financial requirements --------------------------------------------

    private fun evaluateFinancial(
        catalog: VisaCatalog?,
        visaType: VisaType,
        application: Application,
        fin: FinancialRequirement,
    ): RequirementEvaluation {
        val documentId = when {
            fin.type.contains("bank", ignoreCase = true) || fin.id.contains("bank", ignoreCase = true) -> "bank_statement"
            else -> fin.type
        }
        val upload = application.documents[documentId]
        val present = upload?.hasFile == true
        val extraction = upload?.extraction
        val balance = extraction?.fields?.firstOrNull { it.key == "closing_balance" }?.value

        // The published threshold may never be turned into an automatic pass/fail decision.
        val autoAllowed = fin.machineCheckAllowed
        val verification = if (autoAllowed) RuleVerification.VERIFIED else RuleVerification.UNVERIFIED

        val outcome = when {
            !present -> EvaluationOutcome.MISSING
            else -> EvaluationOutcome.MANUAL_REVIEW_REQUIRED
        }

        val reason = when {
            !present -> "No bank statement is present for this financial requirement."

            !autoAllowed ->
                "The dataset publishes a threshold (${fin.currency ?: ""} ${fin.amount ?: "—"}) but explicitly " +
                    "forbids an automatic decision, so the figures are surfaced for human review only."

            else ->
                "A threshold is declared, but the wording is not verified for an automatic decision."
        }

        return RequirementEvaluation(
            requirementId = fin.id,
            requirementName = fin.id.replace('_', ' ').replaceFirstChar { it.uppercase() },
            visaRouteId = visaType.id,
            visaRouteName = visaType.displayName,
            documentCategoryId = documentId,
            applicability = RequirementApplicability.REQUIRED,
            evidenceFound = present,
            evidenceSummary = buildString {
                append(if (present) "Bank statement on file" else "No bank statement supplied")
                balance?.let { append("; extracted closing balance $it") }
                fin.period?.let { append("; stated period: $it") }
            },
            extractedValue = balance,
            ruleId = fin.id,
            ruleType = fin.type,
            ruleVerification = verification,
            outcome = outcome,
            reason = reason,
            nextAction = "A reviewer must confirm the financial evidence against the official channel.",
            sourceIds = emptyList(),
            sourceLabels = emptyList(),
            documentPresence = if (present) DocumentPresence.PRESENT else DocumentPresence.MISSING,
            extractionStatus = if (present) extractionStatusOf(upload, extraction) else ExtractionStatus.NOT_PROCESSED,
            extractionMethod = extraction?.method?.label,
            evidenceFields = evidenceFieldsOf(extraction),
        )
    }

    // ---- helpers ------------------------------------------------------------

    private fun base(
        catalog: VisaCatalog?,
        visaType: VisaType,
        id: String,
        name: String,
        applicability: RequirementApplicability,
        sourceIds: List<String>,
    ): RequirementEvaluation = RequirementEvaluation(
        requirementId = id,
        requirementName = name.replace('_', ' ').trim().replaceFirstChar { it.uppercase() },
        visaRouteId = visaType.id,
        visaRouteName = visaType.displayName,
        documentCategoryId = id,
        applicability = applicability,
        evidenceFound = false,
        evidenceSummary = "",
        ruleVerification = RuleVerification.NO_RULE,
        outcome = EvaluationOutcome.MISSING,
        reason = "",
        nextAction = "",
        sourceIds = sourceIds,
        sourceLabels = sourceLabelsOf(catalog, sourceIds),
    )

    private fun extractionStatusOf(upload: DocumentUpload?, extraction: ExtractionResult?): ExtractionStatus = when {
        upload == null -> ExtractionStatus.NOT_PROCESSED
        upload.state == DocumentState.PROCESSING -> ExtractionStatus.PROCESSING
        extraction == null && (upload.state == DocumentState.NEEDS_REVIEW || upload.lastError != null) ->
            ExtractionStatus.FAILED

        extraction == null -> ExtractionStatus.NOT_PROCESSED
        extraction.fields.isEmpty() || extraction.fields.all { it.value.isBlank() } -> ExtractionStatus.FAILED
        extraction.fields.any { it.value.isBlank() } -> ExtractionStatus.PARTIAL
        else -> ExtractionStatus.SUCCESS
    }

    private fun evidenceFieldsOf(extraction: ExtractionResult?): List<EvidenceField> =
        extraction?.fields?.map { field ->
            val status = when {
                field.value.isBlank() -> FieldEvidenceStatus.UNAVAILABLE
                field.userReviewed -> FieldEvidenceStatus.USER_CONFIRMED
                field.warning?.contains("Needs review", ignoreCase = true) == true -> FieldEvidenceStatus.AMBIGUOUS
                else -> FieldEvidenceStatus.DETECTED
            }
            EvidenceField(key = field.key, label = field.label, value = field.value, status = status)
        }.orEmpty()

    private fun sourceLabelsOf(catalog: VisaCatalog?, sourceIds: List<String>): List<String> =
        sourceIds.map { id ->
            val ref = catalog?.sources?.get(id)
            if (ref == null || (ref.organization.isBlank() && ref.title.isBlank())) {
                id
            } else {
                "$id · ${ref.organization.ifBlank { ref.title }}"
            }
        }

    private fun requirementNote(doc: DocumentRequirement): String {
        val note = (doc.evidenceNotes.firstOrNull { it.isNotBlank() }
            ?: doc.specificationNotes.firstOrNull { it.isNotBlank() }
            ?: "document not further specified").trimEnd('.')
        return "The dataset requires \"${prettify(doc.name)}\": $note."
    }

    private fun ruleText(rule: VerificationRule, fallback: String?): String {
        val description = rule.description?.takeIf { it.isNotBlank() }
        if (description != null && fallback != null) return "$description $fallback"
        return description ?: fallback ?: rule.ruleType
    }

    private fun prettify(raw: String): String = raw.replace('_', ' ').trim()

    private fun evidenceSummary(upload: DocumentUpload, extraction: ExtractionResult?): String = buildString {
        append(if (upload.state == DocumentState.PROCESSING) "Processing on device" else "Document on file")
        extraction?.let {
            append(" · ${it.method.label}")
            val populated = it.fields.count { f -> f.value.isNotBlank() }
            if (populated > 0) append(" ($populated field(s) with values)")
            it.pageCount?.let { pages -> append(", $pages page(s)") }
        }
        upload.lastError?.let { append(" — $it") }
    }

    private fun primaryEvidenceValue(doc: DocumentRequirement, extraction: ExtractionResult): String? {
        val keys = when {
            doc.id.contains("passport", ignoreCase = true) -> listOf("expiry_date")
            doc.id.contains("bank", ignoreCase = true) -> listOf("closing_balance")
            doc.id.contains("ticket", ignoreCase = true) -> listOf("travel_date", "return_date")
            doc.id.contains("insurance", ignoreCase = true) -> listOf("coverage_end", "coverage_start")
            doc.id.contains("residence", ignoreCase = true) || doc.id.contains("accommodation", ignoreCase = true) ->
                listOf("checkin_date", "checkout_date")

            else -> emptyList()
        }
        return keys.firstNotNullOfOrNull { key ->
            extraction.fields.firstOrNull { it.key == key }?.value?.takeIf { it.isNotBlank() }
        }
    }

    private fun nextActionFor(outcome: EvaluationOutcome, verification: RuleVerification = RuleVerification.NO_RULE): String =
        when (outcome) {
            EvaluationOutcome.SATISFIED -> "No action needed."
            EvaluationOutcome.MISSING -> "Import the required document."
            EvaluationOutcome.POTENTIAL_ISSUE -> "Review the extracted value; it conflicts with a configured requirement."
            EvaluationOutcome.COULD_NOT_VERIFY -> "Re-upload a clearer copy or request manual review."
            EvaluationOutcome.MANUAL_REVIEW_REQUIRED -> when (verification) {
                RuleVerification.UNSUPPORTED ->
                    "Confirm this document's content with your agency; no automatic evaluator exists for its rule."

                RuleVerification.UNVERIFIED ->
                    "Ask a reviewer or your agency to confirm this item against the issuing channel."

                RuleVerification.NO_RULE ->
                    "Ask your agency to confirm that this document satisfies the requirement."

                RuleVerification.VERIFIED -> "Ask a reviewer or your agency to confirm this item."
            }

            EvaluationOutcome.RULE_UNVERIFIED -> "No automatic check is available; confirm with the official channel."
        }

}