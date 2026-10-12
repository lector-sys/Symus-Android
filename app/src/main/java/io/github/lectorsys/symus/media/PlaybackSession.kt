package io.github.lectorsys.symus.media

import androidx.media3.common.Player

/**
 * The queue, its position, and the two modes, as one line of text.
 *
 * Its own type rather than a few settings keys: restoring a session is
 * all-or-nothing — a queue without its index, or an index without its position,
 * is worse than starting fresh — and one string either parses or it does not.
 *
 * Separated from the service so the format can be tested without starting one.
 * A queue that comes back wrong is the kind of bug nobody notices until their
 * commute, which is exactly the kind worth a test.
 */
data class PlaybackSession(
    val videoIds: List<String>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeat: Int
) {
    fun serialize(): String = listOf(
        videoIds.joinToString(","),
        index.toString(),
        positionMs.toString(),
        if (shuffle) "1" else "0",
        repeat.toString()
    ).joinToString("|")

    companion object {
        /**
         * Deliberately forgiving: a stored session is state, not input, and a
         * line this app wrote in an older shape must degrade to "no session"
         * rather than crash the service that reads it on every cold start.
         */
        fun parse(raw: String): PlaybackSession? {
            val parts = raw.split("|")
            if (parts.size < 5) return null
            val ids = parts[0].split(",").filter { it.isNotBlank() }
            if (ids.isEmpty()) return null
            return PlaybackSession(
                videoIds = ids,
                index = (parts[1].toIntOrNull() ?: 0).coerceIn(0, ids.lastIndex),
                positionMs = parts[2].toLongOrNull()?.coerceAtLeast(0) ?: 0,
                shuffle = parts[3] == "1",
                repeat = parts[4].toIntOrNull()?.takeIf {
                    it == Player.REPEAT_MODE_OFF ||
                        it == Player.REPEAT_MODE_ONE ||
                        it == Player.REPEAT_MODE_ALL
                } ?: Player.REPEAT_MODE_OFF
            )
        }
    }
}
