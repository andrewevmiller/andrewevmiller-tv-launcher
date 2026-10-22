package com.andrewevmiller.tvlauncher.viewinglog

/** The usage events the launcher turns into sessions. Android's `UsageEvents` types map onto these in the glue. */
enum class UsageKind { FOREGROUND, BACKGROUND, SCREEN_ON, SCREEN_OFF }

/** One raw usage event (epoch millis). Screen events carry an empty [packageName]. */
data class UsageEvent(val atMillis: Long, val packageName: String, val kind: UsageKind)

/** One stretch of an app in front: [startMillis] inclusive, [endMillis] exclusive. */
data class ViewingSession(val packageName: String, val startMillis: Long, val endMillis: Long) {
    val durationMillis: Long get() = endMillis - startMillis
}

/**
 * Every threshold the viewing log uses, in one place. Each one is pinned by a test in SessionBuilderTest or
 * ViewingLogCodecTest.
 */
object ViewingLogRules {
    /** This launcher. The Oxyfield dream runs in this package too, so it is ignored as well. */
    const val LAUNCHER_PACKAGE = "com.andrewevmiller.tvlauncher"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /** Google TV's ambient mode (the Google Photos slideshow), which the launcher can start as the screensaver. */
    const val GOOGLE_SCREENSAVER_PACKAGE = "com.google.android.apps.tv.dreamx"

    val IGNORED_PACKAGES: Set<String> = setOf(LAUNCHER_PACKAGE, SYSTEM_UI_PACKAGE, GOOGLE_SCREENSAVER_PACKAGE)

    /** How long the log keeps a session. Older sessions are pruned when the log is read or written. */
    const val RETENTION_DAYS = 90
    const val RETENTION_MILLIS = RETENTION_DAYS * 24L * 60 * 60 * 1000

    /** Shorter stretches (a launch that was immediately left) are not recorded. */
    const val MIN_SESSION_MILLIS = 60_000L

    /** Two sessions of one app with a gap shorter than this are one session. */
    const val JOIN_GAP_MILLIS = 120_000L

    /** One session never runs longer than this, so a paused video left on cannot run for a day. */
    const val MAX_SESSION_MILLIS = 8L * 60 * 60 * 1000

    /** How far back the first sync reads, which is about all the system keeps. */
    const val FIRST_SYNC_LOOKBACK_MILLIS = 7L * 24 * 60 * 60 * 1000

    fun isIgnored(packageName: String): Boolean = packageName in IGNORED_PACKAGES
}
