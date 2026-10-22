package com.andrewevmiller.tvlauncher.viewinglog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the Viewing log screen and the Settings "Clear log" row need. The Android service implements it; tests fake it. */
interface ViewingLog {
    /** The stored sessions, oldest first. Empty until the first read. */
    val sessions: StateFlow<List<ViewingSession>>

    /** Forgets every stored session. The sync cursor is kept, so nothing is re-counted. */
    fun clear()
}

/** In-memory [ViewingLog] for previews and tests. */
class InMemoryViewingLog(initial: List<ViewingSession> = emptyList()) : ViewingLog {
    private val state = MutableStateFlow(initial.sortedBy { it.startMillis })
    override val sessions: StateFlow<List<ViewingSession>> = state
    override fun clear() {
        state.value = emptyList()
    }

    /** Test helper: replaces what the log holds, as a sync would. */
    fun replace(next: List<ViewingSession>) {
        state.value = next.sortedBy { it.startMillis }
    }
}
