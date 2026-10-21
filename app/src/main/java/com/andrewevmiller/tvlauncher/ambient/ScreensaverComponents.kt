package com.andrewevmiller.tvlauncher.ambient

import com.andrewevmiller.tvlauncher.settings.Screensaver

/**
 * A component name without android.content.ComponentName, so the mapping below is unit-testable.
 * [className] is always fully qualified (a leading '.' is expanded when parsing).
 */
data class ComponentRef(val packageName: String, val className: String) {
    /** "pkg/pkg.Cls", the form `ComponentName.flattenToString()` writes (and DreamBackend stores). */
    fun flatten(): String = "$packageName/$className"

    /** "pkg/.Cls" when the class sits under the package, like `flattenToShortString()`. */
    fun flattenShort(): String =
        if (className.startsWith("$packageName.")) "$packageName/${className.removePrefix(packageName)}" else flatten()

    companion object {
        /** Parses "pkg/cls" or "pkg/.cls"; null for anything else. */
        fun parse(flat: String): ComponentRef? {
            val s = flat.trim()
            val slash = s.indexOf('/')
            if (slash <= 0 || slash == s.length - 1) return null
            val pkg = s.substring(0, slash)
            val cls = s.substring(slash + 1)
            return ComponentRef(pkg, if (cls.startsWith(".")) pkg + cls else cls)
        }
    }
}

/** Which dream component each [Screensaver] choice means, and how `screensaver_components` reads and writes. */
object ScreensaverComponents {
    /** `Settings.Secure.SCREENSAVER_COMPONENTS` (hidden constant, so the literal key). */
    const val SETTING_COMPONENTS = "screensaver_components"

    /** `Settings.Secure.SCREENSAVER_ENABLED`. */
    const val SETTING_ENABLED = "screensaver_enabled"

    const val OXYFIELD_CLASS = "com.andrewevmiller.tvlauncher.ambient.OxyfieldDreamService"

    /** Google TV ambient mode, which shows the owner's Google Photos albums (probe, 2026-10-21). */
    val GOOGLE_PHOTOS = ComponentRef(
        "com.google.android.apps.tv.dreamx",
        "com.google.android.apps.tv.dreamx.service.Backdrop",
    )

    fun componentFor(choice: Screensaver, ownPackage: String): ComponentRef = when (choice) {
        Screensaver.OXYFIELD -> ComponentRef(ownPackage, OXYFIELD_CLASS)
        Screensaver.GOOGLE_PHOTOS -> GOOGLE_PHOTOS
    }

    /** The string to store in `screensaver_components`. */
    fun settingValueFor(choice: Screensaver, ownPackage: String): String =
        componentFor(choice, ownPackage).flatten()

    /** Parses the comma separated setting; malformed entries are skipped. */
    fun parseList(value: String?): List<ComponentRef> =
        if (value.isNullOrBlank()) emptyList() else value.split(',').mapNotNull { ComponentRef.parse(it) }

    /** The active dream is the first entry (what Android's DreamBackend reads). */
    fun activeComponent(value: String?): ComponentRef? = parseList(value).firstOrNull()

    /** Which of our choices the setting holds, or null when it names some other dream or is unset. */
    fun choiceFor(value: String?, ownPackage: String): Screensaver? {
        val active = activeComponent(value) ?: return null
        return Screensaver.values().firstOrNull { componentFor(it, ownPackage) == active }
    }
}
