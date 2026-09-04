package com.duluin.ftth.fieldservice.application.port.inbound

import com.duluin.ftth.fieldservice.application.service.GpsCaptureResult
import com.duluin.ftth.fieldservice.domain.model.CaptureGpsCommand
import java.time.Instant

interface CaptureGpsUseCase {
    fun capture(command: CaptureGpsCommand, serverReceivedAt: Instant): GpsCaptureResult
}
