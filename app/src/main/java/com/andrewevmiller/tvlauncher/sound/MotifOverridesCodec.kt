package com.andrewevmiller.tvlauncher.sound

/**
 * Pure text codec and edit rules for the per-app motif overrides. No Android types, so purecheck compiles and
 * tests it; [DataStoreMotifOverrides] is the thin DataStore layer on top.
 *
 * Storage format: one app per line, `packageName|rerollCount|muted` with `muted` written as 1 or 0, sorted by
 * package so the stored text is stable. Only apps with a non-default override are written. Decoding is forgiving:
 * null or blank input is empty; blank lines, lines without exactly three fields and lines with an empty package
 * are skipped; a re-roll count that is not a number or is negative reads as 0 and one above Int.MAX_VALUE as
 * Int.MAX_VALUE; `muted` is true only for `1` or `true`; a package listed twice keeps its last line; a line that
 * decodes to the default override is dropped.
 */
object MotifOverridesCodec {
    private const val SEPARATOR = '|'
    private val DEFAULT = MotifOverride()

    fun encode(overrides: Map<String, MotifOverride>): String =
        normalize(overrides).toSortedMap().entries.joinToString(separator = "\n") { (pkg, o) ->
            "$pkg$SEPARATOR${o.rerollCount}$SEPARATOR${if (o.muted) 1 else 0}"
        }

    fun decode(raw: String?): Map<String, MotifOverride> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, MotifOverride>()
        for (line in raw.lineSequence()) {
            val fields = line.split(SEPARATOR)
            if (fields.size != 3) continue
            val pkg = fields[0].trim()
            if (pkg.isEmpty()) continue
            val count = fields[1].trim().toLongOrNull()?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
            val muted = fields[2].trim().let { it == "1" || it.equals("true", ignoreCase = true) }
            out.remove(pkg) // last line wins, and it moves to the end like a fresh entry
            out[pkg] = MotifOverride(rerollCount = count, muted = muted)
        }
        return normalize(out)
    }

    /** Drops default entries, blank or unsafe package names (a `|` or line break would not survive the format), and negative counts. */
    fun normalize(overrides: Map<String, MotifOverride>): Map<String, MotifOverride> {
        val out = LinkedHashMap<String, MotifOverride>()
        for ((pkg, o) in overrides) {
            val name = pkg.trim()
            if (name.isEmpty() || name.any { it == SEPARATOR || it == '\n' || it == '\r' }) continue
            val clean = o.copy(rerollCount = o.rerollCount.coerceAtLeast(0))
            if (clean != DEFAULT) out[name] = clean
        }
        return out
    }

    fun reroll(overrides: Map<String, MotifOverride>, packageName: String): Map<String, MotifOverride> =
        change(overrides, packageName) { it.copy(rerollCount = if (it.rerollCount == Int.MAX_VALUE) it.rerollCount else it.rerollCount + 1) }

    fun setMuted(overrides: Map<String, MotifOverride>, packageName: String, muted: Boolean): Map<String, MotifOverride> =
        change(overrides, packageName) { it.copy(muted = muted) }

    fun reset(overrides: Map<String, MotifOverride>, packageName: String): Map<String, MotifOverride> =
        normalize(overrides - packageName)

    private fun change(
        overrides: Map<String, MotifOverride>,
        packageName: String,
        f: (MotifOverride) -> MotifOverride,
    ): Map<String, MotifOverride> {
        val next = f(overrides[packageName] ?: DEFAULT)
        return normalize(overrides + (packageName to next))
    }
}
