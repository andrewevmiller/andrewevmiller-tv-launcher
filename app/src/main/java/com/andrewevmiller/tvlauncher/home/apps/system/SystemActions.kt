package com.andrewevmiller.tvlauncher.home.apps.system

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvContract
import android.util.Log

/**
 * Performs a system tile's action. The fallback chains are decided by the pure [planLaunch]; this file
 * only turns each [LaunchStep] into a real `Intent`, checks it with `PackageManager`, and starts it.
 *
 * Chains (first that starts wins):
 *  - HDMI / composite / ...: `ACTION_VIEW` on `TvContract.buildChannelUriForPassthroughInput(id)`, then the
 *    launcher intent of `net.vidaatv.hisense.android.tv`, then `com.vt.tv.customerlauncher`;
 *  - tuner: `ACTION_VIEW` on `TvContract.Channels.CONTENT_URI`, then the same two Hisense apps;
 *  - Wi-Fi: `ACTION_WIFI_SETTINGS`, `ACTION_SETTINGS`, then `com.android.tv.settings/.MainSettings`;
 *  - Settings: `ACTION_SETTINGS`, then `com.android.tv.settings/.MainSettings`;
 *  - Search: `ACTION_ASSIST`, `WEB_SEARCH`, then `com.google.android.katniss.action.SEARCH`.
 *
 * Each step is first resolved with `PackageManager` (skipped, not attempted, if nothing handles it) and
 * `startActivity` is wrapped for `ActivityNotFoundException` and `SecurityException`. Any other exception
 * is caught too: a system tile must never crash the launcher (two crashes in 60s hand Home away).
 *
 * Every step is logged at INFO under [SYSTEM_TILES_LOG_TAG], so the owner's
 * `adb logcat -s FieldSystemTiles` shows which intent or package worked on this TV.
 */
object SystemActions {

    /**
     * Runs [action]. [label] (the tile's label) only improves log and failure text. Returns
     * [LaunchResult.Started] or, when no step could be started, [LaunchResult.Failed] with a short
     * message for the UI.
     */
    fun launch(context: Context, action: SystemTileAction, label: String? = null): LaunchResult {
        val plan = planLaunch(action)
        val failure = failureMessage(action, label)
        return try {
            runLaunchChain(
                what = label ?: action.toString(),
                plan = plan,
                executor = AndroidStepExecutor(context),
                failureMessage = failure,
                log = { Log.i(SYSTEM_TILES_LOG_TAG, it) },
            )
        } catch (e: Exception) {
            Log.w(SYSTEM_TILES_LOG_TAG, "launch of ${label ?: action} threw: ${describeError(e)}")
            LaunchResult.Failed(failure)
        }
    }
}

private fun describeError(e: Exception): String = "${e.javaClass.simpleName}: ${e.message}"

/** Real-intent implementation of the pure [StepExecutor]. */
private class AndroidStepExecutor(private val context: Context) : StepExecutor {

    private val pm: PackageManager = context.packageManager

    // resolveActivity(Intent, Int) is deprecated on API 33+ in favour of ResolveInfoFlags; the Int form still works.
    @Suppress("DEPRECATION")
    override fun attempt(step: LaunchStep): StepOutcome {
        val intent: Intent = try {
            buildIntent(step) ?: return StepOutcome.Skipped("package is not installed or has no launcher activity")
        } catch (e: Exception) {
            return StepOutcome.Failed("could not build the intent: ${describeError(e)}")
        }
        // The context may not be an Activity (and launch intents from other packages need a task of their own).
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val resolved = try {
            // MATCH_DEFAULT_ONLY mirrors what startActivity does for implicit intents.
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (e: Exception) {
            return StepOutcome.Failed("resolveActivity threw: ${describeError(e)}")
        }
        if (resolved == null) return StepOutcome.Skipped("nothing on this TV resolves $intent")
        val target = resolved.activityInfo?.let { "${it.packageName}/${it.name}" } ?: "an unnamed activity"

        return try {
            context.startActivity(intent)
            StepOutcome.Started("-> $target")
        } catch (e: ActivityNotFoundException) {
            StepOutcome.Failed("ActivityNotFoundException for $target: ${e.message}")
        } catch (e: SecurityException) {
            StepOutcome.Failed("SecurityException for $target: ${e.message}")
        } catch (e: Exception) {
            StepOutcome.Failed("${describeError(e)} for $target")
        }
    }

    private fun buildIntent(step: LaunchStep): Intent? = when (step) {
        is LaunchStep.PassthroughView ->
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(step.inputId))

        LaunchStep.ChannelsView ->
            Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI)

        is LaunchStep.ImplicitAction ->
            Intent(step.action)

        is LaunchStep.Component ->
            Intent().setComponent(ComponentName(step.packageName, step.className))

        is LaunchStep.LaunchablePackage ->
            pm.getLeanbackLaunchIntentForPackage(step.packageName)
                ?: pm.getLaunchIntentForPackage(step.packageName)
    }
}
