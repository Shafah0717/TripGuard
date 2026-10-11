package com.tripguard.app.data.ocr

import java.io.File
import java.util.zip.Inflater

/**
 * Minimal, dependency-free local PDF text extractor for selectable-text PDFs.
 *
 * It is deliberately conservative: it only returns text it actually decoded from content streams
 * and never fabricates anything. If a PDF is a scan (image-only) or uses encodings it cannot
 * decode, it produces an empty result so the caller can fall back to rendering the pages and
 * running ML Kit instead.
 *
 * Supported: classic (non-encrypted) PDFs, the common stream filters [ASCII85Decode],
 * [ASCIIHexDecode], [FlateDecode] and [LZWDecode], and the text-showing operators `Tj`, `TJ`,
 * `'` and `"`. Text is decoded using WinAnsiEncoding (CP1252) which covers the Latin fixtures.
 */
object PdfTextExtractor {

    data class Result(
        val text: String,
        val pageCount: Int,
        val warnings: List<String> = emptyList(),
    )

    private const val ISO = "ISO-8859-1"

    /** Returns a [Result] when real text was decoded, or null when the PDF has no usable text. */
    fun extract(file: File): Result? {
        if (!file.exists() || file.length() == 0L) return null
        val content = runCatching { String(file.readBytes(), charset(ISO)) }.getOrNull() ?: return null
        if (!content.startsWith("%PDF")) return null

        val pageCount = Regex("/Type\\s*/Page(?![sA-Za-z])").findAll(content).count().coerceAtLeast(1)

        val builder = StringBuilder()
        var decodedStreams = 0
        for (obj in OBJECT.findAll(content)) {
            val body = obj.groupValues[3]
            val streamIndex = body.indexOf("stream")
            if (streamIndex < 0) continue
            val dict = body.substring(0, streamIndex)

            val raw = sliceStream(body, streamIndex, dict) ?: continue
            val decoded = decodeStream(raw, dict) ?: continue
            if (!looksLikeContentStream(decoded)) continue

            val text = extractTextOperators(decoded)
            if (text.isNotBlank()) {
                builder.append(text).append('\n')
                decodedStreams++
            }
        }

        val text = builder.toString().trim()
        if (text.isBlank()) return null

        val warnings = buildList {
            if (pageCount > 1) add("Extracted selectable text from a $pageCount-page PDF.")
            if (decodedStreams == 0) add("No decodable text streams were found.")
        }
        return Result(text = text, pageCount = pageCount, warnings = warnings)
    }

    // ---- stream slicing -----------------------------------------------------

    private val OBJECT = Regex("(?s)(\\d+)\\s+(\\d+)\\s+obj(.*?)endobj")

    private fun sliceStream(body: String, streamIndex: Int, dict: String): String? {
        val newline = body.indexOf('\n', streamIndex)
        if (newline < 0) return null
        val start = newline + 1

        val declaredLength = Regex("/Length\\s+(\\d+)(?!\\s+\\d+\\s+R)").find(dict)?.groupValues?.get(1)?.toIntOrNull()
        if (declaredLength != null && declaredLength >= 0 && start + declaredLength <= body.length) {
            return body.substring(start, start + declaredLength)
        }
        val end = body.indexOf("endstream", start)
        if (end < 0) return null
        return body.substring(start, end).trimEnd('\r', '\n')
    }

    // ---- filters ------------------------------------------------------------

    private fun decodeStream(raw: String, dict: String): String? {
        val filters = parseFilters(dict)
        if (filters.isEmpty()) return raw

        var bytes = raw.toByteArray(charset(ISO))
        for (filter in filters) {
            bytes = when (filter) {
                "ASCII85Decode", "A85" -> ascii85(bytes) ?: return null
                "ASCIIHexDecode", "AHx" -> asciiHex(bytes) ?: return null
                "FlateDecode", "Fl" -> inflate(bytes) ?: return null
                "LZWDecode", "LZW" -> lzw(bytes, parseEarlyChange(dict)) ?: return null
                else -> return null
            }
        }
        return String(bytes, charset(ISO))
    }

    private fun parseFilters(dict: String): List<String> {
        val value = Regex("/Filter\\s*(\\[[^\\]]*\\]|/[A-Za-z0-9]+)").find(dict)?.groupValues?.get(1) ?: return emptyList()
        if (value.startsWith("[")) {
            return Regex("/([A-Za-z0-9]+)").findAll(value).map { it.groupValues[1] }.toList()
        }
        return listOf(value.removePrefix("/"))
    }

    private fun parseEarlyChange(dict: String): Int =
        Regex("/EarlyChange\\s+(\\d+)").find(dict)?.groupValues?.get(1)?.toIntOrNull() ?: 1

    private fun inflate(bytes: ByteArray): ByteArray? {
        for (nowrap in listOf(false, true)) {
            runCatching {
                val inflater = Inflater(nowrap)
                try {
                    inflater.setInput(bytes)
                    val out = java.io.ByteArrayOutputStream(bytes.size * 4)
                    val buffer = ByteArray(8192)
                    while (!inflater.finished()) {
                        val n = inflater.inflate(buffer)
                        if (n == 0) {
                            if (inflater.needsInput() || inflater.needsDictionary()) break
                        }
                        out.write(buffer, 0, n)
                    }
                    val result = out.toByteArray()
                    if (result.isNotEmpty()) return result
                } finally {
                    inflater.end()
                }
            }
        }
        return null
    }

    private fun ascii85(input: ByteArray): ByteArray? = runCatching {
        val out = java.io.ByteArrayOutputStream(input.size * 4 / 5 + 4)
        var tuple = 0L
        var count = 0
        var index = 0
        while (index < input.size) {
            val c = input[index++].toInt() and 0xFF
            when {
                c == 0x20 || c == 0x09 || c == 0x0A || c == 0x0D || c == 0x0C || c == 0x00 -> Unit
                c == '~'.code -> return@runCatching out.toByteArray()
                c == 'z'.code && count == 0 -> repeat(4) { out.write(0) }
                c in 0x21..0x75 -> {
                    tuple = tuple * 85 + (c - 0x21)
                    count++
                    if (count == 5) {
                        out.write(((tuple shr 24) and 0xFF).toInt())
                        out.write(((tuple shr 16) and 0xFF).toInt())
                        out.write(((tuple shr 8) and 0xFF).toInt())
                        out.write((tuple and 0xFF).toInt())
                        tuple = 0
                        count = 0
                    }
                }

                else -> Unit
            }
        }
        if (count > 0) {
            for (i in count until 5) tuple = tuple * 85 + 84
            out.write(((tuple shr 24) and 0xFF).toInt())
            out.write(((tuple shr 16) and 0xFF).toInt())
            out.write(((tuple shr 8) and 0xFF).toInt())
            out.write((tuple and 0xFF).toInt())
        }
        out.toByteArray()
    }.getOrNull()

    private fun asciiHex(input: ByteArray): ByteArray? = runCatching {
        val out = java.io.ByteArrayOutputStream(input.size / 2 + 1)
        val digits = StringBuilder()
        for (b in input) {
            val c = b.toInt() and 0xFF
            when {
                c == '>'.code -> break
                (c in '0'.code..'9'.code) || (c in 'a'.code..'f'.code) || (c in 'A'.code..'F'.code) ->
                    digits.append(c.toChar())

                c == 0x20 || c == 0x0A || c == 0x0D || c == 0x09 -> Unit
                else -> Unit
            }
        }
        if (digits.length % 2 == 1) digits.append('0')
        var i = 0
        while (i < digits.length) {
            out.write(digits.substring(i, i + 2).toInt(16))
            i += 2
        }
        out.toByteArray()
    }.getOrNull()

    /** Early-change LZW decoder, sufficient for simple text streams. */
    private fun lzw(input: ByteArray, earlyChange: Int): ByteArray? = runCatching {
        val out = java.io.ByteArrayOutputStream()
        var dict = Array(4096) { ByteArray(0) }
        var dictSize = 0
        for (i in 0 until 256) dict[i] = byteArrayOf(i.toByte())
        dictSize = 258
        var codeWidth = 9
        var prev: ByteArray? = null
        var bitBuffer = 0
        var bitCount = 0
        var index = 0
        while (index < input.size) {
            bitBuffer = (bitBuffer shl 8) or (input[index++].toInt() and 0xFF)
            bitCount += 8
            while (bitCount >= codeWidth) {
                val code = (bitBuffer shr (bitCount - codeWidth)) and ((1 shl codeWidth) - 1)
                bitCount -= codeWidth
                if (code == 256) {
                    dict = Array(4096) { ByteArray(0) }
                    for (i in 0 until 256) dict[i] = byteArrayOf(i.toByte())
                    dictSize = 258
                    codeWidth = 9
                    prev = null
                    continue
                }
                if (code == 257) return@runCatching out.toByteArray()
                val entry: ByteArray = when {
                    code < dictSize -> dict[code]
                    prev != null -> prev.plus(prev[0])
                    else -> return@runCatching out.toByteArray()
                }
                out.write(entry)
                if (prev != null && dictSize < 4096) {
                    dict[dictSize++] = prev.plus(entry[0])
                }
                if (dictSize + earlyChange >= (1 shl codeWidth) && codeWidth < 12) codeWidth++
                prev = entry
            }
        }
        out.toByteArray()
    }.getOrNull()

    // ---- content stream text extraction ------------------------------------

    private val SHOW_TOKEN = Regex(
        "(?s)(\\[(?:[^\\[\\]\\\\]|\\\\.)*\\]\\s*TJ)" +
            "|((?:\\((?:\\\\.|[^\\\\()])*\\)|<[0-9A-Fa-f\\s]*>)\\s*(?:Tj|'|\"))",
    )
    private val STRING_LIKE = Regex("(?:\\r\\n)?(?:\\((?:\\\\.|[^\\\\()])*\\)|<[0-9A-Fa-f\\s]*>)")

    private fun looksLikeContentStream(content: String): Boolean =
        content.contains(" Tj") || content.contains(" TJ") || content.contains("\nTj") ||
            content.contains("T*") || content.contains("BT")

    private fun extractTextOperators(content: String): String {
        val runs = mutableListOf<String>()
        for (match in SHOW_TOKEN.findAll(content)) {
            val token = match.value.trim()
            if (token.endsWith("TJ")) {
                val array = token.substringBeforeLast("TJ")
                runs += STRING_LIKE.findAll(array).joinToString("") { decodeStringObject(it.value) }
            } else {
                val literal = STRING_LIKE.find(token)?.value ?: continue
                runs += decodeStringObject(literal)
            }
        }
        return runs.filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun decodeStringObject(token: String): String {
        val trimmed = token.trim()
        if (trimmed.startsWith("<")) {
            return decodeHexString(trimmed.removePrefix("<").removeSuffix(">"))
        }
        val inner = trimmed.removePrefix("(").removeSuffix(")")
        return decodeLiteral(inner)
    }

    private fun decodeHexString(hex: String): String {
        val cleaned = hex.filter { it.isLetterOrDigit() }
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i + 1 < cleaned.length) {
            runCatching { bytes.write(cleaned.substring(i, i + 2).toInt(16)) }
            i += 2
        }
        return winAnsi(bytes.toByteArray())
    }

    private fun decodeLiteral(value: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\') {
                bytes.write(c.code and 0xFF)
                i++
                continue
            }
            i++
            if (i >= value.length) break
            when (val e = value[i]) {
                'n' -> { bytes.write('\n'.code); i++ }
                'r' -> { bytes.write('\r'.code); i++ }
                't' -> { bytes.write('\t'.code); i++ }
                'b' -> { bytes.write('\b'.code); i++ }
                'f' -> { bytes.write('\u000C'.code); i++ }
                '(', ')', '\\' -> { bytes.write(e.code); i++ }
                '\r' -> i++
                '\n' -> i++
                in '0'..'7' -> {
                    var octal = ""
                    var consumed = 0
                    while (consumed < 3 && i < value.length && value[i] in '0'..'7') {
                        octal += value[i]
                        i++
                        consumed++
                    }
                    bytes.write(octal.toInt(8) and 0xFF)
                }

                else -> { bytes.write(e.code and 0xFF); i++ }
            }
        }
        return winAnsi(bytes.toByteArray())
    }

    private fun winAnsi(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size)
        for (b in bytes) {
            val code = b.toInt() and 0xFF
            when (code) {
                in 0x80..0x9F -> out.append(CP1252[code - 0x80])
                0x7F -> out.append(' ')
                else -> out.append(code.toChar())
            }
        }
        return out.toString()
    }

    private val CP1252 = charArrayOf(
        '\u20AC', '\uFFFD', '\u201A', '\u0192', '\u201E', '\u2026', '\u2020', '\u2021',
        '\u02C6', '\u2030', '\u0160', '\u2039', '\u0152', '\uFFFD', '\u017D', '\uFFFD',
        '\uFFFD', '\u2018', '\u2019', '\u201C', '\u201D', '\u2022', '\u2013', '\u2014',
        '\u02DC', '\u2122', '\u0161', '\u203A', '\u0153', '\uFFFD', '\u017E', '\u0178',
    )

    private fun charset(name: String) = java.nio.charset.Charset.forName(name)
}
