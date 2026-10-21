package com.andrewevmiller.tvlauncher.home.data

import java.security.MessageDigest

/** One installed package's launcher-visible state: a change in any field can change its tile. */
data class AppStamp(val packageName: String, val lastUpdateTime: Long, val enabled: Boolean)

/** What the last scan left behind: the digest it was taken under, and when. */
data class ScanRecord(val signature: String, val at: Long)

/**
 * The decisions behind [AppLoader], free of Android types so they run as plain JVM unit tests.
 * The goal is to avoid re-reading every app's label and artwork when nothing has changed.
 */
object AppStamps {
    /** Rescan at least this often even when no stamp changed (covers a launcher alias toggled inside an app). */
    const val MAX_AGE_MS = 12L * 60 * 60 * 1000

    /**
     * Order-independent digest of every installed package's stamp plus the locale, since labels
     * depend on it. Equal digests mean the list of launchable apps and what it shows is unchanged.
     */
    fun signature(stamps: Collection<AppStamp>, locale: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(locale.toByteArray())
        for (s in stamps.sortedBy { it.packageName }) {
            digest.update("\n${s.packageName}|${s.lastUpdateTime}|${s.enabled}".toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** True when there is no earlier scan, the digest differs, or the earlier scan is too old (or in the future). */
    fun needsRescan(last: ScanRecord?, signature: String, now: Long): Boolean =
        last == null || last.signature != signature || now - last.at !in 0L..MAX_AGE_MS

    /**
     * A previous entry can stand in for a fresh one only when it describes the same activity of the
     * same build of the package. An unknown update time (0) is never trusted.
     */
    fun reusable(
        prev: SnapshotEntry?,
        packageName: String,
        className: String,
        pinned: Boolean,
        lastUpdateTime: Long,
    ): Boolean =
        prev != null && lastUpdateTime != 0L &&
            prev.packageName == packageName && prev.className == className &&
            prev.pinned == pinned && prev.lastUpdateTime == lastUpdateTime
}
