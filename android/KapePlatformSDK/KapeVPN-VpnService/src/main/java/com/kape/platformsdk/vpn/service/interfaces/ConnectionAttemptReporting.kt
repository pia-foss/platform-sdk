package com.kape.platformsdk.vpn.service.interfaces

import com.kape.platformsdk.vpn.service.analytics.AttemptResult
import java.util.UUID

/**
 * Reports connect attempts on behalf of a [ConnectionController] — owned by the controller
 * rather than the run loop, so the attempt span matches its own connect work.
 */
interface ConnectionAttemptReporting {
    fun reportAttemptBegin(configuration: VpnConfiguration): UUID

    fun reportAttemptEnd(
        attemptId: UUID,
        result: AttemptResult,
        elapsedMs: Long,
        configuration: VpnConfiguration? = null,
    )
}
