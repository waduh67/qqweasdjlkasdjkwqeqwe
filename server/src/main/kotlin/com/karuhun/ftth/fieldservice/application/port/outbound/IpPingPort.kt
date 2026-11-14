package com.karuhun.ftth.fieldservice.application.port.outbound

import com.karuhun.ftth.fieldservice.application.service.IpProbeResult

fun interface IpPingPort { fun ping(address: String): IpProbeResult }
