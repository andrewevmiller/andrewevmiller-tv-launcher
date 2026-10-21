package com.andrewevmiller.tvlauncher.ambient

/*
 * Data-driven "start the screensaver now" logic. The Android layer (SystemScreensaverController) knows how to
 * attempt each named strategy; this file decides the order and when to stop. Spike E2 on the TV decides which
 * strategy really works: pass its name as `provenFirst` and it moves to the front.
 */

data class StartStrategy(val name: String, val description: String)

enum class StartOutcome {
    /** The start intent went out without throwing. */
    STARTED,

    /** The target activity does not exist, is not exported, or has no candidate here. */
    NOT_RESOLVED,

    /** Resolved but the start threw (SecurityException, ActivityNotFoundException, ...). */
    FAILED,
}

data class StartAttempt(val outcome: StartOutcome, val detail: String = "")

object StartStrategies {
    /** `com.android.systemui/.Somnambulator`, the activity behind "Start screen saver" on stock Android. */
    const val SOMNAMBULATOR = "somnambulator"

    /** A launcher / ambient activity in the selected dream's own package. */
    const val DREAM_PACKAGE_ACTIVITY = "dream-package-activity"

    val DEFAULT_ORDER: List<StartStrategy> = listOf(
        StartStrategy(SOMNAMBULATOR, "Start com.android.systemui/.Somnambulator, which asks the DreamManager to dream"),
        StartStrategy(DREAM_PACKAGE_ACTIVITY, "Start a launcher or ambient activity of the selected dream's package"),
    )

    /**
     * [all] with the strategy named [provenFirst] moved to the front. A null, blank or unknown name leaves the
     * order unchanged; the relative order of the rest is kept.
     */
    fun ordered(provenFirst: String?, all: List<StartStrategy> = DEFAULT_ORDER): List<StartStrategy> {
        val name = provenFirst?.trim()
        if (name.isNullOrEmpty()) return all
        val proven = all.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return all
        return listOf(proven) + all.filter { it !== proven }
    }
}

/**
 * Tries [order] one by one until an attempt reports [StartOutcome.STARTED]. An attempt that throws counts as
 * FAILED. Every try is reported through [log]. False when none worked (the "(c) none" case).
 */
fun runStartStrategies(
    order: List<StartStrategy>,
    attempt: (StartStrategy) -> StartAttempt,
    log: (String) -> Unit,
): Boolean {
    for (strategy in order) {
        val result = try {
            attempt(strategy)
        } catch (e: Exception) {
            StartAttempt(StartOutcome.FAILED, "${e.javaClass.simpleName}: ${e.message}")
        }
        val detail = if (result.detail.isEmpty()) "" else " (${result.detail})"
        log("start strategy '${strategy.name}': ${result.outcome}$detail")
        if (result.outcome == StartOutcome.STARTED) return true
    }
    log("no start strategy worked (${order.size} tried)")
    return false
}

/** An activity found in the selected dream's package. */
data class DreamActivityCandidate(
    val className: String,
    val exported: Boolean,
    val isLeanbackLauncher: Boolean,
    val isLauncher: Boolean,
)

object DreamActivityPicker {
    private val AMBIENT_WORDS = listOf("ambient", "dream", "screensaver", "slideshow")

    /** Names that open a settings or onboarding page rather than the ambient experience. */
    private val SETTINGS_WORDS = listOf("setting", "config", "setup", "preference", "about", "onboard", "welcome")

    /** Only try another package's activity when the selected dream is not ours. */
    fun shouldTryPackage(selected: ComponentRef?, ownPackage: String): Boolean =
        selected != null && selected.packageName != ownPackage

    /** Score for one candidate; null when it should never be started. */
    fun score(c: DreamActivityCandidate): Int? {
        if (!c.exported) return null
        val simple = c.className.substringAfterLast('.').lowercase()
        if (SETTINGS_WORDS.any { simple.contains(it) }) return null
        return when {
            AMBIENT_WORDS.any { simple.contains(it) } -> 100
            c.isLeanbackLauncher -> 50
            c.isLauncher -> 40
            else -> 10
        }
    }

    /** The best candidate, or null. Ties keep the earlier one. */
    fun pick(candidates: List<DreamActivityCandidate>): DreamActivityCandidate? =
        candidates.filter { score(it) != null }.maxByOrNull { score(it) ?: 0 }
}
