package com.tripguard.app.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tripguard.app.domain.model.AppError
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.ErrorKind
import com.tripguard.app.domain.model.ExtractionResult
import com.tripguard.app.domain.model.OcrMethod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Genuine on-device OCR. Images and scanned PDF pages are read with Google ML Kit Text
 * Recognition v2 using the bundled Latin model, so the first run never downloads a model. Plain
 * text files are read directly. Nothing is uploaded and no field is fabricated: an unreadable or
 * unsupported file yields a failure rather than invented values.
 *
 * The engine is split so the deterministic parsing layer ([DocumentFieldParser]) is unit-testable
 * without Android; only the raw text acquisition touches ML Kit.
 */
class LocalOcrEngine(private val context: Context? = null) {

    private enum class FileKind { TEXT, PDF, IMAGE, UNKNOWN }

    suspend fun process(
        file: File,
        documentId: String,
    ): AppResult<ExtractionResult> = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext AppResult.Failure(
                AppError(ErrorKind.VALIDATION, "The document file is missing or empty."),
            )
        }

        try {
            when (detect(file)) {
                FileKind.TEXT -> processPlainText(file, documentId)
                FileKind.PDF -> processPdf(file, documentId)
                FileKind.IMAGE -> processImage(file, documentId)
                FileKind.UNKNOWN -> AppResult.Failure(
                    AppError(ErrorKind.VALIDATION, "Unsupported or unrecognised file format."),
                )
            }
        } catch (t: Throwable) {
            AppResult.Failure(AppError(ErrorKind.PARSE, "On-device OCR could not read this document.", t))
        }
    }

    private fun processPlainText(file: File, documentId: String): AppResult<ExtractionResult> {
        val text = runCatching { file.readText() }.getOrNull().orEmpty()
        if (text.isBlank()) {
            return AppResult.Failure(AppError(ErrorKind.PARSE, "Could not detect readable text in this document."))
        }
        return buildResult(text, documentId, OcrMethod.PLAIN_TEXT, pageCount = 1, warnings = emptyList())
    }

    private fun processImage(file: File, documentId: String): AppResult<ExtractionResult> {
        if (context == null) {
            return AppResult.Failure(AppError(ErrorKind.VALIDATION, "Image OCR requires an Android runtime."))
        }
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return AppResult.Failure(AppError(ErrorKind.PARSE, "The image could not be decoded."))
        val text = try {
            recognizeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
        if (text.isBlank()) {
            return AppResult.Failure(AppError(ErrorKind.PARSE, "No readable text was recognised in this image."))
        }
        return buildResult(text, documentId, OcrMethod.ML_KIT_OCR, pageCount = 1, warnings = emptyList())
    }

    private fun processPdf(file: File, documentId: String): AppResult<ExtractionResult> {
        // Step 1: selectable text extraction. Never send a PDF to the image-only ML Kit API.
        val selectable = PdfTextExtractor.extract(file)
        if (selectable != null && selectable.text.isNotBlank()) {
            return buildResult(
                text = selectable.text,
                documentId = documentId,
                method = OcrMethod.PDF_TEXT_EXTRACTION,
                pageCount = selectable.pageCount,
                warnings = selectable.warnings,
            )
        }

        // Step 2: image-only scan. Render each page locally at a readable resolution, then OCR.
        if (context == null) {
            return AppResult.Failure(AppError(ErrorKind.VALIDATION, "PDF OCR requires an Android runtime."))
        }
        return processScannedPdf(file, documentId)
    }

    private fun processScannedPdf(file: File, documentId: String): AppResult<ExtractionResult> {
        val descriptor = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull() ?: return AppResult.Failure(AppError(ErrorKind.PARSE, "The PDF could not be opened."))

        val builder = StringBuilder()
        var pages = 0
        try {
            val renderer = runCatching { PdfRenderer(descriptor) }.getOrNull()
                ?: return AppResult.Failure(AppError(ErrorKind.PARSE, "The PDF could not be parsed."))
            try {
                pages = renderer.pageCount
                val limit = minOf(pages, MAX_PDF_PAGES)
                for (index in 0 until limit) {
                    renderer.openPage(index).use { page ->
                        val scale = if (page.width > 0) (TARGET_RENDER_WIDTH / page.width.toFloat()).coerceIn(1f, 4f) else 2f
                        val width = (page.width * scale).toInt().coerceAtLeast(1)
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = try {
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        } catch (t: Throwable) {
                            null
                        } ?: continue
                        try {
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val pageText = recognizeBitmap(bitmap)
                            if (pageText.isNotBlank()) {
                                builder.append("\n").append(pageText)
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            } finally {
                runCatching { renderer.close() }
            }
        } finally {
            runCatching { descriptor.close() }
        }

        if (builder.isBlank()) {
            return AppResult.Failure(AppError(ErrorKind.PARSE, "No selectable text or readable page content was found in this PDF."))
        }

        val warnings = buildList {
            if (pages > MAX_PDF_PAGES) add("Only the first $MAX_PDF_PAGES of $pages pages were processed.")
            add("No selectable text was found; pages were rendered locally and read with ML Kit.")
        }

        return buildResult(
            text = builder.toString(),
            documentId = documentId,
            method = OcrMethod.ML_KIT_OCR,
            pageCount = pages,
            warnings = warnings,
        )
    }

    private fun buildResult(
        text: String,
        documentId: String,
        method: OcrMethod,
        pageCount: Int?,
        warnings: List<String>,
    ): AppResult<ExtractionResult> {
        val fields = DocumentFieldParser.parse(documentId, text, method).map {
            it.copy(sourceDocumentId = documentId, method = method)
        }
        val now = System.currentTimeMillis()
        val averageConfidence = fields.mapNotNull { it.confidence }.takeIf { it.isNotEmpty() }?.average()?.toFloat()

        val resultWarnings = warnings.toMutableList()
        if (fields.isEmpty() || fields.all { it.value.isBlank() }) {
            resultWarnings += "Text was recognised but no structured fields were identified for this category."
        }

        return AppResult.Success(
            ExtractionResult(
                fields = fields,
                processedAt = now,
                provider = method.label,
                overallConfidence = averageConfidence,
                method = method,
                rawText = text,
                pageCount = pageCount,
                warnings = resultWarnings,
            ),
        )
    }

    private fun recognizeBitmap(bitmap: Bitmap): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val task = recognizer.process(image)
            Tasks.await(task, ML_KIT_TIMEOUT_SECONDS, TimeUnit.SECONDS).text
        } finally {
            runCatching { recognizer.close() }
        }
    }

    private fun detect(file: File): FileKind {
        val header = ByteArray(8)
        val read = runCatching {
            file.inputStream().use { it.read(header) }
        }.getOrDefault(0)
        if (read >= 4) {
            if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()) return FileKind.IMAGE
            if (header[0] == 0x89.toByte() && header[1] == 0x50.toByte() && header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()) return FileKind.IMAGE
            if (header[0] == 0x25.toByte() && header[1] == 0x50.toByte() && header[2] == 0x44.toByte() && header[3] == 0x46.toByte()) return FileKind.PDF
        }
        return when (file.extension.lowercase()) {
            "txt" -> FileKind.TEXT
            "pdf" -> FileKind.PDF
            "jpg", "jpeg", "png", "webp" -> FileKind.IMAGE
            else -> FileKind.UNKNOWN
        }
    }

    companion object {
        private const val MAX_PDF_PAGES = 5
        private const val ML_KIT_TIMEOUT_SECONDS = 60L

        /** Pages are rendered wide enough for ML Kit to read small body text reliably. */
        private const val TARGET_RENDER_WIDTH = 1700f
    }
}
