package com.andrewevmiller.tvlauncher.ambient

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.andrewevmiller.tvlauncher.settings.Screensaver

/**
 * [ScreensaverController] backed by `Settings.Secure`. Writing needs `WRITE_SECURE_SETTINGS`
 * (`adb shell pm grant com.andrewevmiller.tvlauncher android.permission.WRITE_SECURE_SETTINGS`, done by
 * deploy.sh); reading does not.
 *
 * [provenFirst] names the start strategy that spike E2 found to work on the TV (see [StartStrategies]);
 * it is tried before the others. Everything tried is logged under tag [TAG].
 */
class SystemScreensaverController(
    context: Context,
    private val provenFirst: String? = null,
) : ScreensaverController {
    private val appContext: Context = context.applicationContext ?: context

    override val canSwitch: Boolean
        get() = appContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    override fun apply(choice: Screensaver) {
        if (!canSwitch) {
            Log.i(TAG, "apply($choice) ignored: WRITE_SECURE_SETTINGS is not granted")
            return
        }
        val value = ScreensaverComponents.settingValueFor(choice, appContext.packageName)
        try {
            val resolver = appContext.contentResolver
            val wrote = Settings.Secure.putString(resolver, ScreensaverComponents.SETTING_COMPONENTS, value)
            Settings.Secure.putInt(resolver, ScreensaverComponents.SETTING_ENABLED, 1)
            Log.i(TAG, "apply($choice): ${ScreensaverComponents.SETTING_COMPONENTS}=$value (wrote=$wrote)")
        } catch (e: SecurityException) {
            Log.w(TAG, "apply($choice) refused: ${e.message}")
        }
    }

    /** Which of our choices the system has selected now, or null when it holds some other dream or nothing. */
    fun current(): Screensaver? =
        ScreensaverComponents.choiceFor(readComponentsSetting(), appContext.packageName)

    override fun startNow(): Boolean {
        val order = StartStrategies.ordered(provenFirst)
        Log.i(TAG, "startNow: active=${readComponentsSetting()} order=${order.joinToString { it.name }}")
        return runStartStrategies(order, { attempt(it) }, { Log.i(TAG, it) })
    }

    private fun readComponentsSetting(): String? =
        Settings.Secure.getString(appContext.contentResolver, ScreensaverComponents.SETTING_COMPONENTS)

    private fun attempt(strategy: StartStrategy): StartAttempt = when (strategy.name) {
        StartStrategies.SOMNAMBULATOR -> startActivityIfResolves(
            Intent(Intent.ACTION_MAIN).setClassName(SYSTEMUI_PACKAGE, SOMNAMBULATOR_CLASS),
        )
        StartStrategies.DREAM_PACKAGE_ACTIVITY -> startDreamPackageActivity()
        else -> StartAttempt(StartOutcome.NOT_RESOLVED, "unknown strategy")
    }

    private fun startDreamPackageActivity(): StartAttempt {
        val selected = ScreensaverComponents.activeComponent(readComponentsSetting())
        if (!DreamActivityPicker.shouldTryPackage(selected, appContext.packageName)) {
            return StartAttempt(StartOutcome.NOT_RESOLVED, "selected dream is ours or unset: $selected")
        }
        val pkg = selected?.packageName
            ?: return StartAttempt(StartOutcome.NOT_RESOLVED, "no selected dream")
        val candidates = queryCandidates(pkg)
        Log.i(TAG, "candidates in $pkg: ${candidates.joinToString { it.className }}")
        val best = DreamActivityPicker.pick(candidates)
            ?: return StartAttempt(
                StartOutcome.NOT_RESOLVED,
                "no ambient/launcher activity among ${candidates.size} candidates in $pkg",
            )
        return startActivityIfResolves(Intent(Intent.ACTION_MAIN).setClassName(pkg, best.className))
    }

    /** Resolves [intent] (explicit component), then starts it in a new task. Never throws. */
    @Suppress("DEPRECATION")
    private fun startActivityIfResolves(intent: Intent): StartAttempt {
        val name = intent.component?.flattenToShortString() ?: intent.toString()
        // Intent.resolveActivity() returns an explicit component without checking it exists,
        // so ask the PackageManager.
        val info = appContext.packageManager.resolveActivity(intent, 0)
            ?: return StartAttempt(StartOutcome.NOT_RESOLVED, "$name does not resolve")
        if (info.activityInfo?.exported == false) {
            return StartAttempt(StartOutcome.NOT_RESOLVED, "$name is not exported")
        }
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
            StartAttempt(StartOutcome.STARTED, name)
        } catch (e: Exception) {
            StartAttempt(StartOutcome.FAILED, "$name: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun queryCandidates(pkg: String): List<DreamActivityCandidate> {
        val pm = appContext.packageManager
        fun query(intent: Intent): List<ActivityInfo> =
            pm.queryIntentActivities(intent, 0).mapNotNull { it.activityInfo }

        val leanback = query(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(pkg),
        ).map { it.name }.toSet()
        val launcher = query(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg),
        ).map { it.name }.toSet()
        // A MAIN intent with no category matches every MAIN activity, launcher ones included.
        return query(Intent(Intent.ACTION_MAIN).setPackage(pkg)).map {
            DreamActivityCandidate(
                className = it.name,
                exported = it.exported,
                isLeanbackLauncher = it.name in leanback,
                isLauncher = it.name in launcher,
            )
        }
    }

    companion object {
        const val TAG = "FieldAmbient"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val SOMNAMBULATOR_CLASS = "com.android.systemui.Somnambulator"
    }
}
