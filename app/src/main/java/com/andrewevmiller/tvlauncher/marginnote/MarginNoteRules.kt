package com.andrewevmiller.tvlauncher.marginnote

/**
 * Pure rules for the margin note: one short line of the owner's own text on Home.
 *
 * Sanitising runs in this order: control characters, surrogates and line/paragraph separators are
 * removed; the ends are trimmed; the text is clamped to [MAX_LENGTH] code points (an over-long input
 * is cut, not rejected, and never splits a surrogate pair); the ends are trimmed again, since a cut
 * can leave a trailing space. Empty means "no note".
 */
object MarginNoteRules {
    /** Longest note, counted in Unicode code points (an emoji is one). */
    const val MAX_LENGTH = 80

    /** The label printed before the note, in the mono type style. */
    const val LABEL = "NOTE"

    /** What the quiet row says while it is focused and there is no note. */
    const val EMPTY_HINT = "add a note"

    /** Cleans a raw note. Null, blank, or all-control input gives "". Never throws. */
    fun sanitize(raw: String?): String {
        if (raw == null) return ""
        val stripped = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            if (!isStrippable(cp)) stripped.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return clamp(stripped.toString().trim()).trim()
    }

    /**
     * What the editor keeps while the owner types: control characters stripped and clamped to [MAX_LENGTH],
     * but not trimmed, so a space typed in the middle of a word or at the end survives until Done.
     */
    fun limitWhileEditing(raw: String): String {
        val stripped = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            if (!isStrippable(cp)) stripped.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return clamp(stripped.toString())
    }

    /**
     * The note Home shows: the stored note when it is non-empty after sanitising, otherwise the
     * secure-setting note (`field_launcher_note`, set from a laptop with scripts/note.sh), otherwise "".
     */
    fun merge(stored: String?, fromSecureSetting: String?): String {
        val own = sanitize(stored)
        if (own.isNotEmpty()) return own
        return sanitize(fromSecureSetting)
    }

    /** The row's text: `NOTE  <note>`, or `NOTE  add a note` when there is no note. */
    fun rowText(note: String): String = if (note.isEmpty()) "$LABEL  $EMPTY_HINT" else "$LABEL  $note"

    private fun isStrippable(cp: Int): Boolean =
        Character.isISOControl(cp) ||
            Character.getType(cp) == Character.SURROGATE.toInt() ||
            cp == LINE_SEPARATOR ||
            cp == PARAGRAPH_SEPARATOR

    /** Cuts [s] after its [MAX_LENGTH]th code point. Returns [s] unchanged when it is short enough. */
    private fun clamp(s: String): String {
        var count = 0
        var i = 0
        while (i < s.length) {
            if (count == MAX_LENGTH) return s.substring(0, i)
            i += Character.charCount(s.codePointAt(i))
            count++
        }
        return s
    }

    private const val LINE_SEPARATOR = 0x2028
    private const val PARAGRAPH_SEPARATOR = 0x2029
}
