package com.andrewevmiller.tvlauncher.home.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withContext

private const val TAG = "PackageAppSource"

/** Quiet period after the last package broadcast before the app list is queried again. */
private const val DEBOUNCE_MS = 300L

/**
 * One launchable activity as reported by PackageManager, before layout and artwork are applied.
 * Equality ignores [activityInfo] (which has no value equality), so re-querying an unchanged system
 * yields equal lists and `distinctUntilChanged` can drop the repeat.
 */
class RawApp(
    val component: ComponentName,
    val label: String,
    /** `ApplicationInfo.category`; -1 when undefined. */
    val androidCategory: Int,
    /** True when the entry came from a LEANBACK_LAUNCHER activity, false for a plain LAUNCHER one. */
    val isLeanback: Boolean,
    /** `PackageInfo.lastUpdateTime`, part of the artwork cache key so updated apps reload their art. */
    val lastUpdateTime: Long,
    /** Kept so [ArtworkLoader] can call `loadBanner` / `loadIcon` without another PackageManager lookup. */
    val activityInfo: ActivityInfo,
) {
    /** `ComponentName.flattenToString()`, the key used by `FieldLayout`. */
    val key: String = component.flattenToString()

    val category: AppCategory = categorize(component.packageName, androidCategory)

    override fun equals(other: Any?): Boolean =
        other is RawApp &&
            component == other.component &&
            label == other.label &&
            androidCategory == other.androidCategory &&
            isLeanback == other.isLeanback &&
            lastUpdateTime == other.lastUpdateTime

    override fun hashCode(): Int {
        var result = component.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + androidCategory
        result = 31 * result + isLeanback.hashCode()
        result = 31 * result + lastUpdateTime.hashCode()
        return result
    }

    override fun toString(): String = "RawApp($key, \"$label\", leanback=$isLeanback)"
}

/**
 * Finds the apps the launcher can show. Queries both `CATEGORY_LEANBACK_LAUNCHER` and `CATEGORY_LAUNCHER`
 * activities, keeps one entry per package (the leanback activity when an app has both), and leaves out
 * this app itself. [apps] re-queries whenever a package is added, removed, changed or replaced.
 *
 * Needs `QUERY_ALL_PACKAGES` (declared in the manifest) to see other apps on API 30+.
 */
class PackageAppSource(context: Context) {
    private val context: Context = context.applicationContext
    private val pm: PackageManager = this.context.packageManager

    /**
     * The current list immediately, then a fresh list after each burst of package changes (debounced, only
     * emitted when it differs from the previous one). Collecting registers a receiver, cancelling unregisters it.
     */
    val apps: Flow<List<RawApp>> = packageChanges()
        .mapNotNull {
            // The query is blocking PackageManager work, so keep it off the collector's thread. The try/catch is
            // inside withContext so it can never swallow a CancellationException.
            withContext(Dispatchers.IO) {
                try {
                    queryAll()
                } catch (e: Exception) {
                    Log.w(TAG, "App query failed; keeping the previous list", e)
                    null
                }
            }
        }
        .distinctUntilChanged()

    /** Blocking. One entry per package, our own package excluded, in no particular order. */
    fun queryAll(): List<RawApp> {
        val updateTimes = HashMap<String, Long>()
        fun updated(pkg: String): Long = updateTimes.getOrPut(pkg) { lastUpdateTime(pkg) }

        val own = context.packageName
        val entries = ArrayList<RawApp>()
        for (info in resolve(Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            val app = toRawApp(info, true) { updated(it) }
            if (app != null) entries.add(app)
        }
        for (info in resolve(Intent.CATEGORY_LAUNCHER)) {
            val app = toRawApp(info, false) { updated(it) }
            if (app != null) entries.add(app)
        }
        return preferLeanbackPerPackage(
            items = entries.filter { it.component.packageName != own },
            packageOf = { it.component.packageName },
            isLeanback = { it.isLeanback },
        )
    }

    private fun toRawApp(info: ResolveInfo, isLeanback: Boolean, updated: (String) -> Long): RawApp? {
        val activity = info.activityInfo ?: return null
        val component = ComponentName(activity.packageName, activity.name)
        val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { activity.packageName }
        return RawApp(
            component = component,
            label = label,
            androidCategory = activity.applicationInfo?.category ?: -1,
            isLeanback = isLeanback,
            lastUpdateTime = updated(activity.packageName),
            activityInfo = activity,
        )
    }

    @Suppress("DEPRECATION")
    private fun resolve(category: String): List<ResolveInfo> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            pm.queryIntentActivities(intent, 0)
        }
    }

    @Suppress("DEPRECATION")
    private fun lastUpdateTime(packageName: String): Long = try {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            pm.getPackageInfo(packageName, 0)
        }
        info.lastUpdateTime
    } catch (e: PackageManager.NameNotFoundException) {
        0L
    }

    /**
     * Emits once on collection (after the receiver is registered, so nothing is missed in between), then once
     * [DEBOUNCE_MS] after the last package broadcast of each burst.
     */
    private fun packageChanges(): Flow<Unit> = callbackFlow<Unit> {
        val handler = Handler(Looper.getMainLooper())
        val emit = Runnable { trySend(Unit) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                handler.removeCallbacks(emit)
                handler.postDelayed(emit, DEBOUNCE_MS)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // System broadcasts only, so no RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED flag is needed.
        context.registerReceiver(receiver, filter)
        trySend(Unit)
        awaitClose {
            handler.removeCallbacks(emit)
            context.unregisterReceiver(receiver)
        }
    }.conflate()
}
