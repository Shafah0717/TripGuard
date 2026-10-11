package com.tripguard.app.data.repository

import com.tripguard.app.data.dataset.AssetDatasetSource
import com.tripguard.app.data.dataset.DatasetMapper
import com.tripguard.app.data.remote.TripGuardApi
import com.tripguard.app.domain.model.AppError
import com.tripguard.app.domain.model.AppResult
import com.tripguard.app.domain.model.ErrorKind
import com.tripguard.app.domain.model.VisaCatalog
import com.tripguard.app.domain.model.VisaType
import com.tripguard.app.domain.repository.VisaCatalogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The bundled versioned dataset is the offline source of truth. When the backend is reachable
 * and asked to refresh, the served dataset (same schema) replaces it; failures never break the
 * catalog the app already has.
 */
class VisaCatalogRepositoryImpl(
    private val assetSource: AssetDatasetSource,
    private val api: TripGuardApi,
) : VisaCatalogRepository {

    private val _catalog = MutableStateFlow<VisaCatalog?>(null)
    override val catalog: StateFlow<VisaCatalog?> = _catalog.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    override val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    override suspend fun load(forceRefresh: Boolean): AppResult<VisaCatalog> {
        _isLoading.value = true
        try {
            if (forceRefresh) {
                when (val remote = api.fetchDataset()) {
                    is AppResult.Success -> {
                        val mapped = DatasetMapper.toDomain(remote.data)
                        _catalog.value = mapped
                        return AppResult.Success(mapped)
                    }

                    is AppResult.Failure -> {
                        _catalog.value?.let { return AppResult.Success(it) }
                    }
                }
            }

            _catalog.value?.let { return AppResult.Success(it) }

            return when (val local = assetSource.load()) {
                is AppResult.Success -> {
                    _catalog.value = local.data
                    local
                }

                is AppResult.Failure -> local
            }
        } catch (t: Throwable) {
            return AppResult.Failure(AppError(ErrorKind.UNKNOWN, "Could not load visa requirements.", t))
        } finally {
            _isLoading.value = false
        }
    }

    override fun visaType(id: String): VisaType? =
        _catalog.value?.visaTypes?.firstOrNull { it.id == id }
}
