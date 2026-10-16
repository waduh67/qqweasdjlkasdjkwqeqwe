package com.karuhun.ftth.fieldservice.application.port.inbound

import com.karuhun.ftth.fieldservice.application.service.GpsCaptureResult
import com.karuhun.ftth.fieldservice.domain.model.CaptureGpsCommand
import java.time.Instant

interface CaptureGpsUseCase {
    fun capture(command: CaptureGpsCommand, serverReceivedAt: Instant): GpsCaptureResult
}
