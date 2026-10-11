package com.tripguard.app.data.document

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tripguard.app.domain.model.AppError
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.DocumentPolicy
import com.tripguard.app.domain.model.ErrorKind
import com.tripguard.app.domain.model.FileValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class StagedFile(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String?,
)

/**
 * Manages picked documents: metadata resolution, policy validation and copying the file into
 * private app storage with genuine byte-level progress (no synthetic spinner).
 */
class DocumentFileStore(private val context: Context) {

    fun fileName(uri: Uri): String? = query(uri).name

    fun query(uriString: String): PickedFileInfo {
        if (uriString.startsWith("file:/") || uriString.startsWith("/")) {
            val cleanPath = uriString.removePrefix("file://").removePrefix("file:")
            val file = File(cleanPath)
            if (file.exists()) {
                val ext = file.extension.lowercase().takeIf { it.isNotBlank() }
                return PickedFileInfo(name = file.name, sizeBytes = file.length(), mimeType = null, extension = ext)
            }
        }
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return PickedFileInfo(null, 0L, null, null)
        return query(uri)
    }

    fun query(uri: Uri): PickedFileInfo {
        var name: String? = null
        var size = 0L
        var mime: String? = null
        runCatching {
            if (uri.scheme == "file" || uri.scheme == null) {
                val file = File(uri.path.orEmpty())
                if (file.exists()) {
                    name = file.name
                    size = file.length()
                }
            } else {
                context.contentResolver?.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex >= 0) name = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
                mime = runCatching { context.contentResolver?.getType(uri) }.getOrNull()
            }
        }
        val extension = name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotBlank() }
            ?: mime?.substringAfterLast('/', "")?.lowercase()
        return PickedFileInfo(name = name, sizeBytes = size, mimeType = mime, extension = extension)
    }

    fun validate(uriString: String, policy: DocumentPolicy): FileValidation {
        val info = query(uriString)
        if (!policy.isAcceptedExtension(info.extension)) {
            return FileValidation.Invalid(
                "Unsupported file type. Accepted formats: ${policy.acceptedFormatsLabel}.",
            )
        }
        if (info.sizeBytes > policy.maxFileSizeBytes) {
            return FileValidation.Invalid(
                "File is too large (${info.sizeBytes / (1024 * 1024)} MB). Maximum is ${policy.formattedMaxSize}.",
            )
        }
        return FileValidation.Valid
    }

    fun validate(uri: Uri, policy: DocumentPolicy): FileValidation = validate(uri.toString(), policy)

    suspend fun stage(
        sourceUri: String,
        applicationId: String,
        documentId: String,
        onProgress: (Float) -> Unit,
    ): AppResult<StagedFile> = withContext(Dispatchers.IO) {
        try {
            val info = query(sourceUri)
            val dir = File(context.filesDir, "documents/$applicationId").apply { mkdirs() }
            val extension = info.extension?.takeIf { it.isNotBlank() } ?: "dat"
            val safeDocId = documentId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val target = File(dir, "${safeDocId}_${System.currentTimeMillis()}.$extension")

            val total = info.sizeBytes.takeIf { it > 0 } ?: -1L
            val inputStream = if (sourceUri.startsWith("file:/") || sourceUri.startsWith("/")) {
                val cleanPath = sourceUri.removePrefix("file://").removePrefix("file:")
                File(cleanPath).inputStream()
            } else {
                val uri = runCatching { Uri.parse(sourceUri) }.getOrNull()
                uri?.let { context.contentResolver?.openInputStream(it) }
            }

            inputStream?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    var read = input.read(buffer)
                    while (read >= 0) {
                        output.write(buffer, 0, read)
                        copied += read
                        val progress = if (total > 0) (copied.toFloat() / total.toFloat()) else null
                        if (progress != null) onProgress(progress.coerceIn(0f, 1f))
                        read = input.read(buffer)
                    }
                }
            } ?: return@withContext AppResult.Failure(
                AppError(ErrorKind.UNKNOWN, "Could not open the selected file."),
            )

            onProgress(1f)
            AppResult.Success(
                StagedFile(
                    path = target.absolutePath,
                    fileName = info.name ?: target.name,
                    sizeBytes = target.length(),
                    mimeType = info.mimeType,
                ),
            )
        } catch (t: Throwable) {
            AppResult.Failure(AppError(ErrorKind.UNKNOWN, "Could not save the selected file.", t))
        }
    }

    suspend fun stage(
        uri: Uri,
        applicationId: String,
        documentId: String,
        onProgress: (Float) -> Unit,
    ): AppResult<StagedFile> = stage(uri.toString(), applicationId, documentId, onProgress)

    fun resolve(path: String?): File? = path?.let { File(it).takeIf { f -> f.exists() } }

    fun delete(path: String?) {
        path?.let { runCatching { File(it).delete() } }
    }

    fun deleteDirectory(applicationId: String) {
        runCatching { File(context.filesDir, "documents/$applicationId").deleteRecursively() }
    }

    data class PickedFileInfo(
        val name: String?,
        val sizeBytes: Long,
        val mimeType: String?,
        val extension: String?,
    )
}
