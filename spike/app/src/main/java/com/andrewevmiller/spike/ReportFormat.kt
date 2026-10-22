package com.andrewevmiller.spike

/*
 * Pure formatting / classification helpers (no Android imports) so the result text is easy to reason
 * about and could be unit-tested later.
 */

/** What a single TvContract query showed. These are the three things E1 must tell apart, plus errors. */
enum class Outcome {
    /** The provider refused the query: no access at all. */
    SECURITY_EXCEPTION,

    /** query() returned null (provider not visible / not found). */
    NULL_CURSOR,

    /** Some other exception. */
    OTHER_ERROR,

    /** Query worked but returned nothing. Either nobody publishes, or other apps' rows are hidden. */
    ZERO_ROWS,

    /** Query worked and every row belongs to this app (cannot happen unless we published; defensive). */
    OWN_ROWS_ONLY,

    /** Query worked and at least one row belongs to another package: the launcher can read them. */
    ROWS_FROM_OTHER_APPS,
}

data class TableResult(
    val table: String,
    val uri: String,
    val outcome: Outcome,
    val totalRows: Int,
    /** package_name column -> row count ("?" when the column is missing or null). */
    val perPackage: Map<String, Int>,
    /** First few rows as "package | title". */
    val sample: List<String>,
    /** Exception message, or extra notes (projection fallback, truncated scan, columns). */
    val detail: String,
)

object ReportFormat {

    fun classify(totalRows: Int, perPackage: Map<String, Int>, ownPackage: String): Outcome {
        if (totalRows == 0) return Outcome.ZERO_ROWS
        val foreign = perPackage.keys.any { it != ownPackage }
        return if (foreign) Outcome.ROWS_FROM_OTHER_APPS else Outcome.OWN_ROWS_ONLY
    }

    /** 1 = neither permission granted, 2 = READ_EPG_DATA granted, 3 = ACCESS_ALL_EPG_DATA granted. */
    fun permissionState(readGranted: Boolean, allGranted: Boolean): Int = when {
        allGranted -> 3
        readGranted -> 2
        else -> 1
    }

    fun stateTitle(state: Int): String = when (state) {
        1 -> "STATE 1 (no extra permission)"
        2 -> "STATE 2 (READ_EPG_DATA granted)"
        3 -> "STATE 3 (ACCESS_ALL_EPG_DATA granted)"
        else -> "STATE $state"
    }

    fun ellipsize(text: String, max: Int): String =
        if (text.length <= max) text else text.substring(0, max - 3) + "..."

    fun packagesText(perPackage: Map<String, Int>, limit: Int = 6): String {
        val sorted = perPackage.entries.sortedByDescending { it.value }
        val shown = sorted.take(limit).joinToString(", ") { it.key + "=" + it.value }
        val more = sorted.size - limit
        return if (more > 0) "$shown, +$more more" else shown
    }

    /** One line per table; this is what gets saved per permission state and pasted by the owner. */
    fun oneLine(r: TableResult): String {
        val head = r.table.padEnd(9) + " " + r.outcome.name
        return when (r.outcome) {
            Outcome.SECURITY_EXCEPTION, Outcome.OTHER_ERROR, Outcome.NULL_CURSOR ->
                head + "  " + ellipsize(r.detail.replace('\n', ' '), 110)
            Outcome.ZERO_ROWS -> head
            Outcome.OWN_ROWS_ONLY, Outcome.ROWS_FROM_OTHER_APPS ->
                head + "  rows=" + r.totalRows + "  apps: " + packagesText(r.perPackage) +
                    (if (r.detail.isEmpty()) "" else "  [" + ellipsize(r.detail.replace('\n', ' '), 80) + "]")
        }
    }

    /** Full per-table block for the live results pane. */
    fun detailLines(r: TableResult): List<String> {
        val lines = ArrayList<String>()
        lines.add("[" + r.table.trim() + "] " + r.uri)
        lines.add("  -> " + r.outcome.name + verdict(r.outcome))
        if (r.totalRows > 0) {
            lines.add("  rows=" + r.totalRows + "  apps: " + packagesText(r.perPackage, 10))
            for (s in r.sample) lines.add("    " + ellipsize(s, 100))
        }
        if (r.detail.isNotEmpty()) lines.add("  note: " + ellipsize(r.detail.replace('\n', ' '), 300))
        return lines
    }

    private fun verdict(outcome: Outcome): String = when (outcome) {
        Outcome.SECURITY_EXCEPTION -> "   (provider refuses us: no access)"
        Outcome.NULL_CURSOR -> "   (provider not reachable / not visible)"
        Outcome.OTHER_ERROR -> "   (unexpected error, see note)"
        Outcome.ZERO_ROWS -> "   (allowed but empty: nobody publishes, or other apps' rows are filtered out)"
        Outcome.OWN_ROWS_ONLY -> "   (only our own rows are visible)"
        Outcome.ROWS_FROM_OTHER_APPS -> "   (REAL ROWS from other apps)"
    }

    /** Combined E1 summary over the three permission states, from the saved per-state blocks. */
    fun e1Summary(header: String, blocks: Map<Int, String?>): List<String> {
        val lines = ArrayList<String>()
        lines.add("E1 SUMMARY  $header")
        for (state in 1..3) {
            lines.add(stateTitle(state))
            val block = blocks[state]
            if (block.isNullOrEmpty()) {
                lines.add("  (not run in this state)")
            } else {
                for (l in block.split('\n')) lines.add("  $l")
            }
        }
        return lines
    }

    /** One E2 attempt as two lines of the summary table. */
    fun e2Row(label: String, screensaver: String, call: String, lostFocus: String, visible: String): List<String> =
        listOf(
            label,
            "  ss=" + ellipsize(screensaver, 60),
            "  call=" + ellipsize(call.replace('\n', ' '), 90),
            "  focusLost=" + lostFocus + "  visible=" + visible,
        )

    /** "com.google.x.Foo" -> ".Foo" when it starts with the package name. */
    fun shortClass(name: String, pkg: String): String =
        if (name.startsWith(pkg)) name.substring(pkg.length) else name
}
