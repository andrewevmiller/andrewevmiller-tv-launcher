package com.andrewevmiller.tvlauncher.safety

/**
 * Pure stand-in for `android.content.ComponentName` so the fallback's choice of target can be unit
 * tested. [className] is always fully qualified.
 */
data class HomeComponent(val packageName: String, val className: String) {
    /** Same text as `ComponentName.flattenToString()`: `package/fully.qualified.Class`. */
    fun flatten(): String = "$packageName/$className"

    companion object {
        /**
         * Parses `package/.Class` or `package/fully.qualified.Class` (what `ComponentName.flattenToShortString()`
         * and `flattenToString()` produce, and what deploy.sh writes into `field_launcher_previous_home`).
         * Returns null for null, blank, the literal "null" that `adb shell settings get` prints for an
         * unset key, or anything without a non-empty package and class.
         */
        fun unflatten(text: String?): HomeComponent? {
            val trimmed = text?.trim() ?: return null
            if (trimmed.isEmpty() || trimmed == "null") return null
            val slash = trimmed.indexOf('/')
            if (slash <= 0 || slash == trimmed.length - 1) return null
            val pkg = trimmed.substring(0, slash)
            val cls = trimmed.substring(slash + 1)
            val fullClass = if (cls.startsWith(".")) pkg + cls else cls
            return HomeComponent(pkg, fullClass)
        }
    }
}

/**
 * One enabled activity that answers ACTION_MAIN + CATEGORY_HOME, as reported by PackageManager.
 *
 * @property declaresDefaultCategory true when it also declares CATEGORY_DEFAULT (it showed up in a
 *   MATCH_DEFAULT_ONLY query). Only those are trusted as an arbitrary last-resort Home; the recorded
 *   previous home may be any candidate because we start it by explicit component.
 */
data class HomeCandidate(
    val component: HomeComponent,
    val declaresDefaultCategory: Boolean = true,
)

/** The decision half of the fallback: which launcher gets Home back. No Android types. */
object HomeTargetChooser {
    /**
     * @param recordedPreviousHome raw value of `field_launcher_previous_home` (may be null or junk)
     * @param candidates enabled HOME activities in the order PackageManager returned them
     * @param ownPackage our package; never chosen
     * @return the recorded previous home if it is still an enabled HOME activity, otherwise the first
     *   other enabled HOME activity that declares CATEGORY_DEFAULT, otherwise null. Null means "there is
     *   nowhere to hand Home to", and the caller must leave our HOME alias alone.
     */
    fun choose(
        recordedPreviousHome: String?,
        candidates: List<HomeCandidate>,
        ownPackage: String,
    ): HomeComponent? {
        val others = candidates.filter { it.component.packageName != ownPackage }
        val previous = HomeComponent.unflatten(recordedPreviousHome)
        if (previous != null && others.any { it.component == previous }) return previous
        return others.firstOrNull { it.declaresDefaultCategory }?.component
    }
}
