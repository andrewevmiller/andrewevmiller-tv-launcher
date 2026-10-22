package com.andrewevmiller.tvlauncher.home.apps

/**
 * One edit-mode session on the Home grid, as an immutable value so Compose can hold it in state.
 * No Android or Compose types.
 *
 * The session works on a copy ([working]); nothing is persisted until the caller drops it, and cancelling is
 * just discarding the session ([original] is untouched, so the original order is simply shown again).
 */
data class EditSession<T>(
    val original: List<T>,
    val working: List<T>,
    /** Where the lifted item started. */
    val startIndex: Int,
    /** Where the lifted item is now. */
    val index: Int,
    val columns: Int,
) {
    /** The lifted item. */
    val item: T get() = working[index]

    /** True when the order differs from the original, i.e. there is something to persist on drop. */
    val changed: Boolean get() = working != original

    /** True when pressing Down cannot move the item because it is already in the last grid row. */
    val inLastRow: Boolean
        get() = editTarget(index, working.size, columns, GridDirection.DOWN) == index

    /** Applies one D-pad press. Returns this session unchanged ([index] equal) when the item cannot move. */
    fun move(direction: GridDirection): EditSession<T> {
        val target = editTarget(index, working.size, columns, direction)
        return if (target == index) this else copy(working = reorder(working, index, target), index = target)
    }

    companion object {
        fun <T> start(items: List<T>, index: Int, columns: Int): EditSession<T> {
            require(index in items.indices) { "index $index is outside 0..${items.size - 1}" }
            require(columns > 0) { "columns must be positive" }
            return EditSession(
                original = items,
                working = items,
                startIndex = index,
                index = index,
                columns = columns,
            )
        }
    }
}
