package com.andrewevmiller.tvlauncher.safety

/**
 * Shown on the next manual start after the crash-loop fallback handed Home back.
 *
 * @property message the full sentence for the banner
 * @property previousHome human-readable label of the launcher that got Home back (e.g. "Wii Menu")
 */
data class FallbackNotice(
    val message: String,
    val previousHome: String,
) {
    companion object {
        /** Builds the notice for a given previous-home label. */
        fun forPreviousHome(label: String): FallbackNotice =
            FallbackNotice(message = messageFor(label), previousHome = label)

        fun messageFor(label: String): String =
            "Field Launcher crashed twice, so Home went back to $label. " +
                "Run scripts/deploy.sh to make it Home again."
    }
}
