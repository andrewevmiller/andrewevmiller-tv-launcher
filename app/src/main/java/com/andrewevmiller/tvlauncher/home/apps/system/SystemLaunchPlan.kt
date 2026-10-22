package com.andrewevmiller.tvlauncher.home.apps.system

/*
 * Pure (Android-free) fallback chains for the system tiles. [planLaunch] says WHAT to try, in order;
 * [runLaunchChain] walks the list against a [StepExecutor] and writes the trace. The Android side
 * (SystemActions) only implements [StepExecutor] with real intents and PackageManager.
 */

/** Logcat tag for every line the system tiles write. `adb logcat -s FieldSystemTiles` */
const val SYSTEM_TILES_LOG_TAG = "FieldSystemTiles"

/** Intent action strings; `FrameworkConstantsTest` checks them against the framework constants. */
object SystemIntentActions {
    const val ASSIST = "android.intent.action.ASSIST"
    const val WEB_SEARCH = "android.speech.action.WEB_SEARCH"
    const val WIFI_SETTINGS = "android.settings.WIFI_SETTINGS"
    const val SETTINGS = "android.settings.SETTINGS"

    /** Google TV's search app (katniss). Only used if something on the TV resolves it. */
    const val KATNISS_SEARCH = "com.google.android.katniss.action.SEARCH"
}

/** The TV build of the system Settings app (see CLAUDE.md "Target device"). */
object TvSettingsApp {
    const val PACKAGE = "com.android.tv.settings"

    /** Fully qualified: `ComponentName(pkg, cls)` does not expand the manifest's ".MainSettings" shorthand. */
    const val MAIN_ACTIVITY = "com.android.tv.settings.MainSettings"
}

/**
 * Hisense / VIDAA packages that own the TV's own input switching. Last-resort targets for an input
 * tile when the TIF passthrough intent finds no handler. Launching one opens that app's front page,
 * not the specific input, so it counts as an approximate result.
 */
val HisenseTvPackages: List<String> = listOf(
    "net.vidaatv.hisense.android.tv",
    "com.vt.tv.customerlauncher",
)

/** One thing to try. Pure description; SystemActions turns it into an `Intent`. */
sealed interface LaunchStep {
    /** Short text for logs. */
    fun describe(): String

    /** `ACTION_VIEW` on `TvContract.buildChannelUriForPassthroughInput(inputId)`. */
    data class PassthroughView(val inputId: String) : LaunchStep {
        override fun describe() = "VIEW passthrough URI for input $inputId"
    }

    /** `ACTION_VIEW` on `TvContract.Channels.CONTENT_URI`, which the system live-TV app handles. */
    data object ChannelsView : LaunchStep {
        override fun describe() = "VIEW content://android.media.tv/channel"
    }

    /** An implicit intent carrying only an action. */
    data class ImplicitAction(val action: String) : LaunchStep {
        override fun describe() = "action $action"
    }

    /** An explicit component. */
    data class Component(val packageName: String, val className: String) : LaunchStep {
        override fun describe() = "component $packageName/$className"
    }

    /** The package's own launcher entry (`getLeanbackLaunchIntentForPackage`, else `getLaunchIntentForPackage`). */
    data class LaunchablePackage(val packageName: String) : LaunchStep {
        override fun describe() = "launch intent of package $packageName"
    }
}

/**
 * An ordered fallback chain. The first [exactSteps] steps reach the screen the user asked for; later
 * steps are fallbacks that land somewhere near it (the TV app's front page, the main Settings list).
 */
data class LaunchPlan(val steps: List<LaunchStep>, val exactSteps: Int)

private val tvSettingsMain = LaunchStep.Component(TvSettingsApp.PACKAGE, TvSettingsApp.MAIN_ACTIVITY)

private fun hisenseSteps(): List<LaunchStep> = HisenseTvPackages.map { LaunchStep.LaunchablePackage(it) }

/**
 * The fallback chain for an action:
 *  - passthrough input: TIF passthrough URI, then the Hisense/VIDAA TV apps;
 *  - tuner input: the channels URI, then the Hisense/VIDAA TV apps;
 *  - Wi-Fi: `ACTION_WIFI_SETTINGS`, then `ACTION_SETTINGS`, then Settings' `MainSettings`;
 *  - Settings: `ACTION_SETTINGS`, then Settings' `MainSettings`;
 *  - Search: `ACTION_ASSIST`, then `WEB_SEARCH`, then Google TV's katniss search action.
 */
fun planLaunch(action: SystemTileAction): LaunchPlan = when (action) {
    is SystemTileAction.PassthroughInput ->
        LaunchPlan(listOf<LaunchStep>(LaunchStep.PassthroughView(action.inputId)) + hisenseSteps(), exactSteps = 1)

    is SystemTileAction.TunerInput ->
        LaunchPlan(listOf<LaunchStep>(LaunchStep.ChannelsView) + hisenseSteps(), exactSteps = 1)

    is SystemTileAction.OpenSettingsPanel -> when (action.kind) {
        SettingsPanel.WIFI -> LaunchPlan(
            listOf(
                LaunchStep.ImplicitAction(SystemIntentActions.WIFI_SETTINGS),
                LaunchStep.ImplicitAction(SystemIntentActions.SETTINGS),
                tvSettingsMain,
            ),
            exactSteps = 1,
        )

        SettingsPanel.MAIN -> LaunchPlan(
            listOf(LaunchStep.ImplicitAction(SystemIntentActions.SETTINGS), tvSettingsMain),
            exactSteps = 2,
        )
    }

    SystemTileAction.Search -> LaunchPlan(
        listOf(
            LaunchStep.ImplicitAction(SystemIntentActions.ASSIST),
            LaunchStep.ImplicitAction(SystemIntentActions.WEB_SEARCH),
            LaunchStep.ImplicitAction(SystemIntentActions.KATNISS_SEARCH),
        ),
        exactSteps = 3,
    )
}

/** What happened when the Android side tried one step. */
sealed interface StepOutcome {
    /** `startActivity` went through; [target] names what resolved, for the log. */
    data class Started(val target: String) : StepOutcome

    /** Not tried: nothing on this TV resolves it (no handler, package not installed). */
    data class Skipped(val reason: String) : StepOutcome

    /** Tried and threw (`ActivityNotFoundException`, `SecurityException`, ...). */
    data class Failed(val reason: String) : StepOutcome
}

fun interface StepExecutor {
    fun attempt(step: LaunchStep): StepOutcome
}

sealed interface LaunchResult {
    /**
     * Some step started. [approximate] is true when it was a fallback past the plan's exact steps, so
     * the UI can say where the user landed.
     */
    data class Started(val via: String, val approximate: Boolean) : LaunchResult

    /** Every step was skipped or failed. [message] is a short line fit for a mono caption. */
    data class Failed(val message: String) : LaunchResult
}

/**
 * Tries the plan's steps in order and stops at the first that starts. Every step is logged through
 * [log] (index, what it was, started / skipped / failed and why), so the owner's logcat shows exactly
 * which intent or package worked on this TV. An executor that throws counts as a failed step.
 */
fun runLaunchChain(
    what: String,
    plan: LaunchPlan,
    executor: StepExecutor,
    failureMessage: String,
    log: (String) -> Unit,
): LaunchResult {
    val total = plan.steps.size
    log("launch \"$what\": $total step(s) planned, first ${plan.exactSteps} reach the exact screen")
    for ((index, step) in plan.steps.withIndex()) {
        val prefix = "[${index + 1}/$total] ${step.describe()}"
        val outcome = try {
            executor.attempt(step)
        } catch (e: Exception) {
            StepOutcome.Failed("${e.javaClass.simpleName}: ${e.message}")
        }
        when (outcome) {
            is StepOutcome.Started -> {
                val approximate = index >= plan.exactSteps
                val note = if (approximate) " (fallback, not the exact screen)" else ""
                log("$prefix -> STARTED ${outcome.target}$note")
                return LaunchResult.Started(via = step.describe(), approximate = approximate)
            }

            is StepOutcome.Skipped -> log("$prefix -> skipped: ${outcome.reason}")
            is StepOutcome.Failed -> log("$prefix -> FAILED: ${outcome.reason}")
        }
    }
    log("launch \"$what\": every step failed or was unavailable")
    return LaunchResult.Failed(failureMessage)
}

private fun defaultName(action: SystemTileAction): String = when (action) {
    is SystemTileAction.PassthroughInput, is SystemTileAction.TunerInput -> "that input"
    is SystemTileAction.OpenSettingsPanel -> if (action.kind == SettingsPanel.WIFI) "Wi-Fi settings" else "Settings"
    SystemTileAction.Search -> "Search"
}

/** The short line shown under the row when nothing started: "Couldn't open HDMI 1." */
fun failureMessage(action: SystemTileAction, label: String?): String =
    "Couldn't open ${label ?: defaultName(action)}."

/**
 * The line shown when a fallback started instead of the exact screen, or null when there is nothing
 * to explain. Only inputs and Wi-Fi have approximate fallbacks.
 */
fun approximateNote(action: SystemTileAction, label: String?): String? = when (action) {
    is SystemTileAction.PassthroughInput, is SystemTileAction.TunerInput ->
        "Opened the TV app. Choose ${label ?: "the input"} there."

    is SystemTileAction.OpenSettingsPanel ->
        if (action.kind == SettingsPanel.WIFI) "Opened Settings. Choose Wi-Fi there." else null

    SystemTileAction.Search -> null
}
