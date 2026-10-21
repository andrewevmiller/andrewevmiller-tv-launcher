package com.andrewevmiller.tvlauncher.home.data

/**
 * Pure text codec for [FieldLayout]. No Android types, so it is compiled and tested by purecheck.
 *
 * Storage format: one component per line (`ComponentName.flattenToString()`, which never contains a
 * line break). [encodeHidden] writes the hidden set sorted so the stored value is stable. Decoding is
 * forgiving: null or blank input is empty, blank lines and surrounding whitespace are dropped, and
 * duplicates are removed keeping the first occurrence.
 *
 * `seeded` is not part of this text format. The DataStore layer stores it under its own key.
 */
object LayoutCodec {

    fun encodePinned(pinned: List<String>): String = encodeLines(pinned)

    fun decodePinned(raw: String?): List<String> = decodeLines(raw)

    fun encodeHidden(hidden: Set<String>): String = encodeLines(hidden.sorted())

    fun decodeHidden(raw: String?): Set<String> = decodeLines(raw).toSet()

    /**
     * Enforces the layout invariants. Entries are trimmed and blank ones dropped. There are no
     * duplicates (first occurrence wins) and pinned order is kept. An app in both lists stays hidden
     * (hidden wins). `seeded` passes through unchanged.
     */
    fun normalize(layout: FieldLayout): FieldLayout {
        val hidden: Set<String> = layout.hidden.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val pinned: List<String> = layout.pinned.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it !in hidden }
            .distinct()
            .toList()
        return FieldLayout(pinned = pinned, hidden = hidden, seeded = layout.seeded)
    }

    private fun encodeLines(items: List<String>): String {
        require(items.none { it.contains('\n') || it.contains('\r') }) {
            "layout entries must not contain line breaks"
        }
        return items.joinToString(separator = "\n")
    }

    private fun decodeLines(raw: String?): List<String> {
        if (raw == null) return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }
}
