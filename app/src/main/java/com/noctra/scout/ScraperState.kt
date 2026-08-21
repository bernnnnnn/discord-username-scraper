package com.noctra.scout

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Stats(
    val running: Boolean = false,
    val checked: Long = 0L,
    val available: Long = 0L,
    val current: String = "",
    val status: String = "Idle",
    val cursor: Long = 0L,
    val total: Long = 0L,
    /** Average checks per minute across the whole run, stalls included. */
    val ratePerMin: Double = 0.0,
    /** The delay currently in use, which auto-pacing may have raised above the setting. */
    val paceMs: Int = 0,
    /** How many times Discord has rate limited this run. */
    val rateLimits: Int = 0,
    /** Which availability endpoint answered, once the client has locked onto one. */
    val endpoint: String = ""
)

/**
 * Single source of truth shared between the service and the UI.
 * The UI only ever reads it, so there is no binder plumbing to keep alive.
 */
object ScraperState {

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    /** Emitted after each database flush so the visible list can refresh. */
    val dataChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emitted with the name whenever a free username is found. */
    val found = MutableSharedFlow<String>(extraBufferCapacity = 16)

    fun update(block: (Stats) -> Stats) {
        _stats.value = block(_stats.value)
    }

    fun notifyDataChanged() {
        dataChanged.tryEmit(Unit)
    }

    fun notifyFound(name: String) {
        found.tryEmit(name)
    }
}
