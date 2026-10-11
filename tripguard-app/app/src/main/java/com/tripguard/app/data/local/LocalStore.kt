package com.tripguard.app.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Small atomic JSON file store backing the whole app state. Reads are performed once at
 * startup; writes are serialised through a mutex so concurrent taps can never corrupt the file.
 */
class LocalStore(
    context: Context,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    },
) {
    private val file = File(context.filesDir, "tripguard_state.json")
    private val mutex = Mutex()

    private val _state = MutableStateFlow(load())
    val state: StateFlow<PersistedState> = _state.asStateFlow()

    private fun load(): PersistedState = try {
        if (file.exists()) {
            json.decodeFromString(PersistedState.serializer(), file.readText())
        } else {
            PersistedState()
        }
    } catch (t: Throwable) {
        PersistedState()
    }

    /** Atomically transforms the state and persists the result. Returns the new state. */
    suspend fun update(transform: (PersistedState) -> PersistedState): PersistedState {
        return mutex.withLock {
            val next = transform(_state.value)
            _state.value = next
            persist(next)
            next
        }
    }

    private suspend fun persist(state: PersistedState) = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(PersistedState.serializer(), state))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}
