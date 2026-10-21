package com.andrewevmiller.tvlauncher.home.data

/** Wraps an [AppsRepository] so every launch is also written to the [LaunchHistory] (the Recent row and the feature band). */
class RecordingAppsRepository(
    private val delegate: AppsRepository,
    private val history: LaunchHistory,
    private val clock: () -> Long = System::currentTimeMillis,
) : AppsRepository by delegate {
    override fun launch(app: LauncherApp) {
        history.record(LaunchEntry(app.component.flattenToString(), app.label, app.category, clock()))
        delegate.launch(app)
    }
}
