package com.gpic.android.data.api

import kotlin.random.Random

/**
 * Port of gphotos/retry.py
 */
data class RetryConfig(
    val maxRetries: Int = 3,
    val initialDelayMs: Long = 1000,
    val maxDelayMs: Long = 30_000,
    val backoffFactor: Double = 2.0,
)

fun calculateBackoff(attempt: Int, config: RetryConfig): Long {
    var delay = config.initialDelayMs * Math.pow(config.backoffFactor, attempt.toDouble())
    delay = minOf(delay, config.maxDelayMs.toDouble())
    val jitter = Random.nextDouble(0.0, delay * 0.1)
    return (delay + jitter).toLong()
}

fun shouldRetry(statusCode: Int): Boolean = statusCode >= 500 || statusCode == 429
