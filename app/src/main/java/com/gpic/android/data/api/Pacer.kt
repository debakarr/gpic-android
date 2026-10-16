package com.gpic.android.data.api

/**
 * Spaces out calls to one rate-limited service (Google Photos' library API allows a fixed number of calls per
 * minute per account). Bursting into the limit costs 10–60 s pauses; pacing just under it keeps a long run
 * steady. Additive increase / multiplicative decrease: halves on a 429, creeps back up after a run of successes.
 */
class Pacer(
    initialPerSec: Double,
    private val minPerSec: Double,
    private val maxPerSec: Double,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile var perSec: Double = initialPerSec
        private set
    private var nextAt = 0L
    private var successes = 0

    /** Milliseconds the caller should wait before its call; reserves the slot. */
    @Synchronized
    fun reserve(): Long {
        val t = now()
        val start = maxOf(t, nextAt)
        nextAt = start + (1000.0 / perSec).toLong()
        return start - t
    }

    fun await() {
        val wait = reserve()
        if (wait > 0) Thread.sleep(wait)
    }

    @Synchronized
    fun onSuccess() {
        if (++successes >= STEP_EVERY && perSec < maxPerSec) {
            successes = 0
            perSec = minOf(maxPerSec, perSec + STEP)
        }
    }

    @Synchronized
    fun onRateLimited() {
        successes = 0
        perSec = maxOf(minPerSec, perSec / 2)
    }

    companion object {
        private const val STEP_EVERY = 60
        private const val STEP = 0.5
    }
}
