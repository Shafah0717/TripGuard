package com.tripguard.app.data.ocr

import com.tripguard.app.domain.model.ExtractedField
import com.tripguard.app.domain.model.OcrMethod
import com.tripguard.app.domain.rules.DateNormalizer
import com.tripguard.app.domain.rules.DateParse

/**
 * Deterministic, document-type-aware field extraction, kept strictly separate from raw text
 * acquisition. Pure Kotlin with no Android dependency so it can be unit tested directly.
 *
 * It supports the two layouts produced by real documents:
 *  - inline `Label: value` on one line, and
 *  - a `Label` line followed by its value on the next line (the layout in the synthetic PDFs).
 *
 * Only values actually found are populated. A field that is absent in the document is returned
 * with an empty value (the UI renders "Not detected."), and an ambiguous date is never guessed.
 */
object DocumentFieldParser {

    fun parse(documentId: String, text: String, method: OcrMethod? = null): List<ExtractedField> {
        val clean = text.replace("\r", "")
        val fields = when {
            documentId.contains("passport", ignoreCase = true) -> passport(clean)
            documentId.contains("bank", ignoreCase = true) -> bankStatement(clean)
            documentId.contains("ticket", ignoreCase = true) -> ticket(clean)
            documentId.contains("insurance", ignoreCase = true) -> insurance(clean)
            documentId.contains("residence", ignoreCase = true) ||
                documentId.contains("accommodation", ignoreCase = true) ||
                documentId.contains("stay", ignoreCase = true) -> accommodation(clean)

            else -> emptyList()
        }
        return fields.map { it.copy(method = it.method ?: method) }
    }

    // ---- passport -----------------------------------------------------------

    private fun passport(text: String): List<ExtractedField> {
        val lines = linesOf(text)
        val labels = mapOf(
            "full_name" to listOf("full name", "given name", "given names"),
            "passport_number" to listOf("passport number", "passport no", "passport no.", "pass no", "passport #"),
            "nationality" to listOf("nationality"),
            "date_of_birth" to listOf("date of birth", "dob", "birth date"),
            "issue_date" to listOf("date of issue", "issue date", "issued"),
            "expiry_date" to listOf("date of expiry", "expiry date", "expiry", "expires", "date of expiration"),
            "issuing_authority" to listOf("issuing authority", "authority"),
        )
        val map = buildLabelMap(lines, labels)
        val fields = mutableListOf<ExtractedField>()

        addText(fields, "full_name", "Full Name", mrzName(text) ?: map["full_name"], 0.95f)
        addText(fields, "passport_number", "Passport Number", map["passport_number"], 0.93f, PASSPORT_NUMBER)
        addText(fields, "nationality", "Nationality", map["nationality"], 0.92f)
        addDate(fields, "date_of_birth", "Date of Birth", map["date_of_birth"])
        addDate(fields, "issue_date", "Issue Date", map["issue_date"])
        addDate(fields, "expiry_date", "Expiry Date", map["expiry_date"])
        addText(fields, "issuing_authority", "Issuing Authority", map["issuing_authority"], 0.90f)
        return fields
    }

    private fun mrzName(text: String): String? {
        val mrz = Regex("P<([A-Z]{3})([A-Z<]{3,})").find(text) ?: return null
        val parts = mrz.groupValues[2].split("<<")
        val surname = parts.getOrNull(0)?.replace('<', ' ')?.trim().orEmpty()
        val given = parts.getOrNull(1)?.replace('<', ' ')?.trim().orEmpty()
        return listOf(given, surname).filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }
    }

    // ---- bank statement -----------------------------------------------------

    private fun bankStatement(text: String): List<ExtractedField> {
        val lines = linesOf(text)
        val labels = mapOf(
            "account_holder" to listOf("account holder", "account name", "customer name", "name"),
            "account_number" to listOf("account number", "account reference", "account no", "a/c no"),
            "closing_balance" to listOf("closing balance", "available balance"),
            "statement_period" to listOf("statement period"),
        )
        val map = buildLabelMap(lines, labels)
        val fields = mutableListOf<ExtractedField>()

        addText(fields, "account_holder", "Account Holder", map["account_holder"], 0.90f)
        addText(fields, "account_number", "Account Number", map["account_number"], 0.88f)
        addText(fields, "closing_balance", "Closing Balance", map["closing_balance"], 0.94f, BALANCE)
        addText(fields, "statement_period", "Statement Period", map["statement_period"], 0.88f)
        return fields
    }

    // ---- return / onward ticket --------------------------------------------

    private fun ticket(text: String): List<ExtractedField> {
        val lines = linesOf(text)
        val labels = mapOf(
            "passenger_name" to listOf("passenger name", "passenger", "traveller", "traveler", "name"),
            "booking_reference" to listOf("booking reference", "booking ref", "pnr", "reference", "ticket number"),
            "travel_date" to listOf("departure date", "date of travel", "travel date", "departure"),
            "return_date" to listOf("return date", "return"),
        )
        val map = buildLabelMap(lines, labels)
        val fields = mutableListOf<ExtractedField>()

        addText(fields, "passenger_name", "Passenger Name", map["passenger_name"], 0.90f)
        addText(fields, "booking_reference", "Booking Reference", map["booking_reference"], 0.90f, REFERENCE)
        addDate(fields, "travel_date", "Departure Date", map["travel_date"] ?: firstDateAfter(lines, "Outbound journey"))
        addDate(fields, "return_date", "Return/Onward Date", map["return_date"] ?: firstDateAfter(lines, "Return journey"))
        return fields
    }

    // ---- medical insurance --------------------------------------------------

    private fun insurance(text: String): List<ExtractedField> {
        val lines = linesOf(text)
        val labels = mapOf(
            "insured_name" to listOf("insured person", "insured", "policy holder", "policyholder", "member name", "name"),
            "policy_number" to listOf("policy number", "policy no", "policy reference", "certificate no"),
            "insurer_name" to listOf("insurer", "insurance company", "provider", "underwritten by"),
            "territory" to listOf("territory", "coverage area", "country"),
            "coverage_start" to listOf("coverage start", "valid from", "start date"),
            "coverage_end" to listOf("coverage end", "valid until", "coverage expiry", "valid to", "end date"),
        )
        val map = buildLabelMap(lines, labels)
        val fields = mutableListOf<ExtractedField>()

        addText(fields, "insured_name", "Insured Person", map["insured_name"], 0.90f)
        addText(fields, "policy_number", "Policy Number", map["policy_number"], 0.92f, REFERENCE)
        addText(fields, "insurer_name", "Insurer", map["insurer_name"], 0.88f)
        addText(fields, "territory", "Coverage Territory", map["territory"], 0.85f)
        addDate(fields, "coverage_start", "Coverage Start", map["coverage_start"])
        addDate(fields, "coverage_end", "Coverage End", map["coverage_end"])
        return fields
    }

    // ---- accommodation / proof of residence --------------------------------

    private fun accommodation(text: String): List<ExtractedField> {
        val lines = linesOf(text)
        val labels = mapOf(
            "guest_name" to listOf("guest name", "guest", "name"),
            "property" to listOf("property", "hotel", "accommodation"),
            "city" to listOf("city"),
            "checkin_date" to listOf("check-in", "check in", "checkin", "arrival"),
            "checkout_date" to listOf("check-out", "check out", "checkout"),
            "reference" to listOf("reference", "booking reference", "confirmation"),
        )
        val map = buildLabelMap(lines, labels)
        val fields = mutableListOf<ExtractedField>()

        addText(fields, "guest_name", "Guest Name", map["guest_name"], 0.90f)
        addText(fields, "property", "Property", map["property"], 0.88f)
        addText(fields, "city", "City", map["city"], 0.88f)
        addDate(fields, "checkin_date", "Check-in", map["checkin_date"])
        addDate(fields, "checkout_date", "Check-out", map["checkout_date"])
        addText(fields, "reference", "Reference", map["reference"], 0.88f, REFERENCE)
        return fields
    }

    // ---- label/value resolution --------------------------------------------

    private fun linesOf(text: String): List<String> =
        text.split('\n').map { it.trim() }.filter { it.isNotBlank() }

    private fun buildLabelMap(lines: List<String>, labels: Map<String, List<String>>): Map<String, String> {
        val map = LinkedHashMap<String, String>()

        // Pass 1: inline "Label: value" (also accepts en/em dash separators).
        for (line in lines) {
            for ((key, aliases) in labels) {
                if (map.containsKey(key)) continue
                val alias = aliases.joinToString("|") { Regex.escape(it) }
                val match = Regex("(?i)\\b(?:$alias)\\b\\s*[:\\-–—]\\s*(.+)$").find(line) ?: continue
                val value = match.groupValues[1].trim()
                if (value.isNotBlank()) map[key] = value
            }
        }

        // Pass 2: label on its own line, value on the following line.
        for (i in lines.indices) {
            val line = lines[i].trim().trimEnd(':', '-').trim()
            for ((key, aliases) in labels) {
                if (map.containsKey(key)) continue
                if (aliases.none { it.equals(line, ignoreCase = true) }) continue
                val next = lines.getOrNull(i + 1)?.trim() ?: continue
                if (looksLikeValue(next) && !isAnyLabel(next, labels)) map[key] = next
            }
        }
        return map
    }

    private fun isAnyLabel(line: String, labels: Map<String, List<String>>): Boolean =
        labels.values.any { aliases -> aliases.any { it.equals(line, ignoreCase = true) } }

    /** A value line is expected to contain a digit, a space, or be reasonably long. */
    private fun looksLikeValue(line: String): Boolean =
        line.any { it.isDigit() } || line.contains(' ') || line.length >= 10

    private fun firstDateAfter(lines: List<String>, header: String): String? {
        val start = lines.indexOfFirst { it.contains(header, ignoreCase = true) }
        if (start < 0) return null
        for (i in start + 1 until minOf(start + 7, lines.size)) {
            when (DateNormalizer.normalize(lines[i])) {
                is DateParse.Success, is DateParse.Ambiguous -> return lines[i]
                is DateParse.Unparseable -> Unit
            }
        }
        return null
    }

    // ---- field builders -----------------------------------------------------

    private fun addText(
        out: MutableList<ExtractedField>,
        key: String,
        label: String,
        raw: String?,
        confidence: Float,
        extract: Regex? = null,
    ) {
        val candidate = raw?.let { extract?.find(it)?.value ?: it }?.trim()
        if (candidate.isNullOrBlank()) {
            out += notDetected(key, label)
            return
        }
        out += ExtractedField(key = key, label = label, value = candidate, confidence = confidence)
    }

    private fun addDate(out: MutableList<ExtractedField>, key: String, label: String, raw: String?) {
        if (raw.isNullOrBlank()) {
            out += notDetected(key, label)
            return
        }
        out += when (val parsed = DateNormalizer.normalize(raw)) {
            is DateParse.Success ->
                ExtractedField(key = key, label = label, value = parsed.iso, confidence = 0.95f)

            is DateParse.Ambiguous -> ExtractedField(
                key = key,
                label = label,
                value = raw.trim(),
                confidence = 0.5f,
                warning = "Needs review. ${parsed.reason}",
            )

            is DateParse.Unparseable -> ExtractedField(
                key = key,
                label = label,
                value = raw.trim(),
                confidence = 0.4f,
                warning = "Needs review. ${parsed.reason}",
            )
        }
    }

    private fun notDetected(key: String, label: String): ExtractedField =
        ExtractedField(key = key, label = label, value = "", confidence = null, warning = null)

    private val PASSPORT_NUMBER = Regex("[A-Za-z0-9][A-Za-z0-9\\- ]{4,}")
    private val BALANCE = Regex("([A-Za-z]{3})?\\s*([\\d,]+\\.\\d{2})")
    private val REFERENCE = Regex("[A-Za-z0-9][A-Za-z0-9\\-/]{3,}")
}
