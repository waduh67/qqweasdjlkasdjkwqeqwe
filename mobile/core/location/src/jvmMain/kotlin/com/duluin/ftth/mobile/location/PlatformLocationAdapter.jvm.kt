package com.duluin.ftth.mobile.location

import com.duluin.ftth.mobile.domain.LocationPort
import com.duluin.ftth.mobile.domain.PermissionState

actual class PlatformLocationAdapter actual constructor() : LocationPort {
    override suspend fun permission() = PermissionState.Unknown
    override suspend fun current() = Result.failure<Pair<Double, Double>>(UnsupportedOperationException("JVM location adapter is a test boundary"))
}
