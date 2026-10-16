package com.gpic.android.util

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Wall-clock totals per step, logged under `GPicPerf` (`adb logcat -s GPicPerf`). */
class Perf {
    private val totals = ConcurrentHashMap<String, AtomicLong>()

    inline fun <T> time(step: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            add(step, System.nanoTime() - t0)
        }
    }

    fun add(step: String, nanos: Long) {
        totals.getOrPut(step) { AtomicLong() }.addAndGet(nanos)
    }

    fun summary(): String =
        totals.entries.sortedByDescending { it.value.get() }.joinToString(" ") { "${it.key}=${it.value.get() / 1_000_000}ms" }

    companion object {
        const val TAG = "GPicPerf"
        inline fun <T> log(what: String, block: () -> T): T {
            val t0 = System.nanoTime()
            val r = block()
            Log.i(TAG, "$what took ${(System.nanoTime() - t0) / 1_000_000}ms" + if (r is Collection<*>) " (${r.size} items)" else "")
            return r
        }
    }
}
