package com.tripguard.app.data.remote

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.File

/** Wraps a file upload and reports byte-level progress so the UI never shows a fake spinner. */
class ProgressRequestBody(
    private val file: File,
    private val contentType: MediaType?,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {

    override fun contentType(): MediaType? = contentType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = file.length().coerceAtLeast(1L)
        var uploaded = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read = input.read(buffer)
            while (read >= 0) {
                sink.write(buffer, 0, read)
                uploaded += read
                onProgress((uploaded.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                read = input.read(buffer)
            }
        }
    }
}
