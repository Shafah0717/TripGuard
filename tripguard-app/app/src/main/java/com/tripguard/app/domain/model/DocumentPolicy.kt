package com.tripguard.app.domain.model

/**
 * File acceptance policy for document uploads. Defaults are bundled with the app and can be
 * overridden by the backend `/api/v1/config/document-policy` endpoint when available.
 */
data class DocumentPolicy(
    val acceptedExtensions: List<String> = listOf("pdf", "jpg", "jpeg", "png"),
    val maxFileSizeBytes: Long = 10L * 1024 * 1024,
    val source: String = "app_default",
) {
    val formattedMaxSize: String
        get() = when {
            maxFileSizeBytes >= 1024 * 1024 -> "${maxFileSizeBytes / (1024 * 1024)} MB"
            else -> "${maxFileSizeBytes / 1024} KB"
        }

    val acceptedFormatsLabel: String
        get() = acceptedExtensions.joinToString(", ") { it.uppercase() }

    fun isAcceptedExtension(extension: String?): Boolean {
        if (extension.isNullOrBlank()) return false
        return acceptedExtensions.any { it.equals(extension.trimStart('.'), ignoreCase = true) }
    }
}

/** Result of validating a chosen file against the policy before staging it. */
sealed interface FileValidation {
    data object Valid : FileValidation
    data class Invalid(val reason: String) : FileValidation
}
