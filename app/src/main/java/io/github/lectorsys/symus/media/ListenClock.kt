package io.github.lectorsys.symus.media

/**
 * How long the user actually listened, as opposed to how far the playhead moved.
 *
 * The distinction is the entire point. A playback position says a ten-minute
 * track was "played" the instant someone drags the scrubber to the end; this
 * says they listened for two seconds, because two seconds of wall-clock went
 * by. Nothing here takes a position, a duration or a seek event as input —
 * there is no input that scrubbing could inflate.
 *
 * Fed a monotonic clock (`SystemClock.elapsedRealtime`), so a timezone change
 * or an NTP correction cannot conjure an hour of listening out of nothing.
 *
 * Not thread-safe, and does not need to be: the service drives it from its
 * single main-immediate scope.
 */
class ListenClock(private val minCreditMs: Long = 1_000L) {

    private var track: String? = null

    /**
     * Nullable rather than a 0 sentinel: 0 is a perfectly legal reading from a
     * monotonic clock, and treating it as "not running" silently drops the
     * first listen after a boot.
     */
    private var since: Long? = null

    /** True while audio is being credited to something. */
    val running: Boolean get() = track != null && since != null

    /** The track currently being credited, for tests and diagnostics. */
    val current: String? get() = track

    /** Audio started coming out for [videoId]. A null id stops the clock. */
    fun start(videoId: String?, now: Long) {
        if (videoId == null) {
            stop()
            return
        }
        track = videoId
        since = now
    }

    /**
     * Banks whatever has accrued.
     *
     * [restart] true is a periodic checkpoint — credit so far is returned and
     * the clock keeps running for the same track. False ends the listen.
     *
     * Returns the track and the milliseconds to credit it, or null when there is
     * nothing worth writing. Below [minCreditMs] a checkpoint deliberately does
     * *not* reset, so a run of short samples accumulates instead of being
     * rounded away one sliver at a time.
     */
    fun sample(now: Long, restart: Boolean): Credit? {
        val t = track
        val s = since
        if (t == null || s == null) {
            if (!restart) stop()
            return null
        }
        val elapsed = (now - s).coerceAtLeast(0L)
        if (elapsed < minCreditMs) {
            if (!restart) stop()
            return null
        }
        if (restart) {
            since = now
        } else {
            stop()
        }
        return Credit(t, elapsed)
    }

    fun stop() {
        track = null
        since = null
    }

    data class Credit(val videoId: String, val ms: Long)
}
