package io.github.lectorsys.symus.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Playlist and queue reordering.
 *
 * Both screens nudge a row with two buttons and both send the whole new order
 * to be renumbered, so this one function is the reorder logic for the app. An
 * off-by-one here silently rewrites somebody's running order.
 */
class OrderingTest {

    private val list = listOf("a", "b", "c", "d")

    @Test
    fun `moves a row up`() {
        assertEquals(listOf("a", "c", "b", "d"), list.movedTo(2, 1))
    }

    @Test
    fun `moves a row down`() {
        assertEquals(listOf("b", "a", "c", "d"), list.movedTo(0, 1))
    }

    @Test
    fun `moves across the whole list`() {
        assertEquals(listOf("b", "c", "d", "a"), list.movedTo(0, 3))
        assertEquals(listOf("d", "a", "b", "c"), list.movedTo(3, 0))
    }

    @Test
    fun `a nudge off either end changes nothing`() {
        // The first row's "up" button is disabled, but the guard is what makes
        // that a presentation detail rather than the only thing preventing a
        // corrupted order.
        assertEquals(list, list.movedTo(0, -1))
        assertEquals(list, list.movedTo(3, 4))
        assertEquals(list, list.movedTo(9, 0))
    }

    @Test
    fun `moving a row onto itself changes nothing`() {
        assertEquals(list, list.movedTo(2, 2))
    }

    @Test
    fun `an empty list is left alone`() {
        assertEquals(emptyList<String>(), emptyList<String>().movedTo(0, 1))
    }

    @Test
    fun `every element survives the move`() {
        val moved = list.movedTo(1, 3)
        assertEquals(list.size, moved.size)
        assertEquals(list.toSet(), moved.toSet())
    }
}
