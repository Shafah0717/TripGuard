package com.tripguard.app.data.dataset

import android.content.Context
import com.tripguard.app.domain.model.AppError
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.ErrorKind
import com.tripguard.app.domain.model.VisaCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Loads the bundled versioned dataset from `assets`. This is the offline source of truth for
 * visa categories and checklists so the app is fully usable without the backend.
 */
class AssetDatasetSource(
    private val context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    },
) {
    suspend fun load(): AppResult<VisaCatalog> = withContext(Dispatchers.IO) {
        try {
            val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            parse(text)
        } catch (t: Throwable) {
            AppResult.Failure(
                AppError(ErrorKind.PARSE, "Could not read the bundled visa requirements.", t),
            )
        }
    }

    fun parse(text: String): AppResult<VisaCatalog> = try {
        if (text.isBlank()) {
            AppResult.Failure(AppError(ErrorKind.PARSE, "Visa requirements dataset is empty."))
        } else {
            val dto = json.decodeFromString(DatasetDto.serializer(), text)
            if (dto.datasetId.isBlank() || dto.visaTypes.isEmpty()) {
                AppResult.Failure(AppError(ErrorKind.PARSE, "Invalid or empty visa requirements dataset."))
            } else {
                AppResult.Success(DatasetMapper.toDomain(dto))
            }
        }
    } catch (t: Throwable) {
        AppResult.Failure(AppError(ErrorKind.PARSE, "Could not parse visa requirements.", t))
    }

    companion object {
        const val ASSET_NAME = "tripguard_uae_visa_requirements_indian_passports_v1.0.1.json"
        const val SCHEMA_VERSION = "1.0.1"
    }
}
