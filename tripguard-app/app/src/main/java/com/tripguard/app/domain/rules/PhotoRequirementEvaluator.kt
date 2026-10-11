package com.tripguard.app.domain.rules

import com.tripguard.app.domain.model.DocumentRequirement
import com.tripguard.app.domain.model.EvaluationOutcome
import com.tripguard.app.domain.model.ImageValidation
import com.tripguard.app.domain.model.PhotoMeasurements
import com.tripguard.app.domain.model.PhotoRequirementCheck
import com.tripguard.app.domain.model.VerificationRule

/**
 * Evaluates a personal photograph against only the checks the dataset actually configures.
 * Measured values are always reported; a missing or unverified rule yields
 * [EvaluationOutcome.RULE_UNVERIFIED] and never an automatic pass. Background colour, face
 * position, identity and official compliance are deliberately not claimed.
 */
object PhotoRequirementEvaluator {

    private val DIMENSION_RULE_TYPES = setOf("image_dimensions", "photo_dimensions", "image_size_check")
    private val FILE_SIZE_RULE_TYPES = setOf("image_file_size", "photo_file_size", "file_size_check")

    fun evaluate(requirement: DocumentRequirement?, measurements: PhotoMeasurements): ImageValidation {
        val now = System.currentTimeMillis()

        if (!measurements.isUsable) {
            val check = PhotoRequirementCheck(
                name = "Image readable",
                configured = true,
                outcome = EvaluationOutcome.COULD_NOT_VERIFY,
                detail = "The file could not be decoded as an image.",
            )
            return ImageValidation(
                measurements = measurements,
                checks = listOf(check),
                summaryOutcome = EvaluationOutcome.COULD_NOT_VERIFY,
                reason = "The selected file is not a readable image, so no measurements could be taken.",
                validatedAt = now,
            )
        }

        val checks = mutableListOf<PhotoRequirementCheck>()

        checks += formatCheck(requirement, measurements)
        checks += dimensionCheck(requirement, measurements)
        checks += fileSizeCheck(requirement, measurements)
        checks += backgroundCheck(requirement)

        val configuredChecks = checks.filter { it.configured }
        val summary = when {
            configuredChecks.any { it.outcome == EvaluationOutcome.POTENTIAL_ISSUE } ->
                EvaluationOutcome.POTENTIAL_ISSUE

            configuredChecks.any { it.outcome == EvaluationOutcome.COULD_NOT_VERIFY } ->
                EvaluationOutcome.COULD_NOT_VERIFY

            configuredChecks.isNotEmpty() && configuredChecks.all { it.outcome == EvaluationOutcome.SATISFIED } ->
                EvaluationOutcome.SATISFIED

            configuredChecks.isEmpty() -> EvaluationOutcome.RULE_UNVERIFIED

            else -> EvaluationOutcome.MANUAL_REVIEW_REQUIRED
        }

        val reason = when (summary) {
            EvaluationOutcome.SATISFIED ->
                "The photograph meets every rule the current dataset configures."

            EvaluationOutcome.POTENTIAL_ISSUE ->
                "The photograph conflicts with a configured rule. Review the details below."

            EvaluationOutcome.COULD_NOT_VERIFY ->
                "The photograph could not be measured against a configured rule."

            EvaluationOutcome.RULE_UNVERIFIED ->
                "Measured locally. The current dataset states no photograph dimension, aspect-ratio " +
                    "or file-size rule, so no automatic conclusion is drawn."

            else -> "The photograph requires manual review."
        }

        return ImageValidation(
            measurements = measurements,
            checks = checks,
            summaryOutcome = summary,
            reason = reason,
            validatedAt = now,
        )
    }

    private fun formatCheck(requirement: DocumentRequirement?, measurements: PhotoMeasurements): PhotoRequirementCheck {
        val accepted = requirement?.acceptedFormats.orEmpty()
        if (accepted.isEmpty()) {
            return PhotoRequirementCheck(
                name = "Accepted file format",
                configured = false,
                outcome = EvaluationOutcome.RULE_UNVERIFIED,
                detail = "The dataset does not state required photo file formats.",
            )
        }
        val ext = measurements.extension?.lowercase()
        val ok = ext != null && accepted.any { it.equals(ext, ignoreCase = true) }
        return PhotoRequirementCheck(
            name = "Accepted file format",
            configured = true,
            outcome = if (ok) EvaluationOutcome.SATISFIED else EvaluationOutcome.POTENTIAL_ISSUE,
            detail = if (ok) {
                "Format \"$ext\" is accepted (${accepted.joinToString(", ") { it.uppercase() }})."
            } else {
                "Format \"${ext ?: "unknown"}\" is not in the required set: ${accepted.joinToString(", ") { it.uppercase() }}."
            },
        )
    }

    private fun dimensionCheck(requirement: DocumentRequirement?, measurements: PhotoMeasurements): PhotoRequirementCheck {
        val rule = requirement?.verificationRules?.firstOrNull { it.ruleType.lowercase() in DIMENSION_RULE_TYPES }
            ?: return PhotoRequirementCheck(
                name = "Dimensions and aspect ratio",
                configured = false,
                outcome = EvaluationOutcome.RULE_UNVERIFIED,
                detail = "Measured locally. The dataset defines no photo dimension or aspect-ratio rule to compare against.",
            )

        val minWidth = rule.parameters["min_width_px"]?.toIntOrNull()
        val maxWidth = rule.parameters["max_width_px"]?.toIntOrNull()
        val minHeight = rule.parameters["min_height_px"]?.toIntOrNull()
        val maxHeight = rule.parameters["max_height_px"]?.toIntOrNull()

        val missingBounds = minWidth == null && maxWidth == null && minHeight == null && maxHeight == null
        if (rule.humanReviewRequired || missingBounds) {
            return PhotoRequirementCheck(
                name = "Dimensions and aspect ratio",
                configured = true,
                outcome = EvaluationOutcome.MANUAL_REVIEW_REQUIRED,
                detail = "A dimension rule exists but is not verified or lacks usable bounds, so it is not applied automatically.",
            )
        }

        val violations = buildList {
            if (minWidth != null && measurements.widthPx < minWidth) add("width below ${minWidth}px")
            if (maxWidth != null && measurements.widthPx > maxWidth) add("width above ${maxWidth}px")
            if (minHeight != null && measurements.heightPx < minHeight) add("height below ${minHeight}px")
            if (maxHeight != null && measurements.heightPx > maxHeight) add("height above ${maxHeight}px")
        }

        return if (violations.isEmpty()) {
            PhotoRequirementCheck(
                name = "Dimensions and aspect ratio",
                configured = true,
                outcome = EvaluationOutcome.SATISFIED,
                detail = "Measured ${measurements.widthPx}×${measurements.heightPx}px is within the configured bounds.",
            )
        } else {
            PhotoRequirementCheck(
                name = "Dimensions and aspect ratio",
                configured = true,
                outcome = EvaluationOutcome.POTENTIAL_ISSUE,
                detail = "Measured ${measurements.widthPx}×${measurements.heightPx}px — ${violations.joinToString("; ")}.",
            )
        }
    }

    private fun fileSizeCheck(requirement: DocumentRequirement?, measurements: PhotoMeasurements): PhotoRequirementCheck {
        val rule = requirement?.verificationRules?.firstOrNull { it.ruleType.lowercase() in FILE_SIZE_RULE_TYPES }
            ?: return PhotoRequirementCheck(
                name = "File size",
                configured = false,
                outcome = EvaluationOutcome.RULE_UNVERIFIED,
                detail = "The dataset defines no photograph file-size rule.",
            )

        val maxBytes = rule.parameters["max_size_bytes"]?.toLongOrNull()
        val minBytes = rule.parameters["min_size_bytes"]?.toLongOrNull()
        if (rule.humanReviewRequired || (maxBytes == null && minBytes == null)) {
            return PhotoRequirementCheck(
                name = "File size",
                configured = true,
                outcome = EvaluationOutcome.MANUAL_REVIEW_REQUIRED,
                detail = "A file-size rule exists but is not verified or lacks bounds.",
            )
        }
        val ok = (maxBytes == null || measurements.sizeBytes <= maxBytes) &&
            (minBytes == null || measurements.sizeBytes >= minBytes)
        return PhotoRequirementCheck(
            name = "File size",
            configured = true,
            outcome = if (ok) EvaluationOutcome.SATISFIED else EvaluationOutcome.POTENTIAL_ISSUE,
            detail = "Measured ${measurements.sizeBytes} bytes.",
        )
    }

    private fun backgroundCheck(requirement: DocumentRequirement?): PhotoRequirementCheck {
        val mentionsBackground = requirement?.verificationRules
            ?.any { it.ruleType.lowercase().contains("background") } == true
        return if (!mentionsBackground) {
            PhotoRequirementCheck(
                name = "Background and facial position",
                configured = false,
                outcome = EvaluationOutcome.RULE_UNVERIFIED,
                detail = "Not checked. Background colour, face position and identity are not verified by this app.",
            )
        } else {
            PhotoRequirementCheck(
                name = "Background and facial position",
                configured = true,
                outcome = EvaluationOutcome.MANUAL_REVIEW_REQUIRED,
                detail = "The dataset references background rules, which require human inspection.",
            )
        }
    }
}
