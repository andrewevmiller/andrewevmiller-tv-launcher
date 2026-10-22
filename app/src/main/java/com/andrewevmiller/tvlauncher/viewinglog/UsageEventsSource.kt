package com.andrewevmiller.tvlauncher.viewinglog

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Reads UsageStatsManager.queryEvents and maps each event to the pure [UsageKind]. Activity resume and pause are
 * the same values as the older MOVE_TO_FOREGROUND and MOVE_TO_BACKGROUND, so those are covered too.
 * Needs usage access (see [usageAccessGranted]); without it the system returns nothing.
 */
class UsageEventsSource(private val context: Context) : UsageEventFeed {

    override fun events(fromMillis: Long, toMillis: Long): List<UsageEvent> {
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val raw = runCatching { usage.queryEvents(fromMillis, toMillis) }.getOrNull() ?: return emptyList()
        val out = ArrayList<UsageEvent>()
        val event = UsageEvents.Event()
        while (raw.hasNextEvent()) {
            raw.getNextEvent(event)
            val kind = kindOf(event.eventType) ?: continue
            out.add(UsageEvent(event.timeStamp, event.packageName ?: "", kind))
        }
        return out
    }

    private fun kindOf(type: Int): UsageKind? = when (type) {
        UsageEvents.Event.ACTIVITY_RESUMED -> UsageKind.FOREGROUND
        UsageEvents.Event.ACTIVITY_PAUSED -> UsageKind.BACKGROUND
        UsageEvents.Event.SCREEN_INTERACTIVE -> UsageKind.SCREEN_ON
        UsageEvents.Event.SCREEN_NON_INTERACTIVE -> UsageKind.SCREEN_OFF
        else -> null
    }
}

/**
 * Whether this app holds usage access (the GET_USAGE_STATS appop). It is granted from the shell, once:
 * `adb shell appops set com.andrewevmiller.tvlauncher GET_USAGE_STATS allow` (deploy.sh does it).
 */
fun usageAccessGranted(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    val mode = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName,
    )
    return mode == AppOpsManager.MODE_ALLOWED
}
