package com.kape.platformsdk.vpn.service.interfaces

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class GeneratorRetryBackoffTest {
    @Test
    fun `delays follow a capped exponential backoff`() =
        runTest {
            val expectedSeconds = listOf(2, 4, 8, 16, 32, 60, 60, 60)

            expectedSeconds.forEachIndexed { index, seconds ->
                val before = testScheduler.currentTime
                DefaultGeneratorRetryBackoff.wait(index + 1)
                assertEquals(seconds * 1_000L, testScheduler.currentTime - before)
            }
        }

    @Test
    fun `a non-positive failure count is treated the same as the first failure`() =
        runTest {
            val before = testScheduler.currentTime
            DefaultGeneratorRetryBackoff.wait(0)
            assertEquals(2_000L, testScheduler.currentTime - before)
        }
}
