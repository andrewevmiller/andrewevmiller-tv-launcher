package com.andrewevmiller.spike

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.dreams.DreamService

/** One activity of the dream package, as reported by PackageManager. */
data class DreamActivity(
    val name: String,
    val exported: Boolean,
    val enabled: Boolean,
    val permission: String?,
)

/**
 * E2: can we start the active screensaver on demand?
 *
 * Public API only: explicit/implicit activity intents, PackageManager queries and Settings.Secure.
 * No reflection on hidden APIs. Every function reports through [emit] and returns plain strings so the
 * caller can keep the summary table. Takes any Context, not just an Activity: E3 reuses [intentSomnambulator] and
 * [fire] from a BroadcastReceiver, which is why every intent here already carries FLAG_ACTIVITY_NEW_TASK.
 */
class DreamProbe(private val context: Context, private val emit: (String) -> Unit) {

    private val pm: PackageManager get() = context.packageManager

    private val prefs by lazy { context.getSharedPreferences(Spike.PREFS, Context.MODE_PRIVATE) }

    // ---- state ----------------------------------------------------------------------------------

    fun hasWriteSecure(): Boolean =
        context.checkSelfPermission(Spike.PERM_WRITE_SECURE) == PackageManager.PERMISSION_GRANTED

    private fun readSetting(name: String): String? {
        return try {
            Settings.Secure.getString(context.contentResolver, name)
        } catch (e: Throwable) {
            "(read failed: " + e.javaClass.simpleName + ": " + e.message + ")"
        }
    }

    fun currentScreensaver(): String = readSetting(Spike.SETTING_SCREENSAVER) ?: "(null)"

    fun savedPrevious(): String =
        if (prefs.getBoolean(Spike.PREF_PREV_SAVED, false)) {
            prefs.getString(Spike.PREF_PREV_VALUE, Spike.NULL_SENTINEL) ?: Spike.NULL_SENTINEL
        } else {
            "(nothing saved yet)"
        }

    /** E2.0: settings, permission and the components every strategy depends on. */
    fun info() {
        emit("--- E2.0 screensaver and system state")
        val keys = listOf(
            "screensaver_enabled",
            Spike.SETTING_SCREENSAVER,
            "screensaver_default_component",
            "screensaver_activate_on_sleep",
            "screensaver_activate_on_dock",
        )
        for (key in keys) emit("  secure." + key + " = " + readSetting(key))
        emit("  WRITE_SECURE_SETTINGS granted = " + hasWriteSecure())
        emit("  saved previous value = " + savedPrevious())
        emit("  Somnambulator:")
        describeActivity(ComponentName(Spike.SYSTEMUI_PACKAGE, Spike.SOMNAMBULATOR_CLASS))
        emit("  Active screensaver service(s):")
        val active = readSetting(Spike.SETTING_SCREENSAVER)
        if (active.isNullOrEmpty()) {
            emit("    (setting is empty)")
        } else {
            for (part in active.split(',')) {
                val cn = ComponentName.unflattenFromString(part.trim())
                if (cn == null) emit("    cannot parse '" + part + "'") else describeService(cn)
            }
        }
        emit("  Dream package " + Spike.DREAM_PACKAGE + ":")
        describePackage()
    }

    @Suppress("DEPRECATION")
    private fun describeActivity(cn: ComponentName) {
        try {
            val ai = pm.getActivityInfo(cn, PackageManager.MATCH_DISABLED_COMPONENTS)
            val setting = pm.getComponentEnabledSetting(cn)
            emit("    " + cn.flattenToShortString() + ": found, exported=" + ai.exported +
                " enabled=" + ai.enabled + " enabledSetting=" + setting + " permission=" + ai.permission)
        } catch (e: PackageManager.NameNotFoundException) {
            emit("    " + cn.flattenToShortString() + ": NOT FOUND")
        } catch (e: Throwable) {
            emit("    " + cn.flattenToShortString() + ": " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    @Suppress("DEPRECATION")
    private fun describeService(cn: ComponentName) {
        try {
            val si = pm.getServiceInfo(cn, PackageManager.MATCH_DISABLED_COMPONENTS)
            emit("    " + cn.flattenToShortString() + ": found, exported=" + si.exported +
                " enabled=" + si.enabled + " permission=" + si.permission)
        } catch (e: PackageManager.NameNotFoundException) {
            emit("    " + cn.flattenToShortString() + ": NOT FOUND")
        } catch (e: Throwable) {
            emit("    " + cn.flattenToShortString() + ": " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    @Suppress("DEPRECATION")
    private fun describePackage() {
        try {
            val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.MATCH_DISABLED_COMPONENTS
            val pi = pm.getPackageInfo(Spike.DREAM_PACKAGE, flags)
            emit("    installed, versionName=" + pi.versionName + " applicationEnabled=" + pi.applicationInfo?.enabled)
            val activities = pi.activities
            emit("    activities: " + (activities?.size ?: 0))
            if (activities != null) {
                for (a in activities) {
                    emit("      " + shortName(a.name) + " exported=" + a.exported + " enabled=" + a.enabled +
                        " permission=" + a.permission)
                }
            }
            val services = pi.services
            emit("    services: " + (services?.size ?: 0))
            if (services != null) {
                for (s in services) {
                    emit("      " + shortName(s.name) + " exported=" + s.exported + " enabled=" + s.enabled +
                        " permission=" + s.permission)
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            emit("    NOT INSTALLED (or hidden from us)")
        } catch (e: Throwable) {
            emit("    " + e.javaClass.simpleName + ": " + e.message)
        }
    }

    private fun shortName(name: String): String = ReportFormat.shortClass(name, Spike.DREAM_PACKAGE)

    /** Exported, enabled activities of the dream package; one button each in the UI. */
    @Suppress("DEPRECATION")
    fun dreamPackageActivities(): List<DreamActivity> {
        return try {
            val pi = pm.getPackageInfo(
                Spike.DREAM_PACKAGE,
                PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            val activities = pi.activities ?: return emptyList()
            activities.map { DreamActivity(it.name, it.exported, it.enabled, it.permission) }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    // ---- E2.1 sweep -----------------------------------------------------------------------------

    /** E2.1: read-only list of everything that could be a way in. */
    @Suppress("DEPRECATION")
    fun sweep() {
        emit("--- E2.1 sweep: what resolves")
        val services = pm.queryIntentServices(Intent(DreamService.SERVICE_INTERFACE), 0)
        emit("  installed DreamService implementations: " + services.size)
        for (ri in services) {
            val si = ri.serviceInfo
            emit("    " + si.packageName + "/" + si.name + " exported=" + si.exported + " permission=" + si.permission)
        }
        resolveReport("MAIN + category DESK_DOCK", Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_DESK_DOCK))
        resolveReport("ACTION_DREAM_SETTINGS", Intent(Settings.ACTION_DREAM_SETTINGS))
        resolveReport("MAIN + category LAUNCHER, pkg systemui",
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(Spike.SYSTEMUI_PACKAGE))
        emit("  Somnambulator:")
        describeActivity(ComponentName(Spike.SYSTEMUI_PACKAGE, Spike.SOMNAMBULATOR_CLASS))
    }

    @Suppress("DEPRECATION")
    private fun resolveReport(label: String, intent: Intent) {
        val list = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        emit("  " + label + ": " + list.size + " activity match(es)")
        for (ri in list) {
            val ai = ri.activityInfo
            emit("    " + ai.packageName + "/" + ai.name + " exported=" + ai.exported)
        }
    }

    // ---- start strategies -----------------------------------------------------------------------

    /** (a) Somnambulator, explicit component, ACTION_MAIN, NEW_TASK. */
    fun intentSomnambulator(): Intent =
        Intent(Intent.ACTION_MAIN)
            .setClassName(Spike.SYSTEMUI_PACKAGE, Spike.SOMNAMBULATOR_CLASS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** (b1) Same, with the DESK_DOCK category (Somnambulator then takes the "start when docked" path). */
    fun intentSomnambulatorDock(): Intent =
        intentSomnambulator().addCategory(Intent.CATEGORY_DESK_DOCK)

    /** (b2) Implicit MAIN + DESK_DOCK: whatever the system resolves for a dock. */
    fun intentImplicitDock(): Intent =
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_DESK_DOCK)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** (b3) The Daydream settings page (public action); on phones it has a "Start now" menu item. */
    fun intentDreamSettings(): Intent =
        Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** (c) One activity of the dream package, launched explicitly. */
    fun intentDreamActivity(className: String): Intent =
        Intent(Intent.ACTION_MAIN)
            .setClassName(Spike.DREAM_PACKAGE, className)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Fires [intent] and returns a one-line result starting with "OK" if startActivity() did not throw.
     * "OK" only means the call was accepted; whether a screensaver appeared is observed by the owner.
     */
    @Suppress("DEPRECATION")
    fun fire(intent: Intent): String {
        emit("    intent: " + intent)
        if (intent.component == null) {
            val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            if (resolved == null) return "NOT RESOLVABLE (no activity handles this intent)"
            emit("    resolves to: " + resolved.activityInfo.packageName + "/" + resolved.activityInfo.name)
        }
        return try {
            context.startActivity(intent)
            "OK (no exception)"
        } catch (e: ActivityNotFoundException) {
            "ActivityNotFoundException: " + e.message
        } catch (e: SecurityException) {
            "SecurityException: " + e.message
        } catch (e: Throwable) {
            e.javaClass.simpleName + ": " + e.message
        }
    }

    // ---- (d) change the active screensaver ------------------------------------------------------

    /** Remembers the current value (only the first time) so [restore] can put it back. */
    private fun savePreviousOnce() {
        if (prefs.getBoolean(Spike.PREF_PREV_SAVED, false)) return
        val current = readSetting(Spike.SETTING_SCREENSAVER)
        prefs.edit()
            .putString(Spike.PREF_PREV_VALUE, current ?: Spike.NULL_SENTINEL)
            .putBoolean(Spike.PREF_PREV_SAVED, true)
            .apply()
        emit("  saved previous screensaver_components = " + (current ?: Spike.NULL_SENTINEL))
    }

    /** (d1) Points screensaver_components at Google's Backdrop dream. */
    fun setToBackdrop(): String {
        if (!hasWriteSecure()) {
            return "WRITE_SECURE_SETTINGS not granted. Run: adb shell pm grant " +
                context.packageName + " " + Spike.PERM_WRITE_SECURE
        }
        savePreviousOnce()
        return write(Spike.BACKDROP)
    }

    /** (d2) Puts back what [setToBackdrop] found. */
    fun restore(): String {
        if (!hasWriteSecure()) {
            return "WRITE_SECURE_SETTINGS not granted. Run: adb shell pm grant " +
                context.packageName + " " + Spike.PERM_WRITE_SECURE
        }
        if (!prefs.getBoolean(Spike.PREF_PREV_SAVED, false)) return "nothing saved, nothing to restore"
        val stored = prefs.getString(Spike.PREF_PREV_VALUE, Spike.NULL_SENTINEL) ?: Spike.NULL_SENTINEL
        val result: String
        if (stored == Spike.NULL_SENTINEL) {
            // The public API cannot delete a setting, so write an empty value. For an exact restore run:
            // adb shell settings delete secure screensaver_components
            result = write("") + " (previous value was null; an empty string was written. For an exact" +
                " restore: adb shell settings delete secure " + Spike.SETTING_SCREENSAVER + ")"
        } else {
            result = write(stored)
        }
        if (result.startsWith("OK")) {
            prefs.edit().remove(Spike.PREF_PREV_VALUE).putBoolean(Spike.PREF_PREV_SAVED, false).apply()
        }
        return result
    }

    private fun write(value: String): String {
        return try {
            val ok = Settings.Secure.putString(context.contentResolver, Spike.SETTING_SCREENSAVER, value)
            val back = readSetting(Spike.SETTING_SCREENSAVER)
            if (ok && back == value) {
                "OK (screensaver_components now '" + back + "')"
            } else {
                "putString returned " + ok + ", read back '" + back + "'"
            }
        } catch (e: SecurityException) {
            "SecurityException: " + e.message
        } catch (e: Throwable) {
            e.javaClass.simpleName + ": " + e.message
        }
    }
}
