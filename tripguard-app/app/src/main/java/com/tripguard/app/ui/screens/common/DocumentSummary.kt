package com.tripguard.app.ui.screens.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.tripguard.app.domain.model.DocumentState
import com.tripguard.app.domain.model.DocumentUpload
import com.tripguard.app.domain.model.ExtractedField
import com.tripguard.app.ui.theme.SoftAmberBg
import com.tripguard.app.ui.theme.SoftAmberFg
import com.tripguard.app.ui.theme.SoftBlueBg
import com.tripguard.app.ui.theme.SoftBlueFg
import com.tripguard.app.ui.theme.SoftEmeraldBg
import com.tripguard.app.ui.theme.SoftEmeraldFg
import com.tripguard.app.ui.theme.SoftRedBg
import com.tripguard.app.ui.theme.SoftRedFg
import com.tripguard.app.ui.theme.SoftSlateBg
import com.tripguard.app.ui.theme.SoftSlateFg
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Visual tone used by the compact per-document status labels. */
enum class DocumentStatusTone { SLATE, BLUE, AMBER, RED, EMERALD }

/** Compact, runtime-derived summary for one uploaded document. */
data class DocumentSummary(
    val statusLabel: String,
    val tone: DocumentStatusTone,
    val resultLine: String,
    val headline: Pair<String, String>?,
)

data class DocumentStatusLabel(
    val label: String,
    val tone: DocumentStatusTone,
)

/**
 * Builds the compact one-line summaries used by the document list and the readiness summary.
 *
 * Every string is derived from persisted upload/extraction state; sample values are never
 * injected, and "text was read" is never presented as "the document is valid".
 */
object DocumentSummaryFactory {

    /** Fields that are most useful to surface first, in preference order. */
    private val headlineKeyOrder = listOf(
        "expiry_date",
        "date_of_expiry",
        "date_of_birth",
        "passport_number",
        "full_name",
        "travel_date",
        "return_date",
        "departure_date",
        "arrival_date",
        "booked_on",
        "coverage_start",
        "coverage_end",
        "start_date",
        "end_date",
        "policy_number",
        "booking_reference",
        "insured_name",
        "insurer_name",
        "closing_balance",
    )

    /** Compact status labels. A document is never labelled "valid" merely because text was read. */
    fun statusLabel(state: DocumentState): DocumentStatusLabel = when (state) {
        DocumentState.NOT_UPLOADED -> DocumentStatusLabel("Missing", DocumentStatusTone.SLATE)
        DocumentState.UPLOADING,
        DocumentState.UPLOADED,
        DocumentState.PROCESSING,
        -> DocumentStatusLabel("Processing", DocumentStatusTone.BLUE)

        DocumentState.UPLOAD_FAILED -> DocumentStatusLabel("Could not read", DocumentStatusTone.RED)
        DocumentState.EXTRACTED -> DocumentStatusLabel("Extracted", DocumentStatusTone.BLUE)
        DocumentState.NEEDS_REVIEW -> DocumentStatusLabel("Needs review", DocumentStatusTone.AMBER)
        DocumentState.POTENTIAL_ISSUE -> DocumentStatusLabel("Needs review", DocumentStatusTone.RED)
        DocumentState.ACCEPTED -> DocumentStatusLabel("Accepted", DocumentStatusTone.EMERALD)
    }

    fun summarize(upload: DocumentUpload): DocumentSummary {
        val status = statusLabel(upload.state)
        val headline = headlineValue(upload.extraction?.fields.orEmpty())
        return DocumentSummary(
            statusLabel = status.label,
            tone = status.tone,
            resultLine = resultLine(upload, headline),
            headline = headline,
        )
    }

    private fun resultLine(upload: DocumentUpload, headline: Pair<String, String>?): String {
        if (!upload.hasFile) return "Not uploaded yet"

        upload.imageValidation?.let { validation ->
            val m = validation.measurements
            return if (m.isUsable) {
                "Image checked · ${m.widthPx} × ${m.heightPx} px · ${m.aspectRatioLabel}"
            } else {
                "Image checked · could not be measured"
            }
        }

        upload.extraction?.let { extraction ->
            val fieldsWithValues = extraction.fields.filter { it.value.isNotBlank() }
            return when {
                headline != null -> "${headline.first}: ${headline.second}"
                fieldsWithValues.isNotEmpty() -> "${fieldsWithValues.size} field(s) read"
                else -> "Read on device · no fields detected"
            }
        }

        return when {
            upload.lastError != null -> "Could not read: ${shortError(upload.lastError ?: "")}"
            upload.state == DocumentState.PROCESSING || upload.state == DocumentState.UPLOADING ->
                "Processing on this device"

            else -> "Waiting for processing"
        }
    }

    /** Most useful detected field (label to formatted value), or null when nothing is populated. */
    fun headlineValue(fields: List<ExtractedField>): Pair<String, String>? {
        val byKey = fields.associateBy { it.key }
        headlineKeyOrder.firstNotNullOfOrNull { key ->
            byKey[key]?.takeIf { it.value.isNotBlank() }
        }?.let { return it.label to formatDisplayValue(it.value) }

        return fields.firstOrNull { it.value.isNotBlank() }?.let { it.label to formatDisplayValue(it.value) }
    }

    /** Formats ISO dates as "10 Jan 2032"; any other value is returned unchanged. Never guesses. */
    fun formatDisplayValue(raw: String): String {
        val parsed = runCatching { LocalDate.parse(raw.trim()) }.getOrNull()
        return parsed?.format(DATE_FORMAT) ?: raw.trim()
    }

    private val DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    private fun shortError(error: String): String =
        error.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: error
}

/** Leading icon for a document, picked from its persisted id/category. */
fun documentIcon(documentId: String): ImageVector = when {
    documentId.contains("photograph", ignoreCase = true) ||
        documentId.contains("photo", ignoreCase = true) ->
        Icons.Outlined.Image

    documentId.contains("passport", ignoreCase = true) ->
        Icons.Outlined.Badge

    documentId.contains("ticket", ignoreCase = true) ||
        documentId.contains("flight", ignoreCase = true) ->
        Icons.Outlined.Flight

    documentId.contains("insurance", ignoreCase = true) ->
        Icons.Outlined.HealthAndSafety

    documentId.contains("bank", ignoreCase = true) ->
        Icons.Outlined.AccountBalance

    documentId.contains("accommodation", ignoreCase = true) ||
        documentId.contains("residence", ignoreCase = true) ||
        documentId.contains("hotel", ignoreCase = true) ->
        Icons.Outlined.Home

    else -> Icons.Outlined.Description
}

/** Background/foreground colour pair for a compact [DocumentStatusTone]. */
fun documentToneColors(tone: DocumentStatusTone): Pair<Color, Color> = when (tone) {
    DocumentStatusTone.SLATE -> SoftSlateBg to SoftSlateFg
    DocumentStatusTone.BLUE -> SoftBlueBg to SoftBlueFg
    DocumentStatusTone.AMBER -> SoftAmberBg to SoftAmberFg
    DocumentStatusTone.RED -> SoftRedBg to SoftRedFg
    DocumentStatusTone.EMERALD -> SoftEmeraldBg to SoftEmeraldFg
}