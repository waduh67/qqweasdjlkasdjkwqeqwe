package com.karuhun.ftth.mobile.location

import com.karuhun.ftth.mobile.domain.LocationPort
import com.karuhun.ftth.mobile.domain.PermissionState

actual class PlatformLocationAdapter actual constructor() : LocationPort {
    override suspend fun permission() = PermissionState.Unknown
    override suspend fun current() = Result.failure<Pair<Double, Double>>(UnsupportedOperationException("iOS location adapter is a platform boundary"))
}
