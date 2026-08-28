package com.kape.platformsdk.vpn.service.interfaces

import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

/**
 * Abstracts the backoff wait between configuration-generator retries.
 *
 * The default implementation delays for a capped exponential backoff.
 */
fun interface GeneratorRetryBackoff {
    suspend fun wait(consecutiveFailures: Int)
}

/** Capped exponential backoff: 2s, 4s, 8s, 16s, 32s, 60s, 60s, ... */
object DefaultGeneratorRetryBackoff : GeneratorRetryBackoff {
    override suspend fun wait(consecutiveFailures: Int) {
        val capped = consecutiveFailures.coerceIn(1, 6)
        val seconds = min(2 shl (capped - 1), 60)
        delay(seconds.seconds)
    }
}
