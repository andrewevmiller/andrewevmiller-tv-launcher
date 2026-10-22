package com.andrewevmiller.tvlauncher.safety

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings
import android.util.Log

/** What [HomeFallback.handOffHome] did. Never thrown; always returned. */
sealed class FallbackResult {
    /**
     * Our HOME alias is now disabled and the previous home was started (or the start was attempted;
     * see [homeStarted]). [target] is the flattened component that got Home.
     */
    data class HandedOff(val target: String, val homeStarted: Boolean) : FallbackResult()

    /** No other enabled HOME activity exists, so nothing was touched: the alias stays enabled. */
    data class Kept(val reason: String) : FallbackResult()

    /**
     * The alias could not be disabled (or the lookup threw), so it is still enabled and no notice
     * record is left behind. If a target had been chosen, starting it was still attempted.
     */
    data class Failed(val reason: String) : FallbackResult()
}

/**
 * Hands Home back to the previous launcher by disabling our own HOME alias.
 *
 * Runs inside the uncaught-exception handler, so it is synchronous, touches nothing but
 * SharedPreferences and PackageManager, and swallows every Throwable.
 *
 * Hard rule: the alias is only disabled when another enabled HOME activity exists. The TV is never
 * left with no HOME.
 */
object HomeFallback {
    private const val TAG = "HomeFallback"

    /** Secure setting that deploy.sh fills with the flattened previous-home component. */
    const val PREVIOUS_HOME_SETTING = "field_launcher_previous_home"

    /** SharedPreferences file shared by [HomeFallback] and [CrashGuard]. */
    const val PREFS_NAME = "field_safety"
    const val KEY_FALLBACK_TARGET = "fallback_target"
    const val KEY_FALLBACK_AT = "fallback_at"

    /** The `<activity-alias>` that carries CATEGORY_HOME in the manifest (`.HomeAlias`). */
    const val HOME_ALIAS_CLASS = "com.andrewevmiller.tvlauncher.HomeAlias"

    fun handOffHome(context: Context): FallbackResult {
        return try {
            doHandOff(context)
        } catch (t: Throwable) {
            Log.e(TAG, "hand-off failed unexpectedly", t)
            FallbackResult.Failed("unexpected ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun doHandOff(context: Context): FallbackResult {
        // 0. Decide first. If there is nowhere to go, change nothing at all.
        val chosen: HomeComponent? = try {
            HomeTargetChooser.choose(
                recordedPreviousHome = readPreviousHome(context),
                candidates = queryHomeCandidates(context),
                ownPackage = context.packageName,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "could not look up HOME activities", t)
            return FallbackResult.Failed("lookup failed: ${t.javaClass.simpleName}: ${t.message}")
        }
        if (chosen == null) {
            Log.w(TAG, "no other enabled HOME activity; keeping our alias enabled")
            return FallbackResult.Kept("no other enabled HOME activity")
        }

        // 1. Persist the record first, so the notice survives even if the next steps kill the process.
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            prefs.edit()
                .putString(KEY_FALLBACK_TARGET, chosen.flatten())
                .putLong(KEY_FALLBACK_AT, System.currentTimeMillis())
                .commit()
        } catch (t: Throwable) {
            // Losing the notice is better than losing the hand-off.
            Log.w(TAG, "could not persist the fallback record", t)
        }

        // 2. Stop being a HOME.
        val aliasError: Throwable? = try {
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context.packageName, HOME_ALIAS_CLASS),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            null
        } catch (t: Throwable) {
            Log.e(TAG, "could not disable $HOME_ALIAS_CLASS", t)
            t
        }
        if (aliasError != null) {
            // The alias is still our HOME, so the notice would be untrue. Drop the record.
            try {
                prefs.edit().remove(KEY_FALLBACK_TARGET).remove(KEY_FALLBACK_AT).commit()
            } catch (t: Throwable) {
                Log.w(TAG, "could not clear the fallback record", t)
            }
        }

        // 3. Start the previous home. Done even if step 2 failed: a working screen beats a crash dialog.
        val started = try {
            context.startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(ComponentName(chosen.packageName, chosen.className))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not start ${chosen.flatten()}", t)
            false
        }

        return if (aliasError == null) {
            FallbackResult.HandedOff(chosen.flatten(), started)
        } else {
            FallbackResult.Failed("could not disable the HOME alias: ${aliasError.javaClass.simpleName}: ${aliasError.message}")
        }
    }

    private fun readPreviousHome(context: Context): String? {
        return try {
            Settings.Secure.getString(context.contentResolver, PREVIOUS_HOME_SETTING)
        } catch (t: Throwable) {
            Log.w(TAG, "could not read $PREVIOUS_HOME_SETTING", t)
            null
        }
    }

    /**
     * Enabled HOME activities, in PackageManager's order, default-category ones first. The query
     * only returns activities whose package and component are installed and enabled; the explicit
     * `applicationInfo.enabled` check is belt and braces.
     */
    @Suppress("DEPRECATION")
    private fun queryHomeCandidates(context: Context): List<HomeCandidate> {
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val withDefault = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        val all = pm.queryIntentActivities(homeIntent, 0)

        val seen = HashSet<HomeComponent>()
        val result = ArrayList<HomeCandidate>()
        for (info in withDefault) {
            val component = toEnabledComponent(info) ?: continue
            if (seen.add(component)) result.add(HomeCandidate(component, declaresDefaultCategory = true))
        }
        for (info in all) {
            val component = toEnabledComponent(info) ?: continue
            if (seen.add(component)) result.add(HomeCandidate(component, declaresDefaultCategory = false))
        }
        return result
    }

    private fun toEnabledComponent(info: ResolveInfo): HomeComponent? {
        val activity = info.activityInfo ?: return null
        val app = activity.applicationInfo
        if (app != null && !app.enabled) return null
        return HomeComponent(activity.packageName, activity.name)
    }
}
