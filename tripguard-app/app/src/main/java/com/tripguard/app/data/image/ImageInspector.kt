package com.tripguard.app.data.image

import android.graphics.BitmapFactory
import com.tripguard.app.domain.model.PhotoMeasurements
import java.io.File

/**
 * Reads real image dimensions without decoding the full bitmap into memory (bounds-only decode).
 * The original file is never modified. If the file cannot be decoded, [decodable] is false and no
 * measurements are invented.
 */
class ImageInspector {

    fun measure(file: File): PhotoMeasurements {
        val extension = file.extension.lowercase().takeIf { it.isNotBlank() }
        if (!file.exists() || file.length() == 0L) {
            return PhotoMeasurements(
                widthPx = 0,
                heightPx = 0,
                sizeBytes = 0L,
                decodable = false,
                mimeType = null,
                extension = extension,
            )
        }

        // The platform image codec is not always available (for example in a plain JVM test
        // environment). Any failure to decode yields decodable = false rather than invented
        // measurements or a crash.
        val decoded = runCatching {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            Triple(options.outWidth, options.outHeight, options.outMimeType)
        }.getOrNull()

        val width = decoded?.first ?: 0
        val height = decoded?.second ?: 0
        val mime = decoded?.third
        val decodable = width > 0 && height > 0

        return PhotoMeasurements(
            widthPx = if (decodable) width else 0,
            heightPx = if (decodable) height else 0,
            sizeBytes = file.length(),
            decodable = decodable,
            mimeType = mime,
            extension = extension ?: mime?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
        )
    }
}
