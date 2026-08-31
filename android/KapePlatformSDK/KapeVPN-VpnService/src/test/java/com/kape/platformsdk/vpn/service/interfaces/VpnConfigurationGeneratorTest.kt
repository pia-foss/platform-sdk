package com.kape.platformsdk.vpn.service.interfaces

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalCoroutinesApi::class)
class VpnConfigurationGeneratorTest {
    @Test
    fun `emits every configuration from a non-empty batch, in order`() =
        runTest {
            val generator = VpnConfigurationGenerator { listOf(TestVpnConfiguration("a"), TestVpnConfiguration("b")) }

            val emitted = generator.configurations().take(2).toList()

            assertEquals(listOf("a", "b"), emitted.map { (it as TestVpnConfiguration).name })
        }

    @Test
    fun `throws NoConfigurationException instead of looping when a batch is empty`() =
        runTest {
            var callCount = 0
            val generator =
                VpnConfigurationGenerator {
                    callCount++
                    emptyList()
                }

            assertFailsWith<NoConfigurationException> { generator.configurations().first() }
            // generateConfigurations() must not be called again before the exception propagates —
            // that immediate re-loop is exactly the tight-spin bug this throw prevents.
            assertEquals(1, callCount)
        }
}

private class TestVpnConfiguration(
    val name: String,
) : VpnConfiguration {
    override val vpnProtocolName: String = "test"
}
