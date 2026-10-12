package io.github.lectorsys.symus.data

/**
 * The list with one item moved.
 *
 * Reordering is expressed as a whole new order rather than a swap: the database
 * renumbers from a list, so rebuilding that list from what the screen already
 * shows is the one form that cannot drift out of step with it. Out-of-range
 * indices return the list untouched, because a nudge on the first row is a
 * no-op, not an error.
 */
fun <T> List<T>.movedTo(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    val copy = toMutableList()
    copy.add(to, copy.removeAt(from))
    return copy
}
