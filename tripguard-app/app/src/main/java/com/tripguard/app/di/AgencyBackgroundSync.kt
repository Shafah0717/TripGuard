package com.tripguard.app.di

import com.tripguard.app.domain.repository.AgencyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls the shared Agency API while the app process is alive so a slot
 * published by the agency is noticed even before the traveller opens the
 * tracker. Only the (empty) inquiry/booking refs are retained, so offline
 * restarts re-sync cleanly. Runs quietly: backend or network failures are
 * ignored and the last known state is simply kept.
 */
class AgencyBackgroundSync(
    private val agency: AgencyRepository,
    private val scope: CoroutineScope,
) {
    private val intervalMillis = 8_000L

    fun start() {
        scope.launch {
            while (isActive) {
                delay(intervalMillis)
                if (agency.localState.value.inquiry != null) {
                    agency.syncAgency()
                }
            }
        }
    }
}