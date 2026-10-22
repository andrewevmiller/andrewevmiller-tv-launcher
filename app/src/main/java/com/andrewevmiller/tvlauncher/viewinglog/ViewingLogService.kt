package com.andrewevmiller.tvlauncher.viewinglog

import android.content.Context
import com.andrewevmiller.tvlauncher.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The Android side of the viewing log: runs [UsageSync] when asked (Home resumes, the log opens), serves the stored
 * sessions to the screen, and clears them. Nothing is read or written while the setting is off, and a sync needs
 * usage access too. Syncs and clears are serialised so two overlapping resumes cannot double-read the log.
 */
class ViewingLogService(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val store: ViewingLogStore = ViewingLogStore(context, clock),
    private val feed: UsageEventFeed = UsageEventsSource(context),
) : ViewingLog {
    private val state = MutableStateFlow<List<ViewingSession>>(emptyList())
    override val sessions: StateFlow<List<ViewingSession>> = state.asStateFlow()
    private val lock = Mutex()

    /** True when the system lets this app read usage. The screen says how to grant it when it does not. */
    fun hasUsageAccess(): Boolean = usageAccessGranted(context)

    /**
     * Copies new usage into the log (when on and granted), then publishes what is stored. Off the main thread.
     * Called when Home resumes and when the Viewing log opens.
     */
    fun refresh() {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                if (!settings.settings.first().viewingLogEnabled) return@withLock
                if (usageAccessGranted(context)) UsageSync(feed, store).run(clock())
                state.value = store.read()
            }
        }
    }

    /** Inside the lock, so a sync that was already reading cannot publish the old sessions after the clear. */
    override fun clear() {
        scope.launch(Dispatchers.IO) {
            lock.withLock {
                store.write(emptyList())
                state.value = emptyList()
            }
        }
    }
}
