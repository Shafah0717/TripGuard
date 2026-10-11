package com.tripguard.app.di

import android.content.Context
import com.tripguard.app.BuildConfig
import com.tripguard.app.data.document.DocumentFileStore
import com.tripguard.app.data.image.ImageInspector
import com.tripguard.app.data.local.LocalStore
import com.tripguard.app.data.ocr.LocalOcrEngine
import com.tripguard.app.data.remote.TripGuardApi
import com.tripguard.app.data.repository.AgencyRepositoryImpl
import com.tripguard.app.data.repository.ApplicationRepositoryImpl
import com.tripguard.app.data.repository.SettingsRepositoryImpl
import com.tripguard.app.data.repository.VisaCatalogRepositoryImpl
import com.tripguard.app.data.dataset.AssetDatasetSource
import com.tripguard.app.domain.repository.ApplicationRepository
import com.tripguard.app.domain.repository.SettingsRepository
import com.tripguard.app.domain.repository.VisaCatalogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency graph. Small by design: one instance per process, created in
 * [com.tripguard.app.TripGuardApplication].
 */
class AppContainer(context: Context) {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val store = LocalStore(context)
    val fileStore = DocumentFileStore(context)

    private val defaultBaseUrl: String = BuildConfig.API_BASE_URL

    private var settingsRef: SettingsRepositoryImpl? = null

    val api: TripGuardApi = TripGuardApi(
        baseUrlProvider = { settingsRef?.baseUrl?.value ?: defaultBaseUrl },
    )

    val settings: SettingsRepository =
        SettingsRepositoryImpl(store, api, defaultBaseUrl, applicationScope).also { settingsRef = it }

    val catalog: VisaCatalogRepository =
        VisaCatalogRepositoryImpl(AssetDatasetSource(context), api)

    /** Agency-assistance flow (contact agency, slots, bookings). */
    val agency: com.tripguard.app.domain.repository.AgencyRepository =
        AgencyRepositoryImpl(api = api, store = store, baseUrlProvider = { settingsRef?.baseUrl?.value ?: defaultBaseUrl })

    val applications: ApplicationRepository = ApplicationRepositoryImpl(
        store = store,
        api = api,
        fileStore = fileStore,
        catalogProvider = { catalog.catalog.value },
        scope = applicationScope,
        ocrEngine = LocalOcrEngine(context),
        imageInspector = ImageInspector(),
        onApplicationDeleted = {
            // Best-effort: withdrawing clears local agency state immediately; the
            // server-side delete may be retried by the next sync if offline.
            runCatching { agency.withdrawInquiry() }
        },
    )

    /**
     * Lightweight foreground sync: while the app process is alive it periodically
     * pulls inquiry/slot/booking state from the backend and flags unseen updates,
     * so published times "arrive" whether or not the tracker page is open.
     */
    val agencySync: AgencyBackgroundSync =
        AgencyBackgroundSync(agency = agency, scope = applicationScope).also { it.start() }
}
