package com.tripguard.app.domain.rules

import java.time.LocalDate

/**
 * Outcome of normalising a candidate date string. Ambiguity is surfaced explicitly and never
 * resolved silently: a value like `05/06/2026` is reported as [Ambiguous] rather than guessed.
 */
sealed interface DateParse {
    data class Success(val iso: String) : DateParse
    data class Ambiguous(val raw: String, val reason: String) : DateParse
    data class Unparseable(val raw: String, val reason: String) : DateParse
}

/**
 * Deterministic, locale-independent date normaliser. Only unambiguous inputs are converted to
 * ISO `YYYY-MM-DD`. Supports `YYYY-MM-DD`, `YYYY/MM/DD`, `YYYY.MM.DD` and unambiguous
 * `DD-MM-YYYY` / `MM-DD-YYYY` forms (where one component is greater than 12).
 */
object DateNormalizer {

    private val separators = Regex("[-/.]")
    private val isoFormatter = "%04d-%02d-%02d"

    /** Month-name forms present in printed documents, e.g. "14 February 1998" or "Feb 14, 1998". */
    private val dayMonthYear = Regex("(?i)^(\\d{1,2})\\s+([A-Za-z]{3,9})\\.?,?\\s+(\\d{4})$")
    private val monthDayYear = Regex("(?i)^([A-Za-z]{3,9})\\.?\\s+(\\d{1,2}),?\\s+(\\d{4})$")

    private val months = mapOf(
        "jan" to 1, "january" to 1,
        "feb" to 2, "february" to 2,
        "mar" to 3, "march" to 3,
        "apr" to 4, "april" to 4,
        "may" to 5,
        "jun" to 6, "june" to 6,
        "jul" to 7, "july" to 7,
        "aug" to 8, "august" to 8,
        "sep" to 9, "sept" to 9, "september" to 9,
        "oct" to 10, "october" to 10,
        "nov" to 11, "november" to 11,
        "dec" to 12, "december" to 12,
    )

    fun normalize(raw: String): DateParse {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return DateParse.Unparseable(raw, "No value supplied.")

        dayMonthYear.matchEntire(trimmed)?.let { m ->
            val month = months[m.groupValues[2].lowercase()]
                ?: return DateParse.Unparseable(raw, "Unrecognised month name.")
            return toIso(raw, m.groupValues[3].toInt(), month, m.groupValues[1].toInt(), note = null)
        }
        monthDayYear.matchEntire(trimmed)?.let { m ->
            val month = months[m.groupValues[1].lowercase()]
                ?: return DateParse.Unparseable(raw, "Unrecognised month name.")
            return toIso(raw, m.groupValues[3].toInt(), month, m.groupValues[2].toInt(), note = null)
        }

        val parts = trimmed.split(separators)
        if (parts.size != 3) return DateParse.Unparseable(raw, "Unrecognised date layout.")

        val numbers = parts.map { it.trim().toIntOrNull() ?: return DateParse.Unparseable(raw, "Non-numeric date component.") }
        val (first, second, third) = numbers

        return when {
            parts[0].length == 4 -> toIso(raw, first, second, third, note = null)

            parts[2].length == 4 -> {
                val firstIsDay = first > 12 && second <= 12
                val secondIsDay = second > 12 && first <= 12
                when {
                    firstIsDay -> toIso(raw, third, second, first, note = null)
                    secondIsDay -> toIso(raw, third, first, second, note = null)
                    else -> DateParse.Ambiguous(
                        raw,
                        "Day and month cannot be told apart without a stated convention.",
                    )
                }
            }

            parts[0].length == 2 || parts[2].length == 2 ->
                DateParse.Ambiguous(raw, "Two-digit years are not interpreted automatically.")

            else -> DateParse.Unparseable(raw, "Unrecognised date layout.")
        }
    }

    private fun toIso(raw: String, year: Int, month: Int, day: Int, @Suppress("UNUSED_PARAMETER") note: String?): DateParse {
        return try {
            val date = LocalDate.of(year, month, day)
            DateParse.Success(isoFormatter.format(date.year, date.monthValue, date.dayOfMonth))
        } catch (t: Throwable) {
            DateParse.Unparseable(raw, "Not a valid calendar date.")
        }
    }
}
